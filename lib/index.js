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

/** All non-internal IPv4 addresses of this machine, as a stable string key. */
function lanIps() {
  return Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);
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
    return html.replace('<head>', `<head>${POLYFILL_SCRIPT}`);
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
