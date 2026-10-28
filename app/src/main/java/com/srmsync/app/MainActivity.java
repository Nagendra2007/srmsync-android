package com.srmsync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

/**
 * The main screen: your srmsync.in website, shown inside the app,
 * plus a small floating button that opens the class-silent settings.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://srmsync.in/";
    private static final long POLL_MS = 15_000L;

    private WebView web;
    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Every 15 s while the app is open: has the student logged in? Then sync the timetable. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            checkLogin();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(makeSilentButton(root));
        setContentView(root);

        setUpWebView();
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }

        SilentScheduler.refresh(this);
        if (!prefs.onboarded()) showWelcome();
    }

    private void setUpWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the site keeps its login in localStorage
        s.setSupportZoom(false);
        s.setUserAgentString(s.getUserAgentString() + " SRMSyncApp/1.0");

        web.setWebChromeClient(new WebChromeClient());   // makes alert()/confirm() work
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String host = url.getHost() == null ? "" : url.getHost();
                if (host.equals("srmsync.in") || host.endsWith(".srmsync.in")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));   // other links open in the browser
                } catch (ActivityNotFoundException ignored) {
                    // nothing can open it; just stay put
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                checkLogin();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOfflinePage();
            }
        });
    }

    // ------------------------------------------------------------------ timetable sync

    private void checkLogin() {
        if (web == null) return;
        web.evaluateJavascript("(function(){try{return localStorage.getItem('sessionId')||''}catch(e){return ''}})()", value -> {
            String sessionId = unquote(value);
            if (sessionId.isEmpty()) return;
            prefs.setLastSessionSeen(sessionId);

            if (TimetableSync.isDue(prefs, sessionId, System.currentTimeMillis())) {
                boolean firstEver = prefs.lastSyncAt() == 0;
                TimetableSync.sync(this, sessionId, (ok, changed, message) -> {
                    if (ok && (firstEver || changed)) {
                        Toast.makeText(this, message + " Auto-silent is ready.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** evaluateJavascript returns a JSON-quoted string; this strips the quotes. */
    private static String unquote(String jsValue) {
        if (jsValue == null || jsValue.equals("null")) return "";
        try {
            return new JSONArray("[" + jsValue + "]").optString(0, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ floating button

    private View makeSilentButton(FrameLayout root) {
        TextView fab = new TextView(this);
        fab.setText("\uD83D\uDD15");   // bell-with-slash emoji
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        fab.setGravity(Gravity.CENTER);
        fab.setContentDescription("Class silent settings");
        fab.setAlpha(0.9f);
        fab.setElevation(dp(6));

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#E3262B"));
        fab.setBackground(circle);

        int size = dp(48);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(14), dp(96));
        fab.setLayoutParams(lp);

        fab.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Drag it up or down if it covers something on the website.
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private float startTranslation;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startTranslation = v.getTranslationY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            float top = -(root.getHeight() - dp(96) - v.getHeight() - dp(24));
                            v.setTranslationY(Math.max(top, Math.min(dp(80), startTranslation + dy)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) v.performClick();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return fab;
    }

    // ------------------------------------------------------------------ first run + offline

    private void showWelcome() {
        prefs.setOnboarded();
        new AlertDialog.Builder(this)
            .setTitle("Auto-silent in class")
            .setMessage("This app can put your phone on silent during your classes and switch it back "
                + "after, using your SRM timetable.\n\nIt needs two or three permissions. Set it up now? "
                + "(You can always open it later with the red \uD83D\uDD15 button.)")
            .setPositiveButton("Set up", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
            .setNegativeButton("Later", null)
            .show();
    }

    private void showOfflinePage() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='font-family:sans-serif;padding:32px;text-align:center;color:#333'>"
            + "<h2>Can't reach srmsync.in</h2>"
            + "<p>Check your internet connection.</p>"
            + "<p>Auto-silent still works offline once your timetable has synced.</p>"
            + "<p><a href='" + HOME + "' style='font-size:18px'>Try again</a></p>"
            + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        web.destroy();
        web = null;
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
