from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path

from app.parsers.aa import (
    is_passing_score,
    parse_academic_progress,
    parse_academic_progress_detail_path,
    parse_empty_rooms,
    parse_exams,
    parse_scorecard_progress,
    parse_scores,
    parse_student_status_profile,
    parse_timetable,
)
from app.parsers.ve import (
    build_homework_data,
    parse_calendar,
    parse_calendar_terms,
    parse_course_resource_listing,
    parse_course_resource_tree,
    parse_courses,
    parse_homework_list,
    parse_student_profile,
)


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


def test_letter_grades_at_d_or_above_pass() -> None:
    assert is_passing_score("D")
    assert is_passing_score("D+")
    assert is_passing_score("C")
    assert is_passing_score("B-")
    assert not is_passing_score("D-")
    assert not is_passing_score("F")


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
    assert homework.items[0].submitted_at is None
    assert homework.items[0].course_code == "M410001B"


def test_parse_homework_status_uses_submission_time() -> None:
    courses = parse_courses(read_json("course_list.json"))
    payload = deepcopy(read_json("homework_open.json"))
    submitted_item = deepcopy(payload["courseNoteList"][0])
    submitted_item.update(
        {
            "id": 872709,
            "title": "已提交作业",
            "subTime": "2026-04-10 23:05:56",
            "subStatus": "已提交",
        }
    )
    payload["courseNoteList"].append(submitted_item)

    items = parse_homework_list(payload, course=courses[0], sub_type=0)

    assert [item.status for item in items] == ["open", "done"]
    assert items[0].submitted_at is None
    assert items[1].submitted_at == "2026-04-10 23:05:56"


def test_parse_course_resource_tree_and_listing() -> None:
        tree = parse_course_resource_tree(
            {
                "nodes": [
                    {"id": 0, "name": "电子课件"},
                    {"id": 7, "pId": 0, "name": "第1章"},
                ],
                "STATUS": "2",
            }
        )
        folders, resources = parse_course_resource_listing(
            {
                "bagList": [{"id": 8, "bag_name": "课堂材料"}],
                "resList": [
                    {
                        "rpId": 1001,
                        "resId": 2001,
                        "rpName": "第6章-GraphQL",
                        "extName": "PDF",
                        "rpSize": "1.67",
                        "inputTime": "2026-03-04 22:48:54",
                        "teacherName": "王戍",
                        "downloadNum": 30,
                        "clicks": 0,
                        "stu_download": 2,
                    },
                    {
                        "rpId": 1002,
                        "rpName": "只读资料.pdf",
                        "RP_PRIX": "pdf",
                        "stu_download": "1",
                    },
                ],
                "STATUS": "0",
            },
            folder_id="0",
        )

        assert [item.folder_id for item in tree] == ["0", "7"]
        assert tree[1].parent_id == "0"
        assert folders[0].folder_id == "8"
        assert resources[0].resource_id == "2001"
        assert resources[0].rp_id == "1001"
        assert resources[0].extension == "pdf"
        assert resources[0].size == "1.67"
        assert resources[0].uploaded_at == "2026-03-04 22:48:54"
        assert resources[0].teacher_name == "王戍"
        assert resources[0].download_count == 30
        assert resources[0].click_count == 0
        assert resources[0].can_download is True
        assert resources[1].can_download is False


def test_parse_course_resource_empty_strings() -> None:
        folders, resources = parse_course_resource_listing(
            {"bagList": "", "resList": "", "STATUS": "2"},
            folder_id="0",
        )
        assert folders == []
        assert resources == []


def test_parse_student_profile_payload() -> None:
        data = parse_student_profile(
            {
                "user": {
                    "userName": "测试学生",
                    "studentNo": "20260001",
                    "collegeName": "软件学院",
                    "majorName": "软件工程",
                    "className": "软件2601",
                },
                "STATUS": "0",
            }
        )
        assert data.name == "测试学生"
        assert data.student_id == "20260001"
        assert data.major == "软件工程"
        assert [field.label for field in data.fields[:3]] == ["姓名", "学号", "学院"]


def test_parse_aa_student_status_profile_sections() -> None:
        data = parse_student_status_profile(
            """
            <table class="table table-bordered table-hover">
              <tr><td colspan="9">人员信息</td></tr>
              <tr>
                <td>学号</td><td>20260001</td><td>姓名</td><td>测试学生</td>
                <td>性别</td><td>男</td><td>出生日期</td><td>20060101</td>
                <td rowspan="4"><img src="data:image/jpeg;base64,abc" /></td>
              </tr>
              <tr><td>曾用名</td><td></td><td>姓名拼音</td><td>ceshixuesheng</td><td>英文姓名</td><td>Test Student</td></tr>
              <tr><td>籍贯</td><td></td><td>民族</td><td>汉族</td><td>政治面貌</td><td>共青团员</td></tr>
              <tr><td>是否留学生</td><td>否</td><td>国家或地区</td><td></td></tr>
              <tr><td colspan="9">培养信息</td></tr>
              <tr>
                <td>学院</td><td>软件学院</td><td>专业</td><td>8531 软件工程</td>
                <td>年级</td><td>2026级</td><td>班级</td><td>软件2601</td>
              </tr>
              <tr>
                <td>是否有学籍</td><td>是</td><td>学籍状态</td><td>正常学籍</td>
                <td>学生类别</td><td>普通生</td><td>异动否</td><td></td>
              </tr>
              <tr><td>授课语种</td><td>英语</td><td>校区</td><td>海淀校区</td></tr>
            </table>
            """
        )

        assert data.student_id == "20260001"
        assert data.name == "测试学生"
        assert data.birthday == "20060101"
        assert data.college == "软件学院"
        assert data.major == "8531 软件工程"
        assert data.student_status == "正常学籍"
        assert data.avatar_url == "data:image/jpeg;base64,abc"
        assert [section.title for section in data.sections] == ["人员信息", "培养信息"]
        assert any(field.label == "曾用名" and field.value == "" for field in data.sections[0].fields)


def test_parse_real_academic_progress_and_detail_link() -> None:
        assert (
            parse_academic_progress_detail_path(
                '<a href="/school_census/schooltraininfo/stustudyview/148257/">查看进度</a>'
            )
            == "/school_census/schooltraininfo/stustudyview/148257/"
        )

        data = parse_academic_progress(
            """
            <table class="table table-bordered">
              <tr><th>合并课组名称</th><th>要求学分</th><th>已完成学分</th></tr>
              <tr><td>综合素质教育平台</td><td>36.0</td><td>34.0</td></tr>
              <tr><td>专业教育平台</td><td>60.0</td><td>49.0</td></tr>
            </table>
            <table class="table table-bordered">
              <tr><th>课组类型</th><th>课组名称</th><th>要求学分</th><th>已完成学分</th></tr>
              <tr><td>综合素质教育平台【36.0】</td><td>思政类课程【17.0】</td><td>17.0</td><td>15.0</td></tr>
              <tr><td>军事课【4.0】</td><td>4.0</td><td>4.0</td></tr>
            </table>
            <table class="table table-bordered">
              <tr><th>序号</th><th>学年学期</th><th>课程</th><th>学分</th><th>考试时间</th><th>课程成绩</th><th>课组信息</th></tr>
              <tr><td>1</td><td>2023-2024-2</td><td>A022011B 大学生健康教育</td><td>2.0</td><td>20240701</td><td>A</td><td>身心素养类课程</td></tr>
            </table>
            """
        )

        assert data.summary.target_credits == 96.0
        assert data.summary.passed_credits == 83.0
        assert data.summary.failed_course_count == 2
        assert data.merged_buckets[0].completion_rate == 94.4
        assert data.detail_buckets[1].parent == "综合素质教育平台【36.0】"
        assert data.courses[0].course_code == "A022011B"
        assert data.courses[0].group_info == "身心素养类课程"


def test_parse_scorecard_progress_from_scorecard_and_scores() -> None:
        scores = parse_scores(read_text("scores_main.html"))
        data = parse_scorecard_progress(
            """
            <table class="table table-bordered">
              <tr><td>姓名</td><td>测试学生</td><td>学号</td><td>20260001</td></tr>
            </table>
            <table class="table table-bordered">
              <tr><th>课程类别</th><th>要求学分</th><th>已获学分</th><th>欠缺学分</th></tr>
              <tr><td>专业必修</td><td>90</td><td>42</td><td>48</td></tr>
            </table>
            """,
            replace_html="""
            <table class="table table-bordered">
              <tr><th>原课程</th><th>替代课程</th></tr>
              <tr><td>大学英语</td><td>英语认定</td></tr>
            </table>
            """,
            scores=scores,
        )
        assert data.summary.course_count == 2
        assert data.summary.passed_course_count == 2
        assert data.buckets[0].completion_rate == 46.7
        assert data.replace_courses[0]["替代课程"] == "英语认定"


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
