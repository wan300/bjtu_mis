from __future__ import annotations

import argparse
import asyncio

from .config import get_settings
from .db import Database
from .services.sync_service import SyncService
from .session import SessionManager


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="BJTU MIS utility commands")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("open-browser", help="Open the persistent login browser")
    subparsers.add_parser("sync-once", help="Run one sync immediately")
    return parser


async def run_command(command: str) -> int:
    settings = get_settings()
    db = Database(settings)
    db.init()
    session_manager = SessionManager(settings)
    sync_service = SyncService(settings, db, session_manager)

    if command == "open-browser":
        await session_manager.run_login_browser_session()
        return 0
    if command == "sync-once":
        await sync_service.run_sync()
        return 0
    raise ValueError(f"Unknown command: {command}")


def main() -> int:
    args = build_parser().parse_args()
    try:
        return asyncio.run(run_command(args.command))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
