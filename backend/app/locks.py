from __future__ import annotations

import json
import os
import time
from pathlib import Path


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

    def __enter__(self) -> "FileLock":
        self.acquire()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.release()
