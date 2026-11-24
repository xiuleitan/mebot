from __future__ import annotations

import json
import logging
import random
import sqlite3
import time
from contextlib import closing
from dataclasses import astuple, dataclass
from datetime import datetime, timedelta, timezone
from http.client import HTTPException
from pathlib import Path
from threading import Event, Lock, Thread
from typing import Callable
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


LOGGER = logging.getLogger(__name__)
EQN_REST = "https://api.eqn.news/public/v1/news"
EQN_WS = "wss://api.eqn.news/public/ws"
NEWS_TYPE = "trading_news"
HISTORY_SIZE = 1000
HISTORY_LIMIT = 100
MAX_PAGES = 20
FRESH_FOR = timedelta(minutes=5)


def _parse_time(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc) if parsed.tzinfo else None
    except (ValueError, OverflowError):
        return None


def _timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


@dataclass(frozen=True)
class NewsItem:
    news_id: str
    channel: str
    title: str
    published_at: str
    url: str | None = None

    def payload(self) -> dict[str, str]:
        result = {
            "news_id": self.news_id, "channel": self.channel,
            "title": self.title, "published_at": self.published_at,
        }
        if self.url is not None:
            result["url"] = self.url
        return result


def _as_news(row: dict, channel: str) -> NewsItem | None:
    number = row.get("no")
    if type(number) is not int or not 0 < number < 10**20:
        return None
    tags = row.get("channels")
    if not isinstance(tags, list) or channel not in tags:
        return None
    # The REST "main" query also includes announcements tagged main plus another channel.
    if channel == "main" and any(tag != "main" for tag in tags):
        return None
    when = _parse_time(row.get("time"))
    if when is None:
        return None
    for language in ("zh", "en"):
        text = row.get(language)
        if isinstance(text, str) and text.strip():
            title = text.strip().splitlines()[0].strip()
            title = title.encode("utf-8", errors="replace")[:768].decode("utf-8", errors="ignore")
            if title:
                break
    else:
        return None

    url = None
    for field in ("url", "source_url"):
        candidate = row.get(field)
        if not isinstance(candidate, str) or not 0 < len(candidate) <= 2048 or candidate.strip() != candidate:
            continue
        try:
            address = urlsplit(candidate)
            valid = address.scheme == "https" and address.hostname and address.username is None
            if valid and len(candidate.encode("utf-8")) <= 2048:
                url = candidate
                break
        except (ValueError, UnicodeError):
            continue
    return NewsItem(f"eqn:{number}", channel, title, _timestamp(when), url)


class NewsStore:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as connection, connection:
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS news (
                    news_id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL,
                    title TEXT NOT NULL,
                    published_at TEXT NOT NULL,
                    url TEXT
                );
                CREATE INDEX IF NOT EXISTS news_by_time ON news(published_at DESC, news_id DESC);
                CREATE TABLE IF NOT EXISTS news_sync (
                    channel TEXT PRIMARY KEY,
                    fetched_at TEXT NOT NULL
                );
            """)

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=3)

    def recent(self, limit: int) -> list[NewsItem]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT news_id, channel, title, published_at, url FROM news "
                "ORDER BY published_at DESC, news_id DESC LIMIT ?", (limit,),
            )
            return [NewsItem(*row) for row in rows]

    def checkpoint(self, channel: str) -> datetime | None:
        with closing(self._connect()) as connection:
            row = connection.execute("SELECT fetched_at FROM news_sync WHERE channel = ?", (channel,)).fetchone()
        return _parse_time(row[0]) if row else None

    def record(self, channel: str, fetched_at: datetime | None, items: list[NewsItem]) -> list[NewsItem]:
        inserted = []
        with closing(self._connect()) as connection, connection:
            for item in items:
                result = connection.execute(
                    "INSERT OR IGNORE INTO news (news_id, channel, title, published_at, url) VALUES (?, ?, ?, ?, ?)",
                    astuple(item),
                )
                if result.rowcount:
                    inserted.append(item)
            if fetched_at is not None:
                connection.execute(
                    "INSERT INTO news_sync (channel, fetched_at) VALUES (?, ?) "
                    "ON CONFLICT(channel) DO UPDATE SET fetched_at = MAX(news_sync.fetched_at, excluded.fetched_at)",
                    (channel, _timestamp(fetched_at)),
                )
            if inserted:
                connection.execute(
                    "DELETE FROM news WHERE news_id IN (SELECT news_id FROM news "
                    "ORDER BY published_at DESC, news_id DESC LIMIT -1 OFFSET ?)", (HISTORY_SIZE,),
                )
        return inserted


class NewsFetchError(Exception):
    pass


@dataclass(frozen=True)
class NewsPage:
    rows: list[dict]
    next_cursor: str | None = None
    etag: str | None = None


class EquationRest:
    def __init__(self):
        self._next_request = 0.0

    def page(self, channel: str, stop: Event, *, before: str | None = None, etag: str | None = None) -> NewsPage | None:
        wait = max(0.0, self._next_request - time.monotonic())
        if stop.wait(wait):
            raise NewsFetchError("新闻采集已停止")
        self._next_request = time.monotonic() + 1.0
        params = {"channel": channel, "limit": "50"}
        if before:
            params["before"] = before
        headers = {"Accept": "application/json", "User-Agent": "probe-vps/1.0"}
        if etag:
            headers["If-None-Match"] = etag
        try:
            with urlopen(Request(EQN_REST + "?" + urlencode(params), headers=headers), timeout=8) as reply:
                body = json.load(reply)
                response_etag = reply.headers.get("ETag")
        except HTTPError as error:
            with error:
                if error.code == 304:
                    return None
                if error.code == 429:
                    retry = error.headers.get("Retry-After", "10")
                    self._next_request = time.monotonic() + (max(1, int(retry)) if retry.isdecimal() else 10)
                raise NewsFetchError(f"方程式 REST 返回 HTTP {error.code}") from error
        except (OSError, HTTPException, ValueError):
            raise NewsFetchError("方程式 REST 请求或 JSON 解析失败") from None

        if not isinstance(body, dict) or not isinstance(body.get("items"), list):
            raise NewsFetchError("方程式 REST 缺少新闻列表")
        cursor = body.get("next_cursor")
        return NewsPage(
            [row for row in body["items"] if isinstance(row, dict)],
            cursor if isinstance(cursor, str) else None,
            response_etag,
        )


class NewsCollector:
    def __init__(self, store: NewsStore, rest: EquationRest, publish: Callable[[NewsItem], None] | None = None):
        self.store = store
        self.rest = rest
        self.publish = publish
        self._main_signal = Event()
        self._ws_lock = Lock()
        self._ws_since: datetime | None = None
        self._etags: dict[str, str] = {}

    def start(self, stop: Event) -> None:
        try:
            from websockets.exceptions import WebSocketException
            from websockets.sync.client import connect
        except ImportError:
            raise ValueError("新闻采集需要先安装 requirements-news.txt 中的依赖") from None

        Thread(target=self._run_websocket, args=(stop, connect, WebSocketException),
               name="equation-news-ws", daemon=True).start()
        Thread(target=self._run_rest, args=(stop,), name="equation-news-rest", daemon=True).start()

    def _run_websocket(self, stop: Event, connect: Callable, websocket_error: type[Exception]) -> None:
        backoff = 1.0
        while not stop.is_set():
            started = time.monotonic()
            try:
                with connect(EQN_WS, ping_interval=None, open_timeout=8, close_timeout=2, max_size=65536) as websocket:
                    with self._ws_lock:
                        self._ws_since = datetime.now(timezone.utc)
                        self._main_signal.set()
                    LOGGER.info("方程式主频道 WS 已连接，开始同步历史")
                    self._listen(websocket, stop)
            except (OSError, websocket_error) as error:
                if not stop.is_set():
                    LOGGER.warning("方程式 WS 已断开（%s）", type(error).__name__)
                if getattr(getattr(error, "rcvd", None), "code", None) == 1008:
                    backoff = max(backoff, 10)
            finally:
                with self._ws_lock:
                    self._ws_since = None
            if time.monotonic() - started >= 30:
                backoff = 1.0
            stop.wait(backoff + random.uniform(0, 0.25))
            backoff = min(backoff * 2, 30)

    def _listen(self, websocket, stop: Event) -> None:
        next_ping = time.monotonic()
        pong_deadline = None
        while not stop.is_set():
            now = time.monotonic()
            if pong_deadline is not None and now >= pong_deadline:
                raise TimeoutError("方程式 WS 未回复 pong")
            if now >= next_ping:
                websocket.send("ping")
                next_ping = now + 30
                pong_deadline = now + 10
            try:
                raw = websocket.recv(timeout=1)
            except TimeoutError:
                continue
            if raw == "pong":
                pong_deadline = None
            elif isinstance(raw, str):
                try:
                    frame = json.loads(raw)
                except ValueError:
                    continue
                if isinstance(frame, dict) and isinstance(frame.get("news_title"), str):
                    with self._ws_lock:
                        self._main_signal.set()

    def _run_rest(self, stop: Event) -> None:
        next_run = {"main": 0.0, "tradfi": 0.0}
        while not stop.is_set():
            now = time.monotonic()
            with self._ws_lock:
                signaled = self._main_signal.is_set()
                refresh_main = signaled or now >= next_run["main"]
                if refresh_main:
                    self._main_signal.clear()
            if refresh_main:
                self._try_refresh("main", stop)
                next_run["main"] = time.monotonic() + (2 if signaled else 10)
            if not stop.is_set() and time.monotonic() >= next_run["tradfi"]:
                self._try_refresh("tradfi", stop)
                next_run["tradfi"] = time.monotonic() + 5
            wait = min(1.0, max(0.0, min(next_run.values()) - time.monotonic()))
            self._main_signal.wait(wait)

    def _try_refresh(self, channel: str, stop: Event) -> None:
        try:
            self.refresh(channel, stop)
        except (NewsFetchError, sqlite3.Error) as error:
            if not stop.is_set():
                LOGGER.warning("方程式 %s 同步失败（%s）", channel, error)

    def refresh(self, channel: str, stop: Event) -> None:
        fetched_at = datetime.now(timezone.utc)
        last_sync = self.store.checkpoint(channel)
        page = self.rest.page(channel, stop, etag=self._etags.get(channel))
        if page is None:
            return

        latest_etag = page.etag
        items = [item for row in page.rows if (item := _as_news(row, channel)) is not None]
        if last_sync is not None:
            # Deliver current items promptly. A slow historical page shouldn't delay a live notification.
            inserted = self.store.record(channel, None, items)
            self._publish_fresh(channel, inserted)
            cutoff = last_sync - timedelta(seconds=30)
            cursors = set()
            for _ in range(MAX_PAGES - 1):
                oldest = min((when for row in page.rows if (when := _parse_time(row.get("time"))) is not None), default=None)
                cursor = page.next_cursor
                if not page.rows or not cursor or (oldest is not None and oldest <= cutoff):
                    break
                if cursor in cursors:
                    raise NewsFetchError("方程式返回了重复的翻页游标")
                cursors.add(cursor)
                page = self.rest.page(channel, stop, before=cursor)
                if page is None:
                    raise NewsFetchError("方程式分页响应为空")
                history = [item for row in page.rows if (item := _as_news(row, channel)) is not None]
                self.store.record(channel, None, history)
            items = []

        self.store.record(channel, fetched_at, items)
        if latest_etag is not None:
            self._etags[channel] = latest_etag
        else:
            self._etags.pop(channel, None)

    def _publish_fresh(self, channel: str, inserted: list[NewsItem]) -> None:
        if self.publish is None or not inserted:
            return

        now = datetime.now(timezone.utc)
        notify_from = now - FRESH_FOR
        if channel == "main":
            with self._ws_lock:
                ws_since = self._ws_since
            if ws_since is None:
                return
            notify_from = max(notify_from, ws_since)
        for item in sorted(inserted, key=lambda row: row.published_at):
            when = _parse_time(item.published_at)
            if when is not None and when >= notify_from:
                self.publish(item)
