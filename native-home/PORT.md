# Native port of the TV home (tv-home/web → Kotlin)

The user chose (2026-11-09): **everything native before switching over**: main screen, sidebar,
Home settings, people, Trakt linking, app rearranging. Same app icons and functionality. Frosted
glass everywhere = the dock's glass (made on the TV from each photo: downscale + BitmapShader,
white wash 0x42FFFFFF, rim 0x47FFFFFF; see the native-home prototype `GlassFace`).

Where it lives: inside the launcher app (`tv-home/android`, package `app.couchtv.launcher`) as a
second activity, so it shares prefs ("page", "home"), Trakt.kt, WatchLog.kt, Doorbell, NowOverlay and
the HA helpers. MainActivity (WebView) stays the Home until the native one has everything here;
then the HOME intent filter moves. `native-home/` is the throwaway prototype (springs, glass, fonts).

## Data sources (option A on the A90J: OFFLINE + ALONE, no bridge)
- Config: `assets/www/tv/config.js` = `window.COUCH_CONFIG = {...}` (home.app_order, dock_size,
  scenes, scene_seconds, clock, hide_packages, language; people; weather{lat,lon,place,timezone};
  tvs[].quick, picture_modes; default_tv). Built by BundleTvWeb from config.json.
- Kept values: prefs "page" (TVOS.keep/kept): `tvhome.settings` (ST), `tvhome.people.list`,
  `tvhome.people.order`, `tvhome.order.tv` {line, packages}, `tvhome.apps.new`, `tvhome.cache.v2`.
  `tvhome.who` lives in WebView localStorage only, so native keeps its own and calls setWho.
- ST keys: lang, place{lat,lon,name,timezone}, wx_show, wx_unit("f"), wx_every(15/30/60), quick[],
  clock("24h"), hidden_apps[], scene_order[], scenes_off[], scene_seconds, photos[{id,file|url}].
- Apps: Native.apps() (package, label, hasBanner); icons = bannerBytes() (640x360, banner or iconTile).
- Keep watching: WatchLog.load (last 14 days, NOT_SHOWS regex) + Trakt.upNext(who) merged
  (mergeTrakt: finished drops, a newer TV record wins episode/progress, Trakt first then newest),
  max 12 per person; NETWORK_APPS map for the Trakt app; user picks (hold OK) override the app.
- Upcoming: Trakt.premieres(who), first card, turns every 30 s.
- Home cards: haRoom JSON (lights, scenes, climate) + weather days; panels (room/climate/weather).
- Weather: Open-Meteo direct, every WX_EVERY min, current + 7 days; WMO words in i18n.
- Now Playing: HA players (MainActivity haPlayersNow every 5 s) → page tvNowPlaying(state).
- Strings: i18n.js (en, es, es-MX) → convert to a JSON asset.

## Main screen features (checklist)
- [x] Scenes: built-in (night, golden, water, fog, dawn) + MY_SCENES (backgrounds/mine/scenes.js)
      + own photos; per-scene clock top/size/align and date top (app.css .s-*, mine/scenes.css);
      fg cut-out over the clock; glass fill; water reflection; shuffle bag; SCENE_MS; crossfade;
      night-time dim (21 to 6).
- [x] Date line + weather glyph + temp + words; fits between people and quick buttons.
- [x] People: 1 none, 2 pill switch, 3+ avatar head + popup list (hold OK to reorder); avatars
      web/avatars/<id>.png; colour palette; who remembered; setWho to WatchLog.
- [x] Quick buttons (sleep/picture/input; offline only input via openInputs).
- [x] Shelf: home cards + Upcoming + Keep watching, labels moving with the row, 5 in view,
      baked band blur, app icon top right, progress bar, "Next ·", premiere meta; top shelf
      (art fades in behind after 600 ms on focus); empty states.
- [x] Home card panels (lights + scenes, climate, weekly weather).
- [x] Dock: sorted apps (app_order, APP_LAST, TUCKED, HIDDEN, hide_packages, hidden_apps,
      RENAME store), DOCK_APPS 11 + More; More apps grid expands (GRID_COLS 12); new-app dots;
      hold OK lifts an app to move (saves tvhome.order.tv); gear tile → Home settings / TV settings.
- [x] Focus/motion: springs 0.12/0.39, icons 1.16 + lift, cards 1.1, bump at row end, press-in on
      OK, launch zoom/fade, coming back fade-up, Reduce Motion.
- [x] Now Playing card (prev/play/next), person tab, VPN chip, toast, OLED idle after 3 min,
      doorbell (native already), Back → sidebar from the start.
- [x] Sidebar: profile, Store, quick buttons, HA scenes, settings; people level; app-pick level
      for a show (hold OK on a card), mark watched.
- [x] Home settings (all sections) + Trakt linking + photos picker.

## Status (2026-11-09)
Written in tv-home/android/app/src/main/java/app/couchtv/launcher/home/ (Ui, Store, Svg, Scenes,
Apps, Shows, Ha, Tiles, Row, Panels, Sidebar, Settings, HomeActivity). Opens as "Home (native)"
(LEANBACK), not as Home yet. Runs on the emulator: scenes + glass clock + cut-out, date/weather,
person tab, home cards, dock with real icons, focus springs. Not yet checked on the emulator:
sidebar, Home settings, panels, Now Playing, hold-to-move, More apps, idle. Not on the TV yet.
Not ported (bridge only, not used on the A90J): sleep timer, picture switch, Screens.
