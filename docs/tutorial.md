# Tutorial / 详细教程：用手机控制电脑上的 DSH

English | [中文](#中文版)

This guide walks you through installing `dsh-phone-control` and using the DSH web GUI from your phone, end to end. It assumes a Windows PC running `dsh web` and a phone on the same WiFi.

---

## 中文版

### 0. 准备条件

| 项目 | 要求 |
|---|---|
| 电脑 | Windows，已能运行 `dsh web`（`http://127.0.0.1:3080` 可打开）|
| Node.js | ≥ 22（DSH 运行环境自带）|
| 手机 | 与电脑连接**同一个 WiFi**（同一网段）|
| 权限 | 一次管理员 PowerShell（放行防火墙）|

### 1. 安装 pnpm（仅首次）

`dsh plugin` 依赖 pnpm 管理插件依赖：

```powershell
npm install -g pnpm
pnpm --version   # 确认有输出
```

### 2. 安装插件

```powershell
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git
```

安装完成后，`~/.dsh/profiles/web/package.json` 会多出依赖，`dsh.profile.bundles` 会自动加入 `dsh-phone-control`。

### 3. 重启 dsh web

bundle 列表在启动时读取，所以必须重启：

- 在运行 `dsh web` 的终端里按 `Ctrl+C` 停止；
- 重新运行 `dsh web`。

重启后启动日志会多打印一行局域网地址：

```
dsh web: http://127.0.0.1:3080 (LAN: http://192.168.x.x:3080)
```

### 4. 放行防火墙（管理员 PowerShell）

```powershell
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

> 不想用管理员终端？在**普通** PowerShell 里执行会提示"需要提升"，按提示用管理员身份重开即可。Windows 也可能在首次监听 0.0.0.0 时弹出"Windows 安全警报"，点"允许访问"（专用网络）等效。

### 5. 手机访问

1. 电脑上查 IP：`ipconfig` → 无线局域网适配器 WLAN → IPv4 地址（形如 `192.168.x.x`）。
2. 手机连**同一个 WiFi**。
3. 手机浏览器打开：`http://192.168.x.x:3080`（把 `192.168.x.x` 换成电脑的真实 IP）。

首次打开会看到"内测声明"弹窗，点"继续"。

### 6. 常见问题（FAQ）

**Q1：手机能打开页面，但会话列表是空的 / 一直转圈？**
- 先看电脑上 `http://127.0.0.1:3080` 是否正常。正常的话，多半是信任围栏没认到手机：
  1. 确认手机和电脑在同一网段（手机浏览器访问 `http://192.168.x.x:3080` 时，x 必须和电脑 ipconfig 里一致）；
  2. 重启一次 `dsh web`（信任列表按当前网卡重新计算）；
  3. 若你最近换过网络/路由器，重启前先确认电脑 IP 已变化。
- 浏览器缓存了旧的 index.html？做一次硬刷新（手机浏览器清缓存或开无痕）。

**Q2：手机上"设置"页面报 403？**
正常。设置/凭据等特权接口按设计只允许回环访问（安全边界）。对话、工具、任务、会话列表都不受影响。

**Q3：聊天记录一直加载不出来？**
历史记录是一次性下载的 JSON（可能高达数 MB~十几 MB），弱网或网络抖动时会超时。换更稳定的 WiFi，或等网络恢复后重进会话。

**Q4：换了个 WiFi / 路由器，手机又连不上了？**
- 电脑 IP 变了：用新 IP 访问；
- 重启 `dsh web` 让信任列表跟随新网卡；
- 确认新网络没开"AP 隔离/客户端隔离"（路由器设置里）。

**Q5：怎么卸载？**

```powershell
dsh plugin --profile web remove dsh-phone-control
# 重启 dsh web 生效
```

### 7. 高级：自定义信任主机

```powershell
$env:DSH_PHONE_CONTROL_TRUSTED = "myhost.local,192.168.1.50"
dsh web
```

### 8. 安全须知（务必阅读）⚠️

- 绑定 `0.0.0.0` 后，**局域网内任何设备都能打开这个 GUI 并驱动 Agent 执行命令**。这不是"只读监控"，是远程控制 + 远程代码执行。
- `/api` 的浏览器信任围栏只防 DNS 重绑定，**不是登录认证**。
- 请在可信网络（家庭 WiFi 等）使用；咖啡店等公共 WiFi 上不要开启。
- 敏感接口（设置、凭据、目录选择）从手机访问会 403——这是刻意的安全边界。

---

## English

### 0. Prerequisites

| Item | Requirement |
|---|---|
| PC | Windows with `dsh web` running (`http://127.0.0.1:3080` opens) |
| Phone | On the **same WiFi** as the PC |
| Privilege | One admin PowerShell for the firewall rule |

### 1. Install pnpm (once)

```powershell
npm install -g pnpm
```

### 2. Install the plugin

```powershell
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git
```

### 3. Restart `dsh web`

The bundle list is read at boot: stop the running `dsh web` (Ctrl+C) and start it again. The startup line now shows a LAN URL:

```
dsh web: http://127.0.0.1:3080 (LAN: http://192.168.x.x:3080)
```

### 4. Allow the firewall (admin PowerShell)

```powershell
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

### 5. Use from your phone

1. Find the PC's LAN IP: `ipconfig` → IPv4 (e.g. `192.168.x.x`).
2. Phone on the **same WiFi**.
3. Phone browser: `http://192.168.x.x:3080`.

### 6. FAQ

- **Page opens but the session list is empty / spinner forever** → PC side fine? Then the trust fence doesn't know your phone: verify both are on the same subnet, restart `dsh web`, hard-refresh the phone browser.
- **Settings page returns 403 on the phone** → By design. Privileged methods are loopback-only. Everything else works.
- **Chat history won't load** → History is one large JSON download (can be tens of MB); weak/flaky WiFi can time it out. Use a more stable network.
- **After switching WiFi/router the phone can't connect** → The PC's IP changed; use the new IP and restart `dsh web`.
- **Uninstall** → `dsh plugin --profile web remove dsh-phone-control`, then restart.

### 7. Security (read this) ⚠️

Binding `0.0.0.0` exposes the harness — which can execute commands on your PC — to **every device on your LAN**. The `/api` fence is DNS-rebinding protection, **not authentication**. Use only on trusted networks.
