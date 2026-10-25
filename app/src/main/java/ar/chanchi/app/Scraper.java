package ar.chanchi.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Busca avisos en el propio celular: carga cada portal en un navegador interno (con la IP y las cookies del
 * usuario), lee las tarjetas, abre la ficha de los avisos nuevos y los guarda ya deduplicables.
 * Todo corre en el hilo principal; la geolocalización va en un hilo aparte.
 */
final class Scraper {
    interface Done { void done(List<JSONObject> fresh); }

    private static final int MAX_DETAILS = 24;
    private static final long DETAIL_BUDGET_MS = 110_000L;
    private final Context app;
    private final Store store;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView wv;
    private int token;
    private final String listJs, detailJs;

    Scraper(Context c) {
        app = c.getApplicationContext();
        store = Store.get(app);
        listJs = asset("native/list.js");
        detailJs = asset("native/detail.js");
    }

    private String asset(String n) {
        try (InputStream in = app.getAssets().open(n); BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) { return "''"; }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView view() {
        if (wv == null) {
            wv = new WebView(app);
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setLoadsImagesAutomatically(false);
            s.setBlockNetworkImage(true);
            String ua = s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", "");
            s.setUserAgentString(ua);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }
        return wv;
    }

    /** Carga una página, espera a que se asiente y corre el script. cb recibe el JSON (o null si falló). */
    private void load(String url, long settleMs, String js, boolean retryEmpty, java.util.function.Consumer<JSONObject> cb) {
        final int my = ++token;
        retries = 0;
        final boolean[] finished = {false};
        WebView w = view();
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                if (my != token || finished[0]) return;
                finished[0] = true;
                h.postDelayed(() -> run(my, js, retryEmpty, cb), settleMs);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest rq) {
                String sc = rq.getUrl().getScheme();
                return sc == null || !(sc.equals("http") || sc.equals("https")); // ignora deep links tipo meli://
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest rq, WebResourceError e) {
                if (my != token || finished[0] || !rq.isForMainFrame()) return;
                finished[0] = true;
                android.util.Log.i("ChanchiScan", "error de carga " + e.getErrorCode() + " " + e.getDescription() + " en " + rq.getUrl());
                cb.accept(null);
            }
        });
        h.postDelayed(() -> { if (my == token && !finished[0]) { finished[0] = true; w.stopLoading(); run(my, js, false, cb); } }, 35000);
        w.loadUrl(url);
    }

    private int retries;

    private void run(int my, String js, boolean retryEmpty, java.util.function.Consumer<JSONObject> cb) {
        if (my != token) return;
        wv.evaluateJavascript(js, raw -> {
            if (my != token) return;
            JSONObject o = null;
            if (raw == null || raw.length() < 60) android.util.Log.i("ChanchiScan", "resultado vacío del script: " + raw);
            try {
                Object v = new JSONTokener(raw).nextValue();
                if (v instanceof String) o = new JSONObject((String) v);
            } catch (Exception ignored) {}
            if (retryEmpty && o != null && !o.optBoolean("blocked") && retries < 4) {
                JSONArray its = o.optJSONArray("items");
                boolean emptyList = its != null && its.length() == 0 && !o.has("err");
                boolean thinPage = o.has("text") && o.optString("text").length() < 800;
                if (emptyList || thinPage) {
                    retries++;
                    h.postDelayed(() -> run(my, js, true, cb), 3000);
                    return;
                }
            }
            cb.accept(o);
        });
    }

    // ---------- ciclo completo ----------
    Runnable midway;

    void cycle(Done done) {
        store.busy = true;
        final List<JSONObject> fresh = new ArrayList<>();
        final ArrayDeque<String[]> srcs = new ArrayDeque<>();
        for (String[] p : Store.PORTALS) if (store.sourceOn(p[0])) srcs.add(p);
        final List<JSONObject> pending = new ArrayList<>();
        Runnable[] nextSrc = new Runnable[1];
        nextSrc[0] = () -> {
            String[] p = srcs.poll();
            if (p == null) { details(pending, fresh, done); return; }
            listSource(p, pending, nextSrc[0]);
        };
        nextSrc[0].run();
    }

    private void listSource(String[] p, List<JSONObject> pending, Runnable next) {
        final String id = p[0];
        final boolean base0 = !store.hasBaseline(id);
        List<String[]> tasks = new ArrayList<>();
        tasks.add(new String[]{store.sourceUrl(id), "3"});
        for (String z : store.zones()) { String u = zoneUrl(id, z); if (u != null) tasks.add(new String[]{u, "2"}); }
        pages(id, tasks, base0, next);
    }

    static String zoneUrl(String id, String zone) {
        String first = Parse.norm(zone.split("[,/]")[0]).replaceAll("\\s+(caba|capital federal|buenos aires)$", "");
        if (first.isEmpty() || first.equals("capital federal") || first.equals("caba") || first.equals("buenos aires")) return null;
        String slug = first.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        switch (id) {
            case "zonaprop": return "https://www.zonaprop.com.ar/departamentos-alquiler-" + slug + "-orden-publicado-descendente.html";
            case "argenprop": return "https://www.argenprop.com/departamentos/alquiler/" + slug + "?orden-masnuevos";
            default: return "https://inmuebles.mercadolibre.com.ar/departamentos/alquiler/capital-federal/" + slug + "/";
        }
    }

    static String pageUrl(String id, String url, int n) {
        if (n <= 1) return url;
        if (id.equals("zonaprop")) return url.replaceFirst("\\.html.*$", "-pagina-" + n + ".html");
        if (id.equals("argenprop")) return url + (url.contains("?") ? "&" : "?") + "pagina-" + n;
        return url.replaceAll("/+$", "") + "/_Desde_" + ((n - 1) * 48 + 1) + "_NoIndex_True";
    }

    private void pages(String id, List<String[]> tasks, boolean base0, Runnable next) {
        final int[] ti = {0}, page = {1}, tot = {0}, added = {0}, skipped = {0};
        final boolean[] okAny = {false};
        final String[] fail = {null};
        Runnable[] go = new Runnable[1];
        go[0] = () -> {
            if (ti[0] >= tasks.size()) {
                if (okAny[0]) store.setStatus(id, true, "Funciona: " + tot[0] + " leídos, " + added[0] + " nuevos" + (skipped[0] > 0 ? ", " + skipped[0] + " incompletos" : ""), tot[0]);
                else store.setStatus(id, false, fail[0] == null ? "Sin resultados" : fail[0], 0);
                next.run(); return;
            }
            String[] t = tasks.get(ti[0]);
            int total = Integer.parseInt(t[1]);
            if (page[0] > total) { ti[0]++; page[0] = 1; go[0].run(); return; }
            listPage(id, pageUrl(id, t[0], page[0]), base0, r -> {
                if (r.err != null) { if (fail[0] == null) fail[0] = r.err; page[0] = total + 1; }
                else { okAny[0] = true; tot[0] += r.read; added[0] += r.added; skipped[0] += r.skipped; page[0]++; if (r.read == 0) page[0] = total + 1; }
                h.postDelayed(go[0], 2500);
            });
        };
        go[0].run();
    }

    private static final class PageRes { String err; int read, added, skipped; }

    private void listPage(String id, String url, boolean base0, java.util.function.Consumer<PageRes> out) {
        final PageRes pr = new PageRes();
        load(url, 2500, listJs, true, res -> {
            try {
                if (res == null) { pr.err = "No cargó la página (¿sin conexión?)"; out.accept(pr); return; }
                android.util.Log.i("ChanchiScan", id + " title=" + res.optString("title") + " n=" + res.optInt("n") + " err=" + res.optString("err") + " diag=" + res.optString("diag"));
                if (res.optBoolean("blocked")) { pr.err = "Pide verificación: tocá Abrir y resolvela"; out.accept(pr); return; }
                JSONArray items = res.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    pr.err = "No encontré avisos en la página (¿cambió el diseño o la búsqueda?)";
                    out.accept(pr); return;
                }
                int added = 0, skipped = 0;
                long now = System.currentTimeMillis();
                synchronized (store) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        String iu = it.getString("u");
                        String num = lastNumber(iu);
                        if (num == null) { skipped++; continue; }
                        String rid = id + "-" + num;
                        JSONObject ad = store.ads().get(rid);
                        if (ad != null) { ad.put("lastSeen", now); continue; }
                        ad = new JSONObject();
                        ad.put("rid", rid); ad.put("portal", id); ad.put("url", iu);
                        String text = it.optString("t");
                        Parse.fill(ad, text);
                        if (!ad.has("rent")) { skipped++; continue; }
                        String loc = it.optString("l"), addr = it.optString("a");
                        String b = Parse.barrio(loc, text);
                        ad.put("barrio", b != null ? b : "Capital Federal");
                        if (addr.isEmpty() && !loc.isEmpty()) addr = loc.split(",")[0].trim();
                        ad.put("addr", addr.isEmpty() ? ad.optString("barrio") : addr);
                        String photo = it.optString("p");
                        JSONArray ph = new JSONArray(); if (!photo.isEmpty()) ph.put(photo);
                        ad.put("photos", ph);
                        ad.put("owner", false); ad.put("guar", new JSONArray());
                        ad.put("firstSeen", now); ad.put("lastSeen", now); ad.put("tries", 0); ad.put("detailed", false);
                        if (ad.has("m2") && ad.has("amb")) { if (!ad.has("dorm")) ad.put("dorm", Math.max(0, ad.optInt("amb") - 1)); ad.put("ready", true); } else ad.put("ready", false);
                        ad.put("base", base0);
                        store.ads().put(rid, ad);
                        added++;
                    }
                }
                pr.read = items.length(); pr.added = added; pr.skipped = skipped;
            } catch (Exception e) {
                pr.err = "Error leyendo la página: " + e.getMessage();
            }
            out.accept(pr);
        });
    }

    static String lastNumber(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{5,})").matcher(url);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private void details(List<JSONObject> unused, List<JSONObject> fresh, Done done) {
        synchronized (store) {
            for (JSONObject o : store.ads().values()) {
                if (o.optBoolean("ready") && !o.optBoolean("bad") && !o.has("lat")) {
                    double[] c = Parse.centroid(o.optString("barrio"));
                    try { o.put("lat", c[0]); o.put("lng", c[1]); o.put("geo", 0); } catch (Exception ignored) {}
                }
            }
            store.saveAds();
        }
        Runnable m = midway; if (m != null) m.run();
        final ArrayDeque<JSONObject> q = new ArrayDeque<>();
        final long t0 = System.currentTimeMillis();
        synchronized (store) {
            Map<String, ArrayDeque<JSONObject>> by = new LinkedHashMap<>();
            List<JSONObject> todo = new ArrayList<>();
            for (JSONObject o : store.ads().values()) if (!o.optBoolean("detailed")) todo.add(o);
            Collections.sort(todo, (a, b) -> Long.compare(b.optLong("firstSeen"), a.optLong("firstSeen")));
            for (JSONObject o : todo) { by.computeIfAbsent(o.optString("portal"), k -> new ArrayDeque<>()).add(o); }
            boolean more = true;
            while (more && q.size() < MAX_DETAILS) {
                more = false;
                for (ArrayDeque<JSONObject> d : by.values()) { JSONObject o = d.poll(); if (o != null) { q.add(o); more = true; } }
            }
        }
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            JSONObject ad = System.currentTimeMillis() - t0 > DETAIL_BUDGET_MS ? null : q.poll();
            if (ad == null) { finish(fresh, done); return; }
            load(ad.optString("url"), 2500, detailJs, true, res -> {
                try {
                    synchronized (store) {
                        ad.put("tries", ad.optInt("tries") + 1);
                        android.util.Log.i("ChanchiScan", "detalle " + ad.optString("rid") + " título=" + (res == null ? "-" : res.optString("title")) + " len=" + (res == null ? -1 : res.optString("text").length()) + " lat=" + (res == null ? "-" : res.opt("lat")) + " fotos=" + (res == null || res.optJSONArray("photos") == null ? 0 : res.optJSONArray("photos").length()));
                        if (res != null && !res.optBoolean("blocked") && res.optString("text").length() > 800) {
                            String text = res.optString("text");
                            String tt = Parse.norm(res.optString("title"));
                            if (tt.startsWith("venta") || tt.contains("oficina") || tt.contains("local comercial") || tt.contains("showroom") || tt.contains("galpon") || tt.contains("cochera en alquiler")) ad.put("bad", true);
                            Parse.features(ad, text);
                            Parse.fill(ad, text);
                            if (!res.isNull("lat") && Parse.inCaba(res.optDouble("lat"), res.optDouble("lng"))) {
                                ad.put("lat", res.getDouble("lat")); ad.put("lng", res.getDouble("lng")); ad.put("geo", 1);
                            }
                            JSONArray ph = res.optJSONArray("photos");
                            if (ph != null && ph.length() > 0) ad.put("photos", ph);
                            if (!ad.has("m2") || !ad.has("amb")) ad.put("bad", true);
                            else if (!ad.has("dorm")) ad.put("dorm", Math.max(0, ad.optInt("amb") - 1));
                            ad.put("ready", true); ad.put("detailed", true);
                        } else if (ad.optInt("tries") >= 3) {
                            if (res != null) android.util.Log.i("ChanchiScan", "detalle pobre " + ad.optString("rid") + " título=" + res.optString("title") + " texto=" + res.optString("text").substring(0, Math.min(200, res.optString("text").length())));
                            if (!ad.has("m2") || !ad.has("amb")) ad.put("bad", true);
                            else if (!ad.has("dorm")) ad.put("dorm", Math.max(0, ad.optInt("amb") - 1));
                            ad.put("ready", true); ad.put("detailed", true);
                        }
                    }
                } catch (Exception ignored) {}
                step[0].run();
            });
        };
        step[0].run();
    }

    private void finish(List<JSONObject> fresh, Done done) {
        // geolocalización fuera del hilo principal
        io.execute(() -> {
            List<JSONObject> ready = new ArrayList<>();
            int budget = 10;
            synchronized (store) {
                for (JSONObject o : store.ads().values()) if (o.optBoolean("ready") && !o.optBoolean("bad") && o.optInt("geo", 0) != 1) ready.add(o);
            }
            for (JSONObject o : ready) {
                try {
                    if (budget <= 0 && o.has("lat")) continue;
                    double[] ll = budget > 0 ? geocode(o) : null;
                    synchronized (store) {
                        if (ll == null) { ll = Parse.centroid(o.optString("barrio")); o.put("geo", 0); }
                        else o.put("geo", 1);
                        o.put("lat", ll[0]); o.put("lng", ll[1]);
                    }
                    budget--;
                } catch (Exception ignored) {}
            }
            synchronized (store) {
                for (JSONObject o : store.ads().values()) {
                    if (o.optBoolean("ready") && o.optBoolean("detailed") && !o.optBoolean("bad") && o.has("lat") && !o.optBoolean("notified")) {
                        try { o.put("notified", true); } catch (Exception ignored) {}
                        if (!o.optBoolean("base")) fresh.add(o);
                    }
                }
                store.saveAds();
            }
            android.util.Log.i("ChanchiScan", "ciclo listo: publicados=" + countReady() + " nuevos=" + fresh.size());
            h.post(() -> { store.busy = false; done.done(fresh); });
        });
    }

    private int countReady() { int n = 0; synchronized (store) { for (JSONObject o : store.ads().values()) if (o.optBoolean("ready") && !o.optBoolean("bad") && o.has("lat")) n++; } return n; }

    /** Nominatim (OpenStreetMap). Una consulta por segundo, con caché. */
    private double[] geocode(JSONObject ad) {
        try {
            String q = ad.optString("addr") + ", " + ad.optString("barrio") + ", Ciudad de Buenos Aires, Argentina";
            String cached = store.geoGet(q);
            if (cached != null) {
                if (cached.equals("-")) return null;
                String[] p = cached.split(",");
                return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
            }
            Thread.sleep(1100);
            URL u = new URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=ar&q=" + URLEncoder.encode(q, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Chanchi/1.0 (app personal de alquileres)");
            c.setRequestProperty("Accept-Language", "es");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            JSONArray a = new JSONArray(sb.toString());
            if (a.length() == 0) { store.geoPut(q, "-"); return null; }
            double lat = Double.parseDouble(a.getJSONObject(0).getString("lat"));
            double lng = Double.parseDouble(a.getJSONObject(0).getString("lon"));
            if (!Parse.inCaba(lat, lng)) { store.geoPut(q, "-"); return null; }
            store.geoPut(q, lat + "," + lng);
            return new double[]{lat, lng};
        } catch (Exception e) { return null; }
    }
}
