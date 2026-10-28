// Light or dark glass (fork addition), as Apple's Liquid Glass does: text and symbols on the glass
// turn dark when what is behind it is light, and stay white when it is dark. Applies to the person
// tab and the Now Playing card, the two pieces of glass over the sky. It measures the scene photo
// behind each one (small canvas, a few times a minute and on every scene change) and sets
// .on-light on it; nowplaying.css and sidebar.css hold the dark text. The glass itself never
// changes: only what is written on it does.
(function () {
  var TARGETS = ["side-tab", "np"];
  // Dark text only where white really stops reading: a near-white sky (lightness above 0.86 of 1,
  // as Fog). It goes back to white below 0.82, apart so a sky on the edge does not flicker.
  var LIGHT = 0.86, DARK = 0.82;
  var W = 192, H = 108;
  var canvas = document.createElement("canvas");
  canvas.width = W; canvas.height = H;
  var g = canvas.getContext("2d", { willReadFrequently: true });

  // What is really on screen behind the glass: the scene's photo drawn the way the page shows it
  // (cover, centred, with its dimming after 9 pm), then the scene's own shade and the page's scrim
  // on top (Dawn darkens the top of its sky by up to 42%, for one).
  function drawScene() {
    var img = document.querySelector(".scene.on .sbg");
    if (!img || !img.complete || !img.naturalWidth) return false;
    var s = Math.max(W / img.naturalWidth, H / img.naturalHeight);
    var w = img.naturalWidth * s, h = img.naturalHeight * s;
    try {
      g.clearRect(0, 0, W, H);
      var f = getComputedStyle(img).filter;
      g.filter = f && f !== "none" ? f : "none";
      g.drawImage(img, (W - w) / 2, (H - h) / 2, w, h);
      g.filter = "none";
      g.getImageData(0, 0, 1, 1);   // a photo from elsewhere cannot be read: give up quietly
    } catch (e) { return false; }
    shade(img.parentNode.querySelector(".shade"));
    shade(document.querySelector(".scrim"));
    return true;
  }
  // a top-to-bottom gradient overlay, as the page draws it
  function shade(el) {
    if (!el) return;
    var bg = getComputedStyle(el).backgroundImage;
    if (!bg || bg.indexOf("linear-gradient") < 0) return;
    var re = /rgba?\(([^)]+)\)\s*([\d.]+)%/g, m, grad = g.createLinearGradient(0, 0, 0, H), n = 0;
    while ((m = re.exec(bg))) {
      var c = m[1].split(",").map(parseFloat), a = c.length > 3 ? c[3] : 1;
      grad.addColorStop(Math.min(1, parseFloat(m[2]) / 100), "rgba(" + c[0] + "," + c[1] + "," + c[2] + "," + a + ")");
      n++;
    }
    if (n < 2) return;
    g.fillStyle = grad;
    g.fillRect(0, 0, W, H);
  }

  function lum(r, gr, b) {   // relative luminance, 0 to 1
    var c = [r, gr, b].map(function (v) { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  function behind(el) {
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    var x = Math.max(0, Math.floor(r.left / innerWidth * W)), y = Math.max(0, Math.floor(r.top / innerHeight * H));
    var w = Math.max(1, Math.min(W - x, Math.ceil(r.width / innerWidth * W))), h = Math.max(1, Math.min(H - y, Math.ceil(r.height / innerHeight * H)));
    var d = g.getImageData(x, y, w, h).data, sum = 0, n = 0;
    for (var i = 0; i < d.length; i += 4) { sum += lum(d[i], d[i + 1], d[i + 2]); n++; }
    // perceived lightness (L*, 0 to 1) of the average
    var Y = sum / n;
    return (Y > 0.008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y) / 100;
  }

  function check() {
    // the top shelf (a show's artwork, darkened at the top) is behind the glass: white text
    var ok = !document.documentElement.classList.contains("shelfing") && drawScene();
    TARGETS.forEach(function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      if (!ok) { el.classList.remove("on-light"); return; }
      var L = behind(el);
      if (L === null) return;
      levels[id] = Math.round(L * 100) / 100;
      var light = el.classList.contains("on-light");
      if (!light && L > LIGHT) el.classList.add("on-light");
      else if (light && L < DARK) el.classList.remove("on-light");
    });
  }
  var levels = {};
  window.tvAdapt = check;
  window.tvAdaptLevels = levels;   // the last lightness measured behind each, for checking

  // on every scene change (after its photo has loaded), on resize, and now and then
  var scenes = document.getElementById("scenes");
  if (scenes) new MutationObserver(function () { setTimeout(check, 50); })
    .observe(scenes, { subtree: true, attributes: true, attributeFilter: ["class"] });
  document.addEventListener("load", function (e) { if (e.target && e.target.classList && e.target.classList.contains("sbg")) check(); }, true);
  addEventListener("resize", check);
  setInterval(check, 20000);
  setTimeout(check, 300);
})();
