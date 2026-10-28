// TV home page, tvOS style: Keep watching on top, a grid of apps below, one screen, no scrolling.
// Runs inside the launcher's WebView (window.TVOS is the native side) or in any browser for
// development: ?mock=1 uses mock.js for everything, ?preview=1 uses mock apps with the real bridge.
(function () {
  "use strict";

  var TV = window.TVOS || null;
  var Q = location.search;
  var MOCK = /[?&]mock\b/.test(Q);
  var PREVIEW = /[?&]preview\b/.test(Q);
  var API = decodeURIComponent((Q.match(/[?&]api=([^&]+)/) || [])[1] || "");
  // which TV this page is on (the launcher adds ?tv=<id> on any TV that is not the default); none = living room
  var TV_ID = (Q.match(/[?&]tv=([a-z-]+)/) || [])[1] || "";
  // The launcher's saved copy of this page, opened when the computer is off (or when there is no
  // computer at all): scenes, clock, weather straight from Open-Meteo and the apps in the saved
  // order; nothing that needs the bridge.
  var OFFLINE = /[?&]offline=1/.test(Q);
  // No computer at all (option A): the launcher's built-in copy is the home, so there is no
  // "computer is off" note to show.
  var ALONE = OFFLINE && /[?&]alone=1/.test(Q);
  if (OFFLINE) document.documentElement.classList.add("offline");
  var CACHE_KEY = "tvhome.cache.v2";
  // Every word on screen comes from i18n.js, in the language picked in Home settings.
  var t = window.I18N ? window.I18N.t : function (k) { return k; };

  // Settings from the bridge's config.json (served as config.js, and saved with the offline copy),
  // read in readConfig() once the page starts (mock.js fills them in for previews). See docs/CONFIG.md.
  var CFG = {}, HOME = {}, TVCFG = {}, QUICK = [];
  var WXCFG = { lat: -33.8688, lon: 151.2093, place: "Sydney", timezone: "Australia/Sydney" };
  var QUICK_ALL = ["sleep", "picture", "input"];
  var CLOCK24 = false;
  // Home settings made on this TV with the gear tile (see "home settings" below). They win over
  // config.js. ST is the saved object; the rest are read from it in readConfig().
  var ST = {}, WX_SHOW = true, WX_F = false, WX_EVERY = 30, HIDDEN_TV = [];

  // What this TV keeps for itself (app order, people order, new apps, the look). Written to the
  // page's storage and to the launcher (TVOS.keep), so the live page and the saved copy share it.
  var PFX = MOCK || PREVIEW ? "mock:" : "";
  function kept(key) {
    var v = null;
    try { if (TV && TV.kept) v = TV.kept(PFX + key); } catch (e) {}
    if (v == null) try { v = localStorage.getItem(PFX + key); } catch (e) {}
    try { return v == null ? null : JSON.parse(v); } catch (e) { return null; }
  }
  function keep(key, value) {
    var j = JSON.stringify(value);
    try { localStorage.setItem(PFX + key, j); } catch (e) {}
    try { if (TV && TV.keep) TV.keep(PFX + key, j); } catch (e) {}
  }

  // Default order: YouTube, then Netflix, then the rest the way a household reaches for them.
  var APP_ORDER = [
    "com.google.android.youtube.tv", "com.netflix.ninja", "au.net.abc.iview", "com.disney.disneyplus",
    "au.com.stan.and", "au.com.streamotion.ares.tv", "com.amazon.amazonvideo.livingroom",
    "com.sbs.ondemand.tv", "com.apple.atve.androidtv.appletv", "com.cbs.ca", "au.com.ninenow.ctv",
    "com.swm.live", "au.com.tenplay", "au.com.kayosports.tv", "au.com.foxtel.atv", "com.wbd.stream",
    "com.spotify.tv.android", "com.google.android.youtube.tvmusic"
  ];
  // The TV's own tools go last, in this order, just before the screens.
  var APP_LAST = ["com.mediatek.wwtv.tvcenter", "com.android.vending", "com.android.tv.settings"];
  // Kept behind the "More apps" tile on every TV (apps most people rarely open).
  var TUCKED = ["com.mediatek.wwtv.tvcenter", "com.google.android.youtube.tvmusic", "com.spotify.tv.android"];
  // The same tools on other makers' TVs, by name.
  var TUCKED_NAMES = /^(screen ?shar|live ?tv|usb|media ?cent|browser|device assistant)/i;
  // The Play Store, under the name most people know it by (in the chosen language).
  var RENAME = { "com.android.vending": "app_store" };
  // Never shown: home screens, store and system clutter, and Moonlight (a screen tile opens it).
  // A maker's own clutter (help apps, file browsers) is listed per house in config.json,
  // home.hide_packages, and added to this list in readConfig().
  var HIDDEN = [
    "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "app.couchtv.launcher",
    "com.limelight", "com.google.android.play.games", "com.google.android.videos"
  ];
  var GRID_COLS = 12, GRID_MAX = 24;
  var DOCK_APPS = 11;   // apps in the dock before More apps (home.dock_size); 11 + More spans the Keep watching row
  var SETTINGS_PKG = "com.android.tv.settings";
  // The Play Store: always the first tile of More apps, never in the dock's order.
  var STORE_PKG = "com.android.vending";
  var HOLD_MS = 600;    // how long OK is held to pick something up

  var ICONS = {
    screen: '<svg viewBox="0 0 24 24"><rect x="2.5" y="3.5" width="19" height="13" rx="2"/><path d="M9 20.5h6M12 16.5v4"/></svg>',
    moon: '<svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg>',
    picture: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 3.5v17a8.5 8.5 0 0 0 0-17z" class="fill"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="19" cy="12" r="1.2"/></svg>',
    gear: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
    look: '<svg viewBox="0 0 24 24"><rect x="2.5" y="4.5" width="19" height="15" rx="3"/><path d="M2.5 16l5.5-5.5 4.5 4.5 3-3 6 6"/><circle cx="16.5" cy="8.8" r="1.6"/></svg>',
    input: '<svg viewBox="0 0 24 24"><rect x="2.5" y="4" width="19" height="13" rx="2.5"/><path d="M8 21h8M12 7.5v6M9.5 11l2.5 2.5 2.5-2.5"/></svg>',
    store: '<svg viewBox="0 0 24 24"><path d="M5 8h14l-1.2 11.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8z"/><path d="M9 10.5V7a3 3 0 0 1 6 0v3.5"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    updown: '<svg viewBox="0 0 24 24"><path d="M8 9.5l4-4 4 4M8 14.5l4 4 4-4"/></svg>',
    sliders: '<svg viewBox="0 0 24 24"><path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></svg>',
    chevron: '<svg viewBox="0 0 24 24"><path d="M9.5 5.5 16 12l-6.5 6.5"/></svg>',
    // the settings screen's sections
    globe: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.6 2.5 3.8 5.3 3.8 8.5s-1.2 6-3.8 8.5c-2.6-2.5-3.8-5.3-3.8-8.5s1.2-6 3.8-8.5z"/></svg>',
    pin: '<svg viewBox="0 0 24 24"><path d="M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z"/><circle cx="12" cy="10" r="2.4"/></svg>',
    cloud: '<svg viewBox="0 0 24 24"><path d="M17.5 19H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 12a3.5 3.5 0 0 1-1.9 7z"/></svg>',
    apps: '<svg viewBox="0 0 24 24"><rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/></svg>',
    person: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20.5c.8-3.9 3.7-6 7.5-6s6.7 2.1 7.5 6"/></svg>',
    bolt: '<svg viewBox="0 0 24 24"><path d="M13 2.8 5.5 13.2h5.8L10.5 21l8-10.6h-5.9z"/></svg>',
    info: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.2"/></svg>'
  };
  // weather glyphs for the date line, by Open-Meteo's WMO code
  var WX = {
    sun: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/></svg>',
    moon: '<svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/></svg>',
    cloud: '<svg viewBox="0 0 24 24"><path d="M17.5 19H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 12a3.5 3.5 0 0 1-1.9 7z"/></svg>',
    rain: '<svg viewBox="0 0 24 24"><path d="M17.5 15H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 8a3.5 3.5 0 0 1-1.9 7z"/><path d="M9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5"/></svg>',
    storm: '<svg viewBox="0 0 24 24"><path d="M17.5 15H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 8a3.5 3.5 0 0 1-1.9 7z"/><path d="M12.5 15.5 10.5 19h3l-2 3.5"/></svg>'
  };
  function wxIcon(w) {
    var c = w.code;
    if (c >= 95) return WX.storm;
    if (c >= 51) return WX.rain;
    if (c >= 3 || c === 45 || c === 48) return WX.cloud;
    return w.is_day ? WX.sun : WX.moon;
  }

  var $ = function (id) { return document.getElementById(id); };
  var data = { shows: [], showsBy: {}, apps: [], screens: [], bridge: null };
  // Whose Keep watching is showing. Remembered on this TV. Any number of people: none or one shows
  // no switcher, two the pill switch, three or more a round head icon that opens a list.
  // With nobody set up, everything is shown ("both" to the bridge).
  var PEOPLE = [], NOBODY = false, who = null, pi = 0;
  try { who = localStorage.getItem("tvhome.who"); } catch (e) {}
  var zone = "grid", s = 0, g = 0;   // focus: zone, shelf index, grid index
  var shelfEls = [], gridEls = [], gridItems = [];
  var touched = false, expanded = false;
  var retryTimer = null, retryMs = 2500, statusTimer = null;
  var pmi = 0, plift = false;        // the people list: focused row, and whether that row is picked up
  var lift = null;                   // the dock app picked up to move: {pkg}
  var newApps = [];                  // apps installed since this TV first saw its apps, until opened once

  function setPeople(list) {
    PEOPLE = (list || []).filter(function (p) { return p && p.id && p.name; });
    NOBODY = !PEOPLE.length;
    if (NOBODY) PEOPLE = [{ id: "both", name: t("everyone") }];
    PEOPLE.forEach(function (p) { if (!data.showsBy[p.id]) data.showsBy[p.id] = []; });
    if (!PEOPLE.some(function (p) { return p.id === who; })) who = PEOPLE[0].id;
  }
  // An order made on this TV with no bridge to keep it (or before the bridge heard about it).
  function localPeopleOrder(list, always) {
    var lo = kept("tvhome.people.order");
    if (!lo || !lo.ids || !(always || lo.pending)) return list;
    var by = {};
    list.forEach(function (p) { by[p.id] = p; });
    var out = lo.ids.filter(function (id) { return by[id]; }).map(function (id) { return by[id]; });
    return out.concat(list.filter(function (p) { return lo.ids.indexOf(p.id) < 0; }));
  }

  // Which wins, lowest first: the defaults here, config.js (config.json), then Home settings made on
  // this TV (kept by the bridge too when there is one), then preview settings in the address. docs/CONFIG.md.
  function readConfig() {
    CFG = window.COUCH_CONFIG || {};
    HOME = CFG.home || {};
    ST = loadSettings();
    if (window.I18N) window.I18N.set(ST.lang || HOME.language || "en");
    if (CFG.weather) WXCFG = CFG.weather;
    var pl = ST.place;
    if (pl && isFinite(pl.lat) && isFinite(pl.lon)) WXCFG = { lat: pl.lat, lon: pl.lon, place: pl.name || "", timezone: pl.timezone || "auto" };
    WX_SHOW = ST.wx_show !== false;
    WX_F = ST.wx_unit === "f";
    WX_EVERY = [15, 30, 60].indexOf(ST.wx_every) >= 0 ? ST.wx_every : 30;
    var mine = TV_ID || CFG.default_tv;
    TVCFG = (CFG.tvs || []).filter(function (t) { return t.id === mine; })[0] || (CFG.tvs || [])[0] || {};
    // no "quick" from an older bridge: the sleep timer, and the picture switch where the TV has one
    QUICK = (Array.isArray(ST.quick) ? ST.quick : TVCFG.quick || ["sleep", "picture"])
      .filter(function (q, i, a) { return QUICK_ALL.indexOf(q) >= 0 && a.indexOf(q) === i; });
    document.documentElement.classList.add("theme-night");   // fork: the Night look is the only look
    GRID_COLS = 12;
    DOCK_APPS = Math.max(1, Math.min(11, parseInt(HOME.dock_size, 10) || 11));
    // fork: home.app_order lists the household's apps first, in that order (Android package names);
    // the built-in order follows for anything not listed
    if (Array.isArray(HOME.app_order)) {
      var mine = HOME.app_order.filter(function (p) { return typeof p === "string"; });
      APP_ORDER = mine.concat(APP_ORDER.filter(function (p) { return mine.indexOf(p) < 0; }));
    }
    CLOCK24 = (ST.clock || HOME.clock) === "24h";
    (Array.isArray(HOME.hide_packages) ? HOME.hide_packages : []).forEach(function (p) {
      if (typeof p === "string" && HIDDEN.indexOf(p) < 0) HIDDEN.push(p);
    });
    HIDDEN_TV = (Array.isArray(ST.hidden_apps) ? ST.hidden_apps : []).filter(function (p) { return typeof p === "string"; });
    var st = sceneState();
    SCENES = st.order.filter(function (id) { return st.off.indexOf(id) < 0; }).map(sceneById).filter(Boolean);
    if (!SCENES.length) SCENES = [SCENE_LIB[0]];
    SCENE_MS = Math.max(5, parseInt(ST.scene_seconds || HOME.scene_seconds, 10) || 20) * 1000;
    var people = CFG.people || [{ id: "person1", name: "Person 1" }, { id: "person2", name: "Person 2" }];
    // people added, renamed or removed in Home settings with no bridge to keep them
    var lst = kept("tvhome.people.list");
    if (lst && Array.isArray(lst.people) && (lst.pending || ALONE)) people = lst.people;
    setPeople(localPeopleOrder(people, OFFLINE));
    newApps = kept("tvhome.apps.new") || [];
  }

  // ---------------- helpers ----------------
  function esc(v) { return String(v == null ? "" : v).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  // The saved copy has its own storage, so the app order is also kept by the launcher (TVOS.keep).
  function load() {
    if (MOCK || PREVIEW) return null;
    try {
      if (OFFLINE) return TV && TV.kept ? { order: (JSON.parse(TV.kept(CACHE_KEY) || "null") || {}).order || null } : null;
      return JSON.parse(localStorage.getItem(CACHE_KEY) || "null");
    } catch (e) { return null; }
  }
  function save() {
    if (MOCK || PREVIEW || OFFLINE) return;
    var j = JSON.stringify({ showsBy: data.showsBy, screens: data.screens, order: data.order });
    try { localStorage.setItem(CACHE_KEY, j); } catch (e) {}
    try { if (TV && TV.keep) TV.keep(CACHE_KEY, j); } catch (e) {}
  }
  function mark(name) { try { performance.mark(name); } catch (e) {} }

  function api(method, path, body, timeoutMs) {
    if (TV_ID) {
      if (method === "GET") path += (path.indexOf("?") < 0 ? "?" : "&") + "tv=" + TV_ID;
      else body = Object.assign({ tv: TV_ID }, body || {});
    }
    if (MOCK && window.MOCK_API) return window.MOCK_API(method, path, body);
    // previews on a computer read the real bridge but never change it
    if (PREVIEW && method !== "GET") return Promise.resolve({ ok: true, note: "" });
    return new Promise(function (resolve, reject) {
      var x = new XMLHttpRequest();
      x.open(method, API + path);
      x.timeout = timeoutMs || 6000;
      x.setRequestHeader("Content-Type", "application/json");
      x.onload = function () {
        var j = null;
        try { j = JSON.parse(x.responseText); } catch (e) {}
        if (x.status >= 200 && x.status < 300 && j && j.ok !== false) resolve(j);
        else reject(new Error((j && j.error) || t("bridge_no", { status: x.status })));
      };
      x.onerror = function () { reject(new Error(t("bridge_down"))); };
      x.ontimeout = function () { reject(new Error(t("bridge_slow"))); };
      x.send(body ? JSON.stringify(body) : null);
    });
  }

  function status(text, sticky) {
    clearTimeout(statusTimer);
    $("status").textContent = text || "";
    if (text && !sticky) statusTimer = setTimeout(function () { status(data.bridge === false && !ALONE ? t("computer_quiet") : "", true); }, 6000);
  }

  function iconUrl(pkg) {
    if (!pkg) return null;
    if (TV) return "http://tvos.invalid/icon/" + pkg;
    return window.MOCK_ICON ? window.MOCK_ICON(pkg) : null;
  }

  // ---------------- the clock ----------------

  // Open-Meteo gives Celsius; Fahrenheit is worked out here when Home settings asks for it.
  function tempText(c) { return WX_F ? Math.round(c * 9 / 5 + 32) + "°F" : c + "°"; }
  function wxText(w) { var k = WMO_KEY[w.code]; return k ? t(k) : (w.text || ""); }

  function clock() {
    var d = new Date(), h = d.getHours(), m = d.getMinutes();
    var mm = (m < 10 ? "0" : "") + m;
    var hm = CLOCK24 ? (h < 10 ? "0" : "") + h + ":" + mm : (h % 12 || 12) + ":" + mm;
    $("time").innerHTML = hm + (CLOCK24 ? "" : "<small>" + esc(h < 12 ? t("am") : t("pm")) + "</small>");
    sideTime = hm + (CLOCK24 ? "" : " " + (h < 12 ? t("am") : t("pm")));
    if (zone === "side") renderSide();
    var loc = window.I18N ? window.I18N.locale() : "en-AU";
    var day = d.toLocaleDateString(loc, { weekday: "long", day: "numeric", month: "long" }).replace(",", "");
    day = day.charAt(0).toUpperCase() + day.slice(1);   // "lunes 27 de octubre" starts a line on screen
    var w = WX_SHOW && data.weather && data.weather.temp != null ? data.weather : null;
    $("date").innerHTML = '<span class="dline">' + esc(day) + (w ? '<span class="wx">' + wxIcon(w) + esc(tempText(w.temp)) +
      '<span class="ww"> ' + esc(wxText(w)) + "</span></span>" : "") + "</span>";
    fitDate();
    document.body.classList.toggle("night", h >= 21 || h < 6);
    glassClock(hm);
    if (quickReady) renderQuick();
  }

  // Night: five lock screen scenes take turns every 20 s (home.scenes picks which and in what
  // order, home.scene_seconds how long each stays). Each has its own photo,
  // a big glass clock and (most) a cut-out of the photo that stands in front of the clock. The clock's
  // fill is the frosted copy of the photo exactly behind each digit, so it is re-aligned whenever the
  // time changes or a scene is about to show. Only the scene showing and the next one are drawn.
  // Files: backgrounds/<photo>.jpg, and optionally <photo>-glass.jpg and <photo>-fg.webp.
  var SCENE_LIB = [
    // Photos are the generated scene-*.jpg set in backgrounds/. Drop your own 1920x1080 JPEGs in there
    // and name them here. Each one can have:
    //   fg: true     backgrounds/<photo>-fg.webp, a transparent copy of everything in front of the sky
    //                (peaks, trees, hills), drawn over the clock so the scenery overlaps its digits
    //   glass: true  backgrounds/<photo>-glass.jpg, a blurred and brightened copy of the photo used
    //                as the clock's frosted fill (without it the fill is the photo itself)
    //   water: 0.55  the waterline, as a share of the screen height: above it the clock is sharp,
    //                below it a blurred copy shows like a reflection
    // backgrounds/CREDITS.md says how to make the cut-out and the glass copy for your own photo.
    // Each scene's clock and date positions are in app.css (.s-<id>, .scene-<id> .date).
    { id: "night", photo: "scene-night", fg: true, glass: true },
    { id: "golden", photo: "scene-golden", fg: true, glass: true },
    { id: "water", photo: "scene-water", fg: false, glass: true, water: 0.55 },   // the underside of the surface
    { id: "fog", photo: "scene-fog", fg: true, glass: true },
    { id: "dawn", photo: "scene-dawn", fg: true, glass: true }
  ].concat(window.MY_SCENES || []);   // fork: the household's own (backgrounds/mine/scenes.js)
  var SCENES = SCENE_LIB.slice(), SCENE_MS = 20000, sceneTimer = null;
  var scene = 0, sceneEls = [], glassShown = "";

  // The household's own photos (Home settings, "Your photos") are scenes too, with no cut-out and
  // no glass copy: the clock's fill is the photo itself, frosted in app.css (.s-own).
  function photoSrc(p) { return p.file ? "http://tvos.invalid/photo/" + encodeURIComponent(p.file) : p.url; }
  function ownScenes() {
    return (Array.isArray(ST.photos) ? ST.photos : []).filter(function (p) { return p && p.id && (p.file || p.url); })
      .map(function (p, i) { return { id: "p-" + p.id, src: photoSrc(p), own: true, n: i + 1, fg: false, glass: false }; });
  }
  function sceneById(id) { return SCENE_LIB.concat(ownScenes()).filter(function (s) { return s.id === id; })[0] || null; }
  // Which scenes take turns and in what order: Home settings (scene_order, scenes_off) when set,
  // else home.scenes from config.json, with anything not listed there switched off.
  function sceneState() {
    var all = SCENE_LIB.concat(ownScenes()), ids = all.map(function (x) { return x.id; });
    var uniq = function (id, i, a) { return ids.indexOf(id) >= 0 && a.indexOf(id) === i; };
    var order, off;
    if (Array.isArray(ST.scene_order)) {
      order = ST.scene_order.filter(uniq);
      order = order.concat(ids.filter(function (id) { return order.indexOf(id) < 0; }));
      off = (Array.isArray(ST.scenes_off) ? ST.scenes_off : []).filter(function (id) { return ids.indexOf(id) >= 0; });
    } else {
      var builtIn = SCENE_LIB.map(function (x) { return x.id; });
      var pick = (HOME.scenes || []).filter(function (id, i, a) { return builtIn.indexOf(id) >= 0 && a.indexOf(id) === i; });
      if (!pick.length) pick = builtIn;
      order = pick.concat(ids.filter(function (id) { return pick.indexOf(id) < 0; }));
      off = builtIn.filter(function (id) { return pick.indexOf(id) < 0; });
    }
    return { order: order, off: off, all: all };
  }
  function cssUrl(u) { return 'url("' + String(u).replace(/["\\\n]/g, encodeURIComponent) + '")'; }

  function buildScenes() {
    var box = $("scenes");
    sceneEls = SCENES.map(function (s) {
      var el = document.createElement("div");
      el.className = "scene s-" + (s.own ? "own" : s.id);
      el.innerHTML = '<img class="sbg" alt=""><div class="shade"></div><div class="gclock"></div>' +
        (s.water ? '<div class="gclock under"></div>' : "") + (s.fg ? '<img class="fg" alt="">' : "");
      box.appendChild(el);
      return el;
    });
  }

  // draw a scene (load its pictures, place its clock) so it can fade in
  function readyScene(i) {
    var s = SCENES[i], el = sceneEls[i];
    if (!el.classList.contains("ready")) {
      el.querySelector(".sbg").src = s.src || "backgrounds/" + s.photo + ".jpg";
      if (s.fg) el.querySelector(".fg").src = "backgrounds/" + s.photo + "-fg.webp";
      el.classList.add("ready");
    }
    drawClocks(el, s, glassShown);
  }

  function unreadyScene(el) {
    el.classList.remove("ready");
    [].forEach.call(el.querySelectorAll("img"), function (im) { im.removeAttribute("src"); });
    [].forEach.call(el.querySelectorAll(".gclock"), function (c) { c.innerHTML = ""; });
  }

  function drawClocks(el, s, hm) {
    if (!hm) return;
    var cs = el.querySelectorAll(".gclock");
    for (var k = 0; k < cs.length; k++) {
      var c = cs[k];
      c.innerHTML = '<span class="fill">' + hm + '</span><span class="sheen">' + hm + '</span><span class="rim">' + hm + "</span>";
      var f = c.firstChild;
      f.style.backgroundImage = s.src ? cssUrl(s.src) : "url(backgrounds/" + s.photo + (s.glass ? "-glass" : "") + ".jpg)";
      f.style.backgroundPosition = -(c.offsetLeft + f.offsetLeft) + "px " + -(c.offsetTop + f.offsetTop) + "px";
    }
    if (s.water) {   // above the waterline sharp, below it the blurred reflection
      // The digits hang below the clock's box (its line height is tight), so the clips reach past
      // the box on the open sides; a plain inset cut the bottom of the numbers off .
      var line = innerHeight * s.water - cs[0].offsetTop, out = "-50%";
      cs[0].style.clipPath = "inset(" + out + " " + out + " " + (cs[0].offsetHeight - line) + "px " + out + ")";
      cs[1].style.clipPath = "inset(" + line + "px " + out + " " + out + " " + out + ")";
    }
  }

  function glassClock(hm) {
    if (hm === glassShown) return;
    glassShown = hm;
    sceneEls.forEach(function (el, i) { if (el.classList.contains("ready")) drawClocks(el, SCENES[i], hm); });
  }

  function showScene(i) {
    var prev = scene;
    scene = i;
    readyScene(i);
    var date = $("date");
    date.classList.add("swap");
    setTimeout(function () {
      document.documentElement.className = document.documentElement.className.replace(/\bscene-\w+/g, "").trim() + " scene-" + (SCENES[i].own ? "own" : SCENES[i].id);
      date.classList.remove("swap");
      fitDate();
    }, 600);
    sceneEls[i].classList.add("on");
    if (prev !== i) sceneEls[prev].classList.remove("on");
    // after the fade, stop drawing the old one and get the next one ready
    setTimeout(function () {
      var next = upcoming();
      // the others let go of their pictures too (only hidden, they stayed decoded in memory)
      sceneEls.forEach(function (el, k) { if (k !== scene && k !== next && el.classList.contains("ready")) unreadyScene(el); });
      readyScene(next);
    }, 2000);
  }

  // Night: the date line sits centred above the clock, between the people switch and the quick
  // buttons. Longer words (Spanish, a running sleep timer, four buttons) can reach them, so it makes
  // room: first it centres in the space left, then drops the weather's words, then the weather.
  function fitDate() {
    var d = $("date"), line = d.firstChild;
    if (!line || !line.getBoundingClientRect) return;
    d.style.left = ""; d.style.right = "";
    d.classList.remove("tight", "tighter");
    var b = line.getBoundingClientRect(), rem = innerWidth / 120;
    var near = function (r) { return r && r.width > 0 && r.bottom > b.top && r.top < b.bottom; };
    var q = $("quick").classList.contains("none") ? null : $("qbtns").getBoundingClientRect();
    var p = $("people").getBoundingClientRect();
    var hitQ = near(q) && b.right > q.left - rem, hitP = near(p) && b.left < p.right + rem;
    if (!hitQ && !hitP) return;
    if (hitQ) d.style.right = (innerWidth - q.left + 1.5 * rem) + "px";
    if (hitP) d.style.left = (p.right + 1.5 * rem) + "px";
    if (d.scrollWidth > d.clientWidth + 1) d.classList.add("tight");
    if (d.scrollWidth > d.clientWidth + 1) d.classList.add("tighter");
  }

  // the settings screen covers the scenes, so they wait (one less thing for a small TV stick to draw)
  // fork: the scenes come in a shuffled order: each once before any comes again, never the same
  // one twice in a row (a new shuffle each round)
  var sceneBag = [];
  function upcoming() {
    if (SCENES.length < 2) return 0;
    if (!sceneBag.length) {
      sceneBag = SCENES.map(function (x, i) { return i; }).filter(function (i) { return i !== scene; });
      for (var i = sceneBag.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)), t = sceneBag[i]; sceneBag[i] = sceneBag[j]; sceneBag[j] = t; }
    }
    return sceneBag[0];
  }
  function nextScene() {
    if (zone === "settings" || SCENES.length < 2) return;
    upcoming();
    showScene(sceneBag.shift());
  }

  // The bridge's reading for the place in config.json, or straight from Open-Meteo with no bridge
  // or when Home settings chose another place. Hidden when Home settings switched the weather off.
  function refreshWeather() {
    if (!WX_SHOW) return Promise.resolve();
    var direct = OFFLINE || !!(ST.place && isFinite(ST.place.lat));
    return (direct ? weatherDirect() : api("GET", "/api/weather")).then(function (j) { data.weather = j; clock(); }, function () {});
  }

  // Open-Meteo (free, no key), the same call the bridge makes. The words come from i18n.js.
  var WMO_KEY = { 0: "wx_clear", 1: "wx_mostly_clear", 2: "wx_partly", 3: "wx_cloudy", 45: "wx_fog", 48: "wx_fog",
    51: "wx_drizzle", 53: "wx_drizzle", 55: "wx_drizzle", 56: "wx_drizzle", 57: "wx_drizzle", 61: "wx_light_rain",
    63: "wx_rain", 65: "wx_heavy_rain", 66: "wx_rain", 67: "wx_rain", 80: "wx_showers", 81: "wx_showers",
    82: "wx_heavy_showers", 95: "wx_storms", 96: "wx_storms", 99: "wx_storms" };
  function weatherDirect() {
    var url = "https://api.open-meteo.com/v1/forecast?latitude=" + WXCFG.lat + "&longitude=" + WXCFG.lon +
      "&current=temperature_2m,weather_code,is_day&timezone=" + encodeURIComponent(WXCFG.timezone || "auto") + "&forecast_days=1";
    return fetch(url).then(function (r) { return r.json(); }).then(function (j) {
      var c = j.current, code = Math.round(c.weather_code);
      return { temp: Math.round(c.temperature_2m), code: code, text: "", is_day: !!c.is_day };
    });
  }

  // ---------------- quick buttons: from tvs[].quick in config.json, in that order ----------------
  // sleep: the sleep timer. picture: flip between the TV's two picture modes. input: the TV's
  // input list. (fork: there is one look, so no look button.) A button that cannot work right now is left out (no bridge
  // for the sleep timer, a TV with no picture setting, an input list nobody can open).
  var SLEEP_CHOICES = [15, 30, 60, 90, 0];   // 0 = off
  // picture mode only exists where the bridge says this TV supports it
  var sleepEnds = null, picMode = null, picCanSet = false, picShown = false, picBusy = false, picModes = null;
  var qi = 0, menuI = 0, noteTimer = null, quickReady = false, quickEls = [];

  function note(text) {
    clearTimeout(noteTimer);
    $("quick-note").textContent = text || "";
    if (text) noteTimer = setTimeout(function () { $("quick-note").textContent = ""; }, 6000);
  }
  function minutesLeft() {
    if (!sleepEnds) return 0;
    var m = Math.ceil((sleepEnds - Date.now()) / 60000);
    if (m <= 0) { sleepEnds = null; return 0; }
    return m;
  }
  function nativeInputs() {
    try { return !!(TV && TV.canOpenInputs && TV.canOpenInputs()); } catch (e) { return false; }
  }
  function quickVisible() {
    return QUICK.filter(function (q) {
      if (q === "sleep") return !OFFLINE;
      if (q === "picture") return !OFFLINE && picShown;
      if (q === "input") return !OFFLINE || nativeInputs();
      return false;
    });
  }
  function hasQuick() { return quickVisible().length > 0; }
  function picLabel() {
    if (picBusy) return t("switching");
    var names = picModes || (TVCFG.picture_modes || []).map(function (n) { return { id: null, name: n }; });
    var hit = names.filter(function (m) { return m.id === picMode; })[0];
    if (hit) return hit.name;
    if (picMode && picMode !== "other") return picMode.charAt(0).toUpperCase() + picMode.slice(1).replace(/-/g, " ");
    return t("picture_mode");
  }
  function renderQuick() {
    quickReady = true;
    var box = $("qbtns"), vis = quickVisible(), key = vis.join(",");
    if (box.getAttribute("data-k") !== key) {
      box.innerHTML = vis.map(function (q) { return '<span class="qbtn" id="q-' + q + '"></span>'; }).join("");
      box.setAttribute("data-k", key);
    }
    $("quick").classList.toggle("none", !vis.length);
    quickEls = vis.map(function (q) { return $("q-" + q); });
    var m = minutesLeft();
    vis.forEach(function (q) {
      var el = $("q-" + q);
      if (q === "sleep") {
        el.innerHTML = ICONS.moon + "<span>" + esc(m ? t("off_in_night", { m: m }) : t("sleep_timer")) + "</span>";
        el.classList.toggle("set", !!m);
      } else if (q === "picture") {
        el.innerHTML = ICONS.picture + "<span>" + esc(picLabel()) + "</span>";
        el.classList.toggle("off", !picCanSet);
      } else if (q === "input") {
        el.innerHTML = ICONS.input + "<span>" + esc(t("input")) + "</span>";
      }
    });
    if (qi >= vis.length) qi = Math.max(0, vis.length - 1);
    fitDate();
    if (zone === "side") renderSide();
  }
  function renderMenu() {
    var menu = $("sleep-menu");
    menu.innerHTML = SLEEP_CHOICES.map(function (c, i) {
      return '<span class="opt' + (i === menuI ? " focus" : "") + '">' + esc(c ? t("n_min", { n: c }) : t("timer_off")) + "</span>";
    }).join("");
  }
  function openMenu() {
    var m = minutesLeft();
    menuI = m ? SLEEP_CHOICES.length - 1 : 1;   // a timer is running: start on Off; otherwise 30 min
    zone = "menu";
    $("sleep-menu").classList.add("on");
    renderMenu();
  }
  function closeMenu() {
    $("sleep-menu").classList.remove("on");
    zone = "quick";
  }
  function setSleep(minutes) {
    closeMenu();
    sleepEnds = minutes ? Date.now() + minutes * 60000 : null;   // show it straight away
    renderQuick();
    api("POST", "/api/sleep", { minutes: minutes })
      .then(function (j) { if (j.ends !== undefined) sleepEnds = j.ends ? Date.parse(j.ends) : null; renderQuick(); })
      .catch(function (e) { note(e.message); refreshQuick(); });
  }
  function togglePicture() {
    if (!picCanSet) { note(t("pic_not_ready")); return; }
    // A picture switch can take several seconds. Extra presses meanwhile would switch it straight back,
    // so they do nothing, and the label says what the TV actually ended on, not a guess.
    if (picBusy) return;
    picBusy = true;
    renderQuick();
    api("POST", "/api/picture", { mode: "toggle" }, 20000)
      .then(function (j) { if (j.mode) picMode = j.mode; })
      .catch(function (e) { note(e.message); })
      .then(function () { picBusy = false; renderQuick(); });
  }
  // Start the page again, so new settings take effect everywhere at once.
  function restart() { location.reload(); }
  // The TV's own input list (HDMI, a console, a set top box): the TV_INPUT key through the bridge,
  // or straight from the launcher when there is no bridge (only where the TV has such a screen).
  function openInputs() {
    var native = function () { try { return !!(TV && TV.openInputs && TV.openInputs()); } catch (e) { return false; } };
    if (OFFLINE) { if (!native()) note(t("no_inputs")); return; }
    api("POST", "/api/key", { key: "input" })
      .catch(function (e) { if (!native()) note(e.message); });
  }
  function quickAction() {
    var q = quickVisible()[qi];
    if (q === "sleep") openMenu();
    else if (q === "picture") togglePicture();
    else if (q === "input") openInputs();
  }
  function refreshQuick() {
    var jobs = [];
    if (QUICK.indexOf("sleep") >= 0) {
      jobs.push(api("GET", "/api/sleep").then(function (j) { sleepEnds = j.ends ? Date.parse(j.ends) : null; }, function () {}));
    }
    if (QUICK.indexOf("picture") >= 0) {
      jobs.push(api("GET", "/api/picture").then(function (j) {
        picShown = j.supported !== false;
        picMode = j.mode || null; picCanSet = !!j.can_set;
        if (j.modes && j.modes.length) picModes = j.modes;
      }, function () { picShown = false; picCanSet = false; }));
    }
    return Promise.all(jobs).then(renderQuick);
  }

  // ---------------- Keep watching ----------------
  function subtitle(it) {
    var parts = [];
    if (it.app && it.app.name) parts.push(it.app.name);
    if (it.season && it.episode) parts.push("S" + it.season + ", E" + it.episode);
    if (it.episode_title) parts.push(it.episode_title);
    return parts.join("  ·  ");
  }

  // ---------------- who is watching: none, a pill switch for two, a head icon and a list for more ----------------
  var PALETTE = ["#ff9f0a", "#30b350", "#0a84ff", "#bf5af2", "#ff375f", "#00a5a8", "#5e5ce6", "#ac8e68"];
  function peopleCount() { return NOBODY ? 0 : PEOPLE.length; }
  function isHead() { return peopleCount() >= 3; }
  function colourOf(p) {
    if (p.colour) return p.colour;
    for (var h = 0, i = 0; i < p.id.length; i++) h = (h * 31 + p.id.charCodeAt(i)) >>> 0;
    return PALETTE[h % PALETTE.length];
  }
  // fork: a person's own picture (web/avatars/<id>.png, git ignores the folder) over their letter;
  // without one the picture removes itself and the letter shows
  // (once a picture has loaded it is remembered, so a redraw, as the sidebar does on every move,
  // shows it straight away instead of the letter's disc until it loads again)
  var FACES = window.tvFaces = {};
  function avatarHTML(p) {
    var face = p.id && FACES[p.id];
    return '<span class="avatar' + (face ? " has-face" : "") + '" style="background:' + esc(colourOf(p)) + '">' + esc((p.name || "?").trim().charAt(0).toUpperCase()) +
      (p.id ? '<img class="aface" src="avatars/' + esc(p.id) + '.png" alt="" onload="tvFaces[\'' + esc(p.id) + '\']=1;this.parentNode.classList.add(\'has-face\')" onerror="this.remove()">' : "") + "</span>";
  }
  function whoIndex() { return Math.max(0, PEOPLE.map(function (p) { return p.id; }).indexOf(who)); }

  function renderPeople() {
    var el = $("people"), n = peopleCount();
    el.classList.toggle("none", n < 2);
    el.classList.toggle("head", n >= 3);
    if (n >= 3) {
      var cur = PEOPLE[whoIndex()];
      el.innerHTML = avatarHTML(cur) + '<span class="sr">' + esc(cur.name) + "</span>";
    } else {
      el.innerHTML = PEOPLE.map(function (p) {
        return '<span class="person' + (p.id === who ? " on" : "") + '">' + esc(p.name) + "</span>";
      }).join("");
    }
    renderPop();
    renderSideTab();
  }

  function renderPop() {
    var el = $("people-pop");
    if (!el.classList.contains("on")) return;
    el.innerHTML = '<div class="plist">' + PEOPLE.map(function (p, i) {
      var cls = "prow" + (i === pmi ? " focus" : "") + (plift && i === pmi ? " lifted" : "") + (p.id === who ? " on" : "");
      var tail = plift && i === pmi ? '<i class="pmark">' + ICONS.updown + "</i>" : p.id === who ? '<i class="pmark">' + ICONS.check + "</i>" : "";
      return '<div class="' + cls + '">' + avatarHTML(p) + "<b>" + esc(p.name) + "</b>" + tail + "</div>";
    }).join("") + '</div><p class="phint">' + (plift ? t("people_lift_hint") : t("people_hold_hint")) + "</p>";
  }
  function openPop() {
    zone = "pmenu"; pmi = whoIndex(); plift = false;
    $("people-pop").classList.add("on");
    renderPop();
  }
  function closePop() {
    $("people-pop").classList.remove("on");
    $("people-pop").innerHTML = "";
    plift = false;
    zone = "people";
  }
  function pickWho(id) {
    if (id === who) return;
    who = id; s = 0;
    try { localStorage.setItem("tvhome.who", who); } catch (e) {}
    tellWho();
    if (!NOBODY) api("POST", "/api/who", { who: who }).catch(function () {});
    renderShelf();
  }
  // The order of the names: kept by the bridge (the phone's People sheet uses the same list), or on
  // this TV when there is no bridge. "pending" means the bridge has not heard about it yet.
  function savePeopleOrder() {
    var ids = PEOPLE.map(function (p) { return p.id; });
    keep("tvhome.people.order", { ids: ids, pending: true });
    if (OFFLINE || data.bridge === false) return;
    api("POST", "/api/people", { order: ids })
      .then(function () { keep("tvhome.people.order", { ids: ids, pending: false }); }, function () {});
  }
  function dropPeople() {
    plift = false;
    savePeopleOrder();
    renderPeople();
  }

  function renderShelf() {
    var track = $("shelf");
    data.shows = (data.showsBy[who] || []).map(function (it) {
      var a = pickedApp(it);
      return a ? Object.assign({}, it, { app: a }) : it;
    });
    var anyone = PEOPLE.some(function (p) { return (data.showsBy[p.id] || []).length; });
    // nobody has anything on the go: no empty box, the photo has the space
    $("shelf-section").classList.toggle("empty", !anyone);
    $("people").classList.toggle("empty", !anyone);
    renderPeople();
    if (!data.shows.length) {
      var name = PEOPLE[whoIndex()].name;
      track.innerHTML = '<p class="shelf-none">' + esc(peopleCount() < 2 ? t("nothing_yet") : t("nothing_for", { name: name })) + "</p>";
      shelfEls = [];
      return;
    }
    {
      // fork: Apple TV's Continue Watching cards, five in view and the rest scrolled to on the right
      // (scrollShelf): the artwork fills the card, the service's icon top right, and a blurred band
      // at the bottom with the title, a play symbol, how far in, and the episode (keepwatching.css)
      track.innerHTML = data.shows.map(function (it) {
        var art = it.backdrop || it.poster, pkg = it.app && it.app.package;
        var ep = it.season && it.episode ? "S" + it.season + ", E" + it.episode : (it.app ? it.app.name : "");
        var left = it.remaining_min || (it.runtime_min && it.progress ? Math.round(it.runtime_min * (1 - it.progress)) : 0);
        var meta = (it.next ? t("up_next") + " \u00b7 " : "") + ep + (left ? " \u00b7 " + (left >= 60 ? Math.floor(left / 60) + "h " + (left % 60) + "m" : left + "m") : "");
        return '<div class="tile ncard">' +
          (art ? '<img class="nart" src="' + esc(art) + '" alt="" decoding="async">' : '<span class="nart none">' + esc(it.title) + "</span>") +
          (pkg && iconUrl(pkg) ? '<img class="napp" src="' + esc(iconUrl(pkg)) + '" alt="" onerror="this.remove()">' : "") +
          '<div class="nband"><b>' + esc(it.title) + '</b><div class="nmeta">' +
            '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4.8v14.4a1 1 0 0 0 1.5.86l11.6-7.2a1 1 0 0 0 0-1.72L8.5 3.94A1 1 0 0 0 7 4.8z"/></svg>' +
            (it.progress ? '<span class="nbar"><i style="width:' + Math.max(4, Math.round(it.progress * 100)) + '%"></i></span>' : "") +
            "<span>" + esc(meta) + "</span></div></div></div>";
      }).join("");
      shelfEls = Array.prototype.slice.call(track.children);
      shelfEls.forEach(function (el) {
        var im = el.querySelector("img.nart");
        if (!im) return;
        if (im.complete && im.naturalWidth) bakeBlur(el, im); else im.addEventListener("load", function () { bakeBlur(el, im); }, { once: true });
      });
      return;
    }
  }
  // fork: the card's blurred band, drawn once: the artwork at an eighth of the card's size (shrinking
  // it is the blur; stretched back it is soft), faded in from the middle down (keepwatching.css)
  function bakeBlur(card, im) {
    if (card.querySelector(".nblur")) return;
    var W = 48, H = Math.round(W * card.offsetHeight / Math.max(1, card.offsetWidth)) || 30;
    var c = document.createElement("canvas");
    c.width = W; c.height = H; c.className = "nblur";
    var g = c.getContext("2d");
    var s = Math.max(W / im.naturalWidth, H / im.naturalHeight), w = im.naturalWidth * s, h = im.naturalHeight * s;
    g.imageSmoothingEnabled = true; g.imageSmoothingQuality = "high";
    g.filter = "saturate(140%)";
    g.drawImage(im, (W - w) / 2, (H - h) / 2, w, h);
    g.filter = "none";
    g.globalCompositeOperation = "destination-in";
    var fade = g.createLinearGradient(0, 0, 0, H);
    fade.addColorStop(0.48, "rgba(0,0,0,0)"); fade.addColorStop(0.74, "rgba(0,0,0,1)");
    g.fillStyle = fade; g.fillRect(0, 0, W, H);
    im.parentNode.insertBefore(c, im.nextSibling);
  }

  // ---------------- apps ----------------
  function tucked(a) {
    return TUCKED.indexOf(a.package) >= 0 || TUCKED_NAMES.test(a.name || "");
  }
  function isNew(pkg) { return newApps.indexOf(pkg) >= 0; }
  // The TV's apps less the ones hidden in Home settings (they come back from the Apps list there).
  function shownApps() { return data.apps.filter(function (a) { return HIDDEN_TV.indexOf(a.package) < 0; }); }
  function moreItem() { return { kind: "more", name: t("more_apps") }; }
  function gridList() { return dockList(); }

  // The dock: the first eleven apps (home.dock_size), then More apps (which shows
  // everything, the App Store first). The order and the More apps line come from Arrange apps on the
  // phone, or from moving apps with the TV remote (hold OK), once one is saved.
  function arrangeItems() {
    var rest = shownApps().filter(function (a) { return a.package !== SETTINGS_PKG && a.package !== STORE_PKG; });
    var screens = data.screens.map(function (sc) {
      return { kind: "screen", id: sc.id, name: sc.name, ready: sc.ready, note: sc.note, package: "screen:" + sc.id };
    });
    var items = rest.filter(function (a) { return !tucked(a); }).concat(screens, rest.filter(tucked));
    var line = Math.min(DOCK_APPS, rest.filter(function (a) { return !tucked(a); }).length);
    var o = data.order;
    if (o && o.saved) {
      var at = {};
      o.packages.forEach(function (p, i) { at[p] = i; });
      var known = items.filter(function (it) { return at[it.package] !== undefined; })
        .sort(function (a, b) { return at[a.package] - at[b.package]; });
      var gained = items.filter(function (it) { return at[it.package] === undefined; });   // new apps go below the line
      items = known.concat(gained);
      // the line counts the apps above it that this TV still has
      var above = o.packages.slice(0, o.line).filter(function (p) { return known.some(function (it) { return it.package === p; }); }).length;
      line = Math.min(DOCK_APPS, above);
    }
    return { items: items, line: line };
  }

  function dockList() {
    var store = data.apps.filter(function (a) { return a.package === STORE_PKG; })[0];
    var arr = arrangeItems();
    var list = expanded ? arr.items.slice() : arr.items.slice(0, arr.line);
    if (expanded && store) list.unshift({ kind: "app", store: true, name: t("app_store"), package: STORE_PKG, id: store.id });
    if (!expanded) list.push(moreItem());   // Settings are in the sidebar now (fork)
    return list;
  }

  // Tell the bridge which apps this TV has (in its default order), so the phone can arrange them.
  var reported = "";
  function reportApps() {
    if (MOCK || PREVIEW || !data.apps.length) return;
    var saved = data.order;
    data.order = null;   // the default order, not the arranged one
    var arr = arrangeItems();
    data.order = saved;
    var payload = { apps: arr.items.map(function (it) { return { package: it.package, name: it.name }; }), line: arr.line };
    var key = JSON.stringify(payload);
    if (key === reported) return;
    api("POST", "/api/home-apps", payload).then(function () { reported = key; }, function () {});
  }

  // ---------------- moving apps with the TV remote ----------------
  // The arrangement as it is on screen, written down as a saved order, so it can be changed.
  function materialise() {
    var arr = arrangeItems();
    data.order = { saved: true, line: arr.line, packages: arr.items.map(function (it) { return it.package; }) };
    return arr;
  }
  // Saved through the bridge (the phone's Arrange apps reads the same order), or on this TV with no
  // bridge. A TV-only order is marked pending and handed to the bridge the next time it answers.
  function persistOrder() {
    var o = data.order;
    var local = { line: o.line, packages: o.packages, pending: true };
    keep("tvhome.order.tv", local);
    save();
    if (OFFLINE || data.bridge === false) return;
    api("POST", "/api/app-order", { order: o.packages, line: o.line })
      .then(function () { local.pending = false; keep("tvhome.order.tv", local); }, function () {});
  }
  function nameOf(pkg) {
    var it = arrangeItems().items.filter(function (x) { return x.package === pkg; })[0];
    return it ? it.name : t("that_app");
  }
  var hintTimer = null, hintFlash = "";
  function renderHint() {
    var text = hintFlash ||
      (lift ? t("app_lift_hint") : expanded && zone === "grid" ? t("app_add_hint") : "");
    $("hint").textContent = text;
    $("hint").classList.toggle("on", !!text);
    document.documentElement.classList.toggle("hinting", !!text);
  }
  function flash(text) {
    clearTimeout(hintTimer);
    hintFlash = text;
    renderHint();
    hintTimer = setTimeout(function () { hintFlash = ""; renderHint(); }, 4000);
  }
  function holdApp() {
    var a = gridItems[g];
    if (!a || a.kind === "more") return false;
    if (a.store || a.kind === "gear") { flash(t("stays_put", { name: a.name })); return true; }
    var arr = arrangeItems();
    var idx = arr.items.map(function (it) { return it.package; }).indexOf(a.package);
    if (idx < 0) return false;
    if (!expanded) {
      materialise();
      lift = { pkg: a.package };
      hintFlash = "";
      renderGrid(); paintFocus();
      return true;
    }
    if (idx < arr.line) { flash(t("already_home", { name: a.name })); return true; }
    // More apps: add it to the end of the dock; a full dock sends its last app behind More apps
    var o = materialise() && data.order, msg = t("now_home", { name: a.name });
    o.packages.splice(idx, 1);
    if (o.line < DOCK_APPS) { o.packages.splice(o.line, 0, a.package); o.line += 1; }
    else {
      o.packages.splice(o.line - 1, 0, a.package);
      msg += " " + t("moved_more", { name: nameOf(o.packages[o.line]) });
    }
    persistOrder();
    expanded = false;
    renderGrid();
    g = o.packages.indexOf(a.package);
    paintFocus();
    flash(msg);
    return true;
  }
  function moveLift(dx, dy) {
    var o = data.order, i = o.packages.indexOf(lift.pkg);
    if (dx) {
      var j = i + dx;
      if (j >= 0 && j < o.line) { o.packages[i] = o.packages[j]; o.packages[j] = lift.pkg; g = j; }
    } else if (dy > 0) {   // behind More apps: first in line there
      var name = nameOf(lift.pkg);
      o.packages.splice(i, 1);
      o.packages.splice(o.line - 1, 0, lift.pkg);
      o.line -= 1;
      lift = null;
      persistOrder();
      renderGrid();
      g = Math.min(i, gridEls.length - 1);
      flash(t("moved_more", { name: name }));
    }
    renderGrid();
    paintFocus();
  }
  function dropLift() {
    lift = null;
    persistOrder();
    renderGrid();
    paintFocus();
    flash(t("saved"));
  }

  // Apps installed since this TV first listed its apps: the end of the dock when there is room,
  // otherwise first behind More apps, with a New dot until opened once.
  function detectNew() {
    if (!data.apps.length) return;
    var pk = data.apps.filter(function (a) { return a.package !== SETTINGS_PKG && a.package !== STORE_PKG; })
      .map(function (a) { return a.package; });
    var seen = kept("tvhome.apps.seen");
    if (!seen) { keep("tvhome.apps.seen", pk); return; }
    var fresh = pk.filter(function (p) { return seen.indexOf(p) < 0; });
    if (!fresh.length) return;
    keep("tvhome.apps.seen", seen.concat(fresh));
    newApps = newApps.concat(fresh.filter(function (p) { return newApps.indexOf(p) < 0; }));
    keep("tvhome.apps.new", newApps);
    materialise();
    var o = data.order;
    fresh.forEach(function (p) {
      var app = data.apps.filter(function (a) { return a.package === p; })[0];
      var i = o.packages.indexOf(p);
      if (i < 0 || i < o.line || (app && tucked(app))) return;
      o.packages.splice(i, 1);
      o.packages.splice(o.line, 0, p);
      if (o.line < DOCK_APPS) o.line += 1;
    });
    persistOrder();
  }
  function opened(pkg) {
    if (!isNew(pkg)) return;
    newApps = newApps.filter(function (p) { return p !== pkg; });
    keep("tvhome.apps.new", newApps);
    renderGrid();
  }

  function dockHTML(it) {
    var face;
    if (it.kind === "screen") face = '<div class="face tool">' + ICONS.screen + "</div>";
    else if (it.kind === "more") face = '<div class="face tool">' + ICONS.more + "</div>";
    else if (it.kind === "gear") face = '<div class="face tool">' + ICONS.gear + "</div>";
    else if (iconUrl(it.package)) face = '<div class="face"><img src="' + esc(iconUrl(it.package)) + '" alt="" decoding="async"></div>';
    else if (it.store) face = '<div class="face tool">' + ICONS.store + "</div>";
    else face = '<div class="face word">' + esc(it.name) + "</div>";
    var cls = "tile app dock-ic" + (lift && it.package === lift.pkg ? " lifted" : "");
    return '<div class="' + cls + '">' + face + (isNew(it.package) ? '<i class="dot" aria-label="' + esc(t("new_app")) + '"></i>' : "") +
      '<div class="label">' + esc(it.name) + "</div></div>";
  }

  function appHTML(it) { return dockHTML(it); }

  // fork: every app's icon is fetched and decoded ahead, so More apps opens with its icons in
  // place instead of empty tiles that fill in while it grows
  var warmed = {}, warmKeep = [];
  function warmIcons() {
    (data.apps || []).forEach(function (a) {
      var u = iconUrl(a.package);
      if (!u || warmed[u]) return;
      warmed[u] = true;
      var im = new Image();
      im.src = u;
      if (im.decode) im.decode().catch(function () {});
      warmKeep.push(im);   // held, so the decoded picture stays in memory
    });
  }

  function renderGrid() {
    warmIcons();
    document.documentElement.classList.toggle("expanded", expanded);
    document.documentElement.classList.toggle("lifting", !!lift);
    gridItems = gridList();
    // the dock is as wide as what is in it; More apps uses all twelve columns. A full dock (and More
    // apps) spans the Keep watching row edge to edge, its icons spread evenly (glass.css, .fill).
    GRID_COLS = expanded ? 12 : Math.max(1, Math.min(12, gridItems.length));
    $("grid").style.gridTemplateColumns = "repeat(" + GRID_COLS + ", 7rem)";
    $("apps-section").classList.toggle("fill", expanded || gridItems.length >= 10);
    $("grid").innerHTML = gridItems.map(appHTML).join("");
    gridEls = Array.prototype.slice.call($("grid").children);
    renderHint();
  }

  // ---------------- focus ----------------
  function clearFocus() {
    shelfEls.concat(gridEls, quickEls).forEach(function (el) { if (el) el.classList.remove("focus"); });
    $("people").classList.remove("focus");
    Array.prototype.forEach.call($("people").children, function (el) { el.classList.remove("focus"); });
    if ($("side-tab")) $("side-tab").classList.remove("focus");
    Array.prototype.forEach.call(document.querySelectorAll("#side-rows .focus, #np .focus"), function (el) { el.classList.remove("focus"); });
  }

  function scrollShelf() {
    var track = $("shelf");
    var el = shelfEls[s];
    if (!el) { track.style.transform = ""; return; }
    var step = el.offsetWidth + parseFloat(getComputedStyle(track).columnGap || 0);
    var first = Math.max(0, Math.min(s - 2, shelfEls.length - 5));   // keep up to five in view
    track.style.transform = "translateX(" + (-first * step) + "px)";
  }

  function shelfOn() { return !$("shelf-section").classList.contains("empty"); }
  // the switcher shows for two or more people, and only while someone has something on the go
  function peopleOn() { return peopleCount() >= 2 && shelfOn(); }
  function toPeople() { zone = "people"; pi = whoIndex(); }

  // ---- fork: the top shelf. Resting on a Keep watching card, its show fills the screen behind the
  // home, as on Apple TV: the artwork, the title and what is next, with the scene's clock and date
  // faded away. Moving along the row changes show only once focus has settled (passing cards cost
  // nothing), and the picture is decoded before it fades in, so the fade never stutters. The card's
  // own picture (1280 wide) is used, not a full-size one: fast to decode, and the TV draws at 1080p.
  var TS = { timer: null, on: false, front: 0, url: "", want: "" };

  function topShelf() {
    var it = zone === "shelf" && !expanded ? data.shows[s] : null, art = it && (it.backdrop || it.poster);
    if (!art) { clearTimeout(TS.timer); TS.want = ""; topShelfOff(); return; }
    // a redraw of the same card (a refresh, Now Playing) neither restarts the wait nor shows it again
    if (art === TS.want) return;
    clearTimeout(TS.timer);
    TS.want = art;
    TS.timer = setTimeout(function () { topShelfShow(it, art); }, TS.on ? 450 : 600);
  }
  function topShelfShow(it, art) {
    var box = $("topshelf"), pics = box.querySelectorAll(".ts-pic"), url = art;
    var ep = it.season && it.episode ? "S" + it.season + ", E" + it.episode : "";
    box.querySelector(".ts-title").textContent = it.title || "";
    box.querySelector(".ts-meta").textContent = [it.next ? t("up_next") : "", ep, it.episode_title || "", it.app ? it.app.name : ""]
      .filter(Boolean).join(" \u00b7 ");
    if (url !== TS.url) {
      TS.url = url;
      var back = pics[TS.front], next = pics[1 - TS.front];
      next.onload = function () {
        var show = function () { if (TS.url !== url) return; next.classList.add("on"); back.classList.remove("on"); TS.front = 1 - TS.front; };
        if (next.decode) next.decode().then(show, show); else show();
      };
      next.onerror = null;
      next.src = url;
    }
    if (!TS.on) { TS.on = true; document.documentElement.classList.add("shelfing"); if (window.tvAdapt) tvAdapt(); }
  }
  function topShelfOff() {
    clearTimeout(TS.timer); TS.want = "";
    if (!TS.on) return;
    TS.on = false; TS.url = "";
    document.documentElement.classList.remove("shelfing");
    if (window.tvAdapt) tvAdapt();
    // after the fade, let go of the pictures
    setTimeout(function () {
      if (TS.on) return;
      [].forEach.call($("topshelf").querySelectorAll(".ts-pic"), function (im) { im.classList.remove("on"); im.onload = null; im.removeAttribute("src"); });
    }, 900);
  }

  function paintFocus() {
    topShelf();
    if (zone === "settings") { renderSettings(); return; }
    clearFocus();
    renderHint();
    if (zone === "side") { renderSide(); return; }
    if (zone === "tab") { $("side-tab").classList.add("focus"); return; }
    if (zone === "np") { renderNp(); return; }
    if (zone === "gear") { if (gridEls[g]) gridEls[g].classList.add("focus"); renderGear(); return; }
    if (zone === "menu") { renderMenu(); return; }
    if (zone === "pmenu") { renderPop(); $("people").classList.add("focus"); return; }
    if (zone === "quick" && quickEls[qi]) {
      quickEls[qi].classList.add("focus");
      return;
    }
    if (zone === "people" && peopleOn()) {
      if (isHead()) $("people").classList.add("focus");
      else if ($("people").children[pi]) $("people").children[pi].classList.add("focus");   // drawn yet
      return;
    }
    if (zone === "shelf" && shelfEls[s]) {
      shelfEls[s].classList.add("focus");
      scrollShelf();
    } else {
      zone = "grid";
      g = Math.max(0, Math.min(g, gridEls.length - 1));
      if (gridEls[g]) gridEls[g].classList.add("focus");
    }
    renderHint();
  }

  function nearestInRow(row, x) {
    var best = row * GRID_COLS, bestD = Infinity;
    for (var i = row * GRID_COLS; i < Math.min(gridEls.length, (row + 1) * GRID_COLS); i++) {
      var b = gridEls[i].getBoundingClientRect();
      var d = Math.abs(b.left + b.width / 2 - x);
      if (d < bestD) { bestD = d; best = i; }
    }
    return best;
  }

  function move(dx, dy) {
    touched = true;
    if (zone === "side") { sideMove(dx, dy); return; }
    if (zone === "tab") { tabMove(dx, dy); return; }
    if (zone === "np") { npMove(dx, dy); return; }
    if (zone === "settings") { setMove(dx, dy); return; }
    if (zone === "gear") { if (dy) { gi = Math.max(0, Math.min(1, gi + dy)); renderGear(); } return; }
    if (lift) { moveLift(dx, dy); return; }
    // Night with More apps open: the cards step aside, so up and down skip them
    var cards = expanded ? [] : shelfEls;
    if (zone === "menu") {
      if (dx) menuI = Math.max(0, Math.min(SLEEP_CHOICES.length - 1, menuI + dx));
      paintFocus();
      return;
    }
    if (zone === "pmenu") {
      if (dy) {
        var n = Math.max(0, Math.min(PEOPLE.length - 1, pmi + dy));
        if (plift && n !== pmi) { var tmp = PEOPLE[pmi]; PEOPLE[pmi] = PEOPLE[n]; PEOPLE[n] = tmp; }
        pmi = n;
        renderPop();
      }
      return;
    }
    if (zone === "quick") {
      var nq = quickVisible().length;
      if (dx < 0 && qi === 0) { if (peopleOn()) toPeople(); }
      else if (dx) qi = Math.max(0, Math.min(nq - 1, qi + dx));
      else if (dy > 0) {
        if (peopleOn()) toPeople();
        else if (cards.length) zone = "shelf";
        else { zone = "grid"; g = Math.min(GRID_COLS, gridEls.length) - 1; }
      }
      paintFocus();
      return;
    }
    if (zone === "people") {
      if (dx > 0 && (isHead() || pi === PEOPLE.length - 1)) { if (hasQuick()) { zone = "quick"; qi = 0; } }
      else if (dx && !isHead()) {
        // moving across the names only moves the outline; OK picks (you have to click to select), so passing a name on the way to Sleep timer changes nothing
        pi = Math.max(0, Math.min(PEOPLE.length - 1, pi + dx));
      } else if (dy > 0) zone = cards.length ? "shelf" : "grid";
    } else if (zone === "shelf") {
      if (dx < 0 && s === 0) { openSide(); return; }
      if (dy < 0) { upTop(shelfEls[s]); return; }   // up from the cards: the person tab or Now Playing
      if (dx) s = Math.max(0, Math.min(shelfEls.length - 1, s + dx));
      else if (dy > 0 && gridEls.length) {
        // land on the app under the show
        var b = shelfEls[s].getBoundingClientRect();
        g = nearestInRow(0, b.left + b.width / 2);
        zone = "grid";
      }
    } else {
      var row = Math.floor(g / GRID_COLS), lastRow = Math.floor((gridEls.length - 1) / GRID_COLS);
      if (dx < 0 && g % GRID_COLS === 0) { openSide(); return; }
      if (dx) {
        var nx = g + dx;
        if (nx >= 0 && nx < gridEls.length && Math.floor(nx / GRID_COLS) === row) g = nx;
      } else if (dy < 0 && row === 0) {
        if (cards.length) zone = "shelf";
        else { upTop(gridEls[g]); return; }
      } else if (dy < 0) {
        g -= GRID_COLS;
      } else if (row < lastRow) {
        g = Math.min(g + GRID_COLS, gridEls.length - 1);
      }
    }
    paintFocus();
  }

  // fork: start on the first app in the dock (not the first Keep watching card)
  function startFocus() {
    s = 0; g = 0;
    zone = "grid";
  }

  function goHome(back) {
    if (zone === "side") { sideBack(); return; }
    if (zone === "tab") { fromTab(); return; }
    if (zone === "np") { fromNp(); return; }
    if (zone === "settings") { setBack(); return; }
    if (zone === "gear") { closeGear(); paintFocus(); return; }
    if (lift) { dropLift(); return; }   // BACK puts a picked-up app down where it is
    if (zone === "pmenu") { if (plift) dropPeople(); else closePop(); paintFocus(); return; }
    if (zone === "menu") { closeMenu(); paintFocus(); return; }   // BACK just closes the sleep choices
    if (expanded) { expanded = false; renderGrid(); startFocus(); paintFocus(); return; }
    var atStart = zone === "grid" && s === 0 && g === 0;
    if (back === true && atStart) { openSide(); return; }   // tvOS: Back from the content goes to the sidebar
    startFocus();
    paintFocus();
  }

  // ---------------- actions ----------------
  // A long press of OK (or the Menu key): pick up an app or a name to move it. Returns false when
  // there is nothing to pick up here, and then the press counts as a normal OK.
  function hold() {
    touched = true;
    if (zone === "side" || zone === "tab" || zone === "np") return false;
    if (zone === "settings") return setHold();
    if (zone === "gear") return false;
    if (lift) { dropLift(); return true; }
    if (zone === "pmenu") {
      if (plift) dropPeople();
      else if (PEOPLE.length > 1) { plift = true; renderPop(); }
      return true;
    }
    if (zone === "grid") return holdApp();
    if (zone === "shelf") return holdShow();
    return false;
  }

  function activate() {
    touched = true;
    if (zone === "side") { sideActivate(); return; }
    if (zone === "tab") { openSide(); return; }
    if (zone === "np") { npAct(NP_ITEMS[npi]); return; }
    if (zone === "settings") { setActivate(); return; }
    if (zone === "gear") { if (gi === 0) openSettings(); else openTvSettings(); return; }
    if (lift) { dropLift(); return; }
    if (zone === "menu") { setSleep(SLEEP_CHOICES[menuI]); paintFocus(); return; }
    if (zone === "pmenu") {
      if (plift) { dropPeople(); return; }
      pickWho(PEOPLE[pmi].id);
      closePop();
      renderPeople();
      paintFocus();
      return;
    }
    if (zone === "quick") { quickAction(); return; }
    if (zone === "people") {
      if (isHead()) { openPop(); paintFocus(); return; }
      pickWho(PEOPLE[pi].id);
      paintFocus();
      return;
    }
    if (zone === "shelf") {
      var it = data.shows[s];
      if (!it) return;
      var resume = function () {
        if (it.source === "tv") {   // fork: recorded on this TV; the app resumes the episode itself
          if (!(TV && it.app && TV.launch(it.app.package)) && window.tvMotion) tvMotion.abort();
          return;
        }
        status(it.app ? t("opening_in", { title: it.title, app: it.app.name }) : t("opening", { title: it.title }), true);
        api("POST", "/api/continue/open", { id: it.id, who: NOBODY ? "both" : who }, 60000)
          .then(function (res) { status(res.note || ""); })
          .catch(function (e) {
            // the bridge can't help: at least open the app it was in
            if (TV && it.app && TV.launch(it.app.package)) { status(""); return; }
            if (window.tvMotion) tvMotion.abort();
            status(e.message);
          });
      };
      if (window.tvMotion) tvMotion.launch(shelfEls[s], resume); else resume();   // motion.js: zoom, then open
      return;
    }
    var a = gridItems[g], el = gridEls[g];
    if (!a) return;
    if (a.kind === "more") { expanded = true; renderGrid(); paintFocus(); return; }
    if (a.kind === "gear") { openGear(); return; }
    if (a.kind === "screen") {
      if (!a.ready) { status(a.note || t("screen_not_set")); return; }
      el.classList.add("busy");
      api("POST", "/api/screens/open", { id: a.id }, 10000)
        .then(function (res) { status(res.note || ""); })
        .catch(function (e) {
          if (TV && TV.launch("com.limelight")) { status(""); return; }
          status(e.message);
        })
        .then(function () { el.classList.remove("busy"); });
      return;
    }
    opened(a.package);
    var go = function () {
      if (TV && TV.launch(a.package)) return;
      api("POST", "/api/launch", { app: a.id || a.package })
        .catch(function (e) { if (window.tvMotion) tvMotion.abort(); status(e.message); });
    };
    if (window.tvMotion) tvMotion.launch(el, go); else go();   // motion.js: zoom, then open
  }

  // ---------------- the gear tile's menu: Home settings or the TV's own settings ----------------
  var gi = 0;
  function openGear() {
    zone = "gear"; gi = 0;
    renderGear();
  }
  function renderGear() {
    var pop = $("gear-pop");
    var items = [[ICONS.sliders, t("gear_home"), t("gear_home_sub")], [ICONS.gear, t("gear_tv"), t("gear_tv_sub")]];
    pop.innerHTML = items.map(function (it, i) {
      return '<div class="grow' + (i === gi ? " focus" : "") + '" role="menuitem"><i>' + it[0] + "</i><span><b>" + esc(it[1]) +
        "</b><small>" + esc(it[2]) + "</small></span></div>";
    }).join("");
    pop.classList.add("on");
    document.documentElement.classList.add("gearing");
    // above the gear tile, kept inside the screen's safe area
    var el = gridEls[g], rem = innerWidth / 120;
    if (el) {
      var b = el.getBoundingClientRect(), w = pop.offsetWidth;
      pop.style.left = Math.max(5 * rem, Math.min(innerWidth - w - 5 * rem, b.left + b.width / 2 - w / 2)) + "px";
      pop.style.top = Math.max(3.75 * rem, b.top - pop.offsetHeight - 2.25 * rem) + "px";
    }
  }
  function closeGear() {
    $("gear-pop").classList.remove("on");
    document.documentElement.classList.remove("gearing");
    if (zone === "gear") zone = "grid";
  }
  // Home Assistant scenes (fork). The launcher keeps the address and token and makes the call; the
  // page only lists the scene names and shows how each run went. The mock has sample scenes.
  var HA_STATE = {}, HA_TIMERS = {};
  var HA_ICON = '<svg viewBox="0 0 24 24"><path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"/></svg>';
  // icons by name, written as in Home Assistant ("mdi:sofa" or "sofa"); drawn in the sidebar's
  // own thin line style rather than copied from Material Design. Anything else: the light bulb.
  var HA_ICONS = {
    sofa: '<svg viewBox="0 0 24 24"><path d="M5 11V8.5A2.5 2.5 0 0 1 7.5 6h9A2.5 2.5 0 0 1 19 8.5V11"/><path d="M3.5 11A1.5 1.5 0 0 1 5 12.5V14h14v-1.5a1.5 1.5 0 0 1 3 0V17a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-4.5A1.5 1.5 0 0 1 3.5 11z"/><path d="M5 18v2M19 18v2"/></svg>',
    "weather-sunset": '<svg viewBox="0 0 24 24"><path d="M2.5 18h19M7 18a5 5 0 0 1 10 0"/><path d="M12 7.5V10M5.3 11.3l1.8 1.8M18.7 11.3l-1.8 1.8M7.5 21h9"/></svg>',
    sun: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/></svg>',
    moon: ICONS.moon, "weather-night": ICONS.moon
  };
  function haIcon(name) {
    var n = String(name || "").replace(/^mdi:/, "");
    return HA_ICONS[n === "weather-sunny" || n === "white-balance-sunny" ? "sun" : n] || HA_ICON;
  }
  var MOCK_SCENES = [{ id: "script.good_morning", name: "Morning", icon: "mdi:weather-sunny" }, { id: "script.good_night", name: "Night", icon: "moon" }, { id: "script.sofa", name: "Sofa", icon: "mdi:sofa" }];
  function haScenes() {
    if (TV && TV.haScenes) { try { return JSON.parse(TV.haScenes()) || []; } catch (e) { return []; } }
    return MOCK ? MOCK_SCENES : [];
  }
  function haRun(id) {
    HA_STATE[id] = "busy"; clearTimeout(HA_TIMERS[id]);
    if (zone === "side") renderSide();
    if (TV && TV.haRun) TV.haRun(id);
    else if (MOCK) setTimeout(function () { haDone(id, true); }, 500);
  }
  function haDone(id, ok) {
    HA_STATE[id] = ok ? "ok" : "fail";
    if (zone === "side") renderSide();
    HA_TIMERS[id] = setTimeout(function () { delete HA_STATE[id]; if (zone === "side") renderSide(); }, ok ? 2500 : 5000);
  }

  // Trakt (fork): each person links their own account with a code (the launcher keeps the sign-in,
  // Trakt.kt). The mock links after a few seconds and has one sample next episode.
  var traktMock = { linked: {}, linking: {} };
  // fork: Trakt, linked per person (Home settings, People, the person); pid defaults to who
  function traktOn() {
    if (TV && TV.traktState) { try { return JSON.parse(TV.traktState(who)).ready !== false; } catch (e) { return false; } }
    return MOCK;
  }
  function traktState(pid) {
    pid = pid || who;
    if (TV && TV.traktState) { try { return JSON.parse(TV.traktState(pid)); } catch (e) { return {}; } }
    if (traktMock.linked[pid]) return { linked: true, user: pid + "_on_trakt" };
    if (traktMock.linking[pid]) return { linking: true, code: "8F3A C21D", url: "https://trakt.tv/activate" };
    return { ready: true };
  }
  function traktLink(pid) {
    var w = pid || who;
    if (TV && TV.traktLink) { TV.traktLink(w); return; }
    traktMock.linking[w] = true;
    setTimeout(function () { delete traktMock.linking[w]; traktMock.linked[w] = true; window.tv.trakt(w); }, 4000);
  }
  function traktCancel(pid) { var w = pid || who; if (TV && TV.traktCancel) TV.traktCancel(w); else delete traktMock.linking[w]; }
  function traktUnlink(pid) { var w = pid || who; if (TV && TV.traktUnlink) TV.traktUnlink(w); else delete traktMock.linked[w]; }
  // the person's Trakt rows in their settings page: link, the code while linking, or unlink
  function traktRows(pid) {
    if (!traktOn()) return [];
    var ts = traktState(pid), rows = [];
    if (ts.linked) {
      rows.push(info("trakt-on", "Trakt", ts.user, t("trakt_about")));
      rows.push({ kind: "action", key: "trakt-unlink", label: t("trakt_unlink"), danger: true, act: function () { traktUnlink(pid); refresh(); } });
    } else if (ts.linking) {
      rows.push(info("trakt-code", t("trakt_go", { url: (ts.url || "https://trakt.tv/activate").replace(/^https?:\/\//, "") }), ts.code));
      rows.push({ kind: "action", key: "trakt-cancel", label: t("trakt_cancel"), act: function () { traktCancel(pid); } });
    } else {
      rows.push({ kind: "action", key: "trakt-link", label: t("trakt_link"), sub: t("trakt_about"), act: function () { traktLink(pid); } });
    }
    return rows;
  }

  // Google Play (fork: the sidebar's Store): straight from the launcher, else through the bridge.
  function openStore() {
    opened(STORE_PKG);
    if (TV && TV.launch(STORE_PKG)) return;
    var st = data.apps.filter(function (a) { return a.package === STORE_PKG; })[0];
    if (OFFLINE) { status(t("store_none")); return; }
    api("POST", "/api/launch", { app: (st && st.id) || STORE_PKG }).catch(function () { status(t("store_none")); });
  }
  // Android's own settings: the Settings app where the TV has one, else the system settings screen.
  function openTvSettings() {
    closeGear();
    paintFocus();
    var done = false;
    try { done = !!(TV && ((TV.launch && TV.launch(SETTINGS_PKG)) || (TV.openSettings && TV.openSettings()))); } catch (e) {}
    if (done) return;
    if (OFFLINE) { status(t("tv_settings_none")); return; }
    api("POST", "/api/launch", { app: "settings" }).catch(function () { status(t("tv_settings_none")); });
  }

  // ---------------- the sidebar (fork): who is watching, the quick buttons and Settings ----------------
  // After the Apple TV app's sidebar: a glass panel that slides in from the left over the home. Left
  // at the start of a row, or Back from the start of the home, opens it; Right or Back closes it and
  // focus goes back where it was. At the top, who is watching (OK lists everyone); then the quick
  // buttons (tvs[].quick); then Settings: couch-tv's own and the TV's. The sleep timer and the
  // people open a second level in the same panel; Back steps out of it.
  var SIDE = { level: "main", i: 0, from: null }, sideTime = "";
  var SIDE_MENU = '<svg viewBox="0 0 24 24"><path d="M4 7h16M4 12h16M4 17h16"/></svg>';
  function sideItems() {
    var rows = [];
    if (SIDE.level === "sleep") {
      var m = minutesLeft();
      rows.push({ title: t("sleep_timer") });
      SLEEP_CHOICES.forEach(function (c) {
        rows.push({ kind: "sleep", mins: c, label: c ? t("n_min", { n: c }) : t("timer_off"), check: !c && !m });
      });
      if (m) rows.push({ note: t("off_in", { m: m }) });
      return rows;
    }
    if (SIDE.level === "app" && SIDE.show) {
      var cur = showPicks()[SIDE.show.key];
      rows.push({ title: t("show_app", { title: SIDE.show.title }) });
      rows.push({ kind: "app-auto", icon: ICONS.check, label: t("show_app_auto"), check: !cur });
      pickApps().forEach(function (a) {
        var u = iconUrl(a.package);
        rows.push({ kind: "app-pick", pkg: a.package, label: a.name, check: a.package === cur,
          avatar: u ? '<img class="sapp" src="' + esc(u) + '" alt="">' : "" });
      });
      return rows;
    }
    if (SIDE.level === "who") {
      rows.push({ title: t("who_watching") });
      PEOPLE.forEach(function (p) { rows.push({ kind: "pick", id: p.id, label: p.name, avatar: avatarHTML(p), check: p.id === who }); });
      return rows;
    }
    if (peopleCount() >= 2) {
      var cur = PEOPLE[whoIndex()];
      rows.push({ kind: "who", label: cur.name, avatar: avatarHTML(cur), value: sideTime, head: true });
    } else rows.push({ head: true, label: t("settings"), value: sideTime });
    rows.push({ kind: "store", icon: ICONS.store, label: t("store") });   // Google Play, as the TV app's Store
    quickVisible().forEach(function (q) {
      if (q === "sleep") {
        var left = minutesLeft();
        rows.push({ kind: "sleep-open", icon: ICONS.moon, label: t("sleep_timer"), value: left ? t("n_min", { n: left }) : t("timer_off") });
      } else if (q === "picture") rows.push({ kind: "picture", icon: ICONS.picture, label: t("picture_mode"), value: picLabel() });
      else if (q === "input") rows.push({ kind: "input", icon: ICONS.input, label: t("input") });
    });
    // fork: Home Assistant scenes (the launcher runs them; ha.json picks which)
    var scenes = haScenes();
    if (scenes.length) {
      rows.push({ title: t("home_scenes") });
      scenes.forEach(function (sc) {
        var st = HA_STATE[sc.id];
        rows.push({ kind: "ha", id: sc.id, icon: haIcon(sc.icon), label: sc.name || sc.id,
          value: st === "busy" ? "\u2026" : st === "fail" ? "!" : "", check: st === "ok" });
      });
    }
    rows.push({ title: t("settings") });
    rows.push({ kind: "home-settings", icon: ICONS.sliders, label: t("gear_home") });
    rows.push({ kind: "tv-settings", icon: ICONS.gear, label: t("gear_tv") });
    return rows;
  }
  function sideFocusable(r) { return !!r.kind; }
  function sideRows() { return sideItems().filter(sideFocusable); }
  function renderSide() {
    var rows = sideItems(), k = 0;
    SIDE.i = Math.max(0, Math.min(SIDE.i, sideRows().length - 1));
    $("side-rows").innerHTML = rows.map(function (r) {
      if (r.title) return '<p class="side-sec">' + esc(r.title) + "</p>";
      if (r.note) return '<p class="side-note">' + esc(r.note) + "</p>";
      var f = sideFocusable(r) && k++ === SIDE.i;
      var cls = "side-row" + (r.head ? " side-head" : "") + (f ? " focus" : "") + (sideFocusable(r) ? "" : " still");
      return '<div class="' + cls + '">' + (r.avatar || (r.icon ? '<i class="sic">' + r.icon + "</i>" : "")) +
        "<b>" + esc(r.label) + "</b>" + (r.value ? '<span class="sv">' + esc(r.value) + "</span>" : "") +
        (r.check ? '<i class="sck">' + ICONS.check + "</i>" : "") + "</div>";
    }).join("");
  }
  function renderSideTab() {
    var el = $("side-tab");
    if (!el) return;
    el.innerHTML = peopleCount() >= 2 ? avatarHTML(PEOPLE[whoIndex()]) + "<b>" + esc(PEOPLE[whoIndex()].name) + "</b>"
      : '<i class="sic">' + SIDE_MENU + "</i>";
  }
  // ---- fork: which app a show is watched in, picked by the household. Hold OK on a Keep watching
  // card: the sidebar lists the dock's apps; the pick is kept per show on this TV and wins over
  // what the show's network or TMDB suggest. "Automatic" forgets it.
  function showKey(it) { return String(it.title || "").toLowerCase().replace(/[^a-z0-9]+/g, ""); }
  function showPicks() { return kept("tvhome.showapp") || {}; }
  function pickedApp(it) {
    var pkg = showPicks()[showKey(it)];
    var a = pkg && (data.apps || []).filter(function (x) { return x.package === pkg; })[0];
    return a ? { package: a.package, name: a.name } : null;
  }
  function holdShow() {
    var it = data.shows[s];
    if (!it) return false;
    SIDE.show = { key: showKey(it), title: it.title };
    openSide();
    var apps = pickApps(), cur = showPicks()[SIDE.show.key];
    sideLevel("app", cur ? 1 + Math.max(0, apps.map(function (a) { return a.package; }).indexOf(cur)) : 0);
    return true;
  }
  function pickApps() {   // the dock's apps, in the dock's order
    return gridItems.filter(function (a) { return a.package && a.kind !== "more" && a.kind !== "gear" && !a.store; });
  }
  function setShowApp(pkg) {
    var picks = showPicks();
    if (pkg) picks[SIDE.show.key] = pkg; else delete picks[SIDE.show.key];
    keep("tvhome.showapp", picks);
    renderShelf();
  }

  function openSide() {
    if (zone === "side") return;
    SIDE.from = { zone: zone, s: s, g: g };
    SIDE.level = "main"; SIDE.i = 0;
    zone = "side";
    clearFocus();
    document.documentElement.classList.add("siding");
    $("side").setAttribute("aria-hidden", "false");
    renderSide();
  }
  function closeSide() {
    if (zone !== "side") return;
    document.documentElement.classList.remove("siding");
    $("side").setAttribute("aria-hidden", "true");
    var f = SIDE.from || { zone: "grid", s: 0, g: 0 };
    zone = f.zone; s = f.s; g = f.g;
    if (zone === "side" || zone === "settings") zone = "grid";
    paintFocus();
  }
  // The person tab top left takes focus too: up from the top row reaches it, OK opens the sidebar
  // (on who is watching), Left opens it as well, Down or Back goes back where you were.
  var tabFrom = null;
  function toTab() { tabFrom = { zone: zone, s: s, g: g }; zone = "tab"; paintFocus(); }
  function fromTab() {
    var f = tabFrom || { zone: "grid", s: 0, g: 0 };
    zone = f.zone; s = f.s; g = f.g;
    paintFocus();
  }
  function tabMove(dx, dy) {
    if (dy > 0) fromTab();
    else if (dx < 0) openSide();
    else if (dx > 0 && npOn()) toNp(tabFrom);
  }
  // Up from the top row: the corner over it. The person tab is top left, Now Playing top right.
  function upTop(el) {
    if (npOn() && el) {
      var b = el.getBoundingClientRect();
      if (b.left + b.width / 2 > innerWidth / 2) { toNp({ zone: zone, s: s, g: g }); return; }
    }
    toTab();
  }
  function sideLevel(level, i) { SIDE.level = level; SIDE.i = i || 0; renderSide(); }
  function sideMove(dx, dy) {
    if (dx > 0) { closeSide(); return; }
    if (dx < 0) { if (SIDE.level !== "main") sideBack(); return; }
    SIDE.i = Math.max(0, Math.min(sideRows().length - 1, SIDE.i + dy));
    renderSide();
  }
  function sideBack() {
    if (SIDE.level === "app") { closeSide(); return; }   // it was opened from a card: back to it
    if (SIDE.level === "sleep") {   // back onto the Sleep timer row
      SIDE.level = "main";
      sideLevel("main", Math.max(0, sideRows().map(function (r) { return r.kind; }).indexOf("sleep-open")));
      return;
    }
    if (SIDE.level === "who") { sideLevel("main", 0); return; }
    closeSide();
  }
  function sideActivate() {
    var r = sideRows()[SIDE.i];
    if (!r) return;
    if (r.kind === "who") { sideLevel("who", whoIndex()); return; }
    if (r.kind === "pick") { pickWho(r.id); renderPeople(); sideLevel("main", 0); return; }
    if (r.kind === "sleep-open") {
      var m = minutesLeft();
      sideLevel("sleep", m ? SLEEP_CHOICES.length - 1 : 1);   // a timer is running: start on Off; otherwise 30 min
      return;
    }
    if (r.kind === "sleep") {
      setSleep(r.mins);   // it closes the old sleep menu, which moves focus to the quick buttons
      zone = "side";
      sideBack();
      return;
    }
    if (r.kind === "picture") { togglePicture(); renderSide(); return; }
    if (r.kind === "input") { closeSide(); openInputs(); return; }
    if (r.kind === "store") { closeSide(); openStore(); return; }
    if (r.kind === "ha") { haRun(r.id); return; }   // the sidebar stays open: run another, or go
    if (r.kind === "app-pick" || r.kind === "app-auto") { setShowApp(r.kind === "app-pick" ? r.pkg : null); closeSide(); return; }
    if (r.kind === "home-settings") { closeSide(); openSettings(); return; }
    if (r.kind === "tv-settings") { closeSide(); openTvSettings(); return; }
  }

  // ---------------- Now Playing (fork): music from Home Assistant ----------------
  // A Liquid Glass card top right, there only while something plays, laid out like the iOS lock
  // screen player: artwork, title and artist and a level meter on top; elapsed, the progress bar and
  // remaining in the middle; rewind, play/pause and fast forward below, with the speaker button at
  // the end. The symbols stand on the glass with no buttons behind them; the focused one gets the
  // white circle (a TV remote needs to see focus). Up from a card on the right half (or Right from
  // the person tab) reaches it, landing on play/pause (the group's main action). Only the three
  // controls take focus: the title is not a button, and the speaker symbol waits for Home
  // Assistant's speaker list before it does anything. Fed by window.tvNowPlaying(state) (Home Assistant,
  // later); the mock plays sample tracks. ?np=0 hides it in the mock.
  var NP = null, npi = 1, npFrom = null, npTimer = null;
  var NP_ITEMS = ["prev", "play", "next"];
  var NP_ICONS = {
    // rewind and fast forward: two equal triangles with soft corners, the pair centred, then moved
    // one unit against their heavy flat backs so they look centred in the focus circle (optical
    // centring, as Apple's symbols do)
    prev: '<svg viewBox="0 0 32 24" class="glyph"><path transform="translate(-1 0)" d="M15.5 5L3 12l12.5 7zM29 5L16.5 12 29 19z"/></svg>',
    next: '<svg viewBox="0 0 32 24" class="glyph"><path transform="translate(1 0)" d="M3 5l12.5 7L3 19zM16.5 5L29 12l-12.5 7z"/></svg>',
    play: '<svg viewBox="0 0 24 24" class="solid"><path d="M6.5 4.6v14.8a1 1 0 0 0 1.5.9l12-7.4a1 1 0 0 0 0-1.8l-12-7.4a1 1 0 0 0-1.5.9z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" class="solid"><rect x="5" y="3.5" width="4.6" height="17" rx="1.3"/><rect x="14.4" y="3.5" width="4.6" height="17" rx="1.3"/></svg>',
    route: '<svg viewBox="0 0 24 24"><path d="M6.3 16.6a8 8 0 1 1 11.4 0M8.7 14.3a4.6 4.6 0 1 1 6.6 0"/><path d="M12 14.5l4.2 5.5H7.8z" class="fill"/></svg>',
    note: '<svg viewBox="0 0 24 24"><path d="M9 18.5V5.5l10-2v13"/><circle cx="6.5" cy="18.5" r="2.5"/><circle cx="16.5" cy="16.5" r="2.5"/></svg>'
  };
  var MOCK_TRACKS = [   // synthetic, for the mock only
    { title: "Golden Hour Drive", artist: "The Paper Lanterns", album: "Coastal Lines", duration: 214, art: "linear-gradient(135deg, #ff9a5a, #c2366b 55%, #4a1e6e)" },
    { title: "Low Tide", artist: "Mara Quell", album: "Saltwater", duration: 187, art: "linear-gradient(160deg, #3fd1c9, #1b6fa8 60%, #142a52)" },
    { title: "Neon Rain", artist: "Kito & The Hours", album: "After Dark", duration: 241, art: "linear-gradient(140deg, #b06bff, #3a3fd8 55%, #10123a)" }
  ];
  var mockTrack = 0;
  function npOn() { return !!(NP && (NP.state === "playing" || NP.state === "paused")); }
  function mmss(sec) { sec = Math.max(0, Math.round(sec)); return Math.floor(sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60); }
  function npArt(cls) {
    return '<span class="' + cls + '" style="background:' + esc(NP.art || "rgba(255,255,255,0.14)") + '">' + (NP.picture ? "" : NP_ICONS.note) +
      (NP.picture ? '<img src="' + esc(NP.picture) + '" alt="">' : "") + "</span>";
  }
  function renderNp() {
    var el = $("np");
    el.classList.toggle("on", npOn());
    el.classList.toggle("playing", !!NP && NP.state === "playing");
    el.setAttribute("aria-hidden", npOn() ? "false" : "true");
    if (!npOn()) { el.innerHTML = ""; return; }
    var f = function (k) { return zone === "np" && NP_ITEMS[npi] === k ? " focus" : ""; };
    el.innerHTML = '<div class="np-top"><div class="np-info">' + npArt("np-art") +
        '<span class="np-txt"><b>' + esc(NP.title) + "</b><small>" + esc(NP.artist) + "</small></span></div>" +
        '<span class="np-level" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span></div>' +
      '<div class="np-time"><span class="np-el"></span><span class="np-bar"><i></i></span><span class="np-rem"></span></div>' +
      '<div class="np-ctl">' +
        '<span class="np-btn' + f("prev") + '" aria-label="Previous">' + NP_ICONS.prev + "</span>" +
        '<span class="np-btn np-main' + f("play") + '" aria-label="' + (NP.state === "playing" ? "Pause" : "Play") + '">' +
          (NP.state === "playing" ? NP_ICONS.pause : NP_ICONS.play) + "</span>" +
        '<span class="np-btn' + f("next") + '" aria-label="Next">' + NP_ICONS.next + "</span>" +
        '<span class="np-route" aria-hidden="true">' + NP_ICONS.route + "</span>" +
      "</div>";
    npTime();
  }
  // the elapsed and remaining times and the bar, every second, without redrawing the card
  function npTime() {
    var el = $("np");
    if (!npOn() || !el.querySelector(".np-bar")) return;
    var pos = NP.position || 0, dur = NP.duration || 0;
    el.querySelector(".np-el").textContent = mmss(pos);
    el.querySelector(".np-rem").textContent = dur ? "\u2212" + mmss(dur - pos) : "";
    el.querySelector(".np-bar i").style.width = (dur ? Math.min(100, pos / dur * 100) : 0).toFixed(2) + "%";
  }
  function toNp(from) {
    npFrom = from || { zone: zone, s: s, g: g };
    tabFrom = npFrom;
    zone = "np"; npi = 1;   // play/pause
    paintFocus();
  }
  function fromNp() {
    var f = npFrom || { zone: "grid", s: 0, g: 0 };
    zone = f.zone === "tab" ? "tab" : f.zone; s = f.s; g = f.g;
    paintFocus();
  }
  // One row of controls: previous, play/pause, next.
  function npMove(dx, dy) {
    if (dy > 0) { fromNp(); return; }
    if (!dx) return;
    if (dx < 0 && npi === 0) { zone = "tab"; paintFocus(); return; }   // on to the person tab
    npi = Math.max(0, Math.min(NP_ITEMS.length - 1, npi + dx));
    renderNp();
  }
  function npAct(what) {
    if (!NP) return;
    if (MOCK) mockNp(what);
    else if (TV && TV.haPlayer) {   // fork: Home Assistant's speaker (MainActivity), shown at once
      TV.haPlayer(what);
      if (what === "play") NP.state = NP.state === "playing" ? "paused" : "playing";
    }
    renderNp();
  }
  // Home Assistant (MainActivity, the speakers in ha.json "players") hands the player's state here: { state: "playing" | "paused" | "idle",
  // title, artist, album, picture (artwork URL) or art (a CSS background), duration, position, speaker }
  window.tvNowPlaying = function (st) {
    var was = npOn();
    NP = st;
    // the time moves on here every second; the launcher only sends a new song, state or jump
    if (!MOCK && !npTimer) npTimer = setInterval(function () {
      if (!NP || NP.state !== "playing") return;
      NP.position = Math.min((NP.position || 0) + 1, NP.duration || Infinity);
      npTime();
    }, 1000);
    if (!npOn() && zone === "np") fromNp();
    renderNp();
    if (was !== npOn() && window.tvMotion && tvMotion.np) tvMotion.np(npOn());
  };
  function mockNp(what) {
    if (what === "play") NP.state = NP.state === "playing" ? "paused" : "playing";
    else if (what === "next" || what === "prev") {
      if (what === "prev" && NP.position > 3) NP.position = 0;   // like every player: back to the start first
      else {
        mockTrack = (mockTrack + (what === "next" ? 1 : MOCK_TRACKS.length - 1)) % MOCK_TRACKS.length;
        var tr = MOCK_TRACKS[mockTrack];
        NP = { state: "playing", title: tr.title, artist: tr.artist, album: tr.album, art: tr.art, duration: tr.duration, position: 0, speaker: NP.speaker };
      }
    }
  }
  function startMockNp() {
    if (!MOCK || /[?&]np=0\b/.test(Q)) return;
    var tr = MOCK_TRACKS[0];
    window.tvNowPlaying({ state: "playing", title: tr.title, artist: tr.artist, album: tr.album, art: tr.art, duration: tr.duration, position: 64, speaker: "Living room speaker" });
    clearInterval(npTimer);
    npTimer = setInterval(function () {
      if (!NP || NP.state !== "playing") return;
      NP.position = (NP.position || 0) + 1;
      if (NP.position >= NP.duration) { mockNp("next"); renderNp(); }
      npTime();
    }, 1000);
  }

  // ---------------- Home settings: couch-tv's own settings screen ----------------
  // A tvOS style list: the sections on the left, their rows on the right. Up and down move, OK
  // changes a row, holding OK picks a row up to move it (scenes, apps, people, quick buttons), Back
  // goes back a step and then closes. What is saved, and which copy wins: docs/CONFIG.md.
  // Saved on this TV (page storage and the launcher's own store, TVOS.keep) and, with a bridge,
  // also on the computer (/api/settings), so the phone and the TV agree.
  var ST_KEY = "tvhome.settings";
  var SECS = [["language", "globe"], ["location", "pin"], ["weather", "cloud"], ["look", "look"],
    ["apps", "apps"], ["people", "person"], ["quick", "bolt"], ["about", "info"]];
  var SET = { col: "secs", sec: 0, ri: 0, keyFocus: null, sub: null, lift: null, confirm: null, dirty: false, typing: null,
    msg: "", geo: { q: "", list: null, busy: false, err: false } };
  var REPO_URL = "github.com/Seagey/couch-tv";
  var WX_DEFAULT = { lat: -33.8688, lon: 151.2093, place: "Sydney", timezone: "Australia/Sydney" };
  var pushTimer = null;

  function loadSettings() {
    var r = kept(ST_KEY);
    return r && r.s && typeof r.s === "object" && !Array.isArray(r.s) ? r.s : {};
  }
  function keepSettings(pending) {
    var rec = { s: ST, updated: Date.now(), pending: pending };
    keep(ST_KEY, rec);
    return rec;
  }
  function pushSettings(rec) {
    return api("POST", "/api/settings", { settings: rec.s })
      .then(function () { rec.pending = false; keep(ST_KEY, rec); }, function () {});
  }
  // Kept on this TV straight away; handed to the bridge a moment later (pending until it has it).
  function saveSettings() {
    var rec = keepSettings(true);
    SET.dirty = true;
    if (OFFLINE || data.bridge === false) return;
    clearTimeout(pushTimer);
    pushTimer = setTimeout(function () { pushSettings(rec); }, 400);
  }
  // Same content, whatever order the keys came back in.
  function canon(v) {
    if (Array.isArray(v)) return "[" + v.map(canon).join(",") + "]";
    if (v && typeof v === "object") return "{" + Object.keys(v).sort().map(function (k) { return JSON.stringify(k) + ":" + canon(v[k]); }).join(",") + "}";
    return JSON.stringify(v);
  }
  // On every refresh with the bridge: a change made here while the computer was off goes to the
  // bridge; otherwise the bridge's copy comes back (this TV's storage was cleared, say).
  function syncSettings() {
    return api("GET", "/api/settings").then(function (j) {
      var rec = kept(ST_KEY), remote = j.settings && typeof j.settings === "object" && !Array.isArray(j.settings) ? j.settings : null;
      if (rec && rec.pending) return pushSettings(rec);
      if (!remote || (!j.updated && !Object.keys(remote).length)) {   // the bridge has none yet: it gets ours
        if (Object.keys(ST).length) return pushSettings(keepSettings(true));
        return null;
      }
      if (canon(remote) === canon(ST)) return null;
      ST = remote;
      keepSettings(false);
      if (zone !== "settings") restart();
      return null;
    });
  }

  function staticText() {
    $("shelf-title").textContent = t("keep_watching");
    $("people-pop").setAttribute("aria-label", t("who_watching"));
    $("apps-section").setAttribute("aria-label", t("apps"));
    $("settings").setAttribute("aria-label", t("set_title"));
  }

  function openSettings() {
    closeGear();
    zone = "settings";
    SET.col = "secs"; SET.sec = 0; SET.ri = 0; SET.keyFocus = null; SET.sub = null; SET.lift = null; SET.confirm = null; SET.msg = "";
    clearFocus();
    $("settings").classList.add("on");
    document.documentElement.classList.add("setting");
    renderSettings();
  }
  // Closing starts the page again when anything changed, so the look, scenes and words all agree.
  function closeSettings() {
    if (SET.typing) stopTyping();
    if (SET.lift) dropRow();
    $("settings").classList.remove("on");
    document.documentElement.classList.remove("setting");
    zone = "grid";
    if (SET.dirty) { SET.dirty = false; restart(); return; }
    paintFocus();
  }

  // ---- rows ----
  function rowsNow() { return SET.sub ? SET.sub.rows() : sectionRows(SECS[SET.sec][0]); }
  function focusable(r) { return !!r && r.kind !== "head" && r.kind !== "info"; }
  function firstRow(rows) { for (var i = 0; i < rows.length; i++) if (focusable(rows[i])) return i; return -1; }
  function stepRow(rows, i, d) { for (var j = i + d; j >= 0 && j < rows.length; j += d) if (focusable(rows[j])) return j; return i; }
  function indexOfKey(rows, k) { for (var i = 0; i < rows.length; i++) if (rows[i].key === k) return i; return -1; }
  function head(label) { return { kind: "head", key: "h:" + label, label: label }; }
  function info(key, label, value, sub) { return { kind: "info", key: key, label: label, value: value, sub: sub }; }

  function sectionRows(id) {
    if (id === "language") return languageRows();
    if (id === "location") return locationRows();
    if (id === "weather") return weatherRows();
    if (id === "look") return lookRows();
    if (id === "apps") return appsRows();
    if (id === "people") return peopleRows();
    if (id === "quick") return quickRows();
    return aboutRows();
  }

  function languageRows() {
    var L = window.I18N;
    var rows = L ? L.langs.map(function (l) {
      return { kind: "choice", key: "lang:" + l.id, label: l.name, lang: l.locale, checked: L.lang() === l.id, act: function () {
        ST.lang = l.id; L.set(l.id); saveSettings(); staticText();
      } };
    }) : [];
    rows.push(info("lang-note", t("lang_note")));
    return rows;
  }

  // ---- location: Open-Meteo's place search (free, no key) ----
  var geoTimer = null, geoTicket = 0;
  function locationRows() {
    var geo = SET.geo;
    var rows = [info("loc-now", t("loc_now"), WXCFG.place || t("loc_unset"), WXCFG.timezone && WXCFG.timezone !== "auto" ? WXCFG.timezone : "")];
    rows.push({ kind: "input", key: "loc-search", label: t("loc_search"), value: geo.q, act: function () {
      startTyping({ label: t("loc_type"), value: geo.q, search: true,
        live: function (q) { findPlaces(q, false); }, submit: function (q) { findPlaces(q, true); } });
    } });
    if (geo.busy) rows.push(info("loc-busy", t("loc_searching")));
    else if (geo.err) rows.push(info("loc-err", t("loc_error")));
    else if (geo.list && !geo.list.length) rows.push(info("loc-none", t("loc_none", { q: geo.q })));
    if (geo.list && geo.list.length) {
      rows.push(head(t("loc_results")));
      geo.list.forEach(function (p, i) {
        rows.push({ kind: "choice", key: "place:" + i, label: p.name, sub: p.area,
          checked: !!(ST.place && ST.place.lat === p.lat && ST.place.lon === p.lon), act: function () { choosePlace(p); } });
      });
    }
    if (ST.place) {
      rows.push({ kind: "action", key: "loc-reset", label: t("loc_reset"), act: function () {
        delete ST.place;
        WXCFG = CFG.weather || WX_DEFAULT;
        saveSettings();
        SET.msg = t("loc_set", { place: WXCFG.place || "" });
      } });
    }
    rows.push(info("loc-note", t("loc_note")));
    return rows;
  }
  function geocode(q) {
    var lang = (window.I18N ? window.I18N.lang() : "en").slice(0, 2);
    var url = "https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language=" + lang + "&name=" + encodeURIComponent(q);
    return fetch(url).then(function (r) { if (!r.ok) throw new Error("place search " + r.status); return r.json(); }).then(function (j) {
      return (j.results || []).filter(function (r) { return isFinite(r.latitude) && isFinite(r.longitude); }).map(function (r) {
        // two decimals is about a kilometre: the town, never the house
        return { name: r.name, area: [r.admin1, r.country].filter(Boolean).join(", "),
          lat: Math.round(r.latitude * 100) / 100, lon: Math.round(r.longitude * 100) / 100, timezone: r.timezone || "auto" };
      });
    });
  }
  // While typing: a search half a second after the last key. OK: straight away, then onto the first place.
  function findPlaces(q, go) {
    clearTimeout(geoTimer);
    q = (q || "").trim();
    SET.geo.q = q;
    if (q.length < 2) {
      SET.geo = { q: q, list: null, busy: false, err: false };
      if (zone === "settings") renderSettings();
      return;
    }
    var run = function () {
      var ticket = ++geoTicket;
      SET.geo.busy = true; SET.geo.err = false;
      if (zone === "settings") renderSettings();
      geocode(q).then(function (list) {
        if (ticket !== geoTicket) return;
        SET.geo = { q: q, list: list, busy: false, err: false };
        if (go && list.length && !SET.typing) { SET.col = "rows"; SET.keyFocus = "place:0"; }
        if (zone === "settings") renderSettings();
      }, function () {
        if (ticket !== geoTicket) return;
        SET.geo = { q: q, list: null, busy: false, err: true };
        if (zone === "settings") renderSettings();
      });
    };
    if (go) run(); else geoTimer = setTimeout(run, 500);
  }
  function choosePlace(p) {
    ST.place = { name: p.name, lat: p.lat, lon: p.lon, timezone: p.timezone };
    WXCFG = { lat: p.lat, lon: p.lon, place: p.name, timezone: p.timezone };
    saveSettings();
    SET.msg = t("loc_set", { place: p.name });
    data.weather = null;
    refreshWeather();
  }

  // ---- weather ----
  function weatherRows() {
    return [
      { kind: "toggle", key: "wx-show", label: t("wx_show"), value: WX_SHOW ? t("on") : t("off"), act: function () {
        WX_SHOW = !WX_SHOW; ST.wx_show = WX_SHOW; saveSettings();
        if (WX_SHOW) refreshWeather();
        clock();
      } },
      { kind: "cycle", key: "wx-unit", label: t("wx_units"), value: WX_F ? "°F (Fahrenheit)" : "°C (Celsius)", act: function () {
        WX_F = !WX_F; ST.wx_unit = WX_F ? "f" : "c"; saveSettings(); clock();
      } },
      { kind: "cycle", key: "wx-every", label: t("wx_every"), value: t("every_min", { n: WX_EVERY }), act: function () {
        WX_EVERY = WX_EVERY === 15 ? 30 : WX_EVERY === 30 ? 60 : 15; ST.wx_every = WX_EVERY; saveSettings();
      } },
      info("wx-provider", t("wx_provider"), "Open-Meteo", t("wx_provider_note")),
      info("wx-night", t("wx_night_only"))
    ];
  }

  // ---- look, scenes and the household's own photos ----
  var SECS_STEPS = [10, 15, 20, 30, 45, 60, 120];
  function lookRows() {
    var st = sceneState(), rows = [head(t("scenes_head"))];
    st.order.forEach(function (id) {
      var sc = sceneById(id);
      if (!sc) return;
      rows.push({ kind: "check", key: "scene:" + id, label: sc.own ? t("scene_photo", { n: sc.n }) : sc.name || t("scene_" + id),
        thumb: sc.own ? sc.src : "backgrounds/" + sc.photo + ".jpg", checked: st.off.indexOf(id) < 0,
        act: function () { toggleScene(id); }, move: function (d) { moveScene(id, d); } });
    });
    var secs = Math.round(SCENE_MS / 1000);
    rows.push({ kind: "cycle", key: "scene-secs", label: t("scene_secs"), value: t("n_secs", { n: secs }), act: function () {
      var i = SECS_STEPS.indexOf(secs), n = i < 0 ? 20 : SECS_STEPS[(i + 1) % SECS_STEPS.length];
      ST.scene_seconds = n; SCENE_MS = n * 1000; saveSettings(); applyScenes();
    } });
    rows.push({ kind: "cycle", key: "clock", label: t("clock"), value: CLOCK24 ? t("clock_24") : t("clock_12"), act: function () {
      CLOCK24 = !CLOCK24; ST.clock = CLOCK24 ? "24h" : "12h"; saveSettings(); glassShown = ""; clock();
    } });
    rows.push(head(t("photos_head")));
    if (canPick()) rows.push({ kind: "action", key: "photo-pick", label: t("photos_pick"), sub: t("photos_pick_sub"), act: pickPhotos });
    else rows.push(info("photo-nopick", t("photos_no_pick"), "", t("photos_no_pick_sub")));
    rows.push({ kind: "input", key: "photo-url", label: t("photos_url"), act: function () {
      startTyping({ label: t("photos_url_type"), value: "", url: true, submit: addPhotoUrl });
    } });
    (Array.isArray(ST.photos) ? ST.photos : []).forEach(function (p, i) {
      rows.push({ kind: "action", key: "photo-rm:" + p.id, label: t("photo_remove", { n: i + 1 }), thumb: photoSrc(p), danger: true,
        act: function () { removePhoto(p.id); } });
    });
    rows.push(info("photos-note", t("photos_note")));
    return rows;
  }
  function setScenes(st) { ST.scene_order = st.order; ST.scenes_off = st.off; saveSettings(); applyScenes(); }
  // fork: a change to the scenes or their timing shows at once, not only after the page starts
  // again (that restart, on leaving Home settings, was sometimes missed and the old set stayed)
  function applyScenes() {
    var st = sceneState();
    SCENES = st.order.filter(function (id) { return st.off.indexOf(id) < 0; }).map(sceneById).filter(Boolean);
    if (!SCENES.length) SCENES = [SCENE_LIB[0]];
    $("scenes").innerHTML = "";
    buildScenes();
    scene = 0; sceneBag = [];
    showScene(Math.floor(Math.random() * SCENES.length));
    clearInterval(sceneTimer);
    if (SCENES.length > 1) sceneTimer = setInterval(nextScene, SCENE_MS);
  }
  function toggleScene(id) {
    var st = sceneState(), i = st.off.indexOf(id);
    if (i >= 0) st.off.splice(i, 1);
    else if (st.order.length - st.off.length <= 1) { SET.msg = t("one_scene"); return; }
    else st.off.push(id);
    setScenes(st);
  }
  function moveScene(id, d) {
    var st = sceneState(), i = st.order.indexOf(id), j = i + d;
    if (i < 0 || j < 0 || j >= st.order.length) return;
    st.order[i] = st.order[j]; st.order[j] = id;
    setScenes(st);
  }
  // The launcher's file picker (Android's "open a document" screen), where the TV has one.
  function canPick() { try { return !!(TV && TV.canPickPhotos && TV.canPickPhotos()); } catch (e) { return false; } }
  function pickPhotos() {
    var ok = false;
    try { ok = !!TV.pickPhotos(); } catch (e) {}
    if (!ok) SET.msg = t("photos_no_pick");
  }
  function uid() { return Date.now().toString(36) + Math.floor(Math.random() * 1296).toString(36); }
  function photosAdded(names) {
    var list = [];
    try { list = typeof names === "string" ? JSON.parse(names) : names || []; } catch (e) { list = []; }
    var photos = Array.isArray(ST.photos) ? ST.photos.slice() : [], n = 0;
    list.forEach(function (f) { if (typeof f === "string" && photos.length < 20) { photos.push({ id: uid(), file: f }); n++; } });
    if (!n) return;
    ST.photos = photos;
    saveSettings();
    SET.msg = n === 1 ? t("photos_added") : t("photos_added_n", { n: n });
    if (zone === "settings") renderSettings();
  }
  function addPhotoUrl(u) {
    u = (u || "").trim();
    if (!u) return;
    if (!/^https?:\/\//i.test(u)) u = "https://" + u;
    if ((ST.photos || []).length >= 20) { SET.msg = t("photos_full"); return; }
    SET.msg = t("photos_checking");
    var im = new Image(), done = false;
    var finish = function (good) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (good) {
        ST.photos = (Array.isArray(ST.photos) ? ST.photos : []).concat([{ id: uid(), url: u }]);
        saveSettings();
        SET.msg = t("photos_added");
      } else SET.msg = t("photos_bad");
      if (zone === "settings") renderSettings();
    };
    var timer = setTimeout(function () { finish(false); }, 20000);
    im.onload = function () { finish(im.naturalWidth > 0); };
    im.onerror = function () { finish(false); };
    im.src = u;
  }
  function removePhoto(id) {
    var p = (ST.photos || []).filter(function (x) { return x.id === id; })[0];
    if (!p) return;
    if (p.file) try { if (TV && TV.removePhoto) TV.removePhoto(p.file); } catch (e) {}
    ST.photos = ST.photos.filter(function (x) { return x.id !== id; });
    saveSettings();
  }

  // ---- apps: the same order as holding OK on the dock, plus hiding ----
  function appsRows() {
    var arr = arrangeItems(), rows = [];
    var hidden = data.apps.filter(function (a) { return HIDDEN_TV.indexOf(a.package) >= 0; });
    if (!arr.items.length && !hidden.length) return [info("apps-none", t("apps_none"))];
    var row = function (it, where) {
      return { kind: "item", key: "app:" + it.package, label: it.name, value: t(where), icon: it.package,
        act: function () { cycleApp(it.package); },
        move: where === "app_hidden" ? null : function (d) { moveAppRow(it.package, d); } };
    };
    rows.push(head(t("apps_home", { n: arr.line, max: DOCK_APPS })));
    arr.items.slice(0, arr.line).forEach(function (it) { rows.push(row(it, "app_on_home")); });
    rows.push(head(t("apps_more")));
    arr.items.slice(arr.line).forEach(function (it) { rows.push(row(it, "app_in_more")); });
    if (hidden.length) {
      rows.push(head(t("apps_hidden")));
      hidden.forEach(function (a) { rows.push(row(a, "app_hidden")); });
    }
    rows.push(info("apps-note", t("apps_note")));
    return rows;
  }
  function setHidden(list) { HIDDEN_TV = list; ST.hidden_apps = list.slice(); saveSettings(); }
  // OK on an app: home, then More apps, then hidden, then back on home (a screen skips hidden).
  function cycleApp(pkg) {
    var h = HIDDEN_TV.indexOf(pkg);
    if (h >= 0) {
      setHidden(HIDDEN_TV.filter(function (p) { return p !== pkg; }));
      materialise();
      appToHome(pkg);
    } else {
      var o = materialise() && data.order, i = o.packages.indexOf(pkg);
      if (i < 0) return;
      if (i < o.line) { o.packages.splice(i, 1); o.line -= 1; o.packages.splice(o.line, 0, pkg); persistOrder(); }
      else if (pkg.indexOf("screen:") === 0) appToHome(pkg);
      else { o.packages.splice(i, 1); setHidden(HIDDEN_TV.concat([pkg])); persistOrder(); }
    }
    SET.dirty = true;
    renderGrid();
  }
  // To the end of the dock; a full dock sends its last app behind More apps (as holding OK does).
  function appToHome(pkg) {
    var o = data.order, i = o.packages.indexOf(pkg);
    if (i >= 0) o.packages.splice(i, 1);
    if (o.line < DOCK_APPS) { o.packages.splice(o.line, 0, pkg); o.line += 1; }
    else {
      o.packages.splice(o.line - 1, 0, pkg);
      SET.msg = t("moved_more", { name: nameOf(o.packages[o.line]) });
    }
    persistOrder();
  }
  // Up and down while an app is picked up; across the line moves it between home and More apps.
  function moveAppRow(pkg, d) {
    var o = data.order, i = o.packages.indexOf(pkg), j = i + d;
    if (i < 0) return;
    var swap = function (a, b) { var x = o.packages[a]; o.packages[a] = o.packages[b]; o.packages[b] = x; };
    if (d > 0 && i === o.line - 1) o.line -= 1;
    else if (d < 0 && i === o.line) { if (o.line < DOCK_APPS) o.line += 1; else swap(i, i - 1); }
    else if (j >= 0 && j < o.packages.length) swap(i, j);
    renderGrid();
  }

  // ---- people: the same list the phone's People sheet edits ----
  function peopleRows() {
    var list = NOBODY ? [] : PEOPLE, rows = [];
    if (!list.length) rows.push(info("people-none", t("people_none")));
    list.forEach(function (p) {
      rows.push({ kind: "nav", key: "person:" + p.id, label: p.name, avatar: p, sub: p.id === who && list.length > 1 ? t("person_on") : "",
        act: function () { openPerson(p.id); }, move: function (d) { movePerson(p.id, d); } });
    });
    if (list.length < 12) {
      rows.push({ kind: "input", key: "people-add", label: t("people_add"), act: function () {
        startTyping({ label: t("people_type"), value: "", submit: addPerson });
      } });
    } else rows.push(info("people-full", t("people_full")));
    rows.push(info("people-note", t("people_note")));
    return rows;
  }
  function personById(id) { return NOBODY ? null : PEOPLE.filter(function (p) { return p.id === id; })[0] || null; }
  function openPerson(id) {
    SET.sub = { back: "person:" + id, crumb: t("sec_people"),
      title: function () { var p = personById(id); return p ? p.name : ""; },
      rows: function () {
        var p = personById(id);
        if (!p) return [];
        return [
          { kind: "input", key: "p-name", label: t("person_name"), value: p.name, act: function () {
            startTyping({ label: t("person_rename_type"), value: p.name, submit: function (v) { renamePerson(id, v); } });
          } }
        ].concat(traktRows(id), [
          { kind: "action", key: "p-remove", label: t("person_remove", { name: p.name }), danger: true, act: function () { removePerson(id); } }
        ]);
      } };
    SET.keyFocus = "p-name";
  }
  function cleanName(v) { return String(v || "").replace(/\s+/g, " ").trim().slice(0, 40); }
  function newPersonId(name, list) {
    var base = name.toLowerCase();
    try { base = base.normalize("NFD").replace(/[̀-ͯ]/g, ""); } catch (e) {}
    base = base.replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 24) || "person";
    if (["both", "all", "everyone", "shared", "together"].indexOf(base) >= 0) base += "-1";
    var id = base, n = 2;
    while (list.some(function (p) { return p.id.toLowerCase() === id; })) id = base + "-" + n++;
    return id;
  }
  // Kept on this TV (pending) and handed to the bridge, which keeps it in bridge/data/people.json.
  function savePeopleList() {
    var list = NOBODY ? [] : PEOPLE.map(function (p) {
      var o = { id: p.id, name: p.name };
      ["colour", "kid", "guest"].forEach(function (k) { if (p[k] != null) o[k] = p[k]; });
      return o;
    });
    keep("tvhome.people.list", { people: list, pending: true });
    keep("tvhome.people.order", { ids: list.map(function (p) { return p.id; }), pending: false });
    SET.dirty = true;
    renderShelf();
    if (OFFLINE || data.bridge === false) return;
    api("POST", "/api/people", { people: list })
      .then(function (r) { keep("tvhome.people.list", { people: r.people || list, pending: false }); }, function () {});
  }
  function addPerson(v) {
    var name = cleanName(v), list = NOBODY ? [] : PEOPLE.slice();
    if (!name) return;
    if (list.length >= 12) { SET.msg = t("people_full"); return; }
    var id = newPersonId(name, list);
    list.push({ id: id, name: name });
    setPeople(list);
    savePeopleList();
    SET.keyFocus = "person:" + id;
  }
  function renamePerson(id, v) {
    var name = cleanName(v), p = personById(id);
    if (!name || !p) return;
    p.name = name;
    savePeopleList();
  }
  function removePerson(id) {
    if (NOBODY) return;
    setPeople(PEOPLE.filter(function (p) { return p.id !== id; }));
    savePeopleList();
    SET.sub = null;
    SET.keyFocus = "people-add";
  }
  function movePerson(id, d) {
    if (NOBODY) return;
    var i = PEOPLE.map(function (p) { return p.id; }).indexOf(id), j = i + d;
    if (i < 0 || j < 0 || j >= PEOPLE.length) return;
    var x = PEOPLE[i]; PEOPLE[i] = PEOPLE[j]; PEOPLE[j] = x;
    renderPeople();
  }

  // ---- quick buttons ----
  function quickRows() {
    var on = QUICK.slice(), order = on.concat(QUICK_ALL.filter(function (q) { return on.indexOf(q) < 0; }));
    var rows = order.map(function (q) {
      var enabled = on.indexOf(q) >= 0;
      return { kind: "check", key: "quick:" + q, label: t("quick_" + q), checked: enabled, sub: quickNeeds(q),
        act: function () { toggleQuick(q); }, move: enabled ? function (d) { moveQuick(q, d); } : null };
    });
    rows.push(info("quick-note", t("quick_note")));
    return rows;
  }
  function quickNeeds(q) {
    if (q === "sleep" && OFFLINE) return t("quick_needs_bridge");
    if (q === "picture" && (OFFLINE || !picShown)) return t("quick_needs_picture");
    if (q === "input" && OFFLINE && !nativeInputs()) return t("quick_needs_inputs");
    return "";
  }
  function setQuick(list) { QUICK = list; ST.quick = list.slice(); saveSettings(); renderQuick(); }
  function toggleQuick(q) {
    var had = QUICK.indexOf(q) >= 0;
    setQuick(had ? QUICK.filter(function (x) { return x !== q; }) : QUICK.concat([q]));
    if (!had && !OFFLINE) refreshQuick().then(function () { if (zone === "settings") renderSettings(); });
  }
  function moveQuick(q, d) {
    var l = QUICK.slice(), i = l.indexOf(q), j = i + d;
    if (i < 0 || j < 0 || j >= l.length) return;
    l[i] = l[j]; l[j] = q;
    setQuick(l);
  }

  // ---- about ----
  function aboutRows() {
    var ver = "";
    try { ver = TV && TV.version ? TV.version() : ""; } catch (e) {}
    var run = ALONE ? "run_alone" : OFFLINE ? "run_off" : data.bridge === false ? "run_quiet" : "run_bridge";
    return [
      info("ver", t("about_version"), ver || t("about_web")),
      info("code", t("about_code"), REPO_URL),
      info("run", t("about_running"), t(run)),
      info("kept", t("about_kept"), t(OFFLINE || data.bridge === false ? "kept_tv" : "kept_both")),
      { kind: "action", key: "reset", label: t("about_reset"), sub: t("about_reset_sub"), danger: true, confirmText: t("confirm_reset"),
        act: resetSettings }
    ];
  }
  // Back to config.json for everything but the people and the apps (which have their own lists).
  function resetSettings() {
    (Array.isArray(ST.photos) ? ST.photos : []).forEach(function (p) {
      if (p.file) try { if (TV && TV.removePhoto) TV.removePhoto(p.file); } catch (e) {}
    });
    ST = Array.isArray(ST.hidden_apps) ? { hidden_apps: ST.hidden_apps } : {};
    keep("tvhome.look", null);
    if (window.I18N) window.I18N.set(HOME.language || "en");
    saveSettings();
    staticText();
    SET.msg = t("reset_done");
  }

  // ---- typing a place, a name or a photo address ----
  // One text box above the rows. OK on a typing row focuses it, so the TV's keyboard comes up; OK
  // (Enter) is done, Back cancels, up and down leave it. The phone remote's Type also lands here.
  function isTyping() { return !!SET.typing; }
  function startTyping(spec) {
    SET.typing = spec;
    var inp = $("set-input");
    $("set-type-label").textContent = spec.label;
    inp.value = spec.value || "";
    inp.type = spec.url ? "url" : "text";
    inp.setAttribute("enterkeyhint", spec.search ? "search" : "done");
    inp.setAttribute("autocapitalize", spec.url ? "off" : "words");
    $("set-type").classList.add("on");
    renderSettings();
    try { inp.focus(); inp.setSelectionRange(inp.value.length, inp.value.length); } catch (e) {}
  }
  function stopTyping() {
    SET.typing = null;
    $("set-type").classList.remove("on");
    try { $("set-input").blur(); } catch (e) {}
    renderSettings();
  }
  function typingKey(e) {
    if (!SET.typing || zone !== "settings") return false;
    var inp = $("set-input");
    if (document.activeElement !== inp) { stopTyping(); return false; }   // the box lost the keys
    if (e.key === "Enter" || e.keyCode === 13) {
      // Nothing typed yet: this OK is the remote asking for the TV's keyboard (a WebView raises it
      // on OK in a text box), so it is left to do that rather than closing the box.
      if (inp.value.trim() === String(SET.typing.value || "").trim()) return true;
      e.preventDefault();
      var spec = SET.typing, v = inp.value;
      stopTyping();
      spec.submit(v);
      if (zone === "settings") renderSettings();
      return true;
    }
    if (e.key === "Escape" || e.key === "Esc") { e.preventDefault(); stopTyping(); return true; }
    var up = e.key === "ArrowUp" || e.keyCode === 38, down = e.key === "ArrowDown" || e.keyCode === 40;
    if (up || down) { e.preventDefault(); stopTyping(); setMove(0, up ? -1 : 1); return true; }
    return true;   // everything else is typing (Backspace deletes here, it does not go back)
  }

  // ---- keys on the settings screen ----
  function enterRows() {
    var rows = rowsNow(), i = firstRow(rows);
    if (i < 0) return;
    SET.col = "rows"; SET.ri = i; SET.keyFocus = rows[i].key;
  }
  function leaveRows() {
    if (SET.sub) { SET.keyFocus = SET.sub.back; SET.sub = null; return; }
    SET.col = "secs"; SET.keyFocus = null;
  }
  function setMove(dx, dy) {
    var rows = rowsNow();
    if (SET.lift) {
      if (dy) {
        var r = rows[indexOfKey(rows, SET.lift)];
        if (r && r.move) r.move(dy);
        SET.keyFocus = SET.lift;
      }
      renderSettings();
      return;
    }
    SET.confirm = null;
    if (SET.col === "secs") {
      if (dy) {
        var n = Math.max(0, Math.min(SECS.length - 1, SET.sec + dy));
        if (n !== SET.sec) { SET.sec = n; SET.sub = null; SET.msg = ""; SET.keyFocus = null; SET.ri = 0; }
      } else if (dx > 0) enterRows();
    } else if (dy) {
      SET.ri = stepRow(rows, SET.ri, dy);
      SET.keyFocus = rows[SET.ri] ? rows[SET.ri].key : null;
    } else if (dx < 0) leaveRows();
    renderSettings();
  }
  function setActivate() {
    if (SET.lift) { dropRow(); return; }
    if (SET.col === "secs") { enterRows(); renderSettings(); return; }
    var rows = rowsNow(), r = rows[SET.ri];
    if (!focusable(r) || !r.act) return;
    if (r.danger && SET.confirm !== r.key) { SET.confirm = r.key; renderSettings(); return; }
    SET.confirm = null;
    SET.keyFocus = r.key;
    r.act();
    if (zone === "settings" && !SET.typing) renderSettings();
  }
  function setHold() {
    if (SET.lift) { dropRow(); return true; }
    if (SET.col !== "rows") return false;
    var r = rowsNow()[SET.ri];
    if (!r || !r.move) return false;
    SET.confirm = null;
    if (r.key.indexOf("app:") === 0) materialise();
    SET.lift = r.key;
    renderSettings();
    return true;
  }
  function dropRow() {
    var k = SET.lift || "";
    SET.lift = null;
    if (k.indexOf("app:") === 0) { persistOrder(); SET.dirty = true; }
    if (k.indexOf("person:") === 0) savePeopleList();
    if (zone === "settings") renderSettings();
  }
  function setBack() {
    if (SET.typing) { stopTyping(); return; }
    if (SET.lift) { dropRow(); return; }
    SET.confirm = null;
    if (SET.col === "rows") { leaveRows(); renderSettings(); return; }
    closeSettings();
  }

  // ---- drawing it ----
  function rowHTML(r, i) {
    if (r.kind === "head") return '<div class="shead">' + esc(r.label) + "</div>";
    var f = SET.col === "rows" && i === SET.ri, lifted = SET.lift === r.key;
    var cls = "srow k-" + r.kind + (f ? " focus" : "") + (lifted ? " lifted" : "") + (r.danger ? " danger" : "");
    var lead = "";
    if (r.avatar) lead = avatarHTML(r.avatar);
    else if (r.icon && iconUrl(r.icon)) lead = '<img class="sicon" src="' + esc(iconUrl(r.icon)) + '" alt="" onerror="this.remove()">';
    else if (r.kind === "item") lead = '<span class="sicon none">' + (r.icon.indexOf("screen:") === 0 ? ICONS.screen : esc((r.label || "?").charAt(0).toUpperCase())) + "</span>";
    else if (r.thumb) lead = '<img class="sthumb" src="' + esc(r.thumb) + '" alt="" decoding="async" onerror="this.remove()">';
    var label = SET.confirm === r.key ? r.confirmText || t("confirm_remove") : r.label;
    var tail = "";
    if (lifted) tail = '<i class="smark">' + ICONS.updown + "</i>";
    else if (r.kind === "choice") tail = r.checked ? '<i class="smark">' + ICONS.check + "</i>" : "";
    else if (r.kind === "check") tail = '<i class="sbox' + (r.checked ? " on" : "") + '">' + (r.checked ? ICONS.check : "") + "</i>";
    else if (r.kind === "nav" || r.kind === "input") tail = '<i class="smark dim">' + ICONS.chevron + "</i>";
    var value = r.value != null && r.value !== "" ? '<span class="sv">' + esc(r.value) + "</span>" : "";
    var sub = r.sub ? "<small>" + esc(r.sub) + "</small>" : "";
    return '<div class="' + cls + '"' + (r.lang ? ' lang="' + esc(r.lang) + '"' : "") + ">" + lead +
      '<span class="sl"><b>' + esc(label) + "</b>" + sub + "</span>" + value + tail + "</div>";
  }
  function renderSettings() {
    var rows = rowsNow();
    if (SET.sub && !rows.length) { SET.sub = null; rows = rowsNow(); }
    if (SET.col === "rows") {
      var k = SET.keyFocus ? indexOfKey(rows, SET.keyFocus) : -1;
      if (k >= 0 && focusable(rows[k])) SET.ri = k;
      else {
        SET.ri = Math.max(0, Math.min(SET.ri, rows.length - 1));
        if (!focusable(rows[SET.ri])) { var back = stepRow(rows, SET.ri, -1); SET.ri = back !== SET.ri ? back : stepRow(rows, SET.ri, 1); }
      }
      if (focusable(rows[SET.ri])) SET.keyFocus = rows[SET.ri].key;
      else SET.col = "secs";
    }
    $("set-title").textContent = t("set_title");
    $("set-secs").innerHTML = SECS.map(function (sc, i) {
      var cls = "ssec" + (i === SET.sec ? (SET.col === "secs" ? " focus" : " on") : "");
      return '<div class="' + cls + '"><i>' + ICONS[sc[1]] + "</i><span>" + esc(t("sec_" + sc[0])) + "</span></div>";
    }).join("");
    $("set-crumb").textContent = SET.sub ? SET.sub.crumb : "";
    $("set-head").textContent = SET.sub ? SET.sub.title() : t("sec_" + SECS[SET.sec][0]);
    var box = $("set-rows");
    box.innerHTML = rows.map(rowHTML).join("");
    $("set-msg").textContent = SET.msg || "";
    var cur = rows[SET.ri];
    $("set-hint").textContent = SET.typing ? t("hint_typing") : SET.lift ? t("hint_lift") : SET.col === "secs" ? t("hint_secs") :
      cur && cur.move ? t("hint_rows_move") : t("hint_rows");
    // keep the focused row well inside the list (a quarter of it from either edge), scrolling only
    // when it gets closer, so short lists and their headings never move
    var f = box.querySelector(".srow.focus");
    if (!f) { box.scrollTop = 0; return; }
    var top = f.offsetTop, bottom = top + f.offsetHeight, view = box.clientHeight, edge = view / 4;
    if (top - edge < box.scrollTop) box.scrollTop = Math.max(0, top - edge);
    else if (bottom + edge > box.scrollTop + view) box.scrollTop = bottom + edge - view;
  }
  function initSettings() {
    $("set-input").addEventListener("input", function () { if (SET.typing && SET.typing.live) SET.typing.live(this.value); });
  }

  // ---------------- keys: any normal TV remote, keyboard or game controller ----------------
  // The full table is in docs/REMOTES.md. The launcher also turns BUTTON_A, BUTTON_SELECT and
  // NUMPAD_ENTER into OK and BUTTON_B and ESCAPE into Back before the page sees them. Any other key
  // (media, numbers, colour buttons, voice, app buttons) is left alone so Android still gets it.
  var OK_KEYS = ["Enter", " ", "Select", "Accept", "NumpadEnter"];
  var BACK_KEYS = ["Escape", "Esc", "Backspace", "GoBack", "BrowserBack"];
  var ARROWS = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0],
    Up: [0, -1], Down: [0, 1], Left: [-1, 0], Right: [1, 0] };
  var ARROW_CODES = { 38: [0, -1], 40: [0, 1], 37: [-1, 0], 39: [1, 0], 19: [0, -1], 20: [0, 1], 21: [-1, 0], 22: [1, 0] };
  function isOk(e) { return OK_KEYS.indexOf(e.key) >= 0 || e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 32; }
  function isBack(e) { return BACK_KEYS.indexOf(e.key) >= 0 || e.keyCode === 27 || e.keyCode === 8; }
  function isMenu(e) { return e.key === "ContextMenu" || e.key === "Menu" || e.keyCode === 93 || e.keyCode === 82; }
  function arrowOf(e) { return ARROWS[e.key] || (e.key === "Unidentified" || !e.key ? ARROW_CODES[e.keyCode] : null) || null; }

  // Focus is never lost: after any key, if nothing shows focus, the first dock app gets it.
  function ensureFocus() {
    if (document.querySelector(".focus")) return;
    zone = "grid"; g = 0;
    paintFocus();
  }

  // OK acts when it is let go, so holding it can pick things up instead (tvOS does the same).
  // Works for remotes that repeat the key while it is held and for ones that only send the press
  // and, later, the release. One press is one action: a pick-up never also opens the app.
  var okDown = false, okHeld = false, okTimer = null;
  function okReset() { okDown = false; okHeld = false; clearTimeout(okTimer); }
  // ---- fork: resting. The panel is OLED, and the home's dock, cards and glass would otherwise sit
  // in one place for hours. After a few minutes with no key (home.idle_minutes, 3 by default) they
  // slide off the screen and the scene dims a little and drifts a few pixels, slowly, while the
  // scenes keep taking turns (idle.css). The first key only brings the home back.
  var idleTimer = null, idleUp = false;
  function isIdle() { return document.documentElement.classList.contains("idle"); }
  function goIdle() {
    if (!/^(grid|shelf|tab|np)$/.test(zone) || expanded || lift) { idleSoon(); return; }
    topShelfOff();
    var root = document.documentElement;
    root.classList.add("idle");
    // the music card, once it has slid off the top, comes back along the bottom, the dock's
    // width, in one row (idle.css): it rises into the place the dock and Keep watching left
    setTimeout(function () {
      if (!isIdle()) return;
      root.classList.add("np-wide", "np-below");
      requestAnimationFrame(function () { requestAnimationFrame(function () { root.classList.remove("np-below"); }); });
    }, 950);
  }
  function idleSoon() { clearTimeout(idleTimer); idleTimer = setTimeout(goIdle, Math.max(1, parseFloat(HOME.idle_minutes) || 3) * 60000); }
  function wake() {
    if (!isIdle()) { idleSoon(); return false; }
    var root = document.documentElement;
    if (root.classList.contains("np-wide")) {   // back to its corner, sliding in from the top
      root.classList.remove("np-wide", "np-below");
      root.classList.add("np-up");
      requestAnimationFrame(function () { requestAnimationFrame(function () { root.classList.remove("np-up"); }); });
    }
    root.classList.remove("idle");
    idleSoon();
  try { if (TV && TV.vpn) document.documentElement.classList.toggle("vpn-on", !!TV.vpn()); } catch (e) {}
    paintFocus();
    return true;
  }
  window.addEventListener("keydown", function (e) {
    if (!isIdle()) { idleSoon(); return; }
    e.preventDefault(); e.stopImmediatePropagation();
    idleUp = true;
    wake();
  }, true);
  window.addEventListener("keyup", function (e) {
    if (!idleUp) return;
    e.preventDefault(); e.stopImmediatePropagation();
    idleUp = false;
  }, true);
  idleSoon();

  document.addEventListener("keydown", function (e) {
    if (typingKey(e)) return;   // typing a place or a name: the keys belong to the text box
    if (isOk(e)) {
      e.preventDefault();
      if (e.repeat || okDown) return;   // a held key repeating: the timer is already running
      okDown = true; okHeld = false;
      okTimer = setTimeout(function () { okHeld = true; if (!hold()) activate(); ensureFocus(); }, HOLD_MS);
      return;
    }
    if ((e.key === "MediaPlayPause" || e.keyCode === 179 || e.keyCode === 85) && npOn()) { e.preventDefault(); npAct("play"); return; }
    var arrow = arrowOf(e);
    if (arrow) move(arrow[0], arrow[1]);
    else if (isBack(e)) goHome(true);
    else if (isMenu(e)) { if (!e.repeat) hold(); }   // optional: many remotes have no Menu key
    else { ensureFocus(); return; }                   // anything else is not ours: Android gets it
    e.preventDefault();
    ensureFocus();
  });
  document.addEventListener("keyup", function (e) {
    if (!isOk(e) || isTyping()) return;
    e.preventDefault();
    if (!okDown) return;
    var held = okHeld;
    okReset();
    if (!held) activate();
    ensureFocus();
  });
  window.addEventListener("blur", okReset);

  // ---------------- data ----------------
  function nativeApps() {
    var list;
    try { list = JSON.parse(TV.apps()); } catch (e) { return null; }
    return list.map(function (a) {
      return { kind: "app", name: RENAME[a.package] ? t(RENAME[a.package]) : a.label, package: a.package,
        banner: "http://tvos.invalid/banner/" + a.package };   // the launcher makes one when the app has none
    });
  }
  function sortApps(list) {
    var rank = function (p) {
      var i = APP_ORDER.indexOf(p);
      if (i >= 0) return i;
      i = APP_LAST.indexOf(p);
      return i >= 0 ? 2000 + i : 1000;
    };
    return list.filter(function (a) { return HIDDEN.indexOf(a.package) < 0; })
      .sort(function (a, b) { return rank(a.package) - rank(b.package) || a.name.localeCompare(b.name); });
  }

  function render() {
    renderShelf();
    renderGrid();
    if (!touched) startFocus();
    paintFocus();
  }

  // The bridge's people list (the phone edits it). An order made on this TV while the bridge was
  // away is handed over first.
  function bridgePeople(j) {
    var list = j.people || [];
    var lo = kept("tvhome.people.order"), lst = kept("tvhome.people.list");
    if (lst && lst.pending && Array.isArray(lst.people)) {
      // people added, renamed or removed in Home settings while the computer was off
      list = lst.people;
      api("POST", "/api/people", { people: lst.people })
        .then(function (r) { keep("tvhome.people.list", { people: r.people || lst.people, pending: false }); }, function () {});
    } else if (lo && lo.pending && lo.ids) {
      list = localPeopleOrder(list, true);
      api("POST", "/api/people", { order: lo.ids })
        .then(function () { keep("tvhome.people.order", { ids: lo.ids, pending: false }); }, function () {});
    }
    if (zone !== "pmenu") setPeople(list);
  }
  function bridgeOrder(j) {
    data.order = { saved: !!j.saved, line: j.line, packages: (j.apps || []).map(function (a) { return a.package; }) };
    var lo = kept("tvhome.order.tv");
    if (lo && lo.pending && lo.packages) {
      data.order = { saved: true, line: lo.line, packages: lo.packages };
      api("POST", "/api/app-order", { order: lo.packages, line: lo.line })
        .then(function () { lo.pending = false; keep("tvhome.order.tv", lo); }, function () {});
    }
  }

  // ---- fork: Keep watching without a bridge. The launcher records what apps say is playing
  // (WatchLog.kt) and hands the list over; each person sees what they watched, plus anything
  // recorded before anyone was picked.
  function tellWho() { try { if (TV && TV.setWho && !NOBODY) TV.setWho(who); } catch (e) {} }
  function watchedShows() {
    if (!TV || !TV.watched) return;
    var list;
    try { list = JSON.parse(TV.watched()); } catch (e) { return; }
    var nameOf = function (pkg) {
      var a = (data.apps || []).filter(function (x) { return x.package === pkg; })[0];
      return a ? a.name : pkg;
    };
    // live TV and YouTube are not shows to come back to: left out
    var NOT_SHOWS = /^(com\.google\.android\.youtube\.tv|com\.sony\.dtv\.tv.*|com\.google\.android\.tv)$/;
    // and only the last two weeks: older is not "keep watching"
    var since = Date.now() - 14 * 86400000;
    var shows = list.filter(function (e) { return !NOT_SHOWS.test(e.package || "") && (e.last_played || 0) >= since; }).map(function (e) {
      return { id: e.id, title: e.title, episode_title: e.episode_title || null, season: e.season || null, episode: e.episode || null,
        app: { package: e.package, name: nameOf(e.package) }, backdrop: e.art || null, progress: e.progress,
        remaining_min: e.remaining_min || 0, who: e.who || null, source: "tv", last_played: e.last_played || 0 };
    });
    PEOPLE.forEach(function (p) {
      var mine = shows.filter(function (x) { return !x.who || x.who === p.id; });
      data.showsBy[p.id] = mergeTrakt(mine, traktNext(p.id)).slice(0, 12);
    });
  }
  // ---- fork: Trakt's next episode. For each show Trakt says comes next, the card reads "Next ·
  // S2, E4"; a show the TV recorded more recently than Trakt last heard of it keeps the TV's card
  // (it may still be mid-episode). The app comes from what the TV saw, else where TMDB says the
  // show streams in this country (Trakt.kt; Netflix for The Office in NL, not NBC), else the
  // show's network, else Trakt's own "Watch now".
  var NETWORK_APPS = [[/netflix/i, ["com.netflix.ninja"]], [/hbo|\bmax\b/i, ["com.wbd.stream"]],
    [/apple/i, ["com.apple.atve.sony.appletv", "com.apple.atve.androidtv.appletv"]], [/disney/i, ["com.disney.disneyplus"]],
    [/prime|amazon/i, ["com.amazon.amazonvideo.livingroom"]], [/youtube/i, ["com.google.android.youtube.tv"]],
    [/paramount/i, ["com.cbs.ca", "com.cbs.ott"]], [/hulu/i, ["com.hulu.livingroomplus"]], [/peacock/i, ["com.peacocktv.peacockandroid"]]];
  function appForNetwork(n) {
    for (var i = 0; i < NETWORK_APPS.length; i++) {
      if (!NETWORK_APPS[i][0].test(n || "")) continue;
      var pk = NETWORK_APPS[i][1].filter(function (p) { return (data.apps || []).some(function (a) { return a.package === p; }); })[0];
      if (pk) return { package: pk, name: (data.apps.filter(function (a) { return a.package === pk; })[0] || {}).name || n };
    }
    return null;
  }
  var MOCK_TRAKT = [{ id: "t1", title: "Slow Horses", season: 4, episode: 4, episode_title: "Hello Goodbye", network: "Apple TV+",
    art: null, last_played: "2026-10-27T21:10:00.000Z" }];
  function traktNext(pid) {
    var list = [];
    if (TV && TV.traktUpNext) { try { list = JSON.parse(TV.traktUpNext(pid)) || []; } catch (e) { list = []; } }
    else if (MOCK && traktMock.linked[pid]) list = MOCK_TRAKT;
    return list.map(function (e) {
      return { id: e.id, title: e.title, season: e.season, episode: e.episode, episode_title: e.episode_title || null,
        app: (e.streams || []).map(appForNetwork).filter(Boolean)[0] || appForNetwork(e.network) ||
          (e.watch || []).map(appForNetwork).filter(Boolean)[0] || null, backdrop: e.art || null,
        // an episode stopped partway (Trakt's playback) carries on with its bar: no "Next"
        progress: e.progress != null ? e.progress : null, remaining_min: e.remaining_min || 0, next: e.progress == null, trakt: true, source: "tv",
        last_played: Date.parse(e.last_played) || 0 };
    });
  }
  function mergeTrakt(mine, next) {
    var key = function (x) { return String(x.title || "").toLowerCase().replace(/[^a-z0-9]+/g, ""); };
    var when = function (x) { return typeof x.last_played === "string" ? (Date.parse(x.last_played) || 0) : (x.last_played || 0); };
    var out = mine.slice();
    next.forEach(function (n) {
      var i = -1;
      out.forEach(function (x, j) { if (i < 0 && key(x) === key(n)) i = j; });
      if (i < 0) { out.push(n); return; }
      var tv = out[i];
      if (when(tv) > n.last_played) return;   // watched here since Trakt last heard
      n.app = tv.app || n.app; n.backdrop = n.backdrop || tv.backdrop;
      out[i] = n;
    });
    // Trakt's shows first, then what the TV recorded itself (YouTube TV); newest first in each
    return out.sort(function (a, b) { return (b.trakt ? 1 : 0) - (a.trakt ? 1 : 0) || when(b) - when(a); });
  }

  function refresh() {
    // never reshuffle under something being moved, or under the settings screen
    if (lift || zone === "pmenu" || zone === "settings" || zone === "gear") return Promise.resolve();
    if (OFFLINE) {
      // only the TV's own apps, in the order last saved; no shows, no Screens
      if (TV) data.apps = sortApps(nativeApps() || []);
      else if (window.MOCK_APPS) data.apps = sortApps(window.MOCK_APPS.slice());
      data.bridge = false;
      watchedShows();
      detectNew();
      status(ALONE ? "" : t("computer_off"), true);
      render();
      return Promise.resolve();
    }
    var people = api("GET", "/api/people").then(bridgePeople, function () {});
    var jobs = [
      people.then(function () {
        return Promise.all(PEOPLE.map(function (p) {
          return api("GET", "/api/continue?who=" + p.id, null, 9000).then(function (j) { data.showsBy[p.id] = mergeTrakt((j.items || []).slice(0, 12), traktNext(p.id)).slice(0, 12); });
        }));
      }),
      api("GET", "/api/screens").then(function (j) { data.screens = j.screens || []; }),
      // whoever the phone remote or this TV last set as watching
      api("GET", "/api/who").then(function (j) {
        return people.then(function () { if (PEOPLE.some(function (p) { return p.id === j.who; }) && !touched) { who = j.who; } });
      }),
      (TV || PREVIEW || MOCK) ? Promise.resolve() : api("GET", "/api/apps").then(function (j) { data.bridgeApps = j.apps || []; }),
      // the order arranged on the phone (or with this TV's remote)
      api("GET", "/api/app-order").then(bridgeOrder),
      // Home settings: the bridge keeps a copy, so the phone and this TV agree
      syncSettings()
    ];
    return Promise.all(jobs.map(function (p) { return p.then(function () { return true; }, function () { return false; }); }))
      .then(function (ok) {
        if (lift || zone === "pmenu" || zone === "settings" || zone === "gear") return;
        data.bridge = ok[0] || ok[2];
        var apps = TV ? nativeApps() : (window.MOCK_APPS ? window.MOCK_APPS.slice() : null);
        if (!apps) apps = (data.bridgeApps || []).filter(function (a) { return a.installed !== false && a.id !== "home"; })
          .map(function (a) { return { kind: "app", id: a.id, name: a.name, package: a.package, colour: a.colour }; });
        data.apps = sortApps(apps);
        if (!data.bridge) {   // no bridge: the order this TV keeps itself
          var lo = kept("tvhome.order.tv");
          if (lo && lo.packages) data.order = { saved: true, line: lo.line, packages: lo.packages };
        }
        reportApps();
        detectNew();
        status(data.bridge ? "" : t("computer_quiet"), true);
        if (data.bridge) save();
        render();
        clearTimeout(retryTimer);
        if (!ok[0] && !ok[1]) { retryMs = Math.min(retryMs * 2, 60000); retryTimer = setTimeout(refresh, retryMs); }
        else retryMs = 2500;
      });
  }

  // the native side calls these
  // Home on the remote also re-reads the lists, so an order arranged on the phone shows straight away
  window.tv = { back: function () { goHome(true); },
    vpn: function (on) { document.documentElement.classList.toggle("vpn-on", !!on); if (window.tvAdapt) tvAdapt(); },
    home: function () { wake(); if (zone === "side") closeSide(); if (zone === "settings") closeSettings(); else goHome(); refresh(); },
    resume: function () { wake(); okReset(); refresh(); if (!OFFLINE) refreshQuick(); refreshWeather(); },
    watched: function () { if (OFFLINE) refresh(); },
    haDone: function (id, ok) { haDone(id, ok); },
    trakt: function (w) { if (zone === "settings") renderSettings(); if (OFFLINE || MOCK) refresh(); },
    // the launcher's file picker has copied the chosen photos into its own storage (Home settings)
    photosAdded: function (names) { photosAdded(names); } };

  function start() {
    readConfig();
    staticText();
    initSettings();
    mark("app-start");
    clock();
    setInterval(clock, 15000);
    {
      buildScenes();
      // ?scene=fog starts on that scene, for previews (?scene=own: the first of the household's photos)
      var first = (Q.match(/[?&]scene=([a-z]+)/) || [])[1];
      var at = SCENES.map(function (s) { return s.own ? "own" : s.id; }).indexOf(first);
      showScene(at >= 0 ? at : Math.floor(Math.random() * SCENES.length));
      if (SCENES.length > 1) sceneTimer = setInterval(nextScene, SCENE_MS);
      // the clock font must be in before the glass is measured
      if (document.fonts) document.fonts.load("300 10px Antonio").then(function () { glassShown = ""; clock(); });
      refreshWeather();
      setInterval(refreshWeather, WX_EVERY * 60000);
    }
    $("place").textContent = "";
    var cached = load();
    if (cached) { data.showsBy = cached.showsBy || data.showsBy; data.screens = cached.screens || []; data.order = cached.order || null; }
    setPeople(PEOPLE);   // the cache may predate someone new
    tellWho();   // fork: the launcher tags what it records with whoever is watching
    if (OFFLINE) {   // no bridge: an order made with this TV's remote wins
      var lo = kept("tvhome.order.tv");
      if (lo && lo.packages) data.order = { saved: true, line: lo.line, packages: lo.packages };
    }
    if (TV) data.apps = sortApps(nativeApps() || []);
    if (cached || TV) { render(); mark("home-painted-from-cache"); }
    renderQuick();
    startMockNp();   // fork: sample music in the mock (Now Playing)
    if (!OFFLINE) refreshQuick();
    refresh().then(function () { mark("home-painted-live"); });
  }

  if (MOCK || PREVIEW) {
    var sc = document.createElement("script");
    sc.src = "mock.js";
    sc.onload = start;
    document.head.appendChild(sc);
  } else {
    start();
  }
})();
