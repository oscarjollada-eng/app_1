package ar.chanchi.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private Store store;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setBackgroundColor(Color.TRANSPARENT);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost()) && "/www/listings.json".equals(u.getPath())) {
                    File f = store.listingsFile();
                    if (f.exists() && f.length() > 20) {
                        try {
                            HashMap<String, String> hd = new HashMap<>();
                            hd.put("Cache-Control", "no-store");
                            return new WebResourceResponse("application/json", "UTF-8", 200, "OK", hd, new FileInputStream(f));
                        } catch (Exception ignored) {}
                    }
                }
                return loader.shouldInterceptRequest(u);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "ChanchiNative");
        web.loadUrl("https://" + HOST + "/www/index.html");

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        String z = getIntent().getStringExtra("zone");
        if (z != null) store.setConfig("{\"zones\":[\"" + z + "\"]}");
        Updater.check(this);
    }

    @Override protected void onStart() {
        super.onStart();
        WatchService.start(this);
        WatchService.onData = () -> { if (web != null) web.evaluateJavascript("window.chanchiRefresh&&chanchiRefresh()", null); };
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.chanchiRefresh&&chanchiRefresh()", null);
    }

    @Override protected void onPause() { web.onPause(); super.onPause(); }

    @Override protected void onStop() {
        WatchService.onData = null;
        if (!store.watch()) WatchService.stop(this);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private void bars(String css, boolean dark) {
        Matcher m = Pattern.compile("(\\d+)[^\\d]+(\\d+)[^\\d]+(\\d+)").matcher(css == null ? "" : css);
        if (!m.find()) return;
        int c = Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(dark ? 0 : f);
    }

    /** Puente entre la interfaz web y el teléfono. */
    private final class Bridge {
        @JavascriptInterface public void setConfig(String json) { if (store.setConfig(json)) runOnUiThread(() -> WatchService.now(MainActivity.this)); }
        @JavascriptInterface public String getStatus() { return store.statusJson(); }
        @JavascriptInterface public void setSource(String id, boolean on) { store.setSourceOn(id, on); }
        @JavascriptInterface public void setEvery(int m) { store.setInterval(m); }
        @JavascriptInterface public boolean notifGranted() {
            return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public void requestNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            });
        }
        @JavascriptInterface public void setWatch(boolean w) {
            store.setWatch(w);
            runOnUiThread(() -> {
                if (w) {
                    WatchService.start(MainActivity.this);
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                    }
                }
            });
        }
        @JavascriptInterface public void testNotification() { WatchService.testNotify(MainActivity.this); }
        @JavascriptInterface public void refreshNow() { runOnUiThread(() -> WatchService.now(MainActivity.this)); }
        @JavascriptInterface public void pick(String id, boolean capture) {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, PickerActivity.class).putExtra("id", id).putExtra("capture", capture)));
        }
        @JavascriptInterface public void setBars(String css, boolean dark) { runOnUiThread(() -> bars(css, dark)); }
    }
}
