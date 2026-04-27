from __future__ import annotations

import re
from typing import Any

from bs4 import BeautifulSoup, Tag

from ..schemas import (
    CourseEntry,
    EmptyRoomData,
    EmptyRoomRow,
    EmptyRoomSlotHeader,
    ExamData,
    ExamItem,
    ScoreData,
    ScoreItem,
    TermOption,
    TimetableData,
)


def normalize_space(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def split_location(location_text: str) -> tuple[str | None, str | None, str | None]:
    chunks = [normalize_space(chunk) for chunk in location_text.split(",") if normalize_space(chunk)]
    campus = chunks[0] if len(chunks) > 0 else None
    building = chunks[1] if len(chunks) > 1 else None
    room = chunks[2] if len(chunks) > 2 else None
    return campus, building, room


def parse_select_options(soup: BeautifulSoup, field_name: str) -> tuple[list[TermOption], str | None]:
    select = soup.find("select", attrs={"name": field_name}) or soup.find("select", attrs={"id": field_name})
    options: list[TermOption] = []
    current: str | None = None
    if not isinstance(select, Tag):
        return options, current

    for option in select.find_all("option"):
        value = normalize_space(option.get("value", ""))
        label = normalize_space(option.get_text(" ", strip=True))
        if not value:
            continue
        selected = option.has_attr("selected")
        options.append(TermOption(value=value, label=label, selected=selected))
        if selected:
            current = value

    if current is None and options:
        current = next((item.value for item in options if item.selected), options[0].value)
    return options, current


def parse_input_value(soup: BeautifulSoup, field_name: str) -> str | None:
    element = soup.find(attrs={"name": field_name})
    if not isinstance(element, Tag):
        return None
    value = element.get("value")
    return normalize_space(value) if value is not None else None


def parse_timetable(html: str) -> TimetableData:
    soup = BeautifulSoup(html, "html.parser")
    table = soup.find("table", class_="table")
    if not isinstance(table, Tag):
        return TimetableData()

    rows = table.find_all("tr", recursive=False)
    if len(rows) < 2:
        return TimetableData()

    header_cells = rows[0].find_all(["th", "td"], recursive=False)
    days = [normalize_space(cell.get_text(" ", strip=True)) for cell in header_cells[1:]]
    periods: list[str] = []
    entries: list[CourseEntry] = []

    for row in rows[1:]:
        cells = row.find_all(["th", "td"], recursive=False)
        if len(cells) < 2:
            continue

        period_text = normalize_space(cells[0].get_text(" ", strip=True))
        period_match = re.search(r"(第\d+节)", period_text)
        time_match = re.search(r"\[(\d{2}:\d{2}-\d{2}:\d{2})\]", period_text)
        period_label = period_match.group(1) if period_match else period_text
        time_range = time_match.group(1) if time_match else None
        if period_label and period_label not in periods:
            periods.append(period_label)

        for day_index, cell in enumerate(cells[1:]):
            weekday = days[day_index] if day_index < len(days) else f"星期{day_index + 1}"
            blocks = [block for block in cell.find_all("div", recursive=False) if normalize_space(block.get_text(" ", strip=True))]
            if not blocks and normalize_space(cell.get_text(" ", strip=True)):
                blocks = [cell]
            for block in blocks:
                block_text = normalize_space(block.get_text(" ", strip=True))
                code_match = re.search(r"([A-Z]\d+[A-Z]?)\s*\[([^\]]+)\]", block_text)
                if not code_match:
                    continue

                name_node = None
                for span in block.find_all("span"):
                    classes = span.get("class", [])
                    if "text-muted" not in classes:
                        name_node = span
                        break
                course_name = normalize_space(name_node.get_text(" ", strip=True) if name_node else "")

                meta_node = None
                for div in block.find_all("div"):
                    if "max-width" in (div.get("style") or ""):
                        meta_node = div
                        break
                meta_text = normalize_space(meta_node.get_text(" ", strip=True) if meta_node else "")
                teacher_node = meta_node.find("i") if isinstance(meta_node, Tag) else None
                teacher = normalize_space(teacher_node.get_text(" ", strip=True)) if isinstance(teacher_node, Tag) else None
                weeks = meta_text.replace(teacher or "", "").strip() or None

                location_node = block.find("span", class_="text-muted")
                location_text = normalize_space(location_node.get_text(" ", strip=True) if isinstance(location_node, Tag) else "")
                campus, building, room = split_location(location_text)

                entries.append(
                    CourseEntry(
                        weekday=weekday,
                        period=period_label,
                        time_range=time_range,
                        course_code=code_match.group(1),
                        section=code_match.group(2),
                        course_name=course_name,
                        teacher=teacher,
                        weeks=weeks,
                        campus=campus,
                        building=building,
                        room=room,
                        location_text=location_text or None,
                    )
                )

    return TimetableData(days=days, periods=periods, entries=entries)


def parse_exams(html: str, requested_term: str | None = None) -> ExamData:
    soup = BeautifulSoup(html, "html.parser")
    options, current_term = parse_select_options(soup, "zxjxjhh")
    table = soup.find("table", class_="table")
    items: list[ExamItem] = []
    if isinstance(table, Tag):
        rows = table.find_all("tr", recursive=False)
        for row in rows[1:]:
            cells = [normalize_space(cell.get_text(" ", strip=True)) for cell in row.find_all(["th", "td"], recursive=False)]
            if len(cells) < 8:
                continue
            items.append(
                ExamItem(
                    term=requested_term or current_term,
                    course_name=cells[1],
                    schedule=cells[2],
                    exam_mode=cells[3],
                    remark=cells[4],
                    registration=cells[5],
                    status=cells[6],
                )
            )
    return ExamData(current_term=requested_term or current_term, available_terms=options, items=items)


def parse_scores(html: str, requested_term: str | None = None) -> ScoreData:
    soup = BeautifulSoup(html, "html.parser")
    options, current_term = parse_select_options(soup, "zxjxjhh")
    table = soup.find("table", class_="table")
    items: list[ScoreItem] = []
    if isinstance(table, Tag):
        rows = table.find_all("tr", recursive=False)
        for row in rows[1:]:
            cells = [normalize_space(cell.get_text(" ", strip=True)) for cell in row.find_all(["th", "td"], recursive=False)]
            if len(cells) < 8:
                continue
            items.append(
                ScoreItem(
                    term=requested_term or current_term or cells[1],
                    course_name=cells[2],
                    credit=cells[3],
                    score=cells[4],
                    bonus_score=cells[5],
                    teacher=cells[6],
                    detail=cells[7],
                )
            )
    return ScoreData(current_term=requested_term or current_term, available_terms=options, items=items)


def parse_empty_rooms(html: str, requested_query: dict[str, Any] | None = None) -> EmptyRoomData:
    soup = BeautifulSoup(html, "html.parser")
    table = soup.find("table", class_="table")
    if not isinstance(table, Tag):
        return EmptyRoomData(query=requested_query or {})

    rows = table.find_all("tr", recursive=False)
    if len(rows) < 2:
        return EmptyRoomData(query=requested_query or {})

    slot_days: list[tuple[str, str | None]] = []
    header_cells = rows[0].find_all("th", recursive=False)
    for cell in header_cells[1:]:
        text = normalize_space(cell.get_text(" ", strip=True))
        parts = text.split(" ")
        day = parts[0] if parts else text
        date = parts[1] if len(parts) > 1 else None
        colspan = int(cell.get("colspan", "1"))
        for _ in range(colspan):
            slot_days.append((day, date))

    period_cells = rows[1].find_all(["th", "td"], recursive=False)[1:]
    slots: list[EmptyRoomSlotHeader] = []
    periods: list[int] = []
    for index, cell in enumerate(period_cells):
        period_text = normalize_space(cell.get_text(" ", strip=True))
        period = int(period_text) if period_text.isdigit() else index + 1
        day, date = slot_days[index] if index < len(slot_days) else ("", None)
        slots.append(EmptyRoomSlotHeader(day=day, date=date, period=period))
        if period not in periods:
            periods.append(period)

    rooms: list[EmptyRoomRow] = []
    for row in rows[2:]:
        cells = row.find_all(["th", "td"], recursive=False)
        if len(cells) < 2:
            continue
        room_text = normalize_space(cells[0].get_text(" ", strip=True))
        if not room_text:
            continue
        seat_match = re.search(r"\(([^)]+)\)", room_text)
        room_name = room_text.split(" ")[0]
        availability: list[bool] = []
        for cell in cells[1:]:
            style = (cell.get("style") or "").lower()
            cell_text = normalize_space(cell.get_text(" ", strip=True))
            if "#fff" in style or "white" in style:
                is_available = True
            elif style:
                is_available = False
            elif any(marker in cell_text for marker in ("占用", "有课", "不可")):
                is_available = False
            elif any(marker in cell_text for marker in ("空闲", "无课", "可用")):
                is_available = True
            else:
                is_available = not cell_text
            availability.append(is_available)
        rooms.append(
            EmptyRoomRow(
                room=room_name,
                seat_label=seat_match.group(1) if seat_match else None,
                availability=availability,
            )
        )

    query = requested_query or {}
    if not query:
        query = {
            "term": parse_input_value(soup, "zxjxjhh"),
            "week": parse_input_value(soup, "zc"),
            "building": parse_input_value(soup, "jxlh"),
            "room": parse_input_value(soup, "jash"),
        }
    days = [normalize_space(" ".join(filter(None, (slot.day, slot.date)))) for slot in slots]
    unique_days: list[str] = []
    for day in days:
        if day and day not in unique_days:
            unique_days.append(day)

    return EmptyRoomData(query=query, days=unique_days, periods=periods, slots=slots, rooms=rooms)
