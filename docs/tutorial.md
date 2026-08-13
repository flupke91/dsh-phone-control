# 详细教程：用手机控制电脑上的 DSH

> [English version](tutorial.en.md)

本文从头到尾演示如何安装 `dsh-phone-control`，并用手机访问电脑上的 DSH web 界面。适用于 Windows 电脑 + 同一 WiFi 下的手机。

## 0. 准备条件

| 项目 | 要求 |
|---|---|
| 电脑 | Windows，已能运行 `dsh web`（`http://127.0.0.1:3080` 可打开）|
| 手机 | 与电脑连接**同一个 WiFi**（同一网段）|
| 权限 | 一次管理员 PowerShell（放行防火墙）|

## 1. 安装 pnpm（仅首次）

`dsh plugin` 依赖 pnpm 管理插件依赖：

```powershell
npm install -g pnpm
pnpm --version   # 确认有输出
```

## 2. 安装插件

```powershell
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git
```

安装完成后，`~/.dsh/profiles/web/package.json` 会多出依赖，`dsh.profile.bundles` 会自动加入 `dsh-phone-control`。

## 3. 重启 dsh web

bundle 列表在启动时读取，所以必须重启一次：

- 在运行 `dsh web` 的终端里按 `Ctrl+C` 停止；
- 重新运行 `dsh web`。

重启后启动日志会多打印一行局域网地址：

```
dsh web: http://127.0.0.1:3080 (LAN: http://192.168.x.x:3080)
```

## 4. 放行防火墙（管理员 PowerShell）

```powershell
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

> 在普通 PowerShell 里执行会提示"需要提升"，用管理员身份重开即可。Windows 也可能在首次监听 0.0.0.0 时弹出"Windows 安全警报"，点"允许访问"（专用网络）等效。

## 5. 手机访问

1. 电脑上查 IP：`ipconfig` → 无线局域网适配器 WLAN → IPv4 地址（形如 `192.168.x.x`）。
2. 手机连**同一个 WiFi**。
3. 手机浏览器打开：`http://192.168.x.x:3080`（把 `192.168.x.x` 换成电脑的真实 IP）。

首次打开会看到"内测声明"弹窗，点"继续"。

## 6. 常见问题（FAQ）

**Q1：手机能打开页面，但会话列表是空的 / 一直转圈？**
- 先看电脑上 `http://127.0.0.1:3080` 是否正常。正常的话，多半是围栏/网络层：
  1. 确认手机和电脑在同一网段（电脑 `ipconfig` 的 IPv4 与手机访问的地址一致）；
  2. 插件每 5 秒自动同步一次围栏信任列表——**换网后等几秒、手机刷新一下**即可，无需重启；
  3. 若插件刚安装还没重启过 `dsh web`，请先重启一次完成激活。
- 浏览器缓存了旧的 index.html？做一次硬刷新（手机浏览器清缓存或开无痕）。

**Q2：手机上"设置"页面报 403？**
正常。设置/凭据等特权接口按设计只允许回环访问（安全边界）。对话、工具、任务、会话列表都不受影响。

**Q3：聊天记录一直加载不出来？**
历史记录是一次性下载的 JSON（可能高达数 MB~十几 MB），弱网或网络抖动时会超时。换更稳定的 WiFi，或等网络恢复后重进会话。

**Q4：换了个 WiFi / 路由器，手机又连不上了？**
- 电脑 IP 变了：手机用新 IP 访问（电脑 `ipconfig` 查询）；
- 插件会**自动**把新 IP 同步给信任围栏（约 5 秒内），手机刷新即可，**不需要重启 dsh web**；
- 若等了几秒仍不行，确认新网络没开"AP 隔离/客户端隔离"（路由器设置里）。

**Q5：怎么卸载？**

```powershell
dsh plugin --profile web remove dsh-phone-control
# 手动删除 ~/.dsh/profiles/web/cordis.patch.yml 里标有
# "dsh-phone-control managed block" 的那段（可选）
# 重启 dsh web 生效
```

## 7. 高级：自定义信任主机

```powershell
$env:DSH_PHONE_CONTROL_TRUSTED = "myhost.local,192.168.1.50"
dsh web
```

## 8. 安全须知（务必阅读）⚠️

- 绑定 `0.0.0.0` 后，**局域网内任何设备都能打开这个 GUI 并驱动 Agent 执行命令**。这不是"只读监控"，是远程控制 + 远程代码执行。
- `/api` 的浏览器信任围栏只防 DNS 重绑定，**不是登录认证**。
- 请在可信网络（家庭 WiFi 等）使用；公共 WiFi 上不要开启。
- 敏感接口（设置、凭据、目录选择）从手机访问会 403——这是刻意的安全边界。
