from __future__ import annotations

import asyncio
import re
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup

from ..exceptions import SessionExpiredError
from ..parsers.aa import (
    parse_academic_progress,
    parse_academic_progress_detail_path,
    parse_empty_rooms,
    parse_exams,
    parse_scores,
    parse_select_options,
    parse_student_status_profile,
    parse_timetable,
)
from ..schemas import CoverageLevel, ModuleEnvelope


MIS_AA_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/10/"
HISTORY_ALL_TERMS = "all"


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

    async def fetch_empty_rooms(
        self,
        *,
        term: str | None = None,
        week: int | str | None = None,
        building: str | None = None,
        room: str | None = None,
    ) -> ModuleEnvelope:
        params: dict[str, Any] = {}
        if term:
            params["zxjxjhh"] = term
        if week is not None:
            params["zc"] = week
        if building:
            params["jxlh"] = building
        if room:
            params["jash"] = room
        if not params:
            params["zc"] = 8
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
                "week": params.get("zc"),
                "building": parsed.query.get("building", building),
                "room": parsed.query.get("room", room),
            },
            data=parsed,
        )
