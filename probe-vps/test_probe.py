import gzip
import io
import unittest
from decimal import Decimal
from unittest.mock import patch

from binance import BinanceClient, MarketDataError
from probe import Probe


class StubClient:
    def mark_prices(self, symbols):
        return {symbol: Decimal("100") for symbol in symbols}

    def is_trading(self, symbol):
        return symbol == "BTCUSDT"


class MonitorTests(unittest.TestCase):
    def setUp(self):
        self.probe = Probe(StubClient())
        self.probe.command("follow BTC futures:BTCUSDT 比特币 加密货币")
        self.monitor = self.probe.monitor

    def check_prices(self, price, seconds):
        return self.monitor.check_prices({"BTCUSDT": Decimal(price)}, seconds)

    def test_follow_metadata_and_cancel_leave_existing_rules_active(self):
        self.probe.command("follow update BTC Bitcoin Perpetual 主流币")
        self.assertIn("Bitcoin Perpetual / BTCUSDT", self.probe.command("list follow")[0])
        self.probe.command("btc 110")
        self.probe.command("add updown btc 10u")
        self.probe.command("delete follow BTC")
        self.assertEqual(self.monitor.symbols(), {"BTCUSDT"})

        self.probe.command("delete alerts 1")
        self.assertEqual(self.monitor.symbols(), {"BTCUSDT"})
        self.probe.command("delete updowns 2")
        self.assertEqual(self.monitor.symbols(), set())

    def test_batch_target_directions_equal_price_duplicates_and_one_shot(self):
        replies = self.probe.command("btc 90 110 100 110")
        self.assertIn("跌到 90", replies[0])
        self.assertIn("涨到 110", replies[1])
        self.assertIn("未创建", replies[2])
        self.assertIn("已存在", replies[3])
        self.assertEqual(len(self.monitor.alerts), 2)

        self.assertEqual(self.monitor.check_prices({}, 0), [])
        self.assertIn("涨到 110", self.check_prices("110", 5)[0])
        self.assertEqual(self.check_prices("111", 10), [])
        self.assertIn("跌到 90", self.check_prices("90", 15)[0])
        self.assertEqual(self.probe.command("list alerts"), ["没有待触发的目标价提醒"])

    def test_percentage_window_warms_up_uses_nearest_baseline_and_cools_by_direction(self):
        self.probe.command("add updown btc 1 5%")
        self.assertEqual(self.check_prices("100", 0), [])
        self.assertEqual(self.check_prices("200", 59), [])
        self.assertIn("上涨 5%", self.check_prices("105", 60)[0])
        self.assertEqual(self.check_prices("106", 61), [])
        self.assertIn("下跌 5%", self.check_prices("95", 62)[0])
        self.assertEqual(self.check_prices("112", 119), [])
        self.assertIn("基准 105", self.check_prices("112", 120)[0])

    def test_absolute_window_uses_price_distance(self):
        self.probe.command("add updown btc 1 25u")
        self.check_prices("200", 0)
        self.assertEqual(self.check_prices("224.99", 60), [])
        self.assertIn("上涨 25u", self.check_prices("225", 65)[0])

    def test_ladder_reports_last_crossed_level_and_has_shared_cooldown(self):
        self.probe.command("add updown btc 5u")
        self.assertEqual(self.check_prices("64", 0), [])
        self.assertIn("涨过 75", self.check_prices("76", 5)[0])
        self.assertEqual(self.check_prices("64", 10), [])
        self.assertEqual(self.monitor.check_prices({}, 15), [])
        self.assertIn("跌过 60", self.check_prices("60", 2705)[0])

    def test_poll_recovers_from_a_failed_request(self):
        self.probe.command("btc 110")
        with patch.object(self.probe.client, "mark_prices", side_effect=MarketDataError("测试超时")):
            with self.assertLogs("probe", level="WARNING"):
                self.probe.poll_once()
        self.assertEqual(len(self.monitor.alerts), 1)
        with patch.object(self.probe.client, "mark_prices", return_value={"BTCUSDT": Decimal("111")}):
            with self.assertLogs("probe", level="INFO") as logs:
                self.probe.poll_once()
        self.assertIn("[目标价提醒]", logs.output[0])
        self.assertFalse(self.monitor.alerts)

    def test_invalid_and_duplicate_inputs_leave_rules_intact(self):
        self.probe.command("add updown btc 1 1%")
        for command in ("btc 90 NaN", "add updown btc 0 1%", "add updown btc 1%", "add updown BTC 1 1.0%"):
            with self.subTest(command=command), self.assertRaises(ValueError):
                self.probe.command(command)
        self.assertFalse(self.monitor.alerts)
        self.assertEqual(len(self.monitor.updowns), 1)


class BinanceClientTests(unittest.TestCase):
    def test_single_symbol_uses_filtered_request_and_decodes_gzip(self):
        body = gzip.compress(b'{"symbol":"BTCUSDT","markPrice":"100.25"}')
        response = io.BytesIO(body)
        response.headers = {"Content-Encoding": "gzip"}
        with patch("binance.urlopen", return_value=response) as request:
            prices = BinanceClient().mark_prices({"BTCUSDT"})
        self.assertEqual(prices, {"BTCUSDT": Decimal("100.25")})
        self.assertTrue(request.call_args.args[0].full_url.endswith("/fapi/v1/premiumIndex?symbol=BTCUSDT"))

    def test_batch_uses_one_request_and_skips_bad_or_unwatched_quotes(self):
        response = io.BytesIO(b"""[
            {"symbol":"BTCUSDT","markPrice":"100"},
            {"symbol":"ETHUSDT","markPrice":"NaN"},
            {"symbol":"SOLUSDT","markPrice":"90"}
        ]""")
        response.headers = {}
        with patch("binance.urlopen", return_value=response) as request:
            prices = BinanceClient().mark_prices({"BTCUSDT", "ETHUSDT"})
        self.assertEqual(prices, {"BTCUSDT": Decimal("100")})
        request.assert_called_once()
        self.assertTrue(request.call_args.args[0].full_url.endswith("/fapi/v1/premiumIndex"))


if __name__ == "__main__":
    unittest.main()
