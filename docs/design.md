# 设计文档：dsh-phone-control

> [English version](design.en.md)

日期：2026-08-13 · 状态：已实现（v0.2）

## 目标

让同一局域网内的手机/平板能直接通过网页控制电脑上的 DSH（DeepSeek Harness）web GUI，并将方案做成可复用、可开源、零依赖的 DSH 插件 bundle。**核心要求：无论局域网 IP 如何变化，手机访问电脑 IP 时会话记录必须始终可用。**

## 非目标

- 不提供认证/登录（围栏只防 DNS 重绑定；信任网络内使用）
- 不做 HTTPS 终结（留给反向代理方案）
- 不修改任何 DSH 安装文件
- 不处理 NAT/公网访问（那是内网穿透的范畴）

## 架构

一个 bundle 补丁层 + 一个零依赖微型 host 插件：

```
dsh-phone-control/
├── package.json        # dsh.bundle.patch 清单（bundle 身份）
├── cordis.patch.yml    # 补丁层：webserver 绑定 + 插入插件行
├── lib/index.js        # host 插件：crypto polyfill + 网卡监视器
└── scripts/netwatch-standalone.mjs  # 独立网卡监视器（未重启实例的过渡方案）
```

### 组件 1：cordis.patch.yml（纯配置）

- **webserver 行覆盖**：`host: 0.0.0.0`（CLI 禁用的部署级逃生通道），`port` 仍跟随 `--port`。
- **插入插件行**：`lan-phone-control`（`name: dsh-phone-control`，`inject: [webServer]`）。

### 组件 2：lib/index.js（零依赖）

1. **crypto polyfill**：`apply()` 调用 `ctx.webServer.tapIndex(transform)`；transform 向 `<head>` 注入经典 `<script>`——若 `crypto.randomUUID` 缺失，用 `crypto.getRandomValues` 实现 UUID v4（经典脚本先于延迟执行的 app bundle 运行）。
2. **网卡监视器（netwatch）**：每 5 秒轮询 `os.networkInterfaces()`；当 IPv4 集合变化时，重写 profile 用户补丁 `cordis.patch.yml` 中的**受管 `connection` 覆盖**（`trustedHosts: !!js ctx.webRuntime.trustedHosts.concat(['<当前IP>', ...])`）。用户补丁层由 Loader 热重载 → 围栏几秒内跟上新地址。

### 关键设计决策：为什么用"受管用户层覆盖"而不是其他方案

| 方案 | 结论 |
|---|---|
| 配置值里放"活的" Proxy 数组 | ❌ schemastery 校验会把 Proxy 重建为普通数组（实测），失效 |
| bundle 层表达式内联读网卡 | ⚠️ 只在加载/热重载时求值，换网后仍过期；且 bundle 行是否随用户层触发热重载语义不确定 |
| 受管用户层覆盖 + 轮询重写 | ✅ **用户层热重载被反复验证有效**（绑定/围栏均实测生效）；换网后几秒内自动同步，无需重启 |

### 数据流

```
手机浏览器 → http://<LAN_IP>:3080
  ├─ index.html（含 polyfill，先执行）→ crypto.randomUUID 可用
  ├─ /api/*（围栏放行：信任列表含当前网卡 IP，netwatch 自动维护）→ RPC 正常
  └─ ws://<LAN_IP>:3080/api/events.*（握手通过）→ 会话/事件流正常
```

## 错误处理

- polyfill 自身 try/catch：注入失败绝不破坏页面；
- transform 幂等：`id="dsh-phone-control-crypto-polyfill"` 已存在则跳过；
- netwatch 写入失败（文件只读等）：记录警告并跳过（围栏保持上次状态，重启兜底）；
- 受管块写入算法：保留用户注释与其他条目、删除旧的 `connection` 条目、替换 `[]` 占位、整条删除（id 行 + config 块）。

## 测试

- 表达式在 Loader 求值环境（`new Function('ctx','expr','with(ctx){eval(expr)}')`）下的单测；
- `dsh --profile web --patch <plugin> --dump-config` 校验补丁组合（EXIT=0）；
- netwatch 写入器单测：空文件/`[]`/含手工条目/幂等/IP 更换/生成的 YAML 可被 dsh 解析；
- polyfill 注入单测：注入位置、幂等性、UUID v4 格式、唯一性（node:vm 模拟浏览器环境）；
- 真机验证：插件安装到 web profile 后手机端会话列表/对话正常（见 tutorial.md）。

## 安全

- 绑定 0.0.0.0 = 局域网内任意设备可驱动 Agent（RCE 能力），教程显著警告，仅建议可信网络；
- 特权方法保持回环限制（settings/credentials 等手机 403 属设计）；
- 不采集任何数据；项目零依赖、MIT 协议。

## 关键决策记录

1. **不直接改 client bundle**（`mintRpcId`）→ 改为 index polyfill：可随插件分发、更新不丢、不碰安装文件。
2. **信任列表不写死 IP** → 网卡监视器自动同步受管用户层覆盖：换网自动适配，无需重启。
3. **零依赖** → 插件只用 `node:os`/`node:fs`/`node:path`/`node:url` 内置模块与注入服务，安装无解析负担。
4. **独立监视脚本**（scripts/netwatch-standalone.mjs）→ 作为"尚未重启激活 bundle"时的过渡方案，保证旧实例也能自动跟随 IP。
