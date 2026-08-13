# Troubleshooting: why LAN phones couldn't reach the DSH web GUI

> [中文版](troubleshooting.md)

> This is the sanitized write-up of a real debugging session: a phone and a PC on the same LAN, where `http://127.0.0.1:3080` on the phone obviously failed, and even with the PC's LAN IP the page opened but the session list stayed permanently empty. Four stacked root causes were found — including a genuine bug in the DSH frontend. This experience became this plugin.

## Symptom chain

```
http://127.0.0.1:3080 on the phone   → cannot connect (loopback means the phone itself)
http://192.168.x.x:3080 on the phone → page opens, but the session list is forever empty
```

## Root cause 1: `127.0.0.1` is the loopback address

`127.0.0.1` always refers to the device making the request. The phone asking `http://127.0.0.1:3080` is asking itself. Use the PC's LAN IP (`ipconfig`).

## Root cause 2: DSH binds loopback only, and the CLI bans `--host 0.0.0.0`

`dsh web`'s webserver defaults to `host: 127.0.0.1` (check with `netstat -ano | findstr :3080`). The CLI **intentionally rejects** `--host 0.0.0.0`: "it would expose remote code execution to the network". But the **config layer (patch) is the escape hatch** — overriding the webserver row's `host` to `0.0.0.0` works, and DSH hot-reloads patch files (no restart needed; the Loader re-applies and re-binds the port).

## Root cause 3: the browser-trust fence is a boot-time snapshot

The `/api` fence (DNS-rebinding protection) only accepts loopback Hosts plus an explicit trusted list. The list is snapshotted from `networkInterfaces()` at (re)load time — after the PC changes networks, the new IP is not on the list and `/api` returns 403:

```
curl -H "Host: 192.168.0.x:3080" http://127.0.0.1:3080/api/events.mux  → 426 (pass)
curl -H "Host: 10.39.215.y:3080"  http://127.0.0.1:3080/api/events.mux  → 403 (rejected)
```

Static pages are **not** fenced (the page opens), but every API call is rejected → empty session list.

Notes:
- Diagnose with `curl -H "Host: <ip>:<port>" http://127.0.0.1:<port>/api/events.mux`: 426 = pass, 403 = rejected.
- The fence is DNS-rebinding protection, **not authentication**.
- Privileged methods (`settings.*`, `credentials.*`, `host.pickDirectory`, …) are additionally pinned to loopback: 403 on the phone is by design.

## Root cause 4 (the real trap): `crypto.randomUUID` only exists in secure contexts

After fixing the fence, the list was still empty. Reproduced with a headless browser (Playwright) opening the LAN address:

- Console: `WebSocket connection to ws://192.168.x.x:3080/api/events.mux failed: WebSocket is closed before the connection is established`, retrying forever;
- `session.list` is never requested;
- A manual `new WebSocket(...)` opens instantly; a curl upgrade returns 101 — **the transport layer is fine**.

Hooking `WebSocket.prototype.close` showed the sockets were closed by the app itself (`ac.abort()` in the `ConnectionController.loop` catch block, close 1006). Hooking `Promise.all` surfaced the swallowed original error:

```
TypeError: crypto.randomUUID is not a function
    at WebApiClient.mintRpcId (client.js)
    at WebApiClient.callUnary (client.js)
    at Object.describe (client.js)
```

Why: `crypto.randomUUID` is only available in secure contexts (HTTPS or localhost); `http://192.168.x.x` is **not** a secure context, so `crypto.randomUUID` is `undefined`. `mintRpcId()` calls it unconditionally → every RPC throws → the readiness handshake (`host.describe`) dies → the app aborts its own WebSockets → infinite retry → the list is never populated. On `http://127.0.0.1` (a secure context) everything works.

```js
isSecureContext        // 127.0.0.1 → true ; 192.168.x.x → false
typeof crypto.randomUUID      // 127.0.0.1 → "function" ; 192.168.x.x → "undefined"
typeof crypto.getRandomValues // "function" in both   ← the fix
```

Fix: synthesize UUID v4 from `crypto.getRandomValues`. Two ways:

1. **Polyfill injection** (this plugin): inject a classic `<script>` into index.html `<head>` via the official `webServer.tapIndex()` hook — runs before the deferred app bundle, touches no installed files;
2. **Patching the bundle** (not recommended): edit `mintRpcId` in the client bundle — lost on every update.

## Additional distractions: network flapping and huge history

- During debugging the PC's WiFi IP jumped across several subnets (DHCP/network switches), producing an intermittent "sometimes works" illusion. **After any network change, re-check the IP and let the trust list recompute.**
- Session history downloads as one large JSON (~10 MB in this case); on weak networks `session.history` times out and the chat "won't load" — unrelated to this bug, fixed by a more stable network.

## How this plugin solves it (v0.2)

1. Patch binds `0.0.0.0`;
2. **Network watcher**: polls interfaces every 5 s and, on IP change, rewrites a managed `connection` override in the profile's user patch → user-layer hot reload → the fence follows the new IP within seconds — no manual edits, no restart;
3. **crypto polyfill** via the index-tap hook.

## Diagnostic toolkit (quick reference)

| Check | Command |
|---|---|
| Listen address | `netstat -ano \| findstr :3080` (should be `0.0.0.0:3080`) |
| Fence (LAN Host) | `curl -H "Host: <ip>:3080" http://127.0.0.1:3080/api/events.mux` (426 = pass / 403 = rejected) |
| Session list API | `curl -X POST -H "Content-Type: application/json" -d '{"type":"client-request","rpcId":"t","method":"session.list","payload":{}}' http://127.0.0.1:3080/api/session.list` |
| Secure context | In the browser console: `isSecureContext`, `typeof crypto.randomUUID` |
| Who closed the WS | Hook `WebSocket.prototype.close` and inspect the stack (app-aborted vs network) |
| Swallowed error | Hook `Promise.all` (`p.catch(e => log(e.stack))`) to surface errors silently eaten by `catch {}` |

## Suggestions for DSH upstream

- `mintRpcId()` should prefer `crypto.randomUUID` and fall back to a `crypto.getRandomValues`-based UUID so non-secure (plain-HTTP LAN) contexts don't crash;
- Readiness handshakes such as `host.describe` should tolerate non-secure contexts;
- The fence's LAN-address snapshot should follow interface changes (or document "restart after changing networks").
