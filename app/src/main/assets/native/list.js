(function () { try {
  function t(e) { return e ? (e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  var host = location.hostname, cards = [];
  function all(sel) { return [].slice.call(document.querySelectorAll(sel)); }
  if (/zonaprop/.test(host)) cards = all('[data-posting-type], [data-qa="posting PROPERTY"], div[class*="postingCard"]');
  else if (/argenprop/.test(host)) cards = all('.listing__item, [class*="listing__item"]');
  else cards = all('li.ui-search-layout__item, .ui-search-result__wrapper, .poly-card, div.ui-search-result');
  // evitar tarjetas anidadas
  cards = cards.filter(function (c) { return !cards.some(function (o) { return o !== c && o.contains(c); }); });
  var seen = {}, items = [];
  cards.forEach(function (c) {
    var href = c.getAttribute('data-to-posting');
    if (!href) {
      var a = c.matches('a[href]') ? c : (c.querySelector('a.poly-component__title, a.ui-search-link, a[href*="/propiedades/"], a[href*="--"], a[href*="MLA"]') || c.querySelector('a[href]'));
      href = a && a.getAttribute('href');
    }
    if (!href || href.indexOf('javascript') === 0) return;
    var url;
    try { url = new URL(href, location.href); } catch (e) { return; }
    if (/mercadolibre|mercadolivre/.test(url.hostname)) url = url.origin + url.pathname; else url = url.origin + url.pathname + (url.search && /argenprop/.test(host) ? '' : '');
    if (seen[url]) return; seen[url] = 1;
    var imgs = [].slice.call(c.querySelectorAll('img')), photo = '';
    for (var i = 0; i < imgs.length && !photo; i++) {
      var s = imgs[i].getAttribute('data-src') || imgs[i].getAttribute('data-lazy') || imgs[i].getAttribute('data-original') || imgs[i].getAttribute('src') || '';
      if (s.indexOf('http') === 0 && !/\.svg|placeholder|blank|pixel/i.test(s)) photo = s;
    }
    var addr = t(c.querySelector('[data-qa*="ADDRESS"], [class*="ddress"], [class*="card__address"]'));
    var loc = t(c.querySelector('[data-qa*="LOCATION"], [class*="ocation"], [class*="card__title--primary"]'));
    items.push({ u: url, p: photo, a: addr, l: loc, t: t(c).slice(0, 700) });
  });
  var body = t(document.body);
  var blocked = /just a moment|un momento|verificando|attention required|captcha|no soy un robot|are you human|acceso denegado/i.test(document.title + ' ' + body.slice(0, 400));
  var diag = '';
  if (!items.length) {
    var cls = {};
    [].slice.call(document.querySelectorAll('[class]')).slice(0, 1500).forEach(function (e) { String(e.className).split(/\s+/).forEach(function (c) { if (/card|item|result|posting|listing|poly/i.test(c)) cls[c] = (cls[c] || 0) + 1; }); });
    diag = 'links=' + document.querySelectorAll('a[href]').length + ' classes=' + JSON.stringify(cls).slice(0, 500) + ' body=' + body.slice(0, 250);
  }
  return JSON.stringify({ title: document.title, blocked: blocked && items.length === 0, n: cards.length, diag: diag, items: items.slice(0, 60) });
} catch (e) { return JSON.stringify({ err: String(e && e.message || e), items: [] }); }
})();
