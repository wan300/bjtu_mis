
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
from app.schemas import SessionCaptchaResponse, SessionState, SessionStatusResponse


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
        sync_lock_path=runtime_dir / "sync.lock",
        login_lock_path=runtime_dir / "login_browser.lock",
        frontend_dist_dir=tmp_path / "frontend" / "dist",
        python_executable=Path("python"),
        mis_home_url="https://mis.bjtu.edu.cn/home/",
        user_agent="pytest-agent",
        request_timeout=30.0,
    )


def build_transport() -> httpx.MockTransport:
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
            assert params.get("zc") == "8"
            return httpx.Response(200, text=read_text("empty_rooms.html"))
        if host == "123.121.147.7" and path == "/ve/back/rp/common/teachCalendar.shtml":
            return httpx.Response(200, json=read_json("calendar_terms.json"))
        if host == "123.121.147.7" and path == "/ve/back/coursePlatform/course.shtml":
            if params.get("method") == "getTimeList":
                return httpx.Response(200, json=read_json("calendar_month.json"))
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
