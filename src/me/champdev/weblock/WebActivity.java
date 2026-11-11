package me.champdev.weblock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One streaming site, full screen, with downloads, uploads, permissions, ad and pop-up blocking. */
public class WebActivity extends Activity {
    static final int REQ_FILE = 1, REQ_STORAGE = 5, REQ_PLAY = 6;
    // The chip that takes you back to the native player after Back
    // The Native player button: on the site's own player (the biggest video, else the biggest frame), top left, scrolling with it;
    // a tab on the right edge when there is no player to sit on
    // It carries the app's icon (drawn inline, so a site's image rules can't block it) and the name FMHYPlayer.
    static final String ICON_SVG = "<svg width=\"20\" height=\"20\" viewBox=\"0 0 108 108\" style=\"display:block\"><defs>"
        + "<linearGradient id=\"fpbg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#2E1A44\"/><stop offset=\"1\" stop-color=\"#110D18\"/></linearGradient>"
        + "<linearGradient id=\"fppl\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#FF6A85\"/><stop offset=\"1\" stop-color=\"#FF2E58\"/></linearGradient></defs>"
        + "<rect width=\"108\" height=\"108\" rx=\"26\" fill=\"url(#fpbg)\"/><circle cx=\"54\" cy=\"54\" r=\"30\" fill=\"none\" stroke=\"#FF3B5C\" stroke-opacity=\".4\" stroke-width=\"2.5\"/>"
        + "<path d=\"M46 38V70L72 54Z\" fill=\"url(#fppl)\" stroke=\"#FF3B5C\" stroke-width=\"6\" stroke-linejoin=\"round\"/></svg>";
    static final String CHIP = "(()=>{if(document.getElementById('fp-native'))return;const b=document.createElement('button');b.id='fp-native';b.title='FMHYPlayer';"
        + "try{b.innerHTML=" + JSONObject.quote(ICON_SVG) + "+'<span>FMHYPlayer</span>'}catch(x){b.textContent='\\u25B6 FMHYPlayer'}"
        + "b.style.cssText='z-index:2147483647;display:flex;align-items:center;gap:7px;padding:5px 11px 5px 6px;font:600 13px sans-serif;color:#fff;background:rgba(22,15,32,.92);border:1px solid #ff3b5c;border-radius:9px;box-shadow:0 2px 8px rgba(0,0,0,.45)';"
        + "const big=sel=>{let best=null,area=0;for(const e of document.querySelectorAll(sel)){const r=e.getBoundingClientRect();if(r.width>=160&&r.height>=90&&r.width*r.height>area){area=r.width*r.height;best=r}}return best};"
        + "const place=()=>{const r=big('video')||big('iframe');const s=b.style;"
        + "if(r){s.position='absolute';s.top=(r.top+scrollY+8)+'px';s.left=(r.left+scrollX+8)+'px';s.right='';s.transform='';s.borderRadius='8px'}"
        + "else{s.position='fixed';s.top='50%';s.left='';s.right='0';s.transform='translateY(-50%)';s.borderRadius='10px 0 0 10px'}};"
        + "place();const t=setInterval(()=>{if(!document.contains(b))clearInterval(t);else place()},1000);"
        + "b.onclick=e=>{e.stopPropagation();FMHY.openNative()};document.documentElement.appendChild(b)})()";
    // Remember <a download="name"> so blob downloads keep the page's filename
    static final String DL_HOOK = "if(!window.__wl){window.__wl=1;const c=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){window.__dl=this.download;return c.call(this)};"
        + "addEventListener('click',e=>{const a=e.target.closest&&e.target.closest('a[download]');if(a)window.__dl=a.download},true);"
        // On any click, report the TMDB artwork of what was clicked (its card, or the hero it sits in), because
        // player pages often only carry the site logo
        + "const tm=s=>/image\\.tmdb\\.org|\\/t\\/p\\//.test(s);addEventListener('click',e=>{let src='';"
        + "for(let el=e.target,i=0;el&&el.querySelector&&i<6&&!src;el=el.parentElement,i++){const im=[...el.querySelectorAll('img')].find(m=>tm(m.currentSrc||m.src));"
        + "if(im)src=im.currentSrc||im.src;else{const u=/url\\([\"']?([^\"')]+)/.exec(getComputedStyle(el).backgroundImage);if(u&&tm(u[1]))src=u[1]}}"
        + "if(!src){let best=0;for(const m of document.images){const r=m.getBoundingClientRect(),s2=m.currentSrc||m.src;"
        + "if(tm(s2)&&r.bottom>0&&r.top<innerHeight&&r.width*r.height>best){best=r.width*r.height;src=s2}}}"
        + "if(src)FMHY.poster(new URL(src,location.href).href)},true)}";
    // Page title and its poster for Continue watching: a TMDB poster/backdrop (what these sites use) wins,
    // then the biggest picture or CSS background on screen, then og:image
    static final String CAPTURE = "(()=>{const m=n=>(document.querySelector(`meta[property=\"${n}\"],meta[name=\"${n}\"]`)||{}).content||'';"
        + "let img='',best=0;const consider=(s,r)=>{if(!/^https?:/.test(s)||r.width<120||r.height<80)return;"
        + "const a=r.width*r.height*(/image\\.tmdb\\.org|\\/t\\/p\\//.test(s)?20:1);if(a>best){best=a;img=s}};"
        + "for(const i of document.images)consider(i.currentSrc||i.src,i.getBoundingClientRect());"
        + "for(const e of document.querySelectorAll('[style*=background]')){const u=/url\\([\"']?([^\"')]+)/.exec(e.style.backgroundImage||'');if(u)consider(new URL(u[1],location.href).href,e.getBoundingClientRect())}"
        + "const og=m('og:image')||m('twitter:image');if(og&&/tmdb/.test(og)&&!/tmdb/.test(img))img=og;"
        + "return JSON.stringify({title:document.title||m('og:title'),image:img||og})})()";

    WebView web;
    ProgressBar progress;
    TextView status;
    String site, siteUrl;
    volatile String lastClicked, lastPoster;
    volatile long lastPosterAt;
    volatile long lastClickedAt;
    ValueCallback<Uri[]> fileCb;
    WebChromeClient.FileChooserParams fileParams;
    View fullscreen;
    WebChromeClient.CustomViewCallback fullscreenCb;
    String spatnav;
    Pointer pointer;
    final Sniffer sniffer = new Sniffer(this::onSeen, this::onStream);
    boolean resumed;
    // The native player lists these (found on the page when a stream starts) and asks us to press one
    static WebActivity active;
    final java.util.Map<String, String[]> servers = new java.util.LinkedHashMap<>(), qualities = new java.util.LinkedHashMap<>(), audios = new java.util.LinkedHashMap<>();
    String audiosAt;   // the page the Sub / Dub list belongs to
    String audioNoop = "";   // the language a press changed nothing for here: the site keeps it from episode to episode, so the next ones skip the press
    String audioTriedAt = "";   // the page address the remembered Sub / Dub pick was last applied on
    String pendingServer;
    int[] pendingEpisode;      // {season, episode} picked in the player's Episodes list, opened when the page is back
    View opening;              // the backdrop (poster, title, what the app is doing now) while it drives the page
    TextView openingText;
    GifView openingGif;
    boolean pageError;         // the page just loaded was an error page
    boolean okOnScreen;        // an OK press began while the backdrop was up: its release takes the backdrop down
    boolean dismissed;         // you looked at the page (or it gave up, or the player took over): the backdrop stays down until the app starts something new
    Runnable openingGiveUp = () -> { };   // never null: Handler.removeCallbacks(null) would drop every message without a callback
    int[] episodeAfterLoad;    // the episode to press on the page once it has loaded (a site without an episode address)
    boolean playAfterLoad;     // an episode page opened from the app: press its Play once it has loaded
    volatile boolean episodeFound;
    boolean holdForEpisode;    // the page plays its first episode by itself while ours is being picked: don't hand that one over
    Catalog.Title show;        // the series or anime being watched (from the catalog, or found by the player from the page's title)
    volatile boolean scanDone;
    // Title to type into the site's own search box (set when you tap Watch on a movie or episode); cleared once typed
    volatile String pendingQuery;
    volatile String pendingPick;   // searched for this title: press it among the results when they come (cleared once pressed)
    int searchTries;

    static Intent open(Context c, String site, String siteUrl, String url) {
        return new Intent(c, WebActivity.class).putExtra("site", site).putExtra("siteUrl", siteUrl).putExtra("url", url);
    }

    final Runnable capture = () -> web.evaluateJavascript(CAPTURE, json -> {
        try {
            JSONObject o = new JSONObject(new JSONArray("[" + json + "]").getString(0));
            String image = o.optString("image");
            if (!History.isTmdb(image) && lastPoster != null && SystemClock.uptimeMillis() - lastPosterAt < 180000) image = lastPoster;
            // Opened from the catalog: its poster, while the page is that title (sites like Movy have none of their own)
            String name = getIntent().getStringExtra("name"), poster = getIntent().getStringExtra("poster");
            if (!History.isTmdb(image) && name != null && poster != null && poster.startsWith("http")
                && History.same(History.titleOf(o.optString("title"), site)).equals(History.same(name))) image = poster;
            History.record(site, siteUrl, web.getUrl(), o.optString("title"), image);
        } catch (Exception ignored) { }
    });

    final WebChromeClient chrome = new WebChromeClient() {
        @Override public void onProgressChanged(WebView v, int p) {
            progress.setProgress(p);
            progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
        }

        @Override public void onReceivedTitle(WebView v, String title) {
            scheduleCapture();
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            fileParams = p;
            openChooser();
            return true;
        }

        // A streaming app has no use for location, camera or microphone: sites asking for them are told no
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }

        @Override public void onPermissionRequest(PermissionRequest r) {
            List<String> ok = new ArrayList<>();   // what needs no device permission, such as protected (DRM) video
            for (String res : r.getResources())
                if (!res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) ok.add(res);
            if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
        }

        // JS dialogs: with an application-context WebView the default ones can't show, so draw them here
        @Override public boolean onJsAlert(WebView v, String url, String message, JsResult r) {
            new AlertDialog.Builder(WebActivity.this).setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm()).setOnCancelListener(d -> r.cancel()).show();
            return true;
        }

        @Override public boolean onJsConfirm(WebView v, String url, String message, JsResult r) {
            new AlertDialog.Builder(WebActivity.this).setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm()).setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                .setOnCancelListener(d -> r.cancel()).show();
            return true;
        }

        @Override public boolean onJsPrompt(WebView v, String url, String message, String def, JsPromptResult r) {
            EditText in = field(WebActivity.this, "", InputType.TYPE_CLASS_TEXT);
            in.setText(def);
            new AlertDialog.Builder(WebActivity.this).setMessage(message).setView(box(WebActivity.this, in))
                .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm(in.getText().toString())).setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                .setOnCancelListener(d -> r.cancel()).show();
            return true;
        }

        @Override public boolean onJsBeforeUnload(WebView v, String url, String message, JsResult r) {
            r.confirm();
            return true;
        }

        // Pop-ups (sports sites open their channels this way): the new window's address is caught in a throwaway WebView,
        // then dropped when it looks like an ad (see popupIsAd) and otherwise opened in this view, so Back comes back here
        @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
            String link = clickedLink();
            if (gesture && link != null && sameSite(link, v.getUrl())) { openHere(v, link); return false; }
            if (!gesture) { toast(App.t(R.string.web_popup_blocked)); return false; }
            long asked = SystemClock.uptimeMillis();
            WebView probe = new WebView(WebActivity.this);
            boolean[] state = {false, false};   // decided, destroyed
            Runnable dispose = () -> { if (!state[1]) { state[1] = true; probe.destroy(); } };
            probe.setWebViewClient(new WebViewClient() {
                void decide(String url) {
                    if (state[0] || url == null || url.startsWith("about:")) return;   // window.open() then a later address
                    state[0] = true;
                    App.main.post(dispose);
                    // Wait a moment: if the same click also moves this page, the pop-up was the ad
                    App.main.postDelayed(() -> {
                        if (isFinishing()) return;
                        boolean ad = popupIsAd(url, asked, link);
                        android.util.Log.i("web", "pop-up " + (ad ? "dropped " : "opened here ") + url);
                        if (!ad) { openHere(v, url); return; }
                        toast(App.t(R.string.web_popup_blocked));
                        // A pop-under also swallows the tap: open the link that was tapped (unless the page already went somewhere)
                        if (link != null && link.matches("(?i)https?://.+") && mainNavAt <= asked - 800 && !link.equals(v.getUrl())
                            && !(AdBlock.enabled() && AdBlock.blocked(String.valueOf(Uri.parse(link).getHost())))) {
                            android.util.Log.i("web", "tapped link opened instead " + link);
                            openHere(v, link);
                        }
                    }, Math.max(0, 700 - (SystemClock.uptimeMillis() - asked)));
                }

                @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) { decide(r.getUrl().toString()); return true; }

                @Override public void onPageStarted(WebView w, String url, Bitmap f) { decide(url); w.stopLoading(); }
            });
            // A window that never gets a real address (about:blank only) is dropped
            App.main.postDelayed(() -> { if (!state[0]) { state[0] = true; toast(App.t(R.string.web_popup_blocked)); } dispose.run(); }, 4000);
            ((WebView.WebViewTransport) msg.obj).setWebView(probe);
            msg.sendToTarget();
            return true;
        }

        // WebView draws a gray play-icon poster on <video> by default; Chrome shows nothing
        @Override public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override public void onShowCustomView(View v, CustomViewCallback cb) {
            if (fullscreen != null) { cb.onCustomViewHidden(); return; }
            fullscreen = v;
            fullscreenCb = cb;
            v.setBackgroundColor(Color.BLACK);
            decor().addView(v, new FrameLayout.LayoutParams(-1, -1));
            if (pointer != null) pointer.raise();
            decor().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override public void onHideCustomView() {
            if (fullscreen == null) return;
            decor().removeView(fullscreen);
            fullscreen = null;
            decor().setSystemUiVisibility(0);
            fullscreenCb.onCustomViewHidden();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        active = this;
        site = getIntent().getStringExtra("site");
        siteUrl = getIntent().getStringExtra("siteUrl");
        pendingQuery = getIntent().getStringExtra("q");
        pendingPick = pendingQuery;
        playAfterLoad = getIntent().getBooleanExtra("play", false);   // the title's own page, opened from the catalog
        String imdb = getIntent().getStringExtra("imdb");
        if (imdb != null && !imdb.isEmpty()) {
            show = new Catalog.Title();
            show.id = imdb;
            show.type = getIntent().getBooleanExtra("series", false) ? "series" : "movie";
            show.name = String.valueOf(getIntent().getStringExtra("name"));
            show.tmdb = String.valueOf(getIntent().getStringExtra("tmdb")).replace("null", "");
            show.anime = getIntent().getBooleanExtra("anime", false);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Home.BG);
        // Application context on purpose: Android's UiModeManager (which Chromium asks for) keeps whatever context it
        // was created from alive forever, and an activity context would leak the whole screen on every site visit.
        // Dialogs the page triggers are shown by our own client below, on this activity.
        web = new WebView(new android.view.ContextThemeWrapper(getApplicationContext(), R.style.AppTheme));
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-1, -1);
        // Settings, Address bar: the page's address on top; tap it (or click it with the cursor) to type another one
        if (App.prefs.getBoolean("urlbar", false)) {
            urlBar = new TextView(this);
            urlBar.setSingleLine(true);
            urlBar.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            urlBar.setTextColor(Home.TEXT);
            urlBar.setTextSize(App.tv ? 13 : 12);
            urlBar.setGravity(Gravity.CENTER_VERTICAL);
            int h = Home.dp(this, App.tv ? 30 : 28), pad = Home.dp(this, 10);   // slim: the page keeps the room
            urlBar.setPadding(pad, 0, pad, 0);
            urlBar.setBackgroundColor(Home.CARD);
            urlBar.setOnClickListener(v -> editAddress());
            urlBar.setText(getIntent().getStringExtra("url"));   // until the page reports where it really is
            root.addView(urlBar, new FrameLayout.LayoutParams(-1, h, Gravity.TOP));
            wl.topMargin = h;
        }
        root.addView(web, wl);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(Home.ACCENT));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, Home.dp(this, 4), Gravity.TOP);
        pl.topMargin = wl.topMargin;   // under the address bar when there is one
        root.addView(progress, pl);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        // The backdrop while the app drives the page (load, search, episode, play): the page flashing through its screens is not for watching
        LinearLayout wait = new LinearLayout(this);
        wait.setOrientation(LinearLayout.VERTICAL);
        wait.setGravity(Gravity.CENTER);
        wait.setBackgroundColor((Home.BG & 0xFFFFFF) | 0xCC000000);   // translucent: the page is faintly there
        wait.setClickable(true);   // a tap shows the page (and the touches don't reach it meanwhile)
        wait.setOnClickListener(v -> hideOpening());
        String name = getIntent().getStringExtra("name");
        TextView head = new TextView(this);
        head.setTextColor(Home.TEXT);
        head.setTextSize(App.tv ? 24 : 20);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setGravity(Gravity.CENTER);
        head.setPadding(Home.dp(this, 24), 0, Home.dp(this, 24), Home.dp(this, 20));
        head.setText(name == null || name.isEmpty() ? String.valueOf(site) : name);
        wait.addView(head);
        ProgressBar spin = new ProgressBar(this);   // until the picture of the state is here
        spin.setIndeterminateTintList(ColorStateList.valueOf(Home.ACCENT));
        wait.addView(spin, new LinearLayout.LayoutParams(Home.dp(this, 44), Home.dp(this, 44)));
        openingGif = new GifView(this, spin);
        int box = Home.dp(this, App.tv ? 190 : 140);   // every cat the same square, whatever the picture's shape
        wait.addView(openingGif, new LinearLayout.LayoutParams(box, box));
        openingText = new TextView(this);
        openingText.setTextColor(Home.TEXT);
        openingText.setTextSize(App.tv ? 20 : 17);
        openingText.setGravity(Gravity.CENTER);
        openingText.setPadding(Home.dp(this, 24), Home.dp(this, 16), Home.dp(this, 24), Home.dp(this, 4));
        wait.addView(openingText);
        TextView hint = new TextView(this);
        hint.setTextColor(Home.MUTED);
        hint.setTextSize(App.tv ? 15 : 13);
        hint.setText(App.t(R.string.web_opening_hint));
        hint.setGravity(Gravity.CENTER);
        wait.addView(hint);
        wait.setVisibility(View.GONE);
        opening = wait;
        root.addView(wait, new FrameLayout.LayoutParams(-1, -1));
        // Anything the app drives from the start (the title's own page, a search for it, an episode) is behind the backdrop from the first frame
        if (playAfterLoad || pendingQuery != null || (show != null && show.series() && getIntent().getIntExtra("episode", 0) > 0))
            step("load", App.t(Warp.state == Warp.State.CONNECTING ? R.string.web_connecting_warp : R.string.web_step_loading), null);
        if (!App.tv) addHomeButton(root);   // top right, where the player has it too; a TV has Menu, Back to home
        setContentView(root);
        // 90/120 Hz phones would repaint an animated page twice as often for no visible gain: ask for 60 (less heat)
        android.view.WindowManager.LayoutParams wp = getWindow().getAttributes();
        wp.preferredRefreshRate = 60f;
        getWindow().setAttributes(wp);
        if (App.tv) pointer = new Pointer(this, new Pointer.Host() {
            @Override public View target() { return fullscreen != null ? fullscreen : web; }
            @Override public WebView web() { return web; }
            @Override public boolean deliver(KeyEvent e) { return WebActivity.super.dispatchKeyEvent(e); }
            @Override public boolean tapAt(float x, float y) {
                if (urlBar == null || fullscreen != null) return false;
                int[] at = new int[2];
                urlBar.getLocationInWindow(at);
                if (x < at[0] || y < at[1] || x > at[0] + urlBar.getWidth() || y > at[1] + urlBar.getHeight()) return false;
                editAddress();
                return true;
            }
        });
        if (pointer != null && pointer.on && !App.prefs.getBoolean("pointerHint", false)) {
            App.prefs.edit().putBoolean("pointerHint", true).apply();
            Toast.makeText(this, App.t(R.string.web_cursor_hint), Toast.LENGTH_LONG).show();
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        // Only in the emulator test build (it is marked debuggable): lets Chrome DevTools attach to the page
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Dark pages: API 29-32 force dark, 33+ algorithmic darkening (needs the dark AppTheme on the WebView's context, see below)
        if (android.os.Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(true);
        else if (android.os.Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_ON);
        // Look like plain Chrome so sites don't block the embedded browser
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            // Redirect ads: leaving the site needs a real click on a link to it; script jumps are blocked
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString(), link = clickedLink();
                String scheme = String.valueOf(r.getUrl().getScheme()).toLowerCase();
                if (scheme.matches("about|data|blob|javascript")) return false;
                if (scheme.matches("https?")) {
                    if (!r.isForMainFrame() || r.isRedirect() || sameSite(url, v.getUrl()) || sameSite(url, link)) return false;
                    toast(App.t(R.string.web_blocked, r.getUrl().getHost()));
                } else if (url.equals(link)) {
                    openExternal(url);
                } else {
                    toast(App.t(R.string.web_blocked_link, scheme));
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                sniffer.request(r.getUrl().toString(), r.getRequestHeaders());
                String host = r.getUrl().getHost();
                if (host == null || r.isForMainFrame() || !AdBlock.enabled() || sameSite("https://" + host, siteUrl) || !AdBlock.blocked(host, r.getUrl().getPath())) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap f) { mainNavAt = SystemClock.uptimeMillis(); pageError = false; }

            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                mainNavAt = SystemClock.uptimeMillis();
                if (!Sniffer.path(url).equals(Sniffer.path(String.valueOf(audiosAt)))) audios.clear();   // another episode or title
                audiosAt = url;
                if (urlBar != null) urlBar.setText(url);
                sniffer.page(url);
                scheduleCapture();
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (pageError) return;
                cloudflareCheck(v);
                v.evaluateJavascript(DL_HOOK, null);
                if (spatnav != null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(spatnav, null);
                if (pointer != null && pointer.on) v.evaluateJavascript("window.__fpClear&&__fpClear()", null);
                String q = pendingQuery;
                if (q != null && searchTries++ < 4) { step("load", App.t(R.string.web_step_search), null); autosearch(v, q, false); }
                else if (pendingPick != null && searchTries++ < 8) { step("load", App.t(R.string.web_step_search), null); autosearch(v, pendingPick, true); }   // a results page that loaded anew
                if (playAfterLoad) {
                    playAfterLoad = false;
                    step("load", App.t(R.string.web_step_play), sorry());
                    // twice: single-page players set themselves up a few seconds after the load; never once a stream is found
                    for (long at : new long[]{2500, 6000}) App.main.postDelayed(() -> { if (!sniffer.has() && !isFinishing()) v.evaluateJavascript("postMessage({fp:'play'},'*')", null); }, at);
                }
                if (episodeAfterLoad != null && pendingPick == null) {   // on the show's page, not the search
                    int[] ep = episodeAfterLoad;
                    episodeAfterLoad = null;
                    App.main.postDelayed(() -> pressEpisode(ep[0], ep[1]), 2500);   // single-page sites draw their episode list after the load
                }
                scheduleCapture();
            }

            // The page the app is driving did not load (no network, timed out, no route): say so now, not after 40 seconds. An abort is not an error (a redirect).
            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (!r.isForMainFrame() || String.valueOf(e.getDescription()).contains("ERR_ABORTED")) return;
                pageError = true;   // the error page that follows is "finished" too: it is not the page to press play on
                if (opening != null && opening.getVisibility() == View.VISIBLE) fail(sorry());
            }

            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                httpAuth(h, host, realm);
            }
        });
        web.setWebChromeClient(chrome);
        web.setDownloadListener(this::download);
        web.addJavascriptInterface(new Bridge(), "FMHY");

        // assets/frame.js: pauses the page's players and finds its server list, in every frame
        try {
            String frameHook = Sites.readAll(getAssets().open("frame.js"));
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(web, frameHook, Collections.singleton("*"));
        } catch (IOException ignored) { }
        if (App.tv) {
            try {
                // roles.js (what things are) goes first; spatnav.js (what to do about them) uses it
                spatnav = Sites.readAll(getAssets().open("roles.js")) + "\n" + Sites.readAll(getAssets().open("spatnav.js"));
            } catch (IOException ignored) { }
            if (spatnav != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(web, spatnav, Collections.singleton("*"));
        }

        // Don't let the first request leak out before the WARP tunnel is in place
        String url = getIntent().getStringExtra("url");
        if (Warp.state == Warp.State.CONNECTING) status.setText(App.t(R.string.web_connecting_warp));
        Warp.whenSettled(() -> {
            status.setVisibility(View.GONE);
            if (opening.getVisibility() == View.VISIBLE) step("load", App.t(R.string.web_step_loading), null);
            if (Warp.state == Warp.State.FAILED) toast(App.t(R.string.web_warp_failed));
            // The page loads once the route is really set (it is set here, not at app start)
            Warp.route(!Warp.bypassed(siteOf(String.valueOf(Uri.parse(siteUrl).getHost()))), () -> {
                if (isFinishing() || isDestroyed()) return;
                int sn = getIntent().getIntExtra("season", 0), en = getIntent().getIntExtra("episode", 0);
                if (show != null && show.series() && en > 0) openEpisode(Math.max(1, sn), en, url);
                else web.loadUrl(url);
                web.requestFocus();
            });
        });
    }

    // Sites fill in the poster (og:image) a few seconds after the title, so look twice
    void scheduleCapture() {
        App.main.removeCallbacks(capture);
        App.main.postDelayed(capture, 2500);
        App.main.postDelayed(capture, 9000);
    }

    // A stream started on the page: hand it to the native player unless the user turned that off
    // A stream just appeared: look for the page's server list now, while the page is still awake
    void onSeen() {
        if (!resumed || isFinishing()) return;
        servers.clear();
        qualities.clear();   // Sub / Dub stays until the page changes: a scan right after a switch may miss its menu
        scanDone = false;
        web.evaluateJavascript(CHIP, null);   // a stream is here: the button stays until the player takes it, or for you to press
        web.evaluateJavascript("postMessage({fp:'servers'},'*')", null);
    }

    // Hand the stream over, but let the server scan finish first (it needs the page awake; the player pauses it), at most 3 s (a Servers menu takes a second to open)
    void onStream() {
        if (!resumed || isFinishing()) return;
        if (!App.prefs.getBoolean("native", true)) { hideOpening(); web.evaluateJavascript(CHIP, null); return; }
        if (holdForEpisode) return;   // picking the episode on the page: its stream comes next
        step("done", App.t(R.string.web_step_done), sorry());   // the stream is found: the player is next
        if (scanDone) playNative(); else { handoffWaiting = true; App.main.postDelayed(waitedHandoff, 3000); }
    }

    // (not Handler.hasCallbacks: it needs Android 10 and the app runs on 8 and 9)
    volatile boolean handoffWaiting;
    final Runnable waitedHandoff = () -> { handoffWaiting = false; if (resumed && !isFinishing() && !holdForEpisode && sniffer.best() != null) playNative(); };

    void playNative() {
        Sniffer.Hit h = sniffer.lastSeen();   // also the stream held back by a switch that did not happen
        if (h == null) { toast(App.t(R.string.web_no_video)); return; }
        sniffer.handedOff(h);
        if (fullscreen != null) chrome.onHideCustomView();
        // The CDN trusts the page that embedded the player, so replay what the WebView sent for the stream (Referer, Origin)
        Map<String, String> hd = new HashMap<>();
        hd.put("User-Agent", web.getSettings().getUserAgentString());
        String ref = header(h.headers, "Referer"), origin = header(h.headers, "Origin");
        if (ref == null) ref = web.getUrl();
        if (ref != null) hd.put("Referer", ref);
        if (origin == null && ref != null && !originOf(ref).equals(originOf(h.url))) origin = originOf(ref);
        if (origin != null) hd.put("Origin", origin);
        // Keep the page's own player quiet underneath
        web.evaluateJavascript("postMessage({fp:'pause'},'*');document.querySelectorAll('video,audio').forEach(m=>m.pause());var c=document.getElementById('fp-native');c&&c.remove()", null);
        Intent in = getIntent();
        startActivityForResult(PlayerActivity.open(this, h.url, h.dash, hd, sniffer.subtitles(), String.valueOf(web.getTitle()))
            .putExtra("pageTitle", String.valueOf(web.getTitle())).putExtra("pageUrl", String.valueOf(web.getUrl())).putExtra("site", site)
            .putExtra("siteUrl", siteUrl).putExtra("image", lastPoster)
            .putExtra("imdb", in.getStringExtra("imdb")).putExtra("series", in.getBooleanExtra("series", false))
            .putExtra("season", in.getIntExtra("season", 0)).putExtra("episode", in.getIntExtra("episode", 0)), REQ_PLAY);
    }

    /** The Sub / Dub (or language) picked in the player's Servers list is remembered per site and used on the next pages. */
    void rememberAudio(String label) {
        audioNoop = "";
        App.prefs.edit().putString("siteAudio:" + siteKey(), label).apply();
    }

    /**
     * A site that offers Sub and Dub (a language to pick before the server) starts in its own default: if you picked another one
     * before, press it now, before the stream goes to the player. If the stream does not change in a few seconds, put it back.
     */
    boolean tryPreferredAudio() {
        String want = App.prefs.getString("siteAudio:" + siteKey(), ""), url = String.valueOf(web.getUrl());
        if (want.isEmpty() || audios.size() < 2 || url.equals(audioTriedAt) || want.equals(audioNoop) || PlayerActivity.current != null) return false;
        audioTriedAt = url;
        String wantKey = null, nowKey = null;
        for (Map.Entry<String, String[]> e : audios.entrySet()) {
            if (e.getValue()[0].equalsIgnoreCase(want)) wantKey = e.getKey();
            if (!e.getValue()[1].isEmpty()) nowKey = e.getKey();
        }
        if (wantKey == null || wantKey.equals(nowKey)) return false;
        App.main.removeCallbacks(waitedHandoff);
        handoffWaiting = false;
        sniffer.switching();
        web.evaluateJavascript(clickJs(wantKey), null);
        final String back = nowKey;
        App.main.postDelayed(() -> {
            if (!resumed || isFinishing() || sniffer.has()) return;
            // no new stream: it was playing already (a site whose buttons don't say which is on), the episode has no such
            // audio, or the press missed. Go back when we know where back is, and hand over what plays
            if (back != null) web.evaluateJavascript(clickJs(back), null);
            else audioNoop = want;
            sniffer.unswitch();
        }, 3500);   // a real switch brings its stream in about a second; a press that changes nothing (the page was in that language already) is not worth more waiting
        return true;
    }

    /**
     * One episode: the site's own page for it when its address pattern is known (deeplinks "episode", or "anime" with Sub / Dub),
     * else the page it is on (or `fallback`) and the episode pressed in its list. The stream that starts goes to the player as usual.
     */
    void openEpisode(int season, int episode, String fallback) {
        getIntent().putExtra("season", season).putExtra("episode", episode);
        dismissed = false;   // a new episode: the backdrop again
        step("load", App.t(R.string.web_opening_episode, season, episode), App.t(R.string.web_pick_episode, season, episode));
        Catalog.Title t = show;
        String here = web.getUrl(), hereTitle = String.valueOf(web.getTitle());   // read here: the lookup below runs off the main thread
        String audio = App.prefs.getString("siteAudio:" + siteKey(), "").toLowerCase(java.util.Locale.ROOT).contains("dub") ? "dub" : "sub";
        App.io.execute(() -> {
            String link = null;
            if (t != null) {
                Map<String, String> v = new HashMap<>();
                v.put("tmdb", t.tmdb);
                v.put("imdb", t.id.startsWith("tt") ? t.id : "");
                v.put("season", String.valueOf(season));
                v.put("episode", String.valueOf(episode));
                v.put("audio", audio);
                if (t.anime && Sites.hasLink(siteUrl, "anime")) {
                    if (t.episodes.isEmpty()) Catalog.fill(t);
                    v.putAll(Catalog.animeEntry(t, season, episode));   // the anime sites' own episode number replaces the season's
                    link = Sites.link(siteUrl, "anime", v);
                }
                if (link == null) link = Sites.link(siteUrl, "episode", v);
            }
            // No address from the catalog (a resumed title, or one it does not know as anime): the page's own address with the episode swapped
            if (link == null) link = History.episodeUrl(here, History.episodeNumber(here, hereTitle), episode);
            String go = link;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                sniffer.switching();
                if (go != null) {
                    pendingQuery = pendingPick = null;   // the episode's own page: no search for the name on it
                    playAfterLoad = true;
                    web.loadUrl(go);
                }
                else if (fallback != null) { holdForEpisode = true; episodeAfterLoad = new int[]{season, episode}; web.loadUrl(fallback); }
                else { holdForEpisode = true; pressEpisode(season, episode); }
            });
        });
    }

    /** Press the episode in the page's own list (assets/frame.js); say so when no frame found it. */
    void pressEpisode(int season, int episode) {
        if (isFinishing()) return;
        episodeFound = false;
        web.evaluateJavascript("postMessage({fp:'episode',s:" + season + ",e:" + episode + "},'*')", null);
        App.main.postDelayed(() -> {
            if (episodeFound || isFinishing()) return;
            fail(App.t(R.string.web_pick_episode, season, episode));
            holdForEpisode = false;
            if (sniffer.has()) onStream();   // hand over what plays now
        }, 30000);   // a player that first looks for a source (P-Stream) shows its Episodes button late
    }

    /** Press a listed choice on the page; its name and kind go along, so frame.js can find it again if the page was redrawn. */
    String clickJs(String key) {
        String[] v = audios.containsKey(key) ? audios.get(key) : servers.containsKey(key) ? servers.get(key) : qualities.get(key);
        String kind = audios.containsKey(key) ? "a" : qualities.containsKey(key) ? "q" : "s";
        return "postMessage({fp:'click',k:" + JSONObject.quote(key) + ",kind:'" + kind + "',label:" + JSONObject.quote(v == null ? "" : v[0]) + "},'*')";
    }

    /** From the native player's Servers list: leave the player, press that button on the page, and go native again when its stream starts. */
    void switchServer(String key) {
        pendingServer = key;
    }

    static String header(Map<String, String> m, String name) {
        for (Map.Entry<String, String> e : m.entrySet()) if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue();
        return null;
    }

    static String originOf(String url) {
        Uri u = Uri.parse(url);
        return u.getScheme() + "://" + u.getAuthority();
    }

    long mainNavAt;   // when this view last started or changed page
    String lastOpened;
    long lastOpenedAt;

    /** Loads a pop-up's address or a tapped link here, once (the link's own new window may ask for it too). */
    void openHere(WebView v, String url) {
        if (url.equals(lastOpened) && SystemClock.uptimeMillis() - lastOpenedAt < 3000) return;
        lastOpened = url;
        lastOpenedAt = SystemClock.uptimeMillis();
        v.loadUrl(url);
    }

    /**
     * A pop-up is an ad when it is not a web page, goes somewhere other than the link that was tapped (pop-under scripts open
     * their ad on any tap), its host is on the ad blocklist, or this page moved on after the same tap.
     */
    boolean popupIsAd(String url, long asked, String link) {
        Uri u = Uri.parse(url);
        if (!String.valueOf(u.getScheme()).matches("https?") || u.getHost() == null) return true;
        if (link != null && !sameSite(url, link)) return true;
        if (mainNavAt > asked - 800) return true;
        return AdBlock.enabled() && AdBlock.blocked(u.getHost());
    }

    String clickedLink() {
        if (lastClicked != null && SystemClock.uptimeMillis() - lastClickedAt < 3000) return lastClicked;
        WebView.HitTestResult h = web.getHitTestResult();
        return h.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE ? h.getExtra() : null;
    }

    // ponytail: "site" = last two host labels, so example.co.uk counts every *.co.uk as one site
    static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        String ha = Uri.parse(a).getHost(), hb = Uri.parse(b).getHost();
        return ha != null && hb != null && siteOf(ha).equals(siteOf(hb));
    }

    static String siteOf(String host) {
        if (host.matches("[\\d.:\\[\\]]+")) return host;
        String[] p = host.split("\\.");
        return p.length < 2 ? host : p[p.length - 2] + "." + p[p.length - 1];
    }

    // tel:, mailto:, intent://, market:// etc. go to the matching app, like Chrome does
    void openExternal(String url) {
        Intent i;
        try { i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME); } catch (Exception e) { return; }
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.setComponent(null);
        i.setSelector(null);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            String fallback = i.getStringExtra("browser_fallback_url");
            if (fallback != null && fallback.matches("(?i)https?://.*")) web.loadUrl(fallback);
            else toast(App.t(R.string.web_no_app, url));
        }
    }

    void httpAuth(HttpAuthHandler h, String host, String realm) {
        WebViewDatabase db = WebViewDatabase.getInstance(this);
        String[] saved = db.getHttpAuthUsernamePassword(host, realm);
        if (saved != null && h.useHttpAuthUsernamePassword()) { h.proceed(saved[0], saved[1]); return; }
        EditText user = field(this, App.t(R.string.web_username), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(this, App.t(R.string.web_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.web_sign_in_to, host)).setView(box(this, user, pass))
            .setPositiveButton(App.t(R.string.web_sign_in), (d, w) -> {
                String u = user.getText().toString(), p = pass.getText().toString();
                db.setHttpAuthUsernamePassword(host, realm, u, p);
                h.proceed(u, p);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> h.cancel())
            .setOnCancelListener(d -> h.cancel())
            .show();
    }

    void download(String url, String ua, String disposition, String mime, long length) {
        if (Build.VERSION.SDK_INT < 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast(App.t(R.string.web_allow_storage));
            return;
        }
        String name = URLUtil.guessFileName(url, disposition, mime);
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // DownloadManager can't fetch these; read them in the page and hand bytes to Bridge.save
            web.evaluateJavascript("fetch(" + JSONObject.quote(url) + ").then(r=>r.blob()).then(b=>{const f=new FileReader();"
                + "f.onload=()=>{FMHY.save(f.result,window.__dl||" + JSONObject.quote(name) + ");window.__dl=''};f.readAsDataURL(b)})", null);
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                .addRequestHeader("User-Agent", ua)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            toast(App.t(R.string.web_downloading, name));
        } catch (Exception e) {
            toast(App.t(R.string.web_download_failed, e.getMessage()));
        }
    }

    /** Types the title into the site's search box (assets/autosearch.js); the page calls FMHY.searched() once it has. */
    void autosearch(WebView v, String q, boolean pickOnly) {
        java.util.regex.Matcher y = java.util.regex.Pattern.compile("\\d{4}").matcher(String.valueOf(getIntent().getStringExtra("year")));
        try {
            v.evaluateJavascript(Sites.readAll(getAssets().open("autosearch.js")) + "(" + JSONObject.quote(q) + "," + JSONObject.quote(y.find() ? y.group() : "") + "," + pickOnly + ")", null);
        } catch (IOException ignored) { }
    }

    public class Bridge {
        // A frame found server buttons: keep them for the native player's Servers list
        @JavascriptInterface public void servers(String json) {
            try {
                JSONArray a = new JSONArray(json);
                App.main.post(() -> {
                    try {
                        for (int i = 0; i < a.length(); i++) if ("a".equals(a.getJSONObject(i).optString("kind"))) { audios.clear(); break; }   // a fresh Sub / Dub list
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            String kind = o.optString("kind");
                            ("q".equals(kind) ? qualities : "a".equals(kind) ? audios : servers).put(o.getString("k"), new String[]{o.getString("label"), o.optBoolean("on") ? "1" : ""});
                        }
                    } catch (Exception ignored) { }
                });
            } catch (Exception ignored) { }
        }

        // A frame saw a playlist go by (found by its content): treat it like a .m3u8 / .mpd request
        @JavascriptInterface public void stream(String url, boolean dash, String referer, String origin) {
            Map<String, String> h = new HashMap<>();
            if (!referer.isEmpty()) h.put("Referer", referer);
            if (!origin.isEmpty()) h.put("Origin", origin);
            sniffer.manifest(url, dash, h);
        }

        // The page's own frame has finished looking for servers
        @JavascriptInterface public void episodeFound() {
            episodeFound = true;
            runOnUiThread(() -> {
                step("load", App.t(R.string.web_step_play), sorry());
                if (!holdForEpisode) return;
                holdForEpisode = false;
                sniffer.switching();   // the first episode's stream is not the one: wait for the picked one's
            });
        }

        @JavascriptInterface public void scanDone() {
            App.main.postDelayed(() -> {
                scanDone = true;
                if (tryPreferredAudio()) return;   // the page restarts in your language: the hand-off follows with that stream
                if (handoffWaiting) { App.main.removeCallbacks(waitedHandoff); waitedHandoff.run(); }
            }, 250);
        }

        @JavascriptInterface public void openNative() {
            runOnUiThread(WebActivity.this::playNative);
        }

        @JavascriptInterface public void searched() {
            pendingQuery = null;
        }

        // The title was pressed among the results: its page is next (a single-page site shows it without a new load)
        @JavascriptInterface public void picked() {
            pendingPick = null;
            runOnUiThread(() -> {
                int[] ep = episodeAfterLoad;
                if (ep == null) { hideOpening(); return; }   // the title's page: the rest is yours
                episodeAfterLoad = null;
                step("load", App.t(R.string.web_opening_episode, ep[0], ep[1]), App.t(R.string.web_pick_episode, ep[0], ep[1]));
                App.main.postDelayed(() -> pressEpisode(ep[0], ep[1]), 4000);
            });
        }

        // ponytail: whole file goes through a base64 string, very large blobs (100MB+) may OOM
        @JavascriptInterface public void save(String dataUrl, String name) {
            String safe = new File(name).getName();
            try {
                String mime = dataUrl.substring(5, dataUrl.indexOf(';'));
                byte[] bytes = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                try (OutputStream out = openDownload(safe, mime.isEmpty() ? "application/octet-stream" : mime)) {
                    out.write(bytes);
                }
                runOnUiThread(() -> toast(App.t(R.string.web_saved, safe)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(App.t(R.string.web_download_failed, e.getMessage())));
            }
        }

        @JavascriptInterface public void poster(String src) {
            lastPoster = src;
            lastPosterAt = SystemClock.uptimeMillis();
        }

        // Clean video mode: hide page layers over a playing video (a big stutter fix for 4K on weak TV chips)
        /** Called every second while a big video plays on the page (any frame). */
        @JavascriptInterface public void videoPlaying() {
            Pointer.videoAt = SystemClock.uptimeMillis();
        }

        @JavascriptInterface public boolean cleanVideo() {
            return App.prefs.getBoolean("cleanvideo", true);
        }

        // Pointer mode: the page's remote-control highlight stays off
        @JavascriptInterface public boolean pointer() {
            return pointer != null && pointer.on;
        }

        // Remote-control clicks aren't touches, so the page tells us which link was clicked
        @JavascriptInterface public void clicked(String href) {
            lastClicked = href;
            lastClickedAt = SystemClock.uptimeMillis();
        }
    }

    OutputStream openDownload(String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            return getContentResolver().openOutputStream(getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv));
        }
        return new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name));
    }

    void openChooser() {
        try { startActivityForResult(Intent.createChooser(fileParams.createIntent(), null), REQ_FILE); } catch (ActivityNotFoundException e) { finishFile(null); }
    }

    void finishFile(Uri[] out) {
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PLAY) {
            // Back from the player: offer the way back (a server switch keeps the page going, it is about to load the new stream)
            // The episode the catalog opened may not be the one picked on the site from here on
            if (pendingEpisode == null) {
                getIntent().removeExtra("season");
                getIntent().removeExtra("episode");
            }
            web.evaluateJavascript(CHIP, null);
            if (pendingServer == null) web.evaluateJavascript("postMessage({fp:'pause'},'*')", null);
            return;
        }
        if (req != REQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            ClipData clip = data == null ? null : data.getClipData();
            if (clip != null) {
                out = new Uri[clip.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = clip.getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) out = new Uri[]{data.getData()};
        }
        finishFile(out);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (Home.holdBack(this, e)) return true;
        int k = e.getKeyCode();
        // OK on the remote while the "Opening" screen is up shows the page (and is not pressed on the page behind it). Only a press that
        // started on the screen counts: the release of the OK that pressed Next episode in the player arrives here after the player
        // closed, and must not take the screen down the moment it goes up
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            boolean up = e.getAction() == KeyEvent.ACTION_UP;
            if (opening != null && opening.getVisibility() == View.VISIBLE) {
                if (!up && e.getRepeatCount() == 0) okOnScreen = true;
                if (up && okOnScreen) hideOpening();
                if (up) okOnScreen = false;
                return true;
            }
            if (up && okOnScreen) { okOnScreen = false; return true; }
        }
        if ((k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && sniffer.has()) {
            if (e.getAction() == KeyEvent.ACTION_UP) playNative();
            return true;
        }
        return pointer != null ? pointer.dispatch(e) : super.dispatchKeyEvent(e);
    }

    // Menu on the remote: reload, WARP for this site, pointer / focus mode
    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (k != KeyEvent.KEYCODE_MENU) return super.onKeyUp(k, e);
        String key = siteKey();
        boolean off = Warp.bypassed(key);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add(App.t(R.string.web_reload));
        acts.add(web::reload);
        if (sniffer.has()) { items.add(App.t(R.string.web_play_native)); acts.add(this::playNative); }
        boolean nat = App.prefs.getBoolean("native", true);
        items.add(App.t(nat ? R.string.web_native_auto : R.string.web_native_off));
        acts.add(() -> { App.prefs.edit().putBoolean("native", !nat).apply(); toast(App.t(nat ? R.string.web_native_off_toast : R.string.web_native_on_toast)); });
        items.add(App.t(off ? R.string.web_use_warp : R.string.web_no_warp, key));
        acts.add(() -> { Warp.setBypassed(key, !off); Warp.route(off, web::reload); toast(App.t(off ? R.string.web_warp_on_site : R.string.web_site_direct)); });
        if (pointer != null) {
            items.add(App.t(pointer.on ? R.string.web_to_focus : R.string.web_to_pointer));
            acts.add(() -> { pointer.okSwitched = false; pointer.switchMode(); });
        }
        boolean clean = App.prefs.getBoolean("cleanvideo", true);
        items.add(App.t(clean ? R.string.web_smooth_on : R.string.web_smooth_off));
        acts.add(() -> { App.prefs.edit().putBoolean("cleanvideo", !clean).apply(); toast(App.t(clean ? R.string.web_smooth_off_toast : R.string.web_smooth_on_toast)); });
        items.add(App.t(R.string.web_back_home));
        acts.add(() -> Home.goHome(this));
        new AlertDialog.Builder(this).setTitle(site)
            .setItems(items.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
        return true;
    }

    String siteKey() {
        return siteOf(String.valueOf(Uri.parse(siteUrl).getHost()));
    }

    // Cloudflare's "Attention Required" is a hard block of WARP's shared address: retry this site direct, once, and remember it
    void cloudflareCheck(WebView v) {
        String title = String.valueOf(v.getTitle());
        String key = siteKey();
        if (title.startsWith("Attention Required") && !Warp.bypassed(key) && Warp.state == Warp.State.ON) {
            Warp.setBypassed(key, true);
            toast(App.t(R.string.web_cf_blocked, key));
            Warp.route(false, v::reload);
        } else if (title.startsWith("Just a moment")) {
            toast(App.t(App.tv ? R.string.web_cf_check_tv : R.string.web_cf_check));
        }
    }

    @Override public void onBackPressed() {
        if (fullscreen != null) chrome.onHideCustomView();
        else if (opening.getVisibility() == View.VISIBLE) hideOpening();   // see the page
        else closePopupOrGoBack();
    }

    /**
     * The backdrop goes up (or says the next thing the app is doing) and the 40 s wait starts over; if nothing moves on it comes down
     * by itself, with `giveUp` as a note. Not after you looked at the page, until the app starts something new.
     */
    void step(String gif, String text, String giveUp) {
        if (dismissed || opening == null) return;
        if (!gif.equals(openingGif.want)) openingGif.play(gif);
        openingText.setText(text);
        opening.setVisibility(View.VISIBLE);
        App.main.removeCallbacks(openingGiveUp);
        openingGiveUp = () -> fail(giveUp);
        App.main.postDelayed(openingGiveUp, 40000);
    }

    /** Stuck: the staring cat says so for a few seconds, then the page is yours (the note is also a toast when no backdrop is up). */
    void fail(String note) {
        if (isFinishing()) return;
        if (opening.getVisibility() != View.VISIBLE) { if (note != null) toast(note); return; }
        openingGif.play("fail");
        openingText.setText(note != null ? note : App.t(R.string.web_step_failed));
        App.main.removeCallbacks(openingGiveUp);
        openingGiveUp = this::hideOpening;
        App.main.postDelayed(openingGiveUp, 4000);
    }

    /** What to tell when playing never started: the episode to pick by hand, if one was asked for. */
    String sorry() {
        int e = getIntent().getIntExtra("episode", 0);
        return e > 0 ? App.t(R.string.web_pick_episode, Math.max(1, getIntent().getIntExtra("season", 0)), e) : null;
    }

    void hideOpening() {
        dismissed = true;
        if (opening != null) opening.setVisibility(View.GONE);
        App.main.removeCallbacks(openingGiveUp);
    }

    /** A round Home button over the page (phones), top right where the player has it too. */
    void addHomeButton(FrameLayout root) {
        int size = Home.dp(this, 40);
        ImageView b = new ImageView(this);
        b.setImageResource(R.drawable.ic_p_home);
        b.setColorFilter(Home.ACCENT);   // the player's house is the same
        b.setPadding(size / 6, size / 6, size / 6, size / 6);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0x99000000);   // darker than the player's: pages are bright
        b.setBackground(bg);
        b.setContentDescription(App.t(R.string.player_home));
        b.setOnClickListener(v -> Home.goHome(this));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.setMargins(0, Home.dp(this, 10) + (urlBar == null ? 0 : urlBar.getLayoutParams().height), Home.dp(this, 16), 0);
        root.addView(b, lp);
    }

    // An open pop-up on the page gets closed first (the page script knows); otherwise Back leaves as usual.
    // The answer comes back asynchronously, so a stuck page can't make Back dead: after 400 ms we go back anyway.
    void closePopupOrGoBack() {
        if (spatnav == null) { goBackOrFinish(); return; }
        final boolean[] answered = {false};
        web.evaluateJavascript("window.__fpBack ? __fpBack() : false", v -> {
            if (answered[0]) return;
            answered[0] = true;
            if (!"true".equals(v)) goBackOrFinish();
        });
        App.main.postDelayed(() -> {
            if (answered[0]) return;
            answered[0] = true;
            goBackOrFinish();
        }, 400);
    }

    void goBackOrFinish() {
        if (web.canGoBack()) web.goBack(); else finish();
    }

    @Override protected void onStop() {
        hideOpening();   // the player is on screen now: the page is what you find when you come back
        super.onStop();
    }

    @Override protected void onPause() {
        resumed = false;
        App.main.removeCallbacks(capture);
        capture.run();
        super.onPause();
        web.onPause();
        web.pauseTimers();       // a page that isn't on screen must not keep running its scripts and ads
        CookieManager.getInstance().flush();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pendingEpisode != null) {
            int[] ep = pendingEpisode;
            pendingEpisode = null;
            openEpisode(ep[0], ep[1], null);
        }
        if (pendingServer != null) {
            String k = pendingServer;
            pendingServer = null;
            dismissed = false;
            step("load", App.t(R.string.web_step_play), null);
            sniffer.fresh();
            web.evaluateJavascript(clickJs(k), null);
        }
        web.onResume();
        web.resumeTimers();
    }

    // A WebView still attached to the window can't be destroyed: Chromium keeps it, and with it this activity.
    // Detach first, drop every callback that points back here, then destroy.
    @Override protected void onDestroy() {
        if (active == this) active = null;
        Warp.route(true);
        App.main.removeCallbacks(capture);
        App.main.removeCallbacks(openingGiveUp);
        if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; }
        if (fullscreen != null) chrome.onHideCustomView();
        if (pointer != null) pointer.release();
        web.stopLoading();
        web.removeJavascriptInterface("FMHY");
        web.setWebChromeClient(null);
        web.setWebViewClient(new WebViewClient());
        web.setDownloadListener(null);
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) parent.removeView(web);
        web.removeAllViews();
        web.destroy();
        super.onDestroy();
    }

    boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    FrameLayout decor() {
        return (FrameLayout) getWindow().getDecorView();
    }

    TextView urlBar;   // null unless Settings, Address bar is on

    /** Type an address (or words to search for) and go there. */
    void editAddress() {
        EditText in = field(this, App.t(R.string.web_address), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(web.getUrl());
        in.selectAll();
        new AlertDialog.Builder(this).setTitle(App.t(R.string.web_go_to)).setView(box(this, in))
            .setPositiveButton(App.t(R.string.web_go), (d, w) -> {
                String s = in.getText().toString().trim();
                if (s.isEmpty()) return;
                if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) s = s.contains(".") && !s.contains(" ") ? "https://" + s : "https://duckduckgo.com/?q=" + Uri.encode(s);
                web.loadUrl(s);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
        in.requestFocus();
    }

    static EditText field(Context c, String hint, int type) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(type);
        e.setTypeface(Typeface.DEFAULT);
        return e;
    }

    static LinearLayout box(Context c, View... views) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = Home.dp(c, 20);
        l.setPadding(pad, pad / 2, pad, 0);
        for (View v : views) l.addView(v);
        return l;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
