from __future__ import annotations

import argparse
import logging
import math
import os
import re
import select
import sys
import time
from decimal import Decimal, InvalidOperation
from pathlib import Path
from threading import Lock

from binance import DEFAULT_BASE_URL, BinanceClient, MarketDataError
from monitor import AlertEvent, FollowedContract, Monitor, TargetRegistration, Updown, format_amount, utc_timestamp


LOGGER = logging.getLogger(__name__)
RESERVED_ALIASES = {"add", "delete", "exit", "follow", "help", "list", "update"}
HELP = """\
follow btc futures:BTCUSDT 比特币 加密货币   添加关注
follow update btc 比特币 主流币            修改名称和类别
list follow                              查看关注
delete follow btc                        取消关注
btc 62000 65000                          创建一个或多个目标价提醒
list alerts                              查看目标价提醒
delete alerts <ID>                       删除目标价提醒
add updown btc 15 1%                     15 分钟内涨跌 1%
add updown btc 15 2000u                  15 分钟内涨跌 2000u
add updown btc 1000u                     每跨过整数倍 1000u 的阶梯价位
list updowns                             查看窗口和阶梯监控
delete updowns <ID>                      删除窗口或阶梯监控
help                                     查看命令
exit                                     退出程序"""


class ContractNotTrading(ValueError):
    pass


class DuplicateRule(ValueError):
    pass


def normalize_alias(value: str) -> str:
    alias = value.lower()
    if alias in RESERVED_ALIASES or re.fullmatch(r"[a-z][a-z0-9_-]*", alias) is None:
        raise ValueError("别名需以英文字母开头，可包含字母、数字、连字符和下划线，且不能与命令重名")
    return alias


def normalize_symbol(symbol: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_]+", symbol) is None:
        raise ValueError("请输入币安 U 本位合约代码，例如 BTCUSDT")
    return symbol.upper()


def positive_amount(text: str) -> Decimal:
    try:
        amount = Decimal(text)
    except InvalidOperation:
        raise ValueError(f"请输入有效的正数（收到 {text!r}）") from None
    if not amount.is_finite() or not 0 < float(amount) < math.inf:
        raise ValueError(f"请输入有效的正数（收到 {text!r}）")
    return amount


def positive_minutes(text: str | int) -> int:
    try:
        minutes = int(text)
        seconds = float(minutes * 60)
    except (ValueError, OverflowError):
        raise ValueError("监控窗口需要填写大于 0 的整数分钟") from None
    if not 0 < seconds < math.inf:
        raise ValueError("监控窗口需要填写大于 0 的整数分钟")
    return minutes


class Probe:
    def __init__(self, client: BinanceClient, show_prices: bool = False):
        self.client = client
        self.monitor = Monitor()
        self.show_prices = show_prices
        self.state_lock = Lock()
        # Serialize quote reads with rule registration so older polls cannot trigger newly registered rules.
        self._market_lock = Lock()
        self._missing_symbols: set[str] = set()
        self._last_polled_symbols: set[str] = set()
        self._last_check: str | None = None
        self._last_success: str | None = None
        self._last_error: str | None = None

    def poll_once(self) -> list[AlertEvent]:
        with self._market_lock:
            with self.state_lock:
                symbols = self.monitor.symbols()
                if not symbols:
                    self._missing_symbols.clear()
                    self._last_polled_symbols.clear()
                    self._last_check = None
                    self._last_error = None
                    return []

            try:
                prices = self.client.mark_prices(symbols)
            except MarketDataError as error:
                with self.state_lock:
                    self._missing_symbols = symbols
                    self._last_polled_symbols = symbols
                    self._last_check = utc_timestamp()
                    self._last_error = str(error)
                LOGGER.warning("%s，下轮继续取价", error)
                return []

            with self.state_lock:
                missing = symbols - prices.keys()
                newly_missing = missing - self._missing_symbols
                self._missing_symbols = missing
                self._last_polled_symbols = symbols
                self._last_check = utc_timestamp()
                if prices:
                    self._last_success = self._last_check
                self._last_error = None
                events = self.monitor.evaluate_prices(prices, time.monotonic())

        if newly_missing:
            LOGGER.warning("本轮缺少 %s 的标记价", ", ".join(sorted(missing)))
        for event in events:
            LOGGER.info("%s", event.message)
        if self.show_prices:
            for symbol, price in sorted(prices.items()):
                LOGGER.info("%s 标记价 %s", symbol, format_amount(price))
        return events

    def market_status(self) -> dict:
        with self.state_lock:
            symbols = self.monitor.symbols()
            missing = symbols & self._missing_symbols
            if not symbols:
                state = "idle"
            elif not symbols <= self._last_polled_symbols:
                state = "waiting"
            elif self._last_error is not None or missing == symbols:
                state = "unavailable"
            elif missing:
                state = "partial"
            else:
                state = "ok"
            return {
                "state": state,
                "symbols": sorted(symbols),
                "missing_symbols": sorted(missing),
                "last_poll_at": self._last_check,
                "last_success_at": self._last_success,
                "last_error": self._last_error,
            }

    def register_targets(
        self, contract: FollowedContract, targets: list[Decimal], device_id: str | None = None,
    ) -> list[TargetRegistration]:
        with self._market_lock:
            with self.state_lock:
                duplicates = [self.monitor.target_rule(contract.symbol, target, device_id) for target in targets]
                if all(rule is not None for rule in duplicates):
                    return [TargetRegistration(target, "duplicate", rule) for target, rule in zip(targets, duplicates)]
            self._check_trading(contract.symbol)
            reference = self.client.mark_prices({contract.symbol}).get(contract.symbol)
            if reference is None:
                raise MarketDataError(f"未获取到 {contract.symbol} 的标记价，请稍后重试")
            with self.state_lock:
                return self.monitor.register_targets(contract, targets, reference, device_id)

    def register_updown(
        self, contract: FollowedContract, minutes: int, amount: Decimal, unit: str, device_id: str | None = None,
    ) -> Updown:
        with self._market_lock:
            with self.state_lock:
                if self.monitor.has_updown(contract.symbol, minutes, amount, unit, device_id):
                    raise DuplicateRule("这条涨跌监控已存在")
            self._check_trading(contract.symbol)
            with self.state_lock:
                return self.monitor.add_updown(contract, minutes, amount, unit, device_id)

    def command(self, line: str) -> list[str]:
        parts = line.split()
        if not parts or parts[0].startswith("#"):
            return []

        action, args = parts[0].lower(), parts[1:]
        if action == "help" and not args:
            return [HELP]
        if action == "follow":
            return self._follow(args)
        if action == "list":
            return self._list(args)
        if action == "delete":
            return self._delete(args)
        if action == "add":
            return self._updown(args)
        if not args:
            raise ValueError("输入 help 查看命令")

        contract = self.monitor.contract(action)
        targets = [positive_amount(value) for value in args]
        return [registration.message() for registration in self.register_targets(contract, targets)]

    def _follow(self, args: list[str]) -> list[str]:
        if len(args) < 4:
            raise ValueError("用法为 follow <别名> futures:<合约> <名称> <类别>")
        name, category = " ".join(args[2:-1]), args[-1]
        if args[0].lower() == "update":
            asset = self.monitor.update_follow(args[1], name, category)
            return [f"已更新 {asset.label}  {asset.category}"]

        alias = normalize_alias(args[0])
        market, _, symbol = args[1].partition(":")
        if market.lower() != "futures":
            raise ValueError("请输入 U 本位合约，例如 futures:BTCUSDT")
        asset = self.monitor.follow(alias, normalize_symbol(symbol), name, category)
        return [f"已关注 {asset.label}  {asset.category}"]

    def _list(self, args: list[str]) -> list[str]:
        group = args[0].lower() if args else "alerts"
        if len(args) <= 1:
            if group == "follow":
                assets = sorted(self.monitor.follows.values(), key=lambda item: (item.category, item.alias))
                return [f"{asset.label}  {asset.category}" for asset in assets] or ["关注清单为空"]
            if group == "alerts":
                alerts = sorted(self.monitor.alerts.values(), key=lambda item: (item.contract.alias, -item.target))
                return [alert.describe() for alert in alerts] or ["没有待触发的目标价提醒"]
            if group == "updowns":
                rules = sorted(self.monitor.updowns.values(), key=lambda item: (item.contract.alias, item.minutes))
                return [rule.describe() for rule in rules] or ["没有窗口或阶梯监控"]
        raise ValueError("用法为 list follow、list alerts 或 list updowns")

    def _delete(self, args: list[str]) -> list[str]:
        if len(args) == 2:
            group, identifier = args[0].lower(), args[1]
            if group == "follow":
                self.monitor.delete_follow(identifier)
                return [f"已取消关注 {identifier}"]
            if group == "alerts":
                self.monitor.delete_alert(identifier)
                return [f"已删除目标价提醒 {identifier}"]
            if group == "updowns":
                self.monitor.delete_updown(identifier)
                return [f"已删除涨跌监控 {identifier}"]
        raise ValueError("用法为 delete follow <别名>、delete alerts <ID> 或 delete updowns <ID>")

    def _updown(self, args: list[str]) -> list[str]:
        if len(args) not in {3, 4} or args[0].lower() != "updown":
            raise ValueError("用法为 add updown <别名> <分钟> <涨跌阈值> 或 add updown <别名> <阶梯>u")
        contract = self.monitor.contract(args[1])
        minutes = positive_minutes(args[2]) if len(args) == 4 else 0
        text = args[-1].lower()
        unit = text[-1]
        if unit not in {"u", "%"} or (minutes == 0 and unit != "u"):
            raise ValueError("窗口阈值需要用 % 或 u 结尾，阶梯价位需要用 u 结尾")
        amount = positive_amount(text[:-1])
        rule = self.register_updown(contract, minutes, amount, unit)
        return [f"已创建涨跌监控 {rule.describe()}"]

    def _check_trading(self, symbol: str) -> None:
        if not self.client.is_trading(symbol):
            raise ContractNotTrading(f"{symbol} 当前不在行情源的可监控合约列表中")


def _read_startup_commands(path: Path, probe: Probe) -> None:
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        try:
            for reply in probe.command(line):
                print(reply, flush=True)
        except (MarketDataError, ValueError) as error:
            raise ValueError(f"{path} 第 {number} 行，{error}") from error


def _run(probe: Probe, interval: float, interactive: bool) -> None:
    next_poll = time.monotonic()
    while True:
        remaining = next_poll - time.monotonic()
        if remaining <= 0:
            probe.poll_once()
            next_poll = time.monotonic() + interval
            continue
        if not interactive:
            time.sleep(remaining)
            continue
        if not select.select([sys.stdin], [], [], remaining)[0]:
            continue

        line = sys.stdin.readline()
        if not line or line.strip().lower() == "exit":
            return
        try:
            for reply in probe.command(line):
                print(reply, flush=True)
        except (MarketDataError, ValueError) as error:
            print(error, file=sys.stderr, flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="监控币安 U 本位合约标记价并在终端输出提醒")
    parser.add_argument("--interval", type=float, default=5, metavar="SECONDS", help="每轮结束后的等待秒数，默认 5")
    parser.add_argument("--show-prices", action="store_true", help="在终端显示每轮取到的标记价")
    parser.add_argument("--commands", type=Path, metavar="FILE", help="启动时执行 UTF-8 命令文件，然后持续监控")
    parser.add_argument(
        "--base-url",
        default=os.environ.get("BINANCE_FUTURES_BASE_URL", DEFAULT_BASE_URL),
        help="币安 U 本位 REST 接口地址，也可通过 BINANCE_FUTURES_BASE_URL 设置",
    )
    args = parser.parse_args(argv)
    if not math.isfinite(args.interval) or args.interval <= 0:
        parser.error("--interval 需要大于 0")
    if args.commands is None and not sys.stdin.isatty():
        parser.error("请在终端交互运行，或通过 --commands 提供启动命令文件")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    try:
        probe = Probe(BinanceClient(args.base_url), args.show_prices)
        if args.commands is not None:
            _read_startup_commands(args.commands, probe)
            if not probe.monitor.symbols():
                raise ValueError("启动文件需要添加至少一个关注合约")
        else:
            print("输入 help 查看命令，输入 exit 或按 Ctrl+C 退出。", flush=True)
        LOGGER.info("开始监控币安 U 本位标记价，每轮结束等待 %g 秒", args.interval)
        _run(probe, args.interval, interactive=args.commands is None)
    except KeyboardInterrupt:
        LOGGER.info("监控已停止")
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
