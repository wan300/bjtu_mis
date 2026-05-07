from __future__ import annotations

import re
from typing import Any

from bs4 import BeautifulSoup

from ..schemas import (
    CalendarData,
    CalendarItem,
    CourseResourceFolder,
    CourseResourceItem,
    CourseResourcesData,
    CourseSummary,
    HomeworkData,
    HomeworkItem,
    ProfileField,
    StudentProfileData,
    TermOption,
)


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
        submitted_at = str(entry.get("subTime") or "").strip() or None
        submission_status = str(entry.get("subStatus") or "").strip() or None
        items.append(
            HomeworkItem(
                homework_id=int(homework_id) if homework_id is not None else None,
                course=str(entry.get("course_name") or course.course_name).strip() or course.course_name,
                course_id=course.course_id,
                course_code=course.course_code,
                title=title,
                content_excerpt=strip_html_excerpt(str(entry.get("content") or "")),
                opened_at=str(entry.get("open_date") or "").strip() or None,
                due_at=str(entry.get("end_time") or "").strip() or None,
                submitted_at=submitted_at,
                status="done" if submitted_at else "open",
                sub_type=sub_type,
                submission_status=submission_status,
                can_submit=True,
                content_type=int(entry.get("content_type") or 0),
                is_group=str(entry.get("is_fz") or "0").strip() == "1",
                return_num=int(entry.get("return_num") or 0),
            )
        )
    return items


def _payload_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _clean_id(value: Any, default: str | None = None) -> str | None:
    text = str(value if value is not None else "").strip()
    if text:
        return text
    return default


def _clean_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _first_present(entry: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in entry and entry[key] not in (None, ""):
            return entry[key]
    return None


def _first_scalar(entry: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = entry.get(key)
        if isinstance(value, (str, int, float)):
            text = str(value).strip()
            if text:
                return text
    return None


def _profile_source(payload: dict[str, Any]) -> dict[str, Any]:
    for key in ("userInfo", "user", "student", "data", "result", "map"):
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    return payload


PROFILE_LABELS = {
    "name": "姓名",
    "userName": "姓名",
    "xm": "姓名",
    "realName": "姓名",
    "studentId": "学号",
    "studentNo": "学号",
    "stuNo": "学号",
    "xh": "学号",
    "loginName": "账号",
    "userId": "账号",
    "account": "账号",
    "sex": "性别",
    "gender": "性别",
    "xb": "性别",
    "college": "学院",
    "collegeName": "学院",
    "deptName": "学院",
    "xy": "学院",
    "major": "专业",
    "majorName": "专业",
    "zymc": "专业",
    "className": "班级",
    "bjmc": "班级",
    "grade": "年级",
    "nj": "年级",
    "educationLevel": "培养层次",
    "pycc": "培养层次",
    "phone": "电话",
    "mobile": "电话",
    "email": "邮箱",
}


IGNORED_PROFILE_KEYS = {
    "STATUS",
    "status",
    "message",
    "sessionId",
    "password",
    "token",
    "roleList",
    "permissionList",
}


def parse_student_profile(payload: dict[str, Any]) -> StudentProfileData:
    source = _profile_source(payload)
    fields: list[ProfileField] = []
    seen_labels: set[str] = set()

    def add_field(label: str, value: str | None) -> None:
        text = str(value or "").strip()
        if not label or not text or label in seen_labels:
            return
        fields.append(ProfileField(label=label, value=text))
        seen_labels.add(label)

    profile = StudentProfileData(
        name=_first_scalar(source, "name", "userName", "xm", "realName", "trueName"),
        student_id=_first_scalar(source, "studentId", "studentNo", "stuNo", "xh", "student_no"),
        account=_first_scalar(source, "loginName", "userId", "account", "userNo", "login_name"),
        gender=_first_scalar(source, "sex", "gender", "xb"),
        college=_first_scalar(source, "college", "collegeName", "deptName", "xy", "academyName"),
        major=_first_scalar(source, "major", "majorName", "zymc", "specialtyName"),
        class_name=_first_scalar(source, "className", "bjmc", "class_name"),
        grade=_first_scalar(source, "grade", "nj", "rxnf"),
        education_level=_first_scalar(source, "educationLevel", "pycc", "levelName"),
        phone=_first_scalar(source, "phone", "mobile", "telephone"),
        email=_first_scalar(source, "email", "mail"),
        avatar_url=_first_scalar(source, "avatar", "avatarUrl", "photo", "photoUrl", "headPic", "userPic"),
    )

    for label, value in (
        ("姓名", profile.name),
        ("学号", profile.student_id),
        ("账号", profile.account),
        ("性别", profile.gender),
        ("学院", profile.college),
        ("专业", profile.major),
        ("班级", profile.class_name),
        ("年级", profile.grade),
        ("培养层次", profile.education_level),
        ("电话", profile.phone),
        ("邮箱", profile.email),
    ):
        add_field(label, value)

    for key, value in source.items():
        if key in IGNORED_PROFILE_KEYS or isinstance(value, (dict, list)):
            continue
        label = PROFILE_LABELS.get(key, key)
        add_field(label, str(value))

    return profile.model_copy(update={"fields": fields})


def parse_course_resource_tree(payload: dict[str, Any]) -> list[CourseResourceFolder]:
    folders: list[CourseResourceFolder] = []
    for entry in _payload_list(payload.get("nodes")):
        if not isinstance(entry, dict):
            continue
        folder_id = _clean_id(entry.get("id"))
        name = str(entry.get("name") or entry.get("bag_name") or "").strip()
        if not folder_id or not name:
            continue
        folders.append(
            CourseResourceFolder(
                folder_id=folder_id,
                name=name,
                parent_id=_clean_id(_first_present(entry, "pId", "pid", "parent_id")),
            )
        )
    return folders


def parse_course_resource_listing(
    payload: dict[str, Any],
    *,
    folder_id: str,
) -> tuple[list[CourseResourceFolder], list[CourseResourceItem]]:
    folders: list[CourseResourceFolder] = []
    resources: list[CourseResourceItem] = []

    for entry in _payload_list(payload.get("bagList")):
        if not isinstance(entry, dict):
            continue
        child_id = _clean_id(entry.get("id"))
        name = str(entry.get("bag_name") or entry.get("name") or "").strip()
        if not child_id or not name:
            continue
        folders.append(
            CourseResourceFolder(
                folder_id=child_id,
                name=name,
                parent_id=_clean_id(_first_present(entry, "pId", "pid", "up_id"), folder_id),
            )
        )

    for entry in _payload_list(payload.get("resList")):
        if not isinstance(entry, dict):
            continue
        rp_id = _clean_id(_first_present(entry, "rpId", "rp_id", "id"))
        res_id = _clean_id(_first_present(entry, "resId", "res_id"))
        name = str(entry.get("rpName") or entry.get("rp_name") or entry.get("name") or "").strip()
        if not rp_id or not name:
            continue
        extension = str(_first_present(entry, "RP_PRIX", "rp_prix", "extName", "ext_name") or "").strip().lower() or None
        resources.append(
            CourseResourceItem(
                resource_id=res_id or rp_id,
                rp_id=rp_id,
                res_id=res_id,
                name=name,
                extension=extension,
                size=str(_first_present(entry, "rpSize", "rp_size") or "").strip() or None,
                uploaded_at=str(_first_present(entry, "inputTime", "input_time", "created_at") or "").strip() or None,
                teacher_name=str(_first_present(entry, "teacherName", "teacher_name") or "").strip() or None,
                download_count=_clean_int(_first_present(entry, "downloadNum", "download_num")),
                click_count=_clean_int(_first_present(entry, "clicks", "click_count")),
                can_download=str(entry.get("stu_download") or "").strip() == "2",
                folder_id=folder_id,
            )
        )

    return folders, resources


def build_course_resources_data(
    *,
    current_term: str | None,
    courses: list[CourseSummary],
    selected_course: CourseSummary | None,
    folder_id: str,
    tree: list[CourseResourceFolder],
    folders: list[CourseResourceFolder],
    resources: list[CourseResourceItem],
) -> CourseResourcesData:
    return CourseResourcesData(
        current_term=current_term,
        courses=courses,
        selected_course_id=selected_course.course_id if selected_course else None,
        folder_id=folder_id,
        tree=tree,
        folders=folders,
        resources=resources,
    )


def build_homework_data(
    *,
    current_term: str | None,
    courses: list[CourseSummary],
    items: list[HomeworkItem],
) -> HomeworkData:
    return HomeworkData(current_term=current_term, courses=courses, items=items)
