package ar.chanchi.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Estado persistente: configuración, avisos encontrados y estado de cada fuente. */
final class Store {
    static final String[][] PORTALS = {
        {"zonaprop", "ZonaProp", "https://www.zonaprop.com.ar/departamentos-alquiler-capital-federal-orden-publicado-descendente.html"},
        {"argenprop", "ArgenProp", "https://www.argenprop.com/departamentos/alquiler/capital-federal?orden-masnuevos"},
        {"meli", "MercadoLibre", "https://inmuebles.mercadolibre.com.ar/departamentos/alquiler/capital-federal/"},
    };
    static final String[] PORTAL_HOME = {
        "https://www.zonaprop.com.ar/", "https://www.argenprop.com/", "https://inmuebles.mercadolibre.com.ar/"};

    private static Store inst;
    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    final Context ctx;
    final SharedPreferences sp;
    private LinkedHashMap<String, JSONObject> ads;
    volatile boolean busy;
    volatile boolean retrySoon;

    private Store(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("chanchi", Context.MODE_PRIVATE);
    }

    // ---------- configuración ----------
    String sourceUrl(String id) {
        for (String[] p : PORTALS) if (p[0].equals(id)) return sp.getString("url_" + id, p[2]);
        return "";
    }
    void setSourceUrl(String id, String url) { sp.edit().putString("url_" + id, url).apply(); }
    boolean sourceOn(String id) { return sp.getBoolean("on_" + id, true); }
    void setSourceOn(String id, boolean on) { sp.edit().putBoolean("on_" + id, on).apply(); }
    int interval() { return sp.getInt("every", 3); }
    void setInterval(int m) { sp.edit().putInt("every", Math.max(1, m)).apply(); }
    boolean watch() { return sp.getBoolean("watch", true); }
    void setWatch(boolean w) { sp.edit().putBoolean("watch", w).apply(); }
    double rate() { return Double.longBitsToDouble(sp.getLong("rate", Double.doubleToLongBits(1450))); }
    synchronized JSONArray alerts() {
        try { return new JSONArray(sp.getString("alerts", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    /** Zonas a buscar además de la búsqueda general: la zona actual y la de las alertas activas (máx. 3). */
    synchronized List<String> zones() {
        List<String> z = new ArrayList<>();
        String raw = sp.getString("zones", "");
        for (String x : raw.split("\\|")) if (!x.trim().isEmpty() && z.size() < 3) z.add(x.trim());
        return z;
    }

    boolean setConfig(String json) {
        boolean changed = false;
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor ed = sp.edit();
            if (o.has("alerts")) ed.putString("alerts", o.getJSONArray("alerts").toString());
            if (o.has("rate")) ed.putLong("rate", Double.doubleToLongBits(o.getDouble("rate")));
            if (o.has("zones")) {
                JSONArray za = o.getJSONArray("zones");
                LinkedHashSet<String> set = new LinkedHashSet<>();
                for (int i = 0; i < za.length(); i++) { String zz = za.optString(i, "").trim(); if (!zz.isEmpty() && Scraper.zoneUrl("meli", zz) != null) set.add(zz); }
                String nv = String.join("|", set);
                changed = !nv.equals(sp.getString("zones", ""));
                ed.putString("zones", nv);
            }
            ed.apply();
        } catch (Exception ignored) {}
        return changed;
    }

    // ---------- estado por fuente ----------
    JSONObject status(String id) {
        try { return new JSONObject(sp.getString("st_" + id, "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    void setStatus(String id, boolean ok, String msg, int count) {
        android.util.Log.i("ChanchiScan", id + " ok=" + ok + " count=" + count + " :: " + msg);
        try {
            JSONObject o = status(id);
            o.put("ok", ok); o.put("msg", msg); o.put("last", System.currentTimeMillis());
            if (count >= 0) o.put("count", count);
            if (ok && !o.has("base")) o.put("base", System.currentTimeMillis());
            sp.edit().putString("st_" + id, o.toString()).apply();
        } catch (Exception ignored) {}
    }
    boolean hasBaseline(String id) { return status(id).has("base"); }

    String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("watch", watch()); o.put("interval", interval()); o.put("busy", busy);
            JSONArray arr = new JSONArray();
            for (String[] p : PORTALS) {
                JSONObject s = status(p[0]);
                JSONObject x = new JSONObject();
                x.put("id", p[0]); x.put("name", p[1]); x.put("on", sourceOn(p[0]));
                x.put("msg", s.optString("msg", "Sin buscar todavía"));
                x.put("last", s.optLong("last", 0));
                if (s.has("ok")) x.put("ok", s.optBoolean("ok"));
                if (s.has("count")) x.put("count", s.optInt("count"));
                arr.put(x);
            }
            o.put("sources", arr);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ---------- avisos ----------
    private File adsFile() { return new File(ctx.getFilesDir(), "ads.json"); }
    File listingsFile() { return new File(ctx.getFilesDir(), "listings.json"); }

    static String readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }
    static void writeAll(File f, String s) throws Exception {
        File t = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(t)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!t.renameTo(f)) { f.delete(); t.renameTo(f); }
    }

    synchronized LinkedHashMap<String, JSONObject> ads() {
        if (ads == null) {
            ads = new LinkedHashMap<>();
            try {
                File f = adsFile();
                if (f.exists()) {
                    JSONArray a = new JSONArray(readAll(f));
                    for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); ads.put(o.getString("rid"), o); }
                }
            } catch (Exception ignored) {}
        }
        return ads;
    }

    synchronized void saveAds() {
        try {
            long cut = System.currentTimeMillis() - 7L * 86400000L;
            Iterator<Map.Entry<String, JSONObject>> it = ads().entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue().optLong("lastSeen") < cut) it.remove();
            while (ads().size() > 500) { ads().remove(ads().keySet().iterator().next()); }
            JSONArray all = new JSONArray(), pub = new JSONArray();
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (JSONObject o : ads().values()) {
                all.put(o);
                if (!o.optBoolean("ready") || o.optBoolean("bad") || !o.has("lat")) continue;
                JSONObject c = new JSONObject(o.toString());
                c.put("published", iso.format(new Date(o.optLong("firstSeen", System.currentTimeMillis()))));
                for (String k : new String[]{"firstSeen", "lastSeen", "ready", "tries", "base", "notified", "geo", "sig", "bad"}) c.remove(k);
                pub.put(c);
            }
            writeAll(adsFile(), all.toString());
            JSONObject out = new JSONObject();
            out.put("updatedAt", iso.format(new Date()));
            out.put("ads", pub);
            if (pub.length() > 0) writeAll(listingsFile(), out.toString());
        } catch (Exception ignored) {}
    }

    // ---------- caché de direcciones y avisos ya enviados ----------
    String geoGet(String key) { return sp.getString("geo_" + Integer.toHexString(key.hashCode()), null); }
    void geoPut(String key, String v) { sp.edit().putString("geo_" + Integer.toHexString(key.hashCode()), v).apply(); }
    synchronized boolean sigSeen(String s) { return sp.getStringSet("sigs", new HashSet<>()).contains(s); }
    synchronized void sigAdd(String s) {
        Set<String> cur = new HashSet<>(sp.getStringSet("sigs", new HashSet<>()));
        if (cur.size() > 600) cur.clear();
        cur.add(s);
        sp.edit().putStringSet("sigs", cur).apply();
    }
}
