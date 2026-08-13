# Design / 设计文档：dsh-phone-control

Date: 2026-08-13 · Status: implemented

## Goal / 目标

让同一局域网内的手机/平板能直接通过网页控制电脑上的 DSH（DeepSeek Harness）web GUI，并将方案做成可复用、可开源、零依赖的 DSH 插件 bundle。

## Non-goals / 非目标

- 不提供认证/登录（围栏只防 DNS 重绑定；信任网络内使用）
- 不做 HTTPS 终结（留给反向代理方案）
- 不修改任何 DSH 安装文件
- 不处理 NAT/公网访问（那是内网穿透的范畴）

## Architecture / 架构

一个 bundle 补丁层 + 一个微型 host 插件（零依赖），全部走官方机制：

```
dsh-phone-control/
├── package.json        # dsh.bundle.patch 清单（bundle 身份）
├── cordis.patch.yml    # 补丁层：webserver 绑定 + web-runtime 信任列表 + 插入插件行
└── lib/index.js        # host 插件：tapIndex 注入 crypto.randomUUID polyfill
```

### 组件 1：cordis.patch.yml（纯配置，无代码）

- **webserver 行覆盖**：`host: 0.0.0.0`（CLI 禁用的部署级逃生通道），`port` 仍跟随 `--port`。
- **web-runtime 行覆盖**：`trustedHosts` 用 Loader 的 `!!js` 表达式实时计算网卡 IPv4（`process.getBuiltinModule('node:os').networkInterfaces()`），外加 `DSH_PHONE_CONTROL_TRUSTED` 环境变量追加项。表达式在每次加载/热重载时重新求值 → 换网后不再依赖过期快照。
- **插入插件行**：`lan-crypto-polyfill`（`name: dsh-phone-control`，`inject: [webServer]`）。

### 组件 2：lib/index.js（约 50 行，零依赖）

- `apply(ctx)` 调用 `ctx.webServer.tapIndex(transform)` 注册 index 变换。
- transform 向 `<head>` 注入经典 `<script>` polyfill：若 `crypto.randomUUID` 缺失，用 `crypto.getRandomValues` 实现 UUID v4。经典脚本先于延迟执行的 app bundle 运行。

### 数据流

```
手机浏览器 → http://<LAN_IP>:3080
  ├─ index.html（含 polyfill，先执行）→ crypto.randomUUID 可用
  ├─ /api/*（围栏放行：trustedHosts 含当前网卡 IP）→ RPC 正常
  └─ ws://<LAN_IP>:3080/api/events.*（握手通过）→ 会话/事件流正常
```

## Error handling / 错误处理

- polyfill 自身 try/catch：注入失败绝不破坏页面；
- transform 幂等：`id="dsh-phone-control-crypto-polyfill"` 已存在则跳过；
- 表达式兜底：`filter(Boolean)` 去掉空项，无网卡时结果为空数组（不崩溃，仅回退到回环）。

## Testing / 测试

- 表达式在 Loader 求值环境（`new Function('ctx','expr','with(ctx){eval(expr)}')`）下的单测；
- `dsh --profile web --patch <plugin> --dump-config` 校验补丁组合（EXIT=0）；
- polyfill 注入单测：注入位置、幂等性、UUID v4 格式、唯一性（node:vm 模拟浏览器环境）；
- 真机验证：插件安装到 web profile 后手机端会话列表/对话正常（见 tutorial.md）。

## Security / 安全

- 绑定 0.0.0.0 = 局域网内任意设备可驱动 Agent（RCE 能力），教程显著警告，仅建议可信网络；
- 特权方法保持回环限制（settings/credentials 等手机 403 属设计）；
- 不采集任何数据；项目零依赖、MIT 协议。

## Decisions / 关键决策

1. **不直接改 client bundle**（`mintRpcId`）→ 改为 index polyfill：可随插件分发、更新不丢、不碰安装文件。
2. **信任列表不用写死 IP** → 用 `!!js` 表达式读网卡：换网自动适配（加载时），教程仍建议换网后重启兜底。
3. **零依赖** → 插件只用 `node:os` 内置模块与注入服务，安装无解析负担。
