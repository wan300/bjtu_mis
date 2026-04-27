from __future__ import annotations

import json
import sqlite3
import threading
from pathlib import Path
from typing import Any

from .config import Settings


class Database:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.path = Path(settings.db_path)
        self._lock = threading.Lock()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path)
        connection.row_factory = sqlite3.Row
        return connection

    def init(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    status TEXT NOT NULL,
                    module_summary_json TEXT NOT NULL DEFAULT '{}',
                    error_text TEXT
                );

                CREATE TABLE IF NOT EXISTS module_snapshots (
                    module_key TEXT PRIMARY KEY,
                    synced_at TEXT NOT NULL,
                    source_system TEXT NOT NULL,
                    coverage TEXT NOT NULL,
                    source_params_json TEXT NOT NULL DEFAULT '{}',
                    payload_json TEXT NOT NULL
                );
                """
            )

    def create_sync_run(self, started_at: str) -> int:
        with self._lock, self._connect() as connection:
            cursor = connection.execute(
                "INSERT INTO sync_runs (started_at, status) VALUES (?, ?)",
                (started_at, "running"),
            )
            return int(cursor.lastrowid)

    def finish_sync_run(
        self,
        run_id: int,
        *,
        status: str,
        finished_at: str,
        module_summary: dict[str, Any],
        error_text: str | None = None,
    ) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                UPDATE sync_runs
                SET finished_at = ?, status = ?, module_summary_json = ?, error_text = ?
                WHERE id = ?
                """,
                (finished_at, status, json.dumps(module_summary, ensure_ascii=False), error_text, run_id),
            )

    def save_snapshot(self, module_key: str, payload: dict[str, Any]) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO module_snapshots (
                    module_key, synced_at, source_system, coverage, source_params_json, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(module_key) DO UPDATE SET
                    synced_at = excluded.synced_at,
                    source_system = excluded.source_system,
                    coverage = excluded.coverage,
                    source_params_json = excluded.source_params_json,
                    payload_json = excluded.payload_json
                """,
                (
                    module_key,
                    payload.get("synced_at"),
                    payload.get("source_system"),
                    payload.get("coverage"),
                    json.dumps(payload.get("source_params", {}), ensure_ascii=False),
                    json.dumps(payload, ensure_ascii=False),
                ),
            )

    def get_snapshot(self, module_key: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT payload_json FROM module_snapshots WHERE module_key = ?",
                (module_key,),
            ).fetchone()
        if row is None:
            return None
        return json.loads(row["payload_json"])

    def get_latest_sync_run(self) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT id, started_at, finished_at, status, module_summary_json, error_text
                FROM sync_runs
                ORDER BY id DESC
                LIMIT 1
                """
            ).fetchone()
        if row is None:
            return None
        return {
            "id": row["id"],
            "started_at": row["started_at"],
            "finished_at": row["finished_at"],
            "status": row["status"],
            "module_summary": json.loads(row["module_summary_json"] or "{}"),
            "error_text": row["error_text"],
        }
