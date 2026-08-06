package com.mojitama.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Mojitama — a single WebView hosting the bundled game.
 *
 * The game is served from a synthetic https origin rather than file://, because
 * localStorage (where every save lives) is unreliable on opaque file:// origins.
 * Nothing leaves the device: every request for this host is answered from assets.
 *
 * The app draws edge to edge (mandatory from targetSdk 35 anyway) and hands the
 * real WindowInsets — status bar, camera cutout, gesture bar — to the page as CSS
 * variables. WebView does not populate env(safe-area-inset-*) here, so without
 * this the header would sit underneath the punch-hole.
 */
public class MainActivity extends Activity {

    private static final String HOST = "mojitama.local";
    private static final String ORIGIN = "https://" + HOST + "/";

    /** Widget shortcut: open the app and run this action in the game itself. */
    static final String ACTION_OPEN = "com.mojitama.app.OPEN";
    static final String EXTRA_ACTION = "mojitama_action";

    private WebView web;
    private String pendingInsetJs;   // insets can arrive before the page is ready
    private boolean pageReady;
    private String pendingAction;    // widget shortcut waiting for the page to load

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                   | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        // transparent bars: the page's own background shows through, which is the
        // modern Android look and lets the pet's room run to the screen edges
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setNavigationBarContrastEnforced(false);
        }
        goEdgeToEdge(w);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#7C5CFF"));
        web.setFitsSystemWindows(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage — the save file
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);           // not needed: assets come via the interceptor
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);                    // ignore system font scaling; layout is fixed
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        // Bridge, local content only. MUST be a public class: WebView invokes
        // @JavascriptInterface methods by reflection, and a non-public declaring
        // class makes every call fail — silently, from the page's point of view.
        web.addJavascriptInterface(new Bridge(this), "MojitamaNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // everything lives inside the app; never hand a URL to the browser
                return !HOST.equals(request.getUrl().getHost());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!HOST.equals(request.getUrl().getHost())) return null;
                return fromAssets(request.getUrl().getPath());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingInsetJs != null) {
                    view.evaluateJavascript(pendingInsetJs, null);
                }
                pullSnapshot();
                runPendingAction();    // cold start: the page is only ready now
            }
        });

        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                publishInsets(insets);
                return insets;   // do not consume: the page pads itself in CSS
            }
        });

        setContentView(web);
        // native=1 tells the page to skip service-worker registration: SW fetches
        // do not pass through shouldInterceptRequest, so a SW here would cache
        // nothing and then serve nothing on the next launch.
        web.loadUrl(ORIGIN + "index.html?native=1");
        takeWidgetAction(getIntent());   // cold start from a widget shortcut
    }

    private void goEdgeToEdge(Window w) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Measure the real insets and push them to the page as --inset-* (in CSS px). */
    private void publishInsets(WindowInsets insets) {
        int top, right, bottom, left;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets i = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = i.top; right = i.right; bottom = i.bottom; left = i.left;
        } else {
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
            left = insets.getSystemWindowInsetLeft();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cut = insets.getDisplayCutout();
                if (cut != null) {
                    top = Math.max(top, cut.getSafeInsetTop());
                    right = Math.max(right, cut.getSafeInsetRight());
                    bottom = Math.max(bottom, cut.getSafeInsetBottom());
                    left = Math.max(left, cut.getSafeInsetLeft());
                }
            }
        }

        float d = getResources().getDisplayMetrics().density;
        if (d <= 0) d = 1f;

        String js = "(function(){var s=document.documentElement.style;"
                + var("--inset-top", top / d)
                + var("--inset-right", right / d)
                + var("--inset-bottom", bottom / d)
                + var("--inset-left", left / d)
                + "})();";

        pendingInsetJs = js;
        if (pageReady && web != null) web.evaluateJavascript(js, null);
    }

    private static String var(String name, float dp) {
        return "s.setProperty('" + name + "','" + Math.round(dp) + "px');";
    }

    /** Light bars carry dark icons and vice versa. */
    void applyBarAppearance(boolean dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                         | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View dv = getWindow().getDecorView();
            int f = dv.getSystemUiVisibility();
            if (dark) {
                f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            dv.setSystemUiVisibility(f);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View dv = getWindow().getDecorView();
            int f = dv.getSystemUiVisibility();
            if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            dv.setSystemUiVisibility(f);
        }
    }

    /**
     * The page's door into native.
     *
     * MUST be public (and is static, holding the Activity explicitly): WebView
     * finds and invokes @JavascriptInterface methods by reflection, and a
     * private or package-private declaring class makes every invocation fail.
     * The page sees the object but every call silently does nothing — which is
     * exactly how the home-screen widget ended up with no data to draw.
     *
     * Every method runs on the WebView's JavaBridge thread, never the UI thread,
     * so View work is posted to the main Looper and everything is wrapped: an
     * exception escaping here lands on a thread with no handler.
     */
    public static class Bridge {
        private final MainActivity act;

        Bridge(MainActivity act) { this.act = act; }

        @JavascriptInterface
        public void setDark(final boolean dark) {
            try {
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { act.applyBarAppearance(dark); }
                });
            } catch (Throwable ignored) {}
        }

        /** Latest game state, for the widget and for re-checking alarms at fire time. */
        @JavascriptInterface
        public void putSnapshot(String json) {
            try {
                if (json == null) return;
                Store.putSnapshot(act, json);
                MojitamaWidget.refresh(act);
            } catch (Throwable ignored) {}
        }

        /** Replace the reminder schedule. [{at,kind,title,text}], already thinned by the page. */
        @JavascriptInterface
        public void scheduleAlerts(String json) {
            try {
                Alerts.schedule(act, json);
            } catch (Throwable ignored) {}
        }

        /** Ask for POST_NOTIFICATIONS. No-op below API 33 or once answered. */
        @JavascriptInterface
        public void requestNotificationPermission() {
            try {
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { act.askForNotifications(); }
                });
            } catch (Throwable ignored) {}
        }

        /** Whether the system will actually display what we post. */
        @JavascriptInterface
        public boolean notificationsEnabled() {
            try {
                return Alerts.enabled(act);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /**
     * Fallback path for the widget's data, independent of addJavascriptInterface.
     *
     * evaluateJavascript works even if the bridge does not, so the widget can
     * still be drawn and the queued taps still delivered. Runs on the UI thread.
     */
    private void pullSnapshot() {
        if (web == null) return;
        try {
            web.evaluateJavascript(
                "(function(){try{return JSON.stringify(window.mojitama.snapshot());}"
                        + "catch(e){return null;}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        try {
                            if (value == null || "null".equals(value)) return;
                            // evaluateJavascript hands back a JSON-encoded value:
                            // our string arrives quoted and escaped
                            Object o = new org.json.JSONTokener(value).nextValue();
                            if (!(o instanceof String)) return;
                            Store.putSnapshot(MainActivity.this, (String) o);
                            MojitamaWidget.refresh(MainActivity.this);
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable ignored) {}
    }

    /** Remember a widget shortcut; run it as soon as the page can act on it. */
    private void takeWidgetAction(Intent intent) {
        if (intent == null) return;
        String a = intent.getStringExtra(EXTRA_ACTION);
        if (a == null) return;
        intent.removeExtra(EXTRA_ACTION);   // don't replay it on the next rotation
        pendingAction = a;
        if (pageReady) runPendingAction();
    }

    /**
     * Ask the game to perform the action, in the game.
     *
     * The widget never mutates state itself: it opens the app here and the player
     * sees the food menu / the mini-game / the broom, with every rule and guard
     * applied once, in the one place that owns them.
     */
    private void runPendingAction() {
        if (web == null || pendingAction == null) return;
        String a = pendingAction;
        pendingAction = null;
        try {
            web.evaluateJavascript(
                "(function(){try{return window.mojitama.widgetIntent("
                        + org.json.JSONObject.quote(a) + ");}catch(e){return false;}})()",
                null);
        } catch (Throwable ignored) {}
    }

    private static final int REQ_NOTIFICATIONS = 91;

    void askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return;              // implicitly granted before Tiramisu
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS);
            }
        } catch (Throwable ignored) {}
    }

    /** Serve one bundled asset, or 404 if it is not in the APK. */
    private WebResourceResponse fromAssets(String path) {
        if (path == null || path.equals("/")) path = "/index.html";
        String name = path.startsWith("/") ? path.substring(1) : path;
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (name.isEmpty()) name = "index.html";

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-cache");

        try {
            InputStream in = getAssets().open("web/" + name);
            WebResourceResponse res = new WebResourceResponse(mimeOf(name), "UTF-8", in);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                res.setStatusCodeAndReasonPhrase(200, "OK");
                res.setResponseHeaders(headers);
            }
            return res;
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "UTF-8",
                    404, "Not Found", headers, null);
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webmanifest") || name.endsWith(".json")) return "application/manifest+json";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);        // launched from a notification or widget while running
        takeWidgetAction(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // grab a fresh snapshot BEFORE freezing the page — this is the moment the
        // widget starts mattering, and the page is about to stop running
        pullSnapshot();
        if (web != null) {
            // without this the page keeps ticking with the screen off — saving to
            // disk and re-arming alarms every few seconds, forever
            web.onPause();
            web.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
            runPendingAction();      // a shortcut tapped while we were backgrounded
        }
        Alerts.ensureChannel(this);
        // alarms are wiped by reboot, app update and force-stop; re-arm as a
        // belt-and-braces recovery even if the boot receiver never ran
        Alerts.armNext(this);
        MojitamaWidget.refresh(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);   // keep the WebView alive across theme flips
        if (web != null) web.requestApplyInsets();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
