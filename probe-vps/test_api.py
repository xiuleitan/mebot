import io
import json
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event
from types import SimpleNamespace
from unittest.mock import Mock, patch

from api import Api, RequestHandler, _poll
from binance import MarketDataError
from news import NewsItem, NewsStore
from probe import Probe
from push import FcmSender, PACKAGE_NAME, PushDispatcher, PushError


PHONE_A = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
PHONE_B = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
CHECK_A = "cccccccc-cccc-4ccc-cccc-cccccccccccc"
CHECK_B = "dddddddd-dddd-4ddd-dddd-dddddddddddd"


class PriceClient:
    def __init__(self):
        self.prices = {"BTCUSDT": Decimal("100"), "ETHUSDT": Decimal("200")}

    def is_trading(self, symbol):
        return symbol in self.prices

    def mark_prices(self, symbols):
        return {symbol: self.prices[symbol] for symbol in symbols & self.prices.keys()}


class MemoryConnection:
    def __init__(self, request):
        self.request = request
        self.reply = bytearray()

    def makefile(self, mode, buffering):
        return io.BytesIO(self.request)

    def sendall(self, data):
        self.reply.extend(data)


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.client = PriceClient()
        self.probe = Probe(self.client)
        self.api = Api(self.probe, 5, "price_file", "test-token")

    def request(self, method, path, data=None, *, token="test-token", key=None):
        headers = [f"{method} /api/v1/{path} HTTP/1.0"]
        if token is not None:
            headers.append(f"Authorization: Bearer {token}")
        if key is not None:
            headers.append(f"Idempotency-Key: {key}")
        body = b""
        if data is not None:
            body = json.dumps(data).encode()
            headers.extend(["Content-Type: application/json", f"Content-Length: {len(body)}"])
        connection = MemoryConnection("\r\n".join(headers).encode() + b"\r\n\r\n" + body)
        RequestHandler(connection, ("127.0.0.1", 8080), SimpleNamespace(api=self.api))
        head, _, payload = connection.reply.partition(b"\r\n\r\n")
        status = int(head.split()[1])
        return status, json.loads(payload) if payload else None

    def poll(self, seconds):
        with patch("probe.time.monotonic", return_value=seconds):
            return self.probe.poll_once()

    def register(self, device_id, token):
        return self.request("PUT", "devices/" + device_id, {"package_name": PACKAGE_NAME, "fcm_token": token})

    def test_health_and_bearer_authentication(self):
        status, body = self.request("GET", "health", token=None)
        self.assertEqual((status, body["error"]["code"]), (401, "unauthorized"))
        status, body = self.request("GET", "health")
        self.assertEqual(status, 200)
        self.assertEqual(body["market"]["state"], "idle")
        self.assertEqual(body["market"]["source"], "price_file")

    def test_news_history_requires_auth_and_returns_latest_public_fields(self):
        with TemporaryDirectory() as path:
            self.api.news = NewsStore(Path(path) / "news.sqlite3")
            later = NewsItem("eqn:2", "tradfi", "财经快讯", "2026-11-24T09:01:00.000Z")
            earlier = NewsItem("eqn:1", "main", "主频道快讯", "2026-11-24T09:00:00.000Z", "https://eqn.news/main/1")
            self.api.news.record("main", datetime.now(timezone.utc), [earlier, later])
            self.assertEqual(self.request("GET", "news", token=None)[0], 401)
            status, page = self.request("GET", "news?limit=1")
            self.assertEqual(status, 200)
            self.assertEqual(page, {"instance_id": self.api.instance_id, "items": [later.payload()]})
            self.assertEqual(self.request("GET", "news?limit=100")[1]["items"], [later.payload(), earlier.payload()])
            self.assertEqual(self.request("GET", "news?limit=101")[0], 400)

    def test_news_registration_reports_its_own_delivery_switch(self):
        body = {"fcm_token": "fake-token", "package_name": PACKAGE_NAME}
        path = "news/devices/" + PHONE_A
        self.assertEqual(self.request("PUT", path, body)[0], 404)
        with TemporaryDirectory() as temporary:
            self.api.news = NewsStore(Path(temporary) / "news.sqlite3")
            self.api.push = PushDispatcher(Mock())
            self.assertEqual(self.request("PUT", path, body, token=None)[0], 401)
            self.assertEqual(self.request("PUT", path, body), (200, {"registered": True, "push_available": False}))
            self.assertEqual(self.api.news_devices.token_for(PHONE_A), "fake-token")
            self.assertFalse(self.api.push.is_registered(PHONE_A))
            self.assertTrue(self.request("PUT", "devices/" + PHONE_A, body)[1]["push_available"])
            self.api.news_push_enabled = True
            self.assertEqual(self.request("PUT", path, body), (200, {"registered": True, "push_available": True}))

    def _configure_push_check(self, sender):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        news = NewsStore(Path(directory.name) / "news.sqlite3")
        self.api = Api(self.probe, 5, "price_file", "test-token", news=news,
                       news_push_enabled=True, news_check_sender=sender)

    def test_push_check_uses_only_registered_news_token_and_returns_accepted(self):
        sender = Mock()
        self._configure_push_check(sender)
        path = "news/push-check"
        data = {"device_id": PHONE_A, "check_id": CHECK_A}
        self.assertEqual(self.request("POST", path, data, token=None)[0], 401)
        self.assertEqual(self.request("POST", path, {**data, "fcm_token": "arbitrary-token"})[0], 400)
        self.assertEqual(self.request("POST", path, {**data, "check_id": CHECK_A.upper()})[0], 400)
        self.assertEqual(self.request("POST", path, {**data, "device_id": PHONE_A + " "})[0], 400)
        self.register(PHONE_A, "price-token")
        status, body = self.request("POST", path, data)
        self.assertEqual((status, body["error"]["code"]), (409, "device_not_registered"))
        sender.send.assert_not_called()

        self.assertEqual(self.request("PUT", "news/devices/" + PHONE_A, {
            "fcm_token": "registered-news-token", "package_name": PACKAGE_NAME,
        })[0], 200)
        self.assertEqual(self.request("POST", path, data), (200, {"accepted": True}))
        sender.send.assert_called_once_with("registered-news-token", {"type": "trading_push_check", **data})
        self.assertEqual(self.request("GET", "news")[1]["items"], [])

    def test_push_check_send_failure_and_rate_rejection_do_not_advance_window(self):
        sender = Mock()
        self._configure_push_check(sender)
        self.api.news_devices.register(PHONE_A, "registered-news-token")
        path = "news/push-check"
        data = {"device_id": PHONE_A, "check_id": CHECK_A}
        sender.send.side_effect = [PushError("FCM HTTP 503"), None, None]
        with patch("api.time.monotonic", return_value=100):
            status, body = self.request("POST", path, data)
            self.assertEqual((status, body["error"]["code"]), (503, "push_unavailable"))
            self.assertEqual(self.request("POST", path, data), (200, {"accepted": True}))
        with patch("api.time.monotonic", return_value=107):
            self.assertEqual(self.request("POST", path, {**data, "check_id": CHECK_B})[0], 429)
        with patch("api.time.monotonic", return_value=115):
            self.assertEqual(self.request("POST", path, {**data, "check_id": CHECK_B}), (200, {"accepted": True}))
        self.assertEqual(sender.send.call_count, 3)

    def test_push_check_rejects_concurrent_check_for_same_device(self):
        sender = Mock()
        self._configure_push_check(sender)
        self.api.news_devices.register(PHONE_A, "registered-news-token")
        started, release = Event(), Event()

        def send(*args):
            started.set()
            self.assertTrue(release.wait(3))

        sender.send.side_effect = send
        with ThreadPoolExecutor(max_workers=2) as workers:
            try:
                pending = workers.submit(self.request, "POST", "news/push-check", {"device_id": PHONE_A, "check_id": CHECK_A})
                self.assertTrue(started.wait(2))
                status, _ = self.request("POST", "news/push-check", {"device_id": PHONE_A, "check_id": CHECK_B})
                self.assertEqual(status, 429)
            finally:
                release.set()
            self.assertEqual(pending.result(timeout=2), (200, {"accepted": True}))
        sender.send.assert_called_once()

    def test_follow_management_keeps_rules_alive(self):
        status, follow = self.request("POST", "follows", {"alias": "BTC", "symbol": "btcusdt", "name": "比特币"})
        self.assertEqual((status, follow["alias"], follow["symbol"]), (201, "btc", "BTCUSDT"))
        self.assertEqual(self.request("GET", "health")[1]["market"]["state"], "waiting")
        self.poll(0)
        self.assertEqual(self.request("GET", "health")[1]["market"]["state"], "ok")
        status, updated = self.request("PATCH", "follows/btc", {"category": "关注中"})
        self.assertEqual((status, updated["category"], updated["name"]), (200, "关注中", "比特币"))
        self.assertEqual(self.request("GET", "follows")[1]["items"], [updated])
        status, _ = self.request("POST", "alerts", {"alias": "BTC", "target_price": "110"})
        self.assertEqual(status, 201)
        self.assertEqual(self.request("DELETE", "follows/btc"), (204, None))
        self.assertEqual(self.request("GET", "follows")[1]["items"], [])
        self.assertEqual(self.request("GET", "health")[1]["market"]["symbols"], ["BTCUSDT"])

    def test_target_trigger_event_and_idempotent_retry_after_trigger(self):
        data = {"symbol": "BTCUSDT", "target_price": "110"}
        status, response = self.request("POST", "alerts", data, key="target-test-1")
        self.assertEqual(status, 201)
        rule = response["items"][0]["rule"]
        self.assertEqual((rule["direction"], rule["reference_price"]), ("up", "100"))
        self.client.prices["BTCUSDT"] = Decimal("110")
        self.poll(1)
        self.assertEqual(self.request("GET", "alerts")[1]["items"], [])

        status, events = self.request("GET", "events?after_id=0")
        self.assertEqual(status, 200)
        event = events["items"][0]
        self.assertEqual((event["kind"], event["mark_price"], event["rule_id"]), ("target", "110", rule["id"]))
        self.assertEqual(self.request("GET", f"events?after_id={event['id']}")[1]["items"], [])
        self.assertEqual(self.request("POST", "alerts", data, key="target-test-1"), (201, response))
        self.assertEqual(self.request("GET", "alerts")[1]["items"], [])
        status, body = self.request("POST", "alerts", {**data, "target_price": "120"}, key="target-test-1")
        self.assertEqual((status, body["error"]["code"]), (409, "idempotency_conflict"))

    def test_batch_targets_describe_equal_duplicate_and_created(self):
        data = {"symbol": "BTCUSDT", "target_prices": ["95", "100", "95", "120"]}
        status, response = self.request("POST", "alerts", data)
        self.assertEqual(status, 201)
        items = response["items"]
        self.assertEqual([item["status"] for item in items], ["created", "at_target", "duplicate", "created"])
        self.assertEqual(items[0]["rule"]["direction"], "down")
        self.assertIsNone(items[1]["rule"])
        self.assertEqual(items[0]["rule"]["id"], items[2]["rule"]["id"])
        self.assertEqual(self.request("DELETE", "alerts/" + items[0]["rule"]["id"]), (204, None))

    def test_window_and_ladder_events_have_structured_metadata(self):
        status, window = self.request("POST", "updowns", {
            "symbol": "BTCUSDT", "mode": "window", "window_minutes": 1, "threshold": "5", "unit": "percent",
        })
        self.assertEqual((status, window["window_minutes"]), (201, 1))
        data = {"symbol": "BTCUSDT", "mode": "ladder", "threshold": "10"}
        status, ladder = self.request("POST", "updowns", data)
        self.assertEqual((status, ladder["unit"], ladder["window_minutes"]), (201, "absolute", None))
        failure, response = self.request("POST", "updowns", data)
        self.assertEqual((failure, response["error"]["code"]), (409, "rule_exists"))
        self.poll(0)
        self.client.prices["BTCUSDT"] = Decimal("111")
        self.poll(60)
        self.assertEqual(self.request("GET", "updowns")[1]["items"], [window, ladder])
        events = self.request("GET", "events")[1]["items"]
        self.assertEqual([(event["kind"], event["rule_id"]) for event in events],
                         [("window", window["id"]), ("ladder", ladder["id"])])
        self.assertEqual(self.request("DELETE", "updowns/" + ladder["id"]), (204, None))

    def test_invalid_requests_and_provider_errors_leave_rules_unchanged(self):
        self.assertEqual(self.request("POST", "alerts", {"symbol": "MISSING", "target_price": "110"})[0], 422)
        self.assertEqual(self.request("POST", "alerts", {"symbol": "BTCUSDT", "target_price": 110})[0], 400)
        with patch.object(self.client, "mark_prices", side_effect=MarketDataError("测试超时")):
            status, response = self.request("POST", "alerts", {"symbol": "BTCUSDT", "target_price": "110"})
        self.assertEqual((status, response["error"]["code"]), (503, "market_unavailable"))
        self.assertEqual(self.request("GET", "alerts")[1]["items"], [])

    def test_concurrent_retries_create_one_rule(self):
        def submit(_):
            return self.request("POST", "alerts", {"symbol": "BTCUSDT", "target_price": "110"}, key="parallel")

        with ThreadPoolExecutor(max_workers=4) as workers:
            responses = list(workers.map(submit, range(8)))
        self.assertTrue(all(response == responses[0] and response[0] == 201 for response in responses))
        self.assertEqual(len(self.request("GET", "alerts")[1]["items"]), 1)

    def test_device_registration_and_rule_isolation_keep_legacy_behavior(self):
        data = {"symbol": "BTCUSDT", "target_price": "110", "device_id": PHONE_A}
        status, body = self.request("POST", "alerts", data)
        self.assertEqual((status, body["error"]["code"]), (409, "device_not_registered"))
        registration = {"fcm_token": "test-phone-a", "package_name": PACKAGE_NAME}
        self.assertEqual(self.request("PUT", "devices/" + PHONE_A, registration, token=None)[0], 401)
        self.assertEqual(self.request("PUT", "devices/invalid", registration)[0], 400)
        self.assertEqual(self.request("PUT", "devices/" + PHONE_A, {**registration, "package_name": "other.app"})[0], 400)
        self.assertEqual(self.register(PHONE_A.upper(), "test-phone-a"), (200, {"registered": True, "push_available": False}))
        self.assertEqual(self.register(PHONE_B, "test-phone-b")[0], 200)

        _, body = self.request("POST", "alerts", data, key="phone-a-alert")
        rule_a = body["items"][0]["rule"]
        status, body = self.request("POST", "alerts", {**data, "device_id": PHONE_B})
        rule_b = body["items"][0]["rule"]
        self.assertEqual(status, 201)
        self.assertNotEqual(rule_a["id"], rule_b["id"])
        _, body = self.request("POST", "alerts", {"symbol": "BTCUSDT", "target_price": "110"})
        legacy = body["items"][0]["rule"]
        self.assertNotIn("device_id", legacy)
        self.assertEqual(self.request("GET", "alerts?device_id=" + PHONE_A)[1]["items"], [rule_a])
        self.assertEqual(len(self.request("GET", "alerts")[1]["items"]), 3)
        self.assertEqual(self.request("DELETE", f"alerts/{rule_a['id']}?device_id={PHONE_B}")[0], 404)
        self.assertEqual(self.request("DELETE", f"alerts/{rule_b['id']}?device_id={PHONE_B}")[0], 204)

        self.client.prices["BTCUSDT"] = Decimal("110")
        self.poll(1)
        scoped = self.request("GET", "events?after_id=0&device_id=" + PHONE_A)[1]
        self.assertEqual([event["rule_id"] for event in scoped["items"]], [rule_a["id"]])
        self.assertEqual(scoped["items"][0]["device_id"], PHONE_A)
        self.assertEqual(self.request("GET", "events?after_id=0&device_id=" + PHONE_B)[1]["items"], [])
        status, body = self.request("POST", "alerts", data, key="phone-a-alert")
        self.assertEqual((status, body["items"][0]["rule"]), (201, rule_a))
        self.assertEqual(self.request("GET", "alerts")[1]["items"], [])

    def test_push_queue_uses_rotated_token_and_keeps_event_for_api(self):
        sender = Mock()
        self.api.push = PushDispatcher(sender)
        self.assertEqual(self.register(PHONE_A, "test-original-token")[1]["push_available"], True)
        self.register(PHONE_B, "test-unrelated-token")
        data = {"symbol": "BTCUSDT", "mode": "ladder", "threshold": "10", "device_id": PHONE_A}
        status, rule = self.request("POST", "updowns", data)
        self.assertEqual(status, 201)
        self.assertEqual(self.request("GET", "updowns?device_id=" + PHONE_A)[1]["items"], [rule])
        self.assertEqual(self.request("GET", "updowns?device_id=" + PHONE_B)[1]["items"], [])
        self.assertEqual(self.request("DELETE", f"updowns/{rule['id']}?device_id={PHONE_B}")[0], 404)
        self.poll(0)
        self.client.prices["BTCUSDT"] = Decimal("111")
        events = self.poll(5)
        self.assertEqual(len(events), 1)

        poll_stop = Mock()
        poll_stop.is_set.side_effect = [False, True]
        with patch.object(self.probe, "poll_once", return_value=events):
            _poll(self.api, poll_stop)
        sender.send.assert_not_called()
        self.register(PHONE_A, "test-updated-token")

        send_stop = Mock()
        send_stop.is_set.side_effect = [False, True]
        self.api.push.run(send_stop)
        event = self.request("GET", "events?device_id=" + PHONE_A)[1]["items"][0]
        sender.send.assert_called_once_with("test-updated-token", {
            "type": "trading_price_alert", "device_id": PHONE_A, "instance_id": self.api.instance_id,
            "event_id": str(event["id"]), "rule_id": rule["id"], "kind": "ladder", "symbol": "BTCUSDT",
            "mark_price": "111", "triggered_at": event["triggered_at"], "message": event["message"],
        })
        self.assertIsNotNone(self.request("GET", "health")[1]["push"]["last_accepted_at"])


class FcmSenderTests(unittest.TestCase):
    def test_posts_only_string_data_and_android_delivery_settings(self):
        post = Mock(return_value=SimpleNamespace(status_code=200))
        sender = FcmSender("probe-test-project", post)
        sender.send("test-registration-token", {"type": "trading_price_alert", "event_id": "1"})
        post.assert_called_once_with("https://fcm.googleapis.com/v1/projects/probe-test-project/messages:send", {
            "message": {
                "token": "test-registration-token",
                "data": {"type": "trading_price_alert", "event_id": "1"},
                "android": {"priority": "high", "ttl": "300s", "restricted_package_name": PACKAGE_NAME},
            },
        })

    def test_rejected_response_excludes_tokens_and_raw_error_body(self):
        response = Mock(status_code=403)
        response.json.return_value = {"error": {"status": "PERMISSION_DENIED", "message": "secret-token"}}
        sender = FcmSender("probe-test-project", Mock(return_value=response))
        with self.assertRaises(PushError) as result:
            sender.send("secret-token", {"event_id": "1"})
        self.assertEqual(str(result.exception), "FCM HTTP 403 (PERMISSION_DENIED)")

    def test_push_check_is_data_only_normal_priority_with_twenty_second_expiry(self):
        post = Mock(return_value=SimpleNamespace(status_code=200))
        sender = FcmSender("probe-test-project", post)
        data = {"type": "trading_push_check", "device_id": PHONE_A, "check_id": CHECK_A}
        sender.send("registered-news-token", data)
        post.assert_called_once_with("https://fcm.googleapis.com/v1/projects/probe-test-project/messages:send", {
            "message": {
                "token": "registered-news-token", "data": data,
                "android": {"priority": "normal", "ttl": "20s", "restricted_package_name": PACKAGE_NAME},
            },
        })


if __name__ == "__main__":
    unittest.main()
