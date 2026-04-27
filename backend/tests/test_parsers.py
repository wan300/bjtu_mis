from __future__ import annotations

import json
from pathlib import Path

from app.parsers.aa import parse_empty_rooms, parse_exams, parse_scores, parse_timetable
from app.parsers.ve import build_homework_data, parse_calendar, parse_calendar_terms, parse_courses, parse_homework_list


FIXTURES = Path(__file__).parent / "fixtures"


def read_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def read_json(name: str) -> dict:
    return json.loads(read_text(name))


def test_parse_timetable_non_empty() -> None:
    data = parse_timetable(read_text("timetable.html"))
    assert len(data.entries) == 2
    assert data.entries[0].course_code == "M310005B"
    assert data.entries[0].teacher == "孔令波"
    assert data.entries[1].weekday == "星期二"


def test_parse_exam_empty_table_returns_no_items() -> None:
    data = parse_exams(read_text("exams_empty.html"))
    assert data.current_term == "2025-2026-2-2"
    assert data.items == []


def test_parse_score_empty_table_returns_no_items() -> None:
    data = parse_scores(read_text("scores_empty.html"))
    assert data.current_term == "2025-2026-2-2"
    assert data.items == []


def test_parse_score_table_with_items() -> None:
    data = parse_scores(read_text("scores_main.html"))
    assert data.current_term == "2025-2026-2-2"
    assert len(data.items) == 2
    assert data.items[0].course_name == "软件项目管理与产品运维"
    assert data.items[1].score == "72"


def test_parse_empty_room_grid() -> None:
    data = parse_empty_rooms(read_text("empty_rooms.html"), requested_query={"week": 8})
    assert len(data.slots) == 4
    assert len(data.rooms) == 2
    assert data.rooms[1].availability == [True, False, True, True]


def test_parse_calendar_and_homework() -> None:
    terms_payload = read_json("calendar_terms.json")
    month_payload = read_json("calendar_month.json")
    options, current_term = parse_calendar_terms(terms_payload)
    calendar = parse_calendar(month_payload, month="2026-04", current_term=current_term, available_terms=options)
    assert calendar.current_week == "8"
    assert len(calendar.items) == 3

    courses = parse_courses(read_json("course_list.json"))
    open_items = parse_homework_list(read_json("homework_open.json"), course=courses[0], sub_type=0)
    done_items = parse_homework_list(read_json("homework_done_empty.json"), course=courses[0], sub_type=2)
    homework = build_homework_data(current_term=current_term, courses=courses, items=open_items + done_items)
    assert homework.current_term == "2025202602"
    assert len(homework.items) == 1
    assert homework.items[0].status == "open"


def test_parse_timetable_with_comment_block() -> None:
        html = """
        <table class=\"table table-bordered\">
            <tr><th></th><th>星期一</th></tr>
            <tr>
                <td>第1节 [08:00-09:50]</td>
                <td>
                    <div>
                        <!-- 处理主修和辅修记录，生成课表 -->
                        M310005B [01]<br/>
                        <span style=\"color:#000\">操作系统 [本]</span><br/>
                        <div style=\"max-width:120px;\">第01-16周 <i>孔令波</i></div>
                        <span class=\"text-muted\">海淀西校区, 逸夫教学楼, YF611</span>
                    </div>
                </td>
            </tr>
        </table>
        """
        data = parse_timetable(html)
        assert len(data.entries) == 1
        assert data.entries[0].course_code == "M310005B"
        assert data.entries[0].teacher == "孔令波"


def test_parse_empty_rooms_style_driven_busy_cells() -> None:
        html = """
        <table class=\"table table-bordered\">
            <tr>
                <th>星期</th>
                <th colspan=\"2\">星期一 04月20日</th>
            </tr>
            <tr>
                <th>教室/节次</th>
                <th>1</th>
                <th>2</th>
            </tr>
            <tr>
                <td>SY101 (90)</td>
                <td style=\"background-color: #e46868\"></td>
                <td style=\"background-color: #fff\"></td>
            </tr>
        </table>
        """
        data = parse_empty_rooms(html, requested_query={"week": 8})
        assert len(data.rooms) == 1
        assert data.rooms[0].availability == [False, True]
