from __future__ import annotations

import argparse
import hmac
import json
import logging
import math
import os
import re
import sqlite3
import ssl
import sys
import time
from collections import OrderedDict
from dataclasses import asdict
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ipaddress import IPv4Address
from pathlib import Path
from threading import Event, Lock, Thread
from urllib.parse import parse_qs, unquote, urlsplit
from uuid import UUID, uuid4

from binance import DEFAULT_BASE_URL, BinanceClient, MarketDataError
from monitor import AlertEvent, FollowedContract, TargetAlert, Updown, format_amount
from news import HISTORY_LIMIT, EquationRest, NewsCollector, NewsStore
from probe import ContractNotTrading, DuplicateRule, Probe, normalize_alias, normalize_symbol
from probe import positive_amount, positive_minutes
from push import (
    DeviceRegistry, FcmSender, NewsPushDispatcher, PACKAGE_NAME, PUSH_CHECK_TYPE, PushDispatcher, PushError, Sender,
)


LOGGER = logging.getLogger(__name__)
PREFIX = "/api/v1"
COLLECTIONS = {"follows", "alerts", "updowns"}
MAX_BODY_BYTES = 64 * 1024
MAX_REPLAY_ENTRIES = 512
PUSH_CHECK_INTERVAL = 10.0


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


def _text(data: dict, field: str) -> str:
    value = data.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} 需要填写非空字符串")
    value = value.strip()
    try:
        value.encode("utf-8")
    except UnicodeError as error:
        raise ValueError(f"{field} 需要填写有效的 UTF-8 字符串") from error
    return value


def _only_fields(data: dict, *fields: str) -> None:
    unexpected = sorted(data.keys() - set(fields))
    if unexpected:
        raise ValueError(f"未知字段 {', '.join(unexpected)}")


def _device_id(value: str) -> str:
    try:
        canonical = str(UUID(value))
    except ValueError:
        raise ValueError("device_id 需要填写安装实例的 UUID") from None
    if value.lower() != canonical:
        raise ValueError("device_id 需要填写带连字符的 UUID")
    return canonical


def _canonical_uuid(data: dict, field: str) -> str:
    value = data.get(field)
    if isinstance(value, str):
        try:
            if str(UUID(value)) == value:
                return value
        except ValueError:
            pass
    raise ValueError(f"{field} 需要填写小写、带连字符的规范 UUID")


def _device_filter(parameters: dict[str, list[str]]) -> str | None:
    values = parameters.get("device_id")
    if values is None:
        return None
    if len(values) != 1:
        raise ValueError("请只提供一个 device_id")
    return _device_id(values[0])


def _alert_payload(alert: TargetAlert) -> dict:
    payload = {
        "id": alert.id,
        "symbol": alert.contract.symbol,
        "target_price": format_amount(alert.target),
        "reference_price": format_amount(alert.reference),
        "direction": "up" if alert.target > alert.reference else "down",
    }
    if alert.device_id is not None:
        payload["device_id"] = alert.device_id
    return payload


def _updown_payload(rule: Updown) -> dict:
    payload = {
        "id": rule.id,
        "symbol": rule.contract.symbol,
        "mode": rule.mode,
        "window_minutes": rule.minutes or None,
        "threshold": format_amount(rule.threshold),
        "unit": "percent" if rule.unit == "%" else "absolute",
    }
    if rule.device_id is not None:
        payload["device_id"] = rule.device_id
    return payload


def _event_payload(event: AlertEvent) -> dict:
    payload = asdict(event)
    payload["mark_price"] = format_amount(event.mark_price)
    if event.device_id is None:
        del payload["device_id"]
    return payload


class Api:
    def __init__(
        self, probe: Probe, interval: float, source: str, token: str = "", push: PushDispatcher | None = None,
        news: NewsStore | None = None, *, news_push_enabled: bool = False, news_check_sender: Sender | None = None,
    ):
        self.probe = probe
        self.interval = interval
        self.source = source
        self.instance_id = uuid4().hex
        self.push = push if push is not None else PushDispatcher()
        self.news = news
        self.news_push_enabled = news_push_enabled
        self.news_devices = DeviceRegistry()
        self._news_check_sender = news_check_sender
        self._check_lock = Lock()
        self._check_send_lock = Lock()
        self._checks_in_flight: set[str] = set()
        self._last_accepted_checks: dict[str, float] = {}
        self._token = ("Bearer " + token).encode("utf-8") if token else None
        self._submit_lock = Lock()
        self._replays: OrderedDict[str, tuple[str, int, dict]] = OrderedDict()

    def authorize(self, header: str) -> None:
        if self._token is not None and not hmac.compare_digest(header.encode("utf-8"), self._token):
            raise ApiError(401, "unauthorized", "请提供有效的 Authorization Bearer 令牌")

    def route(
        self, method: str, target: str, data: dict | None = None, idempotency_key: str | None = None,
    ) -> tuple[int, dict | None]:
        url = urlsplit(target)
        if not url.path.startswith(PREFIX + "/"):
            raise ApiError(404, "not_found", "接口路径不存在")
        parts = url.path[len(PREFIX) + 1:].rstrip("/").split("/")
        resource = parts[0]
        if method == "GET" and len(parts) == 1:
            return 200, self._get(resource, url.query)
        if method == "PUT" and resource == "devices" and len(parts) == 2 and data is not None:
            return 200, self._register_device(unquote(parts[1]), data)
        if method == "PUT" and resource == "news" and len(parts) == 3 and parts[1] == "devices" and data is not None:
            if self.news is None:
                raise ApiError(404, "not_found", "新闻设备注册接口未启用")
            device_id, token = self._registration(unquote(parts[2]), data)
            self.news_devices.register(device_id, token)
            return 200, {"registered": True, "push_available": self.news_push_enabled}
        if method == "POST" and parts == ["news", "push-check"] and data is not None:
            return 200, self._news_push_check(data)
        if resource in COLLECTIONS:
            if method == "POST" and len(parts) == 1 and data is not None:
                return self._create_once(resource, data, idempotency_key)
            if len(parts) == 2:
                identifier = unquote(parts[1])
                if method == "PATCH" and resource == "follows" and data is not None:
                    return 200, self._update_follow(identifier, data)
                if method == "DELETE":
                    device_id = None
                    if resource != "follows":
                        device_id = _device_filter(parse_qs(url.query, keep_blank_values=True))
                    self._delete(resource, identifier, device_id)
                    return 204, None
        raise ApiError(404, "not_found", "接口路径或方法不存在")

    def _get(self, resource: str, query: str) -> dict:
        monitor = self.probe.monitor
        if resource == "health":
            return {
                "status": "ok",
                "instance_id": self.instance_id,
                "poll_interval_seconds": self.interval,
                "market": {"source": self.source, **self.probe.market_status()},
                "push": self.push.status(),
            }
        if resource == "events":
            return self._events(query)
        if resource == "news":
            return self._news_history(query)

        device_id = _device_filter(parse_qs(query, keep_blank_values=True)) if resource in {"alerts", "updowns"} else None
        with self.probe.state_lock:
            if resource == "follows":
                follows = sorted(monitor.follows.values(), key=lambda contract: (contract.category, contract.alias))
                return {"items": [asdict(contract) for contract in follows]}
            if resource == "alerts":
                return {"items": [
                    _alert_payload(alert) for alert in monitor.alerts.values()
                    if device_id is None or alert.device_id == device_id
                ]}
            if resource == "updowns":
                return {"items": [
                    _updown_payload(rule) for rule in monitor.updowns.values()
                    if device_id is None or rule.device_id == device_id
                ]}
        raise ApiError(404, "not_found", "接口路径不存在")

    def _news_history(self, query: str) -> dict:
        parameters = parse_qs(query, keep_blank_values=True)
        values = parameters.get("limit", [str(HISTORY_LIMIT)])
        if parameters.keys() - {"limit"} or len(values) != 1 or re.fullmatch(r"[0-9]{1,3}", values[0]) is None:
            raise ValueError("limit 需要填写 1 至 100 的整数")
        limit = int(values[0])
        if not 1 <= limit <= HISTORY_LIMIT:
            raise ValueError("limit 需要填写 1 至 100 的整数")
        try:
            items = self.news.recent(limit) if self.news is not None else []
        except sqlite3.Error as error:
            raise ApiError(503, "news_unavailable", "暂时无法读取新闻历史") from error
        return {"instance_id": self.instance_id, "items": [item.payload() for item in items]}

    def _news_push_check(self, data: dict) -> dict:
        _only_fields(data, "device_id", "check_id")
        device_id = _canonical_uuid(data, "device_id")
        check_id = _canonical_uuid(data, "check_id")
        sender = self._news_check_sender
        if self.news is None or not self.news_push_enabled or sender is None:
            raise ApiError(503, "push_unavailable", "新闻 FCM 暂不可用")
        if self.news_devices.token_for(device_id) is None:
            raise ApiError(409, "device_not_registered", "请先在新闻服务注册此设备")

        with self._check_lock:
            previous = self._last_accepted_checks.get(device_id)
            if device_id in self._checks_in_flight or (
                previous is not None and time.monotonic() - previous < PUSH_CHECK_INTERVAL
            ):
                raise ApiError(429, "rate_limited", "设备静默检查过于频繁")
            self._checks_in_flight.add(device_id)
        try:
            with self._check_send_lock:
                token = self.news_devices.token_for(device_id)
                if token is None:
                    raise ApiError(409, "device_not_registered", "请先在新闻服务注册此设备")
                started_at = time.monotonic()
                sender.send(token, {"type": PUSH_CHECK_TYPE, "device_id": device_id, "check_id": check_id})
        except PushError as error:
            LOGGER.warning("新闻静默检查发送失败（%s）", error)
            raise ApiError(503, "push_unavailable", "FCM 暂时无法受理新闻静默检查") from error
        else:
            with self._check_lock:
                self._last_accepted_checks[device_id] = started_at
            return {"accepted": True}
        finally:
            with self._check_lock:
                self._checks_in_flight.discard(device_id)

    def _events(self, query: str) -> dict:
        parameters = parse_qs(query, keep_blank_values=True)
        device_id = _device_filter(parameters)
        after = parameters.get("after_id", ["0"])
        if parameters.keys() - {"after_id", "device_id"} or len(after) != 1 or re.fullmatch(r"[0-9]+", after[0]) is None:
            raise ValueError("after_id 需要填写非负整数")
        after_id = int(after[0])
        with self.probe.state_lock:
            events = list(self.probe.monitor.events)
        return {
            "instance_id": self.instance_id,
            "oldest_id": events[0].id if events else None,
            "last_id": events[-1].id if events else None,
            "items": [
                _event_payload(event)
                for event in events if event.id > after_id and (device_id is None or event.device_id == device_id)
            ],
        }

    @staticmethod
    def _registration(identifier: str, data: dict) -> tuple[str, str]:
        device_id = _device_id(identifier)
        _only_fields(data, "fcm_token", "package_name")
        if _text(data, "package_name") != PACKAGE_NAME:
            raise ValueError(f"package_name 需要填写 {PACKAGE_NAME}")
        token = _text(data, "fcm_token")
        if re.fullmatch(r"[\x21-\x7e]{1,4096}", token) is None:
            raise ValueError("fcm_token 需要填写有效的 FCM 设备令牌")
        return device_id, token

    def _register_device(self, identifier: str, data: dict) -> dict:
        device_id, token = self._registration(identifier, data)
        self.push.register(device_id, token)
        return {"registered": True, "push_available": self.push.available}

    def _registered_device(self, data: dict) -> str | None:
        if "device_id" not in data:
            return None
        device_id = _device_id(_text(data, "device_id"))
        if not self.push.is_registered(device_id):
            raise ApiError(409, "device_not_registered", "请先注册该设备的 FCM 令牌")
        return device_id

    def _create_once(self, resource: str, data: dict, key: str | None) -> tuple[int, dict]:
        if key is None:
            return self._create(resource, data)
        if re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", key) is None:
            raise ValueError("Idempotency-Key 需要填写 1 至 128 个字母、数字、连字符或下划线")

        signature = resource + json.dumps(data, sort_keys=True, separators=(",", ":"))
        with self._submit_lock:
            cached = self._replays.get(key)
            if cached is not None:
                original, status, payload = cached
                if original != signature:
                    raise ApiError(409, "idempotency_conflict", "该 Idempotency-Key 已被其他请求使用")
                self._replays.move_to_end(key)
                return status, payload
            status, payload = self._create(resource, data)
            self._replays[key] = signature, status, payload
            if len(self._replays) > MAX_REPLAY_ENTRIES:
                self._replays.popitem(last=False)
            return status, payload

    def _create(self, resource: str, data: dict) -> tuple[int, dict]:
        if resource == "follows":
            _only_fields(data, "alias", "symbol", "name", "category")
            alias = normalize_alias(_text(data, "alias"))
            symbol = normalize_symbol(_text(data, "symbol"))
            name = _text(data, "name") if "name" in data else symbol
            category = _text(data, "category") if "category" in data else "加密货币"
            with self.probe.state_lock:
                if alias in self.probe.monitor.follows:
                    raise ApiError(409, "alias_exists", f"已存在关注别名 {alias}")
                return 201, asdict(self.probe.monitor.follow(alias, symbol, name, category))
        if resource == "alerts":
            return self._create_targets(data)
        return self._create_updown(data)

    def _create_targets(self, data: dict) -> tuple[int, dict]:
        _only_fields(data, "symbol", "alias", "target_price", "target_prices", "device_id")
        if ("target_price" in data) == ("target_prices" in data):
            raise ValueError("请选择 target_price 或 target_prices")
        if "target_prices" in data:
            values = data["target_prices"]
            if not isinstance(values, list) or not values or not all(isinstance(value, str) for value in values):
                raise ValueError("target_prices 需要填写非空字符串数组")
        else:
            values = [_text(data, "target_price")]
        results = self.probe.register_targets(
            self._contract(data), [positive_amount(value) for value in values], self._registered_device(data),
        )
        items = [
            {
                "target_price": format_amount(result.target),
                "status": result.status,
                "rule": _alert_payload(result.rule) if result.rule is not None else None,
            }
            for result in results
        ]
        return (201 if any(result.status == "created" for result in results) else 200), {"items": items}

    def _create_updown(self, data: dict) -> tuple[int, dict]:
        mode = _text(data, "mode")
        if mode == "window":
            _only_fields(data, "symbol", "alias", "mode", "window_minutes", "threshold", "unit", "device_id")
            if type(data.get("window_minutes")) is not int:
                raise ValueError("window_minutes 需要填写整数")
            minutes = positive_minutes(data["window_minutes"])
            unit = {"percent": "%", "absolute": "u"}.get(_text(data, "unit"))
            if unit is None:
                raise ValueError("unit 需要填写 percent 或 absolute")
        elif mode == "ladder":
            _only_fields(data, "symbol", "alias", "mode", "threshold", "device_id")
            minutes, unit = 0, "u"
        else:
            raise ValueError("mode 需要填写 window 或 ladder")
        amount = positive_amount(_text(data, "threshold"))
        rule = self.probe.register_updown(self._contract(data), minutes, amount, unit, self._registered_device(data))
        return 201, _updown_payload(rule)

    def _contract(self, data: dict) -> FollowedContract:
        if ("symbol" in data) == ("alias" in data):
            raise ValueError("请选择 symbol 或 alias")
        with self.probe.state_lock:
            follows = self.probe.monitor.follows
            if "alias" in data:
                alias = normalize_alias(_text(data, "alias"))
                contract = follows.get(alias)
                if contract is None:
                    raise ApiError(404, "not_found", f"未找到关注合约 {alias}")
                return contract
            symbol = normalize_symbol(_text(data, "symbol"))
            for contract in follows.values():
                if contract.symbol == symbol:
                    return contract
        return FollowedContract(symbol.lower(), symbol, symbol, "")

    def _update_follow(self, identifier: str, data: dict) -> dict:
        _only_fields(data, "name", "category")
        if not data:
            raise ValueError("请至少提供 name 或 category")
        with self.probe.state_lock:
            monitor = self.probe.monitor
            existing = monitor.follows.get(identifier.lower())
            if existing is None:
                raise ApiError(404, "not_found", "关注合约不存在")
            name = _text(data, "name") if "name" in data else existing.name
            category = _text(data, "category") if "category" in data else existing.category
            return asdict(monitor.update_follow(identifier, name, category))

    def _delete(self, resource: str, identifier: str, device_id: str | None) -> None:
        monitor = self.probe.monitor
        with self.probe.state_lock:
            if device_id is not None:
                rules = monitor.alerts if resource == "alerts" else monitor.updowns
                rule = rules.get(identifier)
                if rule is None or rule.device_id != device_id:
                    raise ApiError(404, "not_found", "该设备下没有此规则")
            try:
                if resource == "follows":
                    monitor.delete_follow(identifier)
                elif resource == "alerts":
                    monitor.delete_alert(identifier)
                else:
                    monitor.delete_updown(identifier)
            except ValueError as error:
                raise ApiError(404, "not_found", str(error)) from error


class RequestHandler(BaseHTTPRequestHandler):
    def _handle(self) -> None:
        api = self.server.api
        try:
            api.authorize(self.headers.get("Authorization", ""))
            data = self._json_body() if self.command in {"POST", "PATCH", "PUT"} else None
            status, body = api.route(self.command, self.path, data, self.headers.get("Idempotency-Key"))
        except ApiError as error:
            status, body = error.status, self._error_body(error.code, str(error))
        except ContractNotTrading as error:
            status, body = 422, self._error_body("contract_not_trading", str(error))
        except DuplicateRule as error:
            status, body = 409, self._error_body("rule_exists", str(error))
        except MarketDataError as error:
            status, body = 503, self._error_body("market_unavailable", str(error))
        except TimeoutError:
            status, body = 408, self._error_body("request_timeout", "读取请求超时")
        except ValueError as error:
            status, body = 400, self._error_body("invalid_request", str(error))
        self._respond(status, body)

    do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = _handle

    @staticmethod
    def _error_body(code: str, message: str) -> dict:
        return {"error": {"code": code, "message": message}}

    def _json_body(self) -> dict:
        if self.headers.get_content_type() != "application/json":
            raise ApiError(415, "unsupported_media_type", "Content-Type 需要为 application/json")
        size = int(self.headers.get("Content-Length", "0"))
        if size <= 0:
            raise ValueError("需要提供 JSON 请求体")
        if size > MAX_BODY_BYTES:
            raise ApiError(413, "body_too_large", "JSON 请求体超过 64 KiB")
        try:
            body = json.loads(self.rfile.read(size).decode("utf-8"))
        except (RecursionError, UnicodeError, json.JSONDecodeError) as error:
            raise ValueError("请求体需要有效的 UTF-8 JSON") from error
        if not isinstance(body, dict):
            raise ValueError("请求体需要是 JSON 对象")
        return body

    def _respond(self, status: int, body: dict | None) -> None:
        payload = json.dumps(body).encode("utf-8") if body is not None else b""
        try:
            self.send_response(status)
            if body is not None:
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            if status == 401:
                self.send_header("WWW-Authenticate", "Bearer")
            self.end_headers()
            if payload:
                self.wfile.write(payload)
        except (ConnectionError, TimeoutError):
            pass

    def log_request(self, code="-", size="-") -> None:
        LOGGER.info("API %s %s", self.command, code)


class ApiServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], api: Api, tls: ssl.SSLContext | None = None):
        self.api = api
        self.tls = tls
        super().__init__(address, RequestHandler)

    def finish_request(self, request, client_address):
        request.settimeout(15)
        if self.tls is None:
            super().finish_request(request, client_address)
            return
        try:
            with self.tls.wrap_socket(request, server_side=True) as connection:
                super().finish_request(connection, client_address)
        except (ssl.SSLError, ConnectionError, TimeoutError):
            LOGGER.debug("HTTPS connection closed")


class PriceFileClient:
    def __init__(self, path: Path):
        self.path = path

    def _prices(self) -> dict[str, Decimal]:
        try:
            values = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(values, dict):
                raise ValueError("模拟报价需要是以合约代码为键的 JSON 对象")
            return {normalize_symbol(symbol): positive_amount(str(price)) for symbol, price in values.items()}
        except (OSError, ValueError) as error:
            raise MarketDataError(f"无法读取模拟报价（{error}）") from error

    def mark_prices(self, symbols: set[str]) -> dict[str, Decimal]:
        return {symbol: price for symbol, price in self._prices().items() if symbol in symbols}

    def is_trading(self, symbol: str) -> bool:
        return symbol in self._prices()


def _poll(api: Api, stop: Event) -> None:
    while not stop.is_set():
        for event in api.probe.poll_once():
            api.push.enqueue(event, api.instance_id)
        stop.wait(api.interval)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="运行 probe-vps 的价格提醒和新闻 API")
    parser.add_argument("--host", type=IPv4Address, default="127.0.0.1", help="监听的 IPv4 地址")
    parser.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    parser.add_argument("--interval", type=float, default=5, help="每轮结束后的等待秒数，默认 5")
    parser.add_argument(
        "--base-url", default=os.environ.get("BINANCE_FUTURES_BASE_URL", DEFAULT_BASE_URL),
        help="币安 U 本位 REST 接口地址",
    )
    parser.add_argument("--tls-cert", type=Path, help="HTTPS 服务证书 PEM 路径")
    parser.add_argument("--tls-key", type=Path, help="HTTPS 服务私钥 PEM 路径")
    parser.add_argument("--token-file", type=Path, help="读取访问令牌，也可设置 PROBE_API_TOKEN")
    parser.add_argument("--price-file", type=Path, help="使用 JSON 文件中的模拟报价")
    parser.add_argument(
        "--fcm-credentials", type=Path, default=os.environ.get("PROBE_FCM_CREDENTIALS") or None,
        help="FCM 服务账号 JSON 路径，也可设置 PROBE_FCM_CREDENTIALS",
    )
    parser.add_argument("--news-db", type=Path, help="启用方程式采集并把公开新闻保存在此 SQLite 文件")
    parser.add_argument("--news-push", action="store_true", help="向已注册设备发送方程式新闻 FCM")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("--port 需要在 1 至 65535 之间")
    if not math.isfinite(args.interval) or not 0 < args.interval <= 86_400:
        parser.error("--interval 需要大于 0 且不超过 86400 秒")
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("请同时提供 --tls-cert 和 --tls-key")
    if args.news_push and (args.news_db is None or args.fcm_credentials is None):
        parser.error("--news-push 需要 --news-db 和 FCM 服务账号")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    try:
        token = args.token_file.read_text(encoding="utf-8") if args.token_file else os.environ.get("PROBE_API_TOKEN", "")
        token = token.strip()
        if (args.token_file or not args.host.is_loopback) and not token:
            parser.error("请设置 PROBE_API_TOKEN 或通过 --token-file 提供非空令牌")
        if token and re.fullmatch(r"[A-Za-z0-9._~+/=-]+", token) is None:
            parser.error("访问令牌包含 Bearer 令牌格式不支持的字符")

        tls = None
        if args.tls_cert:
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(args.tls_cert, args.tls_key)
        if args.price_file is None:
            client, source = BinanceClient(args.base_url), "binance"
        else:
            client, source = PriceFileClient(args.price_file), "price_file"
        probe = Probe(client)
        sender = FcmSender.from_service_account(args.fcm_credentials) if args.fcm_credentials else None
        news = NewsStore(args.news_db) if args.news_db else None
        check_sender = FcmSender.from_service_account(args.fcm_credentials) if args.news_push else None
        api = Api(probe, args.interval, source, token, PushDispatcher(sender), news,
                  news_push_enabled=args.news_push, news_check_sender=check_sender)
        stop = Event()
        worker = Thread(target=_poll, args=(api, stop), name="binance-poll", daemon=True)
        with ApiServer((str(args.host), args.port), api, tls) as server:
            try:
                if news is not None:
                    news_push = None
                    if args.news_push:
                        news_push = NewsPushDispatcher(
                            FcmSender.from_service_account(args.fcm_credentials), api.news_devices, api.instance_id,
                        )
                    NewsCollector(news, EquationRest(), news_push.enqueue if news_push else None).start(stop)
                    if news_push is not None:
                        Thread(target=news_push.run, args=(stop,), name="news-fcm-send", daemon=True).start()
                    LOGGER.info("方程式新闻采集已启用，新闻推送%s", "已启用" if news_push else "未启用")
                if api.push.available:
                    Thread(target=api.push.run, args=(stop,), name="fcm-send", daemon=True).start()
                worker.start()
                LOGGER.info(
                    "监听 %s://%s:%s%s（行情源 %s，FCM %s）", "https" if tls else "http", args.host, args.port, PREFIX,
                    source, "已配置" if api.push.available else "未配置",
                )
                server.serve_forever()
            finally:
                stop.set()
    except KeyboardInterrupt:
        LOGGER.info("API 已停止")
    except (OSError, ValueError, sqlite3.Error) as error:
        print(error, file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
