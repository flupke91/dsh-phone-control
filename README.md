# dsh-phone-control 📱

> [English](README.en.md)

在**同一局域网**内，用手机/平板随时打开 DeepSeek Harness（DSH）的网页界面：监控 Agent 工作状态、继续对话、查看任务。无需 SSH 隧道、无需内网穿透、无需配置 HTTPS。

## 为什么需要这个插件

默认情况下 `dsh web` 只监听 `127.0.0.1`，且 CLI **故意禁止** `--host 0.0.0.0`（GUI 能在你的电脑上执行代码）。即使通过配置强制绑定，普通 HTTP 局域网源还有两个拦路虎：

| 障碍 | 现象 | 本插件的解法 |
|---|---|---|
| 只监听回环地址 | 手机根本连不上 | ✅ 补丁绑定 `0.0.0.0`（CLI 禁用的部署级逃生通道）|
| 信任围栏是启动快照 | 换网络后 `/api` 403，会话列表变空 | ✅ 网卡监视器：IP 变化后**几秒内自动**重写围栏信任列表（热重载，无需重启）|
| `crypto.randomUUID` 只在安全上下文可用 | 非 HTTPS 的局域网源上每个 RPC 崩溃 → 会话列表永远为空 | ✅ 向 index.html 注入 `getRandomValues` 版 UUID polyfill |

完整的排障过程见 [docs/troubleshooting.md](docs/troubleshooting.md)，逐步上手见 [docs/tutorial.md](docs/tutorial.md)。

## 换网也不会失效

插件每 5 秒检查一次网卡。电脑的局域网 IP 一旦变化（换路由器、开热点、DHCP 续约），它会自动把新 IP 写入 profile 的用户补丁层，DSH 热重载后信任围栏**几秒内跟上新地址**——不需要重启，不需要手动改任何配置。

## 安装

```bash
# 1. pnpm（dsh plugin 依赖）
npm install -g pnpm

# 2. 安装插件到 web profile
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git

# 3. 重启 dsh web 使新 bundle 生效
#    （Ctrl+C 停掉旧的，再运行 dsh web）

# 4. 放行防火墙（管理员 PowerShell）
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

## 使用

1. 在电脑上查局域网 IP：`ipconfig`（找 IPv4，形如 `192.168.x.x`）。
2. 手机连接**同一个 WiFi**。
3. 手机浏览器打开 `http://<电脑局域网IP>:3080`。

> 💡 换网络/路由器后：手机用新的电脑 IP 访问，等几秒刷新即可，无需重启。

## 配置

| 环境变量 | 作用 |
|---|---|
| `DSH_PHONE_CONTROL_TRUSTED` | 额外的信任主机，逗号分隔（`host1,host2`），追加到 `/api` 围栏信任列表 |

## 安全警告 ⚠️

- 绑定 `0.0.0.0` 会把 Harness **暴露给局域网内所有设备**。这个 GUI 可以在主机上执行命令——请把它当作远程代码执行来对待，只在可信网络使用。
- `/api` 的浏览器信任围栏是**防 DNS 重绑定，不是登录认证**，没有账号体系。
- 按设计，特权接口（`settings.*`、`credentials.*`、`host.pickDirectory` 等）**仍只允许回环访问**：手机上这些请求返回 403 属正常。对话、工具、任务、会话列表均不受影响。

## 原理

一个 bundle 补丁层 + 一个零依赖的小型 host 插件：

1. **`cordis.patch.yml`** 覆盖 `webserver` 行（`host: 0.0.0.0`）。
2. **`lib/index.js`**：
   - 注册 index 变换（`webServer.tapIndex`），向 `<head>` 注入经典 `<script>` polyfill（在延迟执行的 app bundle 之前运行）；
   - 轮询网卡，检测到局域网 IP 变化后，重写 profile 用户补丁中受管的 `connection` 覆盖，让 `/api` 信任围栏始终接受当前 IP。

全部使用官方机制：bundle 补丁层、index 变换钩子、用户补丁层。**不改动任何安装文件。**

## 许可证

MIT
