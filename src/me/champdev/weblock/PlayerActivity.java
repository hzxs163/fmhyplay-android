package me.champdev.weblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONObject;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Plays the stream a site started in a real ExoPlayer (hardware decoder, no web page painted over it). Remote: OK pause, left/right seek, up controls, down settings. */
public class PlayerActivity extends Activity {
    static final String TAG = "player";
    // Where each stream was left, so Back to the page and "Play natively" again picks up there (until the app is closed)
    static final Map<String, Long> resume = new HashMap<>();
    // A server/quality switch happens on the page and starts a new stream: keep the position across it
    static long carryMs, carryAt;
    static PlayerActivity current;   // the open player, for casts to this TV (Cast)
    static final int REQ_CAST = 7;
    String casting;                  // the TV this video was sent to, while the phone is its remote
    static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    static final int GESTURE_BAR_DP = 140;

    ExoPlayer player;
    SurfaceView surface;
    TextView subtitle, title, time, timeTotal, notice;
    ProgressBar spinner;
    ImageView playPause;
    SeekBar seek;
    View controls;
    String base;
    ProxySelector previousProxy;
    boolean dragging;
    long pending = -1; // where the last seek key press is heading; the real seek happens once the key rests
    float videoRatio;
    long hideAt;
    LinearLayout chipRow;
    final List<LinearLayout> chips = new ArrayList<>();
    LinearLayout chipCast, chipRotate, chipEpisodes, chipPrev, chipNext, chipDub, chipServers, chipQuality, chipAudio, chipSubs, chipDownload, chipMore;
    Catalog.Title seriesInfo;   // the series playing, with its episodes (for Episodes and Next)
    View panel;                 // the Episodes list, over the video
    boolean shelfOpen;          // the panel is the strip of cards along the bottom (the controls stay on with it)
    TextView skipBtn;           // Skip intro / Skip recap / Next episode, over the picture
    TextView tip;               // the name of the button the remote is on (icons only otherwise)
    View tipFor;                // the button the label above is showing the name of
    final SpeechMeter meter = new SpeechMeter();   // how loud the dialogue is, every 0.1 s (subtitle auto-sync)
    List<long[]> syncCues;      // the downloaded subtitle file's lines, to line up with the speech
    int syncTries;
    long syncSet;               // the timing auto-sync last set (a different one means you set it yourself: then it stops)
    List<Skips.Part> skips = new ArrayList<>();
    final java.util.Set<Skips.Part> skipDone = new java.util.HashSet<>();   // skipped, or the next episode was refused by seeking back
    Skips.Part skipShown;
    boolean skipsAsked;
    Skips.Part tail;            // the last 30 s, when no credits are known for this copy
    int cutTop, cutRight;       // the camera cutout's room at the top and right (the Episodes list keeps clear of it)
    String chipLayoutKey = "";
    LinearLayout topBar, bottomBar;      // padded for notches; the centre buttons stay centred
    View rew, fwd;                       // the -10 / +10 buttons beside play
    TextView epLine;                     // "Season 1 · Episode 3" under the title
    LinearLayout gesture;                // brightness / volume swipe indicator
    ImageView gestureIcon;
    View gestureFill;                    // the filled part of the indicator's vertical bar
    TextView gestureText;
    int zoom = 0;   // Fit at the start of every playback (the choice is not remembered)
    static final int[] ZOOMS = {R.string.player_zoom_fit, R.string.player_zoom_fill, R.string.player_zoom_stretch, R.string.player_zoom_16_9, R.string.player_zoom_4_3};
    android.media.audiofx.LoudnessEnhancer booster;   // Volume boost, on the player's audio session
    android.media.audiofx.Equalizer voices;           // Dialog boost, same session
    // Every video starts with the Settings defaults; the Audio button changes them for this video only
    int boost = boostDb();
    int voice = dialogLevel();
    boolean subsTried;   // the remembered or automatic subtitles were looked at for this video
    MediaItem.Builder mediaItem;   // kept so an OpenSubtitles pick can be added to the playing stream
    List<MediaItem.SubtitleConfiguration> subList;

    private float shownFps;

    long startedAt;   // where this video was resumed (0: from the start)
    int saveTicks;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            checkSkip();
            if (++saveTicks >= 20) { saveTicks = 0; if (player != null && player.isPlaying()) savePosition(); }   // every 10 s: a killed app still has the place
            if (Math.round(measuredFps) != Math.round(shownFps)) { shownFps = measuredFps; refreshChips(); }
            App.main.postDelayed(this, 500);
        }
    };

    static android.content.Intent open(android.content.Context c, String url, boolean dash, Map<String, String> headers, List<String> subs, String title) {
        return new android.content.Intent(c, PlayerActivity.class).putExtra("url", url).putExtra("dash", dash)
            .putExtra("headers", new JSONObject(headers).toString()).putStringArrayListExtra("subs", new ArrayList<>(subs)).putExtra("title", title);
    }

    /** The IPTV channel's own address for its records; a Stalker channel plays from a link made for this one play. */
    String channel() {
        String c = getIntent().getStringExtra("channel");
        return c != null ? c : getIntent().getStringExtra("url");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = getIntent().getStringExtra("url");
        if (url == null) { finish(); return; }
        base = Sniffer.path(url);
        Subs.delayMs = 0;   // timing belongs to one video
        // The manifest says fullUser: a phone opens the player the way it is held (no landscape flash), a TV is landscape anyway.
        // The Full screen button turns a phone by hand.
        if (getIntent().hasExtra("mime")) sibs = Iptv.siblings(channel());   // a live channel: its neighbours
        buildUi();
        routeThroughWarp();
        startPlayer(url);
    }

    List<Iptv.Channel> sibs = new ArrayList<>();   // a live channel's category and then the country's other ones, for Channels and the +/- keys

    /** The channel `dir` places on in its category (wraps round); null when this is not a listed channel. */
    Iptv.Channel channelStep(int dir) {
        for (int i = 0; i < sibs.size() && sibs.size() > 1; i++)
            if (sibs.get(i).url.equals(channel())) return sibs.get(((i + dir) % sibs.size() + sibs.size()) % sibs.size());
        return null;
    }

    void playChannel(Iptv.Channel c) {
        if (c == null) return;
        say(c.name);
        Iptv.play(this, c);
        finish();
    }

    /** The channels of this one's category, then the country's other categories, as a strip of cards along the bottom (a window of them around the one playing). */
    void channelsShelf() {
        int at = 0;
        for (int i = 0; i < sibs.size(); i++) if (sibs.get(i).url.equals(channel())) at = i;
        int from = Math.max(0, at - 20);
        List<Iptv.Channel> list = sibs.subList(from, sibs.size());   // this category, then the country's other ones; the strip adds cards as it is scrolled
        Iptv.Channel me = sibs.get(at);
        shelf(App.t(R.string.player_channels) + "  \u00B7  " + Home.groupName(me.cats[0]) + (me.country.isEmpty() ? "" : "  \u00B7  " + me.country), list.size(), 0,
            i -> new String[]{list.get(i).name, list.get(i).logo}, i -> { if (list.get(i) != me) playChannel(list.get(i)); }, at - from);
    }

    /** A movie: other movies to go to (the viewer's country's when there are, else the new ones), as the same strip. Picking one opens its page. */
    void moreShelf() {
        String region = App.region();
        List<Catalog.Title> hit = Catalog.languages(region).length > 0 ? Catalog.cached("movie", "local=" + region) : null;
        if (hit == null || hit.isEmpty()) hit = Catalog.cached("movie", "genre=" + Catalog.NEW);
        if (hit == null || hit.isEmpty()) hit = Catalog.cached("movie", "");
        if (hit == null || hit.isEmpty()) {   // nothing on disk (a first run, or the lists ran out): ask, then open
            say(App.t(R.string.home_loading));
            App.io.execute(() -> {
                List<Catalog.Title> got = Catalog.list("movie", "");
                runOnUiThread(() -> { if (isFinishing()) return; if (got == null || got.isEmpty()) say(App.t(R.string.home_catalog_offline)); else moreShelf(got); });
            });
            return;
        }
        moreShelf(hit);
    }

    void moreShelf(List<Catalog.Title> l) {
        String playing = String.valueOf(getIntent().getStringExtra("imdb"));
        List<Catalog.Title> list = new ArrayList<>();
        for (Catalog.Title t : l) if (!t.id.equals(playing) && list.size() < 40) list.add(t);
        shelf(App.t(R.string.player_more_watch), list.size(), 1, i -> new String[]{list.get(i).name, list.get(i).poster}, i -> {
            Catalog.Title t = list.get(i);
            startActivity(new Intent(this, TitleActivity.class).putExtra("id", t.id).putExtra("type", t.type).putExtra("name", t.name)
                .putExtra("year", t.year).putExtra("poster", t.poster).putExtra("tmdb", t.tmdb));
            finish();
        }, -1);
    }

    /**
     * A strip of cards along the bottom, over the picture (the video keeps playing): `n` cards, each {name, picture} from `item`;
     * `kind`: 0 = wide tiles with a logo, 1 = upright posters, 2 = wide pictures. The buttons move up and sit above it. Left/Right move, OK picks, Back or a tap outside closes. `current` is marked.
     */
    void shelf(String title, int n, int kind, java.util.function.IntFunction<String[]> item, java.util.function.IntConsumer pick, int current) {
        boolean posters = kind == 1;
        FrameLayout root = (FrameLayout) controls.getParent();
        if (panel != null) root.removeView(panel);
        // The title and the buttons (Previous, Next...) stay on screen: they move up and the strip takes the bottom edge
        showControls();
        rowFocus(false);
        controls.setVisibility(View.VISIBLE);
        tip.setVisibility(View.GONE);
        hideAt = Long.MAX_VALUE;
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setClickable(true);   // a tap on the strip is not a tap on the picture
        p.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x73000000, 0x99000000}));   // see-through: the picture shows behind the cards
        p.setPadding(0, dp(8), 0, dp(12));
        p.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> bottomBar.setTranslationY(t - b));   // the buttons ride on top of it
        // One shade from the buttons down: the bar's fade ends where the strip's begins (no darker band between them)
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x73000000}));
        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(Color.WHITE);
        head.setTextSize(15);
        head.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.setPadding(dp(24), 0, dp(24), dp(6));
        head.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        p.addView(head);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setPadding(dp(20), dp(6), dp(20), dp(4));
        int w = dp(posters ? 92 : 128), h = dp(posters ? 138 : 72);
        View[] start = {null};
        int[] built = {0};
        java.util.function.IntConsumer[] grow = new java.util.function.IntConsumer[1];
        // The cards are built a batch at a time: the next batch is added when the remote gets near the last one (a long list stays light)
        grow[0] = to -> {
            for (int i = built[0]; i < to; i++) {
                final int at = i;
                String[] it = item.apply(i);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                FrameLayout art = new FrameLayout(this);
                art.setBackground(Home.rounded(0xFF282431, dp(10)));
                art.setClipToOutline(true);
                ImageView img = new ImageView(this);
                img.setScaleType(kind == 0 ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                if (kind == 0) img.setPadding(dp(14), dp(10), dp(14), dp(10));
                art.addView(img, new FrameLayout.LayoutParams(-1, -1));
                if (it[1] != null && it[1].startsWith("http")) loadStill(img, it[1], w);
                card.addView(art, new LinearLayout.LayoutParams(w, h));
                TextView name = new TextView(this);
                name.setText(it[0]);
                name.setTextColor(i == current ? Home.ACCENT : Color.WHITE);
                name.setTextSize(12);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setPadding(dp(2), dp(5), dp(2), 0);
                name.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);   // readable over a bright picture
                card.addView(name, new LinearLayout.LayoutParams(w, -2));
                GradientDrawable ring = Home.rounded(0, dp(10));
                ring.setStroke(dp(i == current ? 2 : 3), i == current ? Home.ACCENT : Color.WHITE);
                if (i == current) art.setForeground(ring);
                card.setFocusable(true);
                card.setFocusableInTouchMode(App.tv);
                card.setDefaultFocusHighlightEnabled(false);
                GradientDrawable white = Home.rounded(0, dp(10));
                white.setStroke(dp(3), Color.WHITE);
                card.setOnFocusChangeListener((v, has) -> {
                    art.setForeground(has ? white : at == current ? ring : null);
                    if (has) hs.smoothScrollTo(Math.max(0, v.getLeft() - hs.getWidth() / 2 + w / 2), 0);
                    if (has && at >= built[0] - 8 && built[0] < n) grow[0].accept(Math.min(n, built[0] + 60));
                });
                card.setOnClickListener(v -> { closePanel(); pick.accept(at); });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.rightMargin = dp(10);
                row.addView(card, cp);
                if (start[0] == null || i == current) start[0] = card;
            }
            built[0] = to;
        };
        grow[0].accept(Math.min(n, Math.max(80, current + 40)));
        hs.addView(row);
        p.addView(hs);
        root.addView(p, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        panel = p;
        shelfOpen = true;
        if (start[0] != null) { View f = start[0]; f.post(() -> { hs.scrollTo(Math.max(0, f.getLeft() - hs.getWidth() / 2 + w / 2), 0); f.requestFocus(); }); }
    }

    int dp(float v) { return Home.dp(this, v); }

    /** A round, semi-transparent icon button (back, play, +/-10). */
    ImageView circle(int icon, int sizeDp, Runnable click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(Color.WHITE);
        int pad = dp(sizeDp / 6f);   // a big icon in a tight, faint disc
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x40000000);
        b.setBackground(bg);
        b.setFocusable(false);
        b.setOnClickListener(v -> click.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    /** -10 / +10 (the icon carries the number). */
    View skip(int icon, long ms) {
        ImageView b = circle(icon, 52, () -> seekBy(ms));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(52), dp(52));
        ll.setMargins(dp(20), 0, dp(20), 0);
        b.setLayoutParams(ll);
        return b;
    }

    void showCues(CueGroup group) {
        StringBuilder sb = new StringBuilder();
        for (Cue c : group.cues) {
            if (c.text == null) continue;
            if (sb.length() > 0) sb.append('\n');
            int at = sb.length();
            sb.append(c.text);
            // The file's own line breaks (made for narrow screens) become spaces and the width cap wraps instead; "- " lines are two speakers, kept apart
            for (int i = at; i < sb.length() - 1; i++)
                if (sb.charAt(i) == '\n' && sb.charAt(i + 1) != '-' && sb.charAt(i + 1) != '\u2013') sb.setCharAt(i, ' ');
        }
        subtitle.setText(sb);
        subtitle.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Subtitle size, background and distance from the bottom: 22 sp and 20 dp, changed by the Subtitles panel. */
    void placeSubtitle() {
        subtitle.setTextSize(22f * Subs.size() / 100);
        subtitle.setBackgroundColor((Subs.background() * 255 / 100) << 24);
        subtitle.setTextColor(Subs.color());
        ((FrameLayout.LayoutParams) subtitle.getLayoutParams()).bottomMargin = dp(Math.max(0, 20 + Subs.offset()));
        subtitle.requestLayout();
    }

    /** The subtitle panel (size, position, timing), with a sample line on screen when nothing is being said right now. */
    void subtitleStyle() {
        if (subtitle.getVisibility() != View.VISIBLE) { subtitle.setText(App.t(R.string.player_subs_sample)); subtitle.setVisibility(View.VISIBLE); }
        Subs.panel(this, true, this::placeSubtitle, () -> showCues(player.getCurrentCues()));
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setDefaultFocusHighlightEnabled(false);   // a remote key would grey out the whole picture
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            fit();
            if (r - l != or - ol) {
                subtitle.setMaxWidth((r - l) * 3 / 4);   // long lines wrap in two instead of running edge to edge
                debug.setMaxWidth((r - l) * 3 / 5);      // the panel never takes more than 60% of the width
            }
        });

        subtitle = new TextView(this);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(6, 0, 0, Color.BLACK);
        subtitle.setPadding(dp(10), dp(4), dp(10), dp(4));
        subtitle.setVisibility(View.GONE);
        root.addView(subtitle, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        placeSubtitle();

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        notice = new TextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(18);
        notice.setBackgroundColor(0xAA000000);
        notice.setPadding(dp(14), dp(6), dp(14), dp(6));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        np.topMargin = dp(80);
        root.addView(notice, np);

        // Swipe indicator (brightness on the left half, volume on the right half)
        gesture = new LinearLayout(this);
        gesture.setOrientation(LinearLayout.VERTICAL);
        gesture.setGravity(Gravity.CENTER_HORIZONTAL);
        gesture.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable gbg = new GradientDrawable();
        gbg.setCornerRadius(dp(18));
        gbg.setColor(0xB0000000);
        gesture.setBackground(gbg);
        gestureIcon = new ImageView(this);
        gestureIcon.setColorFilter(Color.WHITE);
        gesture.addView(gestureIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        // A vertical bar that fills from the bottom, like the finger's up and down
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Home.rounded(0x40FFFFFF, dp(4)));
        gestureFill = new View(this);
        gestureFill.setBackground(Home.rounded(Home.ACCENT, dp(4)));
        track.addView(gestureFill, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(8), dp(GESTURE_BAR_DP));
        gp.topMargin = dp(10);
        gesture.addView(track, gp);
        gestureText = new TextView(this);
        gestureText.setTextColor(Color.WHITE);
        gestureText.setTextSize(13);
        gestureText.setPadding(0, dp(6), 0, 0);
        gestureText.setSingleLine(true);
        gestureText.setGravity(Gravity.CENTER);
        gesture.addView(gestureText, new LinearLayout.LayoutParams(dp(48), -2));   // fixed: "100%" fits and the "%" never wraps away
        gesture.setVisibility(View.GONE);
        root.addView(gesture, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Everything that hides after a few seconds lives in one overlay
        FrameLayout overlay = new FrameLayout(this);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(16);
        top.setPadding(dp(4), dp(10), pad, dp(26));
        topBar = top;
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        ImageView back = circle(R.drawable.ic_p_back, 48, this::finish);
        top.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(4), 0, 0, 0);
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // A page's title carries the site name ("Game of Thrones | Movy"): show just the title
        String shown = getIntent().getStringExtra("title");
        if (getIntent().hasExtra("pageTitle")) {
            String clean = History.cleanTitle(String.valueOf(getIntent().getStringExtra("pageTitle")), String.valueOf(getIntent().getStringExtra("site")));
            if (!clean.isEmpty()) shown = clean;
            // The show's own name when the page came from the catalog (a page title may be just the site's name)
            WebActivity pg = WebActivity.active;
            if (pg != null && pg.show != null && !pg.show.name.isEmpty() && !pg.show.name.equals("null")) shown = pg.show.name;
        }
        title.setText(shown);
        titles.addView(title);
        epLine = new TextView(this);
        epLine.setTextColor(0xCCFFFFFF);
        epLine.setTextSize(13);
        epLine.setSingleLine();
        epLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        epLine.setPadding(0, dp(2), 0, 0);
        epLine.setVisibility(View.GONE);
        titles.addView(epLine);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        if (!App.tv) {   // a TV has More, Home
            ImageView house = circle(R.drawable.ic_p_home, 40, () -> Home.goHome(this));
            top.addView(house);
        }
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // -10  PLAY  +10
        LinearLayout cluster = new LinearLayout(this);
        cluster.setGravity(Gravity.CENTER);
        rew = skip(R.drawable.ic_p_replay, -10000);
        playPause = circle(R.drawable.ic_p_play, 68, this::toggle);
        playPause.setColorFilter(Color.WHITE);
        fwd = skip(R.drawable.ic_p_forward, 10000);
        cluster.addView(rew);
        cluster.addView(playPause);
        cluster.addView(fwd);
        overlay.addView(cluster, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(pad, dp(30), pad, dp(10));
        bottomBar = bar;
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setFocusable(false);
        seek.setPadding(dp(14), dp(14), dp(14), dp(14));   // a finger-sized strip to grab
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) pending = p;
            }

            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }

            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; commitSeek(); }
        });
        time = new TextView(this);
        time.setTextColor(Color.WHITE);
        time.setTextSize(14);
        time.setPadding(0, 0, dp(10), 0);
        row.addView(time);
        row.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        timeTotal = new TextView(this);
        timeTotal.setTextColor(Color.WHITE);
        timeTotal.setTextSize(14);
        timeTotal.setPadding(dp(10), 0, 0, 0);
        row.addView(timeTotal);
        bar.addView(row);
        // One row of icon buttons: Previous / Next episode, Quality, Audio, Sub / Dub, Episodes, Servers, Subtitles, More (speed,
        // boosts, zoom, from the start), Cast, Full screen. What each is set to shows under the title. Down puts the remote on them.
        chipRow = new LinearLayout(this);
        chipRow.setGravity(Gravity.CENTER);
        chipRow.setPadding(0, dp(4), 0, dp(4));   // room for the focus ring, top and bottom
        chipRow.setClipChildren(false);
        chipRow.setClipToPadding(false);
        bar.setClipChildren(false);
        chipPrev = chip(R.drawable.ic_p_prev, () -> { int[] n = episodeStep(-1); if (n != null) playEpisode(n[0], n[1]); else playChannel(channelStep(-1)); });
        chipNext = chip(R.drawable.ic_p_next, () -> { int[] n = episodeStep(1); if (n != null) playEpisode(n[0], n[1]); else playChannel(channelStep(1)); });
        chipQuality = chip(R.drawable.ic_p_quality, this::pickQuality);
        chipAudio = chip(R.drawable.ic_p_language, () -> pickTrack(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO, App.t(R.string.player_audio), false));
        chipDub = chip(R.drawable.ic_p_language, this::pickSubDub);
        chipEpisodes = chip(R.drawable.ic_p_episodes, () -> {
            if (shelfOpen) { closePanel(); return; }   // pressed again: the strip closes
            if (sibs.size() > 1) channelsShelf(); else if (seriesInfo != null) episodesPanel(); else moreShelf();
        });
        chipServers = chip(R.drawable.ic_p_servers, () -> pickServer());
        chipSubs = chip(R.drawable.ic_p_subs, () -> pickTrack(player.getCurrentTracks(), C.TRACK_TYPE_TEXT, App.t(R.string.player_subtitles), true));
        chipDownload = chip(R.drawable.ic_download, this::confirmDownload);
        chipMore = chip(R.drawable.ic_p_tune, this::more);
        chipCast = chip(R.drawable.ic_p_cast, () -> { if (casting != null) openRemote(); else pickTv(); });
        chipRotate = chip(R.drawable.ic_p_fullscreen, () -> setRequestedOrientation(landscape()
            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        bar.addView(chipRow, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        controls = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        tip = new TextView(this);
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(14);
        tip.setSingleLine();
        tip.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(dp(8));
        tbg.setColor(0xFF16131C);   // solid: it can sit over the LIVE mark
        tip.setBackground(tbg);
        tip.setVisibility(View.GONE);
        overlay.addView(tip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // Skip intro / Skip recap / Next episode: bottom right, over the picture, also while the controls are hidden
        skipBtn = new TextView(this);
        skipBtn.setTextColor(Color.WHITE);
        skipBtn.setTextSize(15);
        skipBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        skipBtn.setPadding(dp(18), dp(9), dp(18), dp(9));
        skipBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_p_next, 0, 0, 0);
        skipBtn.setCompoundDrawablePadding(dp(6));
        skipBtn.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        GradientDrawable sbg = new GradientDrawable(), sfocus = new GradientDrawable();   // the same faint dark backdrop as the play buttons, with a thin edge
        sbg.setCornerRadius(dp(22));
        sbg.setColor(0x73000000);
        sbg.setStroke(dp(1), 0x59FFFFFF);
        sfocus.setCornerRadius(dp(22));
        sfocus.setColor(0x73000000);
        sfocus.setStroke(dp(2), Color.WHITE);   // the remote is on it
        android.graphics.drawable.StateListDrawable sstates = new android.graphics.drawable.StateListDrawable();
        sstates.addState(new int[]{android.R.attr.state_focused}, sfocus);
        sstates.addState(new int[0], sbg);
        skipBtn.setBackground(sstates);
        skipBtn.setFocusable(true);
        skipBtn.setDefaultFocusHighlightEnabled(false);
        skipBtn.setVisibility(View.GONE);
        skipBtn.setOnClickListener(v -> pressSkip());
        FrameLayout.LayoutParams skp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        skp.setMargins(0, 0, dp(24), dp(150));
        root.addView(skipBtn, skp);

        // The debug overlay: monospace, a panel down the whole right edge, never takes a touch (the controls under it still work)
        debug = new TextView(this);
        debug.setTypeface(android.graphics.Typeface.MONOSPACE);
        debug.setTextSize(App.tv ? 13 : 10.5f);
        debug.setTextColor(0xFFE8E8E8);
        debug.setBackgroundColor(0x99000000);
        debug.setPadding(dp(8), dp(5), dp(8), dp(5));
        debug.setClickable(false);
        debug.setFocusable(false);
        debug.setVisibility(View.GONE);
        root.addView(debug, new FrameLayout.LayoutParams(-2, -1, Gravity.END));

        if (android.os.Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l = 0, t = 0, r = 0, bt = 0;
            if (android.os.Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                l = in.getDisplayCutout().getSafeInsetLeft();
                t = in.getDisplayCutout().getSafeInsetTop();
                r = in.getDisplayCutout().getSafeInsetRight();
                bt = in.getDisplayCutout().getSafeInsetBottom();
            }
            cutTop = t;
            cutRight = r;
            debug.setPadding(dp(8), dp(10) + t, dp(8) + r, dp(10) + bt);   // clear of a camera cutout and the bottom edge
            int pad2 = dp(16);
            topBar.setPadding(dp(4) + l, dp(10) + t, pad2 + r, dp(26));
            bottomBar.setPadding(pad2 + l, dp(30), pad2 + r, dp(10) + bt);
            return in;
        });
        root.setOnClickListener(v -> { if (panel != null) closePanel(); else if (controls.getVisibility() == View.VISIBLE) hideControls(); else showControls(); });
        root.setOnTouchListener(this::swipe);
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---- swipe: up/down on the left half = brightness, on the right half = volume ----

    float downX, downY, startLevel;
    int swipeKind;   // 0 undecided, 1 brightness, 2 volume, -1 not a vertical swipe

    float brightnessNow() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (android.provider.Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    float volumeNow() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private android.view.GestureDetector taps;

    int holding;          // 1 = 2x forward, -1 = 2x back, 0 = not held
    float heldSpeed;
    long backTo;

    final Runnable backStep = new Runnable() {
        @Override public void run() {
            if (holding != -1 || player == null) return;
            backTo = Math.max(0, backTo - 500);   // half a second back every quarter second: 2x
            player.seekTo(backTo);
            if (backTo > 0) App.main.postDelayed(this, 250);
        }
    };

    void holdStart(boolean forward) {
        holding = forward ? 1 : -1;
        hideControls();   // just the picture and the 2x label while held
        notice.setText(forward ? "2x  \u25B6\u25B6" : "\u25C0\u25C0  2x");
        notice.setVisibility(View.VISIBLE);
        App.main.removeCallbacks(hideNotice);
        if (forward) {
            heldSpeed = player.getPlaybackParameters().speed;
            player.setPlaybackSpeed(2f);
        } else {
            backTo = player.getCurrentPosition();
            App.main.post(backStep);
        }
    }

    void holdEnd() {
        if (holding == 1) player.setPlaybackSpeed(heldSpeed);
        App.main.removeCallbacks(backStep);
        holding = 0;
        notice.setVisibility(View.GONE);
    }

    boolean swipe(View v, MotionEvent ev) {
        if (taps == null) taps = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            // Hold: the right half plays at 2x, the left half goes back at 2x, until you let go
            @Override public void onLongPress(MotionEvent e) {
                if (swipeKind != 0 || player == null || player.isCurrentMediaItemLive()) return;
                MotionEvent c = MotionEvent.obtain(e);   // not a tap any more: the controls stay as they are
                c.setAction(MotionEvent.ACTION_CANCEL);
                v.onTouchEvent(c);
                c.recycle();
                holdStart(e.getX() > v.getWidth() / 2f);
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                float third = v.getWidth() / 3f;
                if (e.getX() < third) seekBy(-10000);
                else if (e.getX() > 2 * third) seekBy(10000);
                else toggle();
                return true;
            }
        });
        taps.onTouchEvent(ev);
        if (holding != 0) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) holdEnd();
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swipeKind = 0;
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (swipeKind < 0) return false;
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (swipeKind == 0) {
                    float slop = dp(14);
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) { swipeKind = -1; return false; }
                    if (Math.abs(dy) < slop) return false;
                    if (downX < dp(28) || downX > v.getWidth() - dp(28)) { swipeKind = -1; return false; }
                    swipeKind = downX < v.getWidth() / 2f ? 1 : 2;
                    startLevel = swipeKind == 1 ? brightnessNow() : volumeNow();
                    MotionEvent c = MotionEvent.obtain(ev);   // the view saw a press: end it so it does not count as a tap
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    v.onTouchEvent(c);
                    c.recycle();
                }
                float level = Math.max(swipeKind == 1 ? 0.02f : 0f, Math.min(1f, startLevel - dy / (v.getHeight() * 0.7f)));
                if (swipeKind == 1) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = level;
                    getWindow().setAttributes(lp);
                } else {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
                }
                gestureIcon.setImageResource(swipeKind == 1 ? R.drawable.ic_p_sun : R.drawable.ic_p_volume);
                gestureFill.getLayoutParams().height = Math.round(level * dp(GESTURE_BAR_DP));
                gestureFill.requestLayout();
                gestureText.setText(Math.round(level * 100) + "%");
                FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) gesture.getLayoutParams();
                gl.gravity = (swipeKind == 1 ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
                gl.setMargins(dp(48), 0, dp(48), 0);
                gesture.setLayoutParams(gl);
                gesture.setVisibility(View.VISIBLE);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = swipeKind > 0;
                swipeKind = 0;
                if (was) App.main.postDelayed(() -> gesture.setVisibility(View.GONE), 600);
                return was;
            }
            default:
                return swipeKind > 0;
        }
    }

    /** A round icon button. Its name shows on a long press (phone) and is read out by TalkBack. */
    LinearLayout chip(int icon, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER);
        ImageView img = new ImageView(this);
        img.setImageResource(icon);
        img.setColorFilter(Color.WHITE);
        t.addView(img, new LinearLayout.LayoutParams(dp(24), dp(24)));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = new GradientDrawable(), pressed = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        pressed.setShape(GradientDrawable.OVAL);
        focused.setColor(0x40FFFFFF);   // the remote's place: the app's white ring
        focused.setStroke(dp(2), Color.WHITE);
        pressed.setColor(0x33FFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        t.setBackground(bg);
        t.setClickable(true);
        t.setFocusable(false);
        t.setOnClickListener(v -> { showControls(); action.run(); });
        t.setOnFocusChangeListener((v, has) -> { if (has) showTip(v); else if (!inRow()) tip.setVisibility(View.GONE); });
        chips.add(t);
        return t;
    }

    /** A white rounded badge with the letters cut out of it, like the CC icon: "SUB", "DUB". */
    android.graphics.drawable.Drawable badge(String text) {
        int w = dp(30), h = dp(20);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawRoundRect(0, 0, w, h, dp(4), dp(4), p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        p.setTextSize(dp(11));
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setLetterSpacing(0.04f);
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, p);
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    /** Show or hide a button and give it its name (long press, TalkBack). What it is set to shows in the line under the title. */
    void show(LinearLayout t, boolean visible, String label) {
        t.setVisibility(visible ? View.VISIBLE : View.GONE);
        t.setContentDescription(label);
        t.setTooltipText(label);
        if (t == tipFor && tip.getVisibility() == View.VISIBLE) showTip(t);   // the state changed under the remote (subtitles off): the label above follows
    }

    static void show(TextView t, boolean visible, String text) {
        t.setVisibility(visible ? View.VISIBLE : View.GONE);
        t.setText(text);
    }

    /** "German", "English 5.1", "English · Commentary": the language first; a stream's own name only when it says more ("stream_4" doesn't). */
    static String labelOf(androidx.media3.common.Format f, int numbered, int n) {
        String lang = f.language != null && !f.language.equals("und") ? new Locale(f.language).getDisplayLanguage() : null;
        String label = f.label == null || f.label.matches("(?i)(stream|audio|track|subtitles?|text)[ _-]?\\d*") ? null : f.label;
        String name = lang == null ? (label != null ? label : App.t(numbered, n))
            : label == null || label.toLowerCase(Locale.ROOT).contains(lang.toLowerCase(Locale.ROOT)) ? (label != null ? label : lang) : lang + " \u00B7 " + label;
        return f.channelCount > 2 ? name + " " + (f.channelCount == 6 ? "5.1" : f.channelCount == 8 ? "7.1" : f.channelCount + "ch") : name;
    }

    /** "1080p" for 1080, "4K" for 2160 and up, "" when unknown. */
    static String heightLabel(int h) {
        return h <= 0 ? "" : h >= 2160 ? "4K" : h + "p";
    }

    /** "1080p60": the height and, when the frame rate is known, the rate rounded (23.976 is 24, 59.94 is 60). */
    static String qualityLabel(int h, float fps) {
        String base = heightLabel(h);
        return base.isEmpty() || fps <= 0 ? base : base + Math.round(fps);
    }

    // The stream's frame rate, read off the frames' own timestamps: the shortest gap between two frames in a row. A slow decoder that
    // drops frames makes the gaps longer, never shorter, so the shortest gap is still the true frame duration.
    volatile float measuredFps;

    // The debug overlay (More, Debug overlay): PlayerStats keeps the numbers, the text refreshes once a second while it is on
    final PlayerStats stats = new PlayerStats();
    TextView debug;
    boolean debugOn;
    final long createdAt = SystemClock.elapsedRealtime();
    long readyAt;   // the first time the video was ready to play, for "opened in"
    final Runnable debugTick = new Runnable() {
        @Override public void run() {
            if (!debugOn || player == null || isFinishing() || offScreen) return;
            debug.setText(stats.text(player, PlayerActivity.this));
            App.main.postDelayed(this, 1000);
        }
    };

    void setDebug(boolean on) {
        debugOn = on;
        App.prefs.edit().putBoolean("playerDebug", on).apply();
        debug.setVisibility(on && !inPip ? View.VISIBLE : View.GONE);
        App.main.removeCallbacks(debugTick);
        if (on) debugTick.run();
    }
    private long fpsLastUs = -1, fpsMinGapUs = Long.MAX_VALUE;
    private int fpsFrames;
    static final float[] RATES = {23.976f, 24, 25, 29.97f, 30, 48, 50, 59.94f, 60, 100, 119.88f, 120};
    final androidx.media3.exoplayer.video.VideoFrameMetadataListener fpsMeter = (ptsUs, releaseNs, format, media) -> {
        long gap = ptsUs - fpsLastUs;
        fpsLastUs = ptsUs;
        if (gap > 4000 && gap < 200000) fpsMinGapUs = Math.min(fpsMinGapUs, gap);   // 5 to 250 frames a second; seeks and splices give odd gaps
        if (++fpsFrames < 40 || fpsMinGapUs == Long.MAX_VALUE) return;
        float fps = 1_000_000f / fpsMinGapUs, best = fps;
        for (float r : RATES) if (Math.abs(r - fps) / r < 0.03f) best = r;   // snap to a standard rate
        measuredFps = Math.max(measuredFps, best);   // a decoder that falls behind only ever reads low: keep the highest reading for this quality
        fpsMinGapUs = Long.MAX_VALUE;
        fpsFrames = 0;
    };

    // What each button shows right now: the actual quality, audio, subtitles, speed, zoom, server and episode
    void refreshChips() {
        if (player == null) return;
        Tracks t = player.getCurrentTracks();
        WebActivity page = WebActivity.active;
        String cur = "";
        if (page != null) for (String[] v : page.servers.values()) if (!v[1].isEmpty()) cur = v[0];
        show(chipServers, page != null && !page.servers.isEmpty(), cur.isEmpty() ? App.t(R.string.player_servers) : App.t(R.string.player_servers_now, cur));
        int video = 0, audio = 0, text = 0;
        String audioNow = "", textNow = null;
        for (Tracks.Group g : t.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.getType() == C.TRACK_TYPE_VIDEO) video++;
                else if (g.getType() == C.TRACK_TYPE_AUDIO) { audio++; if (g.isTrackSelected(i)) audioNow = labelOf(g.getTrackFormat(i), R.string.player_audio_n, audio); }
                else if (g.getType() == C.TRACK_TYPE_TEXT) { text++; if (g.isTrackSelected(i)) textNow = labelOf(g.getTrackFormat(i), R.string.player_subs_n, text); }
            }
        }
        boolean manual = false;
        for (TrackSelectionOverride o : player.getTrackSelectionParameters().overrides.values()) if (o.getType() == C.TRACK_TYPE_VIDEO) manual = true;
        androidx.media3.common.Format vf = player.getVideoFormat();
        float rate = vf != null && vf.frameRate > 0 ? vf.frameRate : measuredFps;
        String h = vf == null ? "" : qualityLabel(vf.height, rate);
        String pq = "";
        if (page != null) for (String[] v : page.qualities.values()) if (!v[1].isEmpty()) pq = v[0];
        if (video <= 1 && page != null && !page.qualities.isEmpty()) {
            // The page's own quality list
            show(chipQuality, true, App.t(R.string.player_quality_now, pq.isEmpty() ? (h.isEmpty() ? App.t(R.string.player_auto) : h) : pq));
        } else show(chipQuality, video > 1, App.t(R.string.player_quality_now, manual ? h : h.isEmpty() ? App.t(R.string.player_auto) : App.t(R.string.player_auto_quality, h)));
        show(chipAudio, audio > 1, App.t(R.string.player_audio_now, audioNow));
        boolean subsOn = textNow != null;
        String off = App.t(R.string.player_off);
        show(chipSubs, text > 0 || canSearchSubs(), App.t(R.string.player_subs_now, subsOn ? textNow : off));
        float sp = player.getPlaybackParameters().speed;
        show(chipDownload, canDownload(), App.t(R.string.player_download));
        show(chipMore, true, App.t(R.string.player_more));
        int[] next = episodeStep(1), prev = episodeStep(-1);
        Iptv.Channel up = channelStep(1), dn = channelStep(-1);   // a live channel: the same three buttons move through its category
        show(chipPrev, prev != null || dn != null, prev != null ? App.t(R.string.player_prev_episode, prev[0], prev[1]) : dn != null ? dn.name : "");
        boolean movie = seriesInfo == null && !getIntent().hasExtra("mime");   // a movie: the same button offers other movies
        show(chipEpisodes, seriesInfo != null || up != null || movie, App.t(up != null ? R.string.player_channels : movie ? R.string.player_more_watch : R.string.player_episodes));
        show(chipNext, next != null || up != null, next != null ? App.t(R.string.player_next_episode, next[0], next[1]) : up != null ? up.name : "");
        String sd = subDubNow();
        show(chipDub, subDub().size() >= 2, App.t(R.string.player_sub_dub, sd));
        if (!sd.equals(chipDub.getTag())) {   // its icon says which: SUB or DUB (or the first letters of another, like HIN)
            chipDub.setTag(sd);
            ImageView img = (ImageView) chipDub.getChildAt(0);
            img.setImageDrawable(badge(sd.isEmpty() ? "SUB" : sd.substring(0, Math.min(3, sd.length())).toUpperCase(Locale.ROOT)));
            img.getLayoutParams().width = dp(30);
            img.requestLayout();
        }
        show(chipCast, !App.tv && (casting != null || Cast.any()), casting != null ? App.t(R.string.cast_playing_on, casting) : App.t(R.string.cast_to_tv));
        // Under the title: the episode, then what is picked (quality, audio, Sub / Dub, subtitles, server)
        List<String> info = new ArrayList<>();
        if (!epText.isEmpty()) info.add(epText);
        String q = !pq.isEmpty() ? pq : h;
        if (!q.isEmpty()) info.add(q);
        if (audio > 1 && !audioNow.isEmpty()) info.add(audioNow);   // only when there is a choice ("Audio 1" says nothing)
        if (subDub().size() >= 2) info.add(sd);
        if (subsOn) info.add("CC " + textNow);
        if (!cur.isEmpty()) info.add(cur);
        if (sp != 1f) info.add(speedLabel(sp));
        epLine.setText(String.join("  \u00B7  ", info));
        epLine.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
        ((ImageView) chipRotate.getChildAt(0)).setImageResource(landscape() ? R.drawable.ic_p_fullscreen_exit : R.drawable.ic_p_fullscreen);
        show(chipRotate, !App.tv, App.t(landscape() ? R.string.player_portrait : R.string.player_full_screen));
        fitChips();
    }

    // One compact centred row of icon buttons; on a narrow screen they shrink to fit, never wrapping.
    // Rebuilt only when what is shown changes, so the remote keeps its place.
    void fitChips() {
        StringBuilder key = new StringBuilder(landscape() ? "L" : "P");
        for (LinearLayout c : chips) key.append(c.getVisibility() == View.VISIBLE ? '1' : '0');
        if (key.toString().equals(chipLayoutKey)) return;
        chipLayoutKey = key.toString();
        View had = getCurrentFocus();
        chipRow.removeAllViews();
        int n = 0;
        for (LinearLayout c : chips) if (c.getVisibility() == View.VISIBLE) n++;
        int max = getResources().getDisplayMetrics().widthPixels - 2 * dp(24);
        int size = n == 0 ? dp(48) : Math.min(dp(48), max / n);
        // Left: what moves between videos (Previous, Next, Episodes or Channels). Right: how this one plays (quality, audio, subtitles, More...)
        List<LinearLayout> order = new ArrayList<>(java.util.Arrays.asList(chipPrev, chipNext, chipEpisodes));
        for (LinearLayout c : chips) if (!order.contains(c)) order.add(c);
        for (int i = 0; i < order.size(); i++) {
            LinearLayout c = order.get(i);
            if (c.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) c.getParent()).removeView(c);
            if (i == 3) chipRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));   // the gap between the two groups
            if (c.getVisibility() == View.VISIBLE) chipRow.addView(c, new LinearLayout.LayoutParams(size, size));
        }
        if (had != null && chips.contains(had)) had.requestFocus();
    }

    /** The focused button's name in a small label just above it (a remote has no long press for the tooltip). */
    void showTip(View v) {
        CharSequence name = v.getContentDescription();
        if (name == null || name.length() == 0) { tip.setVisibility(View.GONE); return; }
        tipFor = v;
        tip.setText(name);
        tip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] at = new int[2], base = new int[2];
        v.getLocationInWindow(at);
        controls.getLocationInWindow(base);
        int w = tip.getMeasuredWidth(), x = at[0] - base[0] + v.getWidth() / 2 - w / 2;
        tip.setTranslationX(Math.max(dp(8), Math.min(controls.getWidth() - w - dp(8), x)));
        tip.setTranslationY(at[1] - base[1] - tip.getMeasuredHeight() - dp(6));
        tip.setVisibility(View.VISIBLE);
    }

    // Put the remote on the action row (on) or give it back to play/seek (off)
    void rowFocus(boolean on) {
        LinearLayout first = null;
        for (LinearLayout c : chips) {
            c.setFocusableInTouchMode(on);   // the emulator/phone may be in touch mode; focus must still work
            c.setFocusable(on);
            if (on && first == null && c.getVisibility() == View.VISIBLE) first = c;
        }
        if (on) { showControls(); if (first != null) first.requestFocus(); }
    }

    boolean inRow() {
        View f = getCurrentFocus();
        return controls.getVisibility() == View.VISIBLE && f != null && chips.contains(f);
    }

    // The stream's own requests go through WARP like the page's did (and straight out for LAN or a site on the bypass list)
    void routeThroughWarp() {
        previousProxy = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return Collections.singletonList(Warp.proxyFor(uri.getHost()));
            }

            @Override public void connectFailed(URI uri, SocketAddress a, IOException e) { }
        });
    }

    void startPlayer(String url) {
        Log.i(TAG, "stream " + url + " headers " + getIntent().getStringExtra("headers"));
        Map<String, String> headers = new HashMap<>();
        String ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
        try {
            JSONObject h = new JSONObject(getIntent().getStringExtra("headers"));
            for (java.util.Iterator<String> it = h.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.equalsIgnoreCase("User-Agent")) ua = h.getString(k); else headers.put(k, h.getString(k));
            }
        } catch (Exception ignored) { }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(ua).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(30000).setDefaultRequestProperties(headers);
        // The WebView's cookie for each request's own host, never one site's session sent to another
        DataSource.Factory web = new ResolvingDataSource.Factory(http, spec -> {
            String cookie = CookieManager.getInstance().getCookie(spec.uri.toString());
            return cookie == null || cookie.isEmpty() ? spec : spec.withAdditionalHeaders(Collections.singletonMap("Cookie", cookie));
        });
        DataSource.Factory data = new androidx.media3.datasource.DefaultDataSource.Factory(this, web);   // downloaded subtitles are local files

        // IPTV passes its own media type ("" = let ExoPlayer read it off the URL); pages hand over HLS, DASH or a live FLV
        String mime = getIntent().hasExtra("mime") ? getIntent().getStringExtra("mime") : getIntent().getBooleanExtra("dash", false) ? MimeTypes.APPLICATION_MPD
            : Sniffer.path(url).endsWith(".flv") ? MimeTypes.VIDEO_FLV : MimeTypes.APPLICATION_M3U8;
        MediaItem.Builder item = mediaItem = new MediaItem.Builder().setUri(url);
        if (!mime.isEmpty()) item.setMimeType(mime);
        List<MediaItem.SubtitleConfiguration> subs = subList = new ArrayList<>();
        List<String> found = getIntent().getStringArrayListExtra("subs");
        for (int i = 0; found != null && i < found.size(); i++) {
            String s = found.get(i);
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(s))
                .setMimeType(Sniffer.path(s).endsWith(".srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT)
                .setLabel(App.t(R.string.player_subs_file, i + 1, fileName(s))).build());
        }
        item.setSubtitleConfigurations(subs);

        // The subtitle renderer sees the clock moved by the Timing setting: an earlier clock shows subtitles later, a later one earlier
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override protected void buildTextRenderers(android.content.Context c, androidx.media3.exoplayer.text.TextOutput out, android.os.Looper l, int mode,
                                                        ArrayList<androidx.media3.exoplayer.Renderer> list) {
                int from = list.size();
                super.buildTextRenderers(c, out, l, mode, list);
                for (int i = from; i < list.size(); i++) list.set(i, new androidx.media3.exoplayer.ForwardingRenderer(list.get(i)) {
                    @Override public void render(long pos, long elapsed) throws androidx.media3.exoplayer.ExoPlaybackException { super.render(pos - Subs.delayMs * 1000, elapsed); }

                    @Override public void resetPosition(long pos, boolean joining) throws androidx.media3.exoplayer.ExoPlaybackException { super.resetPosition(pos - Subs.delayMs * 1000, joining); }
                });
            }

            // The sound passes through the speech meter (untouched), for subtitle auto-sync
            @Override protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(android.content.Context c, boolean floatOut, boolean params) {
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(c).setEnableFloatOutput(floatOut).setEnableAudioTrackPlaybackParams(params)
                    .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{meter}).build();
            }
        };
        player = new ExoPlayer.Builder(this, renderers.setEnableDecoderFallback(true).setMediaCodecSelector(PlayerActivity::decoders))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).setHandleAudioBecomingNoisy(true).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        player.setVideoFrameMetadataListener(fpsMeter);
        // Its own audio session from the start, so the boosts attach to this video only (session 0 would be all of the phone's sound)
        player.setAudioSessionId(((AudioManager) getSystemService(AUDIO_SERVICE)).generateAudioSessionId());
        applyBoost(false);
        // Side-loaded subtitles stay off until picked on the Subtitles button. Audio: the language last picked on the Audio
        // button (a dub the stream offers), else the device's, else whatever the stream marks as default (often English)
        String audio = App.prefs.getString("audioLang", "");
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setPreferredAudioLanguages(audio.isEmpty() ? new String[]{Locale.getDefault().getLanguage()} : new String[]{audio, Locale.getDefault().getLanguage()}).build());
        player.addAnalyticsListener(stats);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY && readyAt == 0) readyAt = SystemClock.elapsedRealtime();
                spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY && getIntent().hasExtra("mime")) Iptv.played(channel());
                if (s == Player.STATE_READY && !scrobbled && player.getPlayWhenReady()) { scrobbled = true; scrobble("start"); }
                if (s == Player.STATE_READY && !subsTried) { subsTried = true; autoSubs(); loadSkips(); }
                if (s == Player.STATE_ENDED) {
                    resume.remove(base);
                    scrobble("stop");
                    int[] n = episodeStep(1);
                    if (n != null && !inPip) playEpisode(n[0], n[1]); else if (!inPip) finish();   // a series goes on with the next episode
                }
                update();
            }

            // Play and pause by the user (not buffering) go to Simkl; leaving the player sends the stop
            @Override public void onPlayWhenReadyChanged(boolean play, int reason) {
                if (scrobbled && !isFinishing()) scrobble(play ? "start" : "pause");
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                update();
                updatePip();
                if (playing) hideAt = System.currentTimeMillis() + 3500; else showControls();
            }

            @Override public void onVideoSizeChanged(VideoSize v) {
                measuredFps = 0;
                fpsMinGapUs = Long.MAX_VALUE;
                fpsFrames = 0;
                if (v.width > 0 && v.height > 0) videoRatio = v.width * v.pixelWidthHeightRatio / v.height;
                fit();
                updatePip();
                refreshChips();
            }

            @Override public void onTracksChanged(Tracks t) { refreshChips(); }

            @Override public void onPlaybackParametersChanged(androidx.media3.common.PlaybackParameters p) { refreshChips(); }

            @Override public void onCues(CueGroup group) { showCues(group); }

            @Override public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "playback error", e);
                // which request failed and how: the answer's status, its address and what was sent with it
                for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                    if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException r = (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c;
                        Log.w(TAG, "FAILED " + r.responseCode + " " + r.dataSpec.uri + " sent " + r.dataSpec.httpRequestHeaders.keySet() + " + " + getIntent().getStringExtra("headers")
                            + " answer " + r.headerFields.get("content-type") + " server " + r.headerFields.get("server"));
                    } else if (c instanceof androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                        Log.w(TAG, "FAILED " + c + " " + ((androidx.media3.datasource.HttpDataSource.HttpDataSourceException) c).dataSpec.uri);
                    } else if (c instanceof androidx.media3.common.ParserException) Log.w(TAG, "FAILED parse " + c.getMessage() + " stream " + getIntent().getStringExtra("url"));
                }
                if (getIntent().hasExtra("mime")) { Iptv.mark(channel(), false); Iptv.saveHealth(); }
                Toast.makeText(PlayerActivity.this, App.t(getIntent().hasExtra("mime") ? R.string.player_channel_offline : R.string.player_cant_play, e.getErrorCodeName()),
                    Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        setDebug(App.prefs.getBoolean("playerDebug", false));   // the overlay stays on or off across videos
        // Where to start: a server switch from the Servers button, else where this title was last left on any site or server
        // (Continue watching), else where this very stream was left
        Long at = null;
        if (!getIntent().hasExtra("mime")) {
            long saved = History.position(getIntent().getStringExtra("site"), getIntent().getStringExtra("pageTitle"), getIntent().getStringExtra("pageUrl"));
            if (saved > 0) at = saved;
        }
        if (at == null) at = resume.get(base);
        if (carryMs > 0 && SystemClock.elapsedRealtime() - carryAt < 45000) at = carryMs;
        carryMs = 0;
        if (getIntent().hasExtra("castAt")) at = getIntent().getLongExtra("castAt", 0);   // sent from a phone: carry on where it was
        startedAt = at == null ? 0 : at;
        if (at != null && at > 10000) say(App.t(R.string.player_resumed_at, clock(at)));
        if (at != null && at > 10000) player.setMediaItem(item.build(), at); else player.setMediaItem(item.build());
        player.prepare();
        player.play();
        episodeLine();
        loadSeries();
        showControls();
        App.main.post(tick);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pipToggle, new android.content.IntentFilter(PIP_TOGGLE), 4 /* RECEIVER_NOT_EXPORTED */);
        else registerReceiver(pipToggle, new android.content.IntentFilter(PIP_TOGGLE));
    }

    /**
     * The emulator's software H.264 decoder (c2.goldfish) does not report the new picture size when the quality changes mid-stream, so
     * the picture comes out zoomed. There the decoder is restarted on a size change instead; real devices keep seamless switching.
     */
    static java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> decoders(String mime, boolean secure, boolean tunneling)
        throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
        java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> in = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
        java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> out = new ArrayList<>();
        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : in) {
            out.add(i.name.startsWith("c2.goldfish") && i.adaptive
                ? androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(i.name, i.mimeType, i.codecMimeType, i.capabilities, i.hardwareAccelerated, i.softwareOnly, i.vendor, true, i.secure)
                : i);
        }
        return out;
    }

    boolean landscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    // Phone turned (or the Full screen button): the button row gets the new width, the picture refits through the layout listener
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (chipRow == null) return;
        int pad = dp(24);
        chipRow.getLayoutParams().width = getResources().getDisplayMetrics().widthPixels - 2 * pad;
        chipLayoutKey = "";
        refreshChips();
    }

    // The surface is sized to the picture: Fit letterboxes (nothing stretched), Fill crops to cover the screen, Stretch fills it,
    // 16:9 and 4:3 force that shape
    void fit() {
        View root = (View) surface.getParent();
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0 || (videoRatio <= 0 && zoom != 2)) return;
        float r = zoom == 3 ? 16f / 9 : zoom == 4 ? 4f / 3 : videoRatio;
        int vw = w, vh = h;
        if (zoom == 1) {
            vh = Math.round(w / r);
            if (vh < h) { vh = h; vw = Math.round(h * r); }
        } else if (zoom != 2) {
            vh = Math.round(w / r);
            if (vh > h) { vh = h; vw = Math.round(h * r); }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width == vw && lp.height == vh) return;
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    void cycleZoom() {
        zoom = (zoom + 1) % ZOOMS.length;
        fit();
        say(App.t(R.string.player_zoom_say, App.t(ZOOMS[zoom])));
        refreshChips();
    }

    static String fileName(String url) {
        String p = Sniffer.path(url);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    void toggle() {
        if (player.isPlaying()) player.pause(); else player.play();
        showControls();
    }

    // Presses only move a target; ExoPlayer seeks once, 400 ms after the last one (a seek per press re-buffers each time)
    void seekBy(long ms) {
        long dur = player.getDuration();
        long to = (pending >= 0 ? pending : player.getCurrentPosition()) + ms;
        pending = Math.max(0, dur == C.TIME_UNSET ? to : Math.min(to, dur - 1000));
        App.main.removeCallbacks(commit);
        App.main.postDelayed(commit, 400);
        say(clock(pending));
        showControls();
    }

    final Runnable commit = this::commitSeek;

    void commitSeek() {
        App.main.removeCallbacks(commit);
        if (pending >= 0 && player != null) player.seekTo(pending);
        pending = -1;
        update();
    }

    void say(String s) {
        notice.setText(s);
        notice.setVisibility(View.VISIBLE);
        App.main.removeCallbacks(hideNotice);
        App.main.postDelayed(hideNotice, 1200);
    }

    final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    void showControls() {
        if (inPip) return;
        controls.setVisibility(View.VISIBLE);
        hideAt = System.currentTimeMillis() + (inRow() ? 8000 : 3500);
        refreshChips();
        update();
    }

    void hideControls() {
        tip.setVisibility(View.GONE);
        rowFocus(false);
        controls.setVisibility(View.GONE);
    }

    void update() {
        if (player == null) return;
        boolean playing = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
        playPause.setImageResource(playing ? R.drawable.ic_p_pause : R.drawable.ic_p_play);
        long dur = player.getDuration(), pos = pending >= 0 ? pending : player.getCurrentPosition();
        boolean live = dur == C.TIME_UNSET || player.isCurrentMediaItemLive();
        seek.setVisibility(live ? View.INVISIBLE : View.VISIBLE);
        rew.setVisibility(live ? View.GONE : View.VISIBLE);
        fwd.setVisibility(live ? View.GONE : View.VISIBLE);
        if (!live && !dragging) {
            seek.setMax((int) dur);
            seek.setProgress((int) pos);
            seek.setSecondaryProgress((int) player.getBufferedPosition());
        }
        if (live) {   // a red dot before the word
            android.text.SpannableString on = new android.text.SpannableString("\u25CF  " + App.t(R.string.player_live));
            on.setSpan(new android.text.style.ForegroundColorSpan(Home.ACCENT), 0, 1, 0);
            time.setText(on);
        } else time.setText(clock(pos));
        timeTotal.setText(live ? "" : clock(dur));
        if (playing && controls.getVisibility() == View.VISIBLE && !dragging && hasWindowFocus() && System.currentTimeMillis() > hideAt) hideControls();
    }

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (Home.holdBack(this, e)) return true;
        if (player == null) return super.dispatchKeyEvent(e);
        if (panel != null) {   // the Episodes list: normal focus keys, Back closes it
            if (shelfOpen && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {   // Up from the strip at the bottom closes it, the remote goes back to its button
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    closePanel();
                    rowFocus(true);
                    if (chipEpisodes.getVisibility() == View.VISIBLE) chipEpisodes.requestFocus();
                }
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) closePanel(); return true; }
            return super.dispatchKeyEvent(e);
        }
        int k = e.getKeyCode(), r = e.getRepeatCount();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // The remote's Channel + / - (and Next / Previous, Page up / down): the next channel of this one's category
        int step = k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_PAGE_UP ? 1
            : k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || k == KeyEvent.KEYCODE_PAGE_DOWN ? -1 : 0;
        if (step != 0 && channelStep(step) != null) {
            if (down && r == 0) playChannel(channelStep(step));
            return true;
        }
        if (inRow()) {
            // On the action row the normal focus keys work: Left/Right move, OK presses (it clicks on key up, so pass both)
            if (down) showControls();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) return super.dispatchKeyEvent(e);
            // Up hands the remote back to play/seek
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) {
                    View at = getCurrentFocus(), n = at == null ? null : at.focusSearch(k == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                    if (n != null && chips.contains(n)) n.requestFocus();
                    else if (k == KeyEvent.KEYCODE_DPAD_UP) rowFocus(false);
                    else if (chipEpisodes.getVisibility() == View.VISIBLE && r == 0) chipEpisodes.performClick();   // Down again: the strip of channels / more to watch
                }
                return true;
            }
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (r == 0) { if (skipBtn.getVisibility() == View.VISIBLE) pressSkip(); else toggle(); }   // the row has its own keys (inRow above)
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE:
                if (r == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: player.play(); showControls(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                // Holding the key speeds up: 10 s steps, then 30 s, then 60 s
                if (r == 0 || r % 3 == 0) {
                    boolean back = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND;
                    seekBy((back ? -1 : 1) * (r < 12 ? 10000L : r < 40 ? 30000L : 60000L));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU:
                if (r == 0) rowFocus(true);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    static String speedLabel(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    // The page's own server list, read from the page when the stream started
    void pickServer() {
        WebActivity page = WebActivity.active;
        if (page == null) { pickFromPage(null, App.t(R.string.player_servers), App.t(R.string.player_no_servers)); return; }
        pickFromPage(page.servers, App.t(R.string.player_servers), App.t(R.string.player_no_servers));
    }

    // Quality: the stream's own choices if it has several, else the page's quality list (a page that serves one playlist per quality)
    void pickQuality() {
        WebActivity page = WebActivity.active;
        if (page != null && !page.qualities.isEmpty() && countVideo(player.getCurrentTracks()) <= 1) pickFromPage(page.qualities, App.t(R.string.player_quality), App.t(R.string.player_no_quality));
        else pickVideo(player.getCurrentTracks());
    }

    void pickFromPage(Map<String, String[]> list, String title, String none) {
        WebActivity page = WebActivity.active;
        if (page == null || list == null || list.isEmpty()) { say(none); return; }
        List<String> keys = new ArrayList<>(list.keySet()), labels = new ArrayList<>();
        for (String k : keys) labels.add(list.get(k)[0] + (list.get(k)[1].isEmpty() ? "" : "  \u2713"));
        sheet(title, labels.toArray(new String[0]), (d, i) -> {
            carryMs = player.getCurrentPosition();
            carryAt = SystemClock.elapsedRealtime();
            page.switchServer(keys.get(i));
            finish();
        });
    }

    static int countVideo(Tracks t) {
        int n = 0;
        for (Tracks.Group g : t.getGroups()) if (g.getType() == C.TRACK_TYPE_VIDEO) for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) n++;
        return n;
    }

    void pickSpeed() {
        String[] labels = new String[SPEEDS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = speedLabel(SPEEDS[i]) + (SPEEDS[i] == player.getPlaybackParameters().speed ? "  \u2713" : "");
        sheet(App.t(R.string.player_speed), labels, (d, i) -> player.setPlaybackSpeed(SPEEDS[i]));
    }

    // Quality: the best track of each height, plus Auto
    void pickVideo(Tracks tracks) {
        // key = height * 1000 + frame rate, so 1080p30 and 1080p60 are two entries; the best bitrate of each is kept
        TreeMap<Integer, TrackSelectionOverride> byKey = new TreeMap<>(Collections.reverseOrder());
        TreeMap<Integer, Integer> bitrate = new TreeMap<>();
        TreeMap<Integer, String> names = new TreeMap<>();
        androidx.media3.common.Format playing = player.getVideoFormat();   // an adaptive stream marks every quality as selected, so the playing one is found by its size and bitrate
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int t = 0; t < g.length; t++) {
                androidx.media3.common.Format f = g.getTrackFormat(t);
                int h = f.height, br = f.bitrate, key = h * 1000 + (f.frameRate > 0 ? Math.round(f.frameRate) : 0);
                if (h <= 0 || !g.isTrackSupported(t) || (bitrate.containsKey(key) && bitrate.get(key) >= br)) continue;
                bitrate.put(key, br);
                byKey.put(key, new TrackSelectionOverride(g.getMediaTrackGroup(), t));
                // The declared rate, else (only for the quality playing right now) the measured one; never a guess for the others
                names.put(key, qualityLabel(h, f.frameRate > 0 ? f.frameRate : playing != null && f.height == playing.height && f.bitrate == playing.bitrate ? measuredFps : 0));
            }
        }
        List<String> labels = new ArrayList<>();
        List<TrackSelectionOverride> picks = new ArrayList<>();
        labels.add(App.t(R.string.player_auto));
        picks.add(null);
        for (Map.Entry<Integer, TrackSelectionOverride> en : byKey.entrySet()) {
            labels.add(names.get(en.getKey()));
            picks.add(en.getValue());
        }
        sheet(App.t(R.string.player_quality), labels.toArray(new String[0]), (d, i) -> {
            androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            if (picks.get(i) != null) b.addOverride(picks.get(i));
            player.setTrackSelectionParameters(b.build());
            say(labels.get(i));
        });
    }

    void pickTrack(Tracks tracks, int type, String name, boolean canOff) {
        List<String> labels = new ArrayList<>(), plain = new ArrayList<>(), langs = new ArrayList<>();   // plain, langs: the same rows without the check mark, for remembering the pick
        List<Integer> nums = new ArrayList<>();
        List<TrackSelectionOverride> picks = new ArrayList<>();
        if (canOff) { labels.add(App.t(R.string.player_off)); picks.add(null); plain.add(""); langs.add(""); nums.add(0); }
        int n = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != type) continue;
            TrackGroup tg = g.getMediaTrackGroup();
            for (int t = 0; t < g.length; t++) {
                if (!g.isTrackSupported(t)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(t);
                String label = labelOf(f, type == C.TRACK_TYPE_TEXT ? R.string.player_subs_n : R.string.player_audio_n, ++n);
                labels.add(label + (g.isTrackSelected(t) ? "  ✓" : ""));
                picks.add(new TrackSelectionOverride(tg, t));
                plain.add(label);
                langs.add(f.language == null ? "" : f.language);
                nums.add(n);
            }
        }
        boolean search = type == C.TRACK_TYPE_TEXT && canSearchSubs();
        if (!search && (labels.isEmpty() || (canOff && labels.size() == 1))) { say(App.t(type == C.TRACK_TYPE_TEXT ? R.string.player_no_subs : R.string.player_no_audio)); return; }
        int searchAt = search ? labels.size() : -1;
        if (search) labels.add(App.t(R.string.player_search_subs));
        int positionAt = type == C.TRACK_TYPE_TEXT ? labels.size() : -1;
        if (positionAt >= 0) labels.add(App.t(R.string.home_tab_settings));   // size, position, color, background, timing
        sheet(name, labels.toArray(new String[0]), (d, i) -> {
            if (i == searchAt) { searchSubs(); return; }
            if (i == positionAt) { subtitleStyle(); return; }
            // Off keeps a file or track picked before from coming back; a track of the stream replaces a file; either way the next resume does the same
            if (type == C.TRACK_TYPE_TEXT) for (String k : subKeys(false)) {
                Subs.remember(k, null);
                Subs.rememberTrack(k, picks.get(i) == null ? null : langs.get(i), plain.get(i), nums.get(i));
            }
            androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if (picks.get(i) == null) b.setTrackTypeDisabled(type, true); else b.setTrackTypeDisabled(type, false).addOverride(picks.get(i));
            player.setTrackSelectionParameters(b.build());
            // A language picked for the audio becomes the one every next video starts in, when it has it
            if (type == C.TRACK_TYPE_AUDIO && picks.get(i) != null) {
                String lang = picks.get(i).mediaTrackGroup.getFormat(picks.get(i).trackIndices.get(0)).language;
                if (lang != null && !lang.isEmpty() && !lang.equals("und")) App.prefs.edit().putString("audioLang", lang).apply();
            }
        });
    }

    // ---- seasons and episodes ----

    /** {season, episode} of what is playing: what the page's URL or title say, else what the catalog screen passed; null for a movie. */
    int[] pageEpisode() {
        Intent in = getIntent();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("S(\\d+) E(\\d+)|E(\\d+)")
            .matcher(History.episode(String.valueOf(in.getStringExtra("pageUrl")), String.valueOf(in.getStringExtra("pageTitle"))));
        if (m.find()) return new int[]{m.group(1) != null ? Integer.parseInt(m.group(1)) : 1, Integer.parseInt(m.group(1) != null ? m.group(2) : m.group(3))};
        int s = in.getIntExtra("season", 0), e = in.getIntExtra("episode", 0);
        return s > 0 || e > 0 ? new int[]{s, e} : null;
    }

    String epText = "";   // "Season 1 · Episode 3", first on the line under the title

    void episodeLine() {
        int[] pe = pageEpisode();
        epText = pe == null ? "" : pe[0] > 0 ? App.t(R.string.player_season_episode, pe[0], pe[1]) : App.t(R.string.player_episode, pe[1]);
        refreshChips();
    }

    /**
     * Every menu of the player: a panel on the right edge (along the bottom on an upright phone), so the picture stays in view.
     * A row reads "name   value"; the one in use has a tick. Rows are the old lists' texts: "Speed · 1x", "720p  ✓".
     */
    void sheet(String title, CharSequence[] items, android.content.DialogInterface.OnClickListener pick) {
        sheet(title, items, null, pick);
    }

    /** icons: one drawable id per item (0: none), drawn before its words. */
    void sheet(String title, CharSequence[] items, int[] icons, android.content.DialogInterface.OnClickListener pick) {
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFA16131C);
        float r = dp(18);
        bg.setCornerRadii(wide ? new float[]{r, r, 0, 0, 0, 0, r, r} : new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(0, dp(18) + (wide ? cutTop : 0), wide ? cutRight : 0, dp(10));
        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(0xFF9690A3);
        head.setTextSize(14);
        head.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.setPadding(dp(22), 0, dp(22), dp(10));
        box.addView(head);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        View first = null;
        java.util.regex.Pattern two = java.util.regex.Pattern.compile("^(.*?)(?:\\s+\u00B7\\s+|:\\s+)(.*)$");
        for (int i = 0; i < items.length; i++) {
            final int n = i;
            String text = items[i].toString();
            boolean on = text.endsWith("\u2713");
            text = text.replaceAll("\\s*\u2713$", "");
            java.util.regex.Matcher m = two.matcher(text);
            boolean split = m.matches();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(46));
            row.setPadding(dp(14), dp(6), dp(12), dp(6));
            if (icons != null && icons[i] != 0) {
                ImageView lead = new ImageView(this);
                lead.setImageResource(icons[i]);
                lead.setColorFilter(on ? Home.ACCENT : 0xFF9690A3);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(22), dp(22));
                ll.rightMargin = dp(14);
                row.addView(lead, ll);
            }
            TextView name = new TextView(this);
            name.setText(split ? m.group(1) : text);
            name.setTextColor(on ? Home.ACCENT : Color.WHITE);
            name.setTextSize(15);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (split) {
                TextView value = new TextView(this);
                value.setText(m.group(2));
                value.setTextColor(0xFF9690A3);
                value.setTextSize(14);
                value.setPadding(dp(10), 0, 0, 0);
                row.addView(value);
            }
            if (on) {
                ImageView tick = new ImageView(this);
                tick.setImageResource(R.drawable.ic_p_check);
                tick.setColorFilter(Home.ACCENT);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(20), dp(20));
                tl.leftMargin = dp(10);
                row.addView(tick, tl);
            }
            android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
            GradientDrawable focused = Home.rounded(0x29FFFFFF, dp(10));
            focused.setStroke(dp(2), Color.WHITE);
            st.addState(new int[]{android.R.attr.state_focused}, focused);
            st.addState(new int[]{android.R.attr.state_pressed}, Home.rounded(0x1FFFFFFF, dp(10)));
            row.setBackground(st);
            row.setFocusable(true);
            row.setDefaultFocusHighlightEnabled(false);
            row.setOnClickListener(v -> { d.dismiss(); pick.onClick(d, n); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.setMargins(dp(8), dp(1), dp(8), dp(1));
            list.addView(row, rp);
            if (first == null || on) first = row;
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(list);
        box.addView(sc, wide ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, -2));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        w.getDecorView().setPadding(0, 0, 0, 0);
        w.setGravity(wide ? Gravity.END : Gravity.BOTTOM);
        w.setLayout(wide ? dp(340) : -1, wide ? -1 : -2);
        w.setDimAmount(0.3f);
        d.show();
        if (first != null) { View f = first; f.post(f::requestFocus); }
    }

    /** More: the settings changed less often, each with its value now. */
    void more() {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        labels.add(App.t(R.string.player_speed_now, speedLabel(player.getPlaybackParameters().speed)));
        acts.add(this::pickSpeed);
        icons.add(R.drawable.ic_p_speed);
        icons.add(R.drawable.ic_p_volume);
        labels.add(App.t(R.string.player_sound_boost_now, boostLabel(boost), voice == 0 ? App.t(R.string.player_off) : String.valueOf(voice)));
        acts.add(() -> audioPanel(this, () -> boost, v -> { boost = v; applyBoost(true); refreshChips(); }, () -> voice, v -> { voice = v; applyBoost(true); }, () -> { }));
        if (canDownload()) {
            labels.add(App.t(R.string.player_downloads));
            acts.add(() -> startActivity(new android.content.Intent(this, DownloadsActivity.class)));
            icons.add(R.drawable.ic_download);
        }
        labels.add(App.t(R.string.player_zoom_now, App.t(ZOOMS[zoom])));
        acts.add(this::cycleZoom);
        icons.add(R.drawable.ic_p_zoom);
        labels.add(App.t(R.string.player_debug_now, App.t(debugOn ? R.string.player_on : R.string.player_off)));
        acts.add(() -> setDebug(!debugOn));
        icons.add(R.drawable.ic_info);
        if (!player.isCurrentMediaItemLive()) {
            labels.add(App.t(R.string.player_restart));
            acts.add(() -> { player.seekTo(0); player.play(); say(App.t(R.string.player_from_start)); });
            icons.add(R.drawable.ic_p_restart);
        }
        labels.add(App.t(R.string.player_home));
        acts.add(() -> Home.goHome(this));
        icons.add(R.drawable.ic_p_home);
        int[] ids = new int[icons.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = icons.get(i);
        sheet(App.t(R.string.player_more), labels.toArray(new String[0]), ids, (d, i) -> acts.get(i).run());
    }

    // ---- Download ----

    /** A page's movie, series or anime (not a live channel, not an IPTV stream, not something already on the device). */
    boolean canDownload() {
        String url = String.valueOf(getIntent().getStringExtra("url"));
        return player != null && !player.isCurrentMediaItemLive() && !getIntent().hasExtra("mime") && url.matches("(?i)https?://.*");
    }

    /** The file name: "Movie (2024)", or "Show - S01E02 - Episode title" when the episode is known. */
    String downloadName() {
        String show = title.getText().toString();
        int[] pe = pageEpisode();
        if (seriesInfo != null && pe != null) {
            String ep = String.format(java.util.Locale.ROOT, "S%02dE%02d", pe[0], pe[1]), epTitle = "";
            for (Catalog.Episode e : seriesInfo.episodes) if (e.season == pe[0] && e.number == pe[1]) epTitle = e.title;
            return show + " - " + ep + (epTitle.isEmpty() ? "" : " - " + epTitle);
        }
        WebActivity page = WebActivity.active;
        String year = page != null && page.show != null ? String.valueOf(page.show.year) : "";
        return show + (year.matches("\\d{4}.*") ? " (" + year.substring(0, 4) + ")" : "");
    }

    /**
     * The Download button: looks at the stream first (so the quality and the extension are the real ones), then says which file will be
     * written, and where, before anything starts.
     */
    void confirmDownload() {
        String url = String.valueOf(getIntent().getStringExtra("url")), headers = getIntent().getStringExtra("headers"), base = downloadName();
        boolean dash = getIntent().getBooleanExtra("dash", false);
        androidx.media3.common.Format vf = player.getVideoFormat();
        int playing = vf == null ? 0 : vf.height;   // when the playlist does not say how tall the picture is
        say(App.t(R.string.dl_checking));
        App.io.execute(() -> {
            Downloads.Plan plan = Downloads.plan(url, headers, dash);
            if (plan.height == 0) plan.height = playing;
            runOnUiThread(() -> showConfirm(base, plan, null));
        });
    }

    /**
     * The question itself. The file name and the folder lines can be pressed: each opens its choice and then asks again with the change.
     * custom: the name the user typed (the extension is added), or null for the one made from the title.
     */
    void showConfirm(String base, Downloads.Plan plan, String custom) {
        if (isFinishing()) return;
        String name = custom != null ? custom : Downloads.fileBase(base, plan.height);
        Runnable[] edits = {
            () -> Subs.pickName(this, name, n -> showConfirm(base, plan, n)),
            () -> Subs.pickFolder(this, () -> showConfirm(base, plan, custom))};
        Subs.ask(this, App.t(R.string.dl_confirm_title), new int[]{R.drawable.ic_movie, R.drawable.ic_folder},
            new String[]{name + "." + plan.ext, Downloads.folderPath()},
            R.drawable.ic_download, App.t(R.string.player_download), () -> download(name, plan),
            edits, new String[]{App.t(R.string.dl_name_change), App.t(R.string.dl_folder_change)});
    }

    void download(String base, Downloads.Plan plan) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !App.tv && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 77);   // so the progress shows in the notification shade
        Downloads.Item it = Downloads.enqueue(getIntent().getStringExtra("url"), getIntent().getStringExtra("headers"), base, getIntent().getBooleanExtra("dash", false), plan);
        say(App.t(R.string.dl_added, it.name + "." + it.ext));
    }

    // ---- Skip intro, recap and credits ----

    /** Where the intro, recap and credits are (TheIntroDB, AniSkip), once the title is known. */
    void loadSkips() {
        WebActivity page = WebActivity.active;
        Catalog.Title t = seriesInfo != null ? seriesInfo : page != null ? page.show : null;
        if (t == null || skipsAsked || !getIntent().hasExtra("pageTitle")) return;
        int[] pe = pageEpisode();
        if (t.series() && pe == null) return;
        skipsAsked = true;
        App.io.execute(() -> {
            if (t.tmdb.isEmpty() && t.id.startsWith("tt")) Catalog.fill(t);   // the catalog's detail page has the TMDB id
            List<Skips.Part> found = Skips.find(t, pe == null ? 0 : pe[0], pe == null ? 0 : pe[1]);
            runOnUiThread(() -> skips = found);
        });
    }

    /**
     * Every half second: in an intro or recap, offer to skip it; in the credits (or the next-episode preview), offer the next
     * episode (only a press moves on), or to skip the credits when they don't run to the end.
     */
    void checkSkip() {
        if (player == null || inPip) return;   // the small window is just the picture
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        Skips.Part now = null;
        int[] next = episodeStep(1);
        if (dur > 0 && dur != C.TIME_UNSET && panel == null) {
            for (Skips.Part p : skips) if (p.has(pos, dur) && !skipDone.contains(p)) { now = p; break; }
            // No credits known inside this copy (none timed, or the site's cut is shorter): the last 30 s of an episode count
            if (now == null && next != null && dur - pos < 30000) {
                boolean known = false;
                for (Skips.Part p : skips) if (p.closing() && p.usable(dur) && p.start < dur) known = true;
                if (!known) {
                    if (tail == null || tail.start != dur - 30000) tail = new Skips.Part("credits", dur - 30000, -1);
                    if (!skipDone.contains(tail)) now = tail;
                }
            }
        }
        boolean end = now != null && now.closing();
        if (now == null || (end && next == null && now.end < 0)) { skipShown = null; skipBtn.setVisibility(View.GONE); return; }
        skipShown = now;
        if (end && next != null) {
            skipBtn.setText(App.t(R.string.player_next_episode, next[0], next[1]));
        } else {
            // Settings, Skip intros: an intro or recap goes by itself (credits still ask: they may hide a scene)
            if (!end && App.prefs.getBoolean("autoSkip", false) && player.isPlaying()) {
                skipShown = now;
                pressSkip();
                say(App.t(now.kind.equals("recap") ? R.string.player_skipped_recap : R.string.player_skipped_intro));
                return;
            }
            skipBtn.setText(App.t(now.kind.equals("recap") ? R.string.player_skip_recap : end ? R.string.player_skip_credits : R.string.player_skip_intro));
        }
        if (skipBtn.getVisibility() != View.VISIBLE) {
            skipBtn.setVisibility(View.VISIBLE);
            if (!inRow() && panel == null) skipBtn.requestFocus();   // OK presses it
        }
    }

    void pressSkip() {
        Skips.Part p = skipShown;
        if (p == null || player == null) return;
        skipDone.add(p);
        skipBtn.setVisibility(View.GONE);
        int[] next = episodeStep(1);
        if (p.closing() && next != null) { playEpisode(next[0], next[1]); return; }
        // land after the intro, never a few seconds back in it (the usual seek snaps to the nearest key frame)
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.NEXT_SYNC);
        player.seekTo(p.end < 0 ? player.getDuration() : p.end);
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
    }

    // ---- Episodes, Next, Sub / Dub ----

    /** The series playing, with its episodes: the page's (opened from the catalog), else looked up by the page's title. Once, in the background. */
    void loadSeries() {
        WebActivity page = WebActivity.active;
        if (page == null || seriesInfo != null || !getIntent().hasExtra("pageTitle")) return;
        Catalog.Title known = page.show;
        if (known != null && !known.series()) return;
        if (known == null && pageEpisode() == null) return;   // a movie, or a page that doesn't say it is an episode
        String name = History.cleanTitle(String.valueOf(getIntent().getStringExtra("pageTitle")), String.valueOf(getIntent().getStringExtra("site")));
        App.io.execute(() -> {
            Catalog.Title t = known;
            if (t == null) {
                String id = Subs.imdbFor(name.replaceAll("(?i)\\b(S\\d+\\s*E\\d+|season \\d+|episode \\d+)\\b.*", "").trim(), true);
                if (id.isEmpty()) return;
                t = new Catalog.Title();
                t.id = id;
                t.type = "series";
                t.name = name;
            }
            if (t.episodes.isEmpty()) Catalog.fill(t);
            Catalog.Title done = t;
            runOnUiThread(() -> {
                if (isFinishing() || done.episodes.isEmpty()) return;
                seriesInfo = done;
                if (page.show == null) page.show = done;
                refreshChips();
                loadSkips();
            });
        });
    }

    /** The episode after (dir 1) or before (dir -1) the one playing, by season then number; null at either end or when unknown. */
    int[] episodeStep(int dir) {
        int[] pe = pageEpisode();
        if (seriesInfo == null || pe == null) return null;
        long now = Math.max(1, pe[0]) * 100000L + pe[1], best = 0;
        for (Catalog.Episode e : seriesInfo.episodes) {
            long k = e.season * 100000L + e.number;
            if ((k - now) * dir > 0 && (best == 0 || (k - best) * dir < 0)) best = k;
        }
        return best == 0 ? null : new int[]{(int) (best / 100000), (int) (best % 100000)};
    }

    /** Leave the player and let the page open that episode (WebActivity.openEpisode); its stream comes back here. */
    void playEpisode(int season, int episode) {
        WebActivity page = WebActivity.active;
        if (page == null) { finish(); return; }
        page.pendingEpisode = new int[]{season, episode};
        Toast.makeText(this, App.t(R.string.player_season_episode, season, episode), Toast.LENGTH_SHORT).show();
        finish();
    }

    int panelSeason;

    /** A series' episodes in the same strip as channels and movies: every season in order (a window of them around the one playing). */
    void episodesPanel() {
        if (seriesInfo == null) return;
        List<Catalog.Episode> all = seriesInfo.episodes;
        int[] pe = pageEpisode();
        int at = -1;
        for (int i = 0; i < all.size(); i++) if (pe != null && all.get(i).season == Math.max(1, pe[0]) && all.get(i).number == pe[1]) at = i;
        int from = Math.max(0, Math.min(Math.max(at, 0) - 20, all.size() - 60)), now = at;
        List<Catalog.Episode> list = all.subList(from, Math.min(all.size(), from + 60));
        shelf(App.t(R.string.player_episodes), list.size(), 2, i -> {
            Catalog.Episode e = list.get(i);
            return new String[]{"S" + e.season + " E" + e.number + (e.title.isEmpty() ? "" : "  " + e.title), e.thumb};
        }, i -> { if (i != now - from) playEpisode(list.get(i).season, list.get(i).number); }, at - from);
    }

    GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(color);
        return d;
    }

    GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(color);
        return d;
    }

    /** `min`: the card's width in pixels (the picture is kept no bigger). */
    void loadStill(ImageView into, String url, int min) {
        String key = url + "@" + min;
        into.setTag(key);
        android.graphics.Bitmap hit = Home.images.get(key);
        if (hit != null) { into.setImageBitmap(hit); return; }
        Home.imagePool.execute(() -> {
            android.graphics.Bitmap b = Home.fetch(url, min);
            if (b == null) return;
            Home.images.put(key, b);
            runOnUiThread(() -> { if (key.equals(into.getTag())) into.setImageBitmap(b); });
        });
    }

    void closePanel() {
        if (panel == null) return;
        ((android.view.ViewGroup) panel.getParent()).removeView(panel);
        panel = null;
        bottomBar.setTranslationY(0);
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        if (shelfOpen) { shelfOpen = false; showControls(); }   // the controls were held open for the strip: their timer runs again
    }

    /** Sub / Dub: the page's own choice (a dropdown or toggle that frame.js found: label -> its key), else Sub and Dub by the site's address (key ""). */
    Map<String, String> subDub() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        WebActivity page = WebActivity.active;
        if (page == null) return out;
        if (page.audios.size() >= 2) for (Map.Entry<String, String[]> e : page.audios.entrySet()) out.put(e.getValue()[0], e.getKey());
        else if (page.show != null && page.show.anime && Sites.hasAudioLink(page.siteUrl) && pageEpisode() != null) {
            out.put("Sub", "");
            out.put("Dub", "");
        }
        return out;
    }

    /** The Sub / Dub playing: the page's selected one, else the one picked for this site before, else Sub. */
    String subDubNow() {
        WebActivity page = WebActivity.active;
        if (page == null) return "";
        for (String[] v : page.audios.values()) if (!v[1].isEmpty()) return v[0];
        String want = App.prefs.getString("siteAudio:" + page.siteKey(), "");
        return want.isEmpty() ? "Sub" : want;
    }

    /** One tap switches between two (Sub, Dub); more than two (Sub, Dub, Hindi) are listed. */
    void pickSubDub() {
        Map<String, String> m = subDub();
        if (m.size() < 2) return;
        List<String> labels = new ArrayList<>(m.keySet());
        String now = subDubNow();
        if (labels.size() == 2) {
            String other = labels.get(labels.get(0).equalsIgnoreCase(now) ? 1 : 0);
            switchSubDub(other, m.get(other));
            return;
        }
        List<String> shown = new ArrayList<>();
        for (String l : labels) shown.add(l + (l.equalsIgnoreCase(now) ? "  \u2713" : ""));
        sheet(App.t(R.string.player_sub_dub, now), shown.toArray(new String[0]), (d, i) -> switchSubDub(labels.get(i), m.get(labels.get(i))));
    }

    void switchSubDub(String label, String key) {
        WebActivity page = WebActivity.active;
        if (page == null) return;
        page.rememberAudio(label);
        carryMs = player.getCurrentPosition();   // the same episode in the other language: carry on from here
        carryAt = SystemClock.elapsedRealtime();
        if (!key.isEmpty()) {
            for (Map.Entry<String, String[]> e : page.audios.entrySet()) e.getValue()[1] = e.getKey().equals(key) ? "1" : "";   // the pick is now the one playing
            page.switchServer(key);
        }
        else {
            int[] pe = pageEpisode();
            page.pendingEpisode = new int[]{Math.max(1, pe[0]), pe[1]};
        }
        say(label);
        finish();
    }

    // ---- OpenSubtitles ----

    /** Only for a page's movie or episode, never a live channel. */
    boolean canSearchSubs() {
        return getIntent().hasExtra("pageTitle") && player != null && !player.isCurrentMediaItemLive();
    }

    /** Search: pick a language (the one picked last, the device's and English first), then a file in it. */
    void searchSubs() {
        List<String> codes = Subs.languages(), names = new ArrayList<>();
        for (String c : codes) names.add(Subs.language(c));
        sheet(App.t(R.string.player_subs_language), names.toArray(new String[0]), (d, i) -> {
                say(App.t(R.string.player_searching_subs));
                findSubs(codes.get(i), found -> pickRelease(codes.get(i), found), true);
            });
    }

    /** The files in one language for what is playing go to `then` (main thread) when there are any; `loud` says why when there are none. */
    void findSubs(String lang, java.util.function.Consumer<List<Subs.Sub>> then, boolean loud) {
        Intent in = getIntent();
        String imdb = in.getStringExtra("imdb"), title = History.cleanTitle(String.valueOf(in.getStringExtra("pageTitle")), String.valueOf(in.getStringExtra("site")));
        // The page's own URL/title say which episode is playing (the site may have moved on from the one picked in the catalog)
        int[] pe = pageEpisode();
        final int s = pe == null ? 0 : Math.max(1, pe[0]), e = pe == null ? 0 : pe[1];
        final boolean needsEpisode = in.getBooleanExtra("series", false) && pe == null;
        App.io.execute(() -> {
            String id = needsEpisode ? "" : imdb == null || !imdb.startsWith("tt") ? Subs.imdbFor(title, s > 0) : imdb;
            List<Subs.Sub> found = id.isEmpty() ? null : Subs.search(id, s, e, lang);
            runOnUiThread(() -> {
                if (isFinishing() || player == null) return;
                if (found == null) { if (loud) say(App.t(needsEpisode ? R.string.player_open_episode : R.string.player_not_on_opensubs)); }
                else if (found.isEmpty()) { if (loud) say(App.t(R.string.player_no_subs_lang, Subs.language(lang))); }
                else then.accept(found);
            });
        });
    }

    void pickRelease(String lang, List<Subs.Sub> found) {
        List<Subs.Sub> files = found.subList(0, Math.min(60, found.size()));
        List<String> names = new ArrayList<>();
        for (Subs.Sub f : files) names.add(f.release + (f.hearingImpaired ? "  \u00B7  CC" : "") + (f.source.equals("OpenSubtitles") ? "" : "  \u00B7  " + f.source));
        sheet(App.t(R.string.player_pick_release, Subs.language(lang)), names.toArray(new String[0]), (d, j) -> {
                for (String k : subKeys(false)) { Subs.remember(k, files.get(j)); Subs.rememberTrack(k, null, "", 0); }   // the file replaces a track picked before
                addSubtitle(files.get(j));
            });
    }

    /** Which movie or episode is playing, to remember its subtitle file: IMDb id (else the page's title), plus season and episode. Null for an episode we can't tell. */
    List<String> subKeys(boolean older) {
        Intent in = getIntent();
        List<String> keys = new ArrayList<>();
        int[] pe = pageEpisode();
        if (pe == null && in.getBooleanExtra("series", false)) return keys;
        String tail = pe == null ? "" : ":" + pe[0] + ":" + pe[1], site = String.valueOf(in.getStringExtra("site")), raw = String.valueOf(in.getStringExtra("pageTitle"));
        String imdb = in.getStringExtra("imdb");
        boolean hasImdb = imdb != null && !imdb.isEmpty() && !imdb.equals("null");
        if (hasImdb) keys.add(imdb + tail);
        String title = History.same(History.titleOf(raw, site));
        if (!title.isEmpty()) keys.add("t:" + title + tail);
        if (older && !hasImdb) keys.add(History.cleanTitle(raw, site).toLowerCase(Locale.ROOT) + tail);   // how earlier versions filed a title without an IMDb id
        return keys;
    }

    /** The stream's own subtitle track picked for this video before: the same language and name, else the same place in the list; false when none fits. */
    boolean restoreTrack(JSONObject r) {
        String want = androidx.media3.common.util.Util.normalizeLanguageCode(r.optString("lang")), label = r.optString("label");
        int idx = r.optInt("index"), n = 0, score = 0, bestT = -1;
        Tracks.Group best = null;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int t = 0; t < g.length; t++) {
                if (!g.isTrackSupported(t)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(t);
                n++;
                int sc = (!want.isEmpty() && f.language != null && androidx.media3.common.util.Util.normalizeLanguageCode(f.language).equals(want) ? 2 : 0)
                    + (labelOf(f, R.string.player_subs_n, n).equals(label) ? 2 : 0) + (n == idx ? 1 : 0);
                if (sc > score) { score = sc; best = g; bestT = t; }
            }
        }
        if (best == null || score < 2) return false;   // neither the language nor the name matches: leave it off
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).addOverride(new TrackSelectionOverride(best.getMediaTrackGroup(), bestT)).build());
        say(App.t(R.string.player_subs_lang, labelOf(best.getTrackFormat(bestT), R.string.player_subs_n, n)));
        return true;
    }

    /** At the start: the file picked for this video before; else, with Auto subtitles on, the stream's own track or OpenSubtitles' first file in your language. */
    void autoSubs() {
        if (!canSearchSubs()) return;
        Subs.Sub kept = null;
        JSONObject track = null;
        for (String k : subKeys(true)) {
            if (kept == null) kept = Subs.remembered(k);
            if (track == null) track = Subs.rememberedTrack(k);
        }
        // A pick found under one key is filed under the others too: opened from the catalog or from Continue watching, it is the same video
        if (kept != null) { for (String k : subKeys(false)) Subs.remember(k, kept); addSubtitle(kept); return; }
        if (track != null) {
            for (String k : subKeys(false)) Subs.rememberTrack(k, track.optString("lang"), track.optString("label"), track.optInt("index"));
            if (restoreTrack(track)) return;
        }
        if (!Subs.auto()) return;
        String lang = Subs.preferred(), want = androidx.media3.common.util.Util.normalizeLanguageCode(lang);
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int t = 0; t < g.length; t++) {
                String l = g.getTrackFormat(t).language;
                if (!g.isTrackSupported(t) || l == null || !androidx.media3.common.util.Util.normalizeLanguageCode(l).equals(want)) continue;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .addOverride(new TrackSelectionOverride(g.getMediaTrackGroup(), t)).build());
                say(App.t(R.string.player_subs_lang, Subs.language(lang)));
                return;
            }
        }
        findSubs(lang, found -> addSubtitle(found.get(0)), false);   // the most downloaded file in your language
    }

    // ---- Volume boost ----

    static final int BOOST_MAX = 20;   // both boosts: 0 is off, 20 is the most

    /** Settings, Volume boost: the dB every video starts with. */
    static int boostDb() {
        return Math.max(0, Math.min(BOOST_MAX, App.prefs.getInt("boost", 0)));
    }

    /** Settings, Dialog boost: the level every video starts with. 10 is what the old on / off switch meant by On. */
    static int dialogLevel() {
        int level = App.prefs.contains("dialogLevel") ? App.prefs.getInt("dialogLevel", 0) : App.prefs.getBoolean("dialogBoost", false) ? 10 : 0;
        return Math.max(0, Math.min(BOOST_MAX, level));
    }

    static String boostLabel(int db) {
        return db == 0 ? App.t(R.string.player_off) : App.t(R.string.player_db, db);
    }

    /**
     * Volume boost: LoudnessEnhancer (raises the level, limits the peaks). Dialog boost, level 1-20: an Equalizer that only CUTS (rumble
     * and bass down, voices untouched; the cuts grow with the level up to the device's limit) plus 0.4 dB of LoudnessEnhancer make-up
     * per level, so voices come out louder. Positive Equalizer gains are
     * avoided on purpose: Android's bundled Equalizer turns the whole output down by its largest boost to avoid clipping, which
     * made "dialog boost" quieter. Effects also move the track to another output path on many devices, so when the last one is
     * switched off the audio output is rebuilt (a new audio session) to go back to the normal path. With both off nothing is
     * attached. told = the user just changed a boost (only then is "doesn't work on this device" worth saying).
     */
    void applyBoost(boolean told) {
        boolean had = booster != null || voices != null;
        if (booster != null) { booster.release(); booster = null; }
        if (voices != null) { voices.release(); voices = null; }
        if (player == null) return;
        int gain = boost * 100 + voice * 40;   // millibels
        if (gain == 0) {
            if (had) player.setAudioSessionId(((AudioManager) getSystemService(AUDIO_SERVICE)).generateAudioSessionId());
            return;
        }
        if (player.getAudioSessionId() == 0) return;
        try {
            booster = new android.media.audiofx.LoudnessEnhancer(player.getAudioSessionId());
            booster.setTargetGain(gain);
            booster.setEnabled(true);
            if (voice > 0) {
                voices = new android.media.audiofx.Equalizer(0, player.getAudioSessionId());
                short[] range = voices.getBandLevelRange();
                for (short b = 0; b < voices.getNumberOfBands(); b++) {
                    int hz = voices.getCenterFreq(b) / 1000;   // reported in milliHertz
                    int level = (hz < 150 ? -900 : hz < 400 ? -500 : hz < 800 ? -150 : hz <= 5000 ? 0 : -200) * voice / 10;
                    voices.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], level)));
                }
                voices.setEnabled(true);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "audio boost", e);
            if (told) say(App.t(R.string.player_boost_unsupported));
        }
    }

    /**
     * Volume boost and Dialog boost, each 0-20 with − and +. From Settings they are saved (every video starts with them), from the player
     * they change this video only; `closed` runs when the panel goes away.
     */
    static void audioPanel(android.app.Activity a, java.util.function.IntSupplier boost, java.util.function.IntConsumer setBoost,
                           java.util.function.IntSupplier level, java.util.function.IntConsumer setLevel, Runnable closed) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        List<Runnable> refresh = new ArrayList<>();
        Runnable after = () -> { for (Runnable r : refresh) r.run(); };
        Subs.row(a, body, refresh, App.t(R.string.home_volume_boost), () -> boostLabel(boost.getAsInt()),
            by -> setBoost.accept(Math.max(0, Math.min(BOOST_MAX, boost.getAsInt() + by))), after);
        Subs.row(a, body, refresh, App.t(R.string.home_dialog_boost), () -> level.getAsInt() == 0 ? App.t(R.string.player_off) : String.valueOf(level.getAsInt()),
            by -> setLevel.accept(Math.max(0, Math.min(BOOST_MAX, level.getAsInt() + by))), after);
        Subs.show(a, App.t(R.string.audio_title), body, App.t(R.string.audio_help), () -> { setBoost.accept(0); setLevel.accept(0); after.run(); }, closed);
    }

    // Side-load the file and restart the stream at the same spot with it switched on
    /** Downloads the file (unpacked, converted to UTF-8, kept for next time), then shows it as the subtitle track. */
    void addSubtitle(Subs.Sub sub) {
        App.io.execute(() -> {
            SubFetch.Fetched f;
            try {
                f = Subs.fetch(sub);
            } catch (java.io.IOException e) {
                runOnUiThread(() -> say(App.t(R.string.player_subs_failed)));
                return;
            }
            runOnUiThread(() -> { if (player != null && !isFinishing()) showSubtitle(sub, f); });
        });
    }

    void showSubtitle(Subs.Sub sub, SubFetch.Fetched f) {
        String mime = f.ext.equals("vtt") ? MimeTypes.TEXT_VTT : f.ext.equals("srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_SSA;
        subList.removeIf(c -> "opensubtitles".equals(c.id));
        subList.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f.file)).setId("opensubtitles").setMimeType(mime)
            .setLanguage(sub.lang).setLabel(Subs.language(sub.lang) + " (" + sub.source + ")").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
        mediaItem.setSubtitleConfigurations(subList);
        long pos = player.getCurrentPosition();
        boolean playing = player.getPlayWhenReady();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.setMediaItem(mediaItem.build(), pos);
        player.prepare();
        player.setPlayWhenReady(playing);
        say(App.t(R.string.player_subs_lang, Subs.language(sub.lang)));
        // Auto-sync: once enough has played, line the file up with the speech (a release with a recap is often minutes off)
        syncCues = null;
        syncTries = 0;
        syncSet = Subs.delayMs;
        App.io.execute(() -> {
            try {
                List<long[]> cues = SubSync.cues(f.file);
                runOnUiThread(() -> syncCues = cues);
            } catch (java.io.IOException ignored) { }
        });
        App.main.removeCallbacks(syncCheck);
        App.main.postDelayed(syncCheck, 45000);
    }

    /** Every 45 s (8 times at most) until the file is lined up with the speech, unless you set the timing yourself. */
    final Runnable syncCheck = new Runnable() {
        @Override public void run() {
            if (player == null || isFinishing() || syncCues == null || Subs.delayMs != syncSet || syncTries++ >= 8) return;
            float[] level = meter.snapshot();
            List<long[]> cues = syncCues;
            App.io.execute(() -> {
                Long d = SubSync.offset(level, cues);
                Log.i("SubSync", "try " + syncTries + ": " + (d == null ? "not sure yet" : d + " ms"));
                runOnUiThread(() -> {
                    if (player == null || isFinishing() || Subs.delayMs != syncSet) return;
                    if (d == null) { App.main.postDelayed(syncCheck, 45000); return; }
                    if (Math.abs(d - Subs.delayMs) < 250) return;   // already in sync
                    boolean jump = Math.abs(d - Subs.delayMs) > 5000;
                    Subs.delayMs = syncSet = d;
                    if (jump) player.seekTo(player.getCurrentPosition());   // the subtitle track picks up its new place
                    say(App.t(R.string.player_subs_synced, Subs.delayLabel()));
                });
            });
        }
    };

    // ---- Picture in picture (phone): Home while a video plays keeps it playing in a small window ----

    static final String PIP_TOGGLE = "me.champdev.weblock.PIP_TOGGLE";
    boolean inPip, offScreen;   // in the small window; stopped (not seen at all)
    final android.content.BroadcastReceiver pipToggle = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
        }
    };

    boolean canPip() {
        return !App.tv && player != null && casting == null && !isFinishing()
            && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** The window's shape (the picture's, within what Android allows), a play / pause button, and (Android 12+) going in by itself on Home while playing. */
    android.app.PictureInPictureParams pipParams() {
        float r = videoRatio > 0 ? Math.max(0.42f, Math.min(2.38f, videoRatio)) : 16f / 9;
        boolean playing = player.isPlaying();
        String label = App.t(playing ? R.string.player_pause : R.string.player_play);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 0, new Intent(PIP_TOGGLE).setPackage(getPackageName()),
            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.RemoteAction act = new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, playing ? R.drawable.ic_p_pause : R.drawable.ic_p_play), label, label, pi);
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
            .setAspectRatio(new android.util.Rational(Math.round(r * 1000), 1000)).setActions(Collections.singletonList(act));
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true);
        return b.build();
    }

    void updatePip() {
        if (canPip()) try { setPictureInPictureParams(pipParams()); } catch (IllegalStateException ignored) { }
    }

    // Before Android 12 there is no going in by itself: Home (leaving while it plays) asks for it
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (android.os.Build.VERSION.SDK_INT < 31 && canPip() && player.isPlaying()) {
            try { enterPictureInPictureMode(pipParams()); } catch (IllegalStateException ignored) { }
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean in, android.content.res.Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        inPip = in;
        if (debug != null) debug.setVisibility(debugOn && !in ? View.VISIBLE : View.GONE);
        if (in) {
            closePanel();
            hideControls();
            skipBtn.setVisibility(View.GONE);
            notice.setVisibility(View.GONE);
        } else if (offScreen) finishAndRemoveTask();   // the small window was closed: the video is done with
    }

    @Override protected void onStart() {
        super.onStart();
        offScreen = false;
        if (debugOn && player != null) debugTick.run();
    }

    @Override protected void onStop() {
        super.onStop();
        offScreen = true;
        if (player == null) return;
        player.pause();
        savePosition();
    }

    /**
     * Remembers where this video is, for Continue watching and the next time it opens: on leaving and every 10 s while it plays.
     * Not a spot under 10 s of a video that was resumed (leaving right after opening must not wipe the saved place).
     */
    void savePosition() {
        if (player == null) return;
        long dur = player.getDuration(), pos = player.getCurrentPosition();
        if (pos < 10000 && startedAt > 10000) return;
        if (dur != C.TIME_UNSET && !player.isCurrentMediaItemLive() && pos < dur - 30000) resume.put(base, pos); else resume.remove(base);
        if (dur != C.TIME_UNSET && dur > 0 && !player.isCurrentMediaItemLive() && !getIntent().hasExtra("mime")) {
            Intent in = getIntent();
            // The page's own capture may not have made its entry yet (the player opened first): make it, or there is nowhere to keep the place
            if (in.getStringExtra("siteUrl") != null && in.getStringExtra("pageUrl") != null)
                History.record(in.getStringExtra("site"), in.getStringExtra("siteUrl"), in.getStringExtra("pageUrl"), in.getStringExtra("pageTitle"), in.getStringExtra("image"));
            History.savePosition(in.getStringExtra("site"), in.getStringExtra("pageTitle"), in.getStringExtra("pageUrl"), pos, dur);
        }
    }

    // ---- Simkl ----

    boolean scrobbled, stopped;   // a start went out for this video; the stop went out
    final String session = java.util.UUID.randomUUID().toString();   // PunchPlay's playback_session_id for this video

    /** Tells Simkl and PunchPlay where the video is, for a movie or an episode from the catalog (live streams and unknown episodes are left out). */
    void scrobble(String action) {
        if (player == null || player.isCurrentMediaItemLive() || stopped || !scrobbled) return;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) return;
        boolean series = getIntent().getBooleanExtra("series", false);
        int[] episode = series ? pageEpisode() : null;
        if (series && episode == null) return;
        if (action.equals("stop")) stopped = true;
        Simkl.scrobble(action, getIntent().getStringExtra("imdb"), episode,
            player.getPlaybackState() == Player.STATE_ENDED ? 100 : player.getCurrentPosition() * 100.0 / dur);
        PunchPlay.scrobble(action, getIntent().getStringExtra("imdb"), title.getText().toString(), episode,
            player.getPlaybackState() == Player.STATE_ENDED ? dur : player.getCurrentPosition(), dur, session);
    }

    // ---- Cast (phone): send this video to an FMHYPlay TV on the same Wi-Fi, then be its remote ----

    void pickTv() {
        java.util.List<String[]> list = Cast.targets();
        String[] labels = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i)[0];
        labels[list.size()] = App.t(R.string.cast_rescan);
        sheet(App.t(list.isEmpty() ? R.string.cast_no_tv : R.string.cast_to), labels, (d, i) -> {
            if (i < list.size()) { castTo(list.get(i)); return; }
            Cast.rescan(this);
            say(App.t(R.string.cast_looking));
            App.main.postDelayed(() -> { if (!isFinishing() && casting == null) pickTv(); }, 4000);
        });
    }

    /** t = {label, "cast" or "app", name, host:port}: Google Cast through the phone, or FMHYPlay on the TV. */
    void castTo(String[] t) {
        String name = t[2];
        say(App.t(R.string.cast_sending, name));
        final org.json.JSONObject m;
        try {
            m = Cast.describe(getIntent(), player.getCurrentPosition()).put("live", player.isCurrentMediaItemLive()).put("title", title.getText().toString());
        } catch (Exception e) { say(App.t(R.string.cast_failed)); return; }
        Cast.Tv tv = t[1].equals("cast") ? new Chromecast(t[3]) : t[1].equals("dlna") ? new Dlna(t[3]) : new Cast.App2Tv(t[3]);
        App.io.execute(() -> {
            String why;
            try { why = tv.start(m); } catch (Exception e) { android.util.Log.w(TAG, "cast", e); why = App.t(R.string.cast_unreachable, name); tv.close(); }
            String err = why;
            App.main.post(() -> {
                if (isFinishing()) { if (err == null) tv.close(); return; }
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                if (Cast.session != null) Cast.session.close();
                Cast.session = tv;
                player.pause();
                casting = name;
                refreshChips();
                openRemote();
            });
        });
    }

    void openRemote() {
        if (Cast.session == null) { casting = null; refreshChips(); return; }
        startActivityForResult(new android.content.Intent(this, Cast.Remote.class).putExtra("name", casting)
            .putExtra("title", title.getText().toString()), REQ_CAST);
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAST || player == null) return;
        if (data != null && data.hasExtra("at")) {
            // Stopped casting (or the TV closed the video): carry on here from where the TV was
            casting = null;
            long at = data.getLongExtra("at", 0);
            if (at > 0) player.seekTo(at);
            player.play();
            say(App.t(R.string.cast_back));
        } else say(App.t(R.string.cast_playing_remote, casting));
        refreshChips();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pipToggle); } catch (IllegalArgumentException ignored) { }
        if (current == this) current = null;
        scrobble("stop");
        if (getIntent().hasExtra("mime")) Iptv.saveHealth();
        App.main.removeCallbacks(tick);
        App.main.removeCallbacks(debugTick);
        App.main.removeCallbacks(hideNotice);
        if (booster != null) { booster.release(); booster = null; }
        if (voices != null) { voices.release(); voices = null; }
        if (player != null) { player.release(); player = null; }
        if (previousProxy != null) ProxySelector.setDefault(previousProxy);
        super.onDestroy();
    }
}
