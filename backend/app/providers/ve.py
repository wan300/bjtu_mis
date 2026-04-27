from __future__ import annotations

import asyncio
import logging
import re
from datetime import date
from typing import Any

import httpx

from ..parsers.ve import build_homework_data, parse_calendar, parse_calendar_terms, parse_courses, parse_homework_list
from ..schemas import CoverageLevel, CourseSummary, ModuleEnvelope


MIS_VE_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/104/"
BKSY_VE_BRIDGE_URL = "https://bksycenter.bjtu.edu.cn/NoMasterJumpPage.aspx?URL=jwcZhjx&FPC=page:jwcZhjx"
VE_QXKT_INDEX_PATH = "/ve/back/core/main/index.shtml"
VE_COURSE_PLATFORM_PATH = "/ve/back/coursePlatform/coursePlatform.shtml"
VE_COURSE_PLATFORM_BASE_URL = f"http://123.121.147.7:88{VE_COURSE_PLATFORM_PATH}"
VE_HOMEWORK_COURSE_TO_PAGE = "10460"
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
            rf"(?:name|id)=[\"']{re.escape(field_name)}[\"'][^>]*value=[\"']([^\"']+)[\"']",
            rf"value=[\"']([^\"']+)[\"'][^>]*(?:name|id)=[\"']{re.escape(field_name)}[\"']",
        )
        for pattern in patterns:
            match = re.search(pattern, html, flags=re.IGNORECASE)
            if match:
                return match.group(1)
        return None

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
        return self._course_platform_index_referer

    def _should_send_session_header(self, path: str, params: dict[str, Any]) -> bool:
        method = str(params.get("method") or "").strip()
        if path == "/ve/back/coursePlatform/course.shtml" and method == "getCourseList":
            return True
        if path == "/ve/back/coursePlatform/course.shtml" and method == "getTimeList":
            return True
        if path == "/ve/back/coursePlatform/homeWork.shtml" and method == "getHomeWorkList":
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
        # VE returns STATUS=2 for empty homework list in some subType queries.
        if method == "getHomeWorkList" and status_text == "2":
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

    async def _open_homework_context(self, course: CourseSummary) -> None:
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
            if send_session_header and not self._session_id:
                try:
                    await self._warmup_ajax_session()
                except (httpx.HTTPStatusError, httpx.RequestError, StrictFlowStepError) as exc:
                    logger.warning("VE ajax session warmup failed before %s: %s", request_params.get("method"), exc)
            headers = {
                "Accept": "*/*",
                "X-Requested-With": "XMLHttpRequest",
                "Referer": self._select_referer(path, request_params),
            }
            if send_session_header and self._session_id:
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

        return ModuleEnvelope(
            module="homework",
            source_system="ve",
            coverage=CoverageLevel.PROVISIONAL if request_errors else CoverageLevel.VERIFIED,
            source_params={"term": current_term, "partial_error_count": len(request_errors)},
            data=build_homework_data(current_term=current_term, courses=courses, items=items),
        )
