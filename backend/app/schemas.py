from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class CoverageLevel(str, Enum):
    VERIFIED = "verified"
    PROVISIONAL = "provisional"


class SessionState(str, Enum):
    READY = "ready"
    WAITING_FOR_LOGIN = "waiting_for_login"
    EXPIRED = "expired"


class SessionStatusResponse(BaseModel):
    state: SessionState
    detail: str | None = None


class SessionCaptchaResponse(BaseModel):
    image_data_url: str
    fetched_at: str


class InlineLoginRequest(BaseModel):
    loginname: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=1, max_length=256)
    captcha: str = Field(min_length=1, max_length=16)


class TermOption(BaseModel):
    value: str
    label: str
    selected: bool = False


class CourseEntry(BaseModel):
    weekday: str
    period: str
    time_range: str | None = None
    course_code: str
    section: str | None = None
    course_name: str
    teacher: str | None = None
    weeks: str | None = None
    campus: str | None = None
    building: str | None = None
    room: str | None = None
    location_text: str | None = None


class TimetableData(BaseModel):
    days: list[str] = Field(default_factory=list)
    periods: list[str] = Field(default_factory=list)
    entries: list[CourseEntry] = Field(default_factory=list)
    current_term: str | None = None
    available_terms: list[TermOption] = Field(default_factory=list)


class ExamItem(BaseModel):
    term: str | None = None
    course_name: str
    schedule: str | None = None
    exam_mode: str | None = None
    remark: str | None = None
    registration: str | None = None
    status: str | None = None


class ExamData(BaseModel):
    current_term: str | None = None
    available_terms: list[TermOption] = Field(default_factory=list)
    items: list[ExamItem] = Field(default_factory=list)


class ScoreItem(BaseModel):
    term: str | None = None
    course_name: str
    credit: str | None = None
    score: str | None = None
    bonus_score: str | None = None
    teacher: str | None = None
    detail: str | None = None


class ScoreData(BaseModel):
    current_term: str | None = None
    available_terms: list[TermOption] = Field(default_factory=list)
    items: list[ScoreItem] = Field(default_factory=list)


class CalendarItem(BaseModel):
    date: str
    week: str | None = None
    note: str | None = None


class CalendarData(BaseModel):
    month: str
    current_week: str | None = None
    current_term: str | None = None
    available_terms: list[TermOption] = Field(default_factory=list)
    items: list[CalendarItem] = Field(default_factory=list)


class CourseSummary(BaseModel):
    course_id: int
    course_name: str
    course_code: str | None = None
    teacher_name: str | None = None
    teacher_id: str | None = None
    term: str | None = None
    xq_code: str | None = None
    xkh_id: str | None = None


class HomeworkItem(BaseModel):
    homework_id: int | None = None
    course: str
    course_id: int
    title: str
    content_excerpt: str | None = None
    opened_at: str | None = None
    due_at: str | None = None
    status: str
    sub_type: int
    submission_status: str | None = None


class HomeworkData(BaseModel):
    current_term: str | None = None
    courses: list[CourseSummary] = Field(default_factory=list)
    items: list[HomeworkItem] = Field(default_factory=list)


class EmptyRoomSlotHeader(BaseModel):
    day: str
    date: str | None = None
    period: int


class EmptyRoomRow(BaseModel):
    room: str
    seat_label: str | None = None
    availability: list[bool] = Field(default_factory=list)


class EmptyRoomData(BaseModel):
    query: dict[str, Any] = Field(default_factory=dict)
    days: list[str] = Field(default_factory=list)
    periods: list[int] = Field(default_factory=list)
    slots: list[EmptyRoomSlotHeader] = Field(default_factory=list)
    rooms: list[EmptyRoomRow] = Field(default_factory=list)


class ModuleEnvelope(BaseModel):
    module: str
    synced_at: str | None = None
    source_system: str
    coverage: CoverageLevel
    source_params: dict[str, Any] = Field(default_factory=dict)
    data: Any


class SyncStatusResponse(BaseModel):
    status: str
    started_at: str | None = None
    finished_at: str | None = None
    module_summary: dict[str, Any] = Field(default_factory=dict)
    error_text: str | None = None
