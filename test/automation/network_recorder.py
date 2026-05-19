from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import sys
import threading
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, TYPE_CHECKING
from urllib.parse import parse_qsl, parse_qs, urlencode, urlparse, urlunparse

if TYPE_CHECKING:
    from playwright.async_api import Request, Response

try:
    from playwright.async_api import async_playwright
except ImportError:
    async_playwright = None

PROJECT_ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Settings:
    root_dir: Path
    captures_dir: Path
    runtime_dir: Path
    logs_dir: Path
    profile_dir: Path
    session_state_path: Path
    login_lock_path: Path
    mis_home_url: str
    user_agent: str

    def ensure_directories(self) -> None:
        self.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.profile_dir.mkdir(parents=True, exist_ok=True)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    root_dir = Path(os.getenv("BJTU_MIS_ROOT_DIR", PROJECT_ROOT)).resolve()
    captures_dir = Path(os.getenv("BJTU_MIS_CAPTURES_DIR", root_dir / "captures")).resolve()
    runtime_dir = Path(os.getenv("BJTU_MIS_RUNTIME_DIR", root_dir / "runtime")).resolve()
    logs_dir = captures_dir / "logs"
    profile_dir = Path(
        os.getenv("BJTU_MIS_PROFILE_DIR", captures_dir / "profile" / "default")
    ).resolve()
    settings = Settings(
        root_dir=root_dir,
        captures_dir=captures_dir,
        runtime_dir=runtime_dir,
        logs_dir=logs_dir,
        profile_dir=profile_dir,
        session_state_path=Path(
            os.getenv("BJTU_MIS_SESSION_STATE_PATH", runtime_dir / "session_state.json")
        ).resolve(),
        login_lock_path=Path(
            os.getenv("BJTU_MIS_LOGIN_LOCK_PATH", runtime_dir / "login_browser.lock")
        ).resolve(),
        mis_home_url=os.getenv("BJTU_MIS_HOME_URL", "https://mis.bjtu.edu.cn/home/"),
        user_agent=os.getenv(
            "BJTU_MIS_USER_AGENT",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
        ),
    )
    settings.ensure_directories()
    return settings


class FileLock:
    def __init__(self, path: Path, stale_after_seconds: int | None = None) -> None:
        self.path = path
        self.stale_after_seconds = stale_after_seconds
        self._fd: int | None = None

    def acquire(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists() and self.stale_after_seconds is not None:
            age = time.time() - self.path.stat().st_mtime
            if age > self.stale_after_seconds:
                self.path.unlink(missing_ok=True)

        self._fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_RDWR)
        payload = json.dumps({"pid": os.getpid(), "created_at": time.time()})
        os.write(self._fd, payload.encode("utf-8"))

    def release(self) -> None:
        if self._fd is not None:
            os.close(self._fd)
            self._fd = None
        self.path.unlink(missing_ok=True)


DEFAULT_MAIL_KEYWORDS = [
    "mail",
    "email",
    "message",
    "letter",
    "inbox",
    "outbox",
    "sent",
    "draft",
    "send",
    "receive",
    "attach",
    "attachment",
    "upload",
    "delete",
    "remove",
    "black",
    "block",
    "邮件",
    "邮箱",
    "收件",
    "发件",
    "写信",
    "草稿",
    "附件",
    "删除",
    "黑名单",
    "拉黑",
    "站内信",
    "消息",
]

OPERATION_KEYWORDS = {
    "list": ["list", "query", "page", "inbox", "receive", "mailbox", "收件", "列表"],
    "detail": ["detail", "view", "read", "content", "item", "详情", "阅读", "查看"],
    "send": ["send", "compose", "write", "save", "insert", "add", "reply", "forward", "发送", "写信", "回复", "转发"],
    "attachment": ["attach", "attachment", "upload", "file", "附件", "上传"],
    "delete": ["delete", "remove", "del", "trash", "删除", "回收站"],
    "blocklist": ["black", "block", "reject", "refuse", "ban", "黑名单", "拉黑", "拒收"],
    "contacts": ["contact", "receiver", "recipient", "user", "person", "address", "联系人", "收件人"],
}

TEXTUAL_CONTENT_HINTS = (
    "application/json",
    "application/javascript",
    "application/x-www-form-urlencoded",
    "text/",
    "xml",
    "html",
)

PAGE_STRUCTURE_SCRIPT = r"""
() => {
  const limit = (value, max = 160) => String(value || "").replace(/\s+/g, " ").trim().slice(0, max);
  const visible = (el) => {
    const style = window.getComputedStyle(el);
    const rect = el.getBoundingClientRect();
    return style.visibility !== "hidden" && style.display !== "none" && rect.width > 0 && rect.height > 0;
  };
  const selectorFor = (el) => {
    if (!el || !el.tagName) return "";
    if (el.id) return `${el.tagName.toLowerCase()}#${CSS.escape(el.id)}`;
    const parts = [];
    let node = el;
    while (node && node.nodeType === Node.ELEMENT_NODE && parts.length < 4) {
      let part = node.tagName.toLowerCase();
      const testId = node.getAttribute("data-testid") || node.getAttribute("data-test");
      const name = node.getAttribute("name");
      if (testId) part += `[data-testid="${CSS.escape(testId)}"]`;
      else if (name) part += `[name="${CSS.escape(name)}"]`;
      else if (node.classList.length) part += `.${CSS.escape(Array.from(node.classList)[0])}`;
      parts.unshift(part);
      node = node.parentElement;
    }
    return parts.join(" > ");
  };
  const describe = (el) => ({
    selector: selectorFor(el),
    tag: el.tagName.toLowerCase(),
    type: el.getAttribute("type") || "",
    role: el.getAttribute("role") || "",
    name: el.getAttribute("name") || "",
    id: el.id || "",
    text: limit(el.innerText || el.textContent || el.getAttribute("aria-label") || el.getAttribute("title") || ""),
    href: el.href || "",
    action: el.action || "",
    method: el.method || "",
    visible: visible(el),
  });
  const take = (selector, max) => Array.from(document.querySelectorAll(selector)).slice(0, max).map(describe);
  const forms = Array.from(document.forms).slice(0, 30).map((form) => ({
    ...describe(form),
    fields: Array.from(form.querySelectorAll("input, textarea, select, button")).slice(0, 80).map((field) => ({
      ...describe(field),
      placeholder: limit(field.getAttribute("placeholder") || ""),
      value_length: "value" in field ? String(field.value || "").length : 0,
      checked: "checked" in field ? Boolean(field.checked) : undefined,
    })),
  }));
  return {
    url: location.href,
    title: document.title,
    viewport: { width: window.innerWidth, height: window.innerHeight },
    headings: take("h1,h2,h3", 80),
    forms,
    links: take("a[href]", 160),
    buttons: take("button,input[type=button],input[type=submit],[role=button]", 160),
    inputs: Array.from(document.querySelectorAll("input,textarea,select")).slice(0, 160).map((field) => ({
      ...describe(field),
      placeholder: limit(field.placeholder || field.getAttribute("placeholder") || ""),
      value_length: "value" in field ? String(field.value || "").length : 0,
    })),
    tables: Array.from(document.querySelectorAll("table")).slice(0, 30).map((table) => ({
      ...describe(table),
      headers: Array.from(table.querySelectorAll("th")).slice(0, 40).map((th) => limit(th.innerText || th.textContent || "")),
      rows: table.querySelectorAll("tr").length,
    })),
    frames: Array.from(document.querySelectorAll("iframe,frame")).slice(0, 30).map(describe),
  };
}
"""

OPERATION_RECORDER_SCRIPT = r"""
(() => {
  if (window.__bjtuOperationRecorderInstalled) return;
  window.__bjtuOperationRecorderInstalled = true;
  const limit = (value, max = 160) => String(value || "").replace(/\s+/g, " ").trim().slice(0, max);
  const selectorFor = (el) => {
    if (!el || !el.tagName) return "";
    if (el.id) return `${el.tagName.toLowerCase()}#${CSS.escape(el.id)}`;
    const parts = [];
    let node = el;
    while (node && node.nodeType === Node.ELEMENT_NODE && parts.length < 4) {
      let part = node.tagName.toLowerCase();
      const testId = node.getAttribute("data-testid") || node.getAttribute("data-test");
      const name = node.getAttribute("name");
      if (testId) part += `[data-testid="${CSS.escape(testId)}"]`;
      else if (name) part += `[name="${CSS.escape(name)}"]`;
      else if (node.classList.length) part += `.${CSS.escape(Array.from(node.classList)[0])}`;
      parts.unshift(part);
      node = node.parentElement;
    }
    return parts.join(" > ");
  };
  const describe = (el) => {
    if (!el || !el.tagName) return {};
    const payload = {
      selector: selectorFor(el),
      tag: el.tagName.toLowerCase(),
      type: el.getAttribute("type") || "",
      role: el.getAttribute("role") || "",
      name: el.getAttribute("name") || "",
      id: el.id || "",
      text: limit(el.innerText || el.textContent || el.getAttribute("aria-label") || el.getAttribute("title") || ""),
      href: el.href || "",
      action: el.action || "",
      method: el.method || "",
    };
    if ("value" in el) payload.value_length = String(el.value || "").length;
    if ("checked" in el) payload.checked = Boolean(el.checked);
    return payload;
  };
  const emit = (operation, target, extra = {}) => {
    const record = {
      operation,
      url: location.href,
      title: document.title,
      target: describe(target),
      extra,
      viewport: { width: window.innerWidth, height: window.innerHeight },
    };
    if (typeof window.__bjtuRecordOperation === "function") {
      window.__bjtuRecordOperation(record).catch(() => {});
    }
  };
  document.addEventListener("click", (event) => {
    emit("click", event.target, { x: event.clientX, y: event.clientY, button: event.button });
  }, true);
  document.addEventListener("change", (event) => {
    emit("change", event.target);
  }, true);
  document.addEventListener("submit", (event) => {
    emit("submit", event.target, {
      action: event.target && event.target.action,
      method: event.target && event.target.method,
    });
  }, true);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Enter") emit("enter", document.activeElement || event.target);
  }, true);
})();
"""

INERT_PAGE_URLS = {
    "",
    "about:blank",
    "chrome://newtab/",
    "chrome://new-tab-page/",
    "edge://newtab/",
}

SENSITIVE_NAME_PATTERN = re.compile(
    r"(^sid$|password|passwd|pwd|captcha|cookie|authorization|session|token|ticket|secret|credential|csrf)",
    re.IGNORECASE,
)


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def timestamp_slug() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def is_inert_page_url(url: str | None) -> bool:
    return (url or "").lower() in INERT_PAGE_URLS


async def select_recording_page(
    context: Any,
    start_url: str | None,
    fallback_url: str,
    *,
    open_fallback_url: bool = False,
) -> tuple[Any, str]:
    if start_url:
        page = await context.new_page()
        await page.goto(start_url)
        return page, "opened_explicit_start_url"

    pages = list(context.pages)
    for page in pages:
        if not is_inert_page_url(getattr(page, "url", None)):
            return page, "preserved_existing_page"

    page = pages[0] if pages else await context.new_page()
    if open_fallback_url:
        await page.goto(fallback_url)
        return page, "opened_default_start_url"
    if pages:
        return page, "preserved_inert_page"
    return page, "opened_blank_page"


def is_sensitive_name(name: str) -> bool:
    return bool(SENSITIVE_NAME_PATTERN.search(name or ""))


def truncate_text(value: str, limit: int) -> tuple[str, bool]:
    if len(value) <= limit:
        return value, False
    return value[:limit], True


def sanitize_scalar(name: str, value: Any) -> Any:
    if is_sensitive_name(name):
        text = "" if value is None else str(value)
        return f"<redacted:{len(text)} chars>"
    if isinstance(value, str):
        return truncate_text(value, 4096)[0]
    return value


def sanitize_mapping(mapping: dict[str, Any]) -> dict[str, Any]:
    sanitized: dict[str, Any] = {}
    for key, value in mapping.items():
        if isinstance(value, list):
            sanitized[key] = [sanitize_scalar(key, item) for item in value]
        elif isinstance(value, dict):
            sanitized[key] = sanitize_mapping(value)
        else:
            sanitized[key] = sanitize_scalar(key, value)
    return sanitized


def sanitize_headers(headers: dict[str, str]) -> dict[str, str]:
    return {key: sanitize_scalar(key, value) for key, value in headers.items()}


def sanitize_url(url: str) -> str:
    parsed = urlparse(url)
    query = [
        (key, str(sanitize_scalar(key, value)))
        for key, value in parse_qsl(parsed.query, keep_blank_values=True)
    ]
    return urlunparse(parsed._replace(query=urlencode(query, doseq=True)))


def safe_frame_url(request: Request) -> str | None:
    try:
        return request.frame.url
    except Exception:
        return None


def decode_bytes(body: bytes, content_type: str) -> str:
    charset_match = re.search(r"charset=([\w.-]+)", content_type, flags=re.IGNORECASE)
    encodings = [charset_match.group(1)] if charset_match else []
    encodings.extend(["utf-8", "gb18030", "latin-1"])
    for encoding in encodings:
        try:
            return body.decode(encoding)
        except (LookupError, UnicodeDecodeError):
            continue
    return body.decode("utf-8", errors="replace")


def is_textual_content(content_type: str) -> bool:
    lowered = (content_type or "").lower()
    return any(hint in lowered for hint in TEXTUAL_CONTENT_HINTS)


def json_shape(value: Any, depth: int = 3) -> Any:
    if depth <= 0:
        return type(value).__name__
    if isinstance(value, dict):
        return {str(key): json_shape(item, depth - 1) for key, item in list(value.items())[:40]}
    if isinstance(value, list):
        if not value:
            return []
        return [json_shape(value[0], depth - 1)]
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "bool"
    if isinstance(value, int):
        return "int"
    if isinstance(value, float):
        return "float"
    return "str"


def summarize_multipart(raw: str, content_type: str) -> dict[str, Any]:
    boundary_match = re.search(r"boundary=(?P<boundary>[^;]+)", content_type, flags=re.IGNORECASE)
    summary: dict[str, Any] = {"type": "multipart/form-data", "parts": []}
    if not boundary_match:
        summary["note"] = "missing boundary"
        return summary

    boundary = boundary_match.group("boundary").strip().strip('"')
    parts = raw.split(f"--{boundary}")
    for part in parts[:100]:
        if "Content-Disposition" not in part:
            continue
        header_block, _, body = part.partition("\r\n\r\n")
        name_match = re.search(r'name="([^"]+)"', header_block)
        filename_match = re.search(r'filename="([^"]*)"', header_block)
        field_name = name_match.group(1) if name_match else ""
        filename = filename_match.group(1) if filename_match else None
        item: dict[str, Any] = {
            "name": field_name,
            "filename": filename,
            "headers": truncate_text(header_block.strip(), 1000)[0],
        }
        if filename is not None:
            item["value"] = "<file content omitted>"
        else:
            value = body.rsplit("\r\n", 1)[0].strip()
            item["value"] = sanitize_scalar(field_name, truncate_text(value, 1000)[0])
        summary["parts"].append(item)
    return summary


def request_body_summary(request: Request, max_request_chars: int) -> dict[str, Any] | None:
    if request.method.upper() not in {"POST", "PUT", "PATCH", "DELETE"}:
        return None

    content_type = request.headers.get("content-type", "")
    raw = request.post_data or ""
    if not raw:
        return {"content_type": content_type, "empty": True}

    truncated_raw, truncated = truncate_text(raw, max_request_chars)
    summary: dict[str, Any] = {
        "content_type": content_type,
        "size_chars": len(raw),
        "truncated": truncated,
    }
    lowered = content_type.lower()

    if "multipart/form-data" in lowered:
        summary["multipart"] = summarize_multipart(truncated_raw, content_type)
        return summary

    if "application/json" in lowered or truncated_raw.lstrip().startswith(("{", "[")):
        try:
            parsed = json.loads(truncated_raw)
            summary["json"] = sanitize_mapping(parsed) if isinstance(parsed, dict) else parsed
            summary["shape"] = json_shape(parsed)
        except json.JSONDecodeError:
            summary["text"] = truncated_raw
        return summary

    if "application/x-www-form-urlencoded" in lowered:
        parsed_form = parse_qs(truncated_raw, keep_blank_values=True)
        summary["form"] = sanitize_mapping(parsed_form)
        return summary

    summary["text"] = truncated_raw
    return summary


async def response_body_summary(response: Response, max_response_bytes: int) -> dict[str, Any]:
    content_type = response.headers.get("content-type", "")
    summary: dict[str, Any] = {"content_type": content_type}
    try:
        body = await response.body()
    except Exception as exc:
        summary["body_error"] = str(exc)
        return summary

    summary["size_bytes"] = len(body)
    summary["sha256"] = hashlib.sha256(body).hexdigest()
    truncated = len(body) > max_response_bytes
    sample = body[:max_response_bytes]
    summary["truncated"] = truncated

    if not is_textual_content(content_type):
        summary["body_kind"] = "binary"
        return summary

    text = decode_bytes(sample, content_type)
    summary["text"] = text
    if "application/json" in content_type.lower() or text.lstrip().startswith(("{", "[")):
        try:
            parsed = json.loads(text)
            summary["json"] = sanitize_mapping(parsed) if isinstance(parsed, dict) else parsed
            summary["shape"] = json_shape(parsed)
        except json.JSONDecodeError:
            pass
    return summary


class JsonlWriter:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._file = self.path.open("a", encoding="utf-8")
        self._lock = threading.Lock()

    def write(self, payload: dict[str, Any]) -> None:
        payload.setdefault("ts", utcnow_iso())
        line = json.dumps(payload, ensure_ascii=False, sort_keys=True)
        with self._lock:
            self._file.write(f"{line}\n")
            self._file.flush()

    def close(self) -> None:
        with self._lock:
            self._file.close()


async def capture_page_structure(
    page: Any,
    writer: JsonlWriter,
    *,
    reason: str,
    label: str | None = None,
) -> None:
    try:
        structure = await page.evaluate(PAGE_STRUCTURE_SCRIPT)
    except Exception as exc:
        writer.write(
            {
                "event": "page_structure_error",
                "reason": reason,
                "label": label,
                "error": str(exc),
                "page_url": sanitize_url(getattr(page, "url", "")),
            }
        )
        return

    writer.write(
        {
            "event": "page_structure",
            "reason": reason,
            "label": label,
            "page_url": sanitize_url(getattr(page, "url", "")),
            "structure": structure,
        }
    )


@dataclass
class CaptureResult:
    output_dir: Path
    network_log: Path
    page_structure_log: Path
    operation_log: Path
    manifest_path: Path


async def record_mail_traffic(
    settings: Settings,
    *,
    start_url: str | None = None,
    label: str = "mail",
    max_response_bytes: int = 512 * 1024,
    max_request_chars: int = 128 * 1024,
    open_fallback_url: bool = False,
    close_browser: bool = True,
) -> CaptureResult:
    if async_playwright is None:
        raise RuntimeError("Playwright is required for recording. Install it with: python -m pip install playwright")

    run_id = f"{label}_{timestamp_slug()}"
    output_dir = settings.logs_dir / run_id
    network_log = output_dir / "network.jsonl"
    page_structure_log = output_dir / "page_structure.jsonl"
    operation_log = output_dir / "operation_log.jsonl"
    manifest_path = output_dir / "manifest.json"
    writer = JsonlWriter(network_log)
    structure_writer = JsonlWriter(page_structure_log)
    operation_writer = JsonlWriter(operation_log)
    lock = FileLock(settings.login_lock_path, stale_after_seconds=60 * 60 * 8)
    lock.acquire()
    closed = asyncio.Event()
    browser_closed = asyncio.Event()
    loop = asyncio.get_running_loop()
    request_ids: dict[int, int] = {}
    request_counter = 0
    recording_enabled = True
    recording_page: Any | None = None
    pending_structure_tasks: set[asyncio.Task[Any]] = set()
    last_structure_capture: dict[tuple[int, str], float] = {}

    def next_request_id() -> int:
        nonlocal request_counter
        request_counter += 1
        return request_counter

    def schedule_page_structure_capture(
        page: Any,
        reason: str,
        label_text: str | None = None,
        *,
        min_interval_seconds: float = 1.0,
        delay_seconds: float = 0.0,
    ) -> None:
        if not recording_enabled:
            return
        now = time.monotonic()
        key = (id(page), reason)
        if min_interval_seconds > 0 and now - last_structure_capture.get(key, 0) < min_interval_seconds:
            return
        last_structure_capture[key] = now

        async def run_capture() -> None:
            if delay_seconds > 0:
                await asyncio.sleep(delay_seconds)
            if not recording_enabled:
                return
            await capture_page_structure(
                page,
                structure_writer,
                reason=reason,
                label=label_text,
            )

        task = asyncio.create_task(run_capture())
        pending_structure_tasks.add(task)
        task.add_done_callback(pending_structure_tasks.discard)

    def log_marker(label_text: str) -> None:
        payload = {"event": "marker", "label": label_text}
        writer.write(payload)
        operation_writer.write(payload)
        if recording_page is not None:
            loop.call_soon_threadsafe(
                schedule_page_structure_capture,
                recording_page,
                "marker",
                label_text,
            )

    def marker_thread() -> None:
        print("")
        print("录制已启动。建议按操作阶段输入标记，例如：")
        print("  MARK 收件箱列表")
        print("  MARK 查看邮件详情")
        print("  MARK 发送带附件邮件")
        print("  MARK 删除邮件")
        print("  MARK 拉黑发件人")
        print("输入 q 并回车可结束录制；直接关闭浏览器也会结束。")
        print("")
        for line in sys.stdin:
            text = line.strip()
            if not text:
                continue
            if text.lower() in {"q", "quit", "exit"}:
                loop.call_soon_threadsafe(closed.set)
                break
            if text.lower().startswith("mark "):
                text = text[5:].strip()
            log_marker(text)

    manifest = {
        "run_id": run_id,
        "started_at": utcnow_iso(),
        "start_url": start_url,
        "fallback_url": settings.mis_home_url,
        "open_fallback_url": open_fallback_url,
        "close_browser": close_browser,
        "page_policy": (
            "Preserve an existing browser page; blank/new-tab pages are not navigated "
            "unless --open-fallback-url is provided."
        ),
        "network_log": str(network_log),
        "page_structure_log": str(page_structure_log),
        "operation_log": str(operation_log),
        "profile_dir": str(settings.profile_dir),
        "max_response_bytes": max_response_bytes,
        "max_request_chars": max_request_chars,
        "notes": [
            "Cookies and common token/password fields are redacted.",
            "Use dummy mail subject/body/attachment names during capture if possible.",
            "Do not share captures/logs outside the local machine.",
        ],
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    writer.write({"event": "capture_started", **manifest})

    try:
        async with async_playwright() as playwright:
            context = await playwright.chromium.launch_persistent_context(
                str(settings.profile_dir),
                headless=False,
                viewport={"width": 1440, "height": 900},
                user_agent=settings.user_agent,
            )

            def on_context_close() -> None:
                closed.set()
                browser_closed.set()

            context.on("close", on_context_close)

            attached_page_ids: set[int] = set()

            async def log_browser_operation(source: dict[str, Any], payload: dict[str, Any]) -> None:
                if not recording_enabled:
                    return
                operation = dict(payload) if isinstance(payload, dict) else {"raw": str(payload)}
                operation["event"] = "browser_operation"
                if operation.get("url"):
                    operation["url"] = sanitize_url(str(operation["url"]))
                operation_writer.write(operation)
                page = source.get("page") if isinstance(source, dict) else None
                if page is not None:
                    schedule_page_structure_capture(
                        page,
                        "interaction",
                        str(operation.get("operation") or ""),
                        min_interval_seconds=1.5,
                        delay_seconds=0.35,
                    )

            def on_frame_navigated(page: Any, frame: Any) -> None:
                try:
                    if frame != page.main_frame:
                        return
                except Exception:
                    return
                payload = {
                    "event": "navigation",
                    "url": sanitize_url(getattr(frame, "url", "") or getattr(page, "url", "")),
                    "title": "",
                }
                operation_writer.write(payload)
                schedule_page_structure_capture(
                    page,
                    "navigation",
                    min_interval_seconds=1.0,
                    delay_seconds=0.5,
                )

            def attach_page_events(page: Any) -> None:
                page_id = id(page)
                if page_id in attached_page_ids:
                    return
                attached_page_ids.add(page_id)
                page.on(
                    "domcontentloaded",
                    lambda: schedule_page_structure_capture(
                        page,
                        "domcontentloaded",
                        min_interval_seconds=1.0,
                        delay_seconds=0.1,
                    ),
                )
                page.on(
                    "load",
                    lambda: schedule_page_structure_capture(
                        page,
                        "load",
                        min_interval_seconds=1.0,
                        delay_seconds=0.1,
                    ),
                )
                page.on("framenavigated", lambda frame: on_frame_navigated(page, frame))

            async def install_operation_recorder(page: Any) -> None:
                try:
                    await page.evaluate(OPERATION_RECORDER_SCRIPT)
                except Exception as exc:
                    operation_writer.write(
                        {
                            "event": "operation_recorder_install_error",
                            "page_url": sanitize_url(getattr(page, "url", "")),
                            "error": str(exc),
                        }
                    )

            await context.expose_binding("__bjtuRecordOperation", log_browser_operation)
            await context.add_init_script(script=OPERATION_RECORDER_SCRIPT)
            for existing_page in context.pages:
                attach_page_events(existing_page)
            context.on("page", attach_page_events)

            def on_request(request: Request) -> None:
                if not recording_enabled:
                    return
                request_id = next_request_id()
                request_ids[id(request)] = request_id
                parsed_url = urlparse(request.url)
                writer.write(
                    {
                        "event": "request",
                        "request_id": request_id,
                        "method": request.method,
                        "url": sanitize_url(request.url),
                        "host": parsed_url.hostname,
                        "path": parsed_url.path,
                        "query": sanitize_mapping(dict(parse_qsl(parsed_url.query, keep_blank_values=True))),
                        "resource_type": request.resource_type,
                        "frame_url": sanitize_url(safe_frame_url(request) or ""),
                        "headers": sanitize_headers(dict(request.headers)),
                        "body": request_body_summary(request, max_request_chars),
                    }
                )

            async def log_response(response: Response) -> None:
                if not recording_enabled:
                    return
                request = response.request
                request_id = request_ids.get(id(request))
                if request_id is None:
                    request_id = next_request_id()
                    request_ids[id(request)] = request_id
                body = await response_body_summary(response, max_response_bytes)
                if not recording_enabled:
                    return
                writer.write(
                    {
                        "event": "response",
                        "request_id": request_id,
                        "url": sanitize_url(response.url),
                        "status": response.status,
                        "status_text": response.status_text,
                        "headers": sanitize_headers(dict(response.headers)),
                        "body": body,
                    }
                )

            def on_response(response: Response) -> None:
                asyncio.create_task(log_response(response))

            def on_request_failed(request: Request) -> None:
                if not recording_enabled:
                    return
                request_id = request_ids.get(id(request))
                failure = request.failure
                if callable(failure):
                    failure = failure()
                writer.write(
                    {
                        "event": "request_failed",
                        "request_id": request_id,
                        "method": request.method,
                        "url": sanitize_url(request.url),
                        "failure": failure,
                    }
                )

            context.on("request", on_request)
            context.on("response", on_response)
            context.on("requestfailed", on_request_failed)

            if sys.stdin and sys.stdin.isatty():
                threading.Thread(target=marker_thread, daemon=True).start()

            page, page_action = await select_recording_page(
                context,
                start_url,
                settings.mis_home_url,
                open_fallback_url=open_fallback_url,
            )
            recording_page = page
            for existing_page in context.pages:
                attach_page_events(existing_page)
                await install_operation_recorder(existing_page)
            manifest["initial_page_url"] = getattr(page, "url", "")
            manifest["initial_page_action"] = page_action
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
            writer.write(
                {
                    "event": "capture_page_ready",
                    "page_action": page_action,
                    "page_url": sanitize_url(getattr(page, "url", "")),
                }
            )
            operation_writer.write(
                {
                    "event": "capture_page_ready",
                    "page_action": page_action,
                    "page_url": sanitize_url(getattr(page, "url", "")),
                }
            )
            schedule_page_structure_capture(
                page,
                "capture_page_ready",
                page_action,
                min_interval_seconds=0,
            )

            while not closed.is_set():
                await context.storage_state(path=str(settings.session_state_path))
                try:
                    await asyncio.wait_for(closed.wait(), timeout=2)
                except TimeoutError:
                    continue

            recording_enabled = False
            await context.storage_state(path=str(settings.session_state_path))
            if close_browser:
                if not browser_closed.is_set():
                    await context.close()
            elif not browser_closed.is_set():
                print("Recording stopped. Close the browser window manually to finish the command.")
                await browser_closed.wait()
    finally:
        recording_enabled = False
        for task in list(pending_structure_tasks):
            task.cancel()
        if pending_structure_tasks:
            await asyncio.gather(*pending_structure_tasks, return_exceptions=True)
        finished_payload = {"event": "capture_finished", "finished_at": utcnow_iso()}
        writer.write(finished_payload)
        operation_writer.write(finished_payload)
        structure_writer.write(finished_payload)
        writer.close()
        operation_writer.close()
        structure_writer.close()
        lock.release()

    manifest["finished_at"] = utcnow_iso()
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return CaptureResult(
        output_dir=output_dir,
        network_log=network_log,
        page_structure_log=page_structure_log,
        operation_log=operation_log,
        manifest_path=manifest_path,
    )


def resolve_capture_log(settings: Settings, capture: str | None, label: str = "mail") -> Path:
    if capture:
        path = Path(capture).resolve()
        if path.is_dir():
            candidate = path / "network.jsonl"
            if candidate.exists():
                return candidate
        return path

    pattern = f"{label}_*/network.jsonl"
    matches = sorted(settings.logs_dir.glob(pattern), key=lambda item: item.stat().st_mtime, reverse=True)
    if not matches:
        raise FileNotFoundError(f"No capture logs found under {settings.logs_dir}")
    return matches[0]


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8-sig").split("\n"):
        if not line.strip():
            continue
        events.append(json.loads(line))
    return events


def event_text(event: dict[str, Any]) -> str:
    parts = [
        str(event.get("method") or ""),
        str(event.get("url") or ""),
        str(event.get("path") or ""),
        json.dumps(event.get("query") or {}, ensure_ascii=False),
        json.dumps(event.get("body") or {}, ensure_ascii=False),
    ]
    response_body = event.get("response", {}).get("body") if isinstance(event.get("response"), dict) else None
    if response_body:
        parts.append(json.dumps(response_body, ensure_ascii=False))
    return " ".join(parts).lower()


def infer_operation(text: str, method: str, content_type: str = "") -> str:
    lowered = f"{method} {content_type} {text}".lower()
    if "multipart/form-data" in lowered or re.search(r"(?<![a-z0-9])(upload|attach|attachment)(?![a-z0-9])", lowered):
        return "attachment"
    if re.search(r"(blacklist|blocklist|black|block|reject|refuse|黑名单|拉黑|拒收)", lowered):
        return "blocklist"
    if re.search(r"(delete|remove|trash|/del|删除|回收站)", lowered):
        return "delete"
    if re.search(r"(?<![a-z0-9])(send|compose|write|reply|forward)(?![a-z0-9])|发送|写信|回复|转发", lowered):
        return "send"
    if re.search(r"(detail|view|read|content|详情|阅读|查看)", lowered):
        return "detail"
    scores = {
        operation: sum(1 for keyword in keywords if keyword.lower() in lowered)
        for operation, keywords in OPERATION_KEYWORDS.items()
    }
    if "multipart/form-data" in lowered:
        scores["attachment"] += 3
    if not scores:
        return "unknown"
    operation, score = max(scores.items(), key=lambda item: item[1])
    return operation if score > 0 else "unknown"


def normalized_endpoint(request_event: dict[str, Any]) -> str:
    method = request_event.get("method") or "GET"
    parsed = urlparse(request_event.get("url") or "")
    query = request_event.get("query") or {}
    operation_params = []
    for key in ("method", "action", "cmd", "op", "type", "module", "func", "do"):
        if query.get(key) not in (None, ""):
            operation_params.append((key, str(query[key])))
    suffix = f"?{urlencode(operation_params)}" if operation_params else ""
    return f"{method} {parsed.scheme}://{parsed.netloc}{parsed.path}{suffix}"


def extract_request_fields(request_event: dict[str, Any]) -> list[str]:
    fields = set((request_event.get("query") or {}).keys())
    body = request_event.get("body") or {}
    if isinstance(body.get("json"), dict):
        fields.update(body["json"].keys())
    if isinstance(body.get("form"), dict):
        fields.update(body["form"].keys())
    multipart = body.get("multipart") or {}
    for part in multipart.get("parts", []):
        if part.get("name"):
            fields.add(part["name"])
    return sorted(fields)


def response_shape(response_event: dict[str, Any] | None) -> str:
    if not response_event:
        return ""
    body = response_event.get("body") or {}
    if body.get("shape") is not None:
        return json.dumps(body["shape"], ensure_ascii=False)[:500]
    content_type = body.get("content_type") or ""
    size = body.get("size_bytes")
    if body.get("body_kind") == "binary":
        return f"binary {content_type} {size or 0} bytes"
    if body.get("text"):
        return f"text {content_type} {size or 0} bytes"
    return content_type


def combine_transactions(events: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_id: dict[int, dict[str, Any]] = {}
    markers: list[dict[str, Any]] = []
    for event in events:
        event_type = event.get("event")
        if event_type == "marker":
            markers.append(event)
            continue
        request_id = event.get("request_id")
        if not isinstance(request_id, int):
            continue
        tx = by_id.setdefault(request_id, {"request_id": request_id})
        if event_type == "request":
            tx["request"] = event
        elif event_type == "response":
            tx["response"] = event
        elif event_type == "request_failed":
            tx["failure"] = event
    transactions = sorted(by_id.values(), key=lambda item: item["request_id"])
    return transactions, markers


def transaction_text(tx: dict[str, Any]) -> str:
    return json.dumps(tx, ensure_ascii=False).lower()


def operation_source_text(tx: dict[str, Any]) -> str:
    request_event = tx.get("request") or {}
    response_event = tx.get("response") or {}
    body = request_event.get("body") or {}
    response_body = response_event.get("body") or {}
    parts = [
        str(request_event.get("method") or ""),
        str(request_event.get("url") or ""),
        str(request_event.get("path") or ""),
        " ".join(str(key) for key in (request_event.get("query") or {}).keys()),
        " ".join(str(value) for value in (request_event.get("query") or {}).values()),
        json.dumps(body, ensure_ascii=False),
    ]
    if response_body.get("text"):
        parts.append(str(response_body["text"])[:2000])
    if response_body.get("shape"):
        parts.append(json.dumps(response_body["shape"], ensure_ascii=False))
    return " ".join(parts)


def is_candidate_transaction(tx: dict[str, Any], keywords: list[str]) -> bool:
    text = transaction_text(tx)
    return any(keyword.lower() in text for keyword in keywords)


def marker_segments(events: list[dict[str, Any]], transactions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    marker_positions: list[tuple[int, dict[str, Any]]] = []
    for index, event in enumerate(events):
        if event.get("event") == "marker":
            marker_positions.append((index, event))
    if not marker_positions:
        return []

    event_indexes_by_request: dict[int, int] = {}
    for index, event in enumerate(events):
        request_id = event.get("request_id")
        if isinstance(request_id, int) and event.get("event") == "request":
            event_indexes_by_request[request_id] = index

    segments: list[dict[str, Any]] = []
    for marker_index, (start_index, marker) in enumerate(marker_positions):
        end_index = marker_positions[marker_index + 1][0] if marker_index + 1 < len(marker_positions) else len(events)
        txs = [
            tx
            for tx in transactions
            if start_index < event_indexes_by_request.get(tx["request_id"], -1) < end_index
        ]
        endpoint_counter = Counter(
            normalized_endpoint(tx["request"])
            for tx in txs
            if tx.get("request") and tx["request"].get("resource_type") in {"xhr", "fetch", "document"}
        )
        segments.append(
            {
                "label": marker.get("label") or "",
                "request_count": len(txs),
                "top_endpoints": endpoint_counter.most_common(10),
            }
        )
    return segments


def analyze_capture(
    network_log: Path,
    *,
    output_dir: Path | None = None,
    keywords: list[str] | None = None,
) -> dict[str, Any]:
    keywords = list(dict.fromkeys(DEFAULT_MAIL_KEYWORDS + (keywords or [])))
    events = load_jsonl(network_log)
    transactions, markers = combine_transactions(events)
    candidates = [tx for tx in transactions if tx.get("request") and is_candidate_transaction(tx, keywords)]
    endpoint_groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for tx in candidates:
        endpoint_groups[normalized_endpoint(tx["request"])].append(tx)

    endpoints: list[dict[str, Any]] = []
    coverage: dict[str, set[str]] = {key: set() for key in OPERATION_KEYWORDS}
    for endpoint, txs in sorted(endpoint_groups.items(), key=lambda item: (-len(item[1]), item[0])):
        sample = txs[0]
        request_event = sample["request"]
        response_event = sample.get("response")
        text = operation_source_text(sample)
        content_type = ((response_event or {}).get("body") or {}).get("content_type") or ""
        operation = infer_operation(text, request_event.get("method") or "", content_type)
        if operation in coverage:
            coverage[operation].add(endpoint)
        endpoints.append(
            {
                "endpoint": endpoint,
                "operation": operation,
                "count": len(txs),
                "statuses": sorted(
                    {
                        tx.get("response", {}).get("status")
                        for tx in txs
                        if tx.get("response", {}).get("status") is not None
                    }
                ),
                "resource_types": sorted({tx["request"].get("resource_type") for tx in txs if tx.get("request")}),
                "request_fields": extract_request_fields(request_event),
                "response_shape": response_shape(response_event),
                "sample_request_id": sample["request_id"],
                "sample_url": request_event.get("url"),
            }
        )

    result: dict[str, Any] = {
        "network_log": str(network_log),
        "analyzed_at": utcnow_iso(),
        "total_events": len(events),
        "total_transactions": len(transactions),
        "candidate_transactions": len(candidates),
        "markers": markers,
        "segments": marker_segments(events, transactions),
        "endpoints": endpoints,
        "coverage": {
            key: {
                "observed": bool(value),
                "endpoints": sorted(value),
            }
            for key, value in coverage.items()
        },
    }

    output_dir = output_dir or network_log.parent
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "mail_interface_analysis.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "mail_interface_analysis.md").write_text(
        render_analysis_markdown(result),
        encoding="utf-8",
    )
    return result


def render_analysis_markdown(result: dict[str, Any]) -> str:
    lines = [
        "# 邮件接口抓取分析",
        "",
        f"- 日志：`{result['network_log']}`",
        f"- 分析时间：`{result['analyzed_at']}`",
        f"- 总请求事务：`{result['total_transactions']}`",
        f"- 邮件候选事务：`{result['candidate_transactions']}`",
        "",
        "## 功能覆盖",
        "",
        "| 功能 | 状态 | 候选接口 |",
        "| --- | --- | --- |",
    ]
    labels = {
        "list": "收件箱/列表",
        "detail": "详情/阅读",
        "send": "发送/回复/转发",
        "attachment": "附件上传",
        "delete": "删除/回收站",
        "blocklist": "拉黑/黑名单",
        "contacts": "联系人/收件人查询",
    }
    for key, label in labels.items():
        item = result["coverage"].get(key, {})
        endpoints = "<br>".join(f"`{endpoint}`" for endpoint in item.get("endpoints", [])[:8])
        lines.append(f"| {label} | {'已观察' if item.get('observed') else '未观察'} | {endpoints or '-'} |")

    if result.get("segments"):
        lines.extend(["", "## 操作标记分段", "", "| 标记 | 请求数 | 高频接口 |", "| --- | ---: | --- |"])
        for segment in result["segments"]:
            top = "<br>".join(f"`{endpoint}` x{count}" for endpoint, count in segment["top_endpoints"])
            lines.append(f"| {segment['label']} | {segment['request_count']} | {top or '-'} |")

    lines.extend(
        [
            "",
            "## 候选接口",
            "",
            "| 操作推断 | 次数 | 状态码 | 请求字段 | 响应结构 | 接口 | 样本ID |",
            "| --- | ---: | --- | --- | --- | --- | ---: |",
        ]
    )
    for endpoint in result["endpoints"]:
        fields = ", ".join(f"`{field}`" for field in endpoint["request_fields"][:30]) or "-"
        statuses = ", ".join(str(status) for status in endpoint["statuses"]) or "-"
        shape = endpoint["response_shape"].replace("|", "\\|") if endpoint["response_shape"] else "-"
        lines.append(
            "| "
            f"{endpoint['operation']} | "
            f"{endpoint['count']} | "
            f"{statuses} | "
            f"{fields} | "
            f"`{shape}` | "
            f"`{endpoint['endpoint']}` | "
            f"{endpoint['sample_request_id']} |"
        )

    lines.extend(
        [
            "",
            "## 复核清单",
            "",
            "- 用样本请求 ID 回到 `network.jsonl` 查看完整请求/响应片段。",
            "- 每个功能至少录制一次成功路径和一次失败/边界路径。",
            "- 附件上传需要确认上传接口、业务提交接口以及两者之间返回字段的关联关系。",
            "- 删除、拉黑等破坏性动作建议使用测试邮件或可恢复数据。",
        ]
    )
    return "\n".join(lines) + "\n"
