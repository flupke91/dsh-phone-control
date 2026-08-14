/**
 * dsh-phone-control — host-side plugin.
 *
 * Two responsibilities:
 *
 * 1. crypto.randomUUID polyfill
 *    Browsers only expose Crypto.randomUUID() in secure contexts. A
 *    plain-HTTP LAN origin (http://192.168.x.x:PORT) is NOT a secure context,
 *    so on the phone/tablet every RPC in the web client threw
 *    "TypeError: crypto.randomUUID is not a function", the connection loop
 *    aborted its own WebSockets (close 1006), and the GUI stayed stuck on an
 *    empty session list. crypto.getRandomValues() IS available in insecure
 *    contexts, so we synthesize a UUID v4 from it. The polyfill is injected
 *    as a classic <script> in <head>, which runs before the deferred app
 *    bundle (index transform via webServer.tapIndex).
 *
 * 2. LAN IP netwatch
 *    The /api browser-trust fence only accepts Host headers on its trusted
 *    list, and that list is materialized when the composition (re)loads. If
 *    the PC's WiFi IP changes (new router, hotspot, DHCP renewal) after a
 *    reload, LAN clients start getting 403 and the session list goes empty.
 *    This plugin polls os.networkInterfaces() and, on change, rewrites a
 *    managed `connection` override in the profile's own cordis.patch.yml.
 *    The user patch layer is hot-reloaded by the Loader, so the fence follows
 *    the new IP within seconds — no restart, no manual edits.
 *
 * The row mounts with `inject: [webServer]`.
 */

import { networkInterfaces } from 'node:os';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const name = 'dsh-phone-control';
const inject = ['webServer'];

const POLYFILL_ID = 'dsh-phone-control-crypto-polyfill';
const BANNER_ID = 'dsh-phone-control-banner';
const GUIDE_PATH = '/api/phone-guide';
const GUIDE_PAGE = '/phone';

const POLYFILL_SCRIPT = `<script id="${POLYFILL_ID}">/* dsh-phone-control: crypto.randomUUID polyfill for non-secure (plain-HTTP LAN) origins */
(function () {
  var c = window.crypto;
  if (!c || typeof c.randomUUID === 'function') return;
  try {
    c.randomUUID = function () {
      var b = c.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      var h = '';
      for (var i = 0; i < 16; i++) {
        h += (b[i] < 16 ? '0' : '') + b[i].toString(16);
        if (i === 3 || i === 5 || i === 7 || i === 9) h += '-';
      }
      return h;
    };
  } catch (e) { /* never break the page over a polyfill */ }
})();
</script>`;

/**
 * Floating banner showing the phone connection address in the web GUI.
 * Pulls the current LAN IPs from the plugin's /api/phone-guide endpoint so
 * the address always matches the machine's current network. Dismissible
 * (persisted in localStorage).
 */
const BANNER_SCRIPT = `<script id="${BANNER_ID}">
(function () {
  var KEY = 'dsh-phone-control-banner-dismissed';
  try { if (localStorage.getItem(KEY)) return; } catch (e) {}
  var host = location.host;
  fetch('${GUIDE_PATH}', { headers: { 'x-dsh-phone-control': '1' } })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (g) {
      if (!g || !g.ips || g.ips.length === 0) return;
      var ip = g.ips[0];
      var url = 'http://' + ip + ':' + g.port;
      var el = document.createElement('div');
      el.id = '${BANNER_ID}-chip';
      el.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:9999;background:#4176E6;color:#fff;font:12px/1.4 system-ui,sans-serif;padding:8px 12px;border-radius:10px;box-shadow:0 2px 10px rgba(0,0,0,.25);max-width:min(320px,70vw);cursor:pointer;';
      el.innerHTML = '<b>📱 dsh App 连接</b><br>http://' + ip + ':' + g.port +
        '<br><span style="opacity:.85">在手机 dsh App 中输入此地址；IP 变化自动跟随</span>';
      el.title = '点击关闭';
      el.addEventListener('click', function () {
        try { localStorage.setItem(KEY, '1'); } catch (e) {}
        el.remove();
      });
      document.body.appendChild(el);
    })
    .catch(function () {});
})();
</script>`;

/** All non-internal IPv4 addresses of this machine, as a stable string key. */
function lanIps() {
  return Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);
}

/** Connection guide payload: current IPs + port + ready-to-enter URL. */
function guidePayload(ctx) {
  const ips = lanIps();
  const port = ctx.webServer?.listenedPort ?? 3080;
  return {
    ips,
    port,
    url: ips.length > 0 ? `http://${ips[0]}:${port}` : null,
    app: 'dsh-android',
  };
}

/** The profile directory this plugin is installed into (…/profiles/<name>). */
function profileDir() {
  return resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
}

/** The profile's user patch file path. */
function userPatchPath() {
  return join(profileDir(), 'cordis.patch.yml');
}

const MANAGED_START = '# ---- dsh-phone-control managed block (auto-updated; remove to disable) ----';
const MANAGED_END = '# ---- end dsh-phone-control managed block ----';

/**
 * Build the managed `connection` override for the current IP set.
 * Preserves CLI `--trusted-host` extras via ctx.webRuntime.trustedHosts and
 * DSH_PHONE_CONTROL_TRUSTED environment entries.
 */
function managedBlock(ips) {
  const extras = (process.env.DSH_PHONE_CONTROL_TRUSTED || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const quoted = [...ips, ...extras].map((ip) => `'${ip}'`);
  return [
    MANAGED_START,
    `- id: connection`,
    `  config:`,
    `    trustedHosts: !!js ctx.webRuntime.trustedHosts.concat([${quoted.join(', ')}])`,
    MANAGED_END,
    '',
  ].join('\n');
}

/**
 * Rewrite the profile's cordis.patch.yml so the fence trusts the current IPs.
 * Preserves user comments and non-`connection` entries; drops any previous
 * managed block (idempotent) and a bare `[]` placeholder.
 */
function writeManagedSection(ips, file = userPatchPath()) {
  let content;
  try {
    content = readFileSync(file, 'utf8');
  } catch {
    return false; // no user patch file — nothing to manage
  }
  const lines = content.split(/\r?\n/);
  const kept = [];
  let inManaged = false;
  let skipEntry = false;
  for (const line of lines) {
    if (line.trim() === MANAGED_START) { inManaged = true; continue; }
    if (line.trim() === MANAGED_END) { inManaged = false; continue; }
    if (inManaged) continue;
    const m = line.match(/^\s*- id:\s*(\S+)/);
    if (m) {
      // drop the whole previous managed/manual `connection` entry (id line + its config block)
      skipEntry = m[1] === 'connection';
      if (skipEntry) continue;
    }
    if (skipEntry) continue;
    if (line.trim() === '[]') continue; // drop the empty placeholder
    kept.push(line);
  }
  while (kept.length > 0 && kept[kept.length - 1].trim() === '') kept.pop();
  const next = [...kept, managedBlock(ips)].join('\n').trimEnd() + '\n';
  try {
    writeFileSync(file, next, 'utf8');
    return true;
  } catch {
    return false;
  }
}

const POLL_INTERVAL_MS = 5000;

/**
 * @param ctx - plugin context carrying the webServer service.
 */
function apply(ctx) {
  ctx.webServer.tapIndex((html) => {
    if (html.includes(`id="${POLYFILL_ID}"`)) return html;
    return html.replace('<head>', `<head>${POLYFILL_SCRIPT}${BANNER_SCRIPT}`);
  });

  // 连接引导接口：手机 App / 引导页读取当前局域网地址（IP 变化自动跟随）。
  ctx.webServer.register({
    kind: 'exact',
    path: GUIDE_PATH,
    handler: (req, res) => {
      const data = guidePayload(ctx);
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' });
      res.end(JSON.stringify(data));
    },
  });

  // 手机引导页：打开 http://<ip>:3080/phone 即可看到连接步骤。
  ctx.webServer.register({
    kind: 'exact',
    path: GUIDE_PAGE,
    handler: (req, res) => {
      const g = guidePayload(ctx);
      const url = g.url ?? 'http://<电脑IP>:3080';
      const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>dsh App 连接</title><body style="font-family:system-ui,sans-serif;background:#0F1115;color:#fff;margin:0;padding:24px">
<h2 style="margin:0 0 8px">📱 dsh App 连接电脑</h2>
<p style="opacity:.8;margin:0 0 16px">在手机 dsh App 的「主机地址」中输入：</p>
<p style="font-size:22px;font-weight:700;background:#25375F;padding:14px;border-radius:12px;word-break:break-all">${url}</p>
<p style="opacity:.7;font-size:13px;margin-top:16px">要求：手机与电脑在同一 WiFi / 热点；电脑防火墙放行 3080 端口；IP 变化后本页自动更新。</p>
<p style="opacity:.7;font-size:13px">本页地址：<span style="user-select:all">http://<script>document.write(location.host)</script>/phone</span></p>
</body></html>`;
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(html);
    },
  });

  // LAN IP netwatch: keep the /api trust fence following the machine's IPs.
  let last = '';
  const sync = () => {
    const now = lanIps().join(',');
    if (now === last) return;
    last = now;
    const ok = writeManagedSection(lanIps());
    if (ok) {
      ctx.logger?.info?.('[dsh-phone-control] LAN IPs changed, trust fence updated: ' + (now || '(none)'));
    } else {
      ctx.logger?.warn?.('[dsh-phone-control] could not update trust fence (cordis.patch.yml not writable)');
    }
  };
  sync(); // ensure the fence matches the current IPs right after boot
  const timer = setInterval(sync, POLL_INTERVAL_MS);
  ctx.effect(() => {
    clearInterval(timer);
  }, 'dsh-phone-control: lan-ip watch');
}

const internals = { lanIps, managedBlock, writeManagedSection, userPatchPath };

export { apply, inject, internals, name };
