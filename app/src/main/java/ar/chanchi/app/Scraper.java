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

    private static final int MAX_DETAILS = 8;
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
        final boolean[] finished = {false};
        WebView w = view();
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                if (my != token || finished[0]) return;
                finished[0] = true;
                h.postDelayed(() -> run(my, js, retryEmpty, cb), settleMs);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest rq, WebResourceError e) {
                if (my != token || finished[0] || !rq.isForMainFrame()) return;
                finished[0] = true;
                cb.accept(null);
            }
        });
        h.postDelayed(() -> { if (my == token && !finished[0]) { finished[0] = true; w.stopLoading(); run(my, js, false, cb); } }, 35000);
        w.loadUrl(url);
    }

    private void run(int my, String js, boolean retryEmpty, java.util.function.Consumer<JSONObject> cb) {
        if (my != token) return;
        wv.evaluateJavascript(js, raw -> {
            if (my != token) return;
            JSONObject o = null;
            try {
                Object v = new JSONTokener(raw).nextValue();
                if (v instanceof String) o = new JSONObject((String) v);
            } catch (Exception ignored) {}
            if (retryEmpty && o != null && !o.optBoolean("blocked") && o.optJSONArray("items") != null && o.optJSONArray("items").length() == 0) {
                h.postDelayed(() -> run(my, js, false, cb), 4000);
                return;
            }
            cb.accept(o);
        });
    }

    // ---------- ciclo completo ----------
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
        load(store.sourceUrl(id), 2500, listJs, true, res -> {
            try {
                if (res == null) { store.setStatus(id, false, "No cargó la página (¿sin conexión?)", -1); next.run(); return; }
                if (res.optBoolean("blocked")) { store.setStatus(id, false, "Pide verificación: tocá Abrir y resolvela", -1); next.run(); return; }
                JSONArray items = res.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    store.setStatus(id, false, "No encontré avisos en la página (¿cambió el diseño o la búsqueda?)", 0);
                    next.run(); return;
                }
                int added = 0, skipped = 0;
                long now = System.currentTimeMillis();
                synchronized (store) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        String url = it.getString("u");
                        String num = lastNumber(url);
                        if (num == null) { skipped++; continue; }
                        String rid = id + "-" + num;
                        JSONObject ad = store.ads().get(rid);
                        if (ad != null) { ad.put("lastSeen", now); continue; }
                        ad = new JSONObject();
                        ad.put("rid", rid); ad.put("portal", id); ad.put("url", url);
                        String text = it.optString("t");
                        Parse.fill(ad, text);
                        if (!ad.has("rent") || !ad.has("m2") || !ad.has("amb")) { skipped++; continue; }
                        if (!ad.has("dorm")) ad.put("dorm", Math.max(0, ad.optInt("amb") - 1));
                        String loc = it.optString("l"), addr = it.optString("a");
                        String b = Parse.barrio(loc, text);
                        ad.put("barrio", b != null ? b : "Capital Federal");
                        if (addr.isEmpty() && !loc.isEmpty()) addr = loc.split(",")[0].trim();
                        ad.put("addr", addr.isEmpty() ? ad.optString("barrio") : addr);
                        String photo = it.optString("p");
                        JSONArray ph = new JSONArray(); if (!photo.isEmpty()) ph.put(photo);
                        ad.put("photos", ph);
                        ad.put("balcon", false); ad.put("cocina", false); ad.put("owner", false); ad.put("guar", new JSONArray());
                        ad.put("firstSeen", now); ad.put("lastSeen", now); ad.put("ready", false); ad.put("tries", 0);
                        ad.put("base", base0);
                        store.ads().put(rid, ad);
                        added++;
                    }
                }
                store.setStatus(id, true, "Funciona: " + items.length() + " en la página, " + added + " nuevos" + (skipped > 0 ? ", " + skipped + " incompletos" : ""), items.length());
            } catch (Exception e) {
                store.setStatus(id, false, "Error leyendo la página: " + e.getMessage(), -1);
            }
            next.run();
        });
    }

    static String lastNumber(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{5,})").matcher(url);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private void details(List<JSONObject> unused, List<JSONObject> fresh, Done done) {
        final ArrayDeque<JSONObject> q = new ArrayDeque<>();
        synchronized (store) {
            List<JSONObject> todo = new ArrayList<>();
            for (JSONObject o : store.ads().values()) if (!o.optBoolean("ready")) todo.add(o);
            // los más nuevos primero (los de la lista de cada portal vienen ordenados por reciente)
            Collections.sort(todo, (a, b) -> Long.compare(b.optLong("firstSeen"), a.optLong("firstSeen")));
            for (JSONObject o : todo) { if (q.size() >= MAX_DETAILS) break; q.add(o); }
        }
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            JSONObject ad = q.poll();
            if (ad == null) { finish(fresh, done); return; }
            load(ad.optString("url"), 2500, detailJs, false, res -> {
                try {
                    synchronized (store) {
                        ad.put("tries", ad.optInt("tries") + 1);
                        if (res != null && !res.optBoolean("blocked") && res.optString("text").length() > 200) {
                            String text = res.optString("text");
                            Parse.features(ad, text);
                            Parse.fill(ad, text);
                            if (!res.isNull("lat") && Parse.inCaba(res.optDouble("lat"), res.optDouble("lng"))) {
                                ad.put("lat", res.getDouble("lat")); ad.put("lng", res.getDouble("lng")); ad.put("geo", 1);
                            }
                            JSONArray ph = res.optJSONArray("photos");
                            if (ph != null && ph.length() > 0) ad.put("photos", ph);
                            ad.put("ready", true);
                        } else if (ad.optInt("tries") >= 3) {
                            ad.put("ready", true); // se publica igual con lo que mostraba la tarjeta
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
                for (JSONObject o : store.ads().values()) if (o.optBoolean("ready") && !o.has("lat")) ready.add(o);
            }
            for (JSONObject o : ready) {
                try {
                    if (budget <= 0) continue;
                    double[] ll = geocode(o);
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
                    if (o.optBoolean("ready") && o.has("lat") && !o.optBoolean("notified")) {
                        o.put("notified", true);
                        if (!o.optBoolean("base")) fresh.add(o);
                    }
                }
                store.saveAds();
            }
            h.post(() -> { store.busy = false; done.done(fresh); });
        });
    }

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
