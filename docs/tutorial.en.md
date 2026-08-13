# Tutorial: control your PC's DSH from your phone

> [中文版](tutorial.md)

This guide walks you through installing `dsh-phone-control` and using the DSH web GUI from your phone, end to end. Assumes a Windows PC running `dsh web` and a phone on the same WiFi.

## 0. Prerequisites

| Item | Requirement |
|---|---|
| PC | Windows, with `dsh web` running (`http://127.0.0.1:3080` opens) |
| Phone | On the **same WiFi** as the PC |
| Privilege | One admin PowerShell for the firewall rule |

## 1. Install pnpm (once)

`dsh plugin` relies on pnpm to manage plugin dependencies:

```powershell
npm install -g pnpm
pnpm --version   # confirm it prints a version
```

## 2. Install the plugin

```powershell
dsh plugin --profile web add https://github.com/flupke91/dsh-phone-control.git
```

This adds a dependency to `~/.dsh/profiles/web/package.json` and appends `dsh-phone-control` to `dsh.profile.bundles`.

## 3. Restart `dsh web`

The bundle list is read at boot, so one restart is required:

- Stop the running `dsh web` (Ctrl+C in its terminal).
- Run `dsh web` again.

The startup log now shows a LAN URL:

```
dsh web: http://127.0.0.1:3080 (LAN: http://192.168.x.x:3080)
```

## 4. Allow the port through Windows Firewall (admin PowerShell)

```powershell
netsh advfirewall firewall add rule name="dsh-web-3080" dir=in action=allow protocol=TCP localport=3080
```

> In a normal PowerShell this fails with "requires elevation" — reopen as administrator. If Windows shows a "Windows Security Alert" when the server first listens on 0.0.0.0, click "Allow access" (private networks) — same effect.

## 5. Use from your phone

1. Find the PC's LAN IP: `ipconfig` → WLAN adapter → IPv4 (e.g. `192.168.x.x`).
2. Put the phone on the **same WiFi**.
3. Phone browser: `http://192.168.x.x:3080` (replace with the real IP).

The first launch shows a "内测声明" (beta notice) dialog — click "继续".

## 6. FAQ

**Q1: The page opens but the session list is empty / spinner forever?**
- Check `http://127.0.0.1:3080` on the PC first. If that works, the issue is the fence/network layer:
  1. Make sure phone and PC are on the same subnet (the IP in the phone's address bar must match `ipconfig`).
  2. The plugin syncs the fence trust list every 5 seconds — **after a network change, wait a few seconds and refresh the phone**. No restart needed.
  3. If the plugin was just installed and `dsh web` hasn't been restarted yet, restart once to activate it.
- Cached old index.html? Hard-refresh (clear the phone browser cache or use a private tab).

**Q2: The "settings" page returns 403 on the phone?**
Expected. Privileged methods (`settings.*`, `credentials.*`, …) are loopback-only by design. Chat, tools, jobs and the session list are unaffected.

**Q3: Chat history never finishes loading?**
History is downloaded as one large JSON (can be tens of MB); weak or flaky WiFi can time it out. Use a more stable network, then reopen the session.

**Q4: After switching WiFi/router the phone can't connect?**
- The PC's IP changed: use the new IP (`ipconfig`).
- The plugin **automatically** syncs the new IP to the trust fence (within ~5 s); refresh the phone — **no restart required**.
- If it still fails after a few seconds, check the router for "AP/client isolation".

**Q5: How do I uninstall?**

```powershell
dsh plugin --profile web remove dsh-phone-control
# Optionally remove the block marked "dsh-phone-control managed block"
# from ~/.dsh/profiles/web/cordis.patch.yml
# Restart dsh web to finish
```

## 7. Advanced: extra trusted hosts

```powershell
$env:DSH_PHONE_CONTROL_TRUSTED = "myhost.local,192.168.1.50"
dsh web
```

## 8. Security (read this) ⚠️

- Binding `0.0.0.0` lets **any device on the LAN** open this GUI and drive the agent — that is remote control plus remote code execution, not read-only monitoring.
- The `/api` browser-trust fence is DNS-rebinding protection, **not authentication**.
- Only enable this on networks you trust (home WiFi, etc.). Not on public WiFi.
- Sensitive endpoints (settings, credentials, directory picking) return 403 from the phone — that is an intentional security boundary.
