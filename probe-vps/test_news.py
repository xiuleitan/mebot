import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread
from types import SimpleNamespace
from unittest.mock import Mock

from news import NewsCollector, NewsFetchError, NewsItem, NewsPage, NewsStore, _timestamp
from push import DeviceRegistry, FcmSender, NewsPushDispatcher


class NewsCollectorTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "news.sqlite3"
        self.store = NewsStore(self.path)
        self.rest = Mock()
        self.publish = Mock()
        self.collector = NewsCollector(self.store, self.rest, self.publish)
        self.stop = Event()
        self.now = datetime.now(timezone.utc)

    def row(self, number, seconds_ago, channel="main", **fields):
        return {
            "no": number, "channels": [channel], "time": _timestamp(self.now - timedelta(seconds=seconds_ago)),
            "zh": "新闻标题\n第二行", "url": f"https://eqn.news/{channel}/{number}", **fields,
        }

    def test_main_initial_history_and_announcements_are_quiet(self):
        self.rest.page.return_value = NewsPage([self.row(1, 100), self.row(2, 30, channels=["main", "binance"])])
        self.collector.refresh("main", self.stop)
        self.publish.assert_not_called()
        self.assertEqual([item.news_id for item in self.store.recent(100)], ["eqn:1"])
        self.assertEqual(self.store.recent(1)[0].title, "新闻标题")

    def test_ws_reconnect_archives_gap_and_reuses_persisted_ids(self):
        self.rest.page.return_value = NewsPage([self.row(10, 150)])
        self.collector.refresh("main", self.stop)
        restarted = NewsCollector(NewsStore(self.path), self.rest, self.publish)
        restarted._ws_since = self.now - timedelta(seconds=15)
        self.rest.page.return_value = NewsPage([self.row(12, 4), self.row(11, 60), self.row(10, 150)], etag='"v2"')
        restarted.refresh("main", self.stop)
        self.assertEqual([item.news_id for item in self.store.recent(100)], ["eqn:12", "eqn:11", "eqn:10"])
        self.publish.assert_called_once_with(self.store.recent(1)[0])
        restarted.refresh("main", self.stop)
        self.publish.assert_called_once()
        self.rest.page.assert_called_with("main", self.stop, etag='"v2"')

    def test_live_item_is_available_before_historical_retry_completes(self):
        self.store.record("main", self.now - timedelta(minutes=12), [])
        self.collector._ws_since = self.now - timedelta(seconds=25)
        first = NewsPage([self.row(9, 2)], next_cursor="older", etag='"latest"')
        self.rest.page.side_effect = [first, NewsFetchError("timeout")]
        with self.assertRaises(NewsFetchError):
            self.collector.refresh("main", self.stop)
        self.publish.assert_called_once_with(self.store.recent(1)[0])
        self.assertLess(self.store.checkpoint("main"), self.now - timedelta(minutes=10))
        self.rest.page.side_effect = [first, NewsPage([self.row(8, 900)])]
        self.collector.refresh("main", self.stop)
        self.publish.assert_called_once()
        self.rest.page.assert_called_with("main", self.stop, before="older")
        self.assertEqual([item.news_id for item in self.store.recent(100)], ["eqn:9", "eqn:8"])

    def test_tradfi_only_publishes_fresh_additions(self):
        self.rest.page.return_value = NewsPage([self.row(20, 900, "tradfi")])
        self.collector.refresh("tradfi", self.stop)
        self.rest.page.return_value = NewsPage([
            self.row(22, 2, "tradfi", zh="", en="TradFi headline", url="http://example.com/insecure"),
            self.row(21, 400, "tradfi"), self.row(20, 900, "tradfi"),
        ])
        self.collector.refresh("tradfi", self.stop)
        current = self.store.recent(1)[0]
        self.publish.assert_called_once_with(current)
        self.assertEqual(current.news_id, "eqn:22")
        self.assertEqual(current.title, "TradFi headline")
        self.assertNotIn("url", current.payload())
        self.assertEqual(len(self.store.recent(100)), 3)

    def test_websocket_text_heartbeat_and_news_trigger(self):
        websocket = Mock()
        websocket.recv.side_effect = ["pong", json.dumps({"news_title": "触发 REST 查询"})]
        stop = Mock()
        stop.is_set.side_effect = [False, False, True]
        self.collector._listen(websocket, stop)
        websocket.send.assert_called_once_with("ping")
        self.assertTrue(self.collector._main_signal.is_set())


class NewsPushTests(unittest.TestCase):
    def test_distinct_news_priorities_deliver_same_ids_as_history(self):
        posts = []
        received = Event()

        def capture(endpoint, body):
            posts.append(body["message"])
            if len(posts) == 2:
                received.set()
            return SimpleNamespace(status_code=200)

        sender = FcmSender("test-project", capture)
        devices = DeviceRegistry()
        devices.register("mebot-installation", "current-test-token")
        dispatcher = NewsPushDispatcher(sender, devices, "news-instance")
        now = _timestamp(datetime.now(timezone.utc))
        main = NewsItem("eqn:45", "main", "方程式新闻", now, "https://eqn.news/main/45")
        tradfi = NewsItem("eqn:46", "tradfi", "方程式财经", now)
        dispatcher.enqueue(tradfi)
        dispatcher.enqueue(main)
        stop = Event()
        worker = Thread(target=dispatcher.run, args=(stop,), daemon=True)
        worker.start()
        try:
            self.assertTrue(received.wait(3))
        finally:
            stop.set()
            worker.join(2)
        for message, item, priority in zip(posts, (main, tradfi), ("high", "normal")):
            self.assertEqual(message["token"], "current-test-token")
            self.assertEqual(message["android"]["priority"], priority)
            self.assertEqual(message["android"]["ttl"], "300s")
            self.assertNotIn("notification", message)
            self.assertEqual(message["data"], {
                "type": "trading_news", "instance_id": "news-instance", "device_id": "mebot-installation",
                **item.payload(),
            })
            self.assertTrue(all(isinstance(value, str) for value in message["data"].values()))


if __name__ == "__main__":
    unittest.main()
