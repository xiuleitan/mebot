# probe-vps

独立的币安 U 本位合约标记价监控程序。行情和规则 API 使用 Python 3.9 或以上版本的标准库和币安公开 REST 接口。每轮完成后默认等待 5 秒。单个合约单独取价，多个合约按旧版方式批量取价后筛选。

## 提供 API

从 `probe-vps/` 用 `python3 -B api.py` 启动本机 API，默认地址为 `http://127.0.0.1:8765/api/v1`。从手机联调时使用主机的局域网地址和 HTTPS 证书。mebot 可以注册 FCM 令牌并将价格提醒绑定到安装实例。接口、Android 调试证书和 FCM 发送配置见 [API 文档](API.md)。

方程式新闻可用 `--news-db` 启用历史采集，用 `--news-push` 启用独立的新闻发送队列。两种参数的联调方式见 [新闻接口](API.md#方程式新闻和财经)。

## 本地终端试运行

```sh
cd probe-vps
python3 -B probe.py --show-prices
```

从仓库根目录运行。程序启动后，在同一个终端逐行输入以下命令。等待第一条标记价出现，确认终端已收到行情。

```text
follow btc futures:BTCUSDT 比特币 加密货币
list follow
add updown btc 1 0.05%
add updown btc 100u
list updowns
```

创建目标价提醒时，输入 `btc` 加上一个或多个具体价位，例如 `btc 62000 65000`。程序按创建时的标记价判断每个目标要等涨到还是跌到，达到或越过目标后在终端打印事件并移除该条提醒。用 `list alerts` 查看待触发状态，也可以用 `delete alerts <ID>` 删除。

- `follow update btc 比特币 主流币` 修改名称和类别。`delete follow btc` 移出关注清单。已建立的提醒有自己的生命周期，仍会跟踪该合约，可按 ID 单独删除。
- `add updown btc 15 1%` 监控百分比。`add updown btc 15 2000u` 监控绝对价差。窗口基准取窗口起点或之前最近的有效采样。需要等历史覆盖窗口，上涨和下跌分别冷却，冷却时长等于窗口长度。
- `add updown btc 1000u` 监控 `1000u` 的整数倍价位。连续两次有效采样间跨过多个价位时，报告最后触达的一个。每条阶梯规则触发后上下方向共用 45 分钟冷却。
- 常用命令见终端 `help`。`--show-prices` 用于观察轮询，实际触发事件始终会输出。输入 `exit` 或按 `Ctrl+C` 停止。

## 用文件预设启动命令

例如创建 UTF-8 文件 `watch.commands`，每行写一条关注或提醒命令。以 `#` 开头的整行是注释。

```text
follow btc futures:BTCUSDT 比特币 加密货币
add updown btc 15 1%
add updown btc 1000u
```

```sh
python3 -B probe.py --commands watch.commands
```

关注、提醒、冷却状态和窗口采样都在进程内。重新启动时，启动文件里的命令会重新执行，目标价规则会按启动时的报价重新注册。可用 `--interval` 调整查询间隔，用 `--base-url` 或 `BINANCE_FUTURES_BASE_URL` 指定行情接口地址。通过本地终端直接运行的命令仍适用于前台查看和配置。

## 配置和验证

在 `probe-vps/` 中可从 [.env.example](.env.example) 创建自己的 `.env`。需要使用环境变量时，由启动 shell 显式加载。也可以使用等效的命令行参数。

| 环境变量 | 用途 |
| --- | --- |
| `PROBE_API_TOKEN` | HTTP API 的 Bearer 令牌。对外提供服务时使用自己生成的随机值，也可用 `--token-file`。 |
| `PROBE_FCM_CREDENTIALS` | 自己的 Firebase 服务账号文件路径，对应 `--fcm-credentials`。 |
| `BINANCE_FUTURES_BASE_URL` | 可选的币安 U 本位合约公开行情接口地址。 |

价格轮询和基础 API 使用 Python 标准库。启用 FCM 才需安装 `requirements-fcm.txt`，启用新闻采集才需安装 `requirements-news.txt`。`--news-db` 指定公开新闻的 SQLite 路径。API 规则与设备注册保存在当前服务进程内，重启后须重新注册和提交规则。

从仓库根目录运行本地测试。

```sh
python3 -B -m unittest discover -s probe-vps -p 'test_*.py'
```

凭据、启动预设、数据库和日志放在本机忽略路径。对外部署使用 HTTPS 和独立生成的访问令牌。若通过反向代理发布，仍要为 API 进程配置令牌。

触发规则迁自 CoinEye `bddb63e`，来源授权见 [LICENSE](LICENSE)。
