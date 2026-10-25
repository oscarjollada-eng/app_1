package ar.chanchi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Busca avisos nuevos cada tanto y notifica. Corre como servicio en primer plano para no ser frenado por Android. */
public class WatchService extends Service {
    static final String CH_WATCH = "watch", CH_NEW = "new";
    static volatile Runnable onData;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Scraper scraper;
    private PowerManager.WakeLock wl;
    private boolean running;
    private final Runnable loop = new Runnable() {
        @Override public void run() { tick(); }
    };

    static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        try { c.startForegroundService(i); } catch (Exception ignored) {}
    }
    static void stop(Context c) { c.stopService(new Intent(c, WatchService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_WATCH, "Búsqueda en curso", NotificationManager.IMPORTANCE_MIN);
        a.setDescription("Aviso fijo mientras Chanchi vigila los portales");
        nm.createNotificationChannel(a);
        NotificationChannel b = new NotificationChannel(CH_NEW, "Avisos nuevos", NotificationManager.IMPORTANCE_HIGH);
        b.setDescription("Propiedades nuevas que coinciden con tus alertas");
        nm.createNotificationChannel(b);
        scraper = new Scraper(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Chanchi está buscando avisos")
            .setContentText("Te avisamos apenas aparezca algo que coincida con tus alertas")
            .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        if (!running) { running = true; h.post(loop); }
        else if (intent != null && "now".equals(intent.getAction()) && !Store.get(this).busy) { h.removeCallbacks(loop); h.post(loop); }
        return START_STICKY;
    }

    private void tick() {
        if (!running) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chanchi:scan");
        wl.acquire(4 * 60 * 1000L);
        if (Store.get(this).busy) { schedule(); return; }
        scraper.midway = () -> { Runnable r = onData; if (r != null) r.run(); };
        scraper.cycle(fresh -> {
            try { notifyNew(fresh); } catch (Exception ignored) {}
            if (wl != null && wl.isHeld()) wl.release();
            Runnable r = onData; if (r != null) r.run();
            schedule();
        });
    }

    private void schedule() {
        h.removeCallbacks(loop);
        if (running) h.postDelayed(loop, Store.get(this).interval() * 60_000L);
    }

    /** Pedido manual desde la app. */
    static void now(Context c) { Intent i = new Intent(c, WatchService.class).setAction("now"); try { c.startForegroundService(i); } catch (Exception ignored) {} }

    static void testNotify(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Notification n = new Notification.Builder(c, CH_NEW).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nuevo en Palermo: Honduras 4800")
            .setContentText("2 dorm. · 56 m² · $ 1.150.000 + exp. $ 210.000 · Así te va a llegar un aviso")
            .setContentIntent(PendingIntent.getActivity(c, 8, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build();
        nm.createNotificationChannel(new NotificationChannel(CH_NEW, "Avisos nuevos", NotificationManager.IMPORTANCE_HIGH));
        nm.notify(8, n);
    }

    private void notifyNew(List<JSONObject> fresh) {
        if (fresh.isEmpty()) return;
        Store st = Store.get(this);
        JSONArray alerts = st.alerts();
        double rate = st.rate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        int sent = 0, matched = 0;
        for (JSONObject ad : fresh) {
            boolean hit = false;
            for (int i = 0; i < alerts.length(); i++) {
                JSONObject al = alerts.optJSONObject(i);
                if (al == null || !al.optBoolean("on", true)) continue;
                JSONObject s = al.optJSONObject("search");
                if (s != null && Parse.matches(s, ad, rate)) { hit = true; break; }
            }
            if (!hit) continue;
            String sig = Parse.signature(ad);
            if (st.sigSeen(sig)) continue; // mismo depto ya avisado desde otro portal
            st.sigAdd(sig);
            matched++;
            if (sent >= 5) continue;
            sent++;
            boolean usd = "USD".equals(ad.optString("cur"));
            String price = (usd ? "USD " : "$ ") + String.format(java.util.Locale.forLanguageTag("es-AR"), "%,d", ad.optLong("rent"));
            String exp = ad.has("exp") ? " + exp. $ " + String.format(java.util.Locale.forLanguageTag("es-AR"), "%,d", ad.optLong("exp")) : "";
            String body = ad.optInt("dorm") + " dorm. · " + ad.optLong("m2") + " m² · " + price + exp + (ad.optBoolean("owner") ? " · Dueño directo" : "");
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(ad.optString("url")));
            PendingIntent pi = PendingIntent.getActivity(this, ad.optString("rid").hashCode(), view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CH_NEW)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Nuevo en " + ad.optString("barrio") + ": " + ad.optString("addr"))
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(ad.optString("rid").hashCode(), n);
        }
        if (matched > sent) {
            Notification n = new Notification.Builder(this, CH_NEW).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle((matched - sent) + " avisos más").setContentText("Abrí Chanchi para verlos todos")
                .setContentIntent(PendingIntent.getActivity(this, 7, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build();
            nm.notify(7, n);
        }
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
