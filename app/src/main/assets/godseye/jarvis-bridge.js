/*
 * POST bridge for the bundled God's Eye console inside the Jarvis WebView.
 *
 * Android's WebView request interception never sees a request body, so the
 * console's POSTs (Overpass queries for ALPR cameras / boundaries / map
 * features, the HUD summary, the key-setup save) reached Kotlin empty. This
 * wraps fetch() for those routes only and sends the body through
 * JarvisVoiceBridge.apiPost; Kotlin answers via window.__jarvisApiResolve.
 * Everything else goes to the real fetch untouched.
 *
 * Loaded first in <head> by GodsEyeActivity.bootstrappedIndexHtml().
 */
(function () {
  'use strict';
  if (window.__jarvisFetchBridge) return;
  window.__jarvisFetchBridge = true;

  var BRIDGED = [/^\/api\/overpass/, /^\/api\/openai\/hud-summary/, /^\/api\/hud-summary/, /^\/api\/setup\/keys/];
  var pending = {};
  var seq = 0;
  var nativeFetch = window.fetch.bind(window);

  window.__jarvisApiResolve = function (id, status, contentType, body) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    p.resolve(new Response(body, {
      status: status,
      headers: { 'Content-Type': contentType || 'application/json' }
    }));
  };

  function headerValue(headers, name) {
    if (!headers) return '';
    if (typeof headers.get === 'function') return headers.get(name) || '';
    for (var k in headers) {
      if (Object.prototype.hasOwnProperty.call(headers, k) && k.toLowerCase() === name) return headers[k];
    }
    return '';
  }

  window.fetch = function (input, init) {
    try {
      var bridge = window.JarvisVoiceBridge;
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      var u = new URL(url, location.href);
      var body = init && init.body;
      if (body instanceof URLSearchParams) body = body.toString();
      if (
        method === 'POST' && u.origin === location.origin &&
        bridge && typeof bridge.apiPost === 'function' &&
        (body == null || typeof body === 'string') &&
        BRIDGED.some(function (r) { return r.test(u.pathname); })
      ) {
        return new Promise(function (resolve, reject) {
          var id = 'p' + (++seq);
          var signal = init && init.signal;
          if (signal && signal.aborted) {
            reject(new DOMException('Aborted', 'AbortError'));
            return;
          }
          pending[id] = { resolve: resolve };
          if (signal) {
            signal.addEventListener('abort', function () {
              if (!pending[id]) return;
              delete pending[id];
              reject(new DOMException('Aborted', 'AbortError'));
            }, { once: true });
          }
          bridge.apiPost(id, u.pathname + u.search, body || '', headerValue(init && init.headers, 'content-type'));
        });
      }
    } catch (e) { /* fall through to the real fetch */ }
    return nativeFetch(input, init);
  };
})();
