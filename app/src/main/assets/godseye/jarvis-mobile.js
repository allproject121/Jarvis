/*
 * Jarvis phone layout for the bundled God's Eye console.
 *
 * The upstream console is a desktop cockpit: two stacks of panels down the
 * sides, a HUD in every corner and a command dock. On a portrait phone those
 * stack on top of each other and cover most of the globe. Below 760 px this
 * keeps the globe full screen and moves every panel behind one chip bar at the
 * bottom. A chip opens that panel as a bottom sheet (one at a time) through the
 * panel's own collapse button, so the console's own logic still runs.
 *
 * The dock trays (Location, Visual presets) size themselves for a desktop dock,
 * so on a phone they get small sheets of their own (Places, Style) whose
 * buttons press the console's real ones.
 *
 * Loaded from <head> by GodsEyeActivity.bootstrappedIndexHtml(). Styling lives
 * in jarvis-mobile.css under html.jv-phone.
 */
(function () {
  'use strict';
  if (window.__jarvisMobileLayout) return;
  window.__jarvisMobileLayout = true;

  var PHONE_QUERY = '(max-width: 760px)';

  // Chip order = what people reach for most on a phone first.
  // proxy: a Jarvis-built sheet instead of an upstream panel.
  var PANELS = [
    { id: 'data-panel', label: 'Layers', icon: 'layers' },
    { id: 'jv-places', label: 'Places', icon: 'pin', proxy: true },
    { id: 'cctv-panel', label: 'CCTV', icon: 'videocam' },
    { id: 'scene-panel', label: 'Scenes', icon: 'movie' },
    { id: 'jv-style', label: 'Style', icon: 'palette', proxy: true },
    { id: 'pp-toggles', label: 'Display', icon: 'tune' },
    { id: 'street-level-panel', label: 'Street', icon: 'walk' },
    { id: 'global-context-panel', label: 'Context', icon: 'public' },
    { id: 'weather-panel', label: 'Weather', icon: 'cloud', onlyWhenShown: true },
    { id: 'recent-imagery-panel', label: 'Imagery', icon: 'image', onlyWhenShown: true }
  ];

  // Inline icons: the console's icon font is a subset that lacks most of these.
  var ICONS = {
    layers: '<path d="M12 2 2 7l10 5 10-5-10-5Z"/><path d="m2 17 10 5 10-5"/><path d="m2 12 10 5 10-5"/>',
    pin: '<path d="M12 22s7-6.5 7-12a7 7 0 0 0-14 0c0 5.5 7 12 7 12Z"/><circle cx="12" cy="10" r="2.5"/>',
    videocam: '<rect x="2" y="6" width="14" height="12" rx="2"/><path d="m16 10 6-4v12l-6-4"/>',
    movie: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16M17 4v16M2 9h5M17 9h5M2 15h5M17 15h5"/>',
    palette: '<path d="M12 22a10 10 0 1 1 10-10c0 2.8-2.2 4-4 4h-2a2 2 0 0 0-1.5 3.3A1.6 1.6 0 0 1 12 22Z"/><circle cx="7.5" cy="11" r="1.2"/><circle cx="12" cy="7" r="1.2"/><circle cx="16.5" cy="11" r="1.2"/>',
    tune: '<path d="M4 6h9M19 6h1M4 12h3M13 12h7M4 18h11M21 18h-1"/><circle cx="16" cy="6" r="2.2"/><circle cx="10" cy="12" r="2.2"/><circle cx="18" cy="18" r="2.2"/>',
    walk: '<circle cx="13" cy="4" r="2"/><path d="m9 22 2.5-8L14 16v6M7 12l3-4 4 1 3 4M11.5 14 10 9"/>',
    'public': '<circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20"/>',
    cloud: '<path d="M17.5 19a4.5 4.5 0 1 0-1.4-8.8A6 6 0 0 0 4.3 12 3.5 3.5 0 0 0 6 19Z"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="m3 16 5-5 4 4 3-3 6 6"/>',
    locate: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="7"/>'
  };

  // The console expands some panels by itself while it boots; that must not pop a sheet.
  var SETTLE_MS = 6000;
  var phoneSince = 0;

  // Panels the console expands by itself (CCTV after a fly, imagery on boot) must not cover
  // the globe; only a tap by the user, or Jarvis asking (set_panel_open), opens a sheet.
  var TOUCH_WINDOW_MS = 2000;
  var lastTouch = 0;

  var openId = null;
  var chipBar = null;
  var scrim = null;
  var proxySheet = null;

  function applyTheme() {
    var t = window.__JARVIS_THEME__;
    if (!t) return;
    var s = document.documentElement.style;
    if (t.accent) s.setProperty('--jv-accent', t.accent);
    if (t.accentFill) s.setProperty('--jv-accent-fill', t.accentFill);
    if (t.onAccent) s.setProperty('--jv-on-accent', t.onAccent);
  }

  function isPhone() {
    return window.matchMedia && window.matchMedia(PHONE_QUERY).matches;
  }

  function panelEl(id) { return document.getElementById(id); }

  function panelDef(id) {
    for (var i = 0; i < PANELS.length; i++) if (PANELS[i].id === id) return PANELS[i];
    return null;
  }

  // Weather / imagery panels carry the hidden attribute until their layer is on.
  function isShown(el) { return !!el && !el.hidden; }

  function collapseButton(id) {
    return document.querySelector('.panel-collapse-btn[data-collapse-target="' + id + '"]');
  }

  function isExpanded(el) {
    return !!el && !el.classList.contains('collapsed');
  }

  /** Expand or collapse a panel through its own button so the console keeps its state in sync. */
  function setExpanded(id, expanded) {
    var el = panelEl(id);
    if (!el || isExpanded(el) === expanded) return;
    var btn = collapseButton(id);
    if (btn) {
      btn.click();
    } else {
      el.classList.toggle('collapsed', !expanded);
    }
  }

  function markHosts() {
    ['left-panel-stack', 'right-context-rail'].forEach(function (hid) {
      var h = panelEl(hid);
      if (!h) return;
      var has = !!(openId && h.querySelector('#' + openId));
      h.classList.toggle('jv-has-open', has);
    });
    document.documentElement.classList.toggle('jv-sheet-active', !!openId);
  }

  // ---------- Jarvis-built sheets (Places, Style) ----------

  function esc(text) {
    return String(text).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function isActive(btn) {
    return btn.classList.contains('active') || btn.getAttribute('aria-pressed') === 'true';
  }

  /** One row of buttons that press the console's own buttons (matched by index). */
  function proxyGroup(title, selector, clean) {
    var src = document.querySelectorAll(selector);
    if (!src.length) return '';
    var html = '<div class="jv-group"><div class="jv-group-title">' + esc(title) + '</div><div class="jv-group-row">';
    for (var i = 0; i < src.length; i++) {
      var label = (src[i].innerText || src[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (clean) label = clean(label);
      if (!label) continue;
      html += '<button type="button" class="jv-pill' + (isActive(src[i]) ? ' active' : '') +
        '" data-proxy="' + esc(selector) + '" data-index="' + i + '">' + esc(label) + '</button>';
    }
    return html + '</div></div>';
  }

  function runCommand(name, args) {
    var vc = window.__godsEyeView && window.__godsEyeView.voiceCommands;
    if (vc && typeof vc.runner === 'function') return vc.runner(name, args || {});
    if (typeof window.__executeGodsEyeAction === 'function') {
      return window.__executeGodsEyeAction({ action: name, args: args || {} });
    }
    return null;
  }

  function deviceLocation() {
    try {
      var bridge = window.JarvisVoiceBridge;
      if (bridge && typeof bridge.getDeviceLocation === 'function') {
        var loc = JSON.parse(bridge.getDeviceLocation() || 'null');
        if (loc && isFinite(loc.lat) && isFinite(loc.lon)) return loc;
      }
    } catch (e) { /* no location */ }
    return null;
  }

  function renderProxy(id) {
    var html = '';
    if (id === 'jv-places') {
      html += '<form class="jv-search" autocomplete="off">' +
        '<input type="search" enterkeyhint="go" placeholder="Search a city, country or landmark" aria-label="Search place">' +
        '<button type="submit" class="jv-pill active">Go</button></form>';
      if (deviceLocation()) {
        html += '<div class="jv-group-row"><button type="button" class="jv-pill" data-action="my-location">' +
          '<svg viewBox="0 0 24 24" aria-hidden="true">' + ICONS.locate + '</svg>My location</button></div>';
      }
      html += proxyGroup('Cities', '#location-pills .location-pill');
      html += proxyGroup('Landmarks', '#poi-row button');
    } else if (id === 'jv-style') {
      // "◯ Normal 1" -> "Normal": drop the keyboard-shortcut digit.
      html += proxyGroup('Look', '#control-panel .style-btn', function (l) { return l.replace(/\s*\d+$/, ''); });
      html += proxyGroup('Map', '#control-panel .map-stack-chip');
    }
    proxySheet.innerHTML = '<div class="jv-sheet-title">' + esc(panelDef(id).label) + '</div>' + html;
  }

  function onProxyClick(ev) {
    var b = ev.target.closest('button');
    if (!b || !proxySheet.contains(b)) return;
    if (b.getAttribute('data-action') === 'my-location') {
      var loc = deviceLocation();
      var v = window.__godsEyeView && window.__godsEyeView.viewer;
      // Straight camera move: the console's action executor would pop a result card.
      if (loc && v && window.Cesium) {
        v.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(loc.lon, loc.lat, 60000),
          orientation: { heading: 0, pitch: Cesium.Math.toRadians(-90), roll: 0 },
          duration: 1.8
        });
      }
      closePanel(openId);
      return;
    }
    var sel = b.getAttribute('data-proxy');
    if (!sel) return;
    var target = document.querySelectorAll(sel)[Number(b.getAttribute('data-index'))];
    if (target) target.click();
    var id = openId;
    setTimeout(function () { if (openId === id) renderProxy(id); }, 350);
    // A new place = look at the globe, not the sheet.
    if (id === 'jv-places') setTimeout(function () { if (openId === id) closePanel(id); }, 450);
  }

  function onProxySubmit(ev) {
    ev.preventDefault();
    var input = proxySheet.querySelector('.jv-search input');
    var q = input && input.value.trim();
    if (!q) return;
    input.blur();
    try { runCommand('fly_to_location', { query: q }); } catch (e) { /* console logs it */ }
    closePanel(openId);
  }

  // ---------- open / close ----------

  function openPanel(id) {
    if (openId && openId !== id) closePanel(openId, true);
    var def = panelDef(id);
    if (!def) return;
    if (def.proxy) {
      openId = id;
      renderProxy(id);
      proxySheet.classList.add('jv-open');
    } else {
      var el = panelEl(id);
      if (!el) return;
      openId = id;
      PANELS.forEach(function (p) {
        var pe = panelEl(p.id);
        if (pe) pe.classList.toggle('jv-open', p.id === id);
      });
      setExpanded(id, true);
    }
    markHosts();
    renderChips();
  }

  function closePanel(id, keepChips) {
    id = id || openId;
    var def = panelDef(id);
    if (def && def.proxy) {
      proxySheet.classList.remove('jv-open');
      proxySheet.innerHTML = '';
    } else {
      var el = panelEl(id);
      if (el) {
        el.classList.remove('jv-open');
        setExpanded(id, false);
      }
    }
    if (id === openId) openId = null;
    markHosts();
    if (!keepChips) renderChips();
  }

  function renderChips() {
    if (!chipBar) return;
    var html = '';
    PANELS.forEach(function (p) {
      if (!p.proxy) {
        var el = panelEl(p.id);
        if (!el) return;
        if (p.onlyWhenShown && !isShown(el) && openId !== p.id) return;
      }
      var active = openId === p.id;
      html += '<button type="button" class="jv-chip' + (active ? ' active' : '') + '" data-panel="' + p.id + '">' +
        '<svg viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[p.icon] || '') + '</svg>' +
        '<span>' + p.label + '</span></button>';
    });
    if (chipBar.__lastHtml !== html) {
      chipBar.innerHTML = html;
      chipBar.__lastHtml = html;
    }
  }

  function buildChrome() {
    if (chipBar) return;
    chipBar = document.createElement('nav');
    chipBar.id = 'jv-chipbar';
    chipBar.setAttribute('aria-label', 'God\'s Eye panels');
    chipBar.addEventListener('click', function (ev) {
      var b = ev.target.closest('.jv-chip');
      if (!b) return;
      var id = b.getAttribute('data-panel');
      if (openId === id) closePanel(id); else openPanel(id);
    });
    document.body.appendChild(chipBar);

    scrim = document.createElement('div');
    scrim.id = 'jv-scrim';
    scrim.addEventListener('click', function () { if (openId) closePanel(openId); });
    document.body.appendChild(scrim);

    proxySheet = document.createElement('section');
    proxySheet.id = 'jv-proxy-sheet';
    proxySheet.addEventListener('click', onProxyClick);
    proxySheet.addEventListener('submit', onProxySubmit);
    document.body.appendChild(proxySheet);
  }

  /** Desktop defaults leave several panels expanded; on a phone everything starts closed. */
  function collapseAllForPhone() {
    PANELS.forEach(function (p) {
      if (p.proxy) return;
      var el = panelEl(p.id);
      if (el && p.id !== openId && isExpanded(el)) setExpanded(p.id, false);
    });
  }

  // A panel the console (or Jarvis) expands by itself becomes the open sheet.
  function watchPanels() {
    var mo = new MutationObserver(function (records) {
      if (!document.documentElement.classList.contains('jv-phone')) return;
      for (var i = 0; i < records.length; i++) {
        var el = records[i].target;
        var def = el.id && panelDef(el.id);
        if (!def || def.proxy) continue;
        if (isExpanded(el) && openId !== el.id && isShown(el)) {
          var now = Date.now();
          var asked = now - lastTouch < TOUCH_WINDOW_MS || now < (window.__jarvisAllowSheetUntil || 0);
          if (asked && now - phoneSince >= SETTLE_MS) openPanel(el.id); else setExpanded(el.id, false);
        } else if (!isExpanded(el) && openId === el.id) {
          openId = null;
          el.classList.remove('jv-open');
          markHosts();
          renderChips();
        }
      }
    });
    PANELS.forEach(function (p) {
      var el = !p.proxy && panelEl(p.id);
      if (el && !el.__jvWatched) {
        el.__jvWatched = true;
        mo.observe(el, { attributes: true, attributeFilter: ['class'] });
      }
    });
  }

  function enterPhone() {
    var root = document.documentElement;
    if (root.classList.contains('jv-phone')) return;
    root.classList.add('jv-phone');
    phoneSince = Date.now();
    buildChrome();
    collapseAllForPhone();
    renderChips();
    watchPanels();
    setTimeout(function () {
      if (!openId) collapseAllForPhone();
    }, SETTLE_MS);
  }

  function leavePhone() {
    var root = document.documentElement;
    if (!root.classList.contains('jv-phone')) return;
    if (openId) closePanel(openId);
    root.classList.remove('jv-phone', 'jv-sheet-active');
  }

  function sync() {
    if (isPhone()) enterPhone(); else leavePhone();
  }

  document.addEventListener('pointerdown', function () { lastTouch = Date.now(); }, true);

  window.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && openId) closePanel(openId);
  });

  // Android back: GodsEyeActivity asks this first; true = a sheet was closed.
  window.__jarvisCloseSheet = function () {
    if (!openId) return false;
    closePanel(openId);
    return true;
  };

  function boot() {
    applyTheme();
    // The panels are created by the console's main module; wait for them.
    var tries = 0;
    (function waitForPanels() {
      if (panelEl('data-panel') && panelEl('command-dock')) {
        sync();
        if (window.matchMedia) {
          var mq = window.matchMedia(PHONE_QUERY);
          if (mq.addEventListener) mq.addEventListener('change', sync); else if (mq.addListener) mq.addListener(sync);
        }
        // Layer-driven panels (weather, imagery) come and go.
        setInterval(function () {
          if (document.documentElement.classList.contains('jv-phone')) {
            watchPanels();
            renderChips();
          }
        }, 2000);
      } else if (tries++ < 120) {
        setTimeout(waitForPanels, 250);
      }
    })();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
