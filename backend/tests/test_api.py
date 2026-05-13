
from __future__ import annotations

import json
from contextlib import asynccontextmanager
from pathlib import Path
from types import MethodType
from urllib.parse import parse_qs

import httpx
from fastapi.testclient import TestClient

from app.config import Settings
from app.exceptions import SessionExpiredError
from app.main import create_app, filter_homework_payload
from app.schemas import AutoLoginResponse, SessionCaptchaResponse, SessionState, SessionStatusResponse


FIXTURES = Path(__file__).parent / "fixtures"
VE_SESSION_ID = "VE-SESSION-ID"
VE_URL_SESSION_ID = "VE-URL-SESSION-ID"


def course_platform_page_html(
    *,
    session_id: str = VE_SESSION_ID,
    teacher_id: str = "teacher-login",
    course_id: str = "M410001B",
    c_id: str = "129006",
    xkh_id: str = "2025-2026-2-2M410001B01",
    xq_code: str = "2025202602",
    course_to_page: str = "",
) -> str:
    return (
        f'<input type="hidden" id="courseId" name="courseId" value="{course_id}" />'
        f'<input type="hidden" id="xqCode" name="xqCode" value="{xq_code}" />'
        f'<input type="hidden" id="xkhId" name="xkhId" value="{xkh_id}" />'
        f'<input type="hidden" id="cId" name="cId" value="{c_id}" />'
        f'<input type="hidden" id="teacherId" name="teacherId" value="{teacher_id}" />'
        f'<input type="hidden" id="courseToPage" name="courseToPage" value="{course_to_page}" />'
        f'<input type="hidden" id="sessionId" name="sessionId" value="{session_id}" />'
    )


def homework_upload_page_html(upload_url: str = "http://123.121.147.7:88/ve/back/rp/common/rpUpload.shtml;jsessionid=UPLOAD") -> str:
    return f"""
    <form id="form1">
      <input type="hidden" id="return_num" name="return_num" value="0"/>
      <input type="hidden" id="courseId" name="courseId" value="129006"/>
      <input type="hidden" id="upId" name="upId" value="887764"/>
      <input type="hidden" id="jxrl_id" name="jxrl_id" value=""/>
      <input type="hidden" id="fz" name="fz" value="0"/>
      <input type="hidden" id="contentType" name="contentType" value="0"/>
      <input type="hidden" id="groupName" name="groupName" value=""/>
      <input type="hidden" id="groupId" name="groupId" value=""/>
    </form>
    <script>
      layui.use('upload', function(){{
        upload.render({{ elem: '#uploadFileElem', url: '{upload_url}', accept: 'file' }});
      }});
    </script>
    """


def article_list_payload(session_id: str = VE_SESSION_ID) -> dict:
    return {
        "articleList": [{"id": 1, "title": "教学归档操作指南"}],
        "sessionId": session_id,
        "STATUS": "0",
        "message": "成功",
    }


def profile_payload(session_id: str = VE_SESSION_ID) -> dict:
    return {
        "user": {
            "userName": "测试学生",
            "studentNo": "20260001",
            "collegeName": "软件学院",
            "majorName": "软件工程",
            "className": "软件2601",
            "grade": "2026",
        },
        "sessionId": session_id,
        "STATUS": "0",
    }


def scorecard_html() -> str:
    return """
    <table class="table table-bordered">
      <tr><td>姓名</td><td>测试学生</td><td>学号</td><td>20260001</td></tr>
    </table>
    <table class="table table-bordered">
      <tr><th>课程类别</th><th>要求学分</th><th>已获学分</th><th>欠缺学分</th></tr>
      <tr><td>专业必修</td><td>90</td><td>42</td><td>48</td></tr>
      <tr><td>通识选修</td><td>10</td><td>6</td><td>4</td></tr>
    </table>
    """


def replace_courses_html() -> str:
    return """
    <table class="table table-bordered">
      <tr><th>原课程</th><th>替代课程</th><th>状态</th></tr>
      <tr><td>大学英语</td><td>英语认定</td><td>已认定</td></tr>
    </table>
    """


def student_status_profile_html() -> str:
    return """
    <table class="table table-bordered table-hover">
      <tr><td colspan="9">人员信息</td></tr>
      <tr>
        <td>学号</td><td>20260001</td><td>姓名</td><td>测试学生</td>
        <td>性别</td><td>男</td><td>出生日期</td><td>20060101</td>
        <td rowspan="4"><img src="data:image/jpeg;base64,abc" /></td>
      </tr>
      <tr><td>姓名拼音</td><td>ceshixuesheng</td><td>英文姓名</td><td>Test Student</td></tr>
      <tr><td>民族</td><td>汉族</td><td>政治面貌</td><td>共青团员</td></tr>
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


def academic_progress_list_html() -> str:
    return """
    <table class="table table-bordered table-hover">
      <tr><th>序号</th><th>学生</th><th>专业</th><th>培养方案</th><th>操作</th></tr>
      <tr>
        <td>1</td><td>20260001 测试学生</td><td>软件学院 8531 软件工程</td>
        <td>[4734] 软件工程专业本科培养方案</td>
        <td><a href="/school_census/schooltraininfo/stustudyview/1/">查看进度</a></td>
      </tr>
    </table>
    """


def academic_progress_html() -> str:
    return """
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


def course_selection_selected_html() -> str:
    return """
    <html><body>
      <form method="post" action="/course_selection/courseselecttask/submit/">
        <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
        <div id="selected-container">
          <table class="table table-bordered">
            <tr><th>Action</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
            <tr>
              <td><a class="select-delete-btn" data-pk="target-1">Delete</a></td>
              <td>M410003B Platform Software Design 01 Software School</td>
              <td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
            </tr>
          </table>
        </div>
        <table class="table table-bordered"><tr><th>Other</th><th>Value</th></tr></table>
        <table class="table table-bordered">
          <tr><th>Select</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
          <tr><td>Selected</td><td>M410003B Platform Software Design 01 Software School</td><td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td></tr>
        </table>
        <a class="btn btn-primary" href="#">Submit</a>
      </form>
    </body></html>
    """


def course_selection_replace_html(
    *,
    selected_os: bool = True,
    selected_target: bool = False,
    target_remaining: int = 2,
) -> str:
    selected_rows = []
    if selected_os:
        selected_rows.append(
            """
            <tr>
              <td><a class="select-delete-btn" data-pk="selected-1">Delete</a></td>
              <td>M310005B Operating Systems 01 Software School</td>
              <td>1</td><td>3</td><td>Required</td><td>Exam</td><td>Teacher A</td><td>Mon 1-2 Room 101</td>
            </tr>
            """
        )
    if selected_target:
        selected_rows.append(
            """
            <tr>
              <td><a class="select-delete-btn" data-pk="target-1">Delete</a></td>
              <td>M410003B Platform Software Design 01 Software School</td>
              <td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
            </tr>
            """
        )

    os_available = ""
    if not selected_os:
        os_available = """
          <tr>
            <td><input type="checkbox" name="selects" value="selected-1"></td>
            <td>M310005B Operating Systems 01 Software School</td>
            <td>1</td><td>3</td><td>Required</td><td>Exam</td><td>Teacher A</td><td>Mon 1-2 Room 101</td>
          </tr>
        """
    target_action = "Selected" if selected_target else '<input type="checkbox" name="selects" value="target-1">'
    return f"""
    <html><body>
      <form method="post" action="/course_selection/courseselecttask/submit/">
        <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
        <div id="selected-container">
          <table class="table table-bordered">
            <tr><th>Action</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
            {''.join(selected_rows)}
          </table>
        </div>
        <table class="table table-bordered"><tr><th>Other</th><th>Value</th></tr></table>
        <table class="table table-bordered">
          <tr><th>Select</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
          {os_available}
          <tr>
            <td>{target_action}</td>
            <td>M410003B Platform Software Design 01 Software School</td>
            <td>{target_remaining}</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
          </tr>
        </table>
        <a class="btn btn-primary" href="#">Submit</a>
      </form>
    </body></html>
    """


def read_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def read_json(name: str) -> dict:
    return json.loads(read_text(name))


def make_settings(tmp_path: Path) -> Settings:
    runtime_dir = tmp_path / "runtime"
    captures_dir = tmp_path / "captures"
    profile_dir = captures_dir / "profile" / "default"
    logs_dir = captures_dir / "logs"
    return Settings(
        root_dir=tmp_path,
        backend_dir=tmp_path / "backend",
        captures_dir=captures_dir,
        runtime_dir=runtime_dir,
        logs_dir=logs_dir,
        profile_dir=profile_dir,
        db_path=runtime_dir / "bjtu_mis.sqlite3",
        session_state_path=runtime_dir / "session_state.json",
        login_credentials_path=runtime_dir / "login_credentials.json",
        sync_lock_path=runtime_dir / "sync.lock",
        login_lock_path=runtime_dir / "login_browser.lock",
        captcha_model_path=tmp_path / "model.pt",
        captcha_charset=" 0123456789+-*=",
        frontend_dist_dir=tmp_path / "frontend" / "dist",
        python_executable=Path("python"),
        mis_home_url="https://mis.bjtu.edu.cn/home/",
        user_agent="pytest-agent",
        request_timeout=30.0,
    )


def build_transport(calendar_week: str = "8", expected_empty_room_week: str = "8") -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={VE_URL_SESSION_ID}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text=f'<input id="sessionId" value="{VE_SESSION_ID}" />')
            if params.get("method") == "toCoursePlatform":
                course_to_page = str(params.get("courseToPage") or "")
                return httpx.Response(
                    200,
                    text=course_platform_page_html(
                        session_id=VE_SESSION_ID,
                        teacher_id="rj_ws",
                        course_id=str(params.get("courseId") or "M410001B"),
                        c_id=str(params.get("cId") or "129006"),
                        xkh_id=str(params.get("xkhId") or "2025-2026-2-2M410001B01"),
                        xq_code=str(params.get("xqCode") or "2025202602"),
                        course_to_page=course_to_page,
                    ),
                )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                assert request.headers.get("sessionid") is None
                return httpx.Response(200, json=article_list_payload())
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/userInfo.shtml":
            if params.get("method") == "getUserInfo":
                assert request.headers.get("sessionid") == VE_SESSION_ID
                return httpx.Response(200, json=profile_payload())

        if host == "aa.bjtu.edu.cn" and path == "/course_selection/courseselect/stuschedule/":
            return httpx.Response(200, text=read_text("timetable.html"))
        if host == "aa.bjtu.edu.cn" and path == "/examine/examplanstudent/stulist/":
            return httpx.Response(200, text=read_text("exams_empty.html"))
        if host == "aa.bjtu.edu.cn" and path == "/score/scores/stu/view/":
            assert params.get("ctype") in {"lr", "ln"}
            return httpx.Response(200, text=read_text("scores_main.html"))
        if host == "aa.bjtu.edu.cn" and path == "/score/scores/stu/detail/1001/":
            assert params.get("term") == "2025-2026-2-2"
            return httpx.Response(200, text=read_text("score_detail.html"))
        if host == "aa.bjtu.edu.cn" and path == "/school_census/schoolcensus/stuview/":
            return httpx.Response(200, text=student_status_profile_html())
        if host == "aa.bjtu.edu.cn" and path == "/school_census/schooltraininfo/studylist/":
            return httpx.Response(200, text=academic_progress_list_html())
        if host == "aa.bjtu.edu.cn" and path == "/school_census/schooltraininfo/stustudyview/1/":
            return httpx.Response(200, text=academic_progress_html())
        if host == "aa.bjtu.edu.cn" and path == "/score/scorecard/stu/":
            return httpx.Response(200, text=scorecard_html())
        if host == "aa.bjtu.edu.cn" and path == "/score/scorereplacecourse/stu_lists/":
            return httpx.Response(200, text=replace_courses_html())
        if host == "aa.bjtu.edu.cn" and path == "/classroom/timeholdresult/room_view/":
            assert params.get("zc") == expected_empty_room_week
            return httpx.Response(200, text=read_text("empty_rooms.html"))
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getTimeList":
                payload = read_json("calendar_month.json")
                payload["weekCode"] = calendar_week
                return httpx.Response(200, json=payload)
            if params.get("method") == "getCourseList":
                assert request.headers.get("sessionid") == VE_SESSION_ID
                assert "toCoursePlatformIndex" in request.headers.get("referer", "")
                return httpx.Response(200, json=read_json("course_list.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/homeWork.shtml":
            assert request.headers.get("sessionid") == VE_SESSION_ID
            assert "courseToPage=10460" in request.headers.get("referer", "")
            if params.get("subType") == "0":
                return httpx.Response(200, json=read_json("homework_open.json"))
            if params.get("subType") == "2":
                return httpx.Response(200, json=read_json("homework_done_empty.json"))
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    return httpx.MockTransport(handler)


def build_upstream_500_transport() -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host in {"mis.bjtu.edu.cn", "aa.bjtu.edu.cn", "123.121.147.7", "bksycenter.bjtu.edu.cn", "bksy.bjtu.edu.cn"}:
            return httpx.Response(500, text="upstream error")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    return httpx.MockTransport(handler)


def attach_transport_client(app, transport: httpx.BaseTransport) -> None:
    @asynccontextmanager
    async def fake_get_authenticated_client(self):
        async with httpx.AsyncClient(transport=transport, follow_redirects=True) as client:
            yield client

    async def fake_validate_session(self):
        return SessionStatusResponse(state=SessionState.READY, detail="ok")

    app.state.session_manager.get_authenticated_client = MethodType(fake_get_authenticated_client, app.state.session_manager)
    app.state.session_manager.validate_session = MethodType(fake_validate_session, app.state.session_manager)


def attach_mock_client(app) -> None:
    attach_transport_client(app, build_transport())


def test_empty_rooms_default_uses_current_calendar_week(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, build_transport(calendar_week="12", expected_empty_room_week="12"))

    with TestClient(app) as client:
        response = client.get("/api/modules/empty-rooms")

    assert response.status_code == 200
    body = response.json()
    assert body["source_params"]["week"] == "12"
    assert body["data"]["query"]["week"] == "12"


def test_session_captcha_endpoint_returns_payload(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    async def fake_fetch_inline_login_captcha(self):
        return SessionCaptchaResponse(
            image_data_url="data:image/png;base64,aGVsbG8=",
            fetched_at="2026-04-23T00:00:00Z",
        )

    app.state.session_manager.fetch_inline_login_captcha = MethodType(
        fake_fetch_inline_login_captcha,
        app.state.session_manager,
    )

    with TestClient(app) as client:
        response = client.get("/api/session/captcha")

    assert response.status_code == 200
    body = response.json()
    assert body["image_data_url"].startswith("data:image/png;base64,")
    assert body["fetched_at"] == "2026-04-23T00:00:00Z"


def test_session_captcha_endpoint_handles_session_error(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    async def fake_fetch_inline_login_captcha(self):
        raise SessionExpiredError("当前会话已处于登录状态，无需重复登录。")

    app.state.session_manager.fetch_inline_login_captcha = MethodType(
        fake_fetch_inline_login_captcha,
        app.state.session_manager,
    )

    with TestClient(app) as client:
        response = client.get("/api/session/captcha")

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "SESSION_CAPTCHA_UNAVAILABLE"


def test_session_login_inline_endpoint_returns_ready_status(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))
    captured: dict[str, str] = {}

    async def fake_login_with_inline_form(self, loginname: str, password: str, captcha: str):
        captured["loginname"] = loginname
        captured["password"] = password
        captured["captcha"] = captcha
        return SessionStatusResponse(state=SessionState.READY, detail="ok")

    app.state.session_manager.login_with_inline_form = MethodType(
        fake_login_with_inline_form,
        app.state.session_manager,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/session/login-inline",
            json={"loginname": "20250001", "password": "secret", "captcha": "abcd"},
        )

    assert response.status_code == 200
    assert response.json()["state"] == "ready"
    assert captured == {"loginname": "20250001", "password": "secret", "captcha": "abcd"}


def test_session_login_inline_returns_session_expired(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    async def fake_login_with_inline_form(self, loginname: str, password: str, captcha: str):
        raise SessionExpiredError("验证码错误，请重试。")

    app.state.session_manager.login_with_inline_form = MethodType(
        fake_login_with_inline_form,
        app.state.session_manager,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/session/login-inline",
            json={"loginname": "20250001", "password": "secret", "captcha": "abcd"},
        )

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "SESSION_EXPIRED"


def test_session_login_auto_endpoint_returns_manual_required(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    async def fake_login_with_auto_captcha(self, loginname: str | None = None, password: str | None = None):
        assert loginname == "20250001"
        assert password == "secret"
        return AutoLoginResponse(
            status="manual_required",
            message="请手动输入验证码。",
            attempts=2,
            captcha=SessionCaptchaResponse(
                image_data_url="data:image/png;base64,aGVsbG8=",
                fetched_at="2026-04-23T00:00:00Z",
            ),
        )

    app.state.session_manager.login_with_auto_captcha = MethodType(
        fake_login_with_auto_captcha,
        app.state.session_manager,
    )

    with TestClient(app) as client:
        response = client.post(
            "/api/session/login-auto",
            json={"loginname": "20250001", "password": "secret"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "manual_required"
    assert body["attempts"] == 2
    assert body["captcha"]["image_data_url"].startswith("data:image/png;base64,")


def test_session_login_auto_endpoint_can_use_saved_credentials(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    async def fake_login_with_auto_captcha(self, loginname: str | None = None, password: str | None = None):
        assert loginname is None
        assert password is None
        return AutoLoginResponse(
            status="ready",
            attempts=1,
            session=SessionStatusResponse(state=SessionState.READY, detail="ok"),
        )

    app.state.session_manager.login_with_auto_captcha = MethodType(
        fake_login_with_auto_captcha,
        app.state.session_manager,
    )

    with TestClient(app) as client:
        response = client.post("/api/session/login-auto", json={})

    assert response.status_code == 200
    assert response.json()["status"] == "ready"


def test_sync_run_returns_session_expired_when_not_logged_in(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    @asynccontextmanager
    async def expired_client(self):
        raise SessionExpiredError("会话已失效，请重新登录。")
        yield

    app.state.session_manager.get_authenticated_client = MethodType(expired_client, app.state.session_manager)
    with TestClient(app) as client:
        response = client.post("/api/sync/run")
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "SESSION_EXPIRED"


def test_course_selection_endpoint_lists_courses(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/selects/":
            return httpx.Response(200, text=read_text("course_selection.html"))
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/course-selection")

    assert response.status_code == 200
    body = response.json()
    assert body["module"] == "course_selection"
    assert body["data"]["can_submit"] is True
    assert body["data"]["available_courses"][0]["key"] == "M410003B_01"


def test_course_selection_endpoint_returns_session_expired(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))

    @asynccontextmanager
    async def expired_client(self):
        raise SessionExpiredError("会话已失效，请重新登录。")
        yield

    app.state.session_manager.get_authenticated_client = MethodType(expired_client, app.state.session_manager)
    with TestClient(app) as client:
        response = client.get("/api/modules/course-selection")

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "SESSION_EXPIRED"


def test_course_selection_select_posts_parsed_form(tmp_path: Path) -> None:
    submitted: dict[str, list[str]] = {}
    selected = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal selected
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/selects/":
            return httpx.Response(200, text=course_selection_selected_html() if selected else read_text("course_selection.html"))
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/submit/":
            submitted.update(parse_qs(request.content.decode()))
            selected = True
            return httpx.Response(200, text="<div class='alert'>ok</div>")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post("/api/modules/course-selection/select", json={"course_key": "M410003B_01"})

    assert response.status_code == 200
    assert response.json()["status"] == "success"
    assert submitted["csrfmiddlewaretoken"] == ["csrf-token"]
    assert submitted["selects"] == ["target-1"]


def test_course_selection_select_no_remaining_does_not_submit(tmp_path: Path) -> None:
    submit_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal submit_count
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/selects/":
            return httpx.Response(200, text=read_text("course_selection.html"))
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/submit/":
            submit_count += 1
            return httpx.Response(200, text="<div class='alert'>unexpected</div>")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post("/api/modules/course-selection/select", json={"course_key": "M410004B_01"})

    assert response.status_code == 200
    assert response.json()["status"] == "no_remaining"
    assert submit_count == 0


def test_course_selection_drop_posts_delete_form(tmp_path: Path) -> None:
    dropped = False
    submitted: dict[str, list[str]] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal dropped
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/selects/":
            return httpx.Response(200, text=course_selection_replace_html(selected_os=not dropped))
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/delete/":
            submitted.update(parse_qs(request.content.decode()))
            dropped = True
            return httpx.Response(200, text="<div class='alert'>ok</div>")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post("/api/modules/course-selection/drop", json={"course_key": "M310005B_01"})

    assert response.status_code == 200
    assert response.json()["status"] == "drop_success"
    assert submitted["csrfmiddlewaretoken"] == ["csrf-token"]
    assert submitted["pk"] == ["selected-1"]


def test_course_selection_replace_drops_then_selects_target(tmp_path: Path) -> None:
    selected_os = True
    selected_target = False
    requests: list[str] = []
    submitted_values: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal selected_os, selected_target
        requests.append(f"{request.method} {request.url.path}")
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/selects/":
            return httpx.Response(200, text=course_selection_replace_html(selected_os=selected_os, selected_target=selected_target))
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/delete/":
            selected_os = False
            return httpx.Response(200, text="<div class='alert'>drop ok</div>")
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/submit/":
            submitted = parse_qs(request.content.decode())
            submitted_values.extend(submitted.get("selects", []))
            selected_target = submitted.get("selects") == ["target-1"]
            return httpx.Response(200, text="<div class='alert'>select ok</div>")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post(
            "/api/modules/course-selection/replace",
            json={"target_course_key": "M410003B_01", "drop_course_key": "M310005B_01"},
        )

    assert response.status_code == 200
    assert response.json()["status"] == "replace_success"
    assert "POST /course_selection/courseselecttask/delete/" in requests
    assert submitted_values == ["target-1"]


def test_course_selection_replace_target_no_remaining_does_not_drop(tmp_path: Path) -> None:
    delete_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal delete_count
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/selects/":
            return httpx.Response(200, text=course_selection_replace_html(target_remaining=0))
        if request.url.host == "aa.bjtu.edu.cn" and request.url.path == "/course_selection/courseselecttask/delete/":
            delete_count += 1
            return httpx.Response(200, text="<div class='alert'>unexpected</div>")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post(
            "/api/modules/course-selection/replace",
            json={"target_course_key": "M410003B_01", "drop_course_key": "M310005B_01"},
        )

    assert response.status_code == 200
    assert response.json()["status"] == "target_no_remaining"
    assert delete_count == 0


def test_sync_run_persists_snapshots_and_serves_modules(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))
    attach_mock_client(app)

    with TestClient(app) as client:
        response = client.post("/api/sync/run")
        assert response.status_code == 200
        assert response.json()["status"] == "success"

        status = client.get("/api/sync/status")
        assert status.status_code == 200
        assert status.json()["status"] == "success"

        timetable = client.get("/api/modules/timetable").json()
        assert len(timetable["data"]["entries"]) == 2

        homework = client.get("/api/modules/homework?status=open").json()
        assert len(homework["data"]["items"]) == 1
        assert homework["data"]["items"][0]["course_code"] == "M410001B"

        exams = client.get("/api/modules/exams").json()
        assert exams["coverage"] == "verified"
        assert exams["data"]["items"] == []

        scores = client.get("/api/modules/scores").json()
        assert scores["coverage"] == "verified"
        assert len(scores["data"]["items"]) == 2
        assert scores["data"]["items"][0]["course_name"] == "软件项目管理与产品运维"

        history_scores = client.get("/api/modules/history-scores").json()
        assert history_scores["coverage"] == "verified"
        assert history_scores["module"] == "history_scores"
        assert history_scores["source_params"]["ctype"] == "ln"
        assert history_scores["data"]["items"][0]["detail_path"] == "/score/scores/stu/detail/1001/?term=2025-2026-2-2"

        score_detail = client.get(
            "/api/modules/score-detail",
            params={"path": history_scores["data"]["items"][0]["detail_path"]},
        )
        assert score_detail.status_code == 200
        assert score_detail.json()["data"]["tables"][0]["rows"][0] == ["平时", "40%", "88"]

        profile = client.get("/api/modules/profile").json()
        assert profile["coverage"] == "verified"
        assert profile["data"]["name"] == "测试学生"
        assert profile["data"]["major"] == "8531 软件工程"
        assert profile["data"]["sections"][0]["title"] == "人员信息"

        progress = client.get("/api/modules/academic-progress").json()
        assert progress["coverage"] == "verified"
        assert progress["data"]["summary"]["course_count"] == 1
        assert progress["data"]["buckets"][0]["name"] == "综合素质教育平台"
        assert progress["data"]["detail_buckets"][0]["name"] == "思政类课程【17.0】"

        snapshots = client.get("/api/modules/snapshots")
        assert snapshots.status_code == 200
        assert snapshots.json()["snapshots"]["profile"]["data"]["name"] == "测试学生"


def test_homework_filter_uses_parsed_status() -> None:
    payload = {
        "data": {
            "items": [
                {"homework_id": 1, "status": "open", "submitted_at": None},
                {"homework_id": 2, "status": "done", "submitted_at": "2026-04-10 23:05:56"},
            ]
        }
    }

    open_payload = filter_homework_payload(payload, "open")
    done_payload = filter_homework_payload(payload, "done")

    assert [item["homework_id"] for item in open_payload["data"]["items"]] == [1]
    assert [item["homework_id"] for item in done_payload["data"]["items"]] == [2]


def test_sync_run_returns_409_when_lock_exists(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    settings.ensure_directories()
    settings.sync_lock_path.write_text("busy", encoding="utf-8")
    app = create_app(settings)
    attach_mock_client(app)
    with TestClient(app) as client:
        response = client.post("/api/sync/run")
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "SYNC_ALREADY_RUNNING"


def test_sync_start_returns_409_when_lock_exists(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    settings.ensure_directories()
    settings.sync_lock_path.write_text("busy", encoding="utf-8")
    app = create_app(settings)
    with TestClient(app) as client:
        response = client.post("/api/sync/start")
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "SYNC_ALREADY_RUNNING"


def test_live_modules_fallback_to_empty_payload_when_upstream_500(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, build_upstream_500_transport())

    with TestClient(app) as client:
        calendar = client.get("/api/modules/calendar?month=2026-04")
        assert calendar.status_code == 200
        assert calendar.json()["coverage"] == "provisional"
        assert calendar.json()["data"]["month"] == "2026-04"

        homework = client.get("/api/modules/homework?status=all")
        assert homework.status_code == 200
        assert homework.json()["coverage"] == "provisional"
        assert homework.json()["data"]["items"] == []

        empty_rooms = client.get("/api/modules/empty-rooms")
        assert empty_rooms.status_code == 200
        assert empty_rooms.json()["coverage"] == "provisional"
        assert empty_rooms.json()["data"]["rooms"] == []


def test_live_modules_fallback_to_empty_payload_when_upstream_returns_html_instead_of_json(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={VE_URL_SESSION_ID}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text=f'<input id="sessionId" value="{VE_SESSION_ID}" />')
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                return httpx.Response(200, json=article_list_payload())
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getTimeList":
                return httpx.Response(200, text="<html>bad calendar</html>", headers={"content-type": "text/html;charset=utf-8"})
            if params.get("method") == "getCourseList":
                assert request.headers.get("sessionid") == VE_SESSION_ID
                return httpx.Response(200, text="<html>bad homework</html>", headers={"content-type": "text/html;charset=utf-8"})
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        calendar = client.get("/api/modules/calendar?month=2026-04")
        assert calendar.status_code == 200
        assert calendar.json()["coverage"] == "provisional"
        assert calendar.json()["data"]["month"] == "2026-04"

        homework = client.get("/api/modules/homework?status=all")
        assert homework.status_code == 200
        assert homework.json()["coverage"] == "provisional"
        assert homework.json()["data"]["items"] == []


def test_homework_uses_ajax_session_id_and_course_page_context(tmp_path: Path) -> None:
    url_session_id = "URL-SESSION-ID"
    ajax_session_id = "AJAX-SESSION-ID"
    teacher_login_id = "teacher-login"
    index_referer = "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml?method=toCoursePlatformIndex"

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={url_session_id}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text="<div>index</div>")
            if params.get("method") == "toCoursePlatform" and params.get("courseToPage") is None:
                assert request.headers.get("referer") == index_referer
                return httpx.Response(
                    200,
                    text=course_platform_page_html(session_id=ajax_session_id, teacher_id=teacher_login_id),
                )
            if params.get("method") == "toCoursePlatform" and params.get("courseToPage") == "10460":
                referer = request.headers.get("referer", "")
                assert "courseId=M410001B" in referer
                assert "cId=129006" in referer
                assert "xkhId=2025-2026-2-2M410001B01" in referer
                return httpx.Response(
                    200,
                    text=course_platform_page_html(
                        session_id=ajax_session_id,
                        teacher_id=teacher_login_id,
                        course_to_page="10460",
                    ),
                )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                assert request.headers.get("sessionid") is None
                assert request.headers.get("referer") == index_referer
                return httpx.Response(200, json=article_list_payload(ajax_session_id))
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getCourseList":
                assert request.headers.get("sessionid") == ajax_session_id
                assert request.headers.get("referer") == index_referer
                return httpx.Response(200, json=read_json("course_list.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/homeWork.shtml":
            assert request.headers.get("sessionid") == ajax_session_id
            referer = request.headers.get("referer", "")
            assert "courseToPage=10460" in referer
            assert f"teacherId={teacher_login_id}" in referer
            if params.get("subType") == "0":
                return httpx.Response(200, json=read_json("homework_open.json"))
            if params.get("subType") == "2":
                return httpx.Response(200, json=read_json("homework_done_empty.json"))
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/homework?status=all")

    assert response.status_code == 200
    assert response.json()["coverage"] == "verified"
    assert len(response.json()["data"]["items"]) == 1


def test_homework_submit_posts_text_and_uploaded_file(tmp_path: Path) -> None:
    url_session_id = "SUBMIT-URL-SESSION"
    ajax_session_id = "SUBMIT-AJAX-SESSION"
    teacher_login_id = "teacher-login"
    index_referer = "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml?method=toCoursePlatformIndex"
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={url_session_id}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text="<div>index</div>")
            if params.get("method") == "toCoursePlatform" and params.get("courseToPage") is None:
                assert request.headers.get("referer") == index_referer
                return httpx.Response(
                    200,
                    text=course_platform_page_html(session_id=ajax_session_id, teacher_id=teacher_login_id),
                )
            if params.get("method") == "toCoursePlatform" and params.get("courseToPage") == "10460":
                return httpx.Response(
                    200,
                    text=course_platform_page_html(
                        session_id=ajax_session_id,
                        teacher_id=teacher_login_id,
                        course_to_page="10460",
                    ),
                )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                return httpx.Response(200, json=article_list_payload(ajax_session_id))
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getCourseList":
                assert request.headers.get("sessionid") == ajax_session_id
                return httpx.Response(200, json=read_json("course_list.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/homeWork.shtml":
            if params.get("method") == "getHomeWorkList" and params.get("subType") == "0":
                assert request.headers.get("sessionid") == ajax_session_id
                return httpx.Response(200, json=read_json("homework_open.json"))
            if params.get("method") == "getHomeWorkList" and params.get("subType") == "2":
                return httpx.Response(200, json=read_json("homework_done_empty.json"))
        if host == "123.121.147.7" and path == "/ve/back/course/courseWorkInfo.shtml":
            if request.method == "GET" and params.get("method") == "uploadDiv3":
                captured["upload_params"] = params
                return httpx.Response(200, text=homework_upload_page_html())
            if request.method == "POST" and params.get("method") == "sendStuHomeWorks":
                captured["submit_form"] = parse_qs(request.content.decode())
                return httpx.Response(200, text=json.dumps({"flag": "success", "message": "ok"}))
        if host == "123.121.147.7" and path.startswith("/ve/back/rp/common/rpUpload.shtml"):
            captured["upload_body"] = request.content
            return httpx.Response(
                200,
                json={
                    "STATUS": "0",
                    "fileExtName": "txt",
                    "fileSize": "12",
                    "resSerId": "RES-1",
                    "visitName": "VISIT-1",
                    "fileNameNoExt": "answer",
                },
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post(
            "/api/modules/homework/887764/submit",
            data={"course_id": "129006", "content": "答案内容"},
            files=[("files", ("answer.txt", b"hello answer", "text/plain"))],
        )

    assert response.status_code == 200
    assert response.json()["status"] == "success"
    assert captured["upload_params"]["upId"] == "887764"
    assert b'filename="answer.txt"' in captured["upload_body"]
    submit_form = captured["submit_form"]
    assert submit_form["courseId"] == ["129006"]
    assert submit_form["upId"] == ["887764"]
    assert submit_form["content"] == ["%E7%AD%94%E6%A1%88%E5%86%85%E5%AE%B9"]
    file_list = json.loads(submit_form["fileList"][0])
    assert file_list == [
        {
            "fileNameNoExt": "answer",
            "fileExtName": "txt",
            "fileSize": "12",
            "visitName": "VISIT-1",
            "pid": "",
            "ftype": "insert",
        }
    ]


def test_course_resources_fetches_electronic_courseware_with_ajax_session(tmp_path: Path) -> None:
    url_session_id = "COURSE-RESOURCE-URL"
    ajax_session_id = "COURSE-RESOURCE-AJAX"
    index_referer = (
        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
        f"?method=toCoursePlatformIndex&sessionId={url_session_id}"
    )

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(
                200,
                text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>",
            )
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={url_session_id}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                if params.get("sessionId") != url_session_id:
                    return httpx.Response(
                        302,
                        headers={
                            "Location": (
                                "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                                f"?method=toCoursePlatformIndex&sessionId={url_session_id}"
                            )
                        },
                    )
                return httpx.Response(200, text="<div>index</div>")
            if params.get("method") == "toCoursePlatform" and params.get("courseToPage") is None:
                assert request.headers.get("referer") == index_referer
                return httpx.Response(
                    200,
                    text=course_platform_page_html(session_id=ajax_session_id, teacher_id="rj_ws"),
                )
            if params.get("method") == "toCoursePlatform" and params.get("courseToPage") == "10450":
                assert params.get("courseId") == "M410001B"
                assert params.get("cId") == "129006"
                assert params.get("teacherId") == "rj_ws"
                return httpx.Response(
                    200,
                    text=(
                        course_platform_page_html(
                            session_id=ajax_session_id,
                            teacher_id="rj_ws",
                            course_to_page="10450",
                        )
                        + "<script>"
                        + "var courseId='129006';"
                        + "var courseNum='M410001B';"
                        + "var xkhId='2025-2026-2-2M410001B01';"
                        + "var xqCode='2025202602';"
                        + "var teacherId='rj_ws';"
                        + "</script>"
                    ),
                )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                assert request.headers.get("sessionid") is None
                assert request.headers.get("referer") == index_referer
                return httpx.Response(200, json=article_list_payload(ajax_session_id))
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getCourseList":
                assert request.headers.get("sessionid") == ajax_session_id
                return httpx.Response(200, json=read_json("course_list.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/courseResource.shtml":
            assert params.get("courseId") == "M410001B"
            assert params.get("cId") == "M410001B"
            assert params.get("xkhId") == "2025-2026-2-2M410001B01"
            assert params.get("xqCode") == "2025202602"
            assert params.get("docType") == "1"
            if params.get("method") == "stuQueryCourseResourceBag":
                return httpx.Response(200, json={"nodes": [{"id": 0, "name": "电子课件"}], "STATUS": "2"})
            if params.get("method") == "stuQueryUploadResourceForCourseList":
                assert request.headers.get("sessionid") == ajax_session_id
                assert params.get("up_id") == "0"
                return httpx.Response(
                    200,
                    json={
                        "bagList": [{"id": 7, "bag_name": "第1章"}],
                        "resList": [
                            {
                                "rpId": 1001,
                                "resId": 2001,
                                "rpName": "第6章-GraphQL",
                                "extName": "pdf",
                                "rpSize": "1.67",
                                "inputTime": "2026-03-04 22:48:54",
                                "teacherName": "王戍",
                                "downloadNum": 30,
                                "clicks": 0,
                                "stu_download": 2,
                            }
                        ],
                        "STATUS": "0",
                    },
                )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/course-resources?course_id=129006")

    assert response.status_code == 200
    body = response.json()
    assert body["module"] == "course_resources"
    assert body["coverage"] == "verified"
    assert body["data"]["selected_course_id"] == 129006
    assert body["data"]["tree"][0]["name"] == "电子课件"
    assert body["data"]["folders"][0]["folder_id"] == "7"
    assert body["data"]["resources"][0]["rp_id"] == "1001"
    assert body["data"]["resources"][0]["extension"] == "pdf"
    assert body["data"]["resources"][0]["size"] == "1.67"
    assert body["data"]["resources"][0]["download_count"] == 30
    assert body["data"]["resources"][0]["can_download"] is True


def test_course_resource_download_proxies_resolved_rp_url(tmp_path: Path) -> None:
    ajax_session_id = "DOWNLOAD-AJAX"

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>ok</script>")
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text=f'<input id="sessionId" value="{ajax_session_id}" />')
        if host == "123.121.147.7" and path == "/ve/back/resourceSpace.shtml":
            assert request.method == "POST"
            assert params.get("method") == "rpinfoDownloadUrl"
            assert params.get("rpId") == "1001"
            assert request.headers.get("sessionid") == ajax_session_id
            return httpx.Response(200, json={"rpUrl": "download.shtml?p=course&f=courseware.pptx"})
        if host == "123.121.147.7" and path == "/ve/download.shtml":
            assert params.get("p") == "course"
            assert params.get("f") == "courseware.pptx"
            return httpx.Response(
                200,
                content=b"pptx-bytes",
                headers={"content-type": "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/course-resources/download/1001?filename=courseware.pptx")

    assert response.status_code == 200
    assert response.content == b"pptx-bytes"
    assert response.headers["content-disposition"] == "attachment; filename*=UTF-8''courseware.pptx"
    assert response.headers["content-type"].startswith(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )


def test_calendar_get_time_list_sends_session_header_when_available(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={VE_SESSION_ID}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text=f'<input id="sessionId" value="{VE_SESSION_ID}" />')
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            assert request.headers.get("sessionid") is None
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getTimeList":
                assert request.headers.get("sessionid") == VE_SESSION_ID
                return httpx.Response(200, json=read_json("calendar_month.json"))
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/calendar?month=2026-04")

    assert response.status_code == 200
    assert response.json()["coverage"] == "verified"
    assert response.json()["data"]["month"] == "2026-04"


def test_homework_returns_provisional_when_current_term_missing(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={VE_SESSION_ID}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text=f'<input id="sessionId" value="{VE_SESSION_ID}" />')
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json={"result": [], "STATUS": "0"})
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getCourseList":
                raise AssertionError("getCourseList should not be called when current term is missing")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/homework?status=all")

    assert response.status_code == 200
    assert response.json()["coverage"] == "provisional"
    assert response.json()["source_params"]["fallback_reason"] == "missing_current_term"
    assert response.json()["data"]["items"] == []


def test_homework_partial_subrequest_failure_marks_provisional(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(200, text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>")
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={VE_URL_SESSION_ID}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text="<div>index</div>")
            if params.get("method") == "toCoursePlatform":
                course_to_page = str(params.get("courseToPage") or "")
                return httpx.Response(
                    200,
                    text=course_platform_page_html(
                        session_id=VE_SESSION_ID,
                        teacher_id="rj_ws",
                        course_to_page=course_to_page,
                    ),
                )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                return httpx.Response(200, json=article_list_payload())
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getCourseList":
                assert request.headers.get("sessionid") == VE_SESSION_ID
                return httpx.Response(200, json=read_json("course_list.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/homeWork.shtml":
            assert request.headers.get("sessionid") == VE_SESSION_ID
            assert "courseToPage=10460" in request.headers.get("referer", "")
            if params.get("subType") == "0":
                return httpx.Response(200, json=read_json("homework_open.json"))
            if params.get("subType") == "2":
                return httpx.Response(403, text="forbidden")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/homework?status=all")

    assert response.status_code == 200
    assert response.json()["coverage"] == "provisional"
    assert response.json()["source_params"]["partial_error_count"] == 1
    assert len(response.json()["data"]["items"]) == 1


def test_strict_flow_failure_reports_fallback_reason(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        host = request.url.host
        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            # Keep user on MIS host to trigger strict bksy landing validation failure.
            return httpx.Response(200, text="<html>mis page</html>")
        if host in {"bksycenter.bjtu.edu.cn", "aa.bjtu.edu.cn", "123.121.147.7"}:
            raise AssertionError(f"Strict flow should stop before reaching {request.url}")
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        calendar = client.get("/api/modules/calendar?month=2026-04")
        homework = client.get("/api/modules/homework?status=all")

    assert calendar.status_code == 200
    assert calendar.json()["coverage"] == "provisional"
    assert "strict_flow_step=bksy_landing_reached" in (calendar.json()["source_params"]["fallback_reason"] or "")

    assert homework.status_code == 200
    assert homework.json()["coverage"] == "provisional"
    assert "strict_flow_step=bksy_landing_reached" in (homework.json()["source_params"]["fallback_reason"] or "")


def test_homework_payload_status_error_falls_back_to_provisional(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = dict(request.url.params)
        host = request.url.host

        if host == "mis.bjtu.edu.cn" and path == "/module/module/104/":
            return httpx.Response(302, headers={"Location": "https://bksy.bjtu.edu.cn/login_introduce_t.html"})
        if host == "bksy.bjtu.edu.cn" and path == "/login_introduce_t.html":
            return httpx.Response(200, text="<html>bksy landing</html>")
        if host == "bksycenter.bjtu.edu.cn" and path == "/NoMasterJumpPage.aspx":
            return httpx.Response(302, headers={"Location": "http://123.121.147.7:88/ve/back/core/main/qx_kl.jsp"})
        if host == "123.121.147.7" and path == "/ve/back/core/main/qx_kl.jsp":
            return httpx.Response(
                200,
                text="<script>location.href='http://123.121.147.7:88/ve/back/core/main/index.shtml?method=index&type=qxkt';</script>",
            )
        if host == "123.121.147.7" and path == "/ve/back/core/main/index.shtml":
            return httpx.Response(
                302,
                headers={
                    "Location": (
                        "http://123.121.147.7:88/ve/back/coursePlatform/coursePlatform.shtml"
                        f"?method=toCoursePlatformIndex&sessionId={VE_URL_SESSION_ID}"
                    )
                },
            )
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/coursePlatform.shtml":
            if params.get("method") == "toCoursePlatformIndex":
                return httpx.Response(200, text="<div>index</div>")
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/message.shtml":
            if params.get("method") == "getArticleList":
                return httpx.Response(200, json=article_list_payload(VE_SESSION_ID))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getCourseList":
                return httpx.Response(200, json={"STATUS": "1", "ERRMSG": "term invalid"})
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        homework = client.get("/api/modules/homework?status=all")

    assert homework.status_code == 200
    assert homework.json()["coverage"] == "provisional"
    assert homework.json()["data"]["items"] == []
    assert "VE payload STATUS=1" in (homework.json()["source_params"]["fallback_reason"] or "")


COREMAIL_TEST_SID = "MAIL-SID"


def coremail_sso_response(request: httpx.Request) -> httpx.Response | None:
    host = request.url.host
    path = request.url.path
    if host == "mis.bjtu.edu.cn" and path == "/osys_sso_email/":
        return httpx.Response(
            302,
            headers={"Location": f"https://mail.bjtu.edu.cn/coremail/XT/index.jsp?sid={COREMAIL_TEST_SID}"},
        )
    if host == "mail.bjtu.edu.cn" and path == "/coremail/XT/index.jsp":
        assert request.url.params.get("sid") == COREMAIL_TEST_SID
        return httpx.Response(200, text=f"<script>var sid='{COREMAIL_TEST_SID}'</script>")
    return None


def request_json(request: httpx.Request) -> dict:
    return json.loads(request.content.decode("utf-8") or "{}")


def request_form(request: httpx.Request) -> dict[str, str]:
    return {key: values[0] for key, values in parse_qs(request.content.decode("utf-8"), keep_blank_values=True).items()}


def test_mail_folders_and_messages_use_coremail_sid_and_parse_payloads(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        host = request.url.host
        path = request.url.path
        params = dict(request.url.params)
        if host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/mail.jsp":
            assert params == {"func": "getAllFolders", "sid": COREMAIL_TEST_SID}
            assert request_form(request) == {"stats": "true", "threads": "false"}
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "var": [
                        {
                            "id": 1,
                            "name": "收件箱",
                            "flags": {"system": True},
                            "stats": {
                                "messageCount": 8,
                                "unreadMessageCount": 3,
                                "messageSize": 1024,
                                "unreadMessageSize": 512,
                            },
                        }
                    ],
                },
            )
        if host == "mail.bjtu.edu.cn" and path == "/coremail/s/json" and params.get("func") == "mbox:listMessages":
            assert params["sid"] == COREMAIL_TEST_SID
            assert "text/x-json" in request.headers["content-type"]
            payload = request_json(request)
            assert payload["fid"] == 1
            assert payload["start"] == 5
            assert payload["limit"] == 10
            assert payload["order"] == "date"
            assert payload["desc"] is True
            assert payload["returnTotal"] is True
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "total": 1,
                    "var": [
                        {
                            "id": "2:abc+",
                            "fid": 1,
                            "from": "Alice <alice@example.edu>",
                            "to": "Bob <bob@example.edu>",
                            "sender": "alice@example.edu",
                            "subject": "hello",
                            "sentDate": "2026-05-10 10:00:00",
                            "receivedDate": "2026-05-10 10:00:01",
                            "modifiedDate": "2026-05-10 10:00:02",
                            "size": 123,
                            "priority": 3,
                            "summary": "preview",
                            "flags": {"read": False, "attached": True},
                        }
                    ],
                },
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        folders = client.get("/api/modules/mail/folders")
        messages = client.get("/api/modules/mail/messages?folder_id=1&start=5&limit=10")

    assert folders.status_code == 200
    folder = folders.json()["data"]["folders"][0]
    assert folder["folder_id"] == "1"
    assert folder["message_count"] == 8
    assert folder["unread_count"] == 3

    assert messages.status_code == 200
    body = messages.json()
    assert body["data"]["total"] == 1
    message = body["data"]["messages"][0]
    assert message["message_id"] == "2:abc+"
    assert message["subject"] == "hello"
    assert message["attached"] is True


def test_mail_detail_and_attachment_download(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        host = request.url.host
        path = request.url.path
        params = dict(request.url.params)
        if host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/readMessage.jsp":
            form = request_form(request)
            assert form["mid"] == "2:abc+"
            assert form["mboxa"] == ""
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "var": {
                        "mail": {
                            "from": ["Alice <alice@example.edu>"],
                            "to": ["Bob <bob@example.edu>"],
                            "cc": ["Carol <carol@example.edu>"],
                            "bcc": [],
                            "subject": "detail subject",
                            "headers": {"From": "Alice <alice@example.edu>"},
                            "attachments": [
                                {
                                    "id": "3",
                                    "filename": "report.txt",
                                    "contentType": "text/plain",
                                    "contentLength": 11,
                                }
                            ],
                            "mainPartData": {"content": "<p>mail body</p>"},
                        },
                        "mailInfo": {
                            "id": "2:abc+",
                            "fid": 1,
                            "from": "Alice <alice@example.edu>",
                            "to": "Bob <bob@example.edu>",
                            "subject": "detail subject",
                            "size": 456,
                            "flags": {"read": True, "attached": True},
                        },
                    },
                },
            )
        if host == "mail.bjtu.edu.cn" and path == "/coremail/mbox-data/report.txt":
            assert params == {"part": "3", "mid": "2:abc+", "mode": "download"}
            return httpx.Response(
                200,
                content=b"hello world",
                headers={
                    "content-type": "text/plain",
                    "content-disposition": 'attachment; filename="report.txt"',
                },
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        detail = client.get("/api/modules/mail/message?message_id=2%3Aabc%2B")
        attachment = client.get("/api/modules/mail/attachment?message_id=2%3Aabc%2B&part=3&filename=report.txt")

    assert detail.status_code == 200
    data = detail.json()["data"]
    assert data["message_id"] == "2:abc+"
    assert data["html_content"] == "<p>mail body</p>"
    assert data["attachments"][0]["attachment_id"] == "3"
    assert data["attachments"][0]["filename"] == "report.txt"

    assert attachment.status_code == 200
    assert attachment.content == b"hello world"
    assert attachment.headers["content-type"].startswith("text/plain")
    assert attachment.headers["content-disposition"] == 'attachment; filename="report.txt"'


def test_mail_delete_moves_messages_to_trash_folder(tmp_path: Path) -> None:
    captured: dict[str, dict] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        params = dict(request.url.params)
        if request.url.host == "mail.bjtu.edu.cn" and request.url.path == "/coremail/s/json":
            assert params["sid"] == COREMAIL_TEST_SID
            assert params["func"] == "mbox:updateMessageInfos"
            captured["payload"] = request_json(request)
            return httpx.Response(200, json={"code": "S_OK", "var": {"updated": 1}})
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post("/api/modules/mail/messages/delete", json={"message_ids": ["2:abc+"], "mboxa": ""})

    assert response.status_code == 200
    assert captured["payload"]["attrs"]["fid"] == 4
    assert captured["payload"]["ids"] == ["2:abc+"]
    assert response.json()["status"] == "deleted"


def test_mail_attachment_upload_creates_compose_and_uploads_chunks(tmp_path: Path) -> None:
    file_content = (b"a" * (2 * 1024 * 1024)) + b"bbb"
    offsets: list[int] = []
    ranges: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        host = request.url.host
        path = request.url.path
        params = dict(request.url.params)
        if host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/compose.jsp":
            assert params["sid"] == COREMAIL_TEST_SID
            assert request_form(request)["ctype"] == "normal"
            return httpx.Response(200, json={"code": "S_OK", "var": {"id": "compose-1"}})
        if host == "mail.bjtu.edu.cn" and path == "/coremail/s/json" and params.get("func") == "upload:prepare":
            payload = request_json(request)
            assert payload["composeId"] == "compose-1"
            assert payload["attachmentId"] == -1
            assert payload["fileName"] == "upload.txt"
            assert payload["size"] == len(file_content)
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "var": {
                        "attachmentId": 1,
                        "fileName": "upload.txt",
                        "contentType": "text/plain",
                        "size": len(file_content),
                    },
                },
            )
        if host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/upload.jsp":
            assert params["func"] == "directData"
            assert params["sid"] == COREMAIL_TEST_SID
            assert params["composeId"] == "compose-1"
            assert params["attachmentId"] == "1"
            offsets.append(int(params["offset"]))
            ranges.append(request.headers["content-range"])
            assert request.headers["x-requested-with"] == "XMLHttpRequest"
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "composeId": "compose-1",
                    "var": {
                        "actualSize": 3 if params["offset"] != "0" else 2 * 1024 * 1024,
                        "attachmentId": 1,
                        "fileName": "upload.txt",
                        "contentType": "text/plain",
                        "size": len(file_content),
                    },
                },
            )
        if host == "mail.bjtu.edu.cn" and path == "/coremail/s/json" and params.get("func") == "mbox:compose":
            payload = request_json(request)
            assert payload["id"] == "compose-1"
            assert payload["attrs"]["attachments"] == [
                {
                    "id": 1,
                    "type": "upload",
                    "name": "upload.txt",
                    "displayName": "upload.txt",
                    "size": len(file_content),
                }
            ]
            return httpx.Response(200, json={"code": "S_OK", "var": {"attachments": payload["attrs"]["attachments"]}})
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post(
            "/api/modules/mail/attachments/upload",
            files={"file": ("upload.txt", file_content, "text/plain")},
        )

    assert response.status_code == 200
    assert offsets == [0, 2 * 1024 * 1024]
    assert ranges == [
        f"bytes 0-{2 * 1024 * 1024 - 1}/{len(file_content)}",
        f"bytes {2 * 1024 * 1024}-{len(file_content) - 1}/{len(file_content)}",
    ]
    body = response.json()
    assert body["status"] == "uploaded"
    assert body["compose_id"] == "compose-1"
    assert body["attachment"]["attachment_id"] == "1"


def test_mail_send_uses_compose_payload_and_uploaded_attachments(tmp_path: Path) -> None:
    captured: dict[str, dict] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        host = request.url.host
        path = request.url.path
        params = dict(request.url.params)
        if host == "mail.bjtu.edu.cn" and path == "/coremail/s/json" and params.get("func") == "user:getAttrs":
            assert request_json(request)["optionalAttrIds"] == ["email", "true_name", "default_sender_address"]
            return httpx.Response(
                200,
                json={"code": "S_OK", "var": {"email": "sender@example.edu", "true_name": "Sender"}},
            )
        if host == "mail.bjtu.edu.cn" and path == "/coremail/common/mbox/compose.jsp":
            assert params == {"isUserConfirmed": "true", "sid": COREMAIL_TEST_SID}
            assert "text/x-json" in request.headers["content-type"]
            payload = request_json(request)
            captured["payload"] = payload
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "savedSent": {"mid": "2:sent-id"},
                    "sentTInfo": "trace",
                    "var": {"subject": payload["attrs"]["subject"]},
                },
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post(
            "/api/modules/mail/messages/send",
            json={
                "compose_id": "compose-1",
                "to": ["to@example.edu"],
                "cc": ["cc@example.edu"],
                "subject": "hello",
                "html_content": "<p>body</p>",
                "attachments": [
                    {
                        "attachment_id": "1",
                        "filename": "report.pdf",
                        "content_type": "application/pdf",
                        "size": 123,
                    }
                ],
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "sent"
    assert body["compose_id"] == "compose-1"
    assert body["sent_message_id"] == "2:sent-id"

    payload = captured["payload"]
    assert payload["action"] == "deliver"
    assert payload["id"] == "compose-1"
    assert payload["returnInfo"] is True
    assert payload["autosaveHitCounter"] is True
    assert payload["attrs"]["account"] == '"Sender" <sender@example.edu>'
    assert payload["attrs"]["to"] == ["to@example.edu"]
    assert payload["attrs"]["cc"] == ["cc@example.edu"]
    assert payload["attrs"]["content"] == "<p>body</p>"
    assert payload["attrs"]["attachments"] == [
        {
            "id": 1,
            "type": "upload",
            "name": "report.pdf",
            "displayName": "report.pdf",
            "size": 123,
            "contentType": "application/pdf",
        }
    ]


def test_mail_draft_save_creates_compose_when_missing_id(tmp_path: Path) -> None:
    captured: dict[str, dict] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        host = request.url.host
        path = request.url.path
        params = dict(request.url.params)
        if host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/compose.jsp":
            assert params["sid"] == COREMAIL_TEST_SID
            assert request_form(request)["ctype"] == "normal"
            return httpx.Response(200, json={"code": "S_OK", "var": {"id": "draft-compose"}})
        if host == "mail.bjtu.edu.cn" and path == "/coremail/common/mbox/compose.jsp":
            payload = request_json(request)
            captured["payload"] = payload
            return httpx.Response(
                200,
                json={"code": "S_OK", "draftId": "2:draft-id", "var": {"id": payload["id"]}},
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.post(
            "/api/modules/mail/drafts/save",
            json={
                "account": "Sender <sender@example.edu>",
                "to": ["to@example.edu"],
                "subject": "draft",
                "content": "plain draft body",
                "is_html": False,
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "saved"
    assert body["compose_id"] == "draft-compose"
    assert body["draft_id"] == "2:draft-id"
    assert captured["payload"]["action"] == "save"
    assert captured["payload"]["id"] == "draft-compose"
    assert captured["payload"]["attrs"]["content"] == "plain draft body"
    assert captured["payload"]["attrs"]["isHtml"] is False


def test_mail_contacts_autocomplete_maps_coremail_results(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        sso = coremail_sso_response(request)
        if sso is not None:
            return sso

        host = request.url.host
        path = request.url.path
        params = dict(request.url.params)
        if host == "mail.bjtu.edu.cn" and path == "/coremail/s/json" and params.get("func") == "oab:autoMatch":
            payload = request_json(request)
            assert payload["keyword"] == "alice"
            assert payload["limit"] == 10
            assert payload["@type"] == "U,L,X"
            assert payload["attrIds"] == ["m", "@id", "@type", "location"]
            return httpx.Response(
                200,
                json={
                    "code": "S_OK",
                    "var": [
                        {
                            "@id": "alice-id",
                            "@type": "U",
                            "name": "Alice",
                            "m": ["alice@example.edu"],
                            "location": "School",
                        }
                    ],
                },
            )
        raise AssertionError(f"Unexpected request: {request.method} {request.url}")

    app = create_app(make_settings(tmp_path))
    attach_transport_client(app, httpx.MockTransport(handler))

    with TestClient(app) as client:
        response = client.get("/api/modules/mail/contacts/autocomplete?keyword=alice&limit=10")

    assert response.status_code == 200
    body = response.json()
    assert body["module"] == "mail_contacts"
    contact = body["data"]["contacts"][0]
    assert contact["contact_id"] == "alice-id"
    assert contact["display_name"] == "Alice"
    assert contact["email"] == "alice@example.edu"
    assert contact["type"] == "U"


def test_mail_upstream_errors_map_to_operation_codes(tmp_path: Path) -> None:
    def build_failure_handler(kind: str):
        def handler(request: httpx.Request) -> httpx.Response:
            sso = coremail_sso_response(request)
            if sso is not None:
                return sso

            host = request.url.host
            path = request.url.path
            params = dict(request.url.params)
            if kind == "fetch" and host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/mail.jsp":
                return httpx.Response(200, json={"code": "FA_TEST"})
            if kind == "delete" and host == "mail.bjtu.edu.cn" and path == "/coremail/s/json":
                return httpx.Response(200, json={"code": "FA_TEST"})
            if kind == "download" and host == "mail.bjtu.edu.cn" and path == "/coremail/mbox-data":
                return httpx.Response(500, text="download failed")
            if kind == "upload" and host == "mail.bjtu.edu.cn" and path == "/coremail/XT/jsp/compose.jsp":
                return httpx.Response(200, json={"code": "S_OK", "var": {"id": "compose-1"}})
            if kind == "upload" and host == "mail.bjtu.edu.cn" and path == "/coremail/s/json":
                assert params.get("func") == "upload:prepare"
                return httpx.Response(200, json={"code": "FA_TEST"})
            if kind in {"send", "draft"} and host == "mail.bjtu.edu.cn" and path == "/coremail/common/mbox/compose.jsp":
                return httpx.Response(200, json={"code": "FA_TEST"})
            if kind == "contacts" and host == "mail.bjtu.edu.cn" and path == "/coremail/s/json":
                assert params.get("func") == "oab:autoMatch"
                return httpx.Response(200, json={"code": "FA_TEST"})
            raise AssertionError(f"Unexpected request for {kind}: {request.method} {request.url}")

        return handler

    cases = [
        (
            "fetch",
            lambda client: client.get("/api/modules/mail/folders"),
            "MAIL_FETCH_FAILED",
        ),
        (
            "delete",
            lambda client: client.post("/api/modules/mail/messages/delete", json={"message_ids": ["2:abc+"], "mboxa": ""}),
            "MAIL_DELETE_FAILED",
        ),
        (
            "download",
            lambda client: client.get("/api/modules/mail/attachment?message_id=2%3Aabc%2B&part=3"),
            "MAIL_DOWNLOAD_FAILED",
        ),
        (
            "upload",
            lambda client: client.post(
                "/api/modules/mail/attachments/upload",
                files={"file": ("upload.txt", b"body", "text/plain")},
            ),
            "MAIL_UPLOAD_FAILED",
        ),
        (
            "send",
            lambda client: client.post(
                "/api/modules/mail/messages/send",
                json={"compose_id": "compose-1", "account": "Sender <sender@example.edu>", "to": ["to@example.edu"]},
            ),
            "MAIL_SEND_FAILED",
        ),
        (
            "draft",
            lambda client: client.post(
                "/api/modules/mail/drafts/save",
                json={"compose_id": "compose-1", "account": "Sender <sender@example.edu>"},
            ),
            "MAIL_DRAFT_FAILED",
        ),
        (
            "contacts",
            lambda client: client.get("/api/modules/mail/contacts/autocomplete?keyword=alice"),
            "MAIL_CONTACTS_FAILED",
        ),
    ]

    for kind, call_api, expected_code in cases:
        app = create_app(make_settings(tmp_path / kind))
        attach_transport_client(app, httpx.MockTransport(build_failure_handler(kind)))
        with TestClient(app) as client:
            response = call_api(client)
        assert response.status_code == 502
        assert response.json()["detail"]["code"] == expected_code
