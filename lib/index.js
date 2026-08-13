/**
 * dsh-phone-control — host-side plugin.
 *
 * Injects a crypto.randomUUID polyfill into the served index.html.
 *
 * Why: browsers only expose Crypto.randomUUID() in secure contexts. A
 * plain-HTTP LAN origin (http://192.168.x.x:PORT) is NOT a secure context,
 * so on the phone/tablet every RPC in the web client threw
 * "TypeError: crypto.randomUUID is not a function", the connection loop
 * aborted its own WebSockets (close 1006), and the GUI stayed stuck on an
 * empty session list. crypto.getRandomValues() IS available in insecure
 * contexts, so we synthesize a UUID v4 from it. The polyfill is injected as a
 * classic <script> in <head>, which runs before the deferred app bundle.
 *
 * The row mounts with `inject: [webServer]` and registers an index transform
 * through the webServer service; the frontend-static fallback applies index
 * taps on every index.html render.
 */

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

/**
 * @param ctx - plugin context carrying the webServer service.
 */
function apply(ctx) {
  ctx.webServer.tapIndex((html) => {
    if (html.includes(`id="${POLYFILL_ID}"`)) return html;
    return html.replace('<head>', `<head>${POLYFILL_SCRIPT}`);
  });
}

export { apply, inject, name };
