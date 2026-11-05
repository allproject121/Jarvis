package com.jarvis.assistant.codestudio.generator

/**
 * Offline starter projects (no AI, no network) for each [GeneratorKind].
 * Placeholders: {{TITLE}} (HTML-escaped project title) and {{PROMPT}} (HTML-escaped description).
 * NOTE: these are Kotlin raw strings — they must not contain the dollar sign.
 */
internal object OfflineTemplates {

    fun files(kind: GeneratorKind, title: String, prompt: String): Map<String, String> {
        val t = esc(title.ifBlank { "My Project" })
        val p = esc(prompt.ifBlank { "Built with Jarvis Code Studio." })
        val raw = when (kind) {
            GeneratorKind.LANDING_PAGE -> landing()
            GeneratorKind.WEB_APP -> webApp()
            GeneratorKind.HTML5_GAME -> game()
            GeneratorKind.GENERIC -> generic()
        }
        return raw.mapValues { (_, v) -> v.replace("{{TITLE}}", t).replace("{{PROMPT}}", p).trimStart() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ── Landing page ─────────────────────────────────────────────────────

    private fun landing() = linkedMapOf(
        "index.html" to """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{TITLE}}</title>
  <meta name="description" content="{{PROMPT}}">
  <link rel="stylesheet" href="css/style.css">
</head>
<body>
  <header class="nav">
    <a class="brand" href="#">{{TITLE}}</a>
    <button class="nav-toggle" aria-label="Menu" aria-expanded="false">&#9776;</button>
    <nav class="nav-links">
      <a href="#features">Features</a>
      <a href="#pricing">Pricing</a>
      <a href="#faq">FAQ</a>
      <a class="btn btn-small" href="#cta">Get started</a>
    </nav>
  </header>

  <main>
    <section class="hero">
      <div class="hero-text reveal">
        <span class="pill">New &middot; Just launched</span>
        <h1>{{TITLE}}</h1>
        <p class="lead">{{PROMPT}}</p>
        <div class="hero-actions">
          <a class="btn" href="#cta">Start free</a>
          <a class="btn btn-ghost" href="#features">See how it works</a>
        </div>
      </div>
      <div class="hero-art reveal" aria-hidden="true">
        <div class="blob"></div>
        <div class="card-mock">
          <div class="bar"></div><div class="bar short"></div><div class="bar"></div>
        </div>
      </div>
    </section>

    <section id="features" class="section">
      <h2 class="reveal">Everything you need</h2>
      <div class="grid">
        <article class="feature reveal"><div class="icon">&#9889;</div><h3>Fast</h3><p>Loads instantly on every device and network.</p></article>
        <article class="feature reveal"><div class="icon">&#128274;</div><h3>Secure</h3><p>Privacy-first design with no tracking by default.</p></article>
        <article class="feature reveal"><div class="icon">&#127912;</div><h3>Beautiful</h3><p>Modern, responsive layout that looks great everywhere.</p></article>
      </div>
    </section>

    <section id="pricing" class="section">
      <h2 class="reveal">Simple pricing</h2>
      <div class="grid">
        <article class="price reveal"><h3>Starter</h3><p class="amount">Free</p><ul><li>1 project</li><li>Community support</li></ul><a class="btn btn-ghost" href="#cta">Choose</a></article>
        <article class="price featured reveal"><h3>Pro</h3><p class="amount">9 / mo</p><ul><li>Unlimited projects</li><li>Priority support</li></ul><a class="btn" href="#cta">Choose</a></article>
        <article class="price reveal"><h3>Team</h3><p class="amount">29 / mo</p><ul><li>Everything in Pro</li><li>Team seats</li></ul><a class="btn btn-ghost" href="#cta">Choose</a></article>
      </div>
    </section>

    <section id="faq" class="section narrow">
      <h2 class="reveal">FAQ</h2>
      <details class="reveal"><summary>Is there a free plan?</summary><p>Yes, the Starter plan is free forever.</p></details>
      <details class="reveal"><summary>Can I cancel anytime?</summary><p>Of course. No contracts, no hidden fees.</p></details>
    </section>

    <section id="cta" class="section cta reveal">
      <h2>Ready to begin?</h2>
      <form class="signup">
        <input type="email" required placeholder="you@example.com" aria-label="Email">
        <button class="btn" type="submit">Join the waitlist</button>
      </form>
      <p class="form-msg" role="status"></p>
    </section>
  </main>

  <footer class="footer">&copy; <span id="year"></span> {{TITLE}}</footer>
  <script src="js/main.js"></script>
</body>
</html>
""",
        "css/style.css" to """
:root {
  --bg: #0b0d12; --surface: #141822; --text: #eef1f7; --muted: #9aa3b5;
  --accent: #7a5cff; --accent-2: #22d3ee; --radius: 16px;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html { scroll-behavior: smooth; }
body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: var(--bg); color: var(--text); line-height: 1.6; }
a { color: inherit; text-decoration: none; }
.nav { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; justify-content: space-between; padding: 14px 6vw; background: rgba(11,13,18,.8); backdrop-filter: blur(10px); border-bottom: 1px solid #1f2430; }
.brand { font-weight: 800; letter-spacing: .3px; }
.nav-links { display: flex; gap: 22px; align-items: center; }
.nav-links a { color: var(--muted); } .nav-links a:hover { color: var(--text); }
.nav-toggle { display: none; background: none; border: 0; color: var(--text); font-size: 24px; }
.btn { display: inline-block; padding: 12px 22px; border-radius: 999px; background: linear-gradient(135deg, var(--accent), var(--accent-2)); color: #fff !important; font-weight: 600; border: 0; cursor: pointer; transition: transform .15s, box-shadow .15s; }
.btn:hover { transform: translateY(-2px); box-shadow: 0 10px 30px rgba(122,92,255,.35); }
.btn-ghost { background: transparent; border: 1px solid #2a3040; }
.btn-small { padding: 8px 16px; }
.hero { display: grid; grid-template-columns: 1.1fr .9fr; gap: 40px; align-items: center; padding: 90px 6vw 60px; min-height: 80vh; }
.pill { display: inline-block; padding: 4px 12px; border-radius: 999px; background: #1b2030; color: var(--accent-2); font-size: 13px; margin-bottom: 16px; }
h1 { font-size: clamp(2.2rem, 6vw, 4rem); line-height: 1.1; margin-bottom: 18px; background: linear-gradient(90deg, #fff, #b9a8ff); -webkit-background-clip: text; background-clip: text; color: transparent; }
.lead { color: var(--muted); font-size: 1.15rem; max-width: 560px; margin-bottom: 28px; }
.hero-actions { display: flex; gap: 12px; flex-wrap: wrap; }
.hero-art { position: relative; height: 360px; }
.blob { position: absolute; inset: 10% 5%; background: radial-gradient(circle at 30% 30%, var(--accent), transparent 60%), radial-gradient(circle at 70% 70%, var(--accent-2), transparent 55%); filter: blur(30px); opacity: .7; animation: float 8s ease-in-out infinite; }
.card-mock { position: absolute; inset: 20% 15%; background: var(--surface); border: 1px solid #262c3b; border-radius: var(--radius); padding: 28px; display: flex; flex-direction: column; gap: 14px; box-shadow: 0 30px 60px rgba(0,0,0,.4); }
.bar { height: 14px; border-radius: 8px; background: #252b3a; } .bar.short { width: 60%; }
@keyframes float { 50% { transform: translateY(-14px) scale(1.03); } }
.section { padding: 80px 6vw; } .section.narrow { max-width: 760px; margin: 0 auto; }
.section h2 { font-size: clamp(1.6rem, 4vw, 2.4rem); margin-bottom: 32px; text-align: center; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 20px; }
.feature, .price { background: var(--surface); border: 1px solid #222838; border-radius: var(--radius); padding: 28px; transition: transform .2s, border-color .2s; }
.feature:hover, .price:hover { transform: translateY(-4px); border-color: var(--accent); }
.icon { font-size: 28px; margin-bottom: 10px; }
.feature p, .price li { color: var(--muted); }
.price ul { list-style: none; margin: 14px 0 22px; } .amount { font-size: 2rem; font-weight: 800; }
.price.featured { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
details { background: var(--surface); border-radius: 12px; padding: 16px 20px; margin-bottom: 12px; }
summary { cursor: pointer; font-weight: 600; } details p { color: var(--muted); margin-top: 8px; }
.cta { text-align: center; }
.signup { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
.signup input { padding: 12px 18px; border-radius: 999px; border: 1px solid #2a3040; background: var(--surface); color: var(--text); min-width: 260px; }
.form-msg { margin-top: 12px; color: var(--accent-2); min-height: 1.6em; }
.footer { text-align: center; padding: 30px; color: var(--muted); border-top: 1px solid #1f2430; }
.reveal { opacity: 0; transform: translateY(18px); transition: opacity .6s ease, transform .6s ease; }
.reveal.visible { opacity: 1; transform: none; }
@media (max-width: 760px) {
  .hero { grid-template-columns: 1fr; padding-top: 50px; text-align: center; }
  .hero-actions { justify-content: center; } .lead { margin-left: auto; margin-right: auto; }
  .hero-art { height: 240px; }
  .nav-toggle { display: block; }
  .nav-links { position: absolute; top: 100%; left: 0; right: 0; flex-direction: column; background: var(--bg); padding: 20px; display: none; border-bottom: 1px solid #1f2430; }
  .nav-links.open { display: flex; }
}
@media (prefers-reduced-motion: reduce) { .reveal { opacity: 1; transform: none; transition: none; } .blob { animation: none; } }
""",
        "js/main.js" to """
(function () {
  'use strict';
  document.getElementById('year').textContent = new Date().getFullYear();

  var toggle = document.querySelector('.nav-toggle');
  var links = document.querySelector('.nav-links');
  toggle.addEventListener('click', function () {
    var open = links.classList.toggle('open');
    toggle.setAttribute('aria-expanded', String(open));
  });
  links.addEventListener('click', function (e) {
    if (e.target.tagName === 'A') links.classList.remove('open');
  });

  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) { entry.target.classList.add('visible'); io.unobserve(entry.target); }
    });
  }, { threshold: 0.12 });
  document.querySelectorAll('.reveal').forEach(function (el) { io.observe(el); });

  var form = document.querySelector('.signup');
  var msg = document.querySelector('.form-msg');
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var email = form.querySelector('input').value.trim();
    msg.textContent = 'Thanks! ' + email + ' is on the list.';
    form.reset();
  });
})();
"""
    )

    // ── Web app (tasks app, localStorage) ────────────────────────────────

    private fun webApp() = linkedMapOf(
        "index.html" to """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{TITLE}}</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
  <div class="app">
    <header class="top">
      <div>
        <h1>{{TITLE}}</h1>
        <p class="sub">{{PROMPT}}</p>
      </div>
      <button id="themeBtn" class="icon-btn" title="Toggle theme">&#9680;</button>
    </header>

    <form id="addForm" class="add">
      <input id="newItem" autocomplete="off" placeholder="Add a task and press Enter" required>
      <select id="priority" aria-label="Priority">
        <option value="low">Low</option><option value="med" selected>Medium</option><option value="high">High</option>
      </select>
      <button class="btn" type="submit">Add</button>
    </form>

    <div class="toolbar">
      <input id="search" placeholder="Search..." aria-label="Search">
      <div class="filters" role="tablist">
        <button data-filter="all" class="active">All</button>
        <button data-filter="active">Active</button>
        <button data-filter="done">Done</button>
      </div>
    </div>

    <ul id="list" class="list"></ul>
    <p id="empty" class="empty">Nothing here yet.</p>

    <footer class="foot">
      <span id="counter"></span>
      <button id="clearDone" class="link">Clear completed</button>
    </footer>
  </div>
  <script src="js/store.js"></script>
  <script src="js/app.js"></script>
</body>
</html>
""",
        "css/app.css" to """
:root { --bg: #f5f6fa; --card: #ffffff; --text: #1b1f2a; --muted: #6b7385; --line: #e4e7ef; --accent: #6d4aff; --high: #e5484d; --med: #f5a524; --low: #30a46c; }
[data-theme="dark"] { --bg: #0e1016; --card: #171a23; --text: #eef0f6; --muted: #8d95a8; --line: #262a36; }
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: var(--bg); color: var(--text); transition: background .2s, color .2s; }
.app { max-width: 680px; margin: 0 auto; padding: 28px 18px 60px; }
.top { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
h1 { margin: 0 0 4px; font-size: 1.8rem; } .sub { margin: 0 0 20px; color: var(--muted); }
.icon-btn { background: var(--card); border: 1px solid var(--line); color: var(--text); border-radius: 12px; width: 42px; height: 42px; font-size: 20px; cursor: pointer; }
.add { display: flex; gap: 8px; margin-bottom: 14px; }
.add input, .toolbar input, select { flex: 1; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--line); background: var(--card); color: var(--text); font-size: 15px; }
select { flex: 0 0 auto; }
.btn { padding: 12px 18px; border-radius: 12px; border: 0; background: var(--accent); color: #fff; font-weight: 600; cursor: pointer; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.filters { display: flex; background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 3px; }
.filters button { border: 0; background: none; color: var(--muted); padding: 8px 12px; border-radius: 9px; cursor: pointer; }
.filters button.active { background: var(--accent); color: #fff; }
.list { list-style: none; margin: 0; padding: 0; }
.item { display: flex; align-items: center; gap: 12px; background: var(--card); border: 1px solid var(--line); border-left: 4px solid var(--med); border-radius: 12px; padding: 12px 14px; margin-bottom: 8px; animation: pop .18s ease; }
.item.high { border-left-color: var(--high); } .item.low { border-left-color: var(--low); }
.item.done .text { text-decoration: line-through; color: var(--muted); }
.item .text { flex: 1; word-break: break-word; } .item input[type=checkbox] { width: 20px; height: 20px; accent-color: var(--accent); }
.item .del { border: 0; background: none; color: var(--muted); font-size: 18px; cursor: pointer; }
.item .del:hover { color: var(--high); }
.empty { text-align: center; color: var(--muted); padding: 30px 0; }
.foot { display: flex; justify-content: space-between; color: var(--muted); margin-top: 12px; font-size: 14px; }
.link { background: none; border: 0; color: var(--accent); cursor: pointer; }
@keyframes pop { from { transform: scale(.97); opacity: 0; } }
@media (max-width: 480px) { .add { flex-wrap: wrap; } .add input { flex-basis: 100%; } }
""",
        "js/store.js" to """
/* Tiny persistence layer (localStorage with in-memory fallback). */
window.Store = (function () {
  'use strict';
  var KEY = 'jcs-webapp-items';
  var memory = [];
  function load() {
    try { return JSON.parse(localStorage.getItem(KEY)) || []; } catch (e) { return memory; }
  }
  function save(items) {
    memory = items;
    try { localStorage.setItem(KEY, JSON.stringify(items)); } catch (e) { /* storage unavailable */ }
  }
  return { load: load, save: save };
})();
""",
        "js/app.js" to """
(function () {
  'use strict';
  var items = Store.load();
  var filter = 'all';
  var query = '';
  var list = document.getElementById('list');
  var empty = document.getElementById('empty');
  var counter = document.getElementById('counter');

  function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }

  function render() {
    list.innerHTML = '';
    var visible = items.filter(function (it) {
      if (filter === 'active' && it.done) return false;
      if (filter === 'done' && !it.done) return false;
      return it.text.toLowerCase().indexOf(query) !== -1;
    });
    visible.forEach(function (it) {
      var li = document.createElement('li');
      li.className = 'item ' + it.priority + (it.done ? ' done' : '');
      var cb = document.createElement('input');
      cb.type = 'checkbox'; cb.checked = it.done;
      cb.addEventListener('change', function () { it.done = cb.checked; commit(); });
      var span = document.createElement('span');
      span.className = 'text'; span.textContent = it.text;
      span.title = 'Double-tap to edit';
      span.addEventListener('dblclick', function () {
        var next = window.prompt('Edit task', it.text);
        if (next && next.trim()) { it.text = next.trim(); commit(); }
      });
      var del = document.createElement('button');
      del.className = 'del'; del.setAttribute('aria-label', 'Delete'); del.innerHTML = '&times;';
      del.addEventListener('click', function () { items = items.filter(function (x) { return x.id !== it.id; }); commit(); });
      li.appendChild(cb); li.appendChild(span); li.appendChild(del);
      list.appendChild(li);
    });
    empty.style.display = visible.length ? 'none' : 'block';
    var left = items.filter(function (it) { return !it.done; }).length;
    counter.textContent = left + ' open / ' + items.length + ' total';
  }

  function commit() { Store.save(items); render(); }

  document.getElementById('addForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var input = document.getElementById('newItem');
    var text = input.value.trim();
    if (!text) return;
    items.unshift({ id: uid(), text: text, done: false, priority: document.getElementById('priority').value });
    input.value = '';
    commit();
  });

  document.getElementById('search').addEventListener('input', function (e) {
    query = e.target.value.trim().toLowerCase(); render();
  });

  document.querySelectorAll('.filters button').forEach(function (b) {
    b.addEventListener('click', function () {
      document.querySelectorAll('.filters button').forEach(function (x) { x.classList.remove('active'); });
      b.classList.add('active'); filter = b.getAttribute('data-filter'); render();
    });
  });

  document.getElementById('clearDone').addEventListener('click', function () {
    items = items.filter(function (it) { return !it.done; }); commit();
  });

  var root = document.documentElement;
  try { if (localStorage.getItem('jcs-theme') === 'dark') root.setAttribute('data-theme', 'dark'); } catch (e) {}
  document.getElementById('themeBtn').addEventListener('click', function () {
    var dark = root.getAttribute('data-theme') !== 'dark';
    if (dark) root.setAttribute('data-theme', 'dark'); else root.removeAttribute('data-theme');
    try { localStorage.setItem('jcs-theme', dark ? 'dark' : 'light'); } catch (e) {}
  });

  render();
})();
"""
    )

    // ── HTML5 canvas game (fixed-timestep 60 FPS) ───────────────────────

    private fun game() = linkedMapOf(
        "index.html" to """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>{{TITLE}}</title>
  <link rel="stylesheet" href="css/style.css">
</head>
<body>
  <div id="wrap">
    <canvas id="game" width="480" height="800"></canvas>
    <div id="hud"><span id="score">0</span><span id="best">BEST 0</span></div>
    <div id="overlay">
      <h1>{{TITLE}}</h1>
      <p>Dodge the meteors, grab the stars.<br>Drag / arrow keys to move.</p>
      <button id="startBtn">PLAY</button>
    </div>
  </div>
  <script src="js/input.js"></script>
  <script src="js/entities.js"></script>
  <script src="js/game.js"></script>
</body>
</html>
""",
        "css/style.css" to """
html, body { margin: 0; height: 100%; background: #05060a; overflow: hidden; touch-action: none; user-select: none; -webkit-user-select: none; }
#wrap { position: relative; height: 100%; display: flex; align-items: center; justify-content: center; }
canvas { height: 100%; max-width: 100%; aspect-ratio: 480 / 800; background: radial-gradient(ellipse at top, #1a1f3a, #05060a); image-rendering: auto; }
#hud { position: absolute; top: 12px; left: 0; right: 0; display: flex; justify-content: space-between; padding: 0 18px; color: #fff; font: 700 22px system-ui, sans-serif; pointer-events: none; text-shadow: 0 2px 6px #000; }
#best { font-size: 14px; opacity: .7; align-self: center; }
#overlay { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #fff; text-align: center; font-family: system-ui, sans-serif; background: rgba(5,6,10,.6); }
#overlay.hidden { display: none; }
#overlay h1 { font-size: 40px; margin: 0 0 10px; letter-spacing: 2px; }
#overlay p { opacity: .8; line-height: 1.5; }
#startBtn { margin-top: 18px; padding: 14px 44px; font: 800 20px system-ui, sans-serif; border: 0; border-radius: 999px; color: #fff; background: linear-gradient(135deg, #7a5cff, #22d3ee); cursor: pointer; }
""",
        "js/input.js" to """
/* Unified keyboard + touch/mouse input. Exposes window.Input. */
window.Input = (function () {
  'use strict';
  var keys = {};
  var pointer = { active: false, x: 0 };
  var canvas = null;
  function toCanvasX(clientX) {
    var r = canvas.getBoundingClientRect();
    return (clientX - r.left) * (canvas.width / r.width);
  }
  function attach(c) {
    canvas = c;
    window.addEventListener('keydown', function (e) { keys[e.key] = true; });
    window.addEventListener('keyup', function (e) { keys[e.key] = false; });
    c.addEventListener('pointerdown', function (e) { pointer.active = true; pointer.x = toCanvasX(e.clientX); c.setPointerCapture(e.pointerId); });
    c.addEventListener('pointermove', function (e) { if (pointer.active) pointer.x = toCanvasX(e.clientX); });
    c.addEventListener('pointerup', function () { pointer.active = false; });
    c.addEventListener('pointercancel', function () { pointer.active = false; });
  }
  function axis() {
    var a = 0;
    if (keys.ArrowLeft || keys.a || keys.A) a -= 1;
    if (keys.ArrowRight || keys.d || keys.D) a += 1;
    return a;
  }
  return { attach: attach, axis: axis, pointer: pointer };
})();
""",
        "js/entities.js" to """
/* Game entities. Exposes window.Entities. */
window.Entities = (function () {
  'use strict';
  function Player(w, h) {
    this.x = w / 2; this.y = h - 110; this.r = 22; this.speed = 520; this.tilt = 0;
  }
  Player.prototype.update = function (dt, w) {
    var target = null;
    if (Input.pointer.active) target = Input.pointer.x;
    var vx = 0;
    if (target !== null) {
      var d = target - this.x; vx = Math.max(-1, Math.min(1, d / 40)) * this.speed;
    } else {
      vx = Input.axis() * this.speed;
    }
    this.x = Math.max(this.r, Math.min(w - this.r, this.x + vx * dt));
    this.tilt += ((vx / this.speed) * 0.35 - this.tilt) * Math.min(1, dt * 10);
  };
  Player.prototype.draw = function (g) {
    g.save(); g.translate(this.x, this.y); g.rotate(this.tilt);
    g.fillStyle = '#22d3ee'; g.shadowColor = '#22d3ee'; g.shadowBlur = 18;
    g.beginPath(); g.moveTo(0, -this.r * 1.3); g.lineTo(this.r, this.r); g.lineTo(0, this.r * 0.5); g.lineTo(-this.r, this.r); g.closePath(); g.fill();
    g.restore();
  };

  function Meteor(w, speed) {
    this.r = 14 + Math.random() * 26; this.x = this.r + Math.random() * (w - this.r * 2); this.y = -this.r;
    this.vy = speed * (0.8 + Math.random() * 0.6); this.vx = (Math.random() - 0.5) * 60;
    this.rot = Math.random() * 6.28; this.spin = (Math.random() - 0.5) * 3; this.dead = false;
  }
  Meteor.prototype.update = function (dt, w, h) {
    this.y += this.vy * dt; this.x += this.vx * dt; this.rot += this.spin * dt;
    if (this.x < this.r || this.x > w - this.r) this.vx *= -1;
    if (this.y - this.r > h) this.dead = true;
  };
  Meteor.prototype.draw = function (g) {
    g.save(); g.translate(this.x, this.y); g.rotate(this.rot);
    g.fillStyle = '#8b5e3c'; g.strokeStyle = '#c08552'; g.lineWidth = 3;
    g.beginPath();
    for (var i = 0; i < 8; i++) {
      var a = (i / 8) * Math.PI * 2, rr = this.r * (0.8 + ((i * 37) % 10) / 40);
      g.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    g.closePath(); g.fill(); g.stroke(); g.restore();
  };

  function Star(w, speed) {
    this.r = 12; this.x = 20 + Math.random() * (w - 40); this.y = -20; this.vy = speed * 0.7; this.t = 0; this.dead = false;
  }
  Star.prototype.update = function (dt, w, h) { this.y += this.vy * dt; this.t += dt; if (this.y > h + 20) this.dead = true; };
  Star.prototype.draw = function (g) {
    g.save(); g.translate(this.x, this.y); g.rotate(this.t * 2);
    g.fillStyle = '#ffd166'; g.shadowColor = '#ffd166'; g.shadowBlur = 16; g.beginPath();
    for (var i = 0; i < 10; i++) {
      var a = (i / 10) * Math.PI * 2 - Math.PI / 2, rr = i % 2 ? this.r * 0.45 : this.r;
      g.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    g.closePath(); g.fill(); g.restore();
  };

  function Particle(x, y, color) {
    var a = Math.random() * Math.PI * 2, s = 60 + Math.random() * 240;
    this.x = x; this.y = y; this.vx = Math.cos(a) * s; this.vy = Math.sin(a) * s; this.life = 0.6 + Math.random() * 0.4; this.max = this.life; this.color = color; this.dead = false;
  }
  Particle.prototype.update = function (dt) { this.x += this.vx * dt; this.y += this.vy * dt; this.vx *= 0.98; this.vy *= 0.98; this.life -= dt; if (this.life <= 0) this.dead = true; };
  Particle.prototype.draw = function (g) { g.globalAlpha = Math.max(0, this.life / this.max); g.fillStyle = this.color; g.fillRect(this.x - 2, this.y - 2, 4, 4); g.globalAlpha = 1; };

  function hit(a, b, pad) { var dx = a.x - b.x, dy = a.y - b.y, r = a.r + b.r - (pad || 0); return dx * dx + dy * dy < r * r; }

  return { Player: Player, Meteor: Meteor, Star: Star, Particle: Particle, hit: hit };
})();
""",
        "js/game.js" to """
/* Main loop: fixed 60 Hz simulation step + requestAnimationFrame rendering. */
(function () {
  'use strict';
  var canvas = document.getElementById('game');
  var g = canvas.getContext('2d');
  var W = canvas.width, H = canvas.height;
  var STEP = 1 / 60;
  var scoreEl = document.getElementById('score'), bestEl = document.getElementById('best');
  var overlay = document.getElementById('overlay'), startBtn = document.getElementById('startBtn');
  var state, best = 0;
  try { best = parseInt(localStorage.getItem('jcs-game-best') || '0', 10) || 0; } catch (e) {}
  bestEl.textContent = 'BEST ' + best;

  var bgStars = [];
  for (var i = 0; i < 80; i++) bgStars.push({ x: Math.random() * W, y: Math.random() * H, s: 20 + Math.random() * 80, r: Math.random() * 1.6 + 0.3 });

  Input.attach(canvas);

  function reset() {
    state = { running: true, time: 0, score: 0, speed: 220, spawn: 0, starSpawn: 2, shake: 0,
      player: new Entities.Player(W, H), meteors: [], stars: [], particles: [] };
  }

  function burst(x, y, color, n) { for (var i = 0; i < n; i++) state.particles.push(new Entities.Particle(x, y, color)); }

  function update(dt) {
    bgStars.forEach(function (s) { s.y += s.s * dt; if (s.y > H) { s.y = 0; s.x = Math.random() * W; } });
    if (!state) return;
    state.particles.forEach(function (p) { p.update(dt); });
    state.particles = state.particles.filter(function (p) { return !p.dead; });
    state.shake = Math.max(0, state.shake - dt * 30);
    if (!state.running) return;
    state.time += dt;
    state.speed = 220 + state.time * 9;
    state.score += dt * 10;
    state.player.update(dt, W);

    state.spawn -= dt;
    if (state.spawn <= 0) { state.meteors.push(new Entities.Meteor(W, state.speed)); state.spawn = Math.max(0.22, 0.9 - state.time * 0.012); }
    state.starSpawn -= dt;
    if (state.starSpawn <= 0) { state.stars.push(new Entities.Star(W, state.speed)); state.starSpawn = 2 + Math.random() * 2; }

    state.meteors.forEach(function (m) { m.update(dt, W, H); if (Entities.hit(m, state.player, 6)) gameOver(); });
    state.stars.forEach(function (s) {
      s.update(dt, W, H);
      if (!s.dead && Entities.hit(s, state.player, -6)) { s.dead = true; state.score += 50; burst(s.x, s.y, '#ffd166', 18); }
    });
    state.meteors = state.meteors.filter(function (m) { return !m.dead; });
    state.stars = state.stars.filter(function (s) { return !s.dead; });
    scoreEl.textContent = Math.floor(state.score);
  }

  function draw() {
    g.clearRect(0, 0, W, H);
    g.fillStyle = '#ffffff';
    bgStars.forEach(function (s) { g.globalAlpha = s.r / 2; g.fillRect(s.x, s.y, s.r, s.r); });
    g.globalAlpha = 1;
    if (!state) return;
    g.save();
    if (state.shake > 0) g.translate((Math.random() - 0.5) * state.shake, (Math.random() - 0.5) * state.shake);
    state.stars.forEach(function (s) { s.draw(g); });
    state.meteors.forEach(function (m) { m.draw(g); });
    state.particles.forEach(function (p) { p.draw(g); });
    if (state.running) state.player.draw(g);
    g.restore();
  }

  function gameOver() {
    if (!state.running) return;
    state.running = false; state.shake = 14;
    burst(state.player.x, state.player.y, '#22d3ee', 40);
    var s = Math.floor(state.score);
    if (s > best) { best = s; try { localStorage.setItem('jcs-game-best', String(best)); } catch (e) {} }
    bestEl.textContent = 'BEST ' + best;
    setTimeout(function () {
      overlay.querySelector('h1').textContent = 'GAME OVER';
      overlay.querySelector('p').innerHTML = 'Score ' + s + '<br>Best ' + best;
      startBtn.textContent = 'RETRY';
      overlay.classList.remove('hidden');
    }, 700);
  }

  startBtn.addEventListener('click', function () { overlay.classList.add('hidden'); reset(); });
  document.addEventListener('visibilitychange', function () { last = performance.now(); acc = 0; });

  var last = performance.now(), acc = 0;
  function frame(now) {
    acc += Math.min(0.25, (now - last) / 1000);
    last = now;
    while (acc >= STEP) { update(STEP); acc -= STEP; }
    draw();
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
})();
"""
    )

    // ── Generic starter ──────────────────────────────────────────────────

    private fun generic() = linkedMapOf(
        "index.html" to """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{TITLE}}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main class="container">
    <h1>{{TITLE}}</h1>
    <p class="muted">{{PROMPT}}</p>
    <div class="card">
      <p>Edit <code>index.html</code>, <code>style.css</code> and <code>main.js</code> — the preview reloads automatically.</p>
      <button id="clickBtn">Click me</button>
      <p id="out" class="muted"></p>
    </div>
  </main>
  <script src="main.js"></script>
</body>
</html>
""",
        "style.css" to """
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: #0f1117; color: #e9ecf3; }
.container { max-width: 760px; margin: 0 auto; padding: 48px 20px; }
h1 { font-size: clamp(1.8rem, 5vw, 2.8rem); margin: 0 0 8px; }
.muted { color: #98a0b3; }
.card { background: #171a23; border: 1px solid #262a36; border-radius: 16px; padding: 22px; margin-top: 24px; }
code { background: #232838; padding: 2px 6px; border-radius: 6px; }
button { padding: 12px 20px; border: 0; border-radius: 12px; background: #7a5cff; color: #fff; font-weight: 600; cursor: pointer; }
""",
        "main.js" to """
(function () {
  'use strict';
  var count = 0;
  var out = document.getElementById('out');
  document.getElementById('clickBtn').addEventListener('click', function () {
    count++;
    out.textContent = 'Clicked ' + count + (count === 1 ? ' time' : ' times');
    console.log('click', count);
  });
})();
""",
        "README.md" to """
# {{TITLE}}

{{PROMPT}}

Generated offline by Jarvis Code Studio. Open the Preview tab to run it.
"""
    )
}
