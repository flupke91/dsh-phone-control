# dsh-android 📱

> [English](README.en.md)

`dsh-android` 是 DeepSeek Harness（DSH）的手机原生 App（Kotlin + Jetpack Compose），与 [dsh-phone-control](../README.md) 插件配套使用：在同一局域网内，用手机随时控制电脑上的 DSH——发消息、看流式回复、管理会话、监控后台任务。

## 功能

- **会话同步**：与 Web 工作区完全一致——只显示可见会话，已归档 / 空白 / 工作区外的会话永不出现
- **全功能对话**：发送消息（直接投递）→ Agent 实时流式回复；思考块（可折叠）、工具调用行、消息一键复制
- **会话管理**：长按删除（归档）、下拉刷新、打开即定位最新消息、运行状态徽标、搜索
- **任务看板**：实时显示当前会话的后台任务（运行中 / 停止中 / 完成 / 失败）
- **连接诊断**：🔍 一键诊断——网络连通 → 服务与围栏 → 工作区 → 会话同步，逐项显示结果
- **深色模式**：纯白文字 + 深色气泡，系统栏图标自适应
- **插件协作**：dsh-phone-control 会在 DSH 网页右下角显示连接地址横幅，IP 变化自动跟随

## 构建

```bash
# 需要 JDK 17+ / Android SDK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与连接

1. 电脑端安装 [dsh-phone-control](../README.md) 插件并重启 `dsh web`；防火墙放行 3080 端口
2. 手机安装 APK（见仓库 **Releases**），与电脑连接同一 WiFi / 热点
3. 打开 App → 输入电脑地址 `http://<电脑IP>:3080`（可直接看电脑网页右下角的横幅，或手机浏览器打开 `http://<电脑IP>:3080/phone`）
4. 点「连接」，或点「🔍 一键诊断」自查连接链路

## 目录

- `app/src/main/java/com/flupke/dsh/core/` — JSON-RPC 客户端、事件流（WebSocket mux）、连接管理
- `app/src/main/java/com/flupke/dsh/ui/` — Compose UI（会话列表 / 对话 / 设置 / 任务看板）
- `app/src/main/java/com/flupke/dsh/data/` — 数据模型
- `docs/` — 设计文档（仅保留公开可读部分）

## 许可证

MIT（与 dsh-phone-control 一致）
