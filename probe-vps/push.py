from __future__ import annotations

import json
import logging
import re
import time
from datetime import datetime, timezone
from itertools import count
from pathlib import Path
from queue import Empty, Full, PriorityQueue, Queue
from threading import Event, Lock
from typing import Callable, Protocol
from urllib.parse import quote

from monitor import AlertEvent, EVENT_HISTORY_SIZE, format_amount, utc_timestamp
from news import FRESH_FOR, NEWS_TYPE, NewsItem


LOGGER = logging.getLogger(__name__)
PACKAGE_NAME = "com.txl.mebot"
MESSAGE_TTL_SECONDS = 300
MESSAGE_TTL = f"{MESSAGE_TTL_SECONDS}s"
PUSH_CHECK_TYPE = "trading_push_check"
PUSH_CHECK_TTL = "20s"
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class PushError(Exception):
    pass


class Sender(Protocol):
    def send(self, token: str, data: dict[str, str]) -> None:
        ...


class FcmSender:
    def __init__(self, project_id: str, post: Callable):
        project_path = quote(project_id, safe="")
        self.endpoint = f"https://fcm.googleapis.com/v1/projects/{project_path}/messages:send"
        self._post = post

    @classmethod
    def from_service_account(cls, path: Path) -> FcmSender:
        try:
            from google.auth.exceptions import GoogleAuthError
            from google.auth.transport.requests import AuthorizedSession
            from google.oauth2 import service_account
            from requests.exceptions import RequestException
        except ImportError:
            raise ValueError("FCM 发送需要先安装 requirements-fcm.txt 中的依赖") from None

        try:
            info = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(info, dict) or info.get("type") != "service_account":
                raise ValueError("Expected a service account JSON object")
            credentials = service_account.Credentials.from_service_account_info(info, scopes=[FCM_SCOPE])
        except (GoogleAuthError, OSError, ValueError):
            raise ValueError("无法读取 FCM 服务账号，请检查本机 JSON 文件和访问权限") from None
        if not credentials.project_id:
            raise ValueError("FCM 服务账号 JSON 缺少 project_id")

        session = AuthorizedSession(credentials, refresh_timeout=10)

        def post(url, body):
            try:
                return session.post(url, json=body, timeout=10, allow_redirects=False)
            except (GoogleAuthError, RequestException):
                raise PushError("FCM 网络或鉴权请求失败") from None

        return cls(credentials.project_id, post)

    def send(self, token: str, data: dict[str, str]) -> None:
        message_type = data.get("type")
        if message_type == PUSH_CHECK_TYPE:
            priority, ttl = "normal", PUSH_CHECK_TTL
        else:
            priority = "normal" if message_type == NEWS_TYPE and data.get("channel") == "tradfi" else "high"
            ttl = MESSAGE_TTL
        body = {
            "message": {
                "token": token,
                "data": data,
                "android": {"priority": priority, "ttl": ttl, "restricted_package_name": PACKAGE_NAME},
            },
        }
        response = self._post(self.endpoint, body)
        if 200 <= response.status_code < 300:
            return
        status = _firebase_status(response)
        raise PushError(f"FCM HTTP {response.status_code}" + (f" ({status})" if status else ""))


def _firebase_status(response) -> str | None:
    try:
        body = response.json()
    except ValueError:
        return None
    error = body.get("error") if isinstance(body, dict) else None
    status = error.get("status") if isinstance(error, dict) else None
    if isinstance(status, str) and re.fullmatch(r"[A-Z_]{1,40}", status):
        return status
    return None


class DeviceRegistry:
    def __init__(self):
        self._tokens: dict[str, str] = {}
        self._lock = Lock()

    def register(self, device_id: str, token: str) -> None:
        with self._lock:
            self._tokens[device_id] = token

    def token_for(self, device_id: str) -> str | None:
        with self._lock:
            return self._tokens.get(device_id)

    def registered_devices(self) -> dict[str, str]:
        with self._lock:
            return dict(self._tokens)


class PushDispatcher:
    def __init__(self, sender: Sender | None = None):
        self._sender = sender
        self._devices = DeviceRegistry()
        self._lock = Lock()
        self._pending: Queue[tuple[AlertEvent, str, float]] = Queue(maxsize=EVENT_HISTORY_SIZE)
        self._last_accepted_at: str | None = None
        self._last_error: str | None = None

    @property
    def available(self) -> bool:
        return self._sender is not None

    def register(self, device_id: str, token: str) -> None:
        self._devices.register(device_id, token)

    def is_registered(self, device_id: str) -> bool:
        return self._devices.token_for(device_id) is not None

    def status(self) -> dict:
        with self._lock:
            return {"available": self.available, "last_accepted_at": self._last_accepted_at, "last_error": self._last_error}

    def enqueue(self, event: AlertEvent, instance_id: str) -> None:
        if self._sender is None or event.device_id is None:
            return
        try:
            self._pending.put_nowait((event, instance_id, time.monotonic() + MESSAGE_TTL_SECONDS))
        except Full:
            self._failed(event, "FCM 发送队列已满")

    def run(self, stop: Event) -> None:
        while not stop.is_set():
            try:
                event, instance_id, expires_at = self._pending.get(timeout=0.5)
            except Empty:
                continue
            if time.monotonic() >= expires_at:
                self._failed(event, "提醒已超过发送时限")
            else:
                self._deliver(event, instance_id)

    def _deliver(self, event: AlertEvent, instance_id: str) -> None:
        if self._sender is None or event.device_id is None:
            return
        token = self._devices.token_for(event.device_id)
        if token is None:
            self._failed(event, "设备尚未注册 FCM")
            return

        data = {
            "type": "trading_price_alert",
            "device_id": event.device_id,
            "instance_id": instance_id,
            "event_id": str(event.id),
            "rule_id": event.rule_id,
            "kind": event.kind,
            "symbol": event.symbol,
            "mark_price": format_amount(event.mark_price),
            "triggered_at": event.triggered_at,
        }
        if len(event.message.encode("utf-8")) <= 1024:
            data["message"] = event.message
        try:
            self._sender.send(token, data)
        except PushError as error:
            self._failed(event, str(error))
        else:
            with self._lock:
                self._last_accepted_at = utc_timestamp()
                self._last_error = None
            LOGGER.info("FCM 已接收提醒事件 %s", event.id)

    def _failed(self, event: AlertEvent, reason: str) -> None:
        with self._lock:
            self._last_error = reason
        LOGGER.warning("提醒事件 %s 推送失败（%s）", event.id, reason)


class NewsPushDispatcher:
    def __init__(self, sender: Sender, devices: DeviceRegistry, instance_id: str):
        self._sender = sender
        self._devices = devices
        self._instance_id = instance_id
        self._sequence = count()
        self._pending: PriorityQueue[tuple[int, int, NewsItem]] = PriorityQueue(maxsize=256)

    def enqueue(self, item: NewsItem) -> None:
        rank = 0 if item.channel == "main" else 1
        try:
            self._pending.put_nowait((rank, next(self._sequence), item))
        except Full:
            LOGGER.warning("新闻发送队列已满，%s 可通过新闻历史补取", item.news_id)

    def run(self, stop: Event) -> None:
        while not stop.is_set():
            try:
                _, _, item = self._pending.get(timeout=0.5)
            except Empty:
                continue
            expires = datetime.fromisoformat(item.published_at.replace("Z", "+00:00")) + FRESH_FOR
            for device_id, token in self._devices.registered_devices().items():
                if stop.is_set() or datetime.now(timezone.utc) >= expires:
                    break
                data = {
                    "type": NEWS_TYPE, "instance_id": self._instance_id, "device_id": device_id,
                    **item.payload(),
                }
                try:
                    self._sender.send(token, data)
                except PushError as error:
                    LOGGER.warning("新闻 %s 推送失败（%s）", item.news_id, error)
                else:
                    LOGGER.info("FCM 已接收新闻 %s", item.news_id)
