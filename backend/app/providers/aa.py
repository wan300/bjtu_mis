from __future__ import annotations

import asyncio
import base64
import re
import uuid
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup

from ..exceptions import SessionExpiredError
from ..parsers.aa import (
    parse_academic_progress,
    parse_academic_progress_detail_path,
    parse_course_selection_captcha,
    parse_course_selection_page,
    parse_empty_rooms,
    parse_exams,
    parse_score_detail,
    parse_scores,
    parse_select_options,
    parse_student_status_profile,
    parse_timetable,
)
from ..schemas import (
    CourseSelectionAttemptResult,
    CourseSelectionCaptchaChallenge,
    CoverageLevel,
    ModuleEnvelope,
)


MIS_AA_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/10/"
HISTORY_ALL_TERMS = "all"
AA_COURSE_SELECTION_PATH = "/course_selection/courseselecttask/selects/"
AA_COURSE_SELECTION_URL = f"https://aa.bjtu.edu.cn{AA_COURSE_SELECTION_PATH}"
COURSE_SELECTION_CHALLENGES: dict[str, dict[str, Any]] = {}


class AAProvider:
    def __init__(self, client: httpx.AsyncClient) -> None:
        self.client = client

    def _extract_aa_client_login_url(self, html: str) -> str | None:
        form_action = re.search(
            r'action=["\'](https://aa\.bjtu\.edu\.cn/client/login/[^"\']+)["\']',
            html,
            flags=re.IGNORECASE,
        )
        if form_action:
            return form_action.group(1)

        inline = re.search(
            r"https://aa\.bjtu\.edu\.cn/client/login/[^\s\"'<]+",
            html,
            flags=re.IGNORECASE,
        )
        if inline:
            return inline.group(0)
        return None

    async def _bootstrap_aa_from_mis(self) -> bool:
        try:
            bridge = await self.client.get(
                MIS_AA_BRIDGE_URL,
                headers={"Referer": "https://mis.bjtu.edu.cn/home/"},
            )
        except (httpx.HTTPStatusError, httpx.RequestError):
            return False

        if bridge.status_code >= 500:
            return False

        login_url = self._extract_aa_client_login_url(bridge.text)
        if not login_url:
            return False

        try:
            await self.client.get(login_url, headers={"Referer": "https://mis.bjtu.edu.cn/"})
        except (httpx.HTTPStatusError, httpx.RequestError):
            return False
        return True

    async def _get_text(self, path: str, params: dict[str, Any] | None = None) -> str:
        url = f"https://aa.bjtu.edu.cn{path}"
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                response = await self.client.get(url, params=params)
                response.raise_for_status()
                final_url = str(response.url)
                body_head = response.text[:4096]
                if "/client/login/" in final_url or (
                    "用户登录" in body_head and "教学支撑平台" in body_head
                ):
                    if attempt < 2:
                        bootstrapped = await self._bootstrap_aa_from_mis()
                        if bootstrapped:
                            await asyncio.sleep(0.2)
                            continue
                    raise SessionExpiredError("教学支撑平台未登录，请在登录浏览器中从 MIS 点击“教务平台/课程平台”完成自动登录。")
                return response.text
            except (httpx.HTTPStatusError, httpx.RequestError) as exc:
                last_exc = exc
                status_code = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else None
                retryable = isinstance(exc, httpx.RequestError) or (status_code is not None and status_code >= 500)
                if not retryable or attempt == 2:
                    raise
                await asyncio.sleep(0.6 * (attempt + 1))
            except SessionExpiredError:
                raise

        if last_exc is not None:
            raise last_exc
        raise RuntimeError(f"Failed to fetch {url}")

    def _is_effectively_all_free(self, room_data) -> bool:
        rooms = room_data.rooms
        if not rooms:
            return False
        for room in rooms:
            if room.availability and any(not slot for slot in room.availability):
                return False
        return True

    async def fetch_timetable(self) -> ModuleEnvelope:
        html = await self._get_text("/course_selection/courseselect/stuschedule/")
        return ModuleEnvelope(
            module="timetable",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED,
            source_params={},
            data=parse_timetable(html),
        )

    async def fetch_course_selection(self) -> ModuleEnvelope:
        html = await self._get_text(AA_COURSE_SELECTION_PATH)
        parsed = parse_course_selection_page(html, AA_COURSE_SELECTION_URL)
        return ModuleEnvelope(
            module="course_selection",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED,
            source_params={},
            data=parsed.data,
        )

    async def attempt_course_selection(
        self,
        *,
        course_key: str | None = None,
        course_name: str | None = None,
    ) -> CourseSelectionAttemptResult:
        html = await self._get_text(AA_COURSE_SELECTION_PATH)
        parsed = parse_course_selection_page(html, AA_COURSE_SELECTION_URL)
        target = self._find_course_selection_target(parsed.data.available_courses + parsed.data.selected_courses, course_key, course_name)
        if target is None:
            return CourseSelectionAttemptResult(status="not_found", message="未找到匹配课程。")
        if target.selected:
            return CourseSelectionAttemptResult(status="already_selected", message="课程已选中。", course=target)
        if target.remaining is not None and target.remaining <= 0:
            return CourseSelectionAttemptResult(status="no_remaining", message="课程余量为 0。", course=target)

        action = parsed.actions.get(target.key)
        if action is None:
            return CourseSelectionAttemptResult(
                status="unparseable",
                message=parsed.data.submit_error or "无法解析选课提交入口。",
                course=target,
            )

        response = await self._submit_course_selection_action(action.action_url, action.method, action.fields)
        captcha = await self._build_course_selection_captcha(response.text, str(response.url), target.key, target.course_name)
        if captcha is not None:
            return CourseSelectionAttemptResult(
                status="captcha_required",
                message="需要输入验证码后继续提交。",
                course=target,
                captcha_challenge=captcha,
            )

        refreshed = await self.fetch_course_selection()
        refreshed_target = self._find_course_selection_target(
            refreshed.data.selected_courses + refreshed.data.available_courses,
            target.key,
            target.course_name,
        )
        if refreshed_target and refreshed_target.selected:
            return CourseSelectionAttemptResult(status="success", message="选课成功。", course=refreshed_target)
        return CourseSelectionAttemptResult(
            status="submitted",
            message=self._extract_selection_message(response.text) or "已提交选课请求，请刷新列表确认结果。",
            course=refreshed_target or target,
        )

    async def submit_course_selection_captcha(self, challenge_id: str, captcha: str) -> CourseSelectionAttemptResult:
        state = COURSE_SELECTION_CHALLENGES.pop(challenge_id, None)
        if state is None:
            return CourseSelectionAttemptResult(status="captcha_expired", message="验证码上下文已失效，请重新尝试选课。")
        fields = dict(state.get("fields") or {})
        action_url = fields.pop("__action__", None)
        input_name = state.get("input_name")
        if not action_url or not input_name:
            return CourseSelectionAttemptResult(status="unparseable", message="无法解析验证码提交入口。")
        fields[str(input_name)] = captcha.strip()
        response = await self._submit_course_selection_action(str(action_url), "post", fields)
        course_key = state.get("course_key")
        course_name = state.get("course_name")
        refreshed = await self.fetch_course_selection()
        refreshed_target = self._find_course_selection_target(
            refreshed.data.selected_courses + refreshed.data.available_courses,
            str(course_key) if course_key else None,
            str(course_name) if course_name else None,
        )
        if refreshed_target and refreshed_target.selected:
            return CourseSelectionAttemptResult(status="success", message="选课成功。", course=refreshed_target)
        next_captcha = await self._build_course_selection_captcha(response.text, str(response.url), str(course_key or ""), str(course_name or ""))
        if next_captcha is not None:
            return CourseSelectionAttemptResult(
                status="captcha_required",
                message="验证码未通过或需要再次输入。",
                course=refreshed_target,
                captcha_challenge=next_captcha,
            )
        return CourseSelectionAttemptResult(
            status="submitted",
            message=self._extract_selection_message(response.text) or "验证码已提交，请刷新列表确认结果。",
            course=refreshed_target,
        )

    async def drop_course_selection(
        self,
        *,
        course_key: str | None = None,
        course_name: str | None = None,
    ) -> CourseSelectionAttemptResult:
        html = await self._get_text(AA_COURSE_SELECTION_PATH)
        parsed = parse_course_selection_page(html, AA_COURSE_SELECTION_URL)
        return await self._drop_course_selection_from_parsed(parsed, course_key=course_key, course_name=course_name)

    async def replace_course_selection(
        self,
        *,
        target_course_key: str | None = None,
        target_course_name: str | None = None,
        drop_course_key: str | None = None,
        drop_course_name: str | None = None,
    ) -> CourseSelectionAttemptResult:
        html = await self._get_text(AA_COURSE_SELECTION_PATH)
        parsed = parse_course_selection_page(html, AA_COURSE_SELECTION_URL)
        target = self._find_course_selection_target(
            parsed.data.available_courses + parsed.data.selected_courses,
            target_course_key,
            target_course_name,
        )
        if target is None:
            return CourseSelectionAttemptResult(status="not_found", message="未找到目标课程。")
        if target.selected:
            return CourseSelectionAttemptResult(status="replace_success", message="目标课程已在已选列表中。", course=target)
        if target.remaining is not None and target.remaining <= 0:
            return CourseSelectionAttemptResult(status="target_no_remaining", message="目标课程余量为 0。", course=target)

        drop_result = await self._drop_course_selection_from_parsed(
            parsed,
            course_key=drop_course_key,
            course_name=drop_course_name,
        )
        if drop_result.status != "drop_success":
            return CourseSelectionAttemptResult(
                status="drop_failed",
                message=drop_result.message or "退课失败，未继续抢目标课程。",
                course=drop_result.course,
            )

        select_result = await self.attempt_course_selection(course_key=target.key, course_name=target.course_name)
        if select_result.status in {"success", "already_selected"}:
            return CourseSelectionAttemptResult(
                status="replace_success",
                message="换课成功。",
                course=select_result.course or target,
            )
        if select_result.status == "captcha_required":
            return select_result

        rollback_result = await self.attempt_course_selection(
            course_key=drop_result.course.key if drop_result.course else drop_course_key,
            course_name=drop_result.course.course_name if drop_result.course else drop_course_name,
        )
        if rollback_result.status in {"success", "already_selected"}:
            return CourseSelectionAttemptResult(
                status="rollback_success",
                message=f"目标课程选课失败，已尝试把原课程选回。{select_result.message or select_result.status}",
                course=rollback_result.course or drop_result.course,
            )
        return CourseSelectionAttemptResult(
            status="rollback_failed",
            message=(
                "目标课程选课失败，且原课程回滚失败。"
                f"选课结果：{select_result.message or select_result.status}；"
                f"回滚结果：{rollback_result.message or rollback_result.status}"
            ),
            course=rollback_result.course or drop_result.course,
        )

    async def _drop_course_selection_from_parsed(
        self,
        parsed,
        *,
        course_key: str | None = None,
        course_name: str | None = None,
    ) -> CourseSelectionAttemptResult:
        target = self._find_course_selection_target(parsed.data.selected_courses, course_key, course_name)
        if target is None:
            return CourseSelectionAttemptResult(status="not_selected", message="未找到要退的已选课程。")
        action = parsed.drop_actions.get(target.key)
        if action is None:
            return CourseSelectionAttemptResult(status="unparseable", message="无法解析退课入口。", course=target)

        response = await self._submit_course_selection_action(action.action_url, action.method, action.fields)
        refreshed = await self.fetch_course_selection()
        refreshed_target = self._find_course_selection_target(
            refreshed.data.selected_courses + refreshed.data.available_courses,
            target.key,
            target.course_name,
        )
        if refreshed_target is None or not refreshed_target.selected:
            return CourseSelectionAttemptResult(
                status="drop_success",
                message="退课成功。",
                course=target,
            )
        return CourseSelectionAttemptResult(
            status="drop_failed",
            message=self._extract_selection_message(response.text) or "已提交退课请求，但课程仍在已选列表中。",
            course=refreshed_target,
        )

    def _find_course_selection_target(self, courses, course_key: str | None, course_name: str | None):
        key = (course_key or "").strip()
        name = (course_name or "").strip()
        if key:
            found = next((course for course in courses if course.key == key), None)
            if found is not None:
                return found
        if name:
            normalized = re.sub(r"\s+", "", name).lower()
            return next(
                (
                    course
                    for course in courses
                    if re.sub(r"\s+", "", course.course_name).lower() == normalized
                    or normalized in re.sub(r"\s+", "", course.course_name).lower()
                ),
                None,
            )
        return None

    async def _submit_course_selection_action(self, action_url: str, method: str, fields: dict[str, str]) -> httpx.Response:
        headers = {"Referer": AA_COURSE_SELECTION_URL, "Origin": "https://aa.bjtu.edu.cn"}
        if method.lower() == "get":
            response = await self.client.get(action_url, params=fields, headers={"Referer": AA_COURSE_SELECTION_URL})
        else:
            response = await self.client.post(action_url, data=fields, headers=headers)
        response.raise_for_status()
        final_url = str(response.url)
        body_head = response.text[:4096]
        if "/client/login/" in final_url or ("用户登录" in body_head and "教学" in body_head):
            raise SessionExpiredError("教学支撑平台未登录，请重新登录。")
        return response

    async def _build_course_selection_captcha(
        self,
        html: str,
        page_url: str,
        course_key: str,
        course_name: str,
    ) -> CourseSelectionCaptchaChallenge | None:
        image_url, input_name, fields, prompt = parse_course_selection_captcha(html, page_url)
        if not image_url or not input_name:
            return None
        if image_url.startswith("data:"):
            image_data_url = image_url
        else:
            image = await self.client.get(image_url, headers={"Referer": page_url})
            image.raise_for_status()
            mime_type = (image.headers.get("Content-Type") or "image/png").split(";", 1)[0].strip()
            image_data_url = f"data:{mime_type};base64,{base64.b64encode(image.content).decode('ascii')}"
        challenge_id = uuid.uuid4().hex
        COURSE_SELECTION_CHALLENGES[challenge_id] = {
            "course_key": course_key,
            "course_name": course_name,
            "input_name": input_name,
            "fields": fields,
            "created_at": self._now_iso(),
        }
        return CourseSelectionCaptchaChallenge(
            challenge_id=challenge_id,
            image_data_url=image_data_url,
            prompt=prompt,
            fetched_at=self._now_iso(),
        )

    def _extract_selection_message(self, html: str) -> str | None:
        soup = BeautifulSoup(html, "html.parser")
        for selector in (".alert", ".modal-body", ".bootbox-body", ".error", ".help-block"):
            node = soup.select_one(selector)
            if node:
                text = re.sub(r"\s+", " ", node.get_text(" ", strip=True)).strip()
                if text:
                    return text[:240]
        text = re.sub(r"\s+", " ", soup.get_text(" ", strip=True)).strip()
        return text[:240] if text else None

    def _now_iso(self) -> str:
        return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    async def fetch_exams(self, term: str | None = None) -> ModuleEnvelope:
        params = {"zxjxjhh": term} if term else None
        html = await self._get_text("/examine/examplanstudent/stulist/", params=params)
        parsed = parse_exams(html, requested_term=term)
        if not parsed.items and not term and parsed.available_terms:
            for option in parsed.available_terms[:8]:
                candidate = option.value
                if not candidate or candidate == parsed.current_term:
                    continue
                html = await self._get_text("/examine/examplanstudent/stulist/", params={"zxjxjhh": candidate})
                retry_parsed = parse_exams(html, requested_term=candidate)
                if retry_parsed.items:
                    parsed = retry_parsed
                    break
        return ModuleEnvelope(
            module="exams",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED,
            source_params={"term": term or parsed.current_term},
            data=parsed,
        )

    async def fetch_scores(self, term: str | None = None, ctype: str | None = None) -> ModuleEnvelope:
        params: dict[str, str] = {}
        if term:
            params["zxjxjhh"] = term
        if ctype:
            params["ctype"] = ctype
        html = await self._get_text("/score/scores/stu/view/", params=params or None)
        parsed = parse_scores(html, requested_term=term)
        if not parsed.items and not term and parsed.available_terms:
            for option in parsed.available_terms[:10]:
                candidate = option.value
                if not candidate or candidate == parsed.current_term:
                    continue
                retry_params = {"zxjxjhh": candidate}
                if ctype:
                    retry_params["ctype"] = ctype
                html = await self._get_text("/score/scores/stu/view/", params=retry_params)
                retry_parsed = parse_scores(html, requested_term=candidate)
                if retry_parsed.items:
                    parsed = retry_parsed
                    break
        return ModuleEnvelope(
            module="scores",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED,
            source_params={"term": term or parsed.current_term, "ctype": ctype},
            data=parsed,
        )

    async def fetch_history_scores(self, term: str | None = None) -> ModuleEnvelope:
        requested_term = term.strip() if term else None
        if requested_term == HISTORY_ALL_TERMS:
            requested_term = None
        if requested_term:
            envelope = await self.fetch_scores(term=requested_term, ctype="ln")
            return envelope.model_copy(
                update={
                    "module": "history_scores",
                    "source_params": {"term": requested_term, "ctype": "ln"},
                }
            )

        seed = await self.fetch_scores(ctype="ln")
        available_terms = seed.data.available_terms
        term_values = list(dict.fromkeys(item.value for item in available_terms if item.value))
        seed_terms = {item.term for item in seed.data.items if item.term}
        if not term_values or len(seed_terms) > 1:
            data = seed.data.model_copy(update={"current_term": None})
        else:
            combined_items = []
            seen = set()
            for term_value in term_values:
                term_envelope = await self.fetch_scores(term=term_value, ctype="ln")
                for item in term_envelope.data.items:
                    key = (
                        item.term,
                        item.course_name,
                        item.credit,
                        item.score,
                        item.bonus_score,
                        item.teacher,
                        item.detail,
                        item.detail_path,
                    )
                    if key in seen:
                        continue
                    seen.add(key)
                    combined_items.append(item)
            data = seed.data.model_copy(
                update={
                    "current_term": None,
                    "available_terms": available_terms,
                    "items": combined_items,
                }
            )
        return seed.model_copy(
            update={
                "module": "history_scores",
                "source_params": {"term": HISTORY_ALL_TERMS, "ctype": "ln"},
                "data": data,
            }
        )

    async def fetch_score_detail(self, detail_path: str) -> ModuleEnvelope:
        request_path = self._aa_request_path(detail_path)
        html = await self._get_text(request_path)
        return ModuleEnvelope(
            module="score_detail",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED,
            source_params={"detail_path": request_path},
            data=parse_score_detail(html),
        )

    async def fetch_student_profile(self) -> ModuleEnvelope:
        html = await self._get_text("/school_census/schoolcensus/stuview/")
        profile = parse_student_status_profile(html)
        if profile.avatar_url and not profile.avatar_url.startswith(("http://", "https://", "data:")):
            profile = profile.model_copy(update={"avatar_url": urljoin("https://aa.bjtu.edu.cn/", profile.avatar_url)})
        return ModuleEnvelope(
            module="profile",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED if profile.fields else CoverageLevel.PROVISIONAL,
            source_params={},
            data=profile,
        )

    async def fetch_academic_progress(self) -> ModuleEnvelope:
        list_html = await self._get_text("/school_census/schooltraininfo/studylist/")
        detail_path = parse_academic_progress_detail_path(list_html)
        if not detail_path:
            parsed = parse_academic_progress("")
            coverage = CoverageLevel.PROVISIONAL
            source_params = {"fallback_reason": "missing_academic_progress_detail_link"}
        else:
            detail_url = urljoin("https://aa.bjtu.edu.cn/", detail_path)
            parsed_url = urlparse(detail_url)
            detail_request_path = parsed_url.path
            if parsed_url.query:
                detail_request_path = f"{detail_request_path}?{parsed_url.query}"
            detail_html = await self._get_text(detail_request_path)
            parsed = parse_academic_progress(detail_html)
            coverage = CoverageLevel.VERIFIED if parsed.buckets or parsed.courses else CoverageLevel.PROVISIONAL
            source_params = {"detail_path": detail_path}
        return ModuleEnvelope(
            module="academic_progress",
            source_system="aa",
            coverage=coverage,
            source_params=source_params,
            data=parsed,
        )

    def _aa_request_path(self, path_or_url: str) -> str:
        absolute_url = urljoin("https://aa.bjtu.edu.cn/", path_or_url.strip())
        parsed_url = urlparse(absolute_url)
        if parsed_url.scheme not in {"http", "https"} or parsed_url.netloc != "aa.bjtu.edu.cn":
            raise ValueError("成绩详情链接不是 AA 教学支撑平台地址。")
        request_path = parsed_url.path or "/"
        if parsed_url.query:
            request_path = f"{request_path}?{parsed_url.query}"
        return request_path

    async def fetch_empty_rooms(
        self,
        *,
        term: str | None = None,
        week: int | str | None = None,
        building: str | None = None,
        room: str | None = None,
    ) -> ModuleEnvelope:
        params: dict[str, Any] = {}
        term = term.strip() if term else None
        week = str(week).strip() if week is not None else None
        building = building.strip() if building else None
        room = room.strip() if room else None
        if term:
            params["zxjxjhh"] = term
        if week:
            params["zc"] = week
        if building:
            params["jxlh"] = building
        if room:
            params["jash"] = room
        if any(key in params for key in ("zxjxjhh", "jxlh", "jash", "zc")):
            params.setdefault("has_advance_query", "")
        html = await self._get_text("/classroom/timeholdresult/room_view/", params=params)
        requested_query = {
            key: value
            for key, value in {
                "term": term,
                "week": params.get("zc"),
                "building": building,
                "room": room,
            }.items()
            if value is not None and value != ""
        }
        parsed = parse_empty_rooms(html, requested_query=requested_query or None)

        if term is None and building is None and room is None and self._is_effectively_all_free(parsed):
            soup = BeautifulSoup(html, "html.parser")
            options, current_term = parse_select_options(soup, "zxjxjhh")
            for option in options[:8]:
                candidate = option.value
                if not candidate or candidate == current_term:
                    continue
                retry_params = dict(params)
                retry_params["zxjxjhh"] = candidate
                retry_html = await self._get_text("/classroom/timeholdresult/room_view/", params=retry_params)
                retry_parsed = parse_empty_rooms(
                    retry_html,
                    requested_query={
                        "term": candidate,
                        "week": retry_params.get("zc"),
                        "building": building,
                        "room": room,
                    },
                )
                if retry_parsed.rooms and not self._is_effectively_all_free(retry_parsed):
                    parsed = retry_parsed
                    params = retry_params
                    break

        return ModuleEnvelope(
            module="empty_rooms",
            source_system="aa",
            coverage=CoverageLevel.VERIFIED,
            source_params={
                "term": parsed.query.get("term"),
                "week": parsed.query.get("week", params.get("zc")),
                "building": parsed.query.get("building", building),
                "room": parsed.query.get("room", room),
            },
            data=parsed,
        )
