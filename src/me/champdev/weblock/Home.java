package me.champdev.weblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Start screen: Continue watching, then FMHY's streaming sites by category, then the user's own sites. */
public class Home extends Activity {
    // One accent, kept for play and progress; everything else is ink, two card tones and two text tones
    static final int BG = 0xFF0E0C13, CARD = 0xFF1A1721, CARD_HI = 0xFF282431, NAV = 0xFF131019, ACCENT = 0xFFFF3B5C, TEXT = 0xFFF4F2F7, MUTED = 0xFF9690A3;
    // Decoded pictures: a sixth of the app's memory (it was a fixed 24 MB, about 17 posters, so scrolling back decoded them all again)
    static final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 6, 64L << 20)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };
    // Pictures load on their own threads (the shared pool is busy with catalogs and WARP), one download per picture however many cards ask
    static final java.util.concurrent.ExecutorService imagePool = java.util.concurrent.Executors.newFixedThreadPool(6);
    static final Map<String, List<java.util.function.Consumer<Bitmap>>> loading = new HashMap<>();
    static final Map<String, Long> failed = new java.util.concurrent.ConcurrentHashMap<>();   // address -> when it failed: not asked again for a while
    static final long IMAGE_DISK_MAX = 200L << 20;

    ScrollView page;
    LinearLayout col;
    static final int GREEN = 0xFF6EE7A0, AMBER = 0xFFFFC857;
    int side, slack, cardW = 220, cardH = 92, gapDp = 14;

    /** TV value on a TV, tighter value on a phone (dp / sp). */
    int u(float tv, float phone) { return dp(App.tv ? tv : phone); }
    float sp(float tv, float phone) { return App.tv ? tv : phone; }
    TextView warpChip;
    View firstCard;
    final Runnable warpChanged = this::updateChips;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    int dp(float v) {
        return dp(this, v);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        GifView.prefetch();   // the loading backdrop's pictures, fetched once
        android.view.ViewTreeObserver seen = getWindow().getDecorView().getViewTreeObserver();
        seen.addOnScrollChangedListener(this::checkLazy);
        seen.addOnGlobalLayoutListener(this::checkLazy);
        if (state == null && getClass() == Home.class) App.io.execute(Home::trimImages);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView sv = page = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // One margin on every side; rows get a little slack past it so the grown focus ring never clips
        side = u(16, 10);
        slack = u(10, 6);
        cardW = App.tv ? 220 : 156;
        cardH = App.tv ? 92 : 62;
        gapDp = App.tv ? 14 : 8;
        col.setPadding(side, App.tv ? (tabs() ? dp(18) : side) : dp(6), side, App.tv ? side : dp(12));
        col.setClipToPadding(false);
        col.setClipChildren(false);   // grown cards and their rings draw past their rows
        sv.addView(col);
        if (!App.tv && tabs()) {
            // Pull down at the top of a tab to load it again
            float[] from = {-1};
            sv.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_MOVE && sv.getScrollY() == 0 && !refreshing) {
                    if (from[0] < 0) from[0] = e.getRawY();
                    col.setTranslationY(Math.max(0, Math.min((e.getRawY() - from[0]) / 3, dp(64))));
                } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    boolean go = a == android.view.MotionEvent.ACTION_UP && col.getTranslationY() >= dp(48);
                    col.animate().translationY(0).setDuration(150).start();
                    from[0] = -1;
                    if (go) refresh();
                } else from[0] = -1;
                return false;
            });
        }
        if (tabs() && state == null) tab = 0;
        setContentView(withNav(sv));
        Warp.listeners.add(warpChanged);

        // Site lists and IPTV channels refresh whenever the app starts and they are older than 12 hours
        if (Sites.stale() || Iptv.stale()) Warp.whenSettled(() -> App.io.execute(() -> {
            boolean sites = Sites.stale() && Sites.refresh();
            boolean iptv = Iptv.stale() && Iptv.refresh();
            if (sites || iptv) runOnUiThread(this::build);
        }));
        Warp.whenSettled(() -> App.io.execute(Iptv::refreshLinks));
        // Test the channels that were not checked lately (a few at a time, in the background)
        Warp.whenSettled(() -> App.io.execute(Iptv::sweep));
        // A newer release on GitHub shows as a line on Home
        if (Update.due()) App.io.execute(() -> { if (Update.check()) runOnUiThread(this::build); });   // GitHub's API is open: no waiting for WARP
    }

    @Override protected void onResume() {
        super.onResume();
        boolean back = App.tv && (focusKey != null || focusPath != null) && savedTab == tab;   // coming back from a video or a page
        skipFocus = back;
        if (back) {   // the page holds the focus (unseen) while it is redrawn, so it never flashes on the rail's first button
            col.setFocusable(true);
            col.setDefaultFocusHighlightEnabled(false);
            col.requestFocus();
        }
        build();
        skipFocus = false;
        if (back) restoreFocus(15);
    }

    // ---- TV: the remote's place is kept. Coming back to a screen puts it on the card it left from, not on the first button ----

    String focusKey;   // the card's own key (a title, channel or site), when it has one
    int[] focusPath;   // where it sat in the page: child numbers from the page down to it
    int savedScroll, savedTab = -1;

    @Override protected void onPause() {
        super.onPause();
        View f = getCurrentFocus();
        focusKey = null;
        focusPath = null;
        if (!App.tv || f == null || !inside(f, col)) return;
        focusKey = f.getTag() instanceof String ? (String) f.getTag() : null;
        List<Integer> path = new ArrayList<>();
        for (View v = f; v != col; v = (View) v.getParent()) path.add(0, ((android.view.ViewGroup) v.getParent()).indexOfChild(v));
        focusPath = new int[path.size()];
        for (int i = 0; i < focusPath.length; i++) focusPath[i] = path.get(i);
        savedScroll = page.getScrollY();
        savedTab = tab;
    }

    static boolean inside(View v, View root) {
        for (android.view.ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) if (p == root) return true;
        return false;
    }

    /** The card at the remembered place if it is the same card, else the first card with its key, else whatever sits at the place. Rows that fill in late are waited for. */
    void restoreFocus(int tries) {
        if (focusKey == null && focusPath == null) return;
        View at = col;
        for (int i = 0; focusPath != null && at != null && i < focusPath.length; i++)
            at = at instanceof android.view.ViewGroup && focusPath[i] < ((android.view.ViewGroup) at).getChildCount() ? ((android.view.ViewGroup) at).getChildAt(focusPath[i]) : null;
        if (at == col || (at != null && !at.isFocusable())) at = null;
        View pick = at != null && (focusKey == null || focusKey.equals(at.getTag())) ? at : focusKey != null ? keyed(col, focusKey) : null;
        if (pick == null && tries <= 0) pick = at;   // the card is gone (watched to the end, removed): the same spot
        if (pick == null && tries > 0) { col.postDelayed(() -> restoreFocus(tries - 1), 100); return; }
        focusKey = null;
        focusPath = null;
        if (pick == null) pick = firstCard;
        if (pick != null) {
            page.scrollTo(0, savedScroll);
            pick.requestFocus();
        }
        col.setFocusable(false);
    }

    static View keyed(View v, String key) {
        if (v.isFocusable() && key.equals(v.getTag())) return v;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
            View hit = keyed(((android.view.ViewGroup) v).getChildAt(i), key);
            if (hit != null) return hit;
        }
        return null;
    }

    View railView;

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (holdBack(this, e)) return true;
        // TV: Up and Down stay where they are, in the page or in the rail. (Android picks the nearest button anywhere on screen,
        // so Down at the end of a page jumped into the rail.) Left and Right cross between the two.
        int k = e.getKeyCode();
        View f = getCurrentFocus();
        if (App.tv && f != null && (k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) && !(f instanceof EditText)) {
            android.view.ViewGroup zone = inside(f, page) ? page : railView != null && inside(f, railView) ? (android.view.ViewGroup) railView : null;
            if (zone != null) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    View n = android.view.FocusFinder.getInstance().findNextFocus(zone, f, k == android.view.KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                    if (n != null) n.requestFocus();
                    else if (zone == page) page.arrowScroll(k == android.view.KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);   // text past the last card
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyUp(int k, android.view.KeyEvent e) {
        View f = getCurrentFocus();
        if (k != android.view.KeyEvent.KEYCODE_MENU || f == null) return super.onKeyUp(k, e);
        f.performLongClick();
        return true;
    }

    @Override protected void onDestroy() {
        Warp.listeners.remove(warpChanged);
        super.onDestroy();
    }

    boolean refreshing;

    /** Loads the open tab again: catalog lists from the network, and the site lists (Sports, Home) or the channels (IPTV). */
    void refresh() {
        if (refreshing) return;
        Catalog.expire();
        final int t = tab;
        if (t != 3 && t != 5) {   // poster rows show their placeholders while they load: that is the sign
            build();
            if (t == 0) App.io.execute(Sites::refresh);
            return;
        }
        refreshing = true;
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(8), 0, dp(8));
        col.addView(spin, 0, lp);
        App.io.execute(() -> {
            if (t == 5) Iptv.refresh(); else Sites.refresh();
            runOnUiThread(() -> { refreshing = false; if (tab == t) build(); });
        });
    }

    /**
     * A screen's content with the navigation around it, on every screen: a TV has the rail down the left side (one Left press from any row),
     * a phone the tabs along the bottom, and on the tab screens the top bar. A page on top of the tabs (title, search, a list) goes Home on a tab press.
     */
    View withNav(View content) {
        tabViews.clear();
        tabBar = tabBar();
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        if (App.tv) {
            root.addView(rail(), new LinearLayout.LayoutParams(dp(RAIL), -1));
            root.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            if (tabs()) root.addView(topBar());
            root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
            View line = new View(this);
            line.setBackgroundColor(0x14FFFFFF);
            root.addView(line, new LinearLayout.LayoutParams(-1, 1));
            root.addView(tabBar);
            getWindow().setNavigationBarColor(NAV);
        }
        styleTabs();
        return root;
    }

    /** Draws the selected tab. Subclasses (search, category, title) replace this whole method. */
    void build() {
        col.removeAllViews();
        firstCard = null;
        heroBox = null;
        built++;
        cardW = App.tv ? 220 : 156;   // a site grid on another tab resizes the shared card width
        cardH = App.tv ? 92 : 62;
        styleTabs();
        switch (tab) {
            case 0: buildHome(); break;
            case 1: catalogTab("movie", Cats.MOVIES, ""); break;
            case 2: catalogTab("series", Cats.TV, ""); break;
            case 3: sportsTab(); break;
            case 4: catalogTab("series", Cats.ANIME, Catalog.POPULAR_ANIME); break;
            case 5: iptvTab(); break;
            default: settingsTab();
        }
        View first = firstCard;
        // at once (a wait showed the focus on the rail for a moment), and again after layout in case it could not take it yet
        if (App.tv && first != null && !skipFocus) {
            if (!first.requestFocus()) col.post(first::requestFocus);
            col.setFocusable(false);   // it only held the focus while the page was empty (selectTab)
        }
    }

    void buildHome() {
        heroBox = new FrameLayout(this);   // filled by the first poster row that has titles
        heroBox.setVisibility(View.GONE);
        col.addView(heroBox);
        if (!Update.available().isEmpty()) col.addView(updateLine(Update.available()));

        List<History.Entry> history = History.list();
        if (!history.isEmpty()) {
            col.addView(heading(App.t(R.string.home_continue_watching)));
            LinearLayout row = row();
            for (History.Entry e : history) row.addView(historyCard(e));
        }

        List<Sites.Site> favs = Sites.favorites();
        if (!favs.isEmpty()) {   // only once something is marked (Add a site is also in Settings, Sites)
            LinearLayout favHead = new LinearLayout(this);
            favHead.setGravity(Gravity.BOTTOM);
            favHead.addView(heading(App.t(R.string.home_favorites_heart)), new LinearLayout.LayoutParams(0, -2, 1));
            favHead.addView(iconButton(R.drawable.ic_delete, App.t(R.string.home_clear_all_favorites), v -> confirmClearFavorites(favs.size())));
            col.addView(favHead);
            LinearLayout favRow = row();
            for (Sites.Site s : favs) favRow.addView(siteCard(s, false));
            favRow.addView(addCard());
        }

        List<Sites.Site> own = Sites.custom();
        if (!own.isEmpty()) {
            col.addView(heading(App.t(R.string.home_my_sites)));
            LinearLayout row = row();
            for (Sites.Site s : own) row.addView(siteCard(s, false));
        }

        List<Catalog.Title> mine = Catalog.mine();
        if (!mine.isEmpty()) {
            col.addView(heading(App.t(R.string.home_my_list)));
            LinearLayout row = row();
            for (Catalog.Title t : mine) row.addView(posterCard(t, posterW()));
        }
        otherLanguages();
        String movies = App.t(R.string.home_tab_movies), shows = App.t(R.string.home_tab_tv), anime = App.t(R.string.home_tab_anime);
        // The viewer's own country first (its languages' most popular titles), then the world's
        String region = App.region();
        heroFrom = Catalog.languages(region).length > 0 ? "local=" + region : "genre=" + Catalog.NEW;
        if (Catalog.languages(region).length > 0) {
            String country = new java.util.Locale("", region).getDisplayCountry();
            titleRow(movies, country, "movie", "local=" + region, 1);
            titleRow(shows, country, "series", "local=" + region, 2);
        }
        // The rows below the first screen are added a frame later, so Home shows up at once
        final int id = built;
        col.post(() -> { if (id == built && tab == 0) lowerHome(movies, shows, anime); });
    }

    void lowerHome(String movies, String shows, String anime) {
        // Each kind: what just came out, then what everyone watches (anime: Kitsu's trending)
        String fresh = App.t(R.string.home_new), popular = App.t(R.string.home_popular);
        titleRow(movies, fresh, "movie", "genre=" + Catalog.NEW, 1);
        titleRow(movies, popular, "movie", "", 1);
        titleRow(shows, fresh, "series", "genre=" + Catalog.NEW, 2);
        titleRow(shows, popular, "series", "", 2);
        titleRow(anime, fresh, "series", Catalog.NEW_ANIME, 4);
        titleRow(anime, popular, "series", Catalog.TRENDING_ANIME, 4);

        List<Sites.Site> more = Sites.load().get(Cats.MORE);
        if (more != null) {
            col.addView(heading(App.t(R.string.home_more)));
            siteRow(Cats.MORE, more);
        }
    }

    /** The other-language site lists, above the movie rows. Chinese comes first: it is the one opened most here. */
    void otherLanguages() {
        Map<String, List<Sites.Site>> langs = Sites.languages();
        if (langs.isEmpty()) return;
        col.addView(heading(App.t(R.string.home_other_languages)));
        LinearLayout row = row();
        List<Sites.Site> zh = langs.get("Chinese");
        if (zh != null) row.addView(languageCard("Chinese", zh.size()));
        for (Map.Entry<String, List<Sites.Site>> l : langs.entrySet())
            if (!"Chinese".equals(l.getKey())) row.addView(languageCard(l.getKey(), l.getValue().size()));
    }

    FrameLayout heroBox;
    String heroFrom = "";   // the row whose titles the opening picture comes from: the local movies when there are any, else the new ones

    /** Home's opening picture: one of the first row's titles (another each day), wide, with its name and a way in. Shown once its picture is here. */
    void fillHero(List<Catalog.Title> list) {
        if (heroBox == null || heroBox.getChildCount() > 0 || list.isEmpty()) return;
        Catalog.Title t = list.get((int) (java.time.LocalDate.now().toEpochDay() % Math.min(5, list.size())));
        String art = Catalog.backdrop(t);
        if (art.isEmpty()) return;
        FrameLayout box = heroBox;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, u(205, 200));
        lp.setMargins(-side, -col.getPaddingTop(), -side, 0);   // edge to edge, up to the bar
        box.setLayoutParams(lp);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        int clear = BG & 0xFFFFFF;
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{clear | 0x66000000, clear | 0x33000000, BG}));
        box.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        if (App.tv) {   // wide screen: the words sit on a darker left side
            View left = new View(this);
            left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{clear | 0xD9000000, clear | 0x59000000, clear}));
            box.addView(left, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(side, 0, side, u(6, 4));
        TextView name = text(t.name, sp(30, 24), Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        info.addView(name);
        List<String> meta = new ArrayList<>();
        if (!t.year.isEmpty()) meta.add(t.year);
        if (!t.rating.isEmpty()) meta.add("\u2605 " + t.rating);
        if (!t.genres.isEmpty()) meta.add(t.genres);
        TextView line = text(TextUtils.join("    ", meta), sp(14, 12), 0xFFDAD5E3, false);
        line.setSingleLine();
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setPadding(0, dp(4), 0, 0);
        info.addView(line);
        if (App.tv && !t.overview.isEmpty()) {
            TextView about = text(t.overview, 14, 0xFFDAD5E3, false);
            about.setMaxLines(2);
            about.setEllipsize(TextUtils.TruncateAt.END);
            about.setMaxWidth(dp(520));
            about.setPadding(0, dp(6), 0, 0);
            info.addView(about, new LinearLayout.LayoutParams(-2, -2));
        }
        ImageView play = new ImageView(this);
        play.setImageResource(R.drawable.ic_pb_play);
        play.setColorFilter(Color.WHITE);
        play.setContentDescription(t.name);
        int pad = u(11, 10);
        play.setPadding(pad, pad, pad, pad);
        play.setBackground(rounded(ACCENT, dp(24)));
        focusable(play, dp(24));
        play.setOnClickListener(v -> openTitle(t));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(u(46, 42), u(46, 42));
        pl.topMargin = u(12, 10);
        info.addView(play, pl);
        box.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        box.setOnClickListener(v -> openTitle(t));
        // shown once the picture is here (from memory at once): an empty band would only push the page down
        View arrived = new View(this) {
            @Override public void setVisibility(int v) { box.setVisibility(View.VISIBLE); }
        };
        // loaded at once, not when "near the screen": the box is hidden until this very picture is here
        String key = art + "@1280";
        img.setTag(key);
        Bitmap hit = images.get(key);
        if (hit != null) show(img, hit, arrived); else startLoad(img, art, arrived, 1280, key);
    }

    void openTitle(Catalog.Title t) {
        startActivity(new Intent(this, TitleActivity.class).putExtra("id", t.id).putExtra("type", t.type).putExtra("name", t.name).putExtra("year", t.year)
            .putExtra("poster", t.poster).putExtra("anime", t.anime).putExtra("tmdb", t.tmdb));
    }

    /** One row of site cards. Picks come first; a long category shows its first batch and an "All" card for the rest. */
    void siteRow(String category, List<Sites.Site> sites) {
        LinearLayout row = row();
        boolean more = sites.size() > Sites.ROW_MAX + 2;
        for (Sites.Site s : more ? sites.subList(0, Sites.ROW_MAX) : sites) row.addView(siteCard(s, true));
        if (more) row.addView(allCard(category, sites.size()));
    }

    void confirmClearFavorites(int n) {
        confirm(App.t(R.string.home_clear_favorites_title), App.t(n == 1 ? R.string.home_clear_favorites_msg_one : R.string.home_clear_favorites_msg_many, n),
            App.t(R.string.home_clear_all), () -> { Sites.clearFavorites(); build(); toast(App.t(R.string.home_favorites_cleared)); });
    }

    // ---- voice input: the device's own speech recognizer (Google TV voice on a TV, the Google app on a phone) ----

    static final int REQ_VOICE = 77;
    java.util.function.Consumer<String> voiceDone;

    void listen(java.util.function.Consumer<String> then) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, App.t(R.string.home_voice_prompt))
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        voiceDone = then;
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            toast(App.t(R.string.home_no_voice));
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null || voiceDone == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) voiceDone.accept(said.get(0));
    }

    /** A mic-only chip next to Search. */
    TextView voiceChip(View.OnClickListener click) {
        TextView t = chip(click);
        iconize(t, R.drawable.ic_mic, TEXT);
        t.setCompoundDrawablePadding(0);
        t.setContentDescription(App.t(R.string.home_voice_search));
        return t;
    }

    void confirm(String title, String message, String yes, Runnable then) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(yes, (d, w) -> then.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- tabs ----

    static final int[] TABS = {R.string.home_tab_home, R.string.home_tab_movies, R.string.home_tab_tv, R.string.home_tab_sports, R.string.home_tab_anime,
        R.string.home_tab_iptv, R.string.home_tab_settings};
    static final int SETTINGS = 6;
    final List<TextView> tabViews = new ArrayList<>();
    static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_movie, R.drawable.ic_tv, R.drawable.ic_trophy, R.drawable.ic_sparkle, R.drawable.ic_live, R.drawable.ic_settings};
    static int tab;
    static final Map<Integer, String> genres = new HashMap<>();
    static String focusGenre;
    LinearLayout tabBar;
    ImageView gear;
    int built;
    boolean skipFocus;

    /** Search, category and title screens are pages on top of the tabs and have no tab bar. */
    boolean tabs() { return true; }

    /** Posters say Movie, TV Series or Anime (Search, where the kinds are mixed). */
    boolean kindBadges() { return false; }

    LinearLayout tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        if (App.tv) bar.setOrientation(LinearLayout.VERTICAL);
        else bar.setBackgroundColor(NAV);
        for (int i = 0; i < SETTINGS; i++) {   // Settings is the gear (top bar on a phone, foot of the rail on a TV)
            final int n = i;
            TextView t = text(App.t(TABS[i]), 11, MUTED, true);
            t.setContentDescription(t.getText());
            tabViews.add(t);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, dp(App.tv ? 6 : 8), 0, dp(App.tv ? 5 : 7));
            LinearLayout.LayoutParams lp = App.tv ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            if (App.tv) lp.setMargins(0, dp(1), 0, dp(1));
            t.setLayoutParams(lp);
            // One line: a long name ("Configuración") gets a smaller font instead of wrapping
            t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
                float room = r - l - dp(App.tv ? 6 : 14);   // clear of the neighbours and of a rounded screen corner
                if (room > 0 && t.getPaint().measureText(t.getText().toString()) > room && t.getTextSize() > dp(8))
                    t.post(() -> t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * room / t.getPaint().measureText(t.getText().toString())));
            });
            t.setBackground(rounded(0, dp(12)));
            focusable(t, dp(12));
            if (App.tv) t.setForeground(null);   // the focus ring is in the background (styleTabs): the shared one does not draw on these
            t.setOnClickListener(v -> selectTab(n));
            bar.addView(t);
        }
        return bar;
    }

    static final int RAIL = 72;   // dp

    /** The app's name, written like the project banner: FMHY in white, Play in the accent red. */
    TextView brand(float size) {
        TextView name = text("", size, TEXT, true);
        android.text.SpannableString s = new android.text.SpannableString("FMHYPlay");
        s.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 4, 8, 0);
        name.setText(s);
        name.setSingleLine();
        return name;
    }

    /** TV: logo, Search, the tabs, and at the foot WARP's state and Settings, down the left edge of every tab. */
    View rail() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setPadding(dp(7), dp(14), dp(7), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(30), dp(30));
        r.addView(logo, ll);
        TextView name = brand(10);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(2), 0, dp(8));
        r.addView(name);
        ImageView search = iconButton(R.drawable.ic_search, App.t(R.string.home_search), v -> startActivity(new Intent(this, SearchActivity.class)));
        search.setColorFilter(TEXT);
        search.setBackground(rounded(CARD, dp(22)));
        ((LinearLayout.LayoutParams) search.getLayoutParams()).setMargins(0, 0, 0, dp(6));
        r.addView(search);
        r.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));
        r.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        warpChip = chip(v -> toggleWarp());
        warpChip.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        r.addView(warpChip);
        updateChips();
        gear = iconButton(R.drawable.ic_settings, App.t(R.string.home_tab_settings), v -> selectTab(SETTINGS));
        ((LinearLayout.LayoutParams) gear.getLayoutParams()).setMargins(0, dp(6), 0, 0);
        r.addView(gear);
        ScrollView fit = new ScrollView(this);   // a short screen or big fonts: the rail scrolls instead of losing its foot
        fit.setFillViewport(true);
        fit.setVerticalScrollBarEnabled(false);
        fit.setBackgroundColor(NAV);
        fit.addView(r);
        railView = fit;
        return fit;
    }

    /** On at once; off only after a yes (one stray press on the shield would show sites the real address). */
    void toggleWarp() {
        if (Warp.state != Warp.State.ON && Warp.state != Warp.State.CONNECTING) {
            App.prefs.edit().putBoolean("warp", true).apply();
            Warp.start();
        } else confirm(App.t(R.string.home_warp_off_title), App.t(R.string.home_warp_off_msg), App.t(R.string.home_turn_off), () -> {
            App.prefs.edit().putBoolean("warp", false).apply();
            Warp.stop();
        });
    }

    /** Every site's cookies (so logins), saved data and cached pages. */
    void clearWebData() {
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        android.webkit.WebStorage.getInstance().deleteAllData();
        android.webkit.WebView w = new android.webkit.WebView(getApplicationContext());   // the cache is cleared through any WebView
        w.clearCache(true);
        w.destroy();
        toast(App.t(R.string.home_web_cleared));
    }

    /** The open tab is bright; the others stay quiet. */
    void styleTabs() {
        if (tabBar == null) return;
        if (gear != null) {
            gear.setColorFilter(tab == SETTINGS ? TEXT : MUTED);
            gear.setBackground(rounded(tab == SETTINGS ? CARD_HI : CARD, dp(22)));
        }
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == tab;
            t.setTextColor(on ? TEXT : MUTED);
            android.graphics.drawable.Drawable d = getDrawable(TAB_ICONS[i]).mutate();
            d.setBounds(0, 0, dp(22), dp(22));
            d.setTint(on ? TEXT : MUTED);
            t.setCompoundDrawablesRelative(null, d, null, null);
            t.setCompoundDrawablePadding(dp(3));
            if (App.tv) {
                GradientDrawable idle = rounded(on ? CARD_HI : 0, dp(12)), focused = rounded(CARD_HI, dp(12));
                focused.setStroke(dp(2), Color.WHITE);
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_focused}, focused);
                bg.addState(new int[0], idle);
                t.setBackground(bg);
            }
        }
    }

    void selectTab(int n) {
        if (!tabs()) {   // from a page on top of the tabs: Home, on that tab
            tab = n;
            goHome(this);
            return;
        }
        if (n == tab) return;
        tab = n;
        // The pressed tab lights up and the old page goes at once; the new page is drawn on the next frame
        styleTabs();
        col.removeAllViews();
        page.scrollTo(0, 0);
        if (App.tv) {   // the focus leaves the rail at this press: the page holds it, unseen, until its first card is there
            col.setFocusable(true);
            col.setDefaultFocusHighlightEnabled(false);
            col.requestFocus();
        }
        col.post(() -> {
            if (tab != n) return;
            build();   // on a TV the remote lands on the tab's first card (build does it), not left behind on the rail
        });
    }

    @Override public void onBackPressed() {
        if (tabs() && tab != 0) selectTab(0);
        else super.onBackPressed();
    }

    /** Poster width in dp for a row of posters. */
    int posterW() { return App.tv ? 122 : 108; }

    View note(String s) {
        TextView t = text(s, sp(15, 13), MUTED, false);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    /** A phone's top bar on every tab: the logo, Search filling the middle, voice, WARP's state and Settings. Nothing in it can grow past the screen. */
    View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setPadding(dp(10), 0, dp(4), 0);   // the status bar above already leaves room
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(28), dp(28));
        ll.rightMargin = dp(6);
        bar.addView(logo, ll);
        TextView name = brand(17);
        name.setPadding(0, 0, dp(10), 0);
        bar.addView(name);
        // One search for everything, from every tab: movies, series, IPTV channels and sites
        TextView search = chip(v -> startActivity(new Intent(this, SearchActivity.class)));
        search.setText(App.t(R.string.home_search));
        search.setSingleLine(true);
        search.setEllipsize(TextUtils.TruncateAt.END);
        iconize(search, R.drawable.ic_search, MUTED);
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) search.getLayoutParams();
        sl.width = 0;
        sl.weight = 1;   // whatever the icons leave
        bar.addView(search);
        bar.addView(voiceChip(v -> startActivity(new Intent(this, SearchActivity.class).putExtra("voice", true))));
        warpChip = chip(v -> toggleWarp());
        bar.addView(warpChip);
        updateChips();
        gear = iconButton(R.drawable.ic_settings, App.t(R.string.home_tab_settings), v -> selectTab(SETTINGS));
        ((LinearLayout.LayoutParams) gear.getLayoutParams()).setMargins(0, 0, dp(6), 0);
        bar.addView(gear);
        for (int i = 0; i < bar.getChildCount(); i++) {   // chips keep 2 dp above and below here, not their usual 4
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) bar.getChildAt(i).getLayoutParams();
            p.topMargin = p.bottomMargin = dp(2);
        }
        return bar;
    }

    /** A poster row that draws at once from disk if it can, else fills in when the catalog answers. */
    void titleRow(String head, String sub, String type, String extra, int moreTab) {
        View h = heading(head, sub);
        col.addView(h);
        LinearLayout row = row();
        final int id = built;
        boolean local = extra.startsWith("local=");   // a bonus row: gone when its source is down, no error line
        List<Catalog.Title> hit = Catalog.cached(type, extra);
        if (hit != null && !(local && hit.isEmpty())) { fillPosters(row, hit, extra, moreTab); return; }
        skeleton(row, posterW());
        App.io.execute(() -> {
            List<Catalog.Title> list = Catalog.list(type, extra);
            runOnUiThread(() -> {
                if (id != built) return;
                row.removeAllViews();
                if (list != null && !list.isEmpty()) fillPosters(row, list, extra, moreTab);
                else if (local) {
                    col.removeView(h);
                    col.removeView((View) row.getParent());
                    List<Catalog.Title> world = Catalog.cached("movie", "genre=" + Catalog.NEW);
                    if (extra.equals(heroFrom) && moreTab == 1 && world != null) fillHero(world);
                }
                else row.addView(note(App.t(R.string.home_catalog_offline)));
            });
        });
    }

    /** Quiet blocks where a row's posters will be, so the page does not jump when they arrive. */
    void skeleton(LinearLayout row, int w) {
        for (int i = 0; i < 8; i++) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = cardParams(w, w * 3 / 2);
            lp.bottomMargin = u(40, 36);   // the caption's room
            v.setLayoutParams(lp);
            v.setBackground(rounded(CARD, dp(10)));
            row.addView(v);
        }
    }

    /** `extra` "genre=X": More opens the tab on that genre chip. moreTab -1: no More (the row is on that tab already). */
    void fillPosters(LinearLayout row, List<Catalog.Title> list, String extra, int moreTab) {
        if (extra.equals(heroFrom) && moreTab == 1) fillHero(list);   // the movies row
        for (Catalog.Title t : list.subList(0, Math.min(14, list.size()))) row.addView(posterCard(t, posterW()));
        if (moreTab < 0) return;
        TextView more = text(App.t(R.string.home_more_arrow), sp(17, 15), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setLayoutParams(cardParams(posterW(), posterW() * 3 / 2));
        more.setBackground(rounded(CARD, dp(10)));
        focusable(more, dp(10));
        more.setOnClickListener(v -> {
            if (extra.isEmpty() || extra.startsWith("genre=")) genres.put(moreTab, extra.replaceFirst("^genre=", ""));
            else if (extra.startsWith("local=")) genres.put(moreTab, Catalog.LANG + Catalog.languages(extra.substring(6))[0]);
            selectTab(moreTab);
        });
        row.addView(more);
    }

    /** Movies, TV and Anime: the sites that carry them, then the native catalog (genre chips, posters, load more). */
    void catalogTab(String type, String category, String fixedGenre) {
        List<Sites.Site> sites = Sites.load().get(category);
        if (sites != null) {
            // One slim line of sites (icon and name), so the posters start high on the screen
            cardW = App.tv ? 176 : 138;
            cardH = App.tv ? 50 : 42;
            siteRow(category, sites);
            cardW = App.tv ? 220 : 156;
            cardH = App.tv ? 92 : 62;
        }
        if (category.equals(Cats.ANIME)) titleRow(App.t(R.string.home_new_anime), "", "series", Catalog.NEW_ANIME, -1);
        String genre = fixedGenre.isEmpty() ? genres.getOrDefault(tab, firstList()) : fixedGenre;
        // The chips say which list this is; only the Anime tab has one fixed list, under its own heading
        col.addView(fixedGenre.isEmpty() ? genreChips() : heading(App.t(R.string.home_popular_animation)));
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);
        loadPage(type, genre, 0, holder, new Grid(holder, App.tv ? 8 : 3), built);
    }

    /** What Movies and TV open on: the viewer's own first language when their country has lists, else New. */
    static String firstList() {
        String[] langs = Catalog.languages(App.region());
        return langs.length > 0 && !localDown ? Catalog.LANG + langs[0] : Catalog.NEW;
    }

    static boolean localDown;   // the language lists could not be reached: open on New until the app restarts

    View genreChips() {
        LinearLayout chips = new LinearLayout(this);
        chips.setClipChildren(false);
        String now = genres.getOrDefault(tab, firstList());
        // The viewer's own languages first, then New, Popular and the genres (Cinemeta's own keys, as they are)
        List<String> all = new ArrayList<>();
        for (String l : Catalog.languages(App.region())) all.add(Catalog.LANG + l);
        all.add(Catalog.NEW);
        all.add("");
        all.addAll(java.util.Arrays.asList(Catalog.GENRES));
        for (String g : all) {
            TextView c = chip(v -> { genres.put(tab, g); focusGenre = g; build(); });
            c.setText(g.isEmpty() ? App.t(R.string.home_popular) : g.equals(Catalog.NEW) ? App.t(R.string.home_new) : g.startsWith(Catalog.LANG) ? g.substring(Catalog.LANG.length()) : g);
            c.setTextColor(g.equals(now) ? BG : TEXT);
            c.setBackground(rounded(g.equals(now) ? TEXT : CARD, dp(22)));
            focusable(c, dp(22));
            if (App.tv && g.equals(focusGenre)) { focusGenre = null; c.post(c::requestFocus); }
            chips.addView(c);
        }
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        sc.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        sc.setLayoutParams(lp);
        sc.setPadding(slack, u(12, 6), slack, u(6, 4));
        sc.addView(chips);
        return sc;
    }

    /** Adds one page of posters (50 titles) to the grid, then a "Load more" button. `id` drops answers for a screen that was rebuilt. */
    void loadPage(String type, String genre, int skip, LinearLayout holder, Grid grid, int id) {
        String extra = (genre.isEmpty() ? "" : "genre=" + genre) + (genre.isEmpty() || skip == 0 ? "" : "&") + (skip == 0 ? "" : "skip=" + skip);
        List<Catalog.Title> hit = Catalog.cached(type, extra);
        if (hit != null) { showPage(type, genre, skip, holder, grid, id, hit); return; }
        View loading = note(App.t(R.string.home_loading));
        holder.addView(loading);
        App.io.execute(() -> {
            List<Catalog.Title> list = Catalog.list(type, extra);
            runOnUiThread(() -> {
                if (id != built) return;
                holder.removeView(loading);
                if (list == null && genre.startsWith(Catalog.LANG) && !genres.containsKey(tab) && !localDown) {
                    localDown = true;   // the default list is down: New instead of an error on the tab's first screen
                    build();
                    return;
                }
                if (list == null || list.isEmpty()) {
                    TextView retry = text(App.t(list == null ? R.string.home_catalog_retry : R.string.home_nothing_here), sp(15, 13), TEXT, true);
                    retry.setPadding(dp(14), dp(12), dp(14), dp(12));
                    retry.setBackground(rounded(CARD, dp(14)));
                    focusable(retry, dp(14));
                    retry.setOnClickListener(v -> { holder.removeView(retry); loadPage(type, genre, skip, holder, grid, id); });
                    holder.addView(retry);
                } else showPage(type, genre, skip, holder, grid, id, list);
            });
        });
    }

    void showPage(String type, String genre, int skip, LinearLayout holder, Grid grid, int id, List<Catalog.Title> list) {
        // Kitsu lists each season on its own under one IMDb id: a page of 20 can come back shorter, and a show can come again
        boolean anime = genre.equals(Catalog.POPULAR_ANIME);
        @SuppressWarnings("unchecked") java.util.Set<String> seen = holder.getTag() instanceof java.util.Set ? (java.util.Set<String>) holder.getTag() : new java.util.HashSet<>();
        holder.setTag(seen);
        for (Catalog.Title t : list) if (seen.add(t.id)) grid.add(posterCard(t, grid.cell));
        if (list.isEmpty() || (!anime && list.size() < 20) || genre.equals(Catalog.NEW)) return;   // New is one list
        TextView more = text(App.t(R.string.home_load_more), sp(16, 14), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(14), 0, dp(14));
        more.setBackground(rounded(CARD_HI, dp(14)));
        focusable(more, dp(14));
        more.setOnClickListener(v -> { holder.removeView(more); loadPage(type, genre, skip + (anime ? 20 : list.size()), holder, grid, id); });
        holder.addView(more);
    }

    void sportsTab() {
        for (String cat : new String[]{Cats.SPORTS, Cats.LIVE}) {
            List<Sites.Site> sites = Sites.load().get(cat);
            if (sites == null) continue;
            col.addView(heading(App.t(cat.equals(Cats.LIVE) ? R.string.home_live_tv_count : R.string.home_sports_sites_count, sites.size())));
            Grid grid = new Grid(col);
            for (Sites.Site s : sites) grid.add(s);
        }
    }

    /**
     * IPTV, in the order people reach for channels: what you watched, your stars, your own playlists and pins,
     * your country's channels, then every category and country as a compact list.
     */
    void iptvTab() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        List<Iptv.Channel> all = Iptv.all();
        String status = all.isEmpty() ? App.t(Iptv.loading ? R.string.home_loading_channels : R.string.home_no_channels) : Iptv.statusLine();
        if (status.isEmpty() && App.tv && !Iptv.history().isEmpty()) status = App.t(R.string.home_hint_hold_channel);
        TextView note = text(status, sp(13, 12), MUTED, false);
        h.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = chip(v -> addPlaylistDialog());   // channels are in Search, with movies and sites
        add.setText(App.t(R.string.home_add_playlist_chip));
        add.setBackground(rounded(CARD, dp(22)));
        h.addView(add);
        col.addView(h);
        if (all.isEmpty()) {
            if (!Iptv.loading) Warp.whenSettled(() -> App.io.execute(() -> {
                boolean ok = Iptv.refresh();
                runOnUiThread(() -> { if (ok && tab == 5) build(); });
            }));
            return;
        }
        int cols = App.tv ? 8 : 3;
        channelRow(App.t(R.string.home_continue_watching), Iptv.history(), cols, "history", 99, null);
        channelRow(App.t(R.string.home_favorites), Iptv.favorites(), cols, "fav", 14, v -> openIptv("fav", "", App.t(R.string.home_favorites), false));
        // What you added yourself: a row per playlist under the name you gave it, single stream links together
        List<String> keys = new ArrayList<>(Iptv.mine());
        keys.add(Iptv.STREAMS);
        for (String key : keys) {
            String title = key.equals(Iptv.STREAMS) ? groupName(key) : Iptv.playlistName(key);
            List<Iptv.Channel> own = Iptv.list("mine", key);
            if (!key.equals(Iptv.STREAMS)) own.removeIf(c -> c.group.equals(Iptv.STREAMS));
            channelRow(title, own, cols, "mine", 14, v -> openIptv("mine", key, title, false));
        }
        // Pinned lists (a country or category with its chips), each with an All tile that opens it filtered
        for (Iptv.Pin p : Iptv.pins()) {
            String title = p.title + (p.cat.isEmpty() ? "" : ", " + groupName(p.cat)) + (p.lang.isEmpty() ? "" : ", " + p.lang);
            channelRow("\uD83D\uDCCC  " + title, Iptv.filter(Iptv.list(p.type, p.key), p.cat, p.lang), cols, "pin", 0,
                v -> startActivity(new Intent(this, IptvActivity.class).putExtra("type", p.type).putExtra("key", p.key)
                    .putExtra("title", p.title).putExtra("cat", p.cat).putExtra("lang", p.lang)));
        }
        // The viewer's own country, as on Home
        String region = App.region(), country = region.isEmpty() ? "" : Iptv.country(region.toLowerCase(java.util.Locale.ROOT));
        if (!country.isEmpty()) channelRow(Iptv.flag(country) + "  " + country, Iptv.list("country", country), cols, "country", 0, v -> openIptv("country", country, country, false));
        // Categories and countries (a few hundred cards) come a frame later, so the tab shows up at once
        final int id = built;
        col.post(() -> { if (id == built && tab == 5) iptvGroups(country); });
    }

    /** A row of channel tiles under its name and count. The All tile shows from `allFrom` channels up (0: always), when there is somewhere to go. */
    void channelRow(String title, List<Iptv.Channel> list, int cols, String kind, int allFrom, View.OnClickListener all) {
        if (list.isEmpty()) return;
        col.addView(heading(title, String.valueOf(list.size())));
        LinearLayout r = row();
        for (Iptv.Channel c : list.subList(0, Math.min(kind.equals("history") ? list.size() : 14, list.size()))) r.addView(channelCard(c, cols, kind));
        if (all == null || list.size() <= allFrom) return;
        TextView more = text(App.t(R.string.home_all_count, list.size()), sp(14, 13), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
        more.setBackground(rounded(CARD, dp(14)));
        focusable(more, dp(14));
        more.setOnClickListener(all);
        r.addView(more);
    }

    /** The viewer's country by category (under its channel row), then every country, then every category, as slim cards: icon, name, how many. */
    void iptvGroups(String own) {
        int cols = App.tv ? 5 : 2;
        Map<String, List<Iptv.Channel>> byCat = Iptv.groups("cat"), by = Iptv.groups("country");
        List<Iptv.Channel> mine = by.get(own);
        if (mine != null) {
            Grid local = new Grid(col, cols);
            for (String cat : byCat.keySet()) {   // the categories' own order
                int n = 0;
                for (Iptv.Channel c : mine) if (java.util.Arrays.asList(c.cats).contains(cat)) n++;
                if (n == 0) continue;
                View card = groupCard(local.cell, Iptv.emoji(cat), groupName(cat), n, "country", own);
                card.setOnClickListener(v -> startActivity(new Intent(this, IptvActivity.class).putExtra("type", "country").putExtra("key", own)
                    .putExtra("title", own).putExtra("cat", cat)));
                local.add(card);
            }
        }
        col.addView(heading(App.t(R.string.home_countries)));
        Grid countries = new Grid(col, cols);
        if (mine != null) countries.add(groupCard(countries.cell, Iptv.flag(own), own, mine.size(), "country", own));
        for (Map.Entry<String, List<Iptv.Channel>> e : by.entrySet()) if (!e.getKey().equals(own)) countries.add(groupCard(countries.cell, Iptv.flag(e.getKey()), e.getKey(), e.getValue().size(), "country", e.getKey()));
        col.addView(heading(App.t(R.string.home_categories)));
        Grid cats = new Grid(col, cols);
        int working = Iptv.working().size();
        if (working > 0) cats.add(groupCard(cats.cell, "\u2705", App.t(R.string.home_working_channels), working, "working", ""));
        for (Map.Entry<String, List<Iptv.Channel>> e : byCat.entrySet()) cats.add(groupCard(cats.cell, Iptv.emoji(e.getKey()), groupName(e.getKey()), e.getValue().size(), "cat", e.getKey()));
    }

    View groupCard(int w, String icon, String label, int n, String type, String key) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(w, App.tv ? 48 : 46));
        card.setPadding(u(14, 12), 0, u(14, 12), 0);
        card.addView(text(icon, sp(19, 18), TEXT, false));
        TextView name = text(label, sp(14, 13), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(u(12, 10), 0, dp(8), 0);
        card.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(text(String.valueOf(n), sp(12, 11), MUTED, false));
        card.setBackground(rounded(CARD, dp(12)));
        focusable(card, dp(12));
        card.setTag("g" + type + key);
        if (firstCard == null) firstCard = card;
        card.setOnClickListener(v -> openIptv(type, key, label, false));
        return card;
    }

    /** Tile edge in dp for a row of `cols` square tiles. */
    int squareDp(int cols) {
        int gap = dp(gapDp);
        return Math.round((winW() - 2 * side - gap * (cols - 1)) / (float) cols / getResources().getDisplayMetrics().density) - 1;
    }

    int builtW;   // the window width the tiles were last sized for

    /** The width the page has: the window's, less the rail on a TV. */
    int winW() {
        builtW = getResources().getDisplayMetrics().widthPixels;
        return builtW - (App.tv ? dp(RAIL) : 0);
    }

    /**
     * The phone turned (a fullscreen video, a page in landscape): tiles sized for the old width are drawn again. Coming back from a
     * landscape player, onResume can draw while the window is still landscape, and the turn back to portrait arrives after it.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (builtW != 0 && col != null && getResources().getDisplayMetrics().widthPixels != builtW) build();
    }

    /** Back to Home from any screen in one step: everything above it closes (the position is saved as each one stops). */
    static Runnable holdRun;
    static boolean held;   // a Back hold already sent us Home: the release must not go back one more screen

    /**
     * Holding Back for 3 seconds goes Home from any screen (a remote has no Home button on screen). Call it first in dispatchKeyEvent;
     * true means the key is used up (the release that ends a hold). A normal press is left to the screen.
     */
    static boolean holdBack(Activity a, android.view.KeyEvent e) {
        if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return false;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0 && a.getClass() != Home.class) {
                App.main.removeCallbacks(holdRun);
                held = false;
                holdRun = () -> { held = true; Toast.makeText(App.app, App.t(R.string.home_going), Toast.LENGTH_SHORT).show(); goHome(a); };
                App.main.postDelayed(holdRun, 3000);
            }
            return held;
        }
        App.main.removeCallbacks(holdRun);
        boolean was = held;
        held = false;
        return was;
    }

    static void goHome(Activity a) {
        a.startActivity(new Intent(a, Home.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    View channelCard(Iptv.Channel c, int cols, String row) {
        int px = dp(squareDp(cols));
        View t = channelTile(c, null, px);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
        lp.setMargins(0, 0, dp(gapDp), 0);
        t.setLayoutParams(lp);
        focusable(t, dp(14));
        t.setTag("c" + c.url);
        if (firstCard == null) firstCard = t;
        t.setOnClickListener(v -> Iptv.play(this, c));
        // Hold OK (or press Menu): take it off this row, or star it
        t.setOnLongClickListener(v -> {
            List<String> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            if (row.equals("history")) { items.add(App.t(R.string.home_remove_continue)); actions.add(() -> Iptv.removeFromHistory(c.url)); }
            if (row.equals("working")) { items.add(App.t(R.string.home_remove_working)); actions.add(() -> Iptv.forgetWorking(c.url)); }
            items.add(App.t(Iptv.isFavorite(c) ? R.string.home_remove_favorite : R.string.home_add_favorite));
            actions.add(() -> Iptv.toggleFavorite(c));
            new AlertDialog.Builder(this).setTitle(c.name).setItems(items.toArray(new String[0]), (d, i) -> { actions.get(i).run(); build(); }).show();
            return true;
        });
        return t;
    }

    /** A square channel tile: logo (or its first letter) over the name and group. Reused by the channel grid. */
    View channelTile(Iptv.Channel c, View reuse, int size) {
        FrameLayout t, box;
        LinearLayout info;
        TextView letter, name, sub;
        ImageView logo;
        if (reuse == null) {
            t = new FrameLayout(this);
            t.setBackground(rounded(CARD, dp(14)));
            box = new FrameLayout(this);
            letter = text("", sp(26, 24), MUTED, true);
            letter.setGravity(Gravity.CENTER);
            box.addView(letter, new FrameLayout.LayoutParams(-1, -1));
            logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(14), dp(12), dp(14), dp(4));
            box.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            t.addView(box, new FrameLayout.LayoutParams(-1, size * 11 / 20, Gravity.TOP));
            info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            info.setPadding(dp(8), 0, dp(8), dp(8));
            name = text("", sp(14, 12), TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            sub = text("", sp(11, 10), MUTED, false);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);
            info.addView(sub);
            t.addView(info, new FrameLayout.LayoutParams(-1, size * 9 / 20, Gravity.BOTTOM));
        } else {
            t = (FrameLayout) reuse;
            box = (FrameLayout) t.getChildAt(0);
            letter = (TextView) box.getChildAt(0);
            logo = (ImageView) box.getChildAt(1);
            info = (LinearLayout) t.getChildAt(1);
            name = (TextView) info.getChildAt(0);
            sub = (TextView) info.getChildAt(1);
        }
        box.setBackground(rounded(CARD_HI, dp(14)));   // logos sit on one quiet tone, not a colour per channel
        letter.setText(c.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        letter.setVisibility(View.VISIBLE);
        logo.setImageDrawable(null);
        logo.setTag(null);
        if (c.logo.startsWith("http")) loadImage(logo, c.logo, letter, 200);
        name.setText((Iptv.isFavorite(c) ? "\u2665 " : "") + c.name);
        List<String> cats = new ArrayList<>();
        for (String k : c.cats) cats.add(groupName(k));
        StringBuilder s = new StringBuilder(TextUtils.join(", ", cats));
        if (c.adult && !c.source.isEmpty()) s = new StringBuilder(c.source);   // adult channels: which list they came from
        else if (c.cats[0].equals(Iptv.MINE) && !c.group.isEmpty()) s = new StringBuilder(groupName(c.group));   // your own playlists: their own group names
        else if (!c.country.isEmpty()) s.append("  \u00B7  ").append(c.country);
        sub.setText(s);
        return t;
    }

    /** IPTV group keys the app makes up itself, in the viewer's language (the key stays English); playlist group names pass through. */
    static String groupName(String key) {
        switch (key) {
            case Iptv.ADULT_NAME: return App.t(R.string.home_group_mature);
            case Iptv.MINE: return App.t(R.string.home_group_my_playlists);
            case Iptv.STREAMS: return App.t(R.string.home_my_streams);
            case "Other": return App.t(R.string.home_group_other);
            case "Live": return App.t(R.string.home_group_live);
            default: return key;
        }
    }

    /** Set (or clear) the link to your own extra source list, then fetch it. */
    void extrasDialog() {
        EditText in = WebActivity.field(this, "https://example.com/sources.txt", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(Iptv.extrasUrl());
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_extras_title)).setMessage(App.t(R.string.home_extras_msg)).setView(WebActivity.box(this, in))
            .setPositiveButton(App.t(R.string.home_save), (d, w) -> {
                App.prefs.edit().putString("extrasUrl", in.getText().toString().trim()).apply();
                toast(App.t(R.string.home_fetching));
                Warp.whenSettled(() -> App.io.execute(() -> {
                    boolean ok = Iptv.refresh();
                    runOnUiThread(() -> { toast(App.t(ok ? R.string.home_source_updated : R.string.home_list_offline)); build(); });
                }));
            })
            .setNeutralButton(App.t(R.string.home_default), (d, w) -> {
                App.prefs.edit().remove("extrasUrl").apply();
                Warp.whenSettled(() -> App.io.execute(() -> {
                    Iptv.refresh();
                    runOnUiThread(this::build);
                }));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Ask for an M3U link and a name, download it, and give its channels their own row on the IPTV tab. */
    void addPlaylistDialog() {
        EditText in = WebActivity.field(this, "https://example.com/playlist.m3u", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = WebActivity.field(this, App.t(R.string.home_playlist_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        TextView stalker = chip(null);   // the dialog's three buttons are taken: Stalker sits under the fields
        stalker.setText(App.t(R.string.stalker_button));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(App.t(R.string.home_add_playlist_title)).setMessage(App.t(R.string.home_add_playlist_msg)).setView(WebActivity.box(this, in, name, stalker))
            .setPositiveButton(App.t(R.string.home_add), (d, w) -> {
                String url = in.getText().toString().trim(), label = name.getText().toString();
                if (url.isEmpty()) return;
                toast(App.t(R.string.home_downloading_playlist));
                Warp.whenSettled(() -> App.io.execute(() -> {
                    String err = Iptv.addPlaylist(url, label);
                    runOnUiThread(() -> {
                        toast(err == null ? App.t(R.string.home_added_iptv) : err);
                        build();
                    });
                    if (err == null) Iptv.sweep();
                }));
            })
            .setNeutralButton(App.t(R.string.home_xtream_login), (d, w) -> xtreamDialog())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        stalker.setOnClickListener(v -> { dlg.dismiss(); stalkerDialog(); });
    }

    /** A Stalker (Ministra) portal: its address and the MAC address the provider registered. Its live channels get their own row. */
    void stalkerDialog() {
        EditText name = WebActivity.field(this, App.t(R.string.home_xtream_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText portal = WebActivity.field(this, App.t(R.string.stalker_portal_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText mac = WebActivity.field(this, App.t(R.string.stalker_mac_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        mac.setText("00:1A:79:");   // MAG boxes' prefix, which most providers register
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(App.t(R.string.stalker_title)).setMessage(App.t(R.string.stalker_msg))
            .setView(WebActivity.box(this, name, portal, mac))
            .setPositiveButton(App.t(R.string.home_add), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        // Set here so a wrong MAC keeps the dialog (and what was typed) open
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (portal.getText().toString().trim().isEmpty()) { portal.requestFocus(); return; }
            String url = Stalker.account(portal.getText().toString(), mac.getText().toString());
            if (url == null) { mac.setError(App.t(R.string.stalker_bad_mac)); mac.requestFocus(); return; }
            dlg.dismiss();
            toast(App.t(R.string.home_signing_in));
            Warp.whenSettled(() -> App.io.execute(() -> {
                String err = Iptv.addPlaylist(url, name.getText().toString());
                runOnUiThread(() -> {
                    toast(err == null ? App.t(R.string.home_channels_added) : err);
                    build();
                });
            }));
        });
    }

    /** The DNS servers WARP asks first for Cloudflare's sign-up address (a network that blocks DNS: type your ISP's, from the router). */
    void warpDnsDialog() {
        EditText field = WebActivity.field(this, App.t(R.string.warp_dns_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(App.prefs.getString("warpDns", ""));
        new AlertDialog.Builder(this).setTitle(App.t(R.string.warp_dns_title)).setMessage(App.t(R.string.warp_dns_msg))
            .setView(WebActivity.box(this, field))
            .setPositiveButton(App.t(R.string.warp_dns_save), (d, w) -> saveWarpDns(field.getText().toString()))
            .setNeutralButton(App.t(R.string.warp_dns_auto), (d, w) -> saveWarpDns(""))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Keeps only valid-looking IPv4/IPv6 addresses, then starts WARP again with them (when it is switched on). */
    void saveWarpDns(String typed) {
        StringBuilder ok = new StringBuilder();
        for (String ip : typed.split("[,;\\s]+")) if (ip.matches("[0-9a-fA-F:.]{3,45}") && (ip.contains(".") || ip.contains(":"))) ok.append(ok.length() > 0 ? "," : "").append(ip);
        App.prefs.edit().putString("warpDns", ok.toString()).apply();
        if (App.prefs.getBoolean("warp", true)) { Warp.stop(); Warp.start(); }
        build();
    }

    /** An IPTV provider's Xtream Codes login (server, username, password): its live channels get their own row. */
    void xtreamDialog() {
        EditText name = WebActivity.field(this, App.t(R.string.home_xtream_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText server = WebActivity.field(this, App.t(R.string.home_xtream_server_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = WebActivity.field(this, App.t(R.string.home_username), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = WebActivity.field(this, App.t(R.string.home_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_xtream_login)).setMessage(App.t(R.string.home_xtream_msg))
            .setView(WebActivity.box(this, name, server, user, pass))
            .setPositiveButton(App.t(R.string.home_add), (d, w) -> {
                if (server.getText().toString().trim().isEmpty() || user.getText().toString().trim().isEmpty()) return;
                String url = Iptv.xtreamUrl(server.getText().toString(), user.getText().toString(), pass.getText().toString());
                toast(App.t(R.string.home_signing_in));
                Warp.whenSettled(() -> App.io.execute(() -> {
                    String err = Iptv.addPlaylist(url, name.getText().toString());
                    runOnUiThread(() -> {
                        toast(err == null ? App.t(R.string.home_channels_added) : err);
                        build();
                    });
                }));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    void openIptv(String type, String key, String title, boolean typing) {
        openIptv(type, key, title, typing, false);
    }

    void openIptv(String type, String key, String title, boolean typing, boolean voice) {
        startActivity(new Intent(this, IptvActivity.class).putExtra("type", type).putExtra("key", key).putExtra("title", title).putExtra("typing", typing).putExtra("voice", voice));
    }

    /** One line: a newer version exists, with Install and Later. */
    View updateLine(String version) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(u(18, 14), u(6, 4), u(8, 6), u(6, 4));
        line.setBackground(rounded(CARD, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        line.setLayoutParams(lp);
        line.addView(text(App.t(R.string.home_update_available, version), sp(15, 13), TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView install = chip(null);
        TextView news = chip(v -> whatsNew(version, () -> { if (install.isEnabled()) install.performClick(); }));
        news.setText(App.t(R.string.home_whats_new));
        install.setText(App.t(R.string.home_install));
        install.setBackground(rounded(ACCENT, dp(22)));
        install.setOnClickListener(v -> {
            install.setEnabled(false);
            Update.install(status -> {
                boolean failed = status.startsWith(App.t(R.string.update_failed, ""));
                install.setText(failed ? App.t(R.string.home_install) : status);
                install.setEnabled(failed);
                if (failed) toast(status);
            });
        });
        TextView later = chip(v -> { Update.skip(version); build(); });
        later.setText(App.t(R.string.home_later));
        later.setTextColor(MUTED);
        if (!Update.notes().isEmpty()) line.addView(news);
        line.addView(install);
        line.addView(later);
        if (firstCard == null) firstCard = install;
        return line;
    }

    void settingsTab() {
        single(R.drawable.ic_home);
        TextView regionValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_region), App.t(R.string.home_region_help), regionValue, v -> regionDialog()));
        String region = App.region();
        boolean local = Catalog.languages(region).length > 0;
        regionValue.setText(local ? new java.util.Locale("", region).getDisplayCountry() : App.t(App.prefs.getString("region", "").equals("-") ? R.string.home_off : R.string.warp_dns_auto));
        regionValue.setTextColor(local ? GREEN : MUTED);
        section(App.t(R.string.home_connection), R.drawable.ic_shield);
        warpValue = new TextView(this);
        secBox.addView(settingRow("WARP", App.t(R.string.home_warp_help), warpValue, v -> toggleWarp()));
        TextView dnsValue = new TextView(this);
        String savedDns = App.prefs.getString("warpDns", "");
        secBox.addView(settingRow(App.t(R.string.warp_dns_title), App.t(R.string.warp_dns_help), dnsValue, v -> warpDnsDialog()));
        dnsValue.setText(savedDns.isEmpty() ? App.t(R.string.warp_dns_auto) : savedDns);
        dnsValue.setTextColor(savedDns.isEmpty() ? MUTED : GREEN);
        adValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_ad_block), App.t(R.string.home_ad_block_help), adValue, v -> {
            App.prefs.edit().putBoolean("adblock", !AdBlock.enabled()).apply();
            updateChips();
        }));
        if (App.tv) {
            // Casts from phones open the player by themselves only with "Display over other apps" (Android blocks a closed app otherwise)
            TextView castValue = new TextView(this);
            boolean ready = Cast.canOpen(this);
            secBox.addView(settingRow(App.t(R.string.home_cast_title), App.t(ready ? R.string.home_cast_help_ready : R.string.home_cast_help_allow, Cast.tvName(this)), castValue, v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                }
            }));
            castValue.setText(App.t(ready ? R.string.home_ready : R.string.home_allow));
            castValue.setTextColor(ready ? GREEN : ACCENT);
        }
        TextView urlValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_address_bar), App.t(App.tv ? R.string.home_address_bar_help_tv : R.string.home_address_bar_help_phone), urlValue, v -> {
            App.prefs.edit().putBoolean("urlbar", !App.prefs.getBoolean("urlbar", false)).apply();
            build();
        }));
        urlValue.setText(App.t(App.prefs.getBoolean("urlbar", false) ? R.string.home_on : R.string.home_off));
        urlValue.setTextColor(App.prefs.getBoolean("urlbar", false) ? GREEN : MUTED);
        if (App.tv) {
            TextView cursor = new TextView(this);
            secBox.addView(settingRow(App.t(R.string.home_cursor_on_sites), App.t(R.string.home_cursor_help), cursor, v -> {
                App.prefs.edit().putBoolean("pointer", !App.prefs.getBoolean("pointer", true)).apply();
                build();
            }));
            boolean on = App.prefs.getBoolean("pointer", true);
            cursor.setText(App.t(on ? R.string.home_on : R.string.home_off));
            cursor.setTextColor(on ? GREEN : MUTED);
            TextView lookValue = new TextView(this);
            secBox.addView(settingRow(App.t(R.string.home_cursor_look), App.t(R.string.home_cursor_look_help), lookValue, v -> {
                String[] items = new String[Pointer.LOOKS.length];
                for (int i = 0; i < items.length; i++)
                    items[i] = Pointer.lookIcon(i) + "   " + Pointer.lookLabel(i) + (Pointer.LOOKS[i].equals(Pointer.look()) ? "  \u2713" : "");
                new AlertDialog.Builder(this).setTitle(App.t(R.string.home_cursor_look)).setItems(items, (d, i) -> {
                    App.prefs.edit().putString("cursorLook", Pointer.LOOKS[i]).apply();
                    build();
                }).show();
            }));
            lookValue.setText(Pointer.lookName());
            lookValue.setTextColor(TEXT);
            TextView sizeValue = new TextView(this);
            secBox.addView(settingRow(App.t(R.string.home_cursor_size), App.t(R.string.home_cursor_size_help), sizeValue, v -> {
                int next = Pointer.SIZES[0];
                for (int i = 0; i < Pointer.SIZES.length - 1; i++) if (Pointer.SIZES[i] == Pointer.size()) next = Pointer.SIZES[i + 1];
                App.prefs.edit().putInt("cursorSize", next).apply();
                sizeValue.setText(next + "%");
            }));
            sizeValue.setText(Pointer.size() + "%");
            sizeValue.setTextColor(TEXT);
        }
        secBox.addView(settingRow(App.t(R.string.home_warp_log), App.t(R.string.home_warp_log_help), null, v -> warpLog()));

        section(App.t(R.string.home_your_data), R.drawable.ic_delete);
        List<Sites.Site> favs = Sites.favorites();
        secBox.addView(settingRow(App.t(R.string.home_clear_favorites), App.t(favs.size() == 1 ? R.string.home_sites_one : R.string.home_sites_many, favs.size()), null, v -> confirmClearFavorites(favs.size())));
        int seen = History.list().size();
        secBox.addView(settingRow(App.t(R.string.home_clear_history), App.t(seen == 1 ? R.string.home_history_count_one : R.string.home_history_count_many, seen), null, v ->
            confirm(App.t(R.string.home_clear_history_title), App.t(R.string.home_clear_history_msg), App.t(R.string.home_clear), () -> { History.save(new ArrayList<>()); build(); toast(App.t(R.string.home_history_cleared)); })));
        int watched = Iptv.history().size();
        secBox.addView(settingRow(App.t(R.string.home_clear_iptv_history), App.t(watched == 1 ? R.string.home_iptv_history_count_one : R.string.home_iptv_history_count_many, watched), null, v ->
            confirm(App.t(R.string.home_clear_iptv_history_title), App.t(R.string.home_clear_iptv_history_msg), App.t(R.string.home_clear), () -> { App.prefs.edit().remove("iptvhist").apply(); build(); toast(App.t(R.string.home_iptv_history_cleared)); })));
        secBox.addView(settingRow(App.t(R.string.home_clear_web), App.t(R.string.home_clear_web_help), null, v ->
            confirm(App.t(R.string.home_clear_web) + "?", App.t(R.string.home_clear_web_help), App.t(R.string.home_clear), this::clearWebData)));
        int listed = Catalog.mine().size();
        secBox.addView(settingRow(App.t(R.string.home_clear_mylist), App.t(listed == 1 ? R.string.home_titles_one : R.string.home_titles_many, listed), null, v ->
            confirm(App.t(R.string.home_clear_mylist_title), App.t(R.string.home_clear_mylist_msg, listed), App.t(R.string.home_clear), () -> { App.prefs.edit().remove("mylist").apply(); build(); toast(App.t(R.string.home_mylist_cleared)); })));

        section("IPTV", R.drawable.ic_live);
        if (Iptv.adultAvailable()) {
            TextView adultValue = new TextView(this);
            secBox.addView(settingRow(App.t(R.string.home_adult), App.t(R.string.home_adult_help), adultValue, v -> {
                App.prefs.edit().putBoolean("adult", !Iptv.adultOn()).apply();
                build();
            }));
            adultValue.setText(App.t(Iptv.adultOn() ? R.string.home_on : R.string.home_off));
            adultValue.setTextColor(Iptv.adultOn() ? GREEN : MUTED);
        }
        TextView extrasValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_extras_title), App.t(R.string.home_extras_help), extrasValue, v -> extrasDialog()));
        extrasValue.setText(App.t(Iptv.extrasUrl().isEmpty() ? R.string.home_none : Iptv.extrasUrl().equals(Iptv.DEFAULT_EXTRAS) ? R.string.home_default : R.string.home_custom));
        extrasValue.setTextColor(Iptv.extrasUrl().isEmpty() ? MUTED : GREEN);
        secBox.addView(settingRow(App.t(R.string.home_add_playlist_title), App.t(R.string.home_add_playlist_help), null, v -> addPlaylistDialog()));
        for (String url : Iptv.mine()) {
            int n = Iptv.parse(Iptv.read(Iptv.mineFile(url)), false).size();
            String name = Iptv.playlistName(url);
            secBox.addView(settingRow(name, App.t(n == 1 ? R.string.home_playlist_channels_one : R.string.home_playlist_channels_many, n), null, v ->
                confirm(App.t(R.string.home_remove_playlist_title), name, App.t(R.string.home_remove), () -> { Iptv.removePlaylist(url); build(); toast(App.t(R.string.home_playlist_removed)); })));
        }
        TextView deadValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_hide_offline), App.t(R.string.home_hide_offline_help), deadValue, v -> {
            App.prefs.edit().putBoolean("hideDead", !Iptv.hideDead()).apply();
            build();
        }));
        deadValue.setText(App.t(Iptv.hideDead() ? R.string.home_on : R.string.home_off));
        deadValue.setTextColor(Iptv.hideDead() ? GREEN : MUTED);
        secBox.addView(settingRow(App.t(R.string.home_check_channels), Iptv.sweeping ? App.t(R.string.home_checking_progress, Iptv.sweepDone, Iptv.sweepTotal) : App.t(R.string.home_offline_now, Iptv.deadCount()), null, v -> {
            if (Iptv.sweeping) { toast(App.t(R.string.home_already_checking)); return; }
            Iptv.health.clear();
            toast(App.t(R.string.home_checking_background));
            Iptv.sweep();
        }));
        secBox.addView(settingRow(App.t(R.string.home_refresh_iptv), App.t(R.string.home_refresh_iptv_help), null, v -> {
            toast(App.t(R.string.home_downloading_channels));
            App.io.execute(() -> {
                boolean ok = Iptv.refresh();
                runOnUiThread(() -> toast(App.t(ok ? R.string.home_channels_updated : R.string.home_github_offline_warp)));
            });
        }));

        section(App.t(R.string.home_player), R.drawable.ic_pb_play);
        TextView subValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_subtitles), App.t(R.string.home_subtitles_help), subValue, v ->
            Subs.panel(this, false, () -> subValue.setText(Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel()), this::build)));
        subValue.setText(Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel());
        subValue.setTextColor(Subs.size() == 100 && Subs.offset() == 0 && Subs.background() == 50 && Subs.colorIndex() == 0 ? MUTED : GREEN);
        TextView boostValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_volume_boost), App.t(R.string.home_volume_boost_help), boostValue, v -> {
            int next = PlayerActivity.BOOSTS[0];
            for (int i = 0; i < PlayerActivity.BOOSTS.length - 1; i++) if (PlayerActivity.BOOSTS[i] == PlayerActivity.boostDb()) next = PlayerActivity.BOOSTS[i + 1];
            App.prefs.edit().putInt("boost", next).apply();
            build();
        }));
        boostValue.setText(PlayerActivity.boostDb() == 0 ? App.t(R.string.home_off) : "+" + PlayerActivity.boostDb() + " dB");
        boostValue.setTextColor(PlayerActivity.boostDb() == 0 ? MUTED : GREEN);
        TextView voiceValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_dialog_boost), App.t(R.string.home_dialog_boost_help), voiceValue, v -> {
            App.prefs.edit().putBoolean("dialogBoost", !PlayerActivity.dialogBoost()).apply();
            build();
        }));
        voiceValue.setText(App.t(PlayerActivity.dialogBoost() ? R.string.home_on : R.string.home_off));
        voiceValue.setTextColor(PlayerActivity.dialogBoost() ? GREEN : MUTED);
        TextView autoSub = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_auto_subs), App.t(R.string.home_auto_subs_help, Subs.language(Subs.preferred())), autoSub, v -> {
            App.prefs.edit().putBoolean("subAuto", !Subs.auto()).apply();
            build();
        }));
        autoSub.setText(App.t(Subs.auto() ? R.string.home_on : R.string.home_off));
        autoSub.setTextColor(Subs.auto() ? GREEN : MUTED);
        TextView autoSkip = new TextView(this);
        boolean skipOn = App.prefs.getBoolean("autoSkip", false);
        secBox.addView(settingRow(App.t(R.string.home_auto_skip), App.t(R.string.home_auto_skip_help), autoSkip, v -> {
            App.prefs.edit().putBoolean("autoSkip", !skipOn).apply();
            build();
        }));
        autoSkip.setText(App.t(skipOn ? R.string.home_on : R.string.home_off));
        autoSkip.setTextColor(skipOn ? GREEN : MUTED);
        TextView simklValue = new TextView(this);
        secBox.addView(settingRow("Simkl", App.t(R.string.home_simkl_help), simklValue, v -> {
            if (Simkl.signedIn()) confirm(App.t(R.string.home_simkl_disconnect_title), App.t(R.string.home_simkl_disconnect_msg), App.t(R.string.home_disconnect), () -> { Simkl.signOut(); build(); });
            else simklDialog();
        }));
        simklValue.setText(App.t(Simkl.signedIn() ? R.string.home_connected : R.string.home_connect));
        simklValue.setTextColor(Simkl.signedIn() ? GREEN : ACCENT);
        TextView ppValue = new TextView(this);
        secBox.addView(settingRow("PunchPlay", App.t(R.string.punchplay_help), ppValue, v -> {
            if (PunchPlay.signedIn()) confirm(App.t(R.string.punchplay_disconnect_title), App.t(R.string.punchplay_disconnect_msg), App.t(R.string.home_disconnect), () -> { PunchPlay.signOut(); build(); });
            else punchPlayDialog();
        }));
        ppValue.setText(App.t(PunchPlay.signedIn() ? R.string.home_connected : R.string.home_connect));
        ppValue.setTextColor(PunchPlay.signedIn() ? GREEN : ACCENT);

        section(App.t(R.string.home_updates), R.drawable.ic_refresh);
        String newer = Update.latest();
        TextView updValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_check_updates), App.t(R.string.home_check_updates_help, Update.installed()), updValue, v -> checkUpdate(updValue)));
        updValue.setText(newer.isEmpty() ? App.t(R.string.home_up_to_date) : App.t(R.string.home_version_available, newer));
        updValue.setTextColor(newer.isEmpty() ? MUTED : GREEN);
        TextView autoValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_check_at_start), App.t(R.string.home_check_at_start_help), autoValue, v -> {
            App.prefs.edit().putBoolean("updateCheck", !Update.auto()).apply();
            build();
        }));
        autoValue.setText(App.t(Update.auto() ? R.string.home_on : R.string.home_off));
        autoValue.setTextColor(Update.auto() ? GREEN : MUTED);

        section(App.t(R.string.home_sites), R.drawable.ic_language);
        secBox.addView(settingRow(App.t(R.string.home_add_site), App.t(R.string.home_add_site_help), null, v -> addSiteDialog()));
        TextView ownValue = new TextView(this);
        secBox.addView(settingRow(App.t(R.string.home_my_sites), App.t(R.string.home_my_sites_help), ownValue, v -> mySitesDialog()));
        int own = Sites.custom().size();
        ownValue.setText(own == 0 ? App.t(R.string.home_none) : App.t(own == 1 ? R.string.home_sites_one : R.string.home_sites_many, own));
        ownValue.setTextColor(own == 0 ? MUTED : GREEN);
        secBox.addView(settingRow(App.t(R.string.home_refresh_fmhy), App.t(R.string.home_refresh_fmhy_help), null, v -> {
            toast(App.t(R.string.home_checking_fmhy));
            App.io.execute(() -> {
                boolean changed = Sites.refresh();
                runOnUiThread(() -> toast(App.t(changed ? R.string.home_site_list_updated : R.string.home_site_list_current)));
            });
        }));

        // Which build is this? (the TV and phone APKs share a package, so say it on screen)
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        section(App.t(R.string.home_donate), R.drawable.ic_heart, true);
        View why = note(App.t(R.string.home_donate_help));
        why.setPadding(u(52, 50), 0, dp(14), dp(8));
        why.setVisibility(secOpen ? View.VISIBLE : View.GONE);
        secBox.addView(why);
        secBox.addView(settingRow("Buy Me a Coffee", "buymeacoffee.com/rohit267", null, v -> openLink("https://buymeacoffee.com/rohit267")));
        secBox.addView(settingRow("PayPal", "paypal.me/champdev", null, v -> openLink("https://paypal.me/champdev")));

        section(App.t(R.string.home_about), R.drawable.ic_info, true);
        secBox.addView(settingRow(App.t(R.string.home_about_site), "fmhyplay.github.io/android", null, v -> openLink("https://fmhyplay.github.io/android/")));
        secBox.addView(settingRow(App.t(R.string.home_about_source), "github.com/fmhyplay/android", null, v -> openLink("https://github.com/fmhyplay/android")));
        secBox.addView(settingRow(App.t(R.string.home_about_bug), "github.com/fmhyplay/android/issues", null, v -> openLink("https://github.com/fmhyplay/android/issues")));
        TextView about = text(App.t(App.tv ? R.string.home_about_tv : R.string.home_about_phone, ver), sp(12, 11), MUTED, false);
        about.setPadding(u(52, 50), dp(6), dp(14), dp(12));
        about.setVisibility(secOpen ? View.VISIBLE : View.GONE);
        secBox.addView(about);
        secOpen = true;
        updateChips();
    }

    /** Whose movies and series lead Home: found by itself, picked from the countries we have lists for, or off. */
    void regionDialog() {
        List<String> codes = new ArrayList<>(), names = new ArrayList<>();
        for (String row : Catalog.LOCAL) codes.add(row.substring(0, 2));
        codes.sort(java.util.Comparator.comparing(c -> new java.util.Locale("", c).getDisplayCountry()));
        codes.add(0, "");
        codes.add(1, "-");
        for (String c : codes) names.add(c.isEmpty() ? App.t(R.string.warp_dns_auto) : c.equals("-") ? App.t(R.string.home_off) : new java.util.Locale("", c).getDisplayCountry());
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_region)).setItems(names.toArray(new String[0]), (d, i) -> {
            App.prefs.edit().putString("region", codes.get(i)).apply();
            build();
        }).show();
    }

    /** Shows a code to enter at simkl.com/pin (where you can also make the free account) and waits for it. */
    void simklDialog() {
        TextView steps = text(App.t(R.string.home_getting_code), sp(17, 15), TEXT, false), code = text("", sp(40, 32), TEXT, true);
        code.setLetterSpacing(0.15f);
        code.setPadding(0, dp(14), 0, dp(6));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(App.t(R.string.home_connect_simkl)).setView(WebActivity.box(this, steps, code)).setNegativeButton(android.R.string.cancel, null).create();
        boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
        App.io.execute(() -> {
            Simkl.Code c = Simkl.newCode();
            runOnUiThread(() -> {
                if (!open[0]) return;
                if (c == null) { steps.setText(App.t(R.string.home_simkl_offline)); return; }
                steps.setText(App.t(R.string.home_simkl_steps, c.page.replaceFirst("^https?://", "")));
                code.setText(c.user);
                waitForSimkl(c, c.interval, d, open, steps);
            });
        });
    }

    void waitForSimkl(Simkl.Code c, int every, AlertDialog d, boolean[] open, TextView steps) {
        App.main.postDelayed(() -> {
            if (!open[0]) return;
            App.io.execute(() -> {
                String r = Simkl.check(c);
                runOnUiThread(() -> {
                    if (!open[0]) return;
                    switch (r) {
                        case "ok": d.dismiss(); toast(App.t(R.string.home_simkl_connected)); build(); break;
                        case "expired": steps.setText(App.t(R.string.home_simkl_expired)); break;
                        case "error": steps.setText(App.t(R.string.home_simkl_refused)); break;
                        default: waitForSimkl(c, r.equals("slow") ? every + 5 : every, d, open, steps);
                    }
                });
            });
        }, every * 1000L);
    }

    /** Shows a code to enter at punchplay.tv/link (where you can also make the free account) and waits for it, as for Simkl. */
    void punchPlayDialog() {
        TextView steps = text(App.t(R.string.home_getting_code), sp(17, 15), TEXT, false), code = text("", sp(40, 32), TEXT, true);
        code.setLetterSpacing(0.15f);
        code.setPadding(0, dp(14), 0, dp(6));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(App.t(R.string.punchplay_connect)).setView(WebActivity.box(this, steps, code)).setNegativeButton(android.R.string.cancel, null).create();
        boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
        App.io.execute(() -> {
            PunchPlay.Code c = PunchPlay.newCode();
            runOnUiThread(() -> {
                if (!open[0]) return;
                if (c == null) { steps.setText(App.t(R.string.punchplay_offline)); return; }
                steps.setText(App.t(R.string.home_simkl_steps, c.page.replaceFirst("^https?://", "")));
                code.setText(c.user);
                Runnable[] poll = new Runnable[1];
                poll[0] = () -> App.io.execute(() -> {
                    String r = PunchPlay.check(c);
                    runOnUiThread(() -> {
                        if (!open[0]) return;
                        switch (r) {
                            case "ok": d.dismiss(); toast(App.t(R.string.punchplay_connected)); build(); break;
                            case "expired": steps.setText(App.t(R.string.punchplay_expired)); break;
                            case "error": steps.setText(App.t(R.string.punchplay_refused)); break;
                            default: App.main.postDelayed(poll[0], c.interval * 1000L);
                        }
                    });
                });
                App.main.postDelayed(poll[0], c.interval * 1000L);
            });
        });
    }

    /** Update `version`: what's new since this one, then Install runs `install`. */
    void whatsNew(String version, Runnable install) {
        String notes = Update.notes();
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_update_title, version))
            .setMessage((notes.isEmpty() ? "" : App.t(R.string.home_whats_new) + "\n\n" + notes + "\n\n") + App.t(R.string.home_update_ask, Update.installed()))
            .setPositiveButton(App.t(R.string.home_install), (d, w) -> install.run())
            .setNegativeButton(App.t(R.string.home_later), null).show();
    }

    /** The WARP log in a scrolling box, with Share and Copy. */
    void warpLog() {
        String log = Warp.logText();
        TextView body = text(log, sp(13, 12), TEXT, false);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(body);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_warp_log)).setView(sc)
            .setPositiveButton(App.t(R.string.home_share), (d, w) -> {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), App.t(R.string.home_share_warp_log)));
                } catch (android.content.ActivityNotFoundException e) {
                    toast(App.t(R.string.home_no_share_app));
                }
            })
            .setNeutralButton(android.R.string.copy, (d, w) -> {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("WARP log", log));
                toast(App.t(R.string.home_copied));
            })
            .setNegativeButton(App.t(R.string.home_close), null).show();
    }

    /** Settings: ask GitHub now, then offer the install. */
    void checkUpdate(TextView value) {
        value.setText(App.t(R.string.home_checking));
        App.io.execute(() -> {
            boolean ok = Update.check();
            runOnUiThread(() -> {
                String v = Update.latest();
                if (!ok) { value.setText(App.t(R.string.home_github_offline)); toast(App.t(R.string.home_github_offline_warp)); return; }
                if (v.isEmpty()) { value.setText(App.t(R.string.home_up_to_date)); toast(App.t(R.string.home_is_latest, Update.installed())); return; }
                value.setText(App.t(R.string.home_version_available, v));
                whatsNew(v, () -> Update.install(value::setText));
            });
        });
    }

    TextView warpValue, adValue;

    /** A full-width settings row: title and a line of help on the left, current state on the right. */
    static final java.util.Set<String> openSections = new java.util.HashSet<>();
    static String focusSection;
    boolean secOpen = true;   // whether the rows being added now are shown

    /** A Settings group: one line that opens and closes its rows, so the tab starts as a short list. */
    void section(String title, int icon) {
        section(title, icon, false);
    }

    /** `fixed`: always open, its heading is a label and not a switch (Donate, About). */
    void section(String title, int icon, boolean fixed) {
        secOpen = fixed || openSections.contains(title);
        secBox = new LinearLayout(this);   // one card per group: its heading, then its rows with a hairline between them
        secBox.setOrientation(LinearLayout.VERTICAL);
        secBox.setBackground(rounded(CARD, dp(14)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.bottomMargin = dp(8);
        col.addView(secBox, bl);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u(16, 14), u(12, 12), u(18, 16), u(12, 12));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(secOpen ? ACCENT : MUTED);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
        il.rightMargin = dp(14);
        row.addView(ic, il);
        row.addView(text(title, sp(16, 15), TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        secBox.addView(row);
        if (fixed) return;
        row.addView(text(secOpen ? "\u2212" : "+", sp(20, 19), MUTED, false));
        row.setBackground(rounded(0, dp(14)));
        focusable(row, dp(14));
        row.setOnClickListener(v -> {
            if (!openSections.remove(title)) openSections.add(title);
            focusSection = title;
            skipFocus = true;   // focus stays on this line, not back at the top
            build();
            skipFocus = false;
        });
        if (firstCard == null) firstCard = row;
        if (App.tv && title.equals(focusSection)) { focusSection = null; row.post(row::requestFocus); }
    }

    LinearLayout secBox;
    int rowIcon;   // the next setting row stands alone in its own card, with this icon

    /** A group of one: the setting itself is the card, nothing to open. */
    void single(int icon) {
        secOpen = true;
        rowIcon = icon;
        secBox = new LinearLayout(this);
        secBox.setOrientation(LinearLayout.VERTICAL);
        secBox.setBackground(rounded(CARD, dp(14)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.bottomMargin = dp(8);
        col.addView(secBox, bl);
    }

    /** A link: always the device's own browser, never the app's. A device with none (most TVs) shows the address to open elsewhere. */
    void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException e) {
            new AlertDialog.Builder(this).setMessage(url.replaceFirst("^https://", "")).setPositiveButton(android.R.string.ok, null).show();
        }
    }

    View settingRow(String title, String help, TextView value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u(52, 50), u(9, 9), u(18, 14), u(9, 9));   // text under the heading's text, past its icon
        boolean alone = rowIcon != 0;
        if (alone) {
            row.setPadding(u(16, 14), u(11, 11), u(18, 14), u(11, 11));
            ImageView ic = new ImageView(this);
            ic.setImageResource(rowIcon);
            ic.setColorFilter(MUTED);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
            il.rightMargin = dp(14);
            row.addView(ic, il);
            rowIcon = 0;
        }
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, sp(15, 15), TEXT, true));
        TextView h = text(help, sp(13, 12), MUTED, false);
        h.setPadding(0, dp(2), 0, 0);
        left.addView(h);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) {
            value.setTextSize(sp(16, 14));
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            value.setPadding(dp(12), 0, 0, 0);
            row.addView(value);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        row.setLayoutParams(lp);
        row.setBackground(rounded(0, dp(12)));
        focusable(row, dp(12));
        row.setOnClickListener(click);
        if (firstCard == null && secOpen) firstCard = row;
        // the row under a hairline that lines up with the text
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 1);
        dl.setMargins(u(52, 50), 0, u(18, 14), 0);
        if (!alone) both.addView(line, dl);
        both.addView(row);
        both.setVisibility(secOpen ? View.VISIBLE : View.GONE);
        return both;
    }

    /** A poster with its name and year under it. The picture is the part that takes focus and clicks. */
    View posterCard(Catalog.Title t, int w) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setLayoutParams(cardParams(w, 0));
        FrameLayout art = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text(t.name.isEmpty() ? "?" : t.name.substring(0, 1).toUpperCase(), sp(40, 30), 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        art.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (!t.poster.isEmpty()) loadImage(img, t.poster, letter, dp(w));
        if (kindBadges()) {
            TextView kind = text(App.t(t.anime ? R.string.home_tab_anime : t.series() ? R.string.home_kind_series : R.string.home_kind_movie), sp(11, 10), Color.WHITE, true);
            kind.setPadding(u(8, 6), u(3, 2), u(8, 6), u(3, 2));
            kind.setBackground(rounded(0xCC0E0C13, dp(6)));
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            kp.setMargins(u(7, 5), u(7, 5), 0, 0);
            art.addView(kind, kp);
        }
        art.setBackground(rounded(colorFor(t.name), dp(10)));
        art.setClipToOutline(true);
        art.setContentDescription(t.name);
        art.setTag("t" + t.id);
        focusable(art, dp(10));
        art.setOnClickListener(v -> openTitle(t));
        art.setOnLongClickListener(v -> {
            toast(App.t(Catalog.toggleMine(t) ? R.string.home_added_mylist : R.string.home_removed_mylist));
            if (tab == 0 && tabs()) build();
            return true;
        });
        card.addView(art, new LinearLayout.LayoutParams(-1, dp(w * 3 / 2)));
        TextView name = text(t.name, 12, TEXT, false);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(2), u(8, 6), dp(2), 0);
        card.addView(name);
        // always there, also empty, so every card in a row is the same height
        card.addView(text((t.year.isEmpty() ? "" : t.year) + (t.rating.isEmpty() ? "" : (t.year.isEmpty() ? "" : "    ") + "\u2605 " + t.rating), sp(11, 10), MUTED, false));
        ((TextView) card.getChildAt(2)).setPadding(dp(2), dp(1), dp(2), 0);
        if (firstCard == null) firstCard = art;
        return card;
    }

    /** Refreshes the WARP and ad block state shown on the Home chips and in Settings (whichever is on screen). */
    void updateChips() {
        // Icon and text share one colour: green = working, amber = starting, red = failed, grey = off
        String label, word;   // the chip's "WARP on" and Settings' "On"
        int color, icon = R.drawable.ic_shield;
        switch (Warp.state) {
            case ON: label = App.t(R.string.home_warp_on); word = App.t(R.string.home_on); color = GREEN; icon = R.drawable.ic_shield_check; break;
            case CONNECTING: label = App.t(R.string.home_warp_connecting); word = App.t(R.string.home_connecting); color = AMBER; break;
            case FAILED: label = App.t(R.string.home_warp_failed); word = App.t(R.string.home_failed_retry); color = ACCENT; break;
            default: label = App.t(R.string.home_warp_off); word = App.t(R.string.home_off); color = MUTED;
        }
        boolean ads = AdBlock.enabled();
        if (warpChip != null) {
            warpChip.setText("");   // the icon's colour says it; words would push the bar's other buttons off the screen
            warpChip.setTooltipText(label);
            warpChip.setContentDescription(label);
            iconize(warpChip, icon, color);
            warpChip.setCompoundDrawablePadding(0);
            if (App.tv) warpChip.setPadding(dp(12), dp(10), dp(12), dp(10));
        }
        if (warpValue != null) { warpValue.setText(word); warpValue.setTextColor(color); }
        if (adValue != null) { adValue.setText(App.t(ads ? R.string.home_on : R.string.home_off)); adValue.setTextColor(ads ? GREEN : MUTED); }
    }

    /** Puts a tinted icon in front of a chip's text and colours the text to match. */
    void iconize(TextView t, int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        int size = u(20, 17);
        d.setBounds(0, 0, size, size);
        d.setTint(color);
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(u(10, 7));
        t.setTextColor(color);
    }

    /** A round icon-only button (with a spoken label for screen readers). */
    ImageView iconButton(int res, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setColorFilter(MUTED);
        b.setContentDescription(label);
        int pad = u(10, 8);
        b.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(44, 36), u(44, 36));
        lp.topMargin = u(14, 6);
        b.setLayoutParams(lp);
        focusable(b, dp(22));
        b.setOnClickListener(click);
        return b;
    }

    TextView chip(View.OnClickListener click) {
        TextView t = text("", sp(15, 13), TEXT, true);
        t.setPadding(u(16, 12), u(9, 6), u(16, 12), u(9, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(4), u(10, 6), dp(4));
        t.setLayoutParams(lp);
        focusable(t, dp(22));
        t.setOnClickListener(click);
        return t;
    }

    TextView heading(String s) {
        return heading(s, "");
    }

    /** A row's name, with what narrows it ("Movies  New", "TV  India") quieter beside it. */
    TextView heading(String s, String sub) {
        TextView t = text(s, sp(18, 16), TEXT, true);
        if (!sub.isEmpty()) {
            android.text.SpannableString both = new android.text.SpannableString(s + "   " + sub);
            both.setSpan(new android.text.style.ForegroundColorSpan(MUTED), s.length(), both.length(), 0);
            both.setSpan(new android.text.style.RelativeSizeSpan(0.82f), s.length(), both.length(), 0);
            t.setText(both);
        }
        t.setPadding(0, u(12, 10), 0, u(2, 4));   // a gap before the cards, also on a phone (tile grids have none of their own)
        return t;
    }

    LinearLayout row() {
        return row(col);
    }

    LinearLayout row(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        hs.setLayoutParams(lp);
        hs.setPadding(slack, u(8, 3), slack, u(8, 3));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row);
        parent.addView(hs);
        return row;
    }

    /** Site cards in whole rows across the screen (4 on a TV, 2 on a phone) inside `into`. */
    class Grid {
        final LinearLayout into;
        final int cols, gap = dp(gapDp), cell;   // cell = card width in dp
        LinearLayout row;
        int inRow;

        /** Site cards: 4 across on a TV, 2 on a phone. */
        Grid(LinearLayout into) {
            this(into, App.tv ? 5 : 2);
            cardW = cell;
        }

        Grid(LinearLayout into, int cols) {
            this.into = into;
            this.cols = cols;
            int width = winW() - 2 * side;
            cell = (int) Math.floor((width - gap * (cols - 1)) / (float) cols / getResources().getDisplayMetrics().density) - 1;
        }

        /** The next card starts a fresh row (after a heading). */
        void breakRow() {
            row = null;
            inRow = 0;
        }

        void add(Sites.Site s) {
            add(siteCard(s, true));
        }

        void add(View card) {
            if (row == null || inRow == cols) {
                row = new LinearLayout(Home.this);
                row.setClipChildren(false);
                into.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = gap;
                row.setLayoutParams(lp);
                into.addView(row);
                inRow = 0;
            }
            ((LinearLayout.LayoutParams) card.getLayoutParams()).setMargins(0, 0, inRow == cols - 1 ? 0 : gap, 0);
            row.addView(card);
            inRow++;
        }
    }

    View historyCard(History.Entry e) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(cardParams(App.tv ? 168 : 190, App.tv ? 94 : 108));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text(e.title.isEmpty() ? "?" : e.title.substring(0, 1).toUpperCase(), sp(44, 32), 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        card.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (!e.image.isEmpty()) loadImage(img, e.image, letter, dp(196));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE0000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(12, 9), 0, u(12, 9), u(10, 7));
        TextView title = text(e.title, sp(14, 14), Color.WHITE, true);
        title.setMaxLines(App.tv ? 1 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        long now = System.currentTimeMillis();   // under a minute reads "1 minute ago", not "0 minutes ago"
        String ago = DateUtils.getRelativeTimeSpanString(Math.min(e.time, now - 60000), now, DateUtils.MINUTE_IN_MILLIS).toString();
        info.addView(text((e.episode.isEmpty() ? "" : e.episode + "  ·  ") + e.site + "  ·  " + ago, 12, 0xFFD8D0E2, false));
        card.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (e.pos > 0 && e.dur > 0) {   // how much is watched: the player carries on from there
            info.setPadding(u(12, 9), 0, u(12, 9), u(18, 14));
            float done = (float) Math.min(1.0, (double) e.pos / e.dur);
            LinearLayout track = new LinearLayout(this);
            track.setBackground(rounded(0x55FFFFFF, dp(2)));
            track.setClipToOutline(true);
            View fill = new View(this);
            fill.setBackgroundColor(ACCENT);
            track.addView(fill, new LinearLayout.LayoutParams(0, -1, done));
            track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1 - done));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
            tp.setMargins(u(12, 9), 0, u(12, 9), u(9, 7));
            card.addView(track, tp);
        }

        card.setBackground(rounded(colorFor(e.site), dp(12)));
        card.setClipToOutline(true);
        card.setTag("h" + e.url);
        focusable(card, dp(12));
        // Resume: the episode's own page, with the backdrop up while the app presses play and hands over to the player
        card.setOnClickListener(v -> startActivity(WebActivity.open(this, e.site, e.siteUrl, e.url).putExtra("play", true).putExtra("name", e.title)));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle(e.title)
                .setItems(new String[]{App.t(R.string.home_open_site_home, e.site), App.t(R.string.home_remove_continue)}, (d, i) -> {
                    if (i == 0) startActivity(WebActivity.open(this, e.site, e.siteUrl, e.siteUrl));
                    else { History.remove(e); build(); }
                }).show();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    View siteCard(Sites.Site s, boolean markFavorite) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setPadding(u(16, 10), u(10, 6), u(16, 10), u(10, 6));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // Letter tile until (or unless) the site's icon loads
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(colorFor(s.name), dp(6)));
        TextView letter = text(s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase(), sp(14, 12), 0xCCFFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        tile.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = new ImageView(this);
        String host = String.valueOf(Uri.parse(s.url).getHost());
        tile.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        loadImage(icon, "https://www.google.com/s2/favicons?domain=" + host + "&sz=64", letter);
        top.addView(tile, new LinearLayout.LayoutParams(u(26, 22), u(26, 22)));
        TextView name = text(s.name, sp(17, 15), TEXT, true);
        if (markFavorite && Sites.isFavorite(s.url)) {
            android.text.SpannableString star = new android.text.SpannableString("\u2665  " + s.name);
            star.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 1, 0);
            name.setText(star);
        }
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(u(10, 8), 0, 0, 0);
        top.addView(name);
        card.addView(top);
        TextView tags = text(s.tags.isEmpty() ? host : s.tags, sp(12, 11), MUTED, false);
        tags.setSingleLine();
        tags.setEllipsize(TextUtils.TruncateAt.END);
        tags.setPadding(0, u(6, 2), 0, 0);
        if (cardH >= 60) card.addView(tags);   // the slim card is icon and name only

        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setTag("s" + s.url);
        card.setOnClickListener(v -> openSite(s));
        // Hold OK (or press Menu) for: favorite, mirrors, remove
        card.setOnLongClickListener(v -> {
            List<String> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            items.add(App.t(Sites.isFavorite(s.url) ? R.string.home_remove_favorite : R.string.home_add_favorite));
            actions.add(() -> { Sites.toggleFavorite(s); build(); });
            for (String m : s.mirrors) {
                items.add(App.t(R.string.home_open_mirror, Uri.parse(m).getHost()));
                actions.add(() -> startActivity(WebActivity.open(this, s.name, m, m)));
            }
            new AlertDialog.Builder(this).setTitle(s.name)
                .setItems(items.toArray(new String[0]), (d, i) -> actions.get(i).run()).show();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    /** What tapping a site card does; the title page overrides it to search for the title there. */
    void openSite(Sites.Site s) {
        startActivity(WebActivity.open(this, s.name, s.url, s.url));
    }

    View languageCard(String language, int count) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setPadding(u(16, 10), u(10, 6), u(16, 10), u(10, 6));
        TextView name = text(language, sp(17, 15), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        TextView n = text(App.t(count == 1 ? R.string.home_sites_one : R.string.home_sites_many, count), sp(12, 11), MUTED, false);
        n.setPadding(0, u(6, 2), 0, 0);
        card.addView(n);
        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, CategoryActivity.class).putExtra("language", language)));
        return card;
    }

    View allCard(String category, int count) {
        TextView card = text(App.t(R.string.home_all_count, count), sp(17, 15), TEXT, true);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setBackground(rounded(CARD_HI, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, CategoryActivity.class).putExtra("category", category)));
        return card;
    }

    View addCard() {
        TextView card = text(App.t(R.string.home_add_site_card), sp(17, 15), TEXT, true);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setBackground(rounded(CARD_HI, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> addSiteDialog());
        return card;
    }

    void addSiteDialog() {
        EditText in = WebActivity.field(this, "https://example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_add_site)).setView(WebActivity.box(this, in))
            .setPositiveButton(App.t(R.string.home_add), (d, w) -> {
                if (in.getText().toString().trim().isEmpty()) return;
                Sites.addCustom(in.getText().toString());
                build();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    void mySitesDialog() {
        EditText in = WebActivity.field(this, App.t(R.string.home_my_sites_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(8);
        in.setGravity(Gravity.TOP | Gravity.START);
        in.setText(App.prefs.getString("mySites", ""));
        new AlertDialog.Builder(this).setTitle(App.t(R.string.home_my_sites)).setView(WebActivity.box(this, in))
            .setPositiveButton(App.t(R.string.home_save), (d, w) -> {
                App.prefs.edit().putString("mySites", in.getText().toString().trim()).apply();
                build();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    LinearLayout.LayoutParams cardParams(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), h > 0 ? dp(h) : -2);   // h 0: as tall as its content
        lp.setMargins(0, 0, dp(gapDp), 0);
        return lp;
    }

    // Focus = a white ring and a lift; cards also grow a little (rows keep slack for it), wide rows only get the ring
    void focusable(View v, int radius) {
        v.setFocusable(true);
        v.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(dp(App.tv ? 3 : 2), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, rounded(Color.WHITE, radius)));
        v.setOnFocusChangeListener((view, has) -> {
            // not a text box, and not a view focused before it has a size (width 0 passed as "small": the search box grew off the screen)
            // only cards on the page grow: the rail's buttons keep their size (grown, the rail cut their rings)
            boolean card = false;
            for (android.view.ViewParent q = view.getParent(); q != null && !card; q = q.getParent()) card = q == col;
            float s = has && card && !(view instanceof EditText) && view.getWidth() > 0 && view.getWidth() < dp(330) ? 1.05f : 1f;
            view.animate().scaleX(s).scaleY(s).translationZ(has ? dp(8) : 0).setDuration(120).start();
            if (has) view.post(() -> reveal(view));
        });
        if (!(v.getBackground() instanceof GradientDrawable) && !(v instanceof FrameLayout)) v.setBackground(rounded(CARD, radius));
    }

    // Keep the focused card (grown + ring) fully on screen with a margin, and its row heading visible
    void reveal(View v) {
        // A delayed focus callback can outlive its card when the rows are rebuilt; the top bar is not part of the page
        View up = v;
        HorizontalScrollView hs = null;
        while (up != null && up != page) {
            up = up.getParent() instanceof View ? (View) up.getParent() : null;
            if (hs == null && up instanceof HorizontalScrollView) hs = (HorizontalScrollView) up;
        }
        if (up == null) return;
        Rect r = new Rect();
        if (hs != null) {
            v.getDrawingRect(r);
            hs.offsetDescendantRectToMyCoords(v, r);
            int x = hs.getScrollX(), w = hs.getWidth();
            if (r.left - x < slack) hs.smoothScrollTo(r.left - slack, 0);
            else if (r.right - x > w - slack) hs.smoothScrollTo(r.right - w + slack, 0);
        }
        v.getDrawingRect(r);
        page.offsetDescendantRectToMyCoords(v, r);
        int y = page.getScrollY(), h = page.getHeight();
        if (r.top - dp(64) < y) page.smoothScrollTo(0, Math.max(0, r.top - dp(64)));
        else if (r.bottom + dp(48) > y + h) page.smoothScrollTo(0, r.bottom + dp(48) - h);
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static int colorFor(String s) {
        float[] hsv = {Math.abs(s.hashCode()) % 360, 0.45f, 0.35f};
        return Color.HSVToColor(hsv);
    }

    // Small image loader: memory + disk cache, fetched through WARP
    void loadImage(ImageView into, String url, View placeholder) {
        loadImage(into, url, placeholder, 480);
    }

    /** `min`: the least width in pixels the picture is kept at (the card's own width; backdrops more). Decoded once per size, shared by every card that shows it. */
    void loadImage(ImageView into, String url, View placeholder, int min) {
        String key = url + "@" + min;
        into.setTag(key);
        Bitmap hit = images.get(key);
        if (hit != null) { show(into, hit, placeholder); return; }
        if (lazyImages()) {   // fetched and decoded when the card is on screen or close to it, not for a whole page of rows at once
            lazy.add(new Object[]{into, (Runnable) () -> startLoad(into, url, placeholder, min, key)});
            if (lazy.size() == 1) into.post(this::checkLazy);
            return;
        }
        startLoad(into, url, placeholder, min, key);
    }

    /** Whether this screen loads pictures as they come near the screen (a recycling grid loads its own way). */
    boolean lazyImages() { return true; }

    final List<Object[]> lazy = new ArrayList<>();   // {the picture's view, what starts its load}

    /** Starts the pictures whose views are on screen or within half a screen of it. Runs after every layout and scroll. */
    void checkLazy() {
        if (lazy.isEmpty()) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int[] at = new int[2];
        for (java.util.Iterator<Object[]> it = lazy.iterator(); it.hasNext(); ) {
            Object[] o = it.next();
            ImageView v = (ImageView) o[0];
            if (!v.isAttachedToWindow()) { it.remove(); continue; }   // its card is gone (the page was rebuilt)
            if (v.getWidth() == 0 || !v.isShown()) continue;
            v.getLocationOnScreen(at);
            if (at[0] > dm.widthPixels * 3 / 2 || at[0] + v.getWidth() < -dm.widthPixels / 2
                || at[1] > dm.heightPixels * 3 / 2 || at[1] + v.getHeight() < -dm.heightPixels / 2) continue;
            it.remove();
            ((Runnable) o[1]).run();
        }
    }

    void startLoad(ImageView into, String url, View placeholder, int min, String key) {
        java.util.function.Consumer<Bitmap> done = b -> {
            if (b == null || !key.equals(into.getTag())) return;   // failed, or the view shows something else by now
            show(into, b, placeholder);
            into.setAlpha(0f);
            into.animate().alpha(1).setDuration(180).start();
        };
        synchronized (loading) {
            List<java.util.function.Consumer<Bitmap>> waiting = loading.get(key);
            if (waiting != null) { waiting.add(done); return; }   // already on its way
            loading.put(key, new ArrayList<>(java.util.Collections.singletonList(done)));
        }
        imagePool.execute(() -> {
            Bitmap b = fetch(url, min);
            if (b != null) images.put(key, b);
            List<java.util.function.Consumer<Bitmap>> waiting;
            synchronized (loading) { waiting = loading.remove(key); }
            for (java.util.function.Consumer<Bitmap> c : waiting) App.main.post(() -> c.accept(b));
        });
    }

    void show(ImageView into, Bitmap b, View placeholder) {
        into.setImageBitmap(b);
        if (placeholder != null) placeholder.setVisibility(View.GONE);
    }

    static Bitmap fetch(String url) {
        return fetch(url, 480);
    }

    /** Pictures nobody has looked at for 60 days leave the disk (reading one counts as looking), and the oldest go first when the folder passes 200 MB. */
    static void trimImages() {
        File[] all = new File(App.app.getFilesDir(), "img").listFiles();
        if (all == null) return;
        long old = System.currentTimeMillis() - 60L * 24 * 3600 * 1000, total = 0;
        java.util.Arrays.sort(all, java.util.Comparator.comparingLong(File::lastModified).reversed());   // newest first
        for (File f : all) {
            total += f.length();
            boolean stale = f.getName().endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > 3600000;   // not one being written now
            if (f.lastModified() < old || total > IMAGE_DISK_MAX || stale) f.delete();
        }
    }

    static Bitmap fetch(String url, int min) {
        Long bad = failed.get(url);
        if (bad != null && System.currentTimeMillis() - bad < 10 * 60000) return null;   // a missing picture is not asked for on every redraw
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder();
            for (byte x : sha) name.append(String.format("%02x", x));
            // Kept in app storage (not cache) so Continue watching posters survive cache clears
            File dir = new File(App.app.getFilesDir(), "img");
            dir.mkdirs();
            File f = new File(dir, name.toString());
            if (!f.exists()) {
                File tmp = new File(dir, name + "." + Thread.currentThread().getId() + ".tmp");   // two sizes of one picture can load at once
                try (InputStream in = Warp.openPublic(url, 6000, 20000); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(f)) return null;
            } else if (System.currentTimeMillis() - f.lastModified() > 24L * 3600 * 1000) f.setLastModified(System.currentTimeMillis());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= min) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            // No bigger than it is shown: a 500 px poster on a 280 px card was four times the memory it needed
            int w = o.outWidth / o.inSampleSize;
            if (w > min * 1.2f) { o.inScaled = true; o.inDensity = w; o.inTargetDensity = min; }
            // Photos on cards need no see-through layer: half the memory (big backdrops keep full colour for their soft shades)
            if (min <= 400 && "image/jpeg".equals(o.outMimeType)) o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) b.setDensity(Bitmap.DENSITY_NONE);   // the scaling above was ours, not a screen density
            if (b == null) { f.delete(); failed.put(url, System.currentTimeMillis()); }   // not a picture (an error page): don't keep it
            return b;
        } catch (Exception e) {
            failed.put(url, System.currentTimeMillis());
            return null;
        } catch (OutOfMemoryError e) {
            images.evictAll();
            return null;
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
