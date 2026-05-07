from __future__ import annotations

import asyncio
import json
import logging
import re
from datetime import date
from typing import Any, Sequence
from urllib.parse import quote, unquote, urljoin

import httpx

from ..parsers.ve import (
    build_course_resources_data,
    build_homework_data,
    parse_calendar,
    parse_calendar_terms,
    parse_course_resource_listing,
    parse_course_resource_tree,
    parse_courses,
    parse_homework_list,
    parse_student_profile,
)
from ..schemas import CoverageLevel, CourseSummary, ModuleEnvelope


MIS_VE_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/104/"
BKSY_VE_BRIDGE_URL = "https://bksycenter.bjtu.edu.cn/NoMasterJumpPage.aspx?URL=jwcZhjx&FPC=page:jwcZhjx"
VE_QXKT_INDEX_PATH = "/ve/back/core/main/index.shtml"
VE_COURSE_PLATFORM_PATH = "/ve/back/coursePlatform/coursePlatform.shtml"
VE_COURSE_PLATFORM_BASE_URL = f"http://123.121.147.7:88{VE_COURSE_PLATFORM_PATH}"
VE_HOMEWORK_COURSE_TO_PAGE = "10460"
VE_COURSE_RESOURCES_COURSE_TO_PAGE = "10450"
VE_COURSE_RESOURCES_DOC_TYPE = "1"
VE_QXKT_ENTRY_URL = f"http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"


logger = logging.getLogger(__name__)


class StrictFlowStepError(httpx.RequestError):
    def __init__(
        self,
        *,
        step: str,
        expected: str,
        actual_url: str | None = None,
        request: httpx.Request | None = None,
    ) -> None:
        self.step = step
        self.expected = expected
        self.actual_url = actual_url or "<unknown>"
        super().__init__(
            f"strict_flow_step={step} expected={expected} actual_url={self.actual_url}",
            request=request,
        )


class VEProvider:
    def __init__(self, client: httpx.AsyncClient) -> None:
        self.client = client
        self._session_id: str | None = None
        self._has_ajax_session = False
        self._course_platform_index_referer = VE_COURSE_PLATFORM_BASE_URL
        self._course_platform_referer = VE_COURSE_PLATFORM_BASE_URL
        self._strict_flow_ready = False
        self._strict_flow_step = "init"

    def _reset_course_platform_context(self) -> None:
        self._session_id = None
        self._has_ajax_session = False
        self._course_platform_index_referer = VE_COURSE_PLATFORM_BASE_URL
        self._course_platform_referer = VE_COURSE_PLATFORM_BASE_URL
        self._strict_flow_ready = False
        self._strict_flow_step = "init"

    def _extract_session_id_from_url(self, value: str) -> str | None:
        match = re.search(r"[?&]sessionId=([^&#\"'\s]+)", value, flags=re.IGNORECASE)
        return match.group(1) if match else None

    def _extract_session_id_from_cookie(self, value: str) -> str | None:
        match = re.search(r"(?:^|[;,\s])sessionId=([^;,\s]+)", value, flags=re.IGNORECASE)
        return match.group(1) if match else None

    def _require_url(self, *, step: str, response: httpx.Response, host: str | None = None, path: str | None = None) -> None:
        actual = str(response.url)
        if host and response.url.host != host:
            raise StrictFlowStepError(step=step, expected=f"host={host}", actual_url=actual, request=response.request)
        if path and path not in response.url.path:
            raise StrictFlowStepError(step=step, expected=f"path contains {path}", actual_url=actual, request=response.request)

    def _extract_input_value(self, html: str, field_name: str) -> str | None:
        patterns = (
            rf"(?:name|id)=[\"']{re.escape(field_name)}[\"'][^>]*value=[\"']([^\"']*)[\"']",
            rf"value=[\"']([^\"']*)[\"'][^>]*(?:name|id)=[\"']{re.escape(field_name)}[\"']",
        )
        for pattern in patterns:
            match = re.search(pattern, html, flags=re.IGNORECASE)
            if match:
                return match.group(1)
        return None

    def _extract_js_string_value(self, html: str, field_name: str) -> str | None:
        match = re.search(
            rf"\b(?:var\s+)?{re.escape(field_name)}\s*=\s*[\"']([^\"']*)[\"']",
            html,
            flags=re.IGNORECASE,
        )
        if not match:
            return None
        return match.group(1).strip() or None

    def _extract_session_id_from_html(self, html: str) -> str | None:
        input_match = self._extract_input_value(html, "sessionId")
        if input_match:
            return input_match

        script_match = re.search(
            r"(?:var\s+)?sessionId\s*[:=]\s*[\"']([A-Za-z0-9_-]+)[\"']",
            html,
            flags=re.IGNORECASE,
        )
        if script_match:
            return script_match.group(1)
        return None

    def _remember_course_platform_context(self, final_url: str, body: str) -> None:
        hidden_session_id = self._extract_session_id_from_html(body)
        url_session_id = self._extract_session_id_from_url(final_url)
        if hidden_session_id:
            self._session_id = hidden_session_id
            self._has_ajax_session = True
        elif url_session_id and self._session_id is None:
            self._session_id = url_session_id

        if "coursePlatform.shtml" in final_url:
            if "method=toCoursePlatformIndex" in final_url:
                self._course_platform_index_referer = final_url
            self._course_platform_referer = final_url

    def _remember_session_from_response(self, response: httpx.Response) -> None:
        self._remember_course_platform_context(str(response.url), response.text)
        location = response.headers.get("location", "")
        set_cookie = response.headers.get("set-cookie", "")
        for raw_value in (location, set_cookie):
            if not raw_value:
                continue
            session_id = self._extract_session_id_from_url(raw_value) or self._extract_session_id_from_cookie(raw_value)
            if session_id and not self._session_id:
                self._session_id = session_id

    def _select_referer(self, path: str, params: dict[str, Any]) -> str:
        method = str(params.get("method") or "").strip()
        if path == "/ve/back/coursePlatform/homeWork.shtml" and method == "getHomeWorkList":
            return self._course_platform_referer
        if path == "/ve/back/coursePlatform/courseResource.shtml":
            return self._course_platform_referer
        return self._course_platform_index_referer

    def _should_send_session_header(self, path: str, params: dict[str, Any]) -> bool:
        method = str(params.get("method") or "").strip()
        if path == "/ve/back/coursePlatform/course.shtml" and method == "getCourseList":
            return True
        if path == "/ve/back/coursePlatform/course.shtml" and method == "getTimeList":
            return True
        if path == "/ve/back/coursePlatform/homeWork.shtml" and method == "getHomeWorkList":
            return True
        if path == "/ve/back/coursePlatform/courseResource.shtml" and method == "stuQueryUploadResourceForCourseList":
            return True
        if path == "/ve/back/coursePlatform/userInfo.shtml" and method == "getUserInfo":
            return True
        return False

    async def _bootstrap_ve_session(self) -> bool:
        self._strict_flow_step = "mis_module_104_entered"
        try:
            mis_entry: httpx.Response | None = None
            mis_entry_error: Exception | None = None
            for attempt in range(3):
                try:
                    candidate = await self.client.get(MIS_VE_BRIDGE_URL, headers={"Referer": "https://mis.bjtu.edu.cn/home/"})
                    candidate.raise_for_status()
                    mis_entry = candidate
                    break
                except (httpx.HTTPStatusError, httpx.RequestError) as exc:
                    mis_entry_error = exc
                    if attempt == 2:
                        raise
                    await asyncio.sleep(0.4 * (attempt + 1))
            if mis_entry is None:
                if mis_entry_error is not None:
                    raise mis_entry_error
                raise StrictFlowStepError(
                    step="mis_module_104_entered",
                    expected="successful MIS module/104 entry",
                    actual_url=MIS_VE_BRIDGE_URL,
                )
            for past_response in mis_entry.history:
                self._remember_session_from_response(past_response)
            self._remember_session_from_response(mis_entry)
            # Upstream may land on bksy, bksycenter, or VE directly after redirects.
            if mis_entry.url.host not in {"bksy.bjtu.edu.cn", "bksycenter.bjtu.edu.cn", "123.121.147.7"}:
                raise StrictFlowStepError(
                    step="bksy_landing_reached",
                    expected="host in {bksy.bjtu.edu.cn,bksycenter.bjtu.edu.cn,123.121.147.7}",
                    actual_url=str(mis_entry.url),
                    request=mis_entry.request,
                )
            self._strict_flow_step = "bksy_landing_reached"

            gateway: httpx.Response | None = None
            gateway_error: Exception | None = None
            for attempt in range(3):
                try:
                    candidate = await self.client.get(BKSY_VE_BRIDGE_URL, headers={"Referer": str(mis_entry.url)})
                    candidate.raise_for_status()
                    if "Timeout.jsp" in str(candidate.url):
                        raise StrictFlowStepError(
                            step="bksycenter_gateway_entered",
                            expected="non-timeout VE redirect target",
                            actual_url=str(candidate.url),
                            request=candidate.request,
                        )
                    gateway = candidate
                    break
                except (httpx.HTTPStatusError, httpx.RequestError, StrictFlowStepError) as exc:
                    gateway_error = exc
                    if attempt == 2:
                        raise
                    await asyncio.sleep(0.4 * (attempt + 1))
            if gateway is None:
                if gateway_error is not None:
                    raise gateway_error
                raise StrictFlowStepError(
                    step="bksycenter_gateway_entered",
                    expected="successful bksycenter gateway redirect",
                    actual_url=str(mis_entry.url),
                    request=mis_entry.request,
                )
            for past_response in gateway.history:
                self._remember_session_from_response(past_response)
            self._remember_session_from_response(gateway)
            self._strict_flow_step = "bksycenter_gateway_entered"

            index_page: httpx.Response | None = None
            index_error: Exception | None = None
            for attempt in range(3):
                try:
                    candidate = await self.client.get(
                        f"http://123.121.147.7:88{VE_COURSE_PLATFORM_PATH}",
                        params={"method": "toCoursePlatformIndex"},
                        headers={"Referer": str(gateway.url)},
                    )
                    candidate.raise_for_status()
                    index_page = candidate
                    break
                except (httpx.HTTPStatusError, httpx.RequestError) as exc:
                    index_error = exc
                    if attempt == 2:
                        raise
                    await asyncio.sleep(0.4 * (attempt + 1))
            if index_page is None:
                if index_error is not None:
                    raise index_error
                raise StrictFlowStepError(
                    step="ve_course_platform_index_ready",
                    expected="successful course platform index request",
                    actual_url=str(gateway.url),
                )
            self._require_url(step="ve_course_platform_index_ready", response=index_page, host="123.121.147.7")
            for past_response in index_page.history:
                self._remember_session_from_response(past_response)
            self._remember_session_from_response(index_page)
            if self._course_platform_index_referer == VE_COURSE_PLATFORM_BASE_URL:
                raise StrictFlowStepError(
                    step="ve_course_platform_index_ready",
                    expected="course platform index referer initialized",
                    actual_url=str(index_page.url),
                    request=index_page.request,
                )
            self._strict_flow_step = "ve_course_platform_index_ready"
            self._strict_flow_ready = True
            return True
        except StrictFlowStepError as exc:
            self._strict_flow_step = exc.step
            self._strict_flow_ready = False
            return False
        except (httpx.HTTPStatusError, httpx.RequestError):
            self._strict_flow_ready = False
            return False

    async def _warmup_ajax_session(self) -> None:
        if not self._strict_flow_ready or self._course_platform_index_referer == VE_COURSE_PLATFORM_BASE_URL:
            await self._bootstrap_ve_session()
        if self._has_ajax_session and self._session_id:
            return
        payload = await self._get_json("/ve/back/coursePlatform/message.shtml", {"method": "getArticleList"})
        upstream_session_id = str(payload.get("sessionId") or "").strip()
        if upstream_session_id:
            self._session_id = upstream_session_id
            self._has_ajax_session = True

    def _raise_non_json_response(self, response: httpx.Response) -> None:
        content_type = response.headers.get("content-type", "unknown")
        body_excerpt = re.sub(r"\s+", " ", response.text).strip()[:200] or "<empty>"
        raise httpx.RequestError(
            f"Expected JSON response but got {content_type}: {body_excerpt}",
            request=response.request,
        )

    def _ensure_payload_success(self, payload: dict[str, Any], *, request: httpx.Request) -> None:
        status = payload.get("STATUS")
        if status is None:
            return
        status_text = str(status).strip().lower()
        method = str(request.url.params.get("method") or "").strip()
        if status_text in {"0", "ok", "success", "true"}:
            return
        # VE returns STATUS=2 for empty homework/resource lists in some queries.
        if method in {"getHomeWorkList", "stuQueryCourseResourceBag", "stuQueryUploadResourceForCourseList"} and status_text == "2":
            return
        err_msg = str(payload.get("ERRMSG") or payload.get("message") or "upstream reported failure").strip()
        raise httpx.RequestError(f"VE payload STATUS={status} ERRMSG={err_msg}", request=request)

    async def _get_page(
        self,
        path: str,
        params: dict[str, Any] | None = None,
        *,
        referer: str,
    ) -> str:
        response = await self.client.get(
            f"http://123.121.147.7:88{path}",
            params=dict(params or {}),
            headers={"Referer": referer},
        )
        response.raise_for_status()
        if path.startswith("/ve/back/coursePlatform/"):
            self._remember_course_platform_context(str(response.url), response.text)
        return response.text

    def _build_course_page_params(
        self,
        course: CourseSummary,
        *,
        course_to_page: str | None = None,
        teacher_id: str | None = None,
    ) -> dict[str, str]:
        if not course.course_code or not course.xkh_id or not course.xq_code:
            raise ValueError(f"Incomplete VE course context for course_id={course.course_id}")

        params = {
            "method": "toCoursePlatform",
            "courseId": course.course_code,
            "dataSource": "1",
            "cId": str(course.course_id),
            "xkhId": course.xkh_id,
            "xqCode": course.xq_code,
        }
        if course_to_page:
            params["courseToPage"] = course_to_page
        if teacher_id:
            params["teacherId"] = teacher_id
        return params

    async def _open_homework_context(self, course: CourseSummary) -> str:
        course_page_html = await self._get_page(
            VE_COURSE_PLATFORM_PATH,
            self._build_course_page_params(course),
            referer=self._course_platform_index_referer,
        )
        teacher_id = self._extract_input_value(course_page_html, "teacherId")
        if not teacher_id:
            raise ValueError(f"VE course page missing teacherId for course_id={course.course_id}")

        await self._get_page(
            VE_COURSE_PLATFORM_PATH,
            self._build_course_page_params(
                course,
                course_to_page=VE_HOMEWORK_COURSE_TO_PAGE,
                teacher_id=teacher_id,
            ),
            referer=self._course_platform_referer,
        )
        return teacher_id

    async def _open_course_resources_context(self, course: CourseSummary) -> dict[str, str]:
        course_page_html = await self._get_page(
            VE_COURSE_PLATFORM_PATH,
            self._build_course_page_params(course),
            referer=self._course_platform_index_referer,
        )
        teacher_id = (
            self._extract_input_value(course_page_html, "teacherId")
            or self._extract_js_string_value(course_page_html, "teacherId")
            or course.teacher_id
        )
        if not teacher_id:
            raise ValueError(f"VE course page missing teacherId for course_id={course.course_id}")

        resources_page_html = await self._get_page(
            VE_COURSE_PLATFORM_PATH,
            self._build_course_page_params(
                course,
                course_to_page=VE_COURSE_RESOURCES_COURSE_TO_PAGE,
                teacher_id=teacher_id,
            ),
            referer=self._course_platform_referer,
        )

        def pick(*values: str | None) -> str:
            for value in values:
                text = str(value or "").strip()
                if text:
                    return text
            return ""

        return {
            "courseId": pick(
                self._extract_js_string_value(resources_page_html, "courseNum"),
                self._extract_input_value(resources_page_html, "courseId"),
                course.course_code,
            ),
            "cId": pick(
                self._extract_input_value(resources_page_html, "courseId"),
                self._extract_js_string_value(resources_page_html, "courseNum"),
                course.course_code,
            ),
            "xkhId": pick(
                self._extract_input_value(resources_page_html, "xkhId"),
                self._extract_js_string_value(resources_page_html, "xkhId"),
                course.xkh_id,
            ),
            "xqCode": pick(
                self._extract_input_value(resources_page_html, "xqCode"),
                self._extract_js_string_value(resources_page_html, "xqCode"),
                course.xq_code,
            ),
            "teacherId": pick(
                self._extract_input_value(resources_page_html, "teacherId"),
                self._extract_js_string_value(resources_page_html, "teacherId"),
                teacher_id,
            ),
        }

    async def _get_json(self, path: str, params: dict[str, Any] | None = None) -> dict:
        url = f"http://123.121.147.7:88{path}"
        last_exc: Exception | None = None
        bootstrap_attempted = False
        is_course_platform_api = path.startswith("/ve/back/coursePlatform/")
        for attempt in range(3):
            request_params = dict(params or {})
            send_session_header = self._should_send_session_header(path, request_params)
            if is_course_platform_api and (not self._strict_flow_ready or self._course_platform_index_referer == VE_COURSE_PLATFORM_BASE_URL):
                bootstrapped = await self._bootstrap_ve_session()
                if not bootstrapped:
                    raise StrictFlowStepError(
                        step=self._strict_flow_step,
                        expected="strict flow bootstrap success",
                        actual_url=self._course_platform_index_referer,
                    )
            if send_session_header and not (self._session_id and self._has_ajax_session):
                try:
                    await self._warmup_ajax_session()
                except (httpx.HTTPStatusError, httpx.RequestError, StrictFlowStepError) as exc:
                    logger.warning("VE ajax session warmup failed before %s: %s", request_params.get("method"), exc)
            headers = {
                "Accept": "*/*",
                "X-Requested-With": "XMLHttpRequest",
                "Referer": self._select_referer(path, request_params),
            }
            if send_session_header and self._session_id and self._has_ajax_session:
                headers["sessionId"] = self._session_id
            try:
                response = await self.client.get(url, params=request_params, headers=headers)
                response.raise_for_status()
                if is_course_platform_api:
                    self._remember_session_from_response(response)
                try:
                    payload = response.json()
                except ValueError:
                    self._raise_non_json_response(response)
                if not isinstance(payload, dict):
                    self._raise_non_json_response(response)
                self._ensure_payload_success(payload, request=response.request)
                upstream_session_id = str(payload.get("sessionId") or "").strip()
                if upstream_session_id:
                    self._session_id = upstream_session_id
                    self._has_ajax_session = True
                return payload
            except (httpx.HTTPStatusError, httpx.RequestError, StrictFlowStepError) as exc:
                last_exc = exc
                status_code = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else None
                logger.warning(
                    "VE request failed path=%s method=%s status=%s send_session_header=%s has_session=%s strict_flow_step=%s error=%s",
                    path,
                    request_params.get("method"),
                    status_code,
                    send_session_header,
                    bool(self._session_id and self._has_ajax_session),
                    self._strict_flow_step,
                    exc,
                )

                if (
                    is_course_platform_api
                    and not bootstrap_attempted
                    and (status_code is None or status_code >= 500 or status_code in {401, 403})
                ):
                    bootstrap_attempted = True
                    self._reset_course_platform_context()
                    bootstrapped = await self._bootstrap_ve_session()
                    if bootstrapped:
                        continue
                    continue

                retryable = isinstance(exc, httpx.RequestError) or (status_code is not None and status_code >= 500)
                if not retryable or attempt == 2:
                    raise
                await asyncio.sleep(0.6 * (attempt + 1))

        if last_exc is not None:
            raise last_exc
        raise RuntimeError(f"Failed to fetch {url}")

    async def _post_json(
        self,
        path: str,
        params: dict[str, Any] | None = None,
        *,
        referer: str | None = None,
    ) -> dict:
        if not self._strict_flow_ready or self._course_platform_index_referer == VE_COURSE_PLATFORM_BASE_URL:
            bootstrapped = await self._bootstrap_ve_session()
            if not bootstrapped:
                raise StrictFlowStepError(
                    step=self._strict_flow_step,
                    expected="strict flow bootstrap success",
                    actual_url=self._course_platform_index_referer,
                )
        if not (self._session_id and self._has_ajax_session):
            await self._warmup_ajax_session()

        url = f"http://123.121.147.7:88{path}"
        headers = {
            "Accept": "*/*",
            "X-Requested-With": "XMLHttpRequest",
            "Referer": referer or self._course_platform_referer,
        }
        if self._session_id and self._has_ajax_session:
            headers["sessionId"] = self._session_id

        response = await self.client.post(url, params=dict(params or {}), headers=headers)
        response.raise_for_status()
        self._remember_session_from_response(response)
        try:
            payload = response.json()
        except ValueError:
            self._raise_non_json_response(response)
        if not isinstance(payload, dict):
            self._raise_non_json_response(response)
        self._ensure_payload_success(payload, request=response.request)
        return payload

    def _parse_json_or_text_payload(self, response: httpx.Response) -> dict[str, Any]:
        try:
            payload: Any = response.json()
        except ValueError:
            body = response.text.strip()
            if not body:
                return {}
            try:
                payload = json.loads(body)
            except ValueError as exc:
                body_excerpt = re.sub(r"\s+", " ", body).strip()[:200]
                raise httpx.RequestError(
                    f"Expected JSON response but got: {body_excerpt or '<empty>'}",
                    request=response.request,
                ) from exc

        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except ValueError as exc:
                raise httpx.RequestError(
                    f"Expected JSON object response but got string: {payload[:200]}",
                    request=response.request,
                ) from exc
        if not isinstance(payload, dict):
            raise httpx.RequestError(
                f"Expected JSON object response but got {type(payload).__name__}",
                request=response.request,
            )
        return payload

    def _extract_homework_upload_url(self, html: str) -> str | None:
        patterns = (
            r"\burl\s*:\s*[\"']([^\"']*rpUpload\.shtml[^\"']*)[\"']",
            r"\bscript\s*:\s*[\"']([^\"']*rpUpload\.shtml[^\"']*)[\"']",
        )
        for pattern in patterns:
            match = re.search(pattern, html, flags=re.IGNORECASE)
            if match:
                return urljoin("http://123.121.147.7:88/ve/back/course/courseWorkInfo.shtml", match.group(1))
        return None

    def _homework_entry_int(self, entry: dict[str, Any], key: str, default: int = 0) -> int:
        try:
            return int(entry.get(key) or default)
        except (TypeError, ValueError):
            return default

    def _filename_parts(self, filename: str | None) -> tuple[str, str, str]:
        clean_name = re.split(r"[\\/]", str(filename or "").strip())[-1] or "attachment"
        if "." in clean_name:
            stem, extension = clean_name.rsplit(".", 1)
        else:
            stem, extension = clean_name, ""
        return clean_name, stem or "attachment", extension.lower()

    async def _upload_homework_file(
        self,
        *,
        upload_url: str,
        upload_page_url: str,
        filename: str,
        content: bytes,
        content_type: str | None,
    ) -> dict[str, str]:
        clean_name, stem, extension = self._filename_parts(filename)
        response = await self.client.post(
            upload_url,
            files={"file": (clean_name, content, content_type or "application/octet-stream")},
            headers={
                "Accept": "*/*",
                "X-Requested-With": "XMLHttpRequest",
                "Referer": upload_page_url,
            },
        )
        response.raise_for_status()
        payload = self._parse_json_or_text_payload(response)
        self._ensure_payload_success(payload, request=response.request)

        file_name_no_ext = str(payload.get("fileNameNoExt") or stem).strip() or stem
        file_name_no_ext = unquote(file_name_no_ext)
        file_ext_name = str(payload.get("fileExtName") or extension).strip()
        file_size = str(payload.get("fileSize") if payload.get("fileSize") is not None else len(content)).strip()
        visit_name = str(payload.get("visitName") or "").strip()
        if not visit_name:
            raise httpx.RequestError("VE upload response missing visitName", request=response.request)

        return {
            "fileNameNoExt": quote(file_name_no_ext, safe=""),
            "fileExtName": file_ext_name,
            "fileSize": file_size,
            "visitName": visit_name,
            "pid": "",
            "ftype": "insert",
        }

    async def submit_homework(
        self,
        *,
        homework_id: int,
        course_id: int,
        content: str = "",
        files: Sequence[tuple[str, bytes, str | None]] = (),
        term: str | None = None,
    ) -> dict[str, Any]:
        if self._session_id is None or not self._strict_flow_ready:
            bootstrapped = await self._bootstrap_ve_session()
            if not bootstrapped:
                raise StrictFlowStepError(
                    step=self._strict_flow_step,
                    expected="strict flow bootstrap success before homework submit",
                    actual_url=self._course_platform_index_referer,
                )

        current_term = term
        if not current_term:
            terms_payload = await self._get_json("/ve/back/rp/common/teachCalendar.shtml", {"method": "queryCurrentXq"})
            _, current_term = parse_calendar_terms(terms_payload)
        if not current_term:
            raise ValueError("当前学期缺失，无法定位作业。")

        courses_payload = await self._get_json(
            "/ve/back/coursePlatform/course.shtml",
            {"method": "getCourseList", "pagesize": "100", "page": "1", "xqCode": current_term},
        )
        courses = parse_courses(courses_payload)
        course = next((item for item in courses if item.course_id == course_id), None)
        if course is None:
            raise ValueError(f"未找到课程: {course_id}")

        await self._open_homework_context(course)
        homework_entry: dict[str, Any] | None = None
        for sub_type in (0, 2):
            payload = await self._get_json(
                "/ve/back/coursePlatform/homeWork.shtml",
                {
                    "method": "getHomeWorkList",
                    "cId": str(course_id),
                    "subType": str(sub_type),
                    "page": "1",
                    "pagesize": "100",
                },
            )
            homework_entry = next(
                (
                    entry
                    for entry in payload.get("courseNoteList", [])
                    if str(entry.get("id") or "").strip() == str(homework_id)
                ),
                None,
            )
            if homework_entry is not None:
                break
        if homework_entry is None:
            raise ValueError(f"未找到作业: {homework_id}")

        upload_params = {
            "method": "uploadDiv3",
            "courseId": str(course_id),
            "calendarId": str(homework_entry.get("calendar_id") or ""),
            "upId": str(homework_id),
            "contentType": str(self._homework_entry_int(homework_entry, "content_type", 0)),
            "fz": str(self._homework_entry_int(homework_entry, "is_fz", 0)),
            "openTime": str(homework_entry.get("open_date") or ""),
            "endTime": str(homework_entry.get("end_time") or ""),
            "return_num": str(self._homework_entry_int(homework_entry, "return_num", 0)),
        }
        upload_page_response = await self.client.get(
            "http://123.121.147.7:88/ve/back/course/courseWorkInfo.shtml",
            params=upload_params,
            headers={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer": self._course_platform_referer,
            },
        )
        upload_page_response.raise_for_status()
        upload_page_url = str(upload_page_response.url)
        upload_page_html = upload_page_response.text

        upload_url = self._extract_homework_upload_url(upload_page_html)
        file_list: list[dict[str, str]] = []
        if files:
            if not upload_url:
                raise httpx.RequestError("VE homework upload page missing upload URL", request=upload_page_response.request)
            for filename, file_content, file_content_type in files:
                file_list.append(
                    await self._upload_homework_file(
                        upload_url=upload_url,
                        upload_page_url=upload_page_url,
                        filename=filename,
                        content=file_content,
                        content_type=file_content_type,
                    )
                )

        submit_data = {
            "content": quote(content or "", safe=""),
            "groupName": quote(self._extract_input_value(upload_page_html, "groupName") or "", safe=""),
            "groupId": self._extract_input_value(upload_page_html, "groupId") or "",
            "courseId": self._extract_input_value(upload_page_html, "courseId") or str(course_id),
            "contentType": self._extract_input_value(upload_page_html, "contentType") or upload_params["contentType"],
            "fz": self._extract_input_value(upload_page_html, "fz") or upload_params["fz"],
            "jxrl_id": self._extract_input_value(upload_page_html, "jxrl_id") or "",
            "fileList": json.dumps(file_list, ensure_ascii=False),
            "upId": self._extract_input_value(upload_page_html, "upId") or str(homework_id),
            "return_num": self._extract_input_value(upload_page_html, "return_num") or upload_params["return_num"],
            "isTeacher": "0",
        }
        submit_response = await self.client.post(
            "http://123.121.147.7:88/ve/back/course/courseWorkInfo.shtml",
            params={"method": "sendStuHomeWorks"},
            data=submit_data,
            headers={
                "Accept": "*/*",
                "X-Requested-With": "XMLHttpRequest",
                "Referer": upload_page_url,
            },
        )
        submit_response.raise_for_status()
        submit_payload = self._parse_json_or_text_payload(submit_response)
        flag = str(submit_payload.get("flag") or submit_payload.get("status") or "").strip().lower()
        if flag != "success":
            message = str(submit_payload.get("message") or submit_payload.get("msg") or "VE 作业提交失败").strip()
            raise httpx.RequestError(message, request=submit_response.request)

        submitted_at = str(submit_payload.get("subTime") or submit_payload.get("submitted_at") or "").strip() or None
        return {
            "status": "success",
            "message": str(submit_payload.get("message") or submit_payload.get("msg") or "提交成功").strip(),
            "homework_id": homework_id,
            "submitted_at": submitted_at,
            "upstream": submit_payload,
        }

    async def fetch_calendar(self, month: str | None = None) -> ModuleEnvelope:
        if self._session_id is None or not self._strict_flow_ready:
            bootstrapped = await self._bootstrap_ve_session()
            if not bootstrapped:
                raise StrictFlowStepError(
                    step=self._strict_flow_step,
                    expected="strict flow bootstrap success before calendar fetch",
                    actual_url=self._course_platform_index_referer,
                )

        terms_payload = await self._get_json("/ve/back/rp/common/teachCalendar.shtml", {"method": "queryCurrentXq"})
        available_terms, current_term = parse_calendar_terms(terms_payload)
        target_month = month or date.today().strftime("%Y-%m")
        calendar_payload = await self._get_json(
            "/ve/back/coursePlatform/course.shtml",
            {"method": "getTimeList", "monthTime": target_month},
        )
        return ModuleEnvelope(
            module="calendar",
            source_system="ve",
            coverage=CoverageLevel.VERIFIED,
            source_params={"month": target_month},
            data=parse_calendar(
                calendar_payload,
                month=target_month,
                current_term=current_term,
                available_terms=available_terms,
            ),
        )

    async def fetch_student_profile(self) -> ModuleEnvelope:
        if self._session_id is None or not self._strict_flow_ready:
            bootstrapped = await self._bootstrap_ve_session()
            if not bootstrapped:
                raise StrictFlowStepError(
                    step=self._strict_flow_step,
                    expected="strict flow bootstrap success before profile fetch",
                    actual_url=self._course_platform_index_referer,
                )
        if not (self._session_id and self._has_ajax_session):
            await self._warmup_ajax_session()

        payload = await self._get_json("/ve/back/coursePlatform/userInfo.shtml", {"method": "getUserInfo"})
        profile = parse_student_profile(payload)
        if profile.avatar_url and not profile.avatar_url.startswith(("http://", "https://", "data:")):
            profile = profile.model_copy(
                update={"avatar_url": urljoin("http://123.121.147.7:88/ve/", profile.avatar_url)}
            )
        return ModuleEnvelope(
            module="profile",
            source_system="ve",
            coverage=CoverageLevel.VERIFIED if profile.fields else CoverageLevel.PROVISIONAL,
            source_params={},
            data=profile,
        )

    async def fetch_homework(self, term: str | None = None) -> ModuleEnvelope:
        if self._session_id is None or not self._strict_flow_ready:
            bootstrapped = await self._bootstrap_ve_session()
            if not bootstrapped:
                raise StrictFlowStepError(
                    step=self._strict_flow_step,
                    expected="strict flow bootstrap success before homework fetch",
                    actual_url=self._course_platform_index_referer,
                )

        current_term = term
        if not current_term:
            terms_payload = await self._get_json("/ve/back/rp/common/teachCalendar.shtml", {"method": "queryCurrentXq"})
            _, current_term = parse_calendar_terms(terms_payload)

        if not current_term:
            return ModuleEnvelope(
                module="homework",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"term": None, "fallback_reason": "missing_current_term"},
                data=build_homework_data(current_term=None, courses=[], items=[]),
            )

        courses_payload = await self._get_json(
            "/ve/back/coursePlatform/course.shtml",
            {"method": "getCourseList", "pagesize": "100", "page": "1", "xqCode": current_term or ""},
        )
        courses = parse_courses(courses_payload)
        items = []
        request_errors: list[str] = []
        for course in courses:
            try:
                await self._open_homework_context(course)
            except Exception as exc:
                request_errors.append(f"term={current_term},method=toCoursePlatform,cId={course.course_id}: {exc}")
                continue
            for sub_type in (0, 2):
                try:
                    payload = await self._get_json(
                        "/ve/back/coursePlatform/homeWork.shtml",
                        {
                            "method": "getHomeWorkList",
                            "cId": str(course.course_id),
                            "subType": str(sub_type),
                            "page": "1",
                            "pagesize": "10",
                        },
                    )
                    items.extend(parse_homework_list(payload, course=course, sub_type=sub_type))
                except Exception as exc:
                    request_errors.append(
                        f"term={current_term},method=getHomeWorkList,cId={course.course_id},subType={sub_type}: {exc}"
                    )
                    continue

        deduped_items = {}
        for item in items:
            key = item.homework_id if item.homework_id is not None else (item.course_id, item.title, item.due_at)
            previous = deduped_items.get(key)
            if previous is None or (item.submitted_at and not previous.submitted_at):
                deduped_items[key] = item
        items = list(deduped_items.values())

        return ModuleEnvelope(
            module="homework",
            source_system="ve",
            coverage=CoverageLevel.PROVISIONAL if request_errors else CoverageLevel.VERIFIED,
            source_params={"term": current_term, "partial_error_count": len(request_errors)},
            data=build_homework_data(current_term=current_term, courses=courses, items=items),
        )

    async def fetch_course_resources(
        self,
        term: str | None = None,
        course_id: str | None = None,
        folder_id: str = "0",
        search: str | None = None,
    ) -> ModuleEnvelope:
        if self._session_id is None or not self._strict_flow_ready:
            bootstrapped = await self._bootstrap_ve_session()
            if not bootstrapped:
                raise StrictFlowStepError(
                    step=self._strict_flow_step,
                    expected="strict flow bootstrap success before course resources fetch",
                    actual_url=self._course_platform_index_referer,
                )

        current_term = term
        available_terms = []
        if not current_term:
            terms_payload = await self._get_json("/ve/back/rp/common/teachCalendar.shtml", {"method": "queryCurrentXq"})
            available_terms, current_term = parse_calendar_terms(terms_payload)

        if not current_term:
            return ModuleEnvelope(
                module="course_resources",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={"term": None, "fallback_reason": "missing_current_term"},
                data=build_course_resources_data(
                    current_term=None,
                    courses=[],
                    selected_course=None,
                    folder_id=str(folder_id or "0"),
                    tree=[],
                    folders=[],
                    resources=[],
                ),
            )

        courses_payload = await self._get_json(
            "/ve/back/coursePlatform/course.shtml",
            {"method": "getCourseList", "pagesize": "100", "page": "1", "xqCode": current_term or ""},
        )
        courses = parse_courses(courses_payload)
        selected_course: CourseSummary | None = None
        requested_course_id = str(course_id or "").strip()
        if requested_course_id:
            selected_course = next(
                (
                    course
                    for course in courses
                    if str(course.course_id) == requested_course_id or str(course.course_code or "") == requested_course_id
                ),
                None,
            )
        elif courses:
            selected_course = courses[0]

        normalized_folder_id = str(folder_id or "0").strip() or "0"
        source_params = {
            "term": current_term,
            "course_id": requested_course_id or (selected_course.course_id if selected_course else None),
            "folder_id": normalized_folder_id,
            "search": search or "",
        }
        if not selected_course:
            return ModuleEnvelope(
                module="course_resources",
                source_system="ve",
                coverage=CoverageLevel.VERIFIED,
                source_params=source_params,
                data=build_course_resources_data(
                    current_term=current_term,
                    courses=courses,
                    selected_course=None,
                    folder_id=normalized_folder_id,
                    tree=[],
                    folders=[],
                    resources=[],
                ),
            )

        try:
            resource_context = await self._open_course_resources_context(selected_course)
            if not all(resource_context.get(key) for key in ("courseId", "cId", "xkhId", "xqCode")):
                raise ValueError(f"Incomplete VE course resource context for course_id={selected_course.course_id}")
            base_params = {
                "courseId": resource_context["courseId"],
                "cId": resource_context["cId"],
                "xkhId": resource_context["xkhId"],
                "xqCode": resource_context["xqCode"],
                "docType": VE_COURSE_RESOURCES_DOC_TYPE,
            }
            tree_payload = await self._get_json(
                "/ve/back/coursePlatform/courseResource.shtml",
                {"method": "stuQueryCourseResourceBag", **base_params},
            )
            listing_payload = await self._get_json(
                "/ve/back/coursePlatform/courseResource.shtml",
                {
                    "method": "stuQueryUploadResourceForCourseList",
                    **base_params,
                    "up_id": normalized_folder_id,
                    "searchName": search or "",
                },
            )
        except Exception as exc:
            return ModuleEnvelope(
                module="course_resources",
                source_system="ve",
                coverage=CoverageLevel.PROVISIONAL,
                source_params={**source_params, "fallback_reason": str(exc)},
                data=build_course_resources_data(
                    current_term=current_term,
                    courses=courses,
                    selected_course=selected_course,
                    folder_id=normalized_folder_id,
                    tree=[],
                    folders=[],
                    resources=[],
                ),
            )

        folders, resources = parse_course_resource_listing(listing_payload, folder_id=normalized_folder_id)
        return ModuleEnvelope(
            module="course_resources",
            source_system="ve",
            coverage=CoverageLevel.VERIFIED,
            source_params=source_params,
            data=build_course_resources_data(
                current_term=current_term,
                courses=courses,
                selected_course=selected_course,
                folder_id=normalized_folder_id,
                tree=parse_course_resource_tree(tree_payload),
                folders=folders,
                resources=resources,
            ),
        )

    async def download_course_resource(self, rp_id: str) -> tuple[bytes, str, str | None]:
        normalized_rp_id = str(rp_id or "").strip()
        if not normalized_rp_id:
            raise httpx.RequestError("Missing course resource rp_id")

        payload = await self._post_json(
            "/ve/back/resourceSpace.shtml",
            {"method": "rpinfoDownloadUrl", "rpId": normalized_rp_id},
            referer=self._course_platform_referer,
        )
        rp_url = str(payload.get("rpUrl") or payload.get("url") or "").strip()
        if not rp_url:
            raise httpx.RequestError("VE resource download URL missing")

        download_url = urljoin("http://123.121.147.7:88/ve/", rp_url)
        response = await self.client.get(download_url, headers={"Referer": self._course_platform_referer})
        response.raise_for_status()
        content_type = response.headers.get("content-type", "application/octet-stream").split(";", 1)[0].strip()
        disposition = response.headers.get("content-disposition")
        return response.content, content_type or "application/octet-stream", disposition
