package ar.chanchi.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Navegador visible para (1) resolver una verificación del portal una sola vez y (2) elegir la búsqueda:
 * se filtra como en el sitio y se toca "Usar esta búsqueda".
 */
public class PickerActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String id = getIntent().getStringExtra("id");
        final boolean capture = getIntent().getBooleanExtra("capture", false);
        final Store store = Store.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText(capture
            ? "Filtrá como quieras (zona, dormitorios, orden por más nuevos) y tocá «Usar esta búsqueda»."
            : "Si el portal pide verificación, resolvela acá. Después tocá «Listo».");
        hint.setPadding(32, 24, 32, 16);
        hint.setTextSize(14);
        root.addView(hint);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(16, 12, 16, 12);
        if (capture) {
            Button use = new Button(this);
            use.setText("Usar esta búsqueda");
            use.setOnClickListener(v -> {
                String u = web.getUrl();
                if (u != null && u.startsWith("http")) {
                    store.setSourceUrl(id, u);
                    Toast.makeText(this, "Búsqueda guardada", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
            bar.addView(use, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        Button ok = new Button(this);
        ok.setText("Listo");
        ok.setOnClickListener(v -> finish());
        bar.addView(ok, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);
        setContentView(root);

        String start = store.sourceUrl(id);
        if (start == null || start.isEmpty()) start = "https://www.google.com/";
        web.loadUrl(start);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        CookieManager.getInstance().flush();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
