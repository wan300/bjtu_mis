from __future__ import annotations

import asyncio
import base64
import json
from pathlib import Path
from types import MethodType

from app.captcha_solver import CaptchaSolveError, CaptchaSolveResult
from app.schemas import SessionCaptchaResponse, SessionState, SessionStatusResponse
from app.session import SessionManager

from test_api import make_settings


class FakeSolver:
    def __init__(self, results):
        self.results = list(results)
        self.calls = 0

    def solve(self, image_bytes: bytes) -> CaptchaSolveResult:
        self.calls += 1
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return CaptchaSolveResult(expression=result, answer="2")


def install_fake_captcha_fetch(manager: SessionManager) -> dict[str, int]:
    state = {"calls": 0}

    async def fake_fetch_inline_login_captcha(self):
        state["calls"] += 1
        encoded = base64.b64encode(f"captcha-{state['calls']}".encode("ascii")).decode("ascii")
        self._save_inline_login_state(
            {
                "login_url": "https://cas.bjtu.edu.cn/auth/login/",
                "next": "/home/",
                "csrfmiddlewaretoken": "csrf",
                "captcha_0": "captcha-key",
                "referer": "https://cas.bjtu.edu.cn/auth/login/",
                "cookies": [],
                "captcha_image_base64": encoded,
                "captcha_mime_type": "image/png",
                "created_at": "2026-05-10T00:00:00Z",
            }
        )
        return SessionCaptchaResponse(
            image_data_url=f"data:image/png;base64,{encoded}",
            fetched_at="2026-05-10T00:00:00Z",
        )

    manager.fetch_inline_login_captcha = MethodType(fake_fetch_inline_login_captcha, manager)
    return state


def test_login_auto_first_login_success_saves_credentials(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    manager = SessionManager(settings, captcha_solver=FakeSolver(["1+1="]))
    install_fake_captcha_fetch(manager)

    async def fake_login_with_inline_form(self, loginname, password, captcha, *, persist_credentials=True):
        assert (loginname, password, captcha) == ("20250001", "secret", "2")
        if persist_credentials:
            self._save_login_credentials(loginname, password)
        return SessionStatusResponse(state=SessionState.READY, detail="ok")

    manager.login_with_inline_form = MethodType(fake_login_with_inline_form, manager)

    result = asyncio.run(manager.login_with_auto_captcha("20250001", "secret"))

    assert result.status == "ready"
    assert result.attempts == 1
    credentials = json.loads(settings.login_credentials_path.read_text(encoding="utf-8"))
    assert credentials["loginname"] == "20250001"
    assert credentials["password"] == "secret"


def test_login_auto_returns_manual_required_after_two_solver_failures(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    manager = SessionManager(
        settings,
        captcha_solver=FakeSolver([CaptchaSolveError("bad captcha"), CaptchaSolveError("bad captcha")]),
    )
    fetch_state = install_fake_captcha_fetch(manager)

    result = asyncio.run(manager.login_with_auto_captcha("20250001", "secret"))

    assert result.status == "manual_required"
    assert result.attempts == 2
    assert result.captcha is not None
    assert fetch_state["calls"] == 3


def test_login_auto_saved_credentials_fail_after_three_attempts(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    settings.ensure_directories()
    settings.login_credentials_path.write_text(
        json.dumps({"loginname": "20250001", "password": "secret"}),
        encoding="utf-8",
    )
    manager = SessionManager(
        settings,
        captcha_solver=FakeSolver(
            [
                CaptchaSolveError("bad captcha"),
                CaptchaSolveError("bad captcha"),
                CaptchaSolveError("bad captcha"),
            ]
        ),
    )
    fetch_state = install_fake_captcha_fetch(manager)

    result = asyncio.run(manager.login_with_auto_captcha())

    assert result.status == "auto_failed"
    assert result.attempts == 3
    assert fetch_state["calls"] == 3
