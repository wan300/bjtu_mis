from __future__ import annotations

import re
from typing import Any

from bs4 import BeautifulSoup

from ..schemas import CalendarData, CalendarItem, CourseSummary, HomeworkData, HomeworkItem, TermOption


def parse_calendar_terms(payload: dict[str, Any]) -> tuple[list[TermOption], str | None]:
    options: list[TermOption] = []
    current_term: str | None = None
    for item in payload.get("result", []):
        value = str(item.get("xqCode", "")).strip()
        label = str(item.get("xqName") or item.get("CNAME") or value).strip()
        if not value:
            continue
        selected = int(item.get("currentFlag") or 0) == 2
        options.append(TermOption(value=value, label=label, selected=selected))
        if selected:
            current_term = value
    if current_term is None and options:
        current_term = options[0].value
    return options, current_term


def parse_calendar(payload: dict[str, Any], *, month: str, current_term: str | None, available_terms: list[TermOption]) -> CalendarData:
    items = [
        CalendarItem(date=str(entry.get("dayTime")), week=str(payload.get("weekCode") or ""), note=None)
        for entry in payload.get("maps", [])
        if entry.get("dayTime")
    ]
    return CalendarData(
        month=month,
        current_week=str(payload.get("weekCode") or "") or None,
        current_term=current_term,
        available_terms=available_terms,
        items=items,
    )


def parse_courses(payload: dict[str, Any]) -> list[CourseSummary]:
    courses: list[CourseSummary] = []
    for item in payload.get("courseList", []):
        course_id = item.get("id")
        if course_id is None:
            continue
        xq_code = str(item.get("xq_code") or "").strip() or None
        courses.append(
            CourseSummary(
                course_id=int(course_id),
                course_name=str(item.get("name", "")).strip(),
                course_code=str(item.get("course_num") or "").strip() or None,
                teacher_name=str(item.get("teacher_name") or "").strip() or None,
                teacher_id=str(item.get("teacher_id") or "").strip() or None,
                term=xq_code,
                xq_code=xq_code,
                xkh_id=str(item.get("fz_id") or item.get("xkhId") or "").strip() or None,
            )
        )
    return courses


def strip_html_excerpt(value: str, limit: int = 160) -> str:
    text = BeautifulSoup(value or "", "html.parser").get_text(" ", strip=True)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit].rstrip()


def parse_homework_list(payload: dict[str, Any], *, course: CourseSummary, sub_type: int) -> list[HomeworkItem]:
    items: list[HomeworkItem] = []
    for entry in payload.get("courseNoteList", []):
        homework_id = entry.get("id")
        title = str(entry.get("title") or "").strip()
        if not title and homework_id is not None:
            title = f"作业#{homework_id}"
        items.append(
            HomeworkItem(
                homework_id=int(homework_id) if homework_id is not None else None,
                course=str(entry.get("course_name") or course.course_name).strip() or course.course_name,
                course_id=course.course_id,
                title=title,
                content_excerpt=strip_html_excerpt(str(entry.get("content") or "")),
                opened_at=str(entry.get("open_date") or "").strip() or None,
                due_at=str(entry.get("end_time") or "").strip() or None,
                status="done" if sub_type == 2 else "open",
                sub_type=sub_type,
                submission_status=str(entry.get("subStatus") or "").strip() or None,
            )
        )
    return items


def build_homework_data(
    *,
    current_term: str | None,
    courses: list[CourseSummary],
    items: list[HomeworkItem],
) -> HomeworkData:
    return HomeworkData(current_term=current_term, courses=courses, items=items)
