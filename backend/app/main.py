from __future__ import annotations

import asyncio
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
from .providers.coremail import CoremailError, CoremailProvider
from .providers.aa import AAProvider
from .providers.ve import VEProvider
from .schemas import (
    AutoLoginRequest,
    AutoLoginResponse,
    CourseSelectionAttemptRequest,
    CourseSelectionAttemptResult,
    CourseSelectionDropRequest,
    CourseSelectionReplaceRequest,
    HomeworkSubmitResponse,
    InlineLoginRequest,
    MailAttachmentUploadResponse,
    MailComposeResponse,
    MailDeleteRequest,
    MailDeleteResponse,
    MailDraftSaveRequest,
    MailSendRequest,
    SessionCaptchaResponse,
    SessionStatusResponse,
    SyncStatusResponse,
)
from .services.sync_service import SyncService, utcnow_iso
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


def _clean_text_param(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _empty_rooms_payload_week(payload: dict[str, Any] | None) -> str | None:
    if not payload:
        return None
    source_params = payload.get("source_params") or {}
    data = payload.get("data") or {}
    query = data.get("query") or {}
    return _clean_text_param(source_params.get("week") or query.get("week"))


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

    @app.post("/api/session/login-auto", response_model=AutoLoginResponse)
    async def session_login_auto(request: Request, payload: AutoLoginRequest) -> AutoLoginResponse:
        try:
            return await request.app.state.session_manager.login_with_auto_captcha(
                loginname=payload.loginname,
                password=payload.password,
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

    @app.post("/api/sync/start")
    async def sync_start(request: Request) -> dict[str, Any]:
        sync_service = request.app.state.sync_service
        if sync_service.is_sync_running():
            raise HTTPException(
                status_code=409,
                detail={"code": "SYNC_ALREADY_RUNNING", "message": "同步任务已在运行。"},
            )
        task = asyncio.create_task(sync_service.run_sync_safely())
        request.app.state.sync_task = task
        return {"started": True}

    @app.get("/api/sync/status", response_model=SyncStatusResponse)
    async def sync_status(request: Request) -> SyncStatusResponse:
        return SyncStatusResponse(**request.app.state.sync_service.get_sync_status())

    @app.get("/api/modules/snapshots")
    async def module_snapshots(request: Request) -> dict[str, Any]:
        return {"snapshots": request.app.state.sync_service.get_snapshots()}

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

    @app.get("/api/modules/course-selection")
    async def get_course_selection(request: Request) -> dict[str, Any]:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                envelope = await AAProvider(client).fetch_course_selection()
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "COURSE_SELECTION_FETCH_FAILED", "message": str(exc)},
            ) from exc
        return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

    @app.post("/api/modules/course-selection/select", response_model=CourseSelectionAttemptResult)
    async def select_course(request: Request, payload: CourseSelectionAttemptRequest) -> CourseSelectionAttemptResult:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await AAProvider(client).attempt_course_selection(
                    course_key=payload.course_key,
                    course_name=payload.course_name,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "COURSE_SELECTION_SUBMIT_FAILED", "message": str(exc)},
            ) from exc

    @app.post("/api/modules/course-selection/drop", response_model=CourseSelectionAttemptResult)
    async def drop_course(request: Request, payload: CourseSelectionDropRequest) -> CourseSelectionAttemptResult:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await AAProvider(client).drop_course_selection(
                    course_key=payload.course_key,
                    course_name=payload.course_name,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "COURSE_SELECTION_DROP_FAILED", "message": str(exc)},
            ) from exc

    @app.post("/api/modules/course-selection/replace", response_model=CourseSelectionAttemptResult)
    async def replace_course(request: Request, payload: CourseSelectionReplaceRequest) -> CourseSelectionAttemptResult:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await AAProvider(client).replace_course_selection(
                    target_course_key=payload.target_course_key,
                    target_course_name=payload.target_course_name,
                    drop_course_key=payload.drop_course_key,
                    drop_course_name=payload.drop_course_name,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "COURSE_SELECTION_REPLACE_FAILED", "message": str(exc)},
            ) from exc

    @app.post("/api/modules/course-selection/captcha", response_model=CourseSelectionAttemptResult)
    async def submit_course_selection_captcha(
        request: Request,
        payload: CourseSelectionAttemptRequest,
    ) -> CourseSelectionAttemptResult:
        if not payload.captcha_challenge_id or not payload.captcha:
            raise HTTPException(
                status_code=400,
                detail={"code": "COURSE_SELECTION_CAPTCHA_REQUIRED", "message": "缺少验证码上下文或验证码。"},
            )
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await AAProvider(client).submit_course_selection_captcha(
                    payload.captcha_challenge_id,
                    payload.captcha,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "COURSE_SELECTION_CAPTCHA_FAILED", "message": str(exc)},
            ) from exc

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

    @app.get("/api/modules/score-detail")
    async def get_score_detail(request: Request, path: str = Query(min_length=1)) -> dict[str, Any]:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                envelope = await AAProvider(client).fetch_score_detail(path)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_SCORE_DETAIL_PATH", "message": str(exc)}) from exc
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

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

    @app.get("/api/modules/mail/folders")
    async def get_mail_folders(request: Request) -> dict[str, Any]:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                envelope = await CoremailProvider(client).fetch_folders()
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_FETCH_FAILED", "message": str(exc)},
            ) from exc
        return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

    @app.get("/api/modules/mail/messages")
    async def get_mail_messages(
        request: Request,
        folder_id: str = "1",
        start: int = Query(default=0, ge=0),
        limit: int = Query(default=20, ge=1, le=100),
        mboxa: str = "",
    ) -> dict[str, Any]:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                envelope = await CoremailProvider(client).fetch_messages(
                    folder_id=folder_id,
                    start=start,
                    limit=limit,
                    mboxa=mboxa,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_FETCH_FAILED", "message": str(exc)},
            ) from exc
        return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

    @app.get("/api/modules/mail/message")
    async def get_mail_message(
        request: Request,
        message_id: str = Query(min_length=1),
        mboxa: str = "",
    ) -> dict[str, Any]:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                envelope = await CoremailProvider(client).fetch_message_detail(message_id=message_id, mboxa=mboxa)
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_FETCH_FAILED", "message": str(exc)},
            ) from exc
        return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

    @app.get("/api/modules/mail/attachment")
    async def download_mail_attachment(
        request: Request,
        message_id: str = Query(min_length=1),
        part: str = Query(min_length=1),
        filename: str | None = None,
    ) -> Response:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                content, content_type, upstream_disposition = await CoremailProvider(client).download_attachment(
                    message_id=message_id,
                    part=part,
                    filename=filename,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_DOWNLOAD_FAILED", "message": str(exc)},
            ) from exc

        headers: dict[str, str] = {}
        if upstream_disposition:
            headers["Content-Disposition"] = upstream_disposition
        elif filename:
            headers["Content-Disposition"] = f"attachment; filename*=UTF-8''{quote(filename, safe='')}"
        else:
            headers["Content-Disposition"] = "attachment"
        return Response(content=content, media_type=content_type, headers=headers)

    @app.post("/api/modules/mail/messages/delete", response_model=MailDeleteResponse)
    async def delete_mail_messages(request: Request, payload: MailDeleteRequest) -> MailDeleteResponse:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await CoremailProvider(client).delete_messages(
                    message_ids=payload.message_ids,
                    mboxa=payload.mboxa,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_DELETE_FAILED", "message": str(exc)},
            ) from exc

    @app.post("/api/modules/mail/messages/send", response_model=MailComposeResponse)
    async def send_mail_message(request: Request, payload: MailSendRequest) -> MailComposeResponse:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await CoremailProvider(client).send_message(
                    payload,
                    autosave_hit_counter=payload.autosave_hit_counter,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_SEND_FAILED", "message": str(exc)},
            ) from exc

    @app.post("/api/modules/mail/drafts/save", response_model=MailComposeResponse)
    async def save_mail_draft(request: Request, payload: MailDraftSaveRequest) -> MailComposeResponse:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await CoremailProvider(client).save_draft(payload)
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_DRAFT_FAILED", "message": str(exc)},
            ) from exc

    @app.get("/api/modules/mail/contacts/autocomplete")
    async def autocomplete_mail_contacts(
        request: Request,
        keyword: str = Query(min_length=1, max_length=128),
        limit: int = Query(default=20, ge=1, le=50),
    ) -> dict[str, Any]:
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                envelope = await CoremailProvider(client).autocomplete_contacts(keyword=keyword, limit=limit)
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_CONTACTS_FAILED", "message": str(exc)},
            ) from exc
        return envelope.model_copy(update={"synced_at": utcnow_iso()}).model_dump(mode="json")

    @app.post("/api/modules/mail/attachments/upload", response_model=MailAttachmentUploadResponse)
    async def upload_mail_attachment(
        request: Request,
        file: UploadFile = File(...),
        compose_id: str | None = Form(default=None),
    ) -> MailAttachmentUploadResponse:
        body = await file.read()
        try:
            async with request.app.state.session_manager.get_authenticated_client() as client:
                return await CoremailProvider(client).upload_attachment(
                    filename=file.filename or "attachment",
                    content=body,
                    content_type=file.content_type,
                    compose_id=compose_id,
                )
        except SessionExpiredError as exc:
            raise HTTPException(status_code=401, detail={"code": "SESSION_EXPIRED", "message": str(exc)}) from exc
        except (httpx.HTTPStatusError, httpx.RequestError, CoremailError) as exc:
            raise HTTPException(
                status_code=502,
                detail={"code": "MAIL_UPLOAD_FAILED", "message": str(exc)},
            ) from exc

    @app.get("/api/modules/empty-rooms")
    async def get_empty_rooms(
        request: Request,
        term: str | None = None,
        week: str | None = None,
        building: str | None = None,
        room: str | None = None,
    ) -> dict[str, Any]:
        payload = request.app.state.sync_service.get_snapshot("empty_rooms")
        has_query = any(_clean_text_param(value) for value in (term, week, building, room))
        default_week = None if has_query else request.app.state.sync_service.get_current_calendar_week()
        if (
            payload is None
            or has_query
            or default_week is None
            or _empty_rooms_payload_week(payload) != default_week
        ):
            try:
                payload = await request.app.state.sync_service.fetch_live_module(
                    "empty_rooms",
                    term=term,
                    week=week or default_week,
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
