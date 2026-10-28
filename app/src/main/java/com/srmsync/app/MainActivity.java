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
import android.webkit.JavascriptInterface;
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
import org.json.JSONObject;

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
        if (!prefs.onboarded()) {
            showWelcome();
        } else {
            UpdateChecker.maybeCheck(this);   // not on the very first launch, one popup at a time
        }
    }

    private void setUpWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the site keeps its login in localStorage
        s.setSupportZoom(false);
        s.setUserAgentString(s.getUserAgentString() + " SrmSyncApp/1.3");

        web.addJavascriptInterface(new Bridge(), "SRMSyncApp");
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
                view.evaluateJavascript(ADD_SHARE_TO_MENU_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOfflinePage();
            }
        });
    }

    // ------------------------------------------------------------------ timetable sync

    /**
     * Adds a "Share" button to the website's own menu (the arc that opens from the round
     * button). It uses the site's classes, so it looks and moves like the other items, and
     * the site's own click handler closes the menu. The website itself is not changed:
     * the button only exists inside the app.
     */
    private static final String ADD_SHARE_TO_MENU_JS =
        "(function(){if(window.__srmSyncShare)return;"
        + "var ICON='<svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" "
        + "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"18\" cy=\"5\" r=\"3\"/>"
        + "<circle cx=\"6\" cy=\"12\" r=\"3\"/><circle cx=\"18\" cy=\"19\" r=\"3\"/>"
        + "<line x1=\"8.6\" y1=\"13.5\" x2=\"15.4\" y2=\"17.5\"/><line x1=\"15.4\" y1=\"6.5\" x2=\"8.6\" y2=\"10.5\"/></svg>';"
        + "function add(){var box=document.getElementById('radialMenuItems');if(!box)return false;"
        + "if(box.querySelector('[data-action=\"srmsyncShare\"]'))return true;"
        + "var b=document.createElement('button');b.type='button';b.className='radial-item menu-item';"
        + "b.setAttribute('data-action','srmsyncShare');"
        + "b.setAttribute('data-index',String(box.querySelectorAll('.radial-item').length));"
        + "b.innerHTML='<span class=\"radial-item-icon\">'+ICON+'</span><span class=\"radial-item-label\">Share</span>';"
        + "b.addEventListener('click',function(){try{SRMSyncApp.openShare()}catch(e){}});"
        + "box.appendChild(b);return true}"
        + "window.__srmSyncShare=true;"
        + "if(!add()){var n=0,t=setInterval(function(){if(add()||++n>30)clearInterval(t)},1000)}"
        + "var box=document.getElementById('radialMenuItems');"
        + "if(box)new MutationObserver(function(){add()}).observe(box,{childList:true})})()";

    /** Reads the login session id the website saved (either storage), or "" if logged out. */
    private static final String READ_SESSION_JS =
        "(function(){try{if(!/(^|\\.)srmsync\\.in$/.test(location.hostname))return '';"
        + "return sessionStorage.getItem('sessionId')||localStorage.getItem('sessionId')||''}"
        + "catch(e){return ''}})()";

    /**
     * Asks the page itself to fetch the timetable, then attendance, then internal marks
     * (one after the other, so they don't trip over each other on the server).
     */
    private static String fetchTimetableJs(String sessionId) {
        String sid = JSONObject.quote(sessionId);
        return "(function(){var h={'X-Client-Session':" + sid + "};"
            + "function get(path,cb){return fetch(location.origin+path,{headers:h})"
            + ".then(function(r){return r.text().then(function(t){cb(r.status,t)})})"
            + ".catch(function(e){cb(0,String(e))})}"
            + "get('/api/timetable',function(s,t){SRMSyncApp.onTimetable(" + sid + ",s,t)})"
            + ".then(function(){return get('/api/attendance',function(s,t){SRMSyncApp.onAttendance(" + sid + ",s,t)})})"
            + ".then(function(){return get('/api/internal-marks',function(s,t){SRMSyncApp.onMarks(" + sid + ",s,t)})});"
            + "return 'started'})()";
    }

    private void checkLogin() {
        if (web == null) return;
        boolean forced = prefs.syncRequested();
        web.evaluateJavascript(READ_SESSION_JS, value -> {
            String sessionId = unquote(value);
            if (sessionId.isEmpty()) {
                if (forced) {
                    prefs.setSyncRequested(false);
                    Toast.makeText(this, "Log in first, then sync again.", Toast.LENGTH_LONG).show();
                }
                return;
            }
            prefs.setLastSessionSeen(sessionId);

            if (forced || TimetableSync.isDue(prefs, sessionId, System.currentTimeMillis())) {
                prefs.setSyncRequested(false);
                prefs.setLastAttemptAt(System.currentTimeMillis());
                showSyncToast = forced || prefs.lastSyncAt() == 0;
                if (web != null) web.evaluateJavascript(fetchTimetableJs(sessionId), null);
            }
        });
    }

    private volatile boolean showSyncToast;

    /** Fetch timetable, attendance and marks right now (used by Share when nothing is saved yet). */
    void requestSync() {
        prefs.setSyncRequested(true);
        Toast.makeText(this, "Fetching your data\u2026", Toast.LENGTH_SHORT).show();
        checkLogin();
    }

    /** The website page calls these. They run on a background thread. */
    private final class Bridge {
        @JavascriptInterface
        public void onAttendance(String sessionId, int status, String body) {
            AttendanceWatch.handle(MainActivity.this, status, body);
        }

        @JavascriptInterface
        public void openShare() {
            runOnUiThread(() -> {
                if (!isFinishing()) ShareMenu.open(MainActivity.this);
            });
        }

        @JavascriptInterface
        public void onMarks(String sessionId, int status, String body) {
            Marks.handle(MainActivity.this, status, body);
        }

        @JavascriptInterface
        public void onTimetable(String sessionId, int status, String body) {
            TimetableSync.Result r = TimetableSync.handle(MainActivity.this, sessionId, status, body);
            boolean show = showSyncToast || r.changed;   // background re-syncs stay quiet
            showSyncToast = false;
            if (show) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    r.ok ? r.message + " Auto-silent is ready." : r.message, Toast.LENGTH_LONG).show());
            }
        }
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
            .setMessage("Srm Sync can put your phone on silent during your classes and switch it back "
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
        web.resumeTimers();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        web.onPause();
        // onPause() alone stops drawing but NOT the website's JavaScript timers
        // (it re-renders the live-class box every 30 s). This freezes them too,
        // so the page uses no power while you're outside the app.
        web.pauseTimers();
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
