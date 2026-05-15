from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

AUTOMATION_DIR = Path(__file__).resolve().parent
if str(AUTOMATION_DIR) not in sys.path:
    sys.path.insert(0, str(AUTOMATION_DIR))

from network_recorder import analyze_capture, select_recording_page


class FakePage:
    def __init__(self, url: str) -> None:
        self.url = url
        self.goto_urls: list[str] = []
        self.brought_to_front = False

    async def goto(self, url: str) -> None:
        self.goto_urls.append(url)
        self.url = url

    async def bring_to_front(self) -> None:
        self.brought_to_front = True


class FakeContext:
    def __init__(self, pages: list[FakePage]) -> None:
        self.pages = pages
        self.created_pages: list[FakePage] = []

    async def new_page(self) -> FakePage:
        page = FakePage("about:blank")
        self.pages.append(page)
        self.created_pages.append(page)
        return page


def write_jsonl(path: Path, events: list[dict]) -> None:
    path.write_text(
        "\n".join(json.dumps(event, ensure_ascii=False) for event in events) + "\n",
        encoding="utf-8",
    )


@pytest.mark.asyncio
async def test_select_recording_page_preserves_existing_page_without_start_url() -> None:
    blank_page = FakePage("about:blank")
    current_page = FakePage("https://mail.example.edu/inbox")
    context = FakeContext([blank_page, current_page])

    page, action = await select_recording_page(context, None, "https://mis.bjtu.edu.cn/home/")

    assert page is current_page
    assert action == "preserved_existing_page"
    assert current_page.brought_to_front is False
    assert context.created_pages == []
    assert blank_page.goto_urls == []
    assert current_page.goto_urls == []


@pytest.mark.asyncio
async def test_select_recording_page_opens_explicit_start_url_in_new_tab() -> None:
    current_page = FakePage("https://mail.example.edu/inbox")
    context = FakeContext([current_page])

    page, action = await select_recording_page(
        context,
        "https://mis.bjtu.edu.cn/home/",
        "https://fallback.example.edu/",
    )

    assert page is context.created_pages[0]
    assert page.url == "https://mis.bjtu.edu.cn/home/"
    assert action == "opened_explicit_start_url"
    assert current_page.goto_urls == []
    assert current_page.brought_to_front is False


def test_analyze_mail_capture_covers_core_operations(tmp_path: Path) -> None:
    network_log = tmp_path / "network.jsonl"
    events = [
        {"event": "marker", "label": "收件箱列表"},
        {
            "event": "request",
            "request_id": 1,
            "method": "GET",
            "url": "https://mail.example.edu/api/mail/list?box=inbox&page=1",
            "path": "/api/mail/list",
            "query": {"box": "inbox", "page": "1"},
            "resource_type": "xhr",
            "body": None,
        },
        {
            "event": "response",
            "request_id": 1,
            "url": "https://mail.example.edu/api/mail/list?box=inbox&page=1",
            "status": 200,
            "body": {
                "content_type": "application/json",
                "shape": {"items": [{"id": "str", "subject": "str"}]},
            },
        },
        {"event": "marker", "label": "查看邮件详情"},
        {
            "event": "request",
            "request_id": 2,
            "method": "GET",
            "url": "https://mail.example.edu/api/mail/detail?id=1",
            "path": "/api/mail/detail",
            "query": {"id": "1"},
            "resource_type": "xhr",
            "body": None,
        },
        {"event": "response", "request_id": 2, "url": "https://mail.example.edu/api/mail/detail?id=1", "status": 200},
        {"event": "marker", "label": "发送带附件邮件"},
        {
            "event": "request",
            "request_id": 3,
            "method": "POST",
            "url": "https://mail.example.edu/api/attachment/upload",
            "path": "/api/attachment/upload",
            "query": {},
            "resource_type": "xhr",
            "body": {
                "content_type": "multipart/form-data",
                "multipart": {"parts": [{"name": "file", "filename": "demo.txt"}]},
            },
        },
        {"event": "response", "request_id": 3, "url": "https://mail.example.edu/api/attachment/upload", "status": 200},
        {
            "event": "request",
            "request_id": 4,
            "method": "POST",
            "url": "https://mail.example.edu/api/mail/send",
            "path": "/api/mail/send",
            "query": {},
            "resource_type": "xhr",
            "body": {"content_type": "application/json", "json": {"to": "self", "subject": "demo", "attachmentId": "a1"}},
        },
        {"event": "response", "request_id": 4, "url": "https://mail.example.edu/api/mail/send", "status": 200},
        {"event": "marker", "label": "删除和拉黑"},
        {
            "event": "request",
            "request_id": 5,
            "method": "POST",
            "url": "https://mail.example.edu/api/mail/delete",
            "path": "/api/mail/delete",
            "query": {},
            "resource_type": "xhr",
            "body": {"content_type": "application/json", "json": {"id": "1"}},
        },
        {"event": "response", "request_id": 5, "url": "https://mail.example.edu/api/mail/delete", "status": 200},
        {
            "event": "request",
            "request_id": 6,
            "method": "POST",
            "url": "https://mail.example.edu/api/blacklist/block",
            "path": "/api/blacklist/block",
            "query": {},
            "resource_type": "xhr",
            "body": {"content_type": "application/json", "json": {"sender": "x@example.edu"}},
        },
        {"event": "response", "request_id": 6, "url": "https://mail.example.edu/api/blacklist/block", "status": 200},
    ]
    write_jsonl(network_log, events)

    result = analyze_capture(network_log, output_dir=tmp_path)

    assert result["coverage"]["list"]["observed"] is True
    assert result["coverage"]["detail"]["observed"] is True
    assert result["coverage"]["send"]["observed"] is True
    assert result["coverage"]["attachment"]["observed"] is True
    assert result["coverage"]["delete"]["observed"] is True
    assert result["coverage"]["blocklist"]["observed"] is True
    assert (tmp_path / "mail_interface_analysis.md").exists()
