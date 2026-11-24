from __future__ import annotations

import gzip
import json
from decimal import Decimal, InvalidOperation
from http.client import HTTPException
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from zlib import error as ZlibError


DEFAULT_BASE_URL = "https://fapi.binance.com"


class MarketDataError(Exception):
    pass


class BinanceClient:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, timeout: float = 10):
        url = urlsplit(base_url)
        if url.scheme not in {"https", "http"} or not url.hostname or url.query or url.fragment:
            raise ValueError("币安接口地址需要有效的 HTTP 或 HTTPS 地址")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def mark_prices(self, symbols: set[str]) -> dict[str, Decimal]:
        if not symbols:
            return {}

        path = "/fapi/v1/premiumIndex"
        if len(symbols) == 1:
            path += "?" + urlencode({"symbol": next(iter(symbols))})

        payload = self._request(path)
        if isinstance(payload, dict) and "symbol" in payload:
            payload = [payload]
        if not isinstance(payload, list):
            raise MarketDataError("币安返回了无法识别的标记价数据")

        prices = {}
        for item in payload:
            if not isinstance(item, dict):
                continue
            symbol = item.get("symbol")
            if not isinstance(symbol, str) or symbol not in symbols:
                continue
            try:
                price = Decimal(str(item.get("markPrice")))
            except InvalidOperation:
                continue
            if price.is_finite() and 0 < float(price) < float("inf"):
                prices[symbol] = price
        return prices

    def is_trading(self, symbol: str) -> bool:
        payload = self._request("/fapi/v1/exchangeInfo")
        if not isinstance(payload, dict) or not isinstance(payload.get("symbols"), list):
            raise MarketDataError("币安返回了无法识别的合约列表")
        return any(
            isinstance(item, dict) and item.get("symbol") == symbol and item.get("status") == "TRADING"
            for item in payload["symbols"]
        )

    def _request(self, path: str):
        request = Request(
            self.base_url + path,
            headers={"Accept": "application/json", "Accept-Encoding": "gzip", "User-Agent": "probe-vps"},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                body = response.read()
                if response.headers.get("Content-Encoding", "").lower() == "gzip":
                    body = gzip.decompress(body)
            return json.loads(body, parse_float=Decimal)
        except HTTPError as error:
            with error:
                raise MarketDataError(f"币安接口返回 HTTP {error.code}") from error
        except (OSError, HTTPException) as error:
            raise MarketDataError(f"访问币安行情接口失败（{error}）") from error
        except (ValueError, EOFError, ZlibError) as error:
            raise MarketDataError("币安返回的数据无法解析") from error
