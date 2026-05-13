from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

import httpx

from ..config import Settings
from ..db import Database
from ..exceptions import SessionExpiredError, SyncAlreadyRunningError
from ..locks import FileLock
from ..providers.aa import AAProvider
from ..providers.ve import VEProvider
from ..schemas import (
    AcademicProgressData,
    CalendarData,
    CourseResourcesData,
    CoverageLevel,
    EmptyRoomData,
    ExamData,
    HomeworkData,
    ModuleEnvelope,
    ScoreData,
    StudentProfileData,
    TimetableData,
)
from ..session import SessionManager


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _clean_text_param(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


logger = logging.getLogger(__name__)


class SyncService:
    def __init__(self, settings: Settings, db: Database, session_manager: SessionManager) -> None:
        self.settings = settings
        self.db = db
        self.session_manager = session_manager

    async def run_sync(self) -> dict[str, Any]:
        lock = FileLock(self.settings.sync_lock_path, stale_after_seconds=60 * 60)
        try:
            lock.acquire()
        except FileExistsError as exc:
            raise SyncAlreadyRunningError("同步任务已在运行。") from exc

        started_at = utcnow_iso()
        run_id = self.db.create_sync_run(started_at)
        summary: dict[str, Any] = {}
        error_parts: list[str] = []
        try:
            async with self.session_manager.get_authenticated_client() as client:
                aa = AAProvider(client)
                ve = VEProvider(client)

                calendar = await self._fetch_and_store("calendar", ve.fetch_calendar, summary, error_parts)
                current_week = _clean_text_param(calendar.get("data", {}).get("current_week")) if calendar else None
                current_term = calendar.get("data", {}).get("current_term") if calendar else None

                await self._fetch_and_store("profile", aa.fetch_student_profile, summary, error_parts)
                await self._fetch_and_store("timetable", aa.fetch_timetable, summary, error_parts)
                await self._fetch_and_store("exams", aa.fetch_exams, summary, error_parts)
                await self._fetch_and_store("scores", aa.fetch_scores, summary, error_parts, ctype="lr")
                await self._fetch_and_store("history_scores", aa.fetch_history_scores, summary, error_parts)
                await self._fetch_and_store("academic_progress", aa.fetch_academic_progress, summary, error_parts)
                await self._fetch_and_store(
                    "homework",
                    ve.fetch_homework,
                    summary,
                    error_parts,
                    term=current_term,
                )
                await self._fetch_and_store(
                    "course_resources",
                    ve.fetch_course_resources,
                    summary,
                    error_parts,
                    term=current_term,
                )
                await self._fetch_and_store(
                    "empty_rooms",
                    aa.fetch_empty_rooms,
                    summary,
                    error_parts,
                    week=current_week,
                )
        except SessionExpiredError as exc:
            finished_at = utcnow_iso()
            self.db.finish_sync_run(
                run_id,
                status="session_expired",
                finished_at=finished_at,
                module_summary=summary,
                error_text=str(exc),
            )
            lock.release()
            raise
        except Exception as exc:
            finished_at = utcnow_iso()
            self.db.finish_sync_run(
                run_id,
                status="failed",
                finished_at=finished_at,
                module_summary=summary,
                error_text=str(exc),
            )
            lock.release()
            raise

        finished_at = utcnow_iso()
        status = "success" if not error_parts else "partial_failure"
        self.db.finish_sync_run(
            run_id,
            status=status,
            finished_at=finished_at,
            module_summary=summary,
            error_text=" | ".join(error_parts) if error_parts else None,
        )
        lock.release()
        return self.db.get_latest_sync_run() or {
            "status": status,
            "started_at": started_at,
            "finished_at": finished_at,
            "module_summary": summary,
            "error_text": " | ".join(error_parts) if error_parts else None,
        }

    async def _fetch_and_store(
        self,
        module_key: str,
        fetcher,
        summary: dict[str, Any],
        error_parts: list[str],
        **kwargs,
    ) -> dict[str, Any] | None:
        try:
            envelope: ModuleEnvelope = await fetcher(**kwargs)
            payload = envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")
            self.db.save_snapshot(module_key, payload)
            data = payload.get("data", {})
            item_count = len(
                data.get(
                    "items",
                    data.get(
                        "entries",
                        data.get(
                            "rooms",
                            data.get("resources", data.get("courses", data.get("fields", []))),
                        ),
                    ),
                )
            )
            if not item_count and isinstance(data.get("buckets"), list):
                item_count = len(data.get("buckets", []))
            summary[module_key] = {
                "status": "success",
                "coverage": payload.get("coverage"),
                "items": item_count,
            }
            return payload
        except Exception as exc:
            summary[module_key] = {"status": "error", "error": str(exc)}
            error_parts.append(f"{module_key}: {exc}")
            return None

    def get_sync_status(self) -> dict[str, Any]:
        latest = self.db.get_latest_sync_run()
        if latest is None:
            return {
                "status": "idle",
                "started_at": None,
                "finished_at": None,
                "module_summary": {},
                "error_text": None,
            }
        return latest

    def is_sync_running(self) -> bool:
        return self.settings.sync_lock_path.exists()

    async def run_sync_safely(self) -> None:
        try:
            await self.run_sync()
        except SyncAlreadyRunningError:
            return
        except SessionExpiredError:
            return
        except Exception:
            logger.exception("Background sync failed")

    def get_snapshot(self, module_key: str) -> dict[str, Any] | None:
        return self.db.get_snapshot(module_key)

    def get_snapshots(self) -> dict[str, dict[str, Any]]:
        return self.db.get_snapshots()

    def get_current_calendar_week(self) -> str | None:
        snapshot = self.get_snapshot("calendar")
        data = snapshot.get("data", {}) if snapshot else {}
        return _clean_text_param(data.get("current_week"))

    async def fetch_live_module(self, module_key: str, **params) -> dict[str, Any]:
        try:
            async with self.session_manager.get_authenticated_client() as client:
                aa = AAProvider(client)
                ve = VEProvider(client)
                if module_key == "profile":
                    envelope = await aa.fetch_student_profile()
                elif module_key == "timetable":
                    envelope = await aa.fetch_timetable()
                elif module_key == "exams":
                    envelope = await aa.fetch_exams(term=params.get("term"))
                elif module_key == "scores":
                    envelope = await aa.fetch_scores(term=params.get("term"), ctype=params.get("ctype"))
                elif module_key == "history_scores":
                    envelope = await aa.fetch_history_scores(term=params.get("term"))
                elif module_key == "academic_progress":
                    envelope = await aa.fetch_academic_progress()
                elif module_key == "calendar":
                    envelope = await ve.fetch_calendar(month=params.get("month"))
                elif module_key == "homework":
                    envelope = await ve.fetch_homework(term=params.get("term"))
                elif module_key == "course_resources":
                    envelope = await ve.fetch_course_resources(
                        term=params.get("term"),
                        course_id=params.get("course_id"),
                        folder_id=params.get("folder_id") or "0",
                        search=params.get("search"),
                    )
                elif module_key == "empty_rooms":
                    term = _clean_text_param(params.get("term"))
                    week = _clean_text_param(params.get("week"))
                    building = _clean_text_param(params.get("building"))
                    room = _clean_text_param(params.get("room"))
                    if week is None and term is None and building is None and room is None:
                        week = self.get_current_calendar_week()
                        if week is None:
                            try:
                                calendar = await ve.fetch_calendar()
                                week = _clean_text_param(calendar.data.current_week)
                            except (httpx.HTTPStatusError, httpx.RequestError) as exc:
                                logger.warning("Current teaching week lookup failed, falling back to AA default: %s", exc)
                    envelope = await aa.fetch_empty_rooms(
                        term=term,
                        week=week,
                        building=building,
                        room=room,
                    )
                else:
                    raise KeyError(f"Unsupported module: {module_key}")
            return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            logger.warning("Live fetch failed for module %s, fallback to cache/empty payload: %s", module_key, exc)
            snapshot = self.get_snapshot(module_key)
            if snapshot is not None:
                return snapshot
            fallback_params = dict(params)
            fallback_params.setdefault("fallback_reason", str(exc))
            fallback = self._build_empty_fallback(module_key, fallback_params)
            return fallback.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

    def _build_empty_fallback(self, module_key: str, params: dict[str, Any]) -> ModuleEnvelope:
        if module_key == "profile":
            return ModuleEnvelope(
                module="profile",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"fallback_reason": params.get("fallback_reason")},
                data=StudentProfileData(),
            )
        if module_key == "timetable":
            return ModuleEnvelope(
                module="timetable",
                source_system="aa",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={},
                data=TimetableData(),
            )
        if module_key == "exams":
            return ModuleEnvelope(
                module="exams",
                source_system="aa",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"term": params.get("term")},
                data=ExamData(current_term=params.get("term")),
            )
        if module_key == "scores":
            return ModuleEnvelope(
                module="scores",
                source_system="aa",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"term": params.get("term"), "ctype": params.get("ctype")},
                data=ScoreData(current_term=params.get("term")),
            )
        if module_key == "history_scores":
            return ModuleEnvelope(
                module="history_scores",
                source_system="aa",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"term": params.get("term"), "ctype": "ln"},
                data=ScoreData(current_term=params.get("term")),
            )
        if module_key == "academic_progress":
            return ModuleEnvelope(
                module="academic_progress",
                source_system="aa",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"fallback_reason": params.get("fallback_reason")},
                data=AcademicProgressData(),
            )
        if module_key == "calendar":
            return ModuleEnvelope(
                module="calendar",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"month": params.get("month"), "fallback_reason": params.get("fallback_reason")},
                data=CalendarData(month=params.get("month") or datetime.now().strftime("%Y-%m")),
            )
        if module_key == "homework":
            return ModuleEnvelope(
                module="homework",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"term": params.get("term"), "fallback_reason": params.get("fallback_reason")},
                data=HomeworkData(current_term=params.get("term")),
            )
        if module_key == "course_resources":
            return ModuleEnvelope(
                module="course_resources",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={
                    "term": params.get("term"),
                    "course_id": params.get("course_id"),
                    "folder_id": params.get("folder_id") or "0",
                    "search": params.get("search") or "",
                    "fallback_reason": params.get("fallback_reason"),
                },
                data=CourseResourcesData(
                    current_term=params.get("term"),
                    folder_id=str(params.get("folder_id") or "0"),
                ),
            )
        if module_key == "empty_rooms":
            return ModuleEnvelope(
                module="empty_rooms",
                source_system="aa",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={
                    "term": params.get("term"),
                    "week": params.get("week"),
                    "building": params.get("building"),
                    "room": params.get("room"),
                },
                data=EmptyRoomData(
                    query={
                        "term": params.get("term"),
                        "week": params.get("week"),
                        "building": params.get("building"),
                        "room": params.get("room"),
                    }
                ),
            )
        raise KeyError(f"Unsupported module: {module_key}")
