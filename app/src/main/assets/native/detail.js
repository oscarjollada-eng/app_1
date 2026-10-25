(function () {
  function t(e) { return e ? (e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  var html = document.documentElement.innerHTML;
  var lat = null, lng = null, m;
  var res = [
    /"lat(?:itude)?"\s*:\s*"?(-34\.\d{3,})"?\s*,\s*"(?:lng|lon|long|longitude)"\s*:\s*"?(-58\.\d{3,})"?/,
    /"(?:lng|lon|long|longitude)"\s*:\s*"?(-58\.\d{3,})"?\s*,\s*"lat(?:itude)?"\s*:\s*"?(-34\.\d{3,})"?/,
    /(-34\.\d{4,})\s*,\s*(-58\.\d{4,})/
  ];
  for (var i = 0; i < res.length && lat === null; i++) {
    m = html.match(res[i]);
    if (m) { if (i === 1) { lng = parseFloat(m[1]); lat = parseFloat(m[2]); } else { lat = parseFloat(m[1]); lng = parseFloat(m[2]); } }
  }
  var photos = [], seen = {};
  function add(s) {
    if (!s || s.indexOf('http') !== 0 || seen[s] || /\.svg|logo|avatar|sprite|placeholder|pixel|icon/i.test(s)) return;
    if (!/zonapropcdn|zonaprop|argenprop|mlstatic|cloudfront|imgar|static/i.test(s)) return;
    seen[s] = 1; photos.push(s);
  }
  var og = document.querySelector('meta[property="og:image"]');
  if (og) add(og.getAttribute('content'));
  [].slice.call(document.querySelectorAll('img')).forEach(function (im) {
    var w = im.naturalWidth || parseInt(im.getAttribute('width') || '0', 10);
    var s = im.getAttribute('data-src') || im.getAttribute('data-lazy') || im.getAttribute('src');
    var cls = (im.className || '') + ' ' + ((im.parentNode && im.parentNode.className) || '');
    if (/gallery|carousel|slider|slide|swiper|photo|picture|image/i.test(cls) || w > 300) add(s);
  });
  var text = t(document.body);
  return JSON.stringify({ title: document.title, text: text.slice(0, 9000), lat: lat, lng: lng, photos: photos.slice(0, 8) });
})();
