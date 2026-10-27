// Motion layer (fork addition), with motion.css. Nothing in app.js depends on it except two
// launch hooks (window.tvMotion); without this file the home moves exactly as upstream.
//
// How it moves, after Apple's own focus engine (UIKit's UIFocusAnimationCoordinator docs and
// WWDC23 "Animate with springs"):
//  - Every focus change is a spring set by a duration and a bounce, not a CSS transition. A
//    spring that is retargeted mid-flight keeps its velocity, so quick presses flow into each
//    other instead of stopping and restarting.
//  - Focusing runs faster than unfocusing, so the item you left trails behind you.
//  - When focus moves quickly the timings speed up to keep pace.
//  - Everything that moves with a focus change (the tile, its neighbours, the row scrolling) uses
//    the same timing, like the coordinator's shared animation block.
//  - Menus redraw their rows on every press; each new row takes over the state of the old row in
//    its place, so menu highlights animate like tiles do.
//  - Reduce Motion (Android TV's "Remove animations"): no movement, focus changes at once, a press
//    brightens, and the launch and coming back are fades.
//
// On top of focus: a bump at the end of a row, a press-in on OK, the launch zoom, the coming-back
// fade-up, and the glass clock gliding between scenes and rolling its digits.
// In mock mode (?mock=1) M turns the layer on and off to compare, R tries Reduce Motion;
// ?motion=0 starts with the layer off, ?calm=1 with Reduce Motion on.
(function () {
  var root = document.documentElement;
  var Q = location.search;
  var MOCK = /[?&](mock|preview)\b/.test(Q);
  if (!/[?&]motion=0\b/.test(Q)) root.classList.add("motion");
  var mq = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)");
  var forced = /[?&]calm=1\b/.test(Q);
  function syncCalm() { root.classList.toggle("calm", forced || !!(mq && mq.matches)); }
  syncCalm();
  if (mq && mq.addEventListener) mq.addEventListener("change", syncCalm);
  var layer = function () { return root.classList.contains("motion"); };
  var calm = function () { return layer() && root.classList.contains("calm"); };
  var on = function () { return layer() && !root.classList.contains("calm"); };   // full motion
  var EASE = "cubic-bezier(0.2, 0.8, 0.2, 1)";   // app.css's --ease: quick out, no bounce
  var REM = parseFloat(getComputedStyle(root).fontSize) || 16;

  // The timings. Durations are Apple's "perceptual duration" in seconds (WWDC23); bounce 0 has no
  // overshoot. FOCUS and UNFOCUS are measured from a direct capture of the tvOS 26 Home screen
  // (frame by frame at 30 fps): the icon gaining focus reaches its size in about 0.1 s, the one it
  // left settles back over about 0.35 s, and neither overshoots. Fitted to Apple's spring that is
  // 0.12 s and 0.39 s, both with no bounce.
  var FOCUS = { d: 0.12, b: 0 };        // gaining focus: almost at once
  var UNFOCUS = { d: 0.39, b: 0 };      // losing focus: slower, the trail
  var PRESS = { d: 0.14, b: 0 };        // OK pressed: near instant
  var RELEASE = { d: 0.34, b: 0.2 };    // OK let go
  var SCROLL = { d: 0.42, b: 0 };       // a row sliding along
  var KICK = { d: 0.4, b: 0 };          // the lean and the edge bump settle back with this

  // everything that takes focus: tiles and cards, and the menu rows and buttons
  var MANAGED = ".tile, .person, .people.head, .qbtn, .prow, .grow, .ssec, .srow, .sleep-menu .opt, .side-row, .side-tab, .np-btn";
  var KEYS = {
    ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1],
    Left: [-1, 0], Right: [1, 0], Up: [0, -1], Down: [0, 1]
  };
  var isOk = function (e) { return e.key === "Enter" || e.key === " " || e.keyCode === 23 || e.keyCode === 66; };
  var typing = function () { var a = document.activeElement; return a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA"); };

  // ---------------------------------------------------------------- springs
  // Apple's conversion: stiffness = (2π / duration)², damping = 4π (1 − bounce) / duration, mass 1.
  function Spring(x) { this.x = x; this.v = 0; this.t = x; this.k = 0; this.c = 0; }
  Spring.prototype.to = function (t, p, speed) {   // a new target; the velocity carries on
    var d = p.d * (speed || 1), w = 2 * Math.PI / d;
    this.t = t;
    this.k = w * w;
    this.c = 4 * Math.PI * (1 - p.b) / d;
  };
  Spring.prototype.snap = function (t) { this.x = this.t = t; this.v = 0; };
  Spring.prototype.step = function (dt) {
    this.v += (-this.k * (this.x - this.t) - this.c * this.v) * dt;
    this.x += this.v * dt;
  };

  // ---------------------------------------------------------------- one frame loop for everything
  var live = [];        // things whose springs are still moving: { springs, eps, draw }
  var running = false, last = 0;
  // ?tick=timer (development): drive frames from a timer, for a browser that is not painting
  var nextFrame = /[?&]tick=timer\b/.test(Q)
    ? function (cb) { return setTimeout(function () { cb(performance.now()); }, 16); }
    : function (cb) { return requestAnimationFrame(cb); };
  function wake(th) {
    if (live.indexOf(th) < 0) live.push(th);
    if (!running) { running = true; last = 0; nextFrame(frame); }
  }
  function frame(now) {
    var dt = last ? Math.min((now - last) / 1000, 1 / 20) : 1 / 60;
    last = now;
    var n = Math.max(1, Math.ceil(dt * 240)), h = dt / n;   // small steps keep stiff springs stable
    for (var i = live.length - 1; i >= 0; i--) {
      var th = live[i], still = true;
      for (var key in th.springs) {
        var sp = th.springs[key], eps = th.eps[key];
        for (var j = 0; j < n; j++) sp.step(h);
        if (Math.abs(sp.x - sp.t) < eps && Math.abs(sp.v) < eps * 10) { sp.x = sp.t; sp.v = 0; } else still = false;
      }
      th.draw();
      if (still) live.splice(i, 1);
    }
    if (live.length) nextFrame(frame); else running = false;
  }

  // ---------------------------------------------------------------- focusable things
  // Where app.css wants the element (its .focus or .lifted transform), read with our inline
  // transform out of the way. Transitions on transform are off for these (motion.css).
  function cssTarget(el) {
    var mine = el.style.transform;
    el.style.transform = "";
    var m = getComputedStyle(el).transform;
    el.style.transform = mine;
    var v = m && m !== "none" && m.match(/matrix\(([^)]+)\)/);
    if (!v) return { s: 1, x: 0, y: 0 };
    v = v[1].split(",").map(parseFloat);
    return { s: Math.sqrt(v[0] * v[0] + v[1] * v[1]), x: v[4], y: v[5] };
  }

  // The springs for an element, created at rest where it was: `fromClass` is the class it had
  // before this change, so a first focus still grows from the unfocused size.
  function thingOf(el, fromClass) {
    if (el._mo) return el._mo;
    var cls = el.className;
    if (fromClass != null && fromClass !== cls) el.className = fromClass;
    var t = cssTarget(el);
    if (el.className !== cls) el.className = cls;
    var th = el._mo = {
      el: el, pressed: false, nbr: 0,
      springs: { s: new Spring(t.s), x: new Spring(t.x), y: new Spring(t.y), rx: new Spring(0), ry: new Spring(0) },
      eps: { s: 0.0005, x: 0.05, y: 0.05, rx: 0.02, ry: 0.02 }
    };
    th.draw = function () {
      var p = th.springs, e = th.el;
      if (!e.isConnected) return;
      var tilt = Math.abs(p.rx.x) > 0.01 || Math.abs(p.ry.x) > 0.01;
      e.style.transform = "translate(" + p.x.x.toFixed(2) + "px, " + p.y.x.toFixed(2) + "px)" +
        (tilt ? " perspective(60rem) rotateX(" + p.rx.x.toFixed(2) + "deg) rotateY(" + p.ry.x.toFixed(2) + "deg)" : "") +
        " scale(" + p.s.x.toFixed(4) + ")";
    };
    th.draw();   // before the next paint, so it never flashes at the new size
    return th;
  }

  // Fast moves speed every timing up, down to about half: "when focus is moving quickly, the
  // timing of the animations are sped up to keep up with the user's movement" (Apple).
  var lastFocusAt = 0;
  function pace() { return Math.max(0.5, Math.min(1, (performance.now() - lastFocusAt) / 300)); }

  function retarget(el, speed) {
    var th = thingOf(el), t = cssTarget(el), sp = th.springs;
    var p = el.classList.contains("focus") || el.classList.contains("lifted") ? FOCUS : UNFOCUS;
    var s = t.s * (th.pressed ? 0.94 : 1);
    if (calm()) {
      sp.s.snap(s); sp.x.snap(t.x + th.nbr); sp.y.snap(t.y); sp.rx.snap(0); sp.ry.snap(0);
      th.draw();
      return;
    }
    sp.s.to(s, th.pressed ? PRESS : p, speed);
    sp.x.to(t.x + th.nbr, p, speed);
    sp.y.to(t.y, p, speed);
    wake(th);
  }

  // A push of velocity that the spring carries a little way and settles back from. For a spring
  // with no bounce the furthest it gets is v / (ωe), so v = peak · ω · e.
  function kick(el, key, peak) {
    var th = thingOf(el), sp = th.springs[key];
    sp.to(sp.t, KICK);
    sp.v += peak * Math.E * 2 * Math.PI / KICK.d;
    wake(th);
  }

  // ---------------------------------------------------------------- watching app.js
  function managedIn(nodes) {
    var out = [];
    [].forEach.call(nodes, function (n) {
      if (n.nodeType !== 1) return;
      if (n.matches(MANAGED)) out.push(n);
      [].push.apply(out, n.querySelectorAll(MANAGED));
    });
    return out;
  }
  var FOC = /\b(focus|lifted)\b/;
  var watcher = new MutationObserver(function (list) {
    if (!layer()) return;
    var touched = [], moved = false;
    list.forEach(function (m) {
      if (m.type === "attributes") {
        var el = m.target;
        if (!el.matches || !el.matches(MANAGED) || el.className === m.oldValue) return;
        if (FOC.test(m.oldValue || "") !== FOC.test(el.className)) moved = true;
        thingOf(el, m.oldValue);
        touched.push(el);
      } else if (m.addedNodes.length && m.removedNodes.length) {
        // a list redrawn: each new row takes over from the old row in the same place
        var gone = managedIn(m.removedNodes), come = managedIn(m.addedNodes);
        come.forEach(function (el, i) {
          var old = gone.length === come.length ? gone[i] : null;
          if (old && old._mo) { el._mo = old._mo; el._mo.el = el; el._mo.draw(); }
          else if (old) thingOf(el, old.className);
          else if (el.classList.contains("focus")) thingOf(el, el.className.replace(FOC, ""));
          else return;
          if (!old || FOC.test(old.className) !== FOC.test(el.className)) moved = true;
          touched.push(el);
        });
      }
    });
    if (touched.length) {
      var speed = moved ? pace() : 1;
      if (moved) lastFocusAt = performance.now();
      touched.forEach(function (el) { retarget(el, speed); });
      if (moved) spread(speed);
    }
    watcher.takeRecords();   // our own class swaps in thingOf
  });
  watcher.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ["class"], attributeOldValue: true });

  // ---------------------------------------------------------------- dock neighbours (off, as tvOS)
  var NUDGE = 0;   // rem the next icon steps aside; tvOS: 0
  // The two icons either side of the focused one step aside, with the focus timing.
  function spread(speed) {
    var dock = document.querySelectorAll(".dock-ic"), at = -1, i;
    for (i = 0; i < dock.length; i++) if (dock[i].classList.contains("focus")) at = i;
    var free = at >= 0 && on() && !root.classList.contains("expanded") && !document.querySelector(".lifted");
    var row = at >= 0 ? dock[at].offsetTop : 0;
    for (i = 0; i < dock.length; i++) {
      var d = i - at, off = 0;
      // tvOS leaves the neighbours where they are (measured: the next icon's far edge does not
      // move), so nothing steps aside; kept as a switch: set NUDGE above 0 to bring it back
      if (free && NUDGE && dock[i].offsetTop === row) off = (d === -1 ? -1 : d === 1 ? 1 : d === -2 ? -0.35 : d === 2 ? 0.35 : 0) * NUDGE;
      off *= REM;
      if (!dock[i]._mo && !off) continue;
      var th = thingOf(dock[i]);
      if (th.nbr === off) continue;
      th.nbr = off;
      retarget(dock[i], speed);
    }
  }

  // ---------------------------------------------------------------- Keep watching row scrolling
  // app.js slides the row with an inline translateX; a spring takes that over so it glides,
  // carrying its speed from one step to the next instead of restarting a transition every time.
  var track = document.getElementById("shelf");
  if (track) {
    var row = { springs: { x: new Spring(0) }, eps: { x: 0.1 }, wrote: "" };
    row.draw = function () {
      row.wrote = "translateX(" + row.springs.x.x.toFixed(2) + "px)";
      track.style.transform = row.wrote;
    };
    new MutationObserver(function () {
      var tf = track.style.transform;
      if (tf === row.wrote) return;
      var m = tf.match(/translateX\((-?[\d.]+)px\)/), x = m ? parseFloat(m[1]) : 0;
      if (!layer() || calm()) { row.springs.x.snap(x); return; }
      row.springs.x.to(x, SCROLL, pace());
      row.draw();
      wake(row);
    }).observe(track, { attributes: true, attributeFilter: ["style"] });
  }

  // ---------------------------------------------------------------- the remote
  function focused() {
    var all = document.querySelectorAll(".focus"), el = null;
    for (var i = all.length - 1; i >= 0; i--) {
      if (!all[i].querySelector(".focus") && all[i].offsetParent !== null) { el = all[i]; break; }
    }
    return el && el.matches(MANAGED) ? el : null;
  }

  var pressedEl = null, pressTimer = null;
  function pressIn(el) {
    pressedEl = el;
    thingOf(el).pressed = true;
    retarget(el);
    clearTimeout(pressTimer);
    pressTimer = setTimeout(pressOut, 600);   // app.js's HOLD_MS: a hold lifts it instead
  }
  function pressOut() {
    clearTimeout(pressTimer);
    var el = pressedEl;
    pressedEl = null;
    if (!el || !el._mo) return;
    var th = el._mo;
    th.pressed = false;
    if (!th.el.isConnected) return;
    th.springs.s.to(cssTarget(th.el).s, RELEASE);
    wake(th);
  }
  // Reduce Motion: the press is a brief brighten instead of a movement
  function flash(el) {
    el.animate([{ filter: "brightness(1.35)" }, { filter: "brightness(1)" }], { duration: 260, easing: "ease-out" });
  }

  // Note where focus was before app.js moves it (document, capture) and where it went after
  // (window, bubbling: the last listener to run).
  var before = null;
  document.addEventListener("keydown", function (e) {
    before = null;
    if (typing() || !layer()) return;
    if (launching && performance.now() - launchedAt > 200) back();   // still here: the app never came up
    if (isOk(e)) {
      var f = !e.repeat && !pressedEl && focused();
      if (f) { if (on()) pressIn(f); else flash(f); }
      return;
    }
    if (on() && KEYS[e.key]) before = focused() || false;
  }, true);
  window.addEventListener("keydown", function (e) {
    var d = KEYS[e.key];
    if (!d || before === null || e.repeat) return;
    var was = before;
    // after the focus watcher has seen this move (its records come first), so a first focus still
    // grows from the resting size
    queueMicrotask(function () {
      var after = focused();
      if (!after) return;
      // the end of a row: a 6 px nudge that way. (No lean on an ordinary move: in tvOS captures a
      // direction press does not tilt the tile; the tilt follows a thumb on the Siri Remote's touch
      // surface, which a TV remote does not have.)
      if (after === was) kick(after, d[0] ? "x" : "y", (d[0] || d[1]) * 6);
    });
  });
  document.addEventListener("keyup", function (e) { if (isOk(e)) pressOut(); }, true);
  window.addEventListener("blur", pressOut);

  // ---------------------------------------------------------------- launch: zoom into the app
  var veil = document.createElement("div");
  veil.className = "motion-veil";
  document.body.appendChild(veil);
  var launching = false, launchedAt = 0, veilTimer = null;
  // The app is asked to open straight away; the zoom plays while it starts, never before it.
  function launch(el, go) {
    if (!layer() || !el || launching) { go(); return; }
    launching = true;
    launchedAt = performance.now();
    veil.classList.add("on");
    clearTimeout(veilTimer);
    // If nothing comes in front (no such app), do not leave the home blacked out.
    veilTimer = setTimeout(function () { if (launching) back(); }, MOCK ? 1400 : 3500);
    // open it once the first frame of the zoom is painted, and never more than 50 ms later
    var gone = false, fire = function () { if (!gone) { gone = true; go(); } };
    requestAnimationFrame(function () { setTimeout(fire, 0); });
    setTimeout(fire, 50);
    if (calm()) return;                                          // Reduce Motion: just the fade
    if (pressedEl) { clearTimeout(pressTimer); pressedEl = null; }
    var face = el.querySelector(".face") || el, r = face.getBoundingClientRect();
    var ghost = face.cloneNode(true);
    ghost.classList.add("motion-ghost");
    var cs = getComputedStyle(face);
    ghost.style.cssText += ";left:" + r.left + "px;top:" + r.top + "px;width:" + r.width + "px;height:" + r.height + "px" +
      ";border-radius:" + cs.borderRadius + ";background-color:" + cs.backgroundColor + ";overflow:hidden;transform:none";
    document.body.appendChild(ghost);
    var dx = innerWidth / 2 - (r.left + r.width / 2), dy = innerHeight / 2 - (r.top + r.height / 2);
    // expand towards filling the screen, like a tvOS app opening from its icon
    var grow = Math.max(innerWidth / r.width, innerHeight / r.height);
    ghost.animate([
      { translate: "0 0", scale: "1", opacity: 1 },
      { translate: dx + "px " + dy + "px", scale: String(grow), opacity: 0 }
    ], { duration: 420, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" })
      .onfinish = function () { ghost.remove(); };
  }
  function abort() { if (launching) back(); }

  // ---------------------------------------------------------------- coming back: rise in
  // Three groups (top, Keep watching, dock), 50 ms apart, all done in under half a second, and any
  // key press finishes it at once so nobody waits for it.
  var entering = [];
  function back() {
    clearTimeout(veilTimer);
    launching = false;
    veil.classList.remove("on");
    if (!layer()) return;
    // The glass pieces (the person tab, Now Playing, the cards, the dock) rise without fading:
    // Chrome stops glass seeing what is behind it while its element is below full opacity.
    [["#side-tab, #np", 1], [".ncard", 1], ["#grid", 1]].forEach(function (g, i) {
      [].forEach.call(document.querySelectorAll(g[0]), function (el) {
        var glass = g[1], a;
        if (calm()) {
          if (glass) return;   // Reduce Motion: no movement, and no fade on glass
          a = el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 240, delay: i * 50, easing: "ease-out", fill: "backwards" });
        } else {
          a = el.animate(glass ? [{ translate: "0 1rem" }, { translate: "0 0" }]
                               : [{ opacity: 0, translate: "0 1rem" }, { opacity: 1, translate: "0 0" }],
                         { duration: 360, delay: i * 50, easing: EASE, fill: "backwards" });
        }
        entering.push(a);
      });
    });
  }
  document.addEventListener("keydown", function () {
    if (!entering.length) return;
    entering.forEach(function (a) { try { a.finish(); } catch (e) {} });
    entering = [];
  }, true);
  // the launcher calls tv.resume() each time it comes back in front
  if (window.tv && window.tv.resume) {
    var resume = window.tv.resume;
    window.tv.resume = function () { resume.apply(this, arguments); back(); };
  }
  window.tvMotion = { launch: launch, abort: abort, back: back };

  // ---------------------------------------------------------------- the glass clock
  // Scene change: the outgoing and incoming clocks travel together, so it reads as one clock
  // gliding to its new place (each still sits inside its own scene, behind the scenery).
  // A new minute rolls only the digits that changed: old ones up and out, new ones up and in.
  var scenes = document.getElementById("scenes");
  var gliding = false;
  function box(c) { var r = c.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, h: r.height }; }
  function glide(from, to) {
    var a = from.querySelector(".gclock"), b = to.querySelector(".gclock");
    if (!a || !b) return;
    var A = box(a), B = box(b);
    if (!A.h || !B.h) return;
    gliding = true;
    var opt = { duration: 1600, easing: "cubic-bezier(0.45, 0, 0.2, 1)" };
    [].forEach.call(from.querySelectorAll(".gclock"), function (c) {
      c.animate([{ translate: "0 0", scale: "1" }, { translate: (B.x - A.x) + "px " + (B.y - A.y) + "px", scale: String(B.h / A.h) }], opt);
    });
    [].forEach.call(to.querySelectorAll(".gclock"), function (c) {
      c.animate([{ translate: (A.x - B.x) + "px " + (A.y - B.y) + "px", scale: String(A.h / B.h) }, { translate: "0 0", scale: "1" }], opt);
    });
    setTimeout(function () { gliding = false; }, 1650);
  }

  function roll(c, oldKids) {
    var oldText = oldKids[0] && oldKids[0].textContent, fill = c.firstChild;
    if (!oldText || !fill || !fill.firstChild) return;
    var text = fill.textContent, p = 0;
    while (p < text.length && text[p] === oldText[p]) p++;
    if (p >= text.length) return;
    if (c.style.clipPath) return;   // the waterline clock is already cut in two: it just changes
    var range = document.createRange();
    range.setStart(fill.firstChild, p);
    range.setEnd(fill.firstChild, text.length);
    var cr = c.getBoundingClientRect(), split = range.getBoundingClientRect().left - cr.left;
    var lift = parseFloat(getComputedStyle(c).fontSize) * 0.3;
    var right = "inset(-50% -50% -50% " + split + "px)";
    var out = c.cloneNode(false);
    oldKids.forEach(function (k) { out.appendChild(k); });
    var inn = c.cloneNode(true);
    out.style.clipPath = right; inn.style.clipPath = right;
    c.style.clipPath = "inset(-50% " + (cr.width - split) + "px -50% -50%)";
    c.after(out, inn);
    out.animate([{ translate: "0 0", opacity: 1 }, { translate: "0 " + -lift + "px", opacity: 0 }],
      { duration: 420, easing: "cubic-bezier(0.5, 0, 0.75, 0)", fill: "forwards" });
    inn.animate([{ translate: "0 " + lift + "px", opacity: 0 }, { translate: "0 0", opacity: 1 }],
      { duration: 640, delay: 120, easing: EASE, fill: "backwards" })
      .onfinish = function () { c.style.clipPath = ""; out.remove(); inn.remove(); };
  }

  if (scenes) {
    new MutationObserver(function (list) {
      if (!on()) return;
      var gained = null, lost = null;
      list.forEach(function (m) {
        var t = m.target;
        if (m.type === "attributes" && t.classList.contains("scene")) {
          var was = /\bon\b/.test(m.oldValue || ""), is = t.classList.contains("on");
          if (is && !was) gained = t;
          if (was && !is) lost = t;
        } else if (m.type === "childList" && t.classList && t.classList.contains("gclock") && m.removedNodes.length &&
                   !t.classList.contains("under") && t.parentElement.classList.contains("on") && !gliding) {
          roll(t, [].slice.call(m.removedNodes));
        }
      });
      if (gained && lost) glide(lost, gained);
    }).observe(scenes, { subtree: true, childList: true, attributes: true, attributeFilter: ["class"], attributeOldValue: true });
  }

  // mock only: M compares with and without, R tries Reduce Motion
  if (!MOCK) return;
  var badge = document.createElement("div");
  badge.className = "motion-badge";
  document.body.appendChild(badge);
  var hide = null;
  document.addEventListener("keydown", function (e) {
    if (typing()) return;
    if (e.key === "m" || e.key === "M") {
      root.classList.toggle("motion");
      if (!layer()) {   // hand everything back to app.css
        live.length = 0;
        [].forEach.call(document.querySelectorAll(MANAGED), function (el) { el.style.transform = ""; el._mo = null; });
      }
      badge.textContent = layer() ? "New motion" + (calm() ? " (Reduce Motion)" : "") : "Original motion";
    } else if (e.key === "r" || e.key === "R") {
      forced = !forced;
      syncCalm();
      badge.textContent = calm() ? "Reduce Motion on" : "Reduce Motion off";
    } else return;
    badge.classList.add("on");
    clearTimeout(hide);
    hide = setTimeout(function () { badge.classList.remove("on"); }, 1400);
  });
})();
