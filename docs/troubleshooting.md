# 排障经验：局域网手机访问 DSH 失败的全过程

> [English version](troubleshooting.en.md)

> 本文记录了一次真实排障的完整经过（信息已脱敏）：手机与电脑在同一局域网，但手机打开 `http://127.0.0.1:3080` 无效，改成电脑局域网 IP 后页面能开、会话列表却一直是空的。最终定位到 **4 个叠加的根因**，其中一个是 DSH 前端自身的 bug。这份经验沉淀成了本插件。

## 症状链条

```
手机浏览器打开 http://127.0.0.1:3080   → 连不上（回环地址只指向手机自己）
手机浏览器打开 http://192.168.x.x:3080 → 页面能打开，但会话列表永远为空
```

## 根因 1：`127.0.0.1` 是回环地址

`127.0.0.1` 永远指向"发出请求的设备自己"。手机输入 `http://127.0.0.1:3080` 是在问手机自己，跟电脑无关。必须用电脑的局域网 IP（`ipconfig` 查看）。

## 根因 2：DSH 默认只监听回环，且 CLI 禁止 `--host 0.0.0.0`

`dsh web` 的 webserver 默认 `host: 127.0.0.1`。用 `netstat -ano | findstr :3080` 确认：

```
TCP  127.0.0.1:3080  ...  LISTENING
```

CLI 层**故意拒绝** `--host 0.0.0.0`（源码注释原话大意）："出于安全考虑暂不支持绑定所有接口，会把远程代码执行暴露给网络"。但**配置层（补丁）是逃生通道**——把 webserver 行的 `host` 覆盖为 `0.0.0.0` 即可，且 DSH 的补丁支持热重载（修改 profile 的 `cordis.patch.yml` 后无需重启，Loader 会重新应用并重绑端口）。

## 根因 3：信任围栏（browser-trust fence）是启动快照，换网即过期

DSH 的 `/api` 有一个"浏览器信任围栏"（防 DNS 重绑定）：只接受回环地址 + 显式信任列表里的 Host。信任列表来自 `networkInterfaces()` 的**启动时快照**——电脑换网络（IP 变了）后，新 IP 不在列表里，`/api` 全部返回 403：

```
# 换网前
curl -H "Host: 192.168.0.x:3080" http://127.0.0.1:3080/api/events.mux  → 426（通过）
# 换网后（IP 变成 10.39.215.y）
curl -H "Host: 10.39.215.y:3080" http://127.0.0.1:3080/api/events.mux  → 403（拒绝）
```

**静态页面不受围栏限制**（所以"页面能打开"），但所有 API 调用被拒 → 会话列表空。

### 经验
- 诊断围栏用 `curl -H "Host: <ip>:<port>" http://127.0.0.1:<port>/api/events.mux`：`426` = 通过，`403` = 被拒。
- 围栏是防 DNS 重绑定，**不是认证**。绑定 0.0.0.0 后局域网内任何设备都能调用非特权 API。
- 特权方法（`settings.*`、`credentials.*`、`host.pickDirectory` 等）被额外钉死在回环：手机上这些请求 403 是设计行为。

## 根因 4（真正的坑）：`crypto.randomUUID` 只在"安全上下文"可用

围栏修好后，页面依旧空列表。用无头浏览器（Playwright）以局域网地址打开页面复现：

- 控制台：`WebSocket connection to ws://192.168.x.x:3080/api/events.mux failed: WebSocket is closed before the connection is established`，无限重试；
- 会话列表从不请求 `session.list`；
- 手动 `new WebSocket(...)` 却秒开；curl 升级握手也返回 101——**传输层完全正常**。

用猴子补丁钩住 `WebSocket.prototype.close` 抓堆栈：socket 是被**应用自己**在 `ConnectionController.loop` 的 catch 块里 `ac.abort()` 关掉的（1006）。再钩住 `Promise.all` 抓到被吞掉的原始错误：

```
TypeError: crypto.randomUUID is not a function
    at WebApiClient.mintRpcId (client.js)
    at WebApiClient.callUnary (client.js)
    at Object.describe (client.js)
```

**原因**：`crypto.randomUUID` 只在安全上下文（HTTPS 或 localhost）可用；`http://192.168.x.x` 不是安全上下文，所以 `crypto.randomUUID` 是 `undefined`。`mintRpcId()` 无条件调用它 → 每次 RPC 都抛 TypeError → 连接循环的握手（`host.describe`）崩溃 → 应用主动掐断自己的 WS → 无限重试 → 列表永远为空。而 `http://127.0.0.1` 是安全上下文，所以电脑上一切正常。

```js
// 验证
isSecureContext        // 127.0.0.1 → true；192.168.x.x → false
typeof crypto.randomUUID      // 127.0.0.1 → "function"；192.168.x.x → "undefined"
typeof crypto.getRandomValues // 两种环境都是 "function"  ← 修复抓手
```

### 修复
用 `crypto.getRandomValues` 实现 UUID v4 兜底。两种落法：

1. **补丁式**（本插件的方案）：往 index.html 的 `<head>` 注入一个经典 `<script>` polyfill（在延迟执行的 app bundle 之前运行）——通过 `webServer.tapIndex()` 官方机制，不改任何安装文件；
2. **改包式**：直接改 client bundle 的 `mintRpcId`（会被版本更新覆盖，不推荐）。

## 附加干扰项：网络抖动与超大历史

- 排障期间电脑的 WiFi IP 在多个网段间反复横跳（DHCP/换网），导致"时好时坏"的假象。**换网络后必须重新确认 IP，并让信任列表重算。**
- 会话历史是单次下载的大 JSON（本例约 10 MB），弱网下 `session.history` 超时表现为"聊天记录加载不出来"，与本次 bug 无关，换稳定网络即可。

## 本插件的解法（v0.2）

1. 补丁绑定 `0.0.0.0`；
2. **网卡监视器**：每 5 秒检查网卡，IP 变化时自动把当前 IP 写入 profile 用户补丁的受管 `connection` 覆盖 → 用户层热重载 → 围栏几秒内跟上新地址，**不再需要手动改 IP 或重启**；
3. **crypto polyfill**：通过 index 变换注入 `getRandomValues` 版 UUID v4。

## 诊断工具箱（速查）

| 检查 | 命令 |
|---|---|
| 监听地址 | `netstat -ano \| findstr :3080`（应为 `0.0.0.0:3080`）|
| 围栏（LAN Host） | `curl -H "Host: <ip>:3080" http://127.0.0.1:3080/api/events.mux`（426=通过/403=拒绝）|
| 会话列表 API | `curl -X POST -H "Content-Type: application/json" -d '{"type":"client-request","rpcId":"t","method":"session.list","payload":{}}' http://127.0.0.1:3080/api/session.list` |
| 安全上下文 | 浏览器控制台 `isSecureContext`、`typeof crypto.randomUUID` |
| WS 主动关闭 | 钩住 `WebSocket.prototype.close` 看调用栈（区分应用自断 vs 网络断）|
| 被吞的错误 | 钩住 `Promise.all`（`p.catch(e => log(e.stack))`）抓被 catch 静默吞掉的异常 |

## 给 DSH 上游的建议

- `mintRpcId()` 应优先 `crypto.randomUUID`、回退 `crypto.getRandomValues` 手写 UUID，避免在非安全上下文（局域网明文 HTTP）崩溃；
- `host.describe` 等握手调用应容忍非安全上下文；
- 信任围栏的 LAN 地址快照建议跟随网卡变化（或文档明示"换网需重启"）。
