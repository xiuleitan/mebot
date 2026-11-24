# probe-vps API

API 使用 UTF-8 JSON，路径前缀是 `/api/v1`。服务端持续取币安 U 本位合约标记价，收到新规则后纳入后台监控。关注、规则、采样、设备注册、触发事件和请求去重都属于当前进程，启动新进程后从空状态开始。终端模式 `probe.py` 和 API 模式 `api.py` 各自管理自己的运行状态。

## 本地启动

从仓库根目录运行。只在本机测试时可以监听回环地址。

```bash
cd probe-vps
python3 -B api.py
```

```bash
curl -fsS http://127.0.0.1:8765/api/v1/health
```

手机和开发主机连到同一局域网后，将主机的局域网 IPv4 设为 `PROBE_BIND_IP`，供 `--host` 使用。本地 HTTPS 证书必须包含这个地址的 IP SAN。`--token-file` 读取访问令牌，也可以通过环境变量 `PROBE_API_TOKEN` 配置。监听局域网时需要配置令牌。

```bash
python3 -B api.py --host "$PROBE_BIND_IP" --port 8765 \
  --tls-cert /path/to/server.crt --tls-key /path/to/server.key \
  --token-file /path/to/api-token
```

### mebot 调试版接入 HTTPS

在 App 中填写 `https://<LAN_IP>:8765/api/v1/` 和访问令牌。调试包连接本地 CA 签发的服务时，将 CA **公开证书**保存到 Android 项目的 `app/src/debug/assets/probe-local-ca.crt` 并重新构建。服务器证书须包含连接使用的 IP 或主机名。面向公网的服务使用系统信任的 HTTPS 证书。

从开发主机验证同一个 HTTPS 地址时，可用公开的 CA 文件和同一个访问令牌。

```bash
PROBE_ROOT='https://<LAN_IP>:8765/api/v1'
PROBE_CA='/path/to/ca.crt'
PROBE_TOKEN_PATH='/path/to/api-token'

curl -fsS --cacert "$PROBE_CA" \
  -H "Authorization: Bearer $(cat "$PROBE_TOKEN_PATH")" \
  "$PROBE_ROOT/health"
```

## 接口一览

以下路径均相对于 `/api/v1`。`POST`、`PATCH` 和 `PUT` 使用 `Content-Type: application/json`。价格和阈值字段使用十进制字符串，窗口分钟数使用 JSON 整数。

| 方法和路径 | 用途 | 成功响应 |
| --- | --- | --- |
| `GET /health` | 检查进程和行情轮询状态 | `200`，包含 `market` 和 `instance_id` |
| `PUT /devices/{device_id}` | 注册或更新 mebot 安装实例的 FCM 令牌 | `200`，返回 `registered` 和 `push_available` |
| `GET /follows` | 查看关注合约 | `200`，`{"items":[...]}` |
| `POST /follows` | 添加关注合约 | `201`，返回新关注项 |
| `PATCH /follows/{alias}` | 修改名称或类别 | `200`，返回更新后的关注项 |
| `DELETE /follows/{alias}` | 取消关注 | `204`，空响应体 |
| `GET /alerts` | 查看待触发的目标价规则，可按 `device_id` 筛选 | `200`，`{"items":[...]}` |
| `POST /alerts` | 创建一个或多个一次性目标价提醒 | `201` 有新规则，`200` 已存在或已达目标 |
| `DELETE /alerts/{id}` | 撤销待触发的目标价规则，可限定 `device_id` | `204`，空响应体 |
| `GET /updowns` | 查看窗口和阶梯规则，可按 `device_id` 筛选 | `200`，`{"items":[...]}` |
| `POST /updowns` | 创建窗口涨跌或阶梯价位规则 | `201`，返回新规则 |
| `DELETE /updowns/{id}` | 删除窗口或阶梯规则，可限定 `device_id` | `204`，空响应体 |
| `GET /events?after_id=0` | 获取已触发的提醒事件，可按 `device_id` 筛选 | `200`，包含游标和 `items` |
| `GET /news?limit=100` | 补拉方程式新闻和财经快讯历史 | `200`，返回 `instance_id` 和 `items` |
| `PUT /news/devices/{device_id}` | 更新新闻进程中的 mebot 设备注册 | `200`，返回 `registered` 和 `push_available` |
| `POST /news/push-check` | 请求发回一条静默 FCM 检查消息 | `200`，`{"accepted":true}` |

### 健康和取价状态

`GET /health` 示例。`status: "ok"` 表示 API 可访问。行情状态看 `market.state`，包括 `idle`（暂无监控合约）、`waiting`（有合约待首次取价）、`ok`、`partial`（部分报价缺失）和 `unavailable`。`source` 为 `binance` 或 `price_file`。时间字段是 UTC，尚未取价时为 `null`。`push.available` 表示已配置 FCM 发送端，`last_accepted_at` 记录 FCM 最近一次接收发送请求的时间，`last_error` 记录最近一次推送失败的原因。

```json
{
  "status": "ok",
  "instance_id": "00000000000000000000000000000001",
  "poll_interval_seconds": 5,
  "market": {
    "source": "binance",
    "state": "ok",
    "symbols": ["BTCUSDT"],
    "missing_symbols": [],
    "last_poll_at": "2026-11-23T05:30:05.033Z",
    "last_success_at": "2026-11-23T05:30:05.033Z",
    "last_error": null
  },
  "push": {"available":false,"last_accepted_at":null,"last_error":null}
}
```

### 注册 mebot 设备

用 `PUT /devices/{device_id}` 提交令牌，路径使用带连字符的安装实例 UUID。重复 `PUT` 会更新该 UUID 对应的 FCM 令牌，已绑定的规则继续使用这个设备 ID。这个请求使用同一 Bearer 鉴权。`package_name` 填 `com.txl.mebot`。

```json
{"fcm_token":"<FCM 注册令牌>","package_name":"com.txl.mebot"}
```

成功返回 `200`。服务端加载了 FCM 发送配置时，`push_available` 为 `true`。发送端缺少配置时也能登记设备并创建规则，触发事件照常可查。

```json
{"registered":true,"push_available":false}
```

注册后，在 `POST /alerts` 和 `POST /updowns` 的 JSON 中加 `device_id`，每条规则的提醒会发给这个安装实例。使用 `GET /alerts?device_id=<UUID>`、`GET /updowns?device_id=<UUID>` 和 `GET /events?after_id=0&device_id=<UUID>` 读取该设备的数据。删除时附上同一查询参数，规则不属于该设备时返回 `404`。

```json
{"symbol":"BTCUSDT","target_price":"65000","device_id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"}
```

带设备 ID 的规则和事件会返回 `device_id`。创建时省略它可以继续使用原来的无设备规则。查询或删除时省略它会使用全局范围。重复规则检查按设备分别执行。服务重启后 `instance_id` 会变化，设备需重新 `PUT` 注册，再按需要重新提交规则。

### 关注合约

`POST /follows` 的请求和成功响应字段相同。`name` 默认使用合约代码，`category` 默认为 `加密货币`。别名按小写保存，合约代码按大写保存。更新只传 `name` 或 `category` 中需要修改的字段。取消关注后，该合约已有的提醒规则仍会继续运行。

```json
{"alias":"btc","symbol":"BTCUSDT","name":"比特币","category":"加密货币"}
```

### 一次性目标价提醒

mebot 可以直接传完整的 `symbol`，创建规则的同时将合约纳入监控。也可以传已关注的 `alias`。两者选择一个。使用 `target_price` 创建单个提醒，使用 `target_prices` 数组在同一参考价下创建多个提醒。

```json
{"symbol":"BTCUSDT","target_price":"65000"}
```

`POST /alerts` 响应始终按提交顺序返回 `items`。每项的 `status` 为 `created`、`duplicate` 或 `at_target`。`duplicate` 返回已有规则，`at_target` 表示创建时标记价已等于目标价，`rule` 为 `null`。`direction` 为 `up` 或 `down`。触发后该规则从待触发列表移除，结果可从 `/events` 读取。

```json
{
  "items": [
    {
      "target_price": "65000",
      "status": "created",
      "rule": {"id":"1","symbol":"BTCUSDT","target_price":"65000","reference_price":"64000","direction":"up"}
    }
  ]
}
```

`GET /alerts` 的 `items` 直接包含上例里的 `rule` 对象。

### 窗口和阶梯提醒

`POST /updowns` 接受以下两种请求，合约也可用已关注的 `alias` 指定。窗口 `unit` 为 `percent` 时，`threshold: "1"` 表示 1%。`unit` 为 `absolute` 时按报价单位的绝对价差判断。窗口中的上涨和下跌分别冷却一个窗口长度。阶梯跨过多个整数倍价位时报告最后触达的一档，上下方向共用 45 分钟冷却。

```json
{"symbol":"BTCUSDT","mode":"window","window_minutes":15,"threshold":"1","unit":"percent"}
```

```json
{"symbol":"BTCUSDT","mode":"ladder","threshold":"1000"}
```

`POST /updowns` 返回规则，`GET /updowns` 的 `items` 包含同样的规则对象。阶梯的 `window_minutes` 为 `null`，`unit` 为 `absolute`。

```json
{"id":"2","symbol":"BTCUSDT","mode":"window","window_minutes":15,"threshold":"1","unit":"percent"}
```

### 读取触发事件

每次请求传上次收到的最大事件 ID 作为 `after_id`。第一次可省略或传 `0`。事件 `kind` 是 `target`、`window` 或 `ladder`。`triggered_at` 是 UTC 时间，`mark_price` 是触发时的标记价。事件按 ID 升序返回，最多保留最近 256 条。`instance_id` 变化时从 `after_id=0` 开始读。`oldest_id` 和 `last_id` 是全局保留范围，传 `device_id` 时只筛选 `items`，该设备的事件 ID 可能不连续。

```json
{
  "instance_id": "00000000000000000000000000000001",
  "oldest_id": 1,
  "last_id": 1,
  "items": [
    {
      "id": 1,
      "kind": "target",
      "rule_id": "1",
      "symbol": "BTCUSDT",
      "mark_price": "65100",
      "triggered_at": "2026-11-23T05:30:05.033Z",
      "message": "[目标价提醒] 1  BTCUSDT  涨到 65000  参考价 64000  标记价 65100"
    }
  ]
}
```

### 失败和重试

`POST` 可以带 `Idempotency-Key`，例如由 mebot 每次新建操作生成一个 UUID。同一操作重试时使用相同的键和 JSON。当前进程为最近 512 个成功请求保留原响应，即使一次性规则已触发，重发原请求也会返回原结果。查询当前待触发规则时使用对应的 `GET`。

| 状态码 | `error.code` 示例 | 处理方式 |
| --- | --- | --- |
| `400` | `invalid_request` | 检查字段名、JSON 类型和取值 |
| `401` | `unauthorized` | 检查访问令牌 |
| `404` | `not_found` | 检查路径、别名或规则 ID |
| `409` | `rule_exists`、`alias_exists`、`idempotency_conflict` | 查询已有规则或关注项，新的操作使用新的请求键 |
| `409` | `device_not_registered` | 先用 `PUT /devices/{device_id}` 注册，再重试提交规则 |
| `422` | `contract_not_trading` | 检查币安 U 本位合约代码和交易状态 |
| `429` | `rate_limited` | 按 `Retry-After` 等待后再发请求。重试同一次创建时沿用原 `Idempotency-Key` |
| `503` | `market_unavailable` | 行情请求失败，本次规则未创建，可用原请求键重试 |
| `503` | `news_unavailable` | 暂时无法读取新闻历史，可稍后重试 |

```json
{"error":{"code":"invalid_request","message":"请选择 target_price 或 target_prices"}}
```

### 从 mebot 测一次规则提交

先读 `/health`，再向 `/alerts` 提交一个目标价。用 `GET /alerts` 核对 `id`、目标价和方向。把 `65000` 换成要测试的价位。

```bash
curl -fsS --cacert "$PROBE_CA" \
  -H "Authorization: Bearer $(cat "$PROBE_TOKEN_PATH")" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: mebot-smoke-1' \
  -d '{"symbol":"BTCUSDT","target_price":"65000"}' \
  "$PROBE_ROOT/alerts"
```

如果需要控制行情，可用 `--price-file /path/to/prices.json` 启动 API。文件格式为 `{"BTCUSDT":"64000"}`。先创建 `65000` 的提醒，再将文件中的价格改为 `65100`。等待下一轮轮询后，从 `/events` 读取触发结果。`/health` 的 `market.source` 会标明当前使用 `price_file`。正式连接币安时用默认行情源即可。

## 启用 FCM 发送

使用注册了 `com.txl.mebot` 的 Firebase 项目，在服务端的私有目录保存具备 FCM 发送权限的服务账号 JSON。将私钥文件权限设为 `600`。手机的 FCM 配置使用 `google-services.json`，服务端启动时指定服务账号文件路径。 [Firebase HTTP v1 服务端认证](https://firebase.google.com/docs/cloud-messaging/send/v1-api)

为 FCM 安装独立依赖后，用虚拟环境里的 Python 和原有 HTTPS 参数启动。在原启动命令末尾加 `--fcm-credentials /path/to/service-account.json`，也可设置 `PROBE_FCM_CREDENTIALS`。

```bash
PROBE_FCM_VENV=$(mktemp -d)
python3 -m venv "$PROBE_FCM_VENV"
"$PROBE_FCM_VENV/bin/python" -m pip install -r requirements-fcm.txt
"$PROBE_FCM_VENV/bin/python" -B api.py --host "$PROBE_BIND_IP" --port 8765 \
  --tls-cert /path/to/server.crt --tls-key /path/to/server.key \
  --token-file /path/to/api-token --fcm-credentials /path/to/service-account.json
```

规则触发时，服务先记录事件，再由独立线程调用 FCM HTTP v1。消息只有 `data`，使用 Android 高优先级和 `300s` TTL，限定包名为 `com.txl.mebot`。数据字段均为字符串，`message` 可选。客户端可用 `(instance_id, event_id)` 判定是否已处理该事件。 [FCM Android 消息优先级](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

```json
{
  "type":"trading_price_alert",
  "device_id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
  "instance_id":"00000000000000000000000000000001",
  "event_id":"1",
  "rule_id":"1",
  "kind":"target",
  "symbol":"BTCUSDT",
  "mark_price":"65100",
  "triggered_at":"2026-11-23T05:30:05.033Z",
  "message":"BTCUSDT 涨到 65000"
}
```

发送失败时，事件仍可从 `/events` 查询，最近失败原因可从 `/health` 的 `push.last_error` 查看。本地联调可配合 `--price-file` 手动改变报价，触发一次实际推送。

## 方程式新闻和财经

`GET /news?limit=100` 使用现有 Bearer 鉴权。`limit` 是 `1` 至 `100` 的整数，默认 `100`。`items` 按发布时间从新到旧排列。SQLite 保存最近 1,000 条公开快讯，mebot 可在启动或恢复联网时补拉并静默入库。

```json
{
  "instance_id":"00000000000000000000000000000001",
  "items":[
    {
      "news_id":"eqn:6311",
      "channel":"main",
      "title":"示例快讯",
      "published_at":"2026-11-24T09:00:00.000Z",
      "url":"https://eqn.news/main/6311"
    }
  ]
}
```

`news_id` 是 `eqn:` 加方程式的快讯编号，同一条新闻在 FCM 和历史接口里使用同一个 ID。`instance_id` 会随服务重启变化，App 按 `news_id` 去重即可。`published_at` 使用 UTC RFC3339。`url` 仅在有合适的 HTTPS 链接时出现。主频道和财经都采用中文首行作为标题，缺少中文时使用英文。

| 频道 | 采集 | FCM | mebot 行为 |
| --- | --- | --- | --- |
| `main` 方程式新闻 | WS 保持连接，收到快讯信号后立刻用 REST 取官方编号，重连用 REST 补漏 | `high` | 入库并发普通系统通知 |
| `tradfi` 方程式财经 | REST 每轮完成后等待 5 秒 | `normal` | 入库并更新资讯区 |

首次填充历史和主频道断线补漏均静默。主频道 REST 列表中的交易所公告等带有其他频道标签，采集时排除。新闻发送前检查发布时间，超过 5 分钟的消息只保留在历史中。所有采集请求共用 REST 节流，并按上游 `Retry-After` 退避。 [方程式 API 文档](https://eqn.news/api-docs)

正式部署中，`PUT /devices/{device_id}` 向价格进程注册。新闻使用同一个 HTTPS 地址和 Bearer，通过 `PUT /news/devices/{device_id}` 单独注册，传相同的安装实例 UUID 和 FCM 令牌。

```json
{"fcm_token":"<FCM 注册令牌>","package_name":"com.txl.mebot"}
```

响应是 `{"registered":true,"push_available":false}` 或 `{"registered":true,"push_available":true}`。`push_available` 表示新闻实际发送是否已启用。新闻进程重启后会生成新的新闻 `instance_id`，以 `GET /news` 为准，此时应重新 `PUT /news/devices/{device_id}`。FCM 令牌更新时重复注册两个入口。

FCM 只有 `data`，TTL 为 `300s`，字段值全部是字符串。`device_id` 是接收装置的安装实例 UUID。新闻通知的 `instance_id` 对应 `GET /news`，价格提醒的 `instance_id` 对应原有价格接口。

```json
{
  "type":"trading_news",
  "instance_id":"00000000000000000000000000000001",
  "device_id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
  "news_id":"eqn:6311",
  "channel":"main",
  "title":"示例快讯",
  "published_at":"2026-11-24T09:00:00.000Z",
  "url":"https://eqn.news/main/6311"
}
```

### 隔离验证和启用

先安装 `requirements-news.txt`，然后给 API 加 `--news-db`。以下示例在回环地址启动采集和历史查询。

```bash
python3 -m pip install -r requirements-news.txt
python3 -B api.py --host 127.0.0.1 --port 8767 --news-db /path/to/news.sqlite3
curl -fsS 'http://127.0.0.1:8767/api/v1/news?limit=100'
```

如果分别运行价格和新闻进程，可让新闻进程监听 `127.0.0.1:8767`。在 HTTPS 反向代理中将 `GET /api/v1/news`、`PUT /api/v1/news/devices/{device_id}` 和 `POST /api/v1/news/push-check` 交给新闻进程，价格 API 继续由原价格进程处理。如果使用 systemd，新闻进程可用 `StateDirectory=probe-vps-news` 挂载可写的状态目录。

服务端与手机版本对齐后，在新闻进程的启动配置里添加 FCM 服务账号和 `--news-push`，再让手机注册新闻设备。新闻 SQLite 跨重启保留，新闻设备注册保留在新闻进程内。

### 静默 FCM 检查

`POST /news/push-check` 使用相同的 HTTPS 和 Bearer 鉴权，提交安装 UUID 和本次检查独有的 UUID。字段只接受小写、带连字符的规范 UUID。

```json
{"device_id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa","check_id":"cccccccc-cccc-4ccc-cccc-cccccccccccc"}
```

服务只取该设备通过新闻注册接口登记的 FCM 令牌。FCM 受理后返回 `200` 和 `{"accepted":true}`。静默检查的 FCM 为纯 `data`，Android 优先级 `normal`，TTL 为 `20s`，包名为 `com.txl.mebot`。手机在 20 秒内收到匹配的 `check_id` 才显示绿色。检查消息由客户端只用于更新状态，新闻列表、系统通知和响铃各自按原消息类型处理。 [FCM 消息有效期](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)

```json
{"type":"trading_push_check","device_id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa","check_id":"cccccccc-cccc-4ccc-cccc-cccccccccccc"}
```

未登记返回 `409 device_not_registered`，客户端可重新 `PUT /news/devices/{device_id}` 后重试。FCM 服务不可用或请求发送失败返回 `503 push_unavailable`。同一设备一次只能进行一个检查，成功受理后按该次发送开始计 10 秒窗口，超限返回 `429 rate_limited`。未登记、拒绝和发送失败均不占用成功窗口。
