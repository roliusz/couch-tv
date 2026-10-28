// Edge refraction for the Liquid Glass (fork addition), with glass.css. Apple's glass is clear in
// the middle and bends the light only at its rounded rim, like the edge of a thick lens. For each
// glass piece this draws a displacement map the size of the piece: neutral in the middle, pulling
// the picture behind inwards in a band along the edge, strongest at the very rim and following the
// rounded corners. An SVG filter per piece uses it, and the piece's ::before reads it through the
// --lens property. The map is drawn again whenever the piece changes size (More apps, a new song).
(function () {
  var NS = "http://www.w3.org/2000/svg";
  var PIECES = [
    { sel: ".theme-night .grid", id: "lens-dock" },
    { sel: "#np", id: "lens-np" },
    { sel: "#side-tab", id: "lens-tab" }
  ];
  var BAND = 26;     // px: how far in from the rim the bending reaches
  var STRENGTH = 28; // px: how far the picture is pulled at the rim

  var host = document.createElementNS(NS, "svg");
  host.setAttribute("width", "0"); host.setAttribute("height", "0");
  host.setAttribute("aria-hidden", "true");
  host.style.position = "absolute";
  document.body.appendChild(host);

  // distance from a point inside a rounded rectangle to its edge, and the outward direction there
  function edge(x, y, w, h, r) {
    var cx = Math.min(Math.max(x, r), w - r), cy = Math.min(Math.max(y, r), h - r);
    var dx = x - cx, dy = y - cy, len = Math.sqrt(dx * dx + dy * dy);
    if (len > 0) return { d: r - len, nx: dx / len, ny: dy / len };   // in a corner
    var l = x, ri = w - x, t = y, b = h - y, m = Math.min(l, ri, t, b);
    if (m === l) return { d: l, nx: -1, ny: 0 };
    if (m === ri) return { d: ri, nx: 1, ny: 0 };
    if (m === t) return { d: t, nx: 0, ny: -1 };
    return { d: b, nx: 0, ny: 1 };
  }

  function mapFor(w, h, r) {
    var c = document.createElement("canvas");
    c.width = w; c.height = h;
    var g = c.getContext("2d"), img = g.createImageData(w, h), px = img.data;
    var band = Math.min(BAND, h / 2.5);
    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        var e = edge(x + 0.5, y + 0.5, w, h, r), i = (y * w + x) * 4, k = 0;
        if (e.d < band && e.d > -1) { var t = 1 - Math.max(0, e.d) / band; k = t * t; }
        // sample from further in: the rim shows the picture pulled towards the middle
        px[i] = 128 - e.nx * k * 127; px[i + 1] = 128 - e.ny * k * 127; px[i + 2] = 128; px[i + 3] = 255;
      }
    }
    g.putImageData(img, 0, 0);
    return c.toDataURL();
  }

  function build(p, el) {
    var w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
    if (!w || !h) return;
    var key = w + "x" + h;
    if (p.key === key) return;
    p.key = key;
    var r = Math.min(parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0, w / 2, h / 2);
    var f = document.getElementById(p.id);
    if (!f) {
      f = document.createElementNS(NS, "filter");
      f.id = p.id;
      f.setAttribute("filterUnits", "userSpaceOnUse");
      f.setAttribute("primitiveUnits", "userSpaceOnUse");
      f.setAttribute("color-interpolation-filters", "sRGB");
      // a neutral grey (no bend) under the map, so wherever the map does not reach the glass
      // still shows, unbent
      f.innerHTML = '<feFlood flood-color="#808000" result="still"/>' +
        '<feImage result="rim" preserveAspectRatio="none"/>' +
        '<feMerge result="map"><feMergeNode in="still"/><feMergeNode in="rim"/></feMerge>' +
        '<feDisplacementMap in="SourceGraphic" in2="map" xChannelSelector="R" yChannelSelector="G"/>';
      host.appendChild(f);
    }
    // The filter's area reaches far past the piece: while the dock grows (More apps) the map is
    // the old size until the change settles, and an area that tight cut the new part of the glass
    // away (it went clear, then came back).
    f.setAttribute("x", "-2000"); f.setAttribute("y", "-2000");
    f.setAttribute("width", w + 4000); f.setAttribute("height", h + 4000);
    var im = f.querySelector("feImage");
    im.setAttribute("x", "0"); im.setAttribute("y", "0");
    im.setAttribute("width", w); im.setAttribute("height", h);
    im.setAttribute("href", mapFor(w, h, r));
    f.querySelector("feDisplacementMap").setAttribute("scale", STRENGTH * 2);
    el.style.setProperty("--lens", "url(#" + p.id + ")");
  }

  var timer = null;
  function all() {
    PIECES.forEach(function (p) {
      var el = document.querySelector(p.sel);
      if (el) build(p, el);
    });
  }
  // after a size change has settled (More apps animates its height)
  function soon() { clearTimeout(timer); timer = setTimeout(all, 120); }
  if (window.ResizeObserver) {
    var ro = new ResizeObserver(soon);
    var watch = function () { PIECES.forEach(function (p) { var el = document.querySelector(p.sel); if (el && !el._lensWatched) { el._lensWatched = true; ro.observe(el); } }); };
    watch();
    new MutationObserver(watch).observe(document.body, { childList: true, subtree: true });
  }
  addEventListener("resize", soon);
  // motion.js: while the dock grows (More apps) its rim is left unbent, then drawn again for the
  // new size once it settles: the old size's rim showed as an outline of the small dock inside
  // the growing one
  window.tvLens = {
    pause: function (el) { el.style.setProperty("--lens", "none"); },
    again: function () { PIECES.forEach(function (p) { p.key = null; }); soon(); }
  };
  setTimeout(all, 300);
})();
