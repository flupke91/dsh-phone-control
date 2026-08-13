# dsh-phone-control 📱

> [中文版](README.md)

Control the [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (DSH) web GUI from your phone or tablet on the **same LAN** — no SSH tunnel, no port forwarding, no HTTPS setup.

## Why this plugin exists

Out of the box, `dsh web` only listens on `127.0.0.1` and the CLI **intentionally rejects** `--host 0.0.0.0` (the GUI can execute code on your machine). Even if you force a bind via config, two more blockers appear on a plain-HTTP LAN origin:

| Blocker | Symptom | This plugin |
|---|---|---|
| Loopback-only binding | Phone cannot connect at all | ✅ Patch binds `0.0.0.0` (the config-level escape hatch the CLI blocks) |
| Trust fence is a boot-time snapshot | After a network change, `/api` returns 403 and the session list goes empty | ✅ Network watcher rewrites the fence's trusted list within seconds (hot-reload, no restart) |
| `crypto.randomUUID` only exists in secure contexts | On a non-HTTPS LAN origin every RPC crashes → the session list is permanently empty | ✅ Injects a `getRandomValues`-based UUID polyfill into index.html |

See [docs/troubleshooting.en.md](docs/troubleshooting.en.md) for the full story and [docs/tutorial.en.md](docs/tutorial.en.md) for a step-by-step guide.

## Survives network changes

The plugin polls `os.networkInterfaces()` every 5 seconds. Whenever the machine's LAN IP changes (new router, hotspot, DHCP renewal), it rewrites a managed `connection` override in the profile's `cordis.patch.yml`; the user patch layer hot-reloads and the `/api` trust fence follows the new IP within seconds. **No restart, no manual edits.**

## Install

```bash
# 1. pnpm (required by `dsh plugin`)
npm install -g pnpm

# 2. Install the plugin into the web profile
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git

# 3. Restart `dsh web` so the new bundle loads
#    (Ctrl+C the old process, then run `dsh web` again)

# 4. Allow the port through Windows Firewall (admin PowerShell)
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

## Use

1. Find the PC's LAN IP: `ipconfig` (IPv4, e.g. `192.168.x.x`).
2. Put the phone on the **same WiFi**.
3. Phone browser: `http://<PC-LAN-IP>:3080`.

> 💡 After switching networks/routers: use the PC's new IP and refresh after a few seconds — no restart needed.

## Configuration

| Env var | Purpose |
|---|---|
| `DSH_PHONE_CONTROL_TRUSTED` | Extra trusted hosts, comma-separated (`host1,host2`), appended to the `/api` fence trust list |

## Security warning ⚠️

- Binding `0.0.0.0` exposes the harness — which can execute commands on your PC — to **every device on your LAN**. Treat it as remote code execution; only use on networks you trust.
- The `/api` browser-trust fence is **DNS-rebinding protection, not authentication**. There is no login.
- By design, privileged methods (`settings.*`, `credentials.*`, `host.pickDirectory`, …) stay **loopback-only**: from the phone they return 403. Chat, tools, jobs and the session list are unaffected.

## How it works

One bundle patch layer + one tiny zero-dependency host plugin:

1. **`cordis.patch.yml`** overrides the `webserver` row (`host: 0.0.0.0`).
2. **`lib/index.js`**:
   - registers an index transform (`webServer.tapIndex`) that injects a classic `<script>` polyfill for `crypto.randomUUID` — it runs before the deferred app bundle;
   - polls the machine's interfaces and, on LAN-IP change, rewrites a managed `connection` override in the profile's `cordis.patch.yml` so the `/api` trust fence always accepts the current IPs.

All mechanisms are official: the bundle patch layer, the index-tap hook, and the user patch layer. **No installed files are modified.**

## License

MIT
