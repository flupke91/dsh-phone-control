# dsh-android 📱

> [中文](README.md)

`dsh-android` is the native Android app (Kotlin + Jetpack Compose) companion for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), working together with the [dsh-phone-control](../README.en.md) plugin: on the same LAN, control the DSH on your PC from your phone — send messages, watch streaming replies, manage sessions, monitor background jobs.

## Features

- **Session sync**: identical to the Web workspace — only visible sessions; archived / blank / out-of-workspace sessions never appear
- **Full conversation**: send messages (direct delivery) → Agent streams replies live; collapsible think blocks, tool-call rows, one-tap copy per message
- **Session management**: long-press to delete (archive), pull-to-refresh, opens at the latest message, running badges, search
- **Task board**: live background jobs of the current session (running / stopping / completed / failed)
- **Connection diagnostics**: 🔍 one-tap check — network reachability → service & fence → workspace → session sync
- **Dark mode**: pure-white text + dark bubbles, adaptive system-bar icons
- **Plugin synergy**: dsh-phone-control shows a connection-address banner in the DSH web UI; IP changes auto-follow

## Build

```bash
# JDK 17+ / Android SDK required
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Install & Connect

1. Install the [dsh-phone-control](../README.en.md) plugin on the PC and restart `dsh web`; allow TCP 3080 through the firewall
2. Install the APK on your phone (see repository **Releases**), join the same WiFi / hotspot as the PC
3. Open the app → enter the PC address `http://<PC-IP>:3080` (see the banner in the web UI, or open `http://<PC-IP>:3080/phone` on the phone)
4. Tap Connect, or tap 🔍 Diagnostics to self-check the connection chain

## Layout

- `app/src/main/java/com/flupke/dsh/core/` — JSON-RPC client, event stream (WebSocket mux), connection manager
- `app/src/main/java/com/flupke/dsh/ui/` — Compose UI (sessions / conversation / settings / task board)
- `app/src/main/java/com/flupke/dsh/data/` — data models
- `docs/` — design docs (public-safe subset only)

## License

MIT (same as dsh-phone-control)
