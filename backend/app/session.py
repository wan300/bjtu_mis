from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import subprocess
import sys
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import AsyncIterator, Any
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup
from playwright.async_api import async_playwright

from .captcha_solver import CaptchaSolveError, CaptchaSolver
from .config import Settings
from .exceptions import SessionExpiredError
from .locks import FileLock
from .schemas import AutoLoginResponse, SessionCaptchaResponse, SessionState, SessionStatusResponse


AA_TIMETABLE_URL = "https://aa.bjtu.edu.cn/course_selection/courseselect/stuschedule/"
AA_NOTICE_URL = "https://aa.bjtu.edu.cn/notice/item/"
MIS_AA_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/10/"
BKSY_VE_BRIDGE_URL = "https://bksycenter.bjtu.edu.cn/NoMasterJumpPage.aspx?URL=jwcZhjx&FPC=page:jwcZhjx"
VE_QXKT_ENTRY_URL = "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"
CAS_LOGIN_PATH = "/auth/login/"


class SessionManager:
    def __init__(self, settings: Settings, captcha_solver: CaptchaSolver | None = None) -> None:
        self.settings = settings
        self.inline_login_state_path = self.settings.runtime_dir / "inline_login_state.json"
        self.captcha_solver = captcha_solver or CaptchaSolver(settings)

    def _now_iso(self) -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds")

    def _load_inline_login_state(self) -> dict[str, Any] | None:
        if not self.inline_login_state_path.exists():
            return None
        try:
            payload = json.loads(self.inline_login_state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        return payload if isinstance(payload, dict) else None

    def _save_inline_login_state(self, payload: dict[str, Any]) -> None:
        self.settings.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.inline_login_state_path.write_text(
            json.dumps(payload, ensure_ascii=False),
            encoding="utf-8",
        )

    def _clear_inline_login_state(self) -> None:
        self.inline_login_state_path.unlink(missing_ok=True)

    def _load_login_credentials(self) -> dict[str, str] | None:
        path = self.settings.login_credentials_path
        if not path.exists():
            return None
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(payload, dict):
            return None
        loginname = str(payload.get("loginname") or "").strip()
        password = str(payload.get("password") or "")
        if not loginname or not password:
            return None
        return {"loginname": loginname, "password": password}

    def _save_login_credentials(self, loginname: str, password: str) -> None:
        self.settings.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.settings.login_credentials_path.write_text(
            json.dumps(
                {
                    "loginname": loginname.strip(),
                    "password": password,
                    "saved_at": self._now_iso(),
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

    def has_login_credentials(self) -> bool:
        return self._load_login_credentials() is not None

    def _serialize_client_cookies(self, client: httpx.AsyncClient) -> list[dict[str, Any]]:
        cookies: list[dict[str, Any]] = []
        for cookie in client.cookies.jar:
            http_only = False
            if hasattr(cookie, "_rest") and isinstance(cookie._rest, dict):
                http_only = bool(cookie._rest.get("HttpOnly"))
            cookies.append(
                {
                    "name": cookie.name,
                    "value": cookie.value,
                    "domain": cookie.domain,
                    "path": cookie.path or "/",
                    "expires": cookie.expires if cookie.expires is not None else -1,
                    "httpOnly": http_only,
                    "secure": bool(cookie.secure),
                    "sameSite": "Lax",
                }
            )
        return cookies

    def _save_storage_state_from_client(self, client: httpx.AsyncClient) -> None:
        payload = {
            "cookies": self._serialize_client_cookies(client),
            "origins": [],
        }
        self.settings.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.settings.session_state_path.write_text(
            json.dumps(payload, ensure_ascii=False),
            encoding="utf-8",
        )

    def _load_storage_state(self) -> dict[str, Any] | None:
        if not self.settings.session_state_path.exists():
            return None
        return json.loads(self.settings.session_state_path.read_text(encoding="utf-8"))

    def _build_cookies(self, storage_state: dict[str, Any]) -> httpx.Cookies:
        cookies = httpx.Cookies()
        for cookie in storage_state.get("cookies", []):
            name = cookie.get("name")
            value = cookie.get("value")
            domain = cookie.get("domain")
            path = cookie.get("path") or "/"
            if name and value:
                cookies.set(name, value, domain=domain, path=path)
        return cookies

    def _is_cas_login_url(self, url: str) -> bool:
        parsed = urlparse(url)
        return parsed.hostname == "cas.bjtu.edu.cn" and parsed.path.startswith(CAS_LOGIN_PATH)

    def _has_ve_cookie(self, client: httpx.AsyncClient) -> bool:
        return any(cookie.domain == "123.121.147.7" and cookie.path.startswith("/ve") for cookie in client.cookies.jar)

    async def _open_cas_login_page(self, client: httpx.AsyncClient) -> httpx.Response:
        initial = await client.get(self.settings.mis_home_url, follow_redirects=False)
        location = initial.headers.get("Location")
        target = urljoin(str(initial.url), location) if location else self.settings.mis_home_url
        return await client.get(target)

    def _parse_inline_login_page(self, page_url: str, html: str) -> dict[str, str]:
        soup = BeautifulSoup(html, "html.parser")
        form = soup.find("form", attrs={"id": "login"}) or soup.find("form", attrs={"name": "login"}) or soup.find("form")
        if form is None:
            raise SessionExpiredError("未能解析统一认证登录表单，请稍后重试。")

        def get_input_value(name: str) -> str:
            node = form.find("input", attrs={"name": name})
            if node is None:
                raise SessionExpiredError(f"登录表单缺少字段 {name}。")
            value = (node.get("value") or "").strip()
            if not value:
                raise SessionExpiredError(f"登录表单字段 {name} 为空。")
            return value

        next_value = get_input_value("next")
        csrf_token = get_input_value("csrfmiddlewaretoken")
        captcha_0 = get_input_value("captcha_0")

        captcha_img = form.find("img", attrs={"class": "captcha"}) or form.find("img", attrs={"alt": "captcha"})
        if captcha_img is None:
            raise SessionExpiredError("未找到验证码图片，请刷新后重试。")

        captcha_src = (captcha_img.get("src") or "").strip()
        if not captcha_src:
            raise SessionExpiredError("验证码图片地址为空，请刷新后重试。")

        action = (form.get("action") or "").strip()
        login_url = urljoin(page_url, action or page_url)
        captcha_url = urljoin(page_url, captcha_src)
        return {
            "login_url": login_url,
            "captcha_url": captcha_url,
            "next": next_value,
            "csrfmiddlewaretoken": csrf_token,
            "captcha_0": captcha_0,
        }

    def _extract_inline_login_error(self, html: str) -> str | None:
        soup = BeautifulSoup(html, "html.parser")
        tip = soup.select_one(".tishi")
        if tip:
            content = tip.get_text(" ", strip=True)
            if content:
                return content

        full_text = soup.get_text(" ", strip=True)
        for marker in ("验证码", "用户名或密码", "登录失败", "密码错误", "账号"):
            if marker in full_text:
                return f"登录失败：{marker} 校验未通过。"
        return None

    async def fetch_inline_login_captcha(self) -> SessionCaptchaResponse:
        headers = {"User-Agent": self.settings.user_agent}
        try:
            async with httpx.AsyncClient(
                headers=headers,
                follow_redirects=True,
                timeout=self.settings.request_timeout,
            ) as client:
                login_page = await self._open_cas_login_page(client)
                login_page_url = str(login_page.url)
                if not self._is_cas_login_url(login_page_url):
                    raise SessionExpiredError("当前会话已处于登录状态，无需重复登录。")

                form_payload = self._parse_inline_login_page(login_page_url, login_page.text)
                captcha_response = await client.get(
                    form_payload["captcha_url"],
                    headers={"Referer": login_page_url},
                )
                captcha_response.raise_for_status()
                if not captcha_response.content:
                    raise SessionExpiredError("验证码加载失败，请刷新后重试。")

                mime_type = (captcha_response.headers.get("Content-Type") or "image/jpeg").split(";", 1)[0].strip()
                encoded = base64.b64encode(captcha_response.content).decode("ascii")
                image_data_url = f"data:{mime_type};base64,{encoded}"
                fetched_at = self._now_iso()

                self._save_inline_login_state(
                    {
                        "login_url": form_payload["login_url"],
                        "next": form_payload["next"],
                        "csrfmiddlewaretoken": form_payload["csrfmiddlewaretoken"],
                        "captcha_0": form_payload["captcha_0"],
                        "referer": login_page_url,
                        "cookies": self._serialize_client_cookies(client),
                        "captcha_image_base64": encoded,
                        "captcha_mime_type": mime_type,
                        "created_at": fetched_at,
                    }
                )

                return SessionCaptchaResponse(image_data_url=image_data_url, fetched_at=fetched_at)
        except httpx.HTTPError as exc:
            raise SessionExpiredError(f"验证码加载失败: {exc}") from exc
        except Exception as exc:
            raise SessionExpiredError(f"验证码加载失败: {exc}") from exc

    async def login_with_inline_form(
        self,
        loginname: str,
        password: str,
        captcha: str,
        *,
        persist_credentials: bool = True,
    ) -> SessionStatusResponse:
        inline_state = self._load_inline_login_state()
        if inline_state is None:
            raise SessionExpiredError("验证码已失效，请先刷新验证码。")

        required_fields = (
            "login_url",
            "next",
            "csrfmiddlewaretoken",
            "captcha_0",
            "referer",
            "cookies",
        )
        if not all(inline_state.get(key) for key in required_fields):
            self._clear_inline_login_state()
            raise SessionExpiredError("登录上下文已失效，请刷新验证码后重试。")

        headers = {"User-Agent": self.settings.user_agent}
        cookies = self._build_cookies({"cookies": inline_state.get("cookies", [])})
        try:
            async with httpx.AsyncClient(
                headers=headers,
                cookies=cookies,
                follow_redirects=True,
                timeout=self.settings.request_timeout,
            ) as client:
                response = await client.post(
                    inline_state["login_url"],
                    data={
                        "next": inline_state["next"],
                        "csrfmiddlewaretoken": inline_state["csrfmiddlewaretoken"],
                        "loginname": loginname.strip(),
                        "password": password,
                        "captcha_0": inline_state["captcha_0"],
                        "captcha_1": captcha.strip(),
                    },
                    headers={
                        "Referer": inline_state["referer"],
                        "Origin": "https://cas.bjtu.edu.cn",
                    },
                )

                final_url = str(response.url)
                if self._is_cas_login_url(final_url):
                    self._clear_inline_login_state()
                    detail = self._extract_inline_login_error(response.text)
                    raise SessionExpiredError(detail or "登录失败，请检查账号、密码和验证码。")

                aa_ready, aa_detail = await self._ensure_aa_session_ready(client)
                if not aa_ready:
                    self._clear_inline_login_state()
                    raise SessionExpiredError(aa_detail or "教学支撑平台会话未准备好，请重试。")

                # VE(智慧课程平台)链路偶发不稳定，这里不应阻塞 MIS/AA 登录结果。
                # 后续在按需访问 VE 模块时会再次尝试补建会话。
                await self._bootstrap_ve_session(client)

                self._save_storage_state_from_client(client)
                if persist_credentials:
                    self._save_login_credentials(loginname, password)
                self._clear_inline_login_state()
        except httpx.HTTPError as exc:
            self._clear_inline_login_state()
            raise SessionExpiredError(f"登录请求失败: {exc}") from exc

        return await self.validate_session()

    def _captcha_from_inline_state(self) -> bytes:
        inline_state = self._load_inline_login_state()
        encoded = inline_state.get("captcha_image_base64") if inline_state else None
        if not isinstance(encoded, str) or not encoded:
            raise CaptchaSolveError("验证码图片上下文缺失。")
        try:
            return base64.b64decode(encoded)
        except Exception as exc:
            raise CaptchaSolveError("验证码图片上下文损坏。") from exc

    async def login_with_auto_captcha(self, loginname: str | None = None, password: str | None = None) -> AutoLoginResponse:
        explicit_credentials = bool(loginname and password)
        credentials = (
            {"loginname": loginname.strip(), "password": password}
            if explicit_credentials and loginname is not None and password is not None
            else self._load_login_credentials()
        )
        if credentials is None:
            return AutoLoginResponse(status="auto_failed", message="未保存登录凭据，请手动输入学号和密码。")

        max_attempts = 2 if explicit_credentials else 3
        last_message = ""
        for attempt in range(1, max_attempts + 1):
            try:
                await self.fetch_inline_login_captcha()
                captcha_bytes = self._captcha_from_inline_state()
                solved = self.captcha_solver.solve(captcha_bytes)
                status = await self.login_with_inline_form(
                    credentials["loginname"],
                    credentials["password"],
                    solved.answer,
                    persist_credentials=True,
                )
                return AutoLoginResponse(
                    status="ready",
                    message=f"自动识别验证码算式 {solved.expression} 并完成登录。",
                    attempts=attempt,
                    session=status,
                )
            except CaptchaSolveError as exc:
                last_message = str(exc)
                self._clear_inline_login_state()
            except SessionExpiredError as exc:
                last_message = str(exc)

        if explicit_credentials:
            captcha = None
            try:
                captcha = await self.fetch_inline_login_captcha()
            except SessionExpiredError as exc:
                last_message = str(exc)
            return AutoLoginResponse(
                status="manual_required",
                message=f"自动识别连续失败，请手动输入验证码。{last_message}".strip(),
                attempts=max_attempts,
                captcha=captcha,
            )

        return AutoLoginResponse(
            status="auto_failed",
            message=f"自动登录连续失败 {max_attempts} 次：{last_message or '未知错误'}",
            attempts=max_attempts,
        )

    def _extract_aa_client_login_url(self, html: str) -> str | None:
        form_action = re.search(
            r'action=["\'](https://aa\.bjtu\.edu\.cn/client/login/[^"\']+)["\']',
            html,
            flags=re.IGNORECASE,
        )
        if form_action:
            return form_action.group(1)

        inline = re.search(
            r"https://aa\.bjtu\.edu\.cn/client/login/[^\s\"'<]+",
            html,
            flags=re.IGNORECASE,
        )
        if inline:
            return inline.group(0)
        return None

    def _is_aa_login_page(self, *, final_url: str, body_head: str) -> bool:
        if "/client/login/" in final_url and "/notice/item/" not in final_url:
            return True
        return "用户登录" in body_head and "教学支撑平台" in body_head

    async def _check_aa_session_state(self, client: httpx.AsyncClient) -> tuple[str, str | None]:
        try:
            response = await client.get(AA_TIMETABLE_URL)
        except httpx.HTTPError as exc:
            return "error", f"教学支撑平台会话校验失败: {exc}"

        if response.status_code >= 500:
            # 课表页偶发 5xx 时，回退到公告页判断当前是否是登录态问题。
            try:
                fallback = await client.get(AA_NOTICE_URL)
            except httpx.HTTPError as exc:
                return "error", f"教学支撑平台会话校验失败: {exc}"
            fallback_url = str(fallback.url)
            fallback_head = fallback.text[:4096]
            if self._is_aa_login_page(final_url=fallback_url, body_head=fallback_head):
                return "login_required", None
            return "service_unavailable", "教学支撑平台服务暂时不可用，稍后重试。"

        final_url = str(response.url)
        body_head = response.text[:4096]
        if self._is_aa_login_page(final_url=final_url, body_head=body_head):
            return "login_required", None
        return "ready", None

    async def _bootstrap_aa_session(self, client: httpx.AsyncClient) -> bool:
        try:
            bridge_response = await client.get(
                MIS_AA_BRIDGE_URL,
                headers={"Referer": self.settings.mis_home_url},
            )
        except httpx.HTTPError:
            return False

        if bridge_response.status_code >= 500:
            return False

        login_url = self._extract_aa_client_login_url(bridge_response.text)
        if not login_url:
            return False

        try:
            await client.get(login_url, headers={"Referer": "https://mis.bjtu.edu.cn/"})
        except httpx.HTTPError:
            return False
        return True

    async def _bootstrap_ve_session(self, client: httpx.AsyncClient) -> bool:
        try:
            response = await client.get(
                BKSY_VE_BRIDGE_URL,
                headers={"Referer": "https://bksy.bjtu.edu.cn/"},
            )
        except httpx.HTTPError:
            return False

        if response.status_code >= 500:
            return False

        final_url = str(response.url)
        if "Timeout.jsp" in final_url:
            return False
        if final_url == VE_QXKT_ENTRY_URL:
            return True
        return "123.121.147.7:88/ve/" in final_url

    async def _ensure_aa_session_ready(self, client: httpx.AsyncClient) -> tuple[bool, str | None]:
        state, detail = await self._check_aa_session_state(client)
        if state in {"ready", "service_unavailable"}:
            return True, detail
        if state == "error":
            return False, detail

        if state == "login_required":
            bootstrapped = await self._bootstrap_aa_session(client)
            if not bootstrapped:
                return False, "教学支撑平台未完成单点登录，请重新登录后重试。"

            state_after, detail_after = await self._check_aa_session_state(client)
            if state_after in {"ready", "service_unavailable"}:
                return True, detail_after
            if state_after == "login_required":
                return False, "教学支撑平台未完成单点登录，请重新登录后重试。"
            return False, detail_after

        return False, detail

    async def validate_session(self) -> SessionStatusResponse:
        storage_state = self._load_storage_state()
        if storage_state is None:
            return SessionStatusResponse(
                state=SessionState.WAITING_FOR_LOGIN,
                detail="未找到可用会话，请先在本页输入账号、密码与验证码完成登录。",
            )

        cookies = self._build_cookies(storage_state)
        headers = {"User-Agent": self.settings.user_agent}
        try:
            async with httpx.AsyncClient(
                cookies=cookies,
                headers=headers,
                follow_redirects=True,
                timeout=self.settings.request_timeout,
            ) as client:
                response = await client.get(self.settings.mis_home_url)
                aa_ready, aa_detail = await self._ensure_aa_session_ready(client)
        except httpx.HTTPError as exc:
            return SessionStatusResponse(state=SessionState.EXPIRED, detail=f"会话校验失败: {exc}")

        final_url = str(response.url)
        body = response.text[:4096]
        if any(marker in final_url for marker in ("/auth/sso/", "cas.bjtu.edu.cn/auth/login")):
            return SessionStatusResponse(state=SessionState.EXPIRED, detail="会话已失效，请重新登录。")
        if "loginname" in body or "统一身份认证" in body:
            return SessionStatusResponse(state=SessionState.EXPIRED, detail="当前页面仍停留在登录态。")

        if not aa_ready:
            return SessionStatusResponse(state=SessionState.EXPIRED, detail=aa_detail)

        if aa_detail:
            return SessionStatusResponse(state=SessionState.READY, detail=f"会话可用；{aa_detail}")
        return SessionStatusResponse(state=SessionState.READY, detail="会话可用。")

    def _is_pid_running(self, pid: int) -> bool:
        if pid <= 0:
            return False
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        except OSError:
            return False
        return True

    def _read_login_lock_payload(self) -> dict[str, Any] | None:
        try:
            payload = json.loads(self.settings.login_lock_path.read_text(encoding="utf-8"))
            return payload if isinstance(payload, dict) else None
        except (OSError, json.JSONDecodeError):
            return None

    def _clear_stale_login_lock(self) -> bool:
        lock_path = self.settings.login_lock_path
        if not lock_path.exists():
            return False

        payload = self._read_login_lock_payload()
        pid = payload.get("pid") if payload else None
        if isinstance(pid, int) and self._is_pid_running(pid):
            return False

        if payload is None:
            try:
                age = time.time() - lock_path.stat().st_mtime
            except OSError:
                age = 0
            if age < 15:
                return False

        lock_path.unlink(missing_ok=True)
        return True

    def open_login_browser_background(self) -> dict[str, Any]:
        stale_lock_cleared = False
        if self.settings.login_lock_path.exists():
            stale_lock_cleared = self._clear_stale_login_lock()
            if not stale_lock_cleared:
                return {"launched": False, "already_running": True}

        env = os.environ.copy()
        existing_pythonpath = env.get("PYTHONPATH", "")
        env["PYTHONPATH"] = str(self.settings.backend_dir) + (
            os.pathsep + existing_pythonpath if existing_pythonpath else ""
        )
        creationflags = 0
        if os.name == "nt":
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS

        process = subprocess.Popen(
            [str(self.settings.python_executable), "-m", "app.cli", "open-browser"],
            cwd=self.settings.backend_dir,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creationflags,
        )

        # Wait briefly for either lock creation or immediate process failure.
        for _ in range(10):
            if self.settings.login_lock_path.exists():
                break
            if process.poll() is not None:
                break
            time.sleep(0.2)

        if process.poll() is not None:
            return {
                "launched": False,
                "already_running": False,
                "message": "登录浏览器启动失败，请查看后端终端日志。",
            }

        result: dict[str, Any] = {"launched": True, "pid": process.pid}
        if stale_lock_cleared:
            result["recovered_stale_lock"] = True
        return result

    @asynccontextmanager
    async def get_authenticated_client(self) -> AsyncIterator[httpx.AsyncClient]:
        status = await self.validate_session()
        if status.state != SessionState.READY:
            raise SessionExpiredError(status.detail or "会话未准备好。")

        storage_state = self._load_storage_state()
        if storage_state is None:
            raise SessionExpiredError("会话状态文件缺失。")

        headers = {
            "User-Agent": self.settings.user_agent,
            "Accept": "text/html,application/json,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        }
        async with httpx.AsyncClient(
            cookies=self._build_cookies(storage_state),
            headers=headers,
            follow_redirects=True,
            timeout=self.settings.request_timeout,
        ) as client:
            aa_ready, aa_detail = await self._ensure_aa_session_ready(client)
            if not aa_ready:
                raise SessionExpiredError(aa_detail or "教学支撑平台未完成单点登录。")
            if not self._has_ve_cookie(client):
                ve_ready = await self._bootstrap_ve_session(client)
                if ve_ready:
                    self._save_storage_state_from_client(client)
            yield client

    async def run_login_browser_session(self) -> None:
        self.settings.ensure_directories()
        lock = FileLock(self.settings.login_lock_path, stale_after_seconds=60 * 60 * 8)
        lock.acquire()
        closed = asyncio.Event()
        try:
            async with async_playwright() as playwright:
                context = await playwright.chromium.launch_persistent_context(
                    str(self.settings.profile_dir),
                    headless=False,
                    viewport={"width": 1440, "height": 900},
                )
                context.on("close", lambda: closed.set())
                page = context.pages[0] if context.pages else await context.new_page()
                await page.goto(self.settings.mis_home_url)
                bridge_page = await context.new_page()
                await bridge_page.goto(MIS_AA_BRIDGE_URL)
                while not closed.is_set():
                    await context.storage_state(path=str(self.settings.session_state_path))
                    try:
                        await asyncio.wait_for(closed.wait(), timeout=5)
                    except TimeoutError:
                        continue
        finally:
            lock.release()
