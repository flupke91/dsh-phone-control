#!/usr/bin/env node
/**
 * dsh-phone-control — standalone LAN-IP watcher (bridge for un-restarted
 * instances).
 *
 * The plugin's built-in netwatch only runs after `dsh web` is restarted with
 * the bundle loaded. Until then, you can run this script in the background:
 * it polls the machine's network interfaces and rewrites the managed
 * `connection` override in the profile's cordis.patch.yml on change, so the
 * /api trust fence follows the current LAN IP without a restart.
 *
 * Usage:
 *   node scripts/netwatch-standalone.mjs [<profile-cordis-patch.yml>]
 *   # default path: ~/.dsh/profiles/web/cordis.patch.yml
 */
import { internals } from '../lib/index.js';
import { networkInterfaces } from 'node:os';
import { join } from 'node:path';
import { homedir } from 'node:os';

const patchFile = process.argv[2] ?? join(homedir(), '.dsh', 'profiles', 'web', 'cordis.patch.yml');

function currentIps() {
  return Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);
}

let last = '';
function sync() {
  const ips = currentIps();
  const key = ips.join(',');
  if (key === last) return;
  last = key;
  const ok = internals.writeManagedSection(ips, patchFile);
  const stamp = new Date().toISOString();
  if (ok) {
    console.log(`[dsh-phone-control] ${stamp} LAN IPs changed -> ${key || '(none)'} ; trust fence updated`);
  } else {
    console.error(`[dsh-phone-control] ${stamp} could not update ${patchFile}`);
  }
}

sync();
setInterval(sync, 5000);
console.log(`[dsh-phone-control] watcher started, managing ${patchFile}`);
