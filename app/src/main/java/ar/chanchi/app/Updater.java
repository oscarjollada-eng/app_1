package ar.chanchi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Busca versiones nuevas en los Releases del repositorio y las instala (Android pide confirmar). */
final class Updater {
    static final String API = "https://api.github.com/repos/oscarjollada-eng/app_1/releases/latest";

    static void check(Activity a) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Chanchi");
                if (c.getResponseCode() != 200) return;
                String body = new String(readAll(c.getInputStream()), "UTF-8");
                JSONObject rel = new JSONObject(body);
                int code = Integer.parseInt(rel.getString("tag_name").replaceAll("[^0-9]", ""));
                if (code <= BuildConfig.VERSION_CODE) return;
                JSONArray assets = rel.getJSONArray("assets");
                String url = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject o = assets.getJSONObject(i);
                    if (o.getString("name").endsWith(".apk")) { url = o.getString("browser_download_url"); break; }
                }
                if (url == null) return;
                final String fu = url;
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a)
                        .setTitle("Hay una versión nueva de Chanchi")
                        .setMessage("Se descarga y se instala en un momento. Android te va a pedir confirmar.")
                        .setPositiveButton("Actualizar", (d, w) -> download(a, fu))
                        .setNegativeButton("Después", null).show();
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private static void download(Activity a, String url) {
        Toast.makeText(a, "Descargando actualización…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                File dir = new File(a.getCacheDir(), "updates");
                dir.mkdirs();
                File f = new File(dir, "chanchi.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                a.runOnUiThread(() -> install(a, f));
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "No se pudo descargar la actualización", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void install(Activity a, File f) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Permití instalar desde Chanchi y volvé a tocar Actualizar", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Uri u = FileProvider.getUriForFile(a, "ar.chanchi.app.files", f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
