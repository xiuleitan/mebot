from __future__ import annotations

from bisect import bisect_right
from collections import deque
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
from itertools import count


LEVEL_COOLDOWN_SECONDS = 45 * 60
EVENT_HISTORY_SIZE = 256


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def format_amount(amount: Decimal) -> str:
    if not -24 <= amount.adjusted() <= 24:
        return str(amount)
    text = format(amount, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


@dataclass(frozen=True)
class FollowedContract:
    alias: str
    symbol: str
    name: str
    category: str

    @property
    def label(self) -> str:
        if self.name == self.symbol and self.alias == self.symbol.lower():
            return self.symbol
        return f"{self.alias}（{self.name} / {self.symbol}）"


@dataclass(frozen=True)
class TargetAlert:
    id: str
    contract: FollowedContract
    target: Decimal
    reference: Decimal
    device_id: str | None = None

    @property
    def direction(self) -> str:
        return "涨到" if self.target > self.reference else "跌到"

    def is_triggered(self, price: Decimal) -> bool:
        if self.target > self.reference:
            return price >= self.target
        return price <= self.target

    def describe(self) -> str:
        return (
            f"{self.id}  {self.contract.label}  {self.direction} {format_amount(self.target)}"
            f"  参考价 {format_amount(self.reference)}"
        )


@dataclass(frozen=True)
class TargetRegistration:
    target: Decimal
    status: str
    rule: TargetAlert | None

    def message(self) -> str:
        if self.status == "duplicate":
            return f"{format_amount(self.target)} 的目标价提醒已存在"
        if self.rule is None:
            return f"标记价正好是 {format_amount(self.target)}，未创建提醒"
        return f"已创建目标价提醒 {self.rule.describe()}"


@dataclass
class Updown:
    id: str
    contract: FollowedContract
    minutes: int
    threshold: Decimal
    unit: str
    cooldown_until: dict[str, float] = field(default_factory=dict)
    device_id: str | None = None

    @property
    def mode(self) -> str:
        return "window" if self.minutes else "ladder"

    def describe(self) -> str:
        period = f"{self.minutes} 分钟" if self.minutes else "阶梯"
        return f"{self.id}  {self.contract.label}  {period}  {format_amount(self.threshold)}{self.unit}"

    def check_window(self, price: Decimal, baseline: Decimal | None, now: float) -> str | None:
        if baseline is None or price == baseline:
            return None

        change = price - baseline
        direction = "上涨" if change > 0 else "下跌"
        if now < self.cooldown_until.get(direction, now):
            return None

        if self.unit == "%":
            if abs(change) * 100 < baseline * self.threshold:
                return None
            percentage = abs(change) / baseline * 100
            magnitude = Decimal(f"{percentage:.6g}")
        else:
            magnitude = abs(change)
            if magnitude < self.threshold:
                return None

        self.cooldown_until[direction] = now + self.minutes * 60
        return (
            f"[窗口提醒 {self.id}] {self.contract.label} 过去 {self.minutes} 分钟"
            f"{direction} {format_amount(magnitude)}{self.unit}，基准 {format_amount(baseline)}，标记价 {format_amount(price)}"
        )

    def check_level(self, price: Decimal, previous: Decimal | None, now: float) -> str | None:
        if previous is None or price == previous or now < self.cooldown_until.get("level", now):
            return None

        if price > previous:
            rounding = ROUND_FLOOR
            direction = "涨过"
        else:
            rounding = ROUND_CEILING
            direction = "跌过"

        level = (price / self.threshold).to_integral_value(rounding=rounding) * self.threshold
        if not (previous < level <= price or price <= level < previous):
            return None

        self.cooldown_until["level"] = now + LEVEL_COOLDOWN_SECONDS
        return (
            f"[阶梯提醒 {self.id}] {self.contract.label} {direction} {format_amount(level)}"
            f"，标记价 {format_amount(price)}"
        )


@dataclass(frozen=True, order=True)
class PriceSample:
    time: float
    price: Decimal = field(compare=False)


@dataclass(frozen=True)
class AlertEvent:
    id: int
    kind: str
    rule_id: str
    symbol: str
    mark_price: Decimal
    triggered_at: str
    message: str
    device_id: str | None = None


class Monitor:
    def __init__(self):
        self.follows: dict[str, FollowedContract] = {}
        self.alerts: dict[str, TargetAlert] = {}
        self.updowns: dict[str, Updown] = {}
        self.events: deque[AlertEvent] = deque(maxlen=EVENT_HISTORY_SIZE)
        self._ids = count(1)
        self._event_ids = count(1)
        self._last_prices: dict[str, PriceSample] = {}
        self._window_prices: dict[str, list[PriceSample]] = {}

    def symbols(self) -> set[str]:
        return (
            {contract.symbol for contract in self.follows.values()}
            | {alert.contract.symbol for alert in self.alerts.values()}
            | {rule.contract.symbol for rule in self.updowns.values()}
        )

    def contract(self, alias: str) -> FollowedContract:
        contract = self.follows.get(alias.lower())
        if contract is None:
            raise ValueError(f"未找到关注合约 {alias}，请先用 follow 添加")
        return contract

    def follow(self, alias: str, symbol: str, name: str, category: str) -> FollowedContract:
        if alias in self.follows:
            raise ValueError(f"已存在关注别名 {alias}")
        contract = FollowedContract(alias, symbol, name, category)
        self.follows[alias] = contract
        return contract

    def update_follow(self, alias: str, name: str, category: str) -> FollowedContract:
        updated = replace(self.contract(alias), name=name, category=category)
        self.follows[updated.alias] = updated
        return updated

    def delete_follow(self, alias: str) -> None:
        del self.follows[self.contract(alias).alias]
        self._discard_unused_prices()

    def target_rule(self, symbol: str, target: Decimal, device_id: str | None = None) -> TargetAlert | None:
        return next(
            (
                alert for alert in self.alerts.values()
                if alert.contract.symbol == symbol and alert.target == target and alert.device_id == device_id
            ),
            None,
        )

    def register_targets(
        self, contract: FollowedContract, targets: list[Decimal], reference: Decimal,
        device_id: str | None = None,
    ) -> list[TargetRegistration]:
        registrations = []
        for target in targets:
            existing = self.target_rule(contract.symbol, target, device_id)
            if existing is not None:
                registrations.append(TargetRegistration(target, "duplicate", existing))
            elif target == reference:
                registrations.append(TargetRegistration(target, "at_target", None))
            else:
                alert = TargetAlert(str(next(self._ids)), contract, target, reference, device_id)
                self.alerts[alert.id] = alert
                registrations.append(TargetRegistration(target, "created", alert))
        return registrations

    def has_updown(
        self, symbol: str, minutes: int, threshold: Decimal, unit: str, device_id: str | None = None,
    ) -> bool:
        return any(
            rule.contract.symbol == symbol
            and rule.minutes == minutes
            and rule.threshold == threshold
            and rule.unit == unit
            and rule.device_id == device_id
            for rule in self.updowns.values()
        )

    def add_updown(
        self, contract: FollowedContract, minutes: int, threshold: Decimal, unit: str, device_id: str | None = None,
    ) -> Updown:
        if self.has_updown(contract.symbol, minutes, threshold, unit, device_id):
            raise ValueError("这条涨跌监控已存在")
        rule = Updown(str(next(self._ids)), contract, minutes, threshold, unit, device_id=device_id)
        self.updowns[rule.id] = rule
        if minutes and contract.symbol not in self._window_prices:
            latest = self._last_prices.get(contract.symbol)
            self._window_prices[contract.symbol] = [latest] if latest is not None else []
        return rule

    def delete_alert(self, alert_id: str) -> None:
        if self.alerts.pop(alert_id, None) is None:
            raise ValueError(f"未找到目标价提醒 {alert_id}")
        self._discard_unused_prices()

    def delete_updown(self, rule_id: str) -> None:
        if self.updowns.pop(rule_id, None) is None:
            raise ValueError(f"未找到涨跌监控 {rule_id}")
        self._discard_unused_prices()

    def check_prices(self, prices: dict[str, Decimal], now: float) -> list[str]:
        return [event.message for event in self.evaluate_prices(prices, now)]

    def evaluate_prices(self, prices: dict[str, Decimal], now: float) -> list[AlertEvent]:
        events = []
        triggered = []
        for alert in self.alerts.values():
            price = prices.get(alert.contract.symbol)
            if price is not None and alert.is_triggered(price):
                triggered.append(alert.id)
                message = f"[目标价提醒] {alert.describe()}  标记价 {format_amount(price)}"
                events.append(self._record_event("target", alert, price, message))
        for alert_id in triggered:
            del self.alerts[alert_id]

        windows: dict[str, int] = {}
        for rule in self.updowns.values():
            symbol = rule.contract.symbol
            if rule.minutes:
                windows[symbol] = max(windows.get(symbol, 0), rule.minutes)
            price = prices.get(symbol)
            if price is None:
                continue
            if rule.minutes:
                baseline = self._baseline(self._window_prices[symbol], now - rule.minutes * 60)
                event = rule.check_window(price, baseline, now)
            else:
                previous = self._last_prices.get(symbol)
                event = rule.check_level(price, previous.price if previous else None, now)
            if event is not None:
                events.append(self._record_event(rule.mode, rule, price, event))

        for symbol in self.symbols() & prices.keys():
            sample = PriceSample(now, prices[symbol])
            self._last_prices[symbol] = sample
            if symbol in windows:
                history = self._window_prices[symbol]
                history.append(sample)
                # Keep the latest sample at or before the longest window's start.
                oldest = bisect_right(history, PriceSample(now - windows[symbol] * 60, Decimal(0))) - 1
                if oldest > 0 and oldest * 2 >= len(history):
                    del history[:oldest]

        if triggered:
            self._discard_unused_prices()
        return events

    def _record_event(self, kind: str, rule: TargetAlert | Updown, price: Decimal, message: str) -> AlertEvent:
        event = AlertEvent(
            next(self._event_ids), kind, rule.id, rule.contract.symbol, price, utc_timestamp(), message, rule.device_id,
        )
        self.events.append(event)
        return event

    @staticmethod
    def _baseline(history: list[PriceSample], cutoff: float) -> Decimal | None:
        index = bisect_right(history, PriceSample(cutoff, Decimal(0))) - 1
        return history[index].price if index >= 0 else None

    def _discard_unused_prices(self) -> None:
        active_symbols = self.symbols()
        window_symbols = {rule.contract.symbol for rule in self.updowns.values() if rule.minutes}
        for symbol in self._last_prices.keys() - active_symbols:
            del self._last_prices[symbol]
        for symbol in self._window_prices.keys() - window_symbols:
            del self._window_prices[symbol]
