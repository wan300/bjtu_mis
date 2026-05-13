from __future__ import annotations

import json
import re
from typing import Any
from urllib.parse import quote

import httpx

from ..schemas import (
    CoverageLevel,
    MailAttachment,
    MailAttachmentUploadResponse,
    MailComposeAttachment,
    MailComposeBaseRequest,
    MailComposeResponse,
    MailContactSuggestion,
    MailDeleteResponse,
    MailFolder,
    MailMessageDetail,
    MailMessageSummary,
    ModuleEnvelope,
)


COREMAIL_BASE_URL = "https://mail.bjtu.edu.cn"
COREMAIL_SSO_URL = "https://mis.bjtu.edu.cn/osys_sso_email/"
COREMAIL_CHUNK_SIZE = 2 * 1024 * 1024
COREMAIL_TRASH_FOLDER_ID = 4


class CoremailError(Exception):
    pass


class CoremailProvider:
    def __init__(self, client: httpx.AsyncClient) -> None:
        self.client = client
        self.sid: str | None = None

    async def _ensure_ready(self) -> str:
        if self.sid:
            return self.sid

        response = await self.client.get(COREMAIL_SSO_URL, headers={"Referer": "https://mis.bjtu.edu.cn/home/"})
        response.raise_for_status()
        sid = self._extract_sid(str(response.url), response.text)
        if not sid:
            raise CoremailError("Coremail sid missing after SSO")
        self.sid = sid
        return sid

    def _extract_sid(self, *values: str) -> str | None:
        for value in values:
            if not value:
                continue
            match = re.search(r"(?:[?&]sid=|sid[\"']?\s*[:=]\s*[\"'])([A-Za-z0-9_-]+)", value)
            if match:
                return match.group(1)
        return None

    def _referer(self) -> str:
        return f"{COREMAIL_BASE_URL}/coremail/XT/index.jsp?sid={self.sid or ''}"

    def _ensure_success(self, payload: dict[str, Any], *, context: str) -> dict[str, Any]:
        code = str(payload.get("code") or "")
        if code and code != "S_OK":
            raise CoremailError(f"{context} failed with Coremail code={code}")
        return payload

    async def _post_json_func(self, func: str, body: dict[str, Any]) -> dict[str, Any]:
        sid = await self._ensure_ready()
        response = await self.client.post(
            f"{COREMAIL_BASE_URL}/coremail/s/json",
            params={"sid": sid, "func": func},
            content=json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            headers={
                "Accept": "*/*",
                "Content-Type": 'text/x-json; tz="Asia/Shanghai"',
                "Referer": self._referer(),
                "X-Requested-With": "XMLHttpRequest",
            },
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise CoremailError(f"{func} returned non-object JSON")
        return self._ensure_success(payload, context=func)

    async def _post_json_path(
        self,
        path: str,
        body: dict[str, Any],
        *,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        sid = await self._ensure_ready()
        request_params = dict(params or {})
        request_params["sid"] = sid
        response = await self.client.post(
            f"{COREMAIL_BASE_URL}{path}",
            params=request_params,
            content=json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            headers={
                "Accept": "text/x-json",
                "Content-Type": 'text/x-json; tz="Asia/Shanghai"',
                "Referer": self._referer(),
                "X-Requested-With": "XMLHttpRequest",
            },
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise CoremailError(f"{path} returned non-object JSON")
        return self._ensure_success(payload, context=path)

    async def _post_form(
        self,
        path: str,
        data: dict[str, Any],
        *,
        params: dict[str, Any] | None = None,
        include_sid: bool = False,
    ) -> dict[str, Any]:
        sid = await self._ensure_ready()
        request_params = dict(params or {})
        if include_sid:
            request_params["sid"] = sid
        response = await self.client.post(
            f"{COREMAIL_BASE_URL}{path}",
            params=request_params,
            data=data,
            headers={
                "Accept": "*/*",
                "Referer": self._referer(),
                "X-Requested-With": "XMLHttpRequest",
            },
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise CoremailError(f"{path} returned non-object JSON")
        return self._ensure_success(payload, context=path)

    def _int_value(self, value: Any, default: int = 0) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return default

    def _folder_from_raw(self, raw: dict[str, Any]) -> MailFolder:
        stats = raw.get("stats") if isinstance(raw.get("stats"), dict) else {}
        flags = raw.get("flags") if isinstance(raw.get("flags"), dict) else {}
        return MailFolder(
            folder_id=str(raw.get("id") or ""),
            name=str(raw.get("name") or ""),
            message_count=self._int_value(stats.get("messageCount")),
            unread_count=self._int_value(stats.get("unreadMessageCount")),
            message_size=self._int_value(stats.get("messageSize")),
            unread_size=self._int_value(stats.get("unreadMessageSize")),
            system=bool(flags.get("system")),
        )

    def _summary_from_raw(self, raw: dict[str, Any]) -> MailMessageSummary:
        flags = raw.get("flags") if isinstance(raw.get("flags"), dict) else {}
        return MailMessageSummary(
            message_id=str(raw.get("id") or ""),
            folder_id=str(raw.get("fid") or ""),
            subject=str(raw.get("subject") or ""),
            from_text=str(raw.get("from") or ""),
            to_text=str(raw.get("to") or ""),
            sender=str(raw.get("sender") or "") or None,
            sent_at=str(raw.get("sentDate") or "") or None,
            received_at=str(raw.get("receivedDate") or "") or None,
            modified_at=str(raw.get("modifiedDate") or "") or None,
            size=self._int_value(raw.get("size")),
            read=bool(flags.get("read")),
            attached=bool(flags.get("attached") or raw.get("attached")),
            priority=self._int_value(raw.get("priority")) if raw.get("priority") is not None else None,
            summary=str(raw.get("summary") or "") or None,
        )

    def _attachment_from_raw(self, raw: dict[str, Any]) -> MailAttachment:
        attachment_id = str(raw.get("id") or raw.get("attachmentId") or raw.get("part") or "")
        filename = str(raw.get("filename") or raw.get("name") or raw.get("fileName") or "attachment")
        return MailAttachment(
            attachment_id=attachment_id,
            filename=filename,
            content_type=str(raw.get("contentType") or "") or None,
            size=self._int_value(raw.get("contentLength") or raw.get("size") or raw.get("estimateSize")),
            part=str(raw.get("part") or attachment_id),
        )

    def _string_list(self, value: Any) -> list[str]:
        if value is None:
            return []
        if isinstance(value, list):
            return [str(item) for item in value]
        return [str(value)]

    def _dict_items(self, value: Any) -> list[dict[str, Any]]:
        if not isinstance(value, list):
            return []
        return [item for item in value if isinstance(item, dict)]

    def _first_text(self, *values: Any) -> str:
        for value in values:
            if value is None:
                continue
            if isinstance(value, list):
                nested = self._first_text(*value)
                if nested:
                    return nested
                continue
            if isinstance(value, dict):
                nested = self._first_text(
                    value.get("email"),
                    value.get("mail"),
                    value.get("addr"),
                    value.get("name"),
                    value.get("displayName"),
                    value.get("text"),
                )
                if nested:
                    return nested
                continue
            text = str(value).strip()
            if text:
                return text
        return ""

    def _contact_from_raw(self, raw: Any) -> MailContactSuggestion:
        if not isinstance(raw, dict):
            text = str(raw or "").strip()
            return MailContactSuggestion(display_name=text, email=text)
        email = self._first_text(raw.get("email"), raw.get("EMAIL;PREF"), raw.get("m"), raw.get("mail"))
        display_name = self._first_text(
            raw.get("name"),
            raw.get("FN"),
            raw.get("cn"),
            raw.get("true_name"),
            raw.get("displayName"),
            raw.get("@id"),
            email,
        )
        return MailContactSuggestion(
            contact_id=self._first_text(raw.get("id"), raw.get("@id")) or None,
            display_name=display_name,
            email=email,
            type=self._first_text(raw.get("@type"), raw.get("type")) or None,
            location=self._first_text(raw.get("location"), raw.get("@location")) or None,
            raw=raw,
        )

    async def _default_account(self) -> str:
        payload = await self._post_json_func(
            "user:getAttrs",
            {"optionalAttrIds": ["email", "true_name", "default_sender_address"]},
        )
        value = payload.get("var") if isinstance(payload.get("var"), dict) else {}
        default_sender = self._first_text(value.get("default_sender_address"))
        if default_sender:
            return default_sender
        email = self._first_text(value.get("email"))
        if not email:
            raise CoremailError("Coremail sender account missing")
        name = self._first_text(value.get("true_name"))
        return f'"{name}" <{email}>' if name else email

    def _attachment_payload(self, attachment: MailComposeAttachment) -> dict[str, Any]:
        attachment_id = self._int_value(attachment.attachment_id, default=-1)
        payload: dict[str, Any] = {
            "id": attachment_id if attachment_id >= 0 else attachment.attachment_id,
            "type": attachment.type or "upload",
            "name": attachment.filename,
            "displayName": attachment.filename,
            "size": attachment.size,
        }
        if attachment.content_type:
            payload["contentType"] = attachment.content_type
        if attachment.security_level is not None:
            payload["securityLevel"] = attachment.security_level
        return payload

    async def _compose_body(self, request: MailComposeBaseRequest) -> tuple[str, dict[str, Any]]:
        compose_id = (request.compose_id or "").strip() or await self.create_compose(mboxa=request.mboxa)
        account = (request.account or "").strip() or await self._default_account()
        attrs = {
            "account": account,
            "to": request.to,
            "cc": request.cc,
            "bcc": request.bcc,
            "subject": request.subject,
            "content": request.html_content if request.html_content is not None else (request.content or ""),
            "attachments": [self._attachment_payload(item) for item in request.attachments],
            "isHtml": request.is_html,
            "saveSentCopy": request.save_sent_copy,
            "requestReadReceipt": request.request_read_receipt,
            "scheduleDate": request.schedule_date,
            "showOneRcpt": request.show_one_rcpt,
            "forbidDownload": request.forbid_download,
            "smimeEncrypt": False,
            "smimeSign": False,
            "smimeEnvelopId": "",
        }
        return compose_id, attrs

    async def fetch_folders(self) -> ModuleEnvelope:
        payload = await self._post_form(
            "/coremail/XT/jsp/mail.jsp",
            {"stats": "true", "threads": "false"},
            params={"func": "getAllFolders"},
            include_sid=True,
        )
        folders = [
            self._folder_from_raw(item).model_dump(mode="json")
            for item in payload.get("var", [])
            if isinstance(item, dict)
        ]
        return ModuleEnvelope(
            module="mail_folders",
            source_system="coremail",
            coverage=CoverageLevel.VERIFIED,
            source_params={},
            data={"folders": folders},
        )

    async def fetch_messages(self, *, folder_id: str, start: int = 0, limit: int = 20, mboxa: str = "") -> ModuleEnvelope:
        fid = self._int_value(folder_id, default=1)
        payload = await self._post_json_func(
            "mbox:listMessages",
            {
                "fid": fid,
                "start": start,
                "limit": limit,
                "mode": "count",
                "order": "date",
                "desc": True,
                "returnTotal": True,
                "returnTag": False,
                "summaryWindowSize": limit,
                "mboxa": mboxa,
                "topFirst": True,
            },
        )
        messages = [
            self._summary_from_raw(item).model_dump(mode="json")
            for item in payload.get("var", [])
            if isinstance(item, dict)
        ]
        return ModuleEnvelope(
            module="mail_messages",
            source_system="coremail",
            coverage=CoverageLevel.VERIFIED,
            source_params={"folder_id": str(folder_id), "start": start, "limit": limit, "mboxa": mboxa},
            data={
                "folder_id": str(folder_id),
                "start": start,
                "limit": limit,
                "total": self._int_value(payload.get("total")),
                "messages": messages,
            },
        )

    async def fetch_message_detail(self, *, message_id: str, mboxa: str = "") -> ModuleEnvelope:
        payload = await self._post_form(
            "/coremail/XT/jsp/readMessage.jsp",
            {"mid": message_id, "mboxa": mboxa, "part": "", "mailCipherPassword": ""},
        )
        value = payload.get("var") if isinstance(payload.get("var"), dict) else {}
        mail = value.get("mail") if isinstance(value.get("mail"), dict) else {}
        info = value.get("mailInfo") if isinstance(value.get("mailInfo"), dict) else {}
        summary = self._summary_from_raw(info)
        content = mail.get("mainPartData") if isinstance(mail.get("mainPartData"), dict) else {}
        attachments = [
            self._attachment_from_raw(item)
            for item in self._dict_items(mail.get("attachments"))
        ]
        detail = MailMessageDetail(
            **summary.model_dump(mode="json"),
            from_list=self._string_list(mail.get("from")),
            to_list=self._string_list(mail.get("to")),
            cc_list=self._string_list(mail.get("cc")),
            bcc_list=self._string_list(mail.get("bcc")),
            html_content=str(content.get("content") or ""),
            headers=mail.get("headers") if isinstance(mail.get("headers"), dict) else {},
            attachments=attachments,
        )
        return ModuleEnvelope(
            module="mail_message",
            source_system="coremail",
            coverage=CoverageLevel.VERIFIED,
            source_params={"message_id": message_id, "mboxa": mboxa},
            data=detail.model_dump(mode="json"),
        )

    async def download_attachment(
        self,
        *,
        message_id: str,
        part: str,
        filename: str | None = None,
    ) -> tuple[bytes, str, str | None]:
        await self._ensure_ready()
        path = "/coremail/mbox-data"
        if filename:
            path = f"{path}/{quote(filename, safe='')}"
        response = await self.client.get(
            f"{COREMAIL_BASE_URL}{path}",
            params={"part": part, "mid": message_id, "mode": "download"},
            headers={"Referer": self._referer()},
        )
        response.raise_for_status()
        content_type = response.headers.get("content-type", "application/octet-stream").split(";", 1)[0].strip()
        return response.content, content_type or "application/octet-stream", response.headers.get("content-disposition")

    async def delete_messages(self, *, message_ids: list[str], mboxa: str = "") -> MailDeleteResponse:
        payload = await self._post_json_func(
            "mbox:updateMessageInfos",
            {
                "attrs": {"fid": COREMAIL_TRASH_FOLDER_ID},
                "ids": message_ids,
                "mboxa": mboxa,
                "returnOriginalMsgInfos": True,
                "expandThreadMid": False,
            },
        )
        return MailDeleteResponse(
            status="deleted",
            message_ids=message_ids,
            target_folder_id=str(COREMAIL_TRASH_FOLDER_ID),
            upstream={"var": payload.get("var")},
        )

    async def save_draft(self, request: MailComposeBaseRequest) -> MailComposeResponse:
        compose_id, attrs = await self._compose_body(request)
        payload = await self._post_json_path(
            "/coremail/common/mbox/compose.jsp",
            {
                "action": "save",
                "id": compose_id,
                "returnInfo": True,
                "encryptPassword": "",
                "attrs": attrs,
            },
            params={"isUserConfirmed": "true"},
        )
        return MailComposeResponse(
            status="saved",
            compose_id=compose_id,
            draft_id=str(payload.get("draftId") or "") or None,
            upstream={"var": payload.get("var")},
        )

    async def send_message(self, request: MailComposeBaseRequest, *, autosave_hit_counter: bool = True) -> MailComposeResponse:
        compose_id, attrs = await self._compose_body(request)
        payload = await self._post_json_path(
            "/coremail/common/mbox/compose.jsp",
            {
                "action": "deliver",
                "id": compose_id,
                "returnInfo": True,
                "autosaveHitCounter": autosave_hit_counter,
                "encryptPassword": "",
                "attrs": attrs,
            },
            params={"isUserConfirmed": "true"},
        )
        saved_sent = payload.get("savedSent") if isinstance(payload.get("savedSent"), dict) else {}
        return MailComposeResponse(
            status="sent",
            compose_id=compose_id,
            sent_message_id=str(saved_sent.get("mid") or "") or None,
            upstream={
                "var": payload.get("var"),
                "savedSent": payload.get("savedSent"),
                "sentTInfo": payload.get("sentTInfo"),
            },
        )

    async def autocomplete_contacts(self, *, keyword: str, limit: int = 20) -> ModuleEnvelope:
        payload = await self._post_json_func(
            "oab:autoMatch",
            {
                "@type": "U,L,X",
                "attrIds": ["m", "@id", "@type", "location"],
                "enableAliasAC": True,
                "enableNickNameAC": True,
                "enableVirtualUserAC": False,
                "keyword": keyword,
                "limit": limit,
                "matchDuty": False,
                "matchPhone": False,
            },
        )
        raw_contacts = payload.get("var") if isinstance(payload.get("var"), list) else []
        contacts = [
            self._contact_from_raw(item).model_dump(mode="json")
            for item in raw_contacts
            if isinstance(item, (dict, str))
        ]
        return ModuleEnvelope(
            module="mail_contacts",
            source_system="coremail",
            coverage=CoverageLevel.VERIFIED,
            source_params={"keyword": keyword, "limit": limit},
            data={"keyword": keyword, "contacts": contacts},
        )

    async def create_compose(self, *, mboxa: str = "") -> str:
        payload = await self._post_form(
            "/coremail/XT/jsp/compose.jsp",
            {"ctype": "normal", "mboxa": mboxa},
            include_sid=True,
        )
        value = payload.get("var") if isinstance(payload.get("var"), dict) else {}
        compose_id = str(value.get("id") or "")
        if not compose_id:
            raise CoremailError("Coremail compose id missing")
        return compose_id

    def _content_disposition_for_upload(self, filename: str) -> str:
        quoted = quote(filename, safe="")
        return f"attachment; filename=\"{quoted}\"; filename*=UTF-8''{quoted}"

    async def upload_attachment(
        self,
        *,
        filename: str,
        content: bytes,
        content_type: str | None,
        compose_id: str | None = None,
    ) -> MailAttachmentUploadResponse:
        active_compose_id = (compose_id or "").strip() or await self.create_compose()
        prepared = await self._post_json_func(
            "upload:prepare",
            {
                "composeId": active_compose_id,
                "attachmentId": -1,
                "fileName": filename,
                "contentType": content_type or "",
                "securityLevel": None,
                "size": len(content),
            },
        )
        prepared_var = prepared.get("var") if isinstance(prepared.get("var"), dict) else {}
        attachment_id = self._int_value(prepared_var.get("attachmentId"), default=-1)
        if attachment_id < 0:
            raise CoremailError("Coremail attachment id missing")

        uploaded_var: dict[str, Any] = dict(prepared_var)
        for offset in range(0, len(content) or 1, COREMAIL_CHUNK_SIZE):
            chunk = content[offset : offset + COREMAIL_CHUNK_SIZE]
            end = offset + len(chunk) - 1 if chunk else offset
            response = await self.client.post(
                f"{COREMAIL_BASE_URL}/coremail/XT/jsp/upload.jsp",
                params={
                    "func": "directData",
                    "sid": await self._ensure_ready(),
                    "composeId": active_compose_id,
                    "attachmentId": attachment_id,
                    "offset": offset,
                },
                files={"files[]": (filename, chunk, content_type or "application/octet-stream")},
                headers={
                    "Accept": "text/plain, */*; q=0.01",
                    "Content-Disposition": self._content_disposition_for_upload(filename),
                    "Content-Range": f"bytes {offset}-{end}/{len(content)}",
                    "Referer": self._referer(),
                    "X-Requested-With": "XMLHttpRequest",
                },
            )
            response.raise_for_status()
            upload_payload = response.json()
            if not isinstance(upload_payload, dict):
                raise CoremailError("upload:directData returned non-object JSON")
            self._ensure_success(upload_payload, context="upload:directData")
            if isinstance(upload_payload.get("var"), dict):
                uploaded_var.update(upload_payload["var"])

        attachment = MailAttachment(
            attachment_id=str(attachment_id),
            filename=filename,
            content_type=str(uploaded_var.get("contentType") or content_type or "") or None,
            size=self._int_value(uploaded_var.get("size"), default=len(content)),
            part=str(attachment_id),
        )
        attachment_payload = {
            "id": attachment_id,
            "type": "upload",
            "name": filename,
            "displayName": filename,
            "size": len(content),
        }
        composed = await self._post_json_func(
            "mbox:compose",
            {
                "id": active_compose_id,
                "attrs": {"attachments": [attachment_payload]},
                "returnInfo": ["attachments"],
            },
        )
        return MailAttachmentUploadResponse(
            status="uploaded",
            compose_id=active_compose_id,
            attachment=attachment,
            upstream={"prepare": prepared_var, "compose": composed.get("var")},
        )
