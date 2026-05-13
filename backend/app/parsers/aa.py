from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import urljoin

from bs4 import BeautifulSoup, Tag

from ..schemas import (
    AcademicProgressCourse,
    AcademicProgressData,
    CreditBucket,
    CreditSummary,
    CourseEntry,
    CourseSelectionCourse,
    CourseSelectionData,
    EmptyRoomData,
    EmptyRoomRow,
    EmptyRoomSlotHeader,
    ExamData,
    ExamItem,
    ProfileField,
    ProfileSection,
    ScoreData,
    ScoreDetailData,
    ScoreDetailField,
    ScoreDetailTable,
    ScoreItem,
    StudentProfileData,
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


@dataclass(frozen=True)
class CourseSelectionAction:
    action_url: str
    method: str
    fields: dict[str, str]


@dataclass(frozen=True)
class ParsedCourseSelectionPage:
    data: CourseSelectionData
    actions: dict[str, CourseSelectionAction]
    drop_actions: dict[str, CourseSelectionAction]


def _course_selection_key(course_name: str) -> tuple[str, str | None, str | None]:
    text = normalize_space(course_name)
    code_match = re.match(r"^([A-Za-z]\d+[A-Za-z]?)", text)
    course_code = code_match.group(1) if code_match else None
    section_match = re.search(r"\s(\d{2})(?:\s|$)", text)
    section = section_match.group(1) if section_match else None
    if course_code and section:
        return f"{course_code}_{section}", course_code, section
    if course_code:
        return course_code, course_code, section
    return text, course_code, section


def _parse_remaining(value: str) -> int | None:
    match = re.search(r"-?\d+", normalize_space(value))
    return int(match.group(0)) if match else None


def _form_fields(form: Tag | None) -> dict[str, str]:
    fields: dict[str, str] = {}
    if not isinstance(form, Tag):
        return fields
    for input_node in form.find_all("input"):
        if not isinstance(input_node, Tag):
            continue
        name = normalize_space(str(input_node.get("name") or ""))
        if not name:
            continue
        input_type = normalize_space(str(input_node.get("type") or "")).lower()
        if input_type in {"checkbox", "radio", "submit", "button", "image", "file"}:
            continue
        fields[name] = str(input_node.get("value") or "")
    return fields


def _submit_action(soup: BeautifulSoup, page_url: str) -> tuple[str | None, str, dict[str, str], str | None]:
    submit = (
        soup.select_one("a.btn-primary")
        or soup.select_one("button.btn-primary")
        or soup.select_one("input[type=submit]")
    )
    form = submit.find_parent("form") if isinstance(submit, Tag) else None
    if form is None:
        form = soup.find("form")

    method = normalize_space(str(form.get("method") if isinstance(form, Tag) else "")).lower() or "post"
    fields = _form_fields(form if isinstance(form, Tag) else None)

    candidates: list[str] = []
    if isinstance(submit, Tag):
        for attr in ("data-url", "data-href", "data-action", "formaction", "href"):
            value = normalize_space(str(submit.get(attr) or ""))
            if value and value != "#" and not value.lower().startswith("javascript:"):
                candidates.append(value)
    if isinstance(form, Tag):
        action = normalize_space(str(form.get("action") or ""))
        candidates.append(action or page_url)

    for candidate in candidates:
        if candidate:
            return urljoin(page_url, candidate), method, fields, None
    return None, method, fields, "无法解析选课提交入口：页面没有暴露 form/action/data-url。"


def _checkbox_payload(cell: Tag) -> tuple[str | None, str | None]:
    checkbox = cell.select_one("input[type=checkbox]")
    if not isinstance(checkbox, Tag):
        return None, None
    name = normalize_space(str(checkbox.get("name") or ""))
    value = str(checkbox.get("value") or "on")
    if not name:
        return None, None
    return name, value


def _onclick_action_candidate(value: str) -> str | None:
    for match in re.finditer(r"""["']([^"']*(?:delete|courseselecttask)[^"']*)["']""", value, flags=re.IGNORECASE):
        candidate = normalize_space(match.group(1))
        if candidate and not candidate.lower().startswith("javascript:"):
            return candidate
    return None


def _drop_action(cell: Tag | None, page_url: str, base_fields: dict[str, str]) -> CourseSelectionAction | None:
    if not isinstance(cell, Tag):
        return None
    trigger = (
        cell.select_one(".select-delete-btn")
        or cell.select_one("[data-pk]")
        or cell.select_one("[data-url], [data-href], [data-action], [formaction]")
        or cell.find(["a", "button", "input"])
    )
    if not isinstance(trigger, Tag):
        return None

    data_pk = normalize_space(str(trigger.get("data-pk") or trigger.get("pk") or trigger.get("value") or ""))
    candidates: list[str] = []
    for attr in ("href", "data-url", "data-href", "data-action", "formaction"):
        value = normalize_space(str(trigger.get(attr) or ""))
        if value and value != "#" and not value.lower().startswith("javascript:"):
            candidates.append(value)

    onclick = normalize_space(str(trigger.get("onclick") or ""))
    onclick_candidate = _onclick_action_candidate(onclick)
    if onclick_candidate:
        candidates.append(onclick_candidate)

    if not candidates and data_pk:
        candidates.append("/course_selection/courseselecttask/delete/")

    action_url = next((urljoin(page_url, candidate) for candidate in candidates if candidate), None)
    if not action_url:
        return None

    method = normalize_space(str(trigger.get("data-method") or trigger.get("method") or "post")).lower() or "post"
    fields = dict(base_fields)
    if data_pk:
        fields["pk"] = data_pk
    return CourseSelectionAction(action_url=action_url, method=method, fields=fields)


def _course_from_cells(cells: list[Tag], *, selected: bool, index: int) -> CourseSelectionCourse | None:
    texts = [normalize_space(cell.get_text(" ", strip=True)) for cell in cells]
    if len(texts) < 2:
        return None
    name = texts[1]
    if not name:
        return None
    key, course_code, section = _course_selection_key(name)
    status = texts[0] if texts[0] else ("selected" if selected else "available")
    remaining_text = texts[2] if len(texts) > 2 and texts[2] else None
    return CourseSelectionCourse(
        key=key or f"course_{index}",
        status=status,
        selected=selected or any(marker in status for marker in ("已选", "selected", "Selected")),
        course_name=name,
        course_code=course_code,
        section=section,
        remaining=_parse_remaining(remaining_text or ""),
        remaining_text=remaining_text,
        credit=texts[3] if len(texts) > 3 and texts[3] else None,
        course_type=texts[4] if len(texts) > 4 and texts[4] else None,
        exam_type=texts[5] if len(texts) > 5 and texts[5] else None,
        teacher=texts[6] if len(texts) > 6 and texts[6] else None,
        time_location=texts[7] if len(texts) > 7 and texts[7] else None,
        note=texts[8] if len(texts) > 8 and texts[8] else None,
    )


def _direct_table_rows(table: Tag | None) -> list[list[Tag]]:
    if not isinstance(table, Tag):
        return []
    rows: list[list[Tag]] = []
    for row in table.find_all("tr"):
        cells = [cell for cell in row.find_all(["th", "td"], recursive=False) if isinstance(cell, Tag)]
        if cells:
            rows.append(cells)
    return rows


def parse_course_selection_page(
    html: str,
    page_url: str = "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects/",
) -> ParsedCourseSelectionPage:
    soup = BeautifulSoup(html, "html.parser")
    selected_courses: list[CourseSelectionCourse] = []
    available_courses: list[CourseSelectionCourse] = []
    actions: dict[str, CourseSelectionAction] = {}
    drop_actions: dict[str, CourseSelectionAction] = {}
    submit_url, method, base_fields, submit_error = _submit_action(soup, page_url)
    can_submit = submit_url is not None

    selected_container = soup.select_one("#selected-container")
    selected_table = selected_container.find("table") if isinstance(selected_container, Tag) else None
    for index, cells in enumerate(_direct_table_rows(selected_table)[1:]):
        course = _course_from_cells(cells, selected=True, index=index)
        if course:
            selected_courses.append(course)
            action = _drop_action(cells[0] if cells else None, page_url, base_fields)
            if action is not None:
                drop_actions[course.key] = action

    candidate_tables = soup.select("table.table-bordered") or soup.find_all("table")
    tables = [
        table
        for table in candidate_tables
        if isinstance(table, Tag) and table.find_parent(id="selected-container") is None
    ]
    available_table = tables[1] if len(tables) > 1 else (tables[0] if tables else None)

    for index, cells in enumerate(_direct_table_rows(available_table)[1:]):
        course = _course_from_cells(cells, selected=False, index=index)
        if not course:
            continue
        available_courses.append(course)
        checkbox_name, checkbox_value = _checkbox_payload(cells[0])
        if submit_url and checkbox_name:
            fields = dict(base_fields)
            fields[checkbox_name] = checkbox_value or "on"
            actions[course.key] = CourseSelectionAction(
                action_url=submit_url,
                method=method,
                fields=fields,
            )
        elif checkbox_name is None:
            can_submit = False
            submit_error = submit_error or "无法解析选课提交入口：目标课程行没有 checkbox name。"

    return ParsedCourseSelectionPage(
        data=CourseSelectionData(
            selected_courses=selected_courses,
            available_courses=available_courses,
            can_submit=can_submit,
            submit_error=submit_error,
        ),
        actions=actions,
        drop_actions=drop_actions,
    )


def parse_course_selection_captcha(
    html: str,
    page_url: str,
) -> tuple[str | None, str | None, dict[str, str], str | None]:
    soup = BeautifulSoup(html, "html.parser")
    modal = next(
        (
            node
            for node in soup.select(".modal, .bootbox")
            if isinstance(node, Tag) and node.find("img") is not None
        ),
        soup,
    )
    image = modal.find("img") if isinstance(modal, Tag) else soup.find("img")
    image_url = urljoin(page_url, normalize_space(str(image.get("src") or ""))) if isinstance(image, Tag) else None
    form = image.find_parent("form") if isinstance(image, Tag) else None
    if form is None:
        form = soup.find("form")
    action = urljoin(page_url, normalize_space(str(form.get("action") or "")) or page_url) if isinstance(form, Tag) else page_url
    fields = _form_fields(form if isinstance(form, Tag) else None)
    input_name = None
    inputs = form.find_all("input") if isinstance(form, Tag) else soup.find_all("input")
    for input_node in inputs:
        if not isinstance(input_node, Tag):
            continue
        input_type = normalize_space(str(input_node.get("type") or "text")).lower()
        name = normalize_space(str(input_node.get("name") or ""))
        if name and input_type not in {"hidden", "submit", "button", "checkbox", "radio"}:
            input_name = name
            break
    prompt = normalize_space(modal.get_text(" ", strip=True) if isinstance(modal, Tag) else soup.get_text(" ", strip=True))
    if action:
        fields["__action__"] = action
    return image_url, input_name, fields, prompt or None


def _clean_table_cell_text(cell: Tag) -> str:
    html = str(cell)
    html = re.sub(r"<img\b[^>]*>", " ", html, flags=re.IGNORECASE | re.DOTALL)
    html = re.sub(r"&lt;img\b.*?&gt;", " ", html, flags=re.IGNORECASE | re.DOTALL)
    text = BeautifulSoup(html, "html.parser").get_text(" ", strip=True)
    text = re.sub(r"data:image/[^\"'\s>]+", " ", text, flags=re.IGNORECASE)
    return normalize_space(text)


def _extract_image_src(html: str, soup: BeautifulSoup) -> str | None:
    for image in soup.find_all("img"):
        if not isinstance(image, Tag):
            continue
        src = normalize_space(str(image.get("src") or ""))
        if not src or "static/" in src or src.endswith("/user.jpg"):
            continue
        return src

    match = re.search(r"<img\b[^>]*\bsrc=[\"']([^\"']+)[\"']", html, flags=re.IGNORECASE | re.DOTALL)
    if match:
        return match.group(1).strip()
    return None


PROFILE_FIELD_MAP = {
    "学号": "student_id",
    "姓名": "name",
    "性别": "gender",
    "出生日期": "birthday",
    "姓名拼音": "name_pinyin",
    "英文姓名": "english_name",
    "民族": "ethnicity",
    "政治面貌": "political_status",
    "是否留学生": "is_international_student",
    "国家或地区": "nationality",
    "学院": "college",
    "专业": "major",
    "年级": "grade",
    "班级": "class_name",
    "是否有学籍": "has_student_status",
    "学籍状态": "student_status",
    "学生类别": "student_category",
    "异动否": "change_status",
    "学生": "education_level",
    "培养方式": "cultivation_method",
    "是否旁听生": "is_auditor",
    "授课语种": "study_language",
    "校区": "campus",
}


def parse_student_status_profile(html: str) -> StudentProfileData:
    soup = BeautifulSoup(html, "html.parser")
    tables = soup.find_all("table", class_="table") or soup.find_all("table")
    avatar_url = _extract_image_src(html, soup)
    if not tables:
        return StudentProfileData(avatar_url=avatar_url)

    sections: list[ProfileSection] = []
    flat_fields: list[ProfileField] = []
    profile_values: dict[str, str] = {}
    current_section: ProfileSection | None = None

    def ensure_section(title: str) -> ProfileSection:
        nonlocal current_section
        title = title or "学籍信息"
        if current_section is None or current_section.title != title:
            current_section = ProfileSection(title=title, fields=[])
            sections.append(current_section)
        return current_section

    def add_field(label: str, value: str) -> None:
        label = label.rstrip(":：")
        if not label:
            return
        section = current_section or ensure_section("学籍信息")
        field = ProfileField(label=label, value=value)
        section.fields.append(field)
        flat_fields.append(field)
        attr = PROFILE_FIELD_MAP.get(label)
        if attr and value and not profile_values.get(attr):
            profile_values[attr] = value

    for table in tables:
        if not isinstance(table, Tag):
            continue
        for row in table.find_all("tr"):
            cells = [cell for cell in row.find_all(["th", "td"], recursive=False) if isinstance(cell, Tag)]
            if not cells:
                continue
            texts = [_clean_table_cell_text(cell) for cell in cells]
            visible_texts = [text for text in texts if text]
            if len(visible_texts) == 1 and (len(cells) == 1 or cells[0].get("colspan")):
                current_section = ProfileSection(title=visible_texts[0], fields=[])
                sections.append(current_section)
                continue

            # Keep blank value cells so label/value alignment survives, but drop the
            # row-spanned photo cell.
            pair_texts: list[str] = []
            for cell, text in zip(cells, texts):
                raw_cell = str(cell)
                has_image = bool(cell.find("img")) or "<img" in raw_cell or "data:image" in raw_cell
                if has_image:
                    continue
                pair_texts.append(text)
            for index in range(0, len(pair_texts) - 1, 2):
                add_field(pair_texts[index], pair_texts[index + 1])

    return StudentProfileData(
        avatar_url=avatar_url,
        fields=flat_fields,
        sections=[section for section in sections if section.fields],
        **profile_values,
    )


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
        rows = table.find_all("tr")
        for row in rows[1:]:
            cell_nodes = row.find_all(["th", "td"], recursive=False)
            cells = [normalize_space(cell.get_text(" ", strip=True)) for cell in cell_nodes]
            if len(cells) < 8:
                continue
            detail_cell = cell_nodes[7] if len(cell_nodes) > 7 else None
            items.append(
                ScoreItem(
                    term=requested_term or cells[1] or current_term,
                    course_name=cells[2],
                    credit=cells[3],
                    score=cells[4],
                    bonus_score=cells[5],
                    teacher=cells[6],
                    detail=cells[7],
                    detail_path=_extract_score_detail_path(detail_cell) if isinstance(detail_cell, Tag) else None,
                )
            )
    return ScoreData(current_term=requested_term or current_term, available_terms=options, items=items)


def _extract_score_detail_path(cell: Tag) -> str | None:
    candidates: list[str] = []
    for link in cell.find_all("a"):
        if not isinstance(link, Tag):
            continue
        for attr in ("href", "data-url", "data-href"):
            value = normalize_space(str(link.get(attr) or ""))
            if value:
                candidates.append(value)
        onclick = normalize_space(str(link.get("onclick") or ""))
        if onclick:
            candidates.append(onclick)

    cell_html = str(cell)
    candidates.append(cell_html)
    for candidate in candidates:
        direct = _clean_score_detail_candidate(candidate)
        if direct:
            return direct
        for match in re.findall(r"""["']([^"']*(?:score|cj|grade)[^"']*)["']""", candidate, flags=re.IGNORECASE):
            cleaned = _clean_score_detail_candidate(match)
            if cleaned:
                return cleaned
    return None


def _clean_score_detail_candidate(value: str) -> str | None:
    value = normalize_space(value)
    if not value or value == "#" or value.lower().startswith(("javascript:void", "void(")):
        return None
    if value.lower().startswith("javascript:"):
        value = value[len("javascript:") :]
    match = re.search(r"""(https?://aa\.bjtu\.edu\.cn/[^\s"'<>]+|/[^\s"'<>]*(?:score|cj|grade)[^\s"'<>]*)""", value, flags=re.IGNORECASE)
    if match:
        return match.group(1).rstrip(");,")
    if re.search(r"(?:score|cj|grade)", value, flags=re.IGNORECASE) and not re.search(r"\s", value):
        return value.rstrip(");,")
    return None


def parse_score_detail(html: str) -> ScoreDetailData:
    soup = BeautifulSoup(html, "html.parser")
    title = _score_detail_title(soup)
    fields: list[ScoreDetailField] = []
    tables: list[ScoreDetailTable] = []
    seen_fields: set[tuple[str, str]] = set()

    def add_field(label: str, value: str) -> None:
        label = normalize_space(label).rstrip(":：")
        value = normalize_space(value)
        if not label or not value:
            return
        key = (label, value)
        if key in seen_fields:
            return
        seen_fields.add(key)
        fields.append(ScoreDetailField(label=label, value=value))

    for table in soup.find_all("table"):
        if not isinstance(table, Tag):
            continue
        rows = _table_rows(table)
        if not rows:
            continue

        for row in rows:
            if len(row) >= 2 and len(row) % 2 == 0:
                for index in range(0, len(row), 2):
                    label = row[index]
                    value = row[index + 1]
                    if len(label) <= 32:
                        add_field(label, value)

        header_cells = table.find("tr").find_all(["th", "td"], recursive=False) if table.find("tr") else []
        has_header = bool(header_cells) and all(cell.name == "th" for cell in header_cells)
        headers = rows[0] if has_header else []
        body_rows = rows[1:] if has_header else rows
        is_field_table = not has_header and all(len(row) >= 2 and len(row) % 2 == 0 for row in rows)
        if body_rows and not is_field_table:
            tables.append(
                ScoreDetailTable(
                    title=_table_title(table),
                    headers=headers,
                    rows=body_rows,
                )
            )

    raw_text = normalize_space(soup.get_text(" ", strip=True))
    if len(raw_text) > 4000:
        raw_text = f"{raw_text[:4000]}..."
    return ScoreDetailData(
        title=title,
        fields=fields,
        tables=tables,
        raw_text=raw_text or None,
    )


def _score_detail_title(soup: BeautifulSoup) -> str | None:
    for selector in (".modal-title", "h1", "h2", "h3", "legend", "title"):
        node = soup.select_one(selector)
        if node:
            text = normalize_space(node.get_text(" ", strip=True))
            if text:
                return text
    return None


def _table_title(table: Tag) -> str | None:
    caption = table.find("caption")
    if isinstance(caption, Tag):
        text = normalize_space(caption.get_text(" ", strip=True))
        if text:
            return text
    previous = table.find_previous(["h1", "h2", "h3", "h4", "legend"])
    if isinstance(previous, Tag):
        text = normalize_space(previous.get_text(" ", strip=True))
        if text:
            return text
    return None


def parse_credit(value: Any) -> float | None:
    match = re.search(r"\d+(?:\.\d+)?", str(value or ""))
    return float(match.group(0)) if match else None


def is_passing_score(score: str | None) -> bool:
    value = normalize_space(score or "")
    if not value:
        return False
    numeric = parse_credit(value)
    if numeric is not None:
        return numeric >= 60
    if any(marker in value for marker in ("不及格", "未通过", "不合格")):
        return False
    normalized_grade = value.upper().replace("＋", "+").replace("－", "-")
    grade_match = re.search(r"(?:^|[^A-Z])([A-D][+-]?|F)(?:$|[^A-Z])", normalized_grade)
    if grade_match:
        letter_grade = grade_match.group(1)
        return letter_grade in {"A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D"}
    return any(marker in value for marker in ("通过", "合格", "及格", "中", "良", "优", "优秀"))


def _score_to_progress_course(item: ScoreItem) -> AcademicProgressCourse:
    passed = is_passing_score(item.score)
    return AcademicProgressCourse(
        term=item.term,
        course_name=item.course_name,
        credit=parse_credit(item.credit),
        score=item.score,
        status="passed" if passed else "attention",
        detail=item.detail,
    )


def _compute_credit_summary(courses: list[AcademicProgressCourse], target_credits: float | None = None) -> CreditSummary:
    attempted = sum(course.credit or 0 for course in courses)
    passed = sum(course.credit or 0 for course in courses if course.status == "passed")
    failed = sum(course.credit or 0 for course in courses if course.status != "passed")
    denominator = target_credits if target_credits and target_credits > 0 else attempted
    completion_rate = round(passed / denominator * 100, 1) if denominator else 0
    return CreditSummary(
        course_count=len(courses),
        passed_course_count=sum(1 for course in courses if course.status == "passed"),
        failed_course_count=sum(1 for course in courses if course.status != "passed"),
        attempted_credits=round(attempted, 2),
        passed_credits=round(passed, 2),
        failed_credits=round(failed, 2),
        target_credits=target_credits,
        completion_rate=min(completion_rate, 100),
    )


def _table_rows(table: Tag) -> list[list[str]]:
    rows: list[list[str]] = []
    for row in table.find_all("tr"):
        cells = [normalize_space(cell.get_text(" ", strip=True)) for cell in row.find_all(["th", "td"], recursive=False)]
        if any(cells):
            rows.append(cells)
    return rows


def _header_index(headers: list[str], *needles: str) -> int | None:
    for index, header in enumerate(headers):
        if any(needle in header for needle in needles):
            return index
    return None


def _parse_scorecard_fields_and_buckets(html: str) -> tuple[list[ProfileField], list[CreditBucket]]:
    soup = BeautifulSoup(html, "html.parser")
    fields: list[ProfileField] = []
    buckets: list[CreditBucket] = []

    def add_field(label: str, value: str) -> None:
        if not label or not value:
            return
        if any(item.value == value and item.label == label for item in fields):
            return
        fields.append(ProfileField(value=value, label=label))

    for table in soup.find_all("table"):
        if not isinstance(table, Tag):
            continue
        rows = _table_rows(table)
        if not rows:
            continue

        for row in rows:
            if len(row) >= 2 and len(row) % 2 == 0:
                for index in range(0, len(row), 2):
                    label = row[index].rstrip(":：")
                    value = row[index + 1]
                    if label and value and len(label) <= 16:
                        add_field(label, value)

        headers = rows[0]
        if len(rows) < 2:
            continue
        required_index = _header_index(headers, "要求", "应修", "计划")
        earned_index = _header_index(headers, "获得", "已获", "已修", "完成")
        pending_index = _header_index(headers, "欠", "差", "未修", "还需")
        name_index = _header_index(headers, "类别", "性质", "模块", "课程类型")
        if name_index is None:
            name_index = 0
        if required_index is None and earned_index is None:
            continue

        for row in rows[1:]:
            if len(row) <= name_index:
                continue
            name = row[name_index]
            if not name or "合计" in name:
                continue
            required = parse_credit(row[required_index]) if required_index is not None and len(row) > required_index else None
            earned = parse_credit(row[earned_index]) if earned_index is not None and len(row) > earned_index else None
            pending = parse_credit(row[pending_index]) if pending_index is not None and len(row) > pending_index else None
            if required is None and earned is None and pending is None:
                continue
            rate = round((earned or 0) / required * 100, 1) if required and required > 0 else None
            buckets.append(
                CreditBucket(
                    name=name,
                    required_credits=required,
                    earned_credits=round(earned or 0, 2),
                    pending_credits=pending,
                    completion_rate=min(rate, 100) if rate is not None else None,
                )
            )

    return fields, buckets


def _parse_replace_courses(html: str | None) -> list[dict[str, Any]]:
    if not html:
        return []
    soup = BeautifulSoup(html, "html.parser")
    records: list[dict[str, Any]] = []
    for table in soup.find_all("table"):
        if not isinstance(table, Tag):
            continue
        rows = _table_rows(table)
        if len(rows) < 2:
            continue
        headers = rows[0]
        if not any("替代" in header or "课程" in header for header in headers):
            continue
        for row in rows[1:]:
            record = {
                headers[index] or f"列{index + 1}": value
                for index, value in enumerate(row)
                if index < len(headers) and value
            }
            if record:
                records.append(record)
    return records


def parse_scorecard_progress(
    scorecard_html: str,
    *,
    replace_html: str | None = None,
    scores: ScoreData | None = None,
) -> AcademicProgressData:
    fields, buckets = _parse_scorecard_fields_and_buckets(scorecard_html)
    courses = [_score_to_progress_course(item) for item in (scores.items if scores else [])]
    target_credits = None
    if buckets:
        target_credits = round(sum(bucket.required_credits or 0 for bucket in buckets), 2) or None
    summary = _compute_credit_summary(courses, target_credits=target_credits)
    if buckets and summary.passed_credits > 0:
        bucket_earned = sum(bucket.earned_credits for bucket in buckets)
        if bucket_earned <= 0:
            buckets = [
                bucket.model_copy(update={"earned_credits": summary.passed_credits})
                if index == 0
                else bucket
                for index, bucket in enumerate(buckets)
            ]
    return AcademicProgressData(
        current_term=scores.current_term if scores else None,
        summary=summary,
        buckets=buckets,
        courses=courses,
        replace_courses=_parse_replace_courses(replace_html),
        fields=fields,
    )


def parse_academic_progress_detail_path(html: str) -> str | None:
    soup = BeautifulSoup(html, "html.parser")
    for link in soup.find_all("a"):
        if not isinstance(link, Tag):
            continue
        href = normalize_space(str(link.get("href") or ""))
        text = normalize_space(link.get_text(" ", strip=True))
        if href and "stustudyview" in href and ("查看" in text or not text):
            return href
    match = re.search(r"(/school_census/schooltraininfo/stustudyview/\d+/)", html)
    return match.group(1) if match else None


def _make_credit_bucket(name: str, required: Any, earned: Any, *, parent: str | None = None) -> CreditBucket | None:
    name = normalize_space(name)
    if not name:
        return None
    required_credits = parse_credit(required)
    earned_credits = parse_credit(earned) or 0
    if required_credits is None and earned_credits == 0:
        return None
    pending = None
    rate = None
    if required_credits is not None:
        pending = max(round(required_credits - earned_credits, 2), 0)
        if required_credits > 0:
            rate = min(round(earned_credits / required_credits * 100, 1), 100)
    return CreditBucket(
        name=name,
        required_credits=required_credits,
        earned_credits=round(earned_credits, 2),
        pending_credits=pending,
        completion_rate=rate,
        parent=parent,
    )


def _parse_merged_progress_buckets(table: Tag) -> list[CreditBucket]:
    buckets: list[CreditBucket] = []
    for row in _table_rows(table)[1:]:
        if len(row) < 3:
            continue
        bucket = _make_credit_bucket(row[0], row[1], row[2])
        if bucket:
            buckets.append(bucket)
    return buckets


def _parse_detail_progress_buckets(table: Tag) -> list[CreditBucket]:
    buckets: list[CreditBucket] = []
    current_parent: str | None = None
    for row in _table_rows(table)[1:]:
        if len(row) >= 4:
            current_parent = row[0]
            bucket = _make_credit_bucket(row[1], row[2], row[3], parent=current_parent)
        elif len(row) >= 3:
            bucket = _make_credit_bucket(row[0], row[1], row[2], parent=current_parent)
        else:
            continue
        if bucket:
            buckets.append(bucket)
    return buckets


def _split_course_code_and_name(value: str) -> tuple[str | None, str]:
    text = normalize_space(value)
    match = re.match(r"^([A-Z]\d+[A-Z]?)\s+(.+)$", text)
    if not match:
        return None, text
    return match.group(1), match.group(2)


def _parse_progress_courses(table: Tag) -> list[AcademicProgressCourse]:
    rows = _table_rows(table)
    if len(rows) < 2:
        return []
    headers = rows[0]
    term_index = _header_index(headers, "学年学期", "学期")
    course_index = _header_index(headers, "课程")
    credit_index = _header_index(headers, "学分")
    exam_index = _header_index(headers, "考试时间", "考试日期")
    score_index = _header_index(headers, "课程成绩", "成绩")
    group_index = _header_index(headers, "课组信息", "课组")
    if course_index is None:
        return []

    courses: list[AcademicProgressCourse] = []
    for row in rows[1:]:
        if len(row) <= course_index:
            continue
        code, name = _split_course_code_and_name(row[course_index])
        score = row[score_index] if score_index is not None and len(row) > score_index else None
        courses.append(
            AcademicProgressCourse(
                term=row[term_index] if term_index is not None and len(row) > term_index else None,
                course_code=code,
                course_name=name,
                credit=parse_credit(row[credit_index]) if credit_index is not None and len(row) > credit_index else None,
                exam_date=row[exam_index] if exam_index is not None and len(row) > exam_index else None,
                score=score,
                status="passed" if is_passing_score(score) else "attention",
                group_info=row[group_index] if group_index is not None and len(row) > group_index else None,
                source="academic_progress",
            )
        )
    return courses


def parse_academic_progress(html: str) -> AcademicProgressData:
    soup = BeautifulSoup(html, "html.parser")
    merged_buckets: list[CreditBucket] = []
    detail_buckets: list[CreditBucket] = []
    courses: list[AcademicProgressCourse] = []

    for table in soup.find_all("table"):
        if not isinstance(table, Tag):
            continue
        rows = _table_rows(table)
        if not rows:
            continue
        headers = rows[0]
        header_text = " ".join(headers)
        if "合并课组名称" in header_text:
            merged_buckets = _parse_merged_progress_buckets(table)
        elif "课组类型" in header_text and "已完成学分" in header_text:
            detail_buckets = _parse_detail_progress_buckets(table)
        elif "课程成绩" in header_text and "课组信息" in header_text:
            courses = _parse_progress_courses(table)

    primary_buckets = merged_buckets or detail_buckets
    target_credits = round(sum(bucket.required_credits or 0 for bucket in primary_buckets), 2) or None
    earned_credits = round(sum(bucket.earned_credits for bucket in primary_buckets), 2)
    incomplete_buckets = sum(
        1
        for bucket in primary_buckets
        if bucket.required_credits is not None and bucket.earned_credits < bucket.required_credits
    )
    completion_rate = round(earned_credits / target_credits * 100, 1) if target_credits else 0
    completion_rate = min(completion_rate, 100)
    summary = CreditSummary(
        course_count=len(courses),
        passed_course_count=sum(1 for course in courses if course.status == "passed"),
        failed_course_count=incomplete_buckets,
        attempted_credits=earned_credits,
        passed_credits=earned_credits,
        failed_credits=round(max((target_credits or 0) - earned_credits, 0), 2),
        target_credits=target_credits,
        completion_rate=completion_rate,
    )
    return AcademicProgressData(
        summary=summary,
        buckets=primary_buckets,
        merged_buckets=merged_buckets,
        detail_buckets=detail_buckets,
        courses=courses,
    )


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
