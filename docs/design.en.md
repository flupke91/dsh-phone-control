# Design: dsh-phone-control

> [中文版](design.md)

Date: 2026-08-13 · Status: implemented (v0.2)

## Goal

Let phones/tablets on the same LAN control the DSH (DeepSeek Harness) web GUI on a PC directly from the browser, packaged as a reusable, open-source, zero-dependency DSH bundle. **Hard requirement: no matter how the LAN IP changes, session records must stay accessible when the phone visits the PC's IP.**

## Non-goals

- No authentication/login (the fence is DNS-rebinding protection only; use on trusted networks)
- No HTTPS termination (left to a reverse-proxy setup)
- No modification of DSH installed files
- No NAT/public access (that is port-forwarding territory)

## Architecture

One bundle patch layer + one tiny zero-dependency host plugin:

```
dsh-phone-control/
├── package.json        # dsh.bundle.patch manifest (bundle identity)
├── cordis.patch.yml    # patch layer: webserver bind + plugin row insert
├── lib/index.js        # host plugin: crypto polyfill + network watcher
└── scripts/netwatch-standalone.mjs  # standalone watcher (bridge for un-restarted instances)
```

### Component 1: cordis.patch.yml (config only)

- **webserver row override**: `host: 0.0.0.0` (the config-level escape hatch the CLI bans), `port` still follows `--port`.
- **plugin row insert**: `lan-phone-control` (`name: dsh-phone-control`, `inject: [webServer]`).

### Component 2: lib/index.js (zero dependencies)

1. **crypto polyfill**: `apply()` calls `ctx.webServer.tapIndex(transform)`; the transform injects a classic `<script>` into `<head>` — if `crypto.randomUUID` is missing, it synthesizes UUID v4 from `crypto.getRandomValues` (classic scripts run before the deferred app bundle).
2. **Network watcher (netwatch)**: polls `os.networkInterfaces()` every 5 s; when the IPv4 set changes, rewrites a **managed `connection` override** in the profile's user patch `cordis.patch.yml` (`trustedHosts: !!js ctx.webRuntime.trustedHosts.concat(['<current-IP>', ...])`). The user patch layer hot-reloads → the fence follows the new IP within seconds.

### Why a "managed user-layer override" and not the alternatives

| Approach | Verdict |
|---|---|
| Live Proxy array in the config value | ❌ schemastery validation rebuilds the Proxy into a plain array (verified) — dead |
| Inline interface lookup in a bundle-layer expression | ⚠️ evaluated only on (re)load; still stale after a network change; bundle-row re-evaluation on user-patch touch is not guaranteed |
| Managed user-layer override + poll-and-rewrite | ✅ user-layer hot reload is repeatedly proven (bind and fence both verified live); follows network changes in seconds with no restart |

### Data flow

```
Phone browser → http://<LAN_IP>:3080
  ├─ index.html (with polyfill, runs first) → crypto.randomUUID available
  ├─ /api/* (fence passes: trust list holds the current interface IPs, kept fresh by netwatch) → RPC works
  └─ ws://<LAN_IP>:3080/api/events.* (handshake passes) → session/event stream works
```

## Error handling

- Polyfill is wrapped in try/catch: it can never break the page;
- Transform is idempotent: skips if `id="dsh-phone-control-crypto-polyfill"` already present;
- Netwatch write failure (read-only file, etc.): logs a warning and skips (fence keeps the last state; a restart recovers);
- Managed-block writer: preserves user comments and unrelated entries, removes old `connection` entries (id line + config block), replaces the `[]` placeholder.

## Testing

- Expression unit test in the Loader evaluation environment (`new Function('ctx','expr','with(ctx){eval(expr)}')`);
- `dsh --profile web --patch <plugin> --dump-config` validates the patch composition (EXIT=0);
- Netwatch writer unit tests: empty file / `[]` / manual entries / idempotency / IP replacement / generated YAML accepted by dsh's parser;
- Polyfill unit tests: injection position, idempotency, UUID v4 format, uniqueness (node:vm browser sandbox);
- Real-device verification after installing into the web profile (see tutorial.en.md).

## Security

- Binding 0.0.0.0 = any LAN device can drive the agent (RCE capability); the tutorial warns prominently; trusted networks only;
- Privileged methods stay loopback-limited (settings/credentials 403 on the phone is by design);
- No data collection; zero dependencies; MIT.

## Decision log

1. **Do not patch the client bundle** (`mintRpcId`) → index polyfill instead: ships with the plugin, survives updates, touches no installed files.
2. **Do not hard-code IPs** → netwatch auto-syncs the managed user-layer override: adapts to network changes without restart.
3. **Zero dependencies** → only `node:os` / `node:fs` / `node:path` / `node:url` builtins plus injected services.
4. **Standalone watcher script** (scripts/netwatch-standalone.mjs) → bridge so un-restarted instances also follow IP changes automatically.
