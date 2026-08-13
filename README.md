# dsh-phone-control 📱

Control the [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (DSH) web GUI from your phone or tablet on the **same LAN** — no SSH, no cloud, no HTTPS setup.

在**同一局域网**内用手机/平板随时打开 DSH 的网页界面，监控 Agent 工作状态、继续对话、查看任务。无需 SSH 隧道、无需内网穿透、无需配置 HTTPS。

## Why this plugin exists / 为什么需要它

Out of the box, `dsh web` only listens on `127.0.0.1` and the CLI **intentionally rejects** `--host 0.0.0.0` (the GUI can execute code on your machine). Even if you force a bind via config, two more blockers appear on a plain-HTTP LAN origin:

| Blocker | Result | This plugin |
|---|---|---|
| 只监听回环地址 | 手机连不上 | ✅ 补丁绑定 `0.0.0.0`（CLI 禁用的逃生通道）|
| 信任围栏快照过期 | 换网络后 `/api` 403，列表变空 | ✅ 每次加载/热重载时**实时读取网卡 IP** 喂给围栏 |
| `crypto.randomUUID` 只在安全上下文可用 | 非 HTTPS 的局域网源上每个 RPC 崩溃 → 会话列表永远为空 | ✅ 向 index.html 注入 `getRandomValues` 版 UUID polyfill |

See [docs/troubleshooting.md](docs/troubleshooting.md) for the full story, and [docs/tutorial.md](docs/tutorial.md) for a step-by-step guide. 详细原理与排查过程见 [docs/troubleshooting.md](docs/troubleshooting.md)。

## Install / 安装

```bash
# 1. pnpm (dsh plugin 依赖)
npm install -g pnpm

# 2. 安装插件到 web profile
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git

# 3. 重启 dsh web 使新 bundle 生效
#    (Ctrl+C 停掉旧的，再运行 dsh web)

# 4. 放行防火墙（管理员 PowerShell）
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

## Use / 使用

1. 在电脑上查局域网 IP：`ipconfig`（找 IPv4，形如 `192.168.x.x`）。
2. 手机连接**同一个 WiFi**。
3. 手机浏览器打开 `http://<电脑局域网IP>:3080`。

> 💡 换网络/路由器后如果手机又出现"页面能开但列表空"：重启一次 `dsh web`（信任列表会重新按当前网卡计算），或用 `dsh plugin --profile web update` 重装。

## Configuration / 配置

| 环境变量 | 作用 |
|---|---|
| `DSH_PHONE_CONTROL_TRUSTED` | 额外的信任主机，逗号分隔（`host1,host2`），追加到 `/api` 围栏信任列表 |
| `DSH_WEB_URL` | DSH 自带；本插件不改动 |

## Security warning / 安全警告 ⚠️

- Binding `0.0.0.0` exposes the harness to **every device on your LAN**. The web GUI can execute commands on the host — treat it as remote code execution. Only use on networks you trust.
- The `/api` browser-trust fence is **DNS-rebinding protection, not authentication**. There is no login.
- By design, privileged methods (`settings.*`, `credentials.*`, `host.pickDirectory`, …) stay **loopback-only**: from the phone they return 403. Chat, tools, jobs and monitoring work normally. 这是设计如此：设置/凭据等敏感接口钉死在回环，手机上 403 属正常。

## How it works / 原理

One bundle patch layer + one tiny host plugin (zero dependencies):

1. **`cordis.patch.yml`** overrides the `webserver` row (`host: 0.0.0.0`) and the `web-runtime` row (`trustedHosts` recomputed from `os.networkInterfaces()` via the Loader's `!!js` expression evaluator).
2. **`lib/index.js`** registers an index transform (`webServer.tapIndex`) that injects a classic `<script>` polyfill for `crypto.randomUUID` — it runs before the deferred app bundle.

Both use only official mechanisms: the bundle patch layer and the index-tap hook. No installed files are modified.

## License

MIT
