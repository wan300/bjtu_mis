from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    root_dir: Path
    backend_dir: Path
    captures_dir: Path
    runtime_dir: Path
    logs_dir: Path
    profile_dir: Path
    db_path: Path
    session_state_path: Path
    login_credentials_path: Path
    sync_lock_path: Path
    login_lock_path: Path
    captcha_model_path: Path
    captcha_charset: str
    frontend_dist_dir: Path
    python_executable: Path
    mis_home_url: str
    user_agent: str
    request_timeout: float

    def ensure_directories(self) -> None:
        self.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.profile_dir.mkdir(parents=True, exist_ok=True)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    root_dir = Path(
        os.getenv("BJTU_MIS_ROOT_DIR", Path(__file__).resolve().parents[2])
    ).resolve()
    backend_dir = root_dir / "backend"
    captures_dir = Path(os.getenv("BJTU_MIS_CAPTURES_DIR", root_dir / "captures")).resolve()
    runtime_dir = Path(os.getenv("BJTU_MIS_RUNTIME_DIR", root_dir / "runtime")).resolve()
    logs_dir = captures_dir / "logs"
    profile_dir = Path(
        os.getenv("BJTU_MIS_PROFILE_DIR", captures_dir / "profile" / "default")
    ).resolve()
    frontend_dist_dir = Path(
        os.getenv("BJTU_MIS_FRONTEND_DIST", root_dir / "frontend" / "dist")
    ).resolve()
    settings = Settings(
        root_dir=root_dir,
        backend_dir=backend_dir,
        captures_dir=captures_dir,
        runtime_dir=runtime_dir,
        logs_dir=logs_dir,
        profile_dir=profile_dir,
        db_path=Path(os.getenv("BJTU_MIS_DB_PATH", runtime_dir / "bjtu_mis.sqlite3")).resolve(),
        session_state_path=Path(
            os.getenv("BJTU_MIS_SESSION_STATE_PATH", runtime_dir / "session_state.json")
        ).resolve(),
        login_credentials_path=Path(
            os.getenv("BJTU_MIS_LOGIN_CREDENTIALS_PATH", runtime_dir / "login_credentials.json")
        ).resolve(),
        sync_lock_path=Path(
            os.getenv("BJTU_MIS_SYNC_LOCK_PATH", runtime_dir / "sync.lock")
        ).resolve(),
        login_lock_path=Path(
            os.getenv("BJTU_MIS_LOGIN_LOCK_PATH", runtime_dir / "login_browser.lock")
        ).resolve(),
        captcha_model_path=Path(
            os.getenv("BJTU_MIS_CAPTCHA_MODEL_PATH", root_dir / "models" / "bjtu_captcha_crnn.pt")
        ).resolve(),
        captcha_charset=os.getenv("BJTU_MIS_CAPTCHA_CHARSET", " 0123456789+-*="),
        frontend_dist_dir=frontend_dist_dir,
        python_executable=Path(os.getenv("BJTU_MIS_PYTHON", sys.executable)).resolve(),
        mis_home_url=os.getenv("BJTU_MIS_HOME_URL", "https://mis.bjtu.edu.cn/home/"),
        user_agent=os.getenv(
            "BJTU_MIS_USER_AGENT",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
        ),
        request_timeout=float(os.getenv("BJTU_MIS_REQUEST_TIMEOUT", "30")),
    )
    settings.ensure_directories()
    return settings
