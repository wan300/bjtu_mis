from __future__ import annotations

from typing import Any
from urllib.parse import quote

import httpx
from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from .config import Settings, get_settings
from .db import Database
from .exceptions import SessionExpiredError, SyncAlreadyRunningError
from .providers.ve import VEProvider
from .schemas import HomeworkSubmitResponse, InlineLoginRequest, SessionCaptchaResponse, SessionStatusResponse, SyncStatusResponse
from .services.sync_service import SyncService
from .session import SessionManager


def filter_homework_payload(payload: dict[str, Any], status: str) -> dict[str, Any]:
    payload = dict(payload)
    data = dict(payload.get("data", {}))
    payload["data"] = data
    if status == "all":
        return payload
    items = data.get("items", [])
    data["items"] = [item for item in items if item.get("status") == status]
    return payload


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    db = Database(settings)
    db.init()
    session_manager = SessionManager(settings)
    sync_service = SyncService(settings, db, session_manager)

    app = FastAPI(title="BJTU MIS Collector", version="1.0.0")
    app.state.settings = settings
    app.state.db = db
    app.state.session_manager = session_manager
    app.state.sync_service = sync_service

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    assets_dir = settings.frontend_dist_dir / "assets"
    if assets_dir.exists():
        app.mount("/assets", StaticFiles(directory=assets_dir), name="assets")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/favicon.ico", include_in_schema=False)
    async def favicon() -> Response:
        return Response(status_code=204)

    @app.post("/api/session/open-browser")
    async def open_browser(request: Request) -> dict[str, Any]:
        return request.app.state.session_manager.open_login_browser_background()

    @app.get("/api/session/status", response_model=SessionStatusResponse)
    async def session_status(request: Request) -> SessionStatusResponse:
        return await request.app.state.session_manager.validate_session()

    @app.get("/api/session/captcha", response_model=SessionCaptchaResponse)
    async def session_captcha(request: Request) -> SessionCaptchaResponse:
        try:
            return await request.app.state.session_manager.fetch_inline_login_captcha()
        except SessionExpiredError as exc:
            raise HTTPException(
                status_code=400,
                detail={"code": "SESSION_CAPTCHA_UNAVAILABLE", "message": str(exc)},
            ) from exc

    @app.post("/api/session/login-inline", response_model=SessionStatusResponse)
    async def session_login_inline(request: Request, payload: InlineLoginRequest) -> SessionStatusResponse:
        try:
            return await request.app.state.session_manager.login_with_inline_form(
                loginname=payload.loginname,
                password=payload.password,
                captcha=payload.captcha,
            )
        except SessionExpiredError as exc:
            raise HTTPException(
                status_code=401,
                detail={"code": "SESSION_EXPIRED", "message": str(exc)},
            ) from exc

    @app.post("/api/sync/run")
    async def sync_run(request: Request) -> dict[str, Any]:
        try:
            return await request.app.state.sync_service.run_sync()
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except SyncAlreadyRunningError as exc:
            raise HTTPException(status_code=409, detail={"code": "SYNC_ALREADY_RUNNING", "message": str(exc)}) from exc

    @app.get("/api/sync/status", response_model=SyncStatusResponse)
    async def sync_status(request: Request) -> SyncStatusResponse:
        return SyncStatusResponse(**request.app.state.sync_service.get_sync_status())

    @app.get("/api/modules/profile")
    async def get_profile(request: Request) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("profile")
        if payload is None:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("profile")
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/academic-progress")
    async def get_academic_progress(request: Request) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("academic_progress")
        if payload is None:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("academic_progress")
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/history-scores")
    async def get_history_scores(request: Request, term: str | None = None) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("history_scores")
        if payload is None or term:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("history_scores", term=term)
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/timetable")
    async def get_timetable(request: Request, term: str | None = None, week: str | None = None) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("timetable")
        if payload is None or term or week:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("timetable", term=term, week=week)
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/exams")
    async def get_exams(request: Request, term: str | None = None) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("exams")
        if payload is None or term:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("exams", term=term)
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/scores")
    async def get_scores(
        request: Request,
        term: str | None = None,
        ctype: str | None = Query(default=None, pattern=r"^(lr|ln|en|rm)$"),
    ) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("scores")
        if payload is None or term or ctype:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("scores", term=term, ctype=ctype)
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/calendar")
    async def get_calendar(request: Request, month: str | None = Query(default=None, pattern=r"^\d{4}-\d{2}$")) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("calendar")
        if payload is None or month:
            try:
                payload = await request.app.state.sync_service.fetch_live_module("calendar", month=month)
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/api/modules/homework")
    async def get_homework(
        request: Request,
        status: str = Query(default="all", pattern=r"^(all|open|done)$"),
    ) -> dict[str, Any]:
        try:
            payload = await request.app.state.sync_service.fetch_live_module("homework")
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return filter_homework_payload(payload, status)

    @app.post("/api/modules/homework/{homework_id}/submit", response_model=HomeworkSubmitResponse)
    async def submit_homework(
        request: Request,
        homework_id: int,
        course_id: int = Form(...),
        content: str = Form(default=""),
        files: list[UploadFile] | None = File(default=None),
    ) -> HomeworkSubmitResponse:
        uploaded_files: list[tuple[str, bytes, str | None]] = []
        for upload in files or []:
            body = await upload.read()
            if not upload.filename and not body:
                continue
            uploaded_files.append((upload.filename or "attachment", body, upload.content_type))

        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                result = await VEProvider(client).submit_homework(
                    homework_id=homework_id,
                    course_id=course_id,
                    content=content,
                    files=uploaded_files,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except ValueError as exc:
            raise HTTPException(status_code=404, detail={"code": "HOMEWORK_NOT_FOUND", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "HOMEWORK_SUBMIT_FAILED", "message": str(exc)},
            ) from exc
        return HomeworkSubmitResponse(**result)

    @app.get("/api/modules/course-resources")
    async def get_course_resources(
        request: Request,
        term: str | None = None,
        course_id: str | None = None,
        folder_id: str = "0",
        search: str | None = None,
    ) -> dict[str, Any]:
        try:
            return await request.app.state.sync_service.fetch_live_module(
                "course_resources",
                term=term,
                course_id=course_id,
                folder_id=folder_id,
                search=search,
            )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc

    @app.get("/api/modules/course-resources/download/{rp_id}")
    async def download_course_resource(
        request: Request,
        rp_id: str,
        filename: str | None = None,
    ) -> Response:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                content, content_type, upstream_disposition = await VEProvider(client).download_course_resource(rp_id)
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "COURSE_RESOURCE_DOWNLOAD_FAILED", "message": str(exc)},
            ) from exc

        headers: dict[str, str] = {}
        if filename:
            headers["Content-Disposition"] = f"attachment; filename*=UTF-8''{quote(filename, safe='')}"
        elif upstream_disposition:
            headers["Content-Disposition"] = upstream_disposition
        else:
            headers["Content-Disposition"] = "attachment"
        return Response(content=content, media_type=content_type, headers=headers)

    @app.get("/api/modules/empty-rooms")
    async def get_empty_rooms(
        request: Request,
        term: str | None = None,
        week: str | None = None,
        building: str | None = None,
        room: str | None = None,
    ) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("empty_rooms")
        if payload is None or any(value for value in (term, week, building, room)):
            try:
                payload = await request.app.state.sync_service.fetch_live_module(
                    "empty_rooms",
                    term=term,
                    week=week,
                    building=building,
                    room=room,
                )
            except SessionExpiredError as exc:
                raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return payload

    @app.get("/", include_in_schema=False)
    async def root() -> Any:
        index_file = settings.frontend_dist_dir / "index.html"
        if index_file.exists():
            return FileResponse(index_file)
        return JSONResponse(
            {
                "service": "BJTU MIS Collector",
                "frontend": "Build frontend with `npm install && npm run build` inside frontend/.",
            }
        )

    return app


app = create_app()
