from __future__ import annotations

from typing import Any

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from .config import Settings, get_settings
from .db import Database
from .exceptions import SessionExpiredError, SyncAlreadyRunningError
from .schemas import InlineLoginRequest, SessionCaptchaResponse, SessionStatusResponse, SyncStatusResponse
from .services.sync_service import SyncService
from .session import SessionManager


def filter_homework_payload(payload: dict[str, Any], status: str) -> dict[str, Any]:
    if status == "all":
        return payload
    data = payload.get("data", {})
    items = data.get("items", [])
    data["items"] = [item for item in items if item.get("status") == status]
    payload["data"] = data
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
