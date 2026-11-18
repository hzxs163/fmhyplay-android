package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenSubtitles through Stremio's public addon (no key, no login): subtitle files for an IMDb title or episode. */
final class Subs {
    static final String BASE = "https://opensubtitles-v3.strem.io/subtitles/";

    static final class Sub {
        String url, lang, release;
        String encoding = "", source = "OpenSubtitles";
        boolean hearingImpaired;
    }

    // ---- Search and download (OpenSubtitles, per language) ----

    /** OpenSubtitles' language codes (ISO 639-2/B, plus pob) for the language list; the rest of the world can type nothing, so this is long. */
    static final String[] LANGS = ("ara,ben,bul,chi,hrv,cze,dan,dut,eng,est,fin,fre,ger,ell,heb,hin,hun,ind,ita,jpn,kan,kor,lav,lit,"
        + "may,mal,mar,nor,per,pol,por,pob,rum,rus,scc,sin,slo,slv,spa,swe,tam,tel,tha,tur,ukr,urd,vie").split(",");

    /** The /T spellings Android uses (deu, fra) as OpenSubtitles' /B ones (ger, fre). */
    static String osCode(String iso3) {
        String c = iso3 == null ? "" : iso3.toLowerCase(Locale.ROOT);
        String[] pairs = {"deu ger", "fra fre", "nld dut", "zho chi", "ces cze", "ron rum", "fas per", "slk slo", "msa may", "srp scc",
            "sqi alb", "hye arm", "eus baq", "mya bur", "kat geo", "isl ice", "mkd mac", "cym wel", "gre ell"};
        for (String p : pairs) if (p.startsWith(c + " ")) return p.substring(4);
        return c;
    }

    /** Language list order: the one picked last, the device's, English, then by name. */
    static List<String> languages() {
        List<String> rest = new ArrayList<>(java.util.Arrays.asList(LANGS)), out = new ArrayList<>();
        for (String first : new String[]{osCode(preferred()), osCode(Locale.getDefault().getISO3Language()), "eng"})
            if (!out.contains(first)) out.add(first);
        rest.removeAll(out);
        rest.sort((a, b) -> language(a).compareToIgnoreCase(language(b)));
        out.addAll(rest);
        return out;
    }

    static final SubOs.Http HTTP = url -> {
        java.net.HttpURLConnection c = Warp.open(url);
        c.setRequestProperty("User-Agent", SubOs.UA);   // OpenSubtitles' legacy API wants this one
        return c.getInputStream();
    };

    /**
     * Files in one language for a movie (season 0) or episode: OpenSubtitles' (most downloaded first), then SubDL's when you
     * gave a key (single episodes only: a season pack's zip holds the wrong ones). Falls back to the Stremio addon's sample
     * when OpenSubtitles can't be reached. Null when nothing could be asked. Off the main thread.
     */
    static List<Sub> search(String imdb, int season, int episode, String lang) {
        String code = osCode(lang);
        List<Sub> out = new ArrayList<>();
        boolean asked = false;
        try {
            for (SubOs.Result r : SubOs.search(HTTP, imdb, season, episode, code)) {
                Sub s = new Sub();
                s.url = r.url;
                s.lang = r.lang.isEmpty() ? code : r.lang;
                s.release = r.release;
                s.encoding = r.encoding;
                s.hearingImpaired = r.hearingImpaired;
                out.add(s);
            }
            asked = true;
        } catch (java.io.IOException e) {
            List<Sub> sample = find(imdb, season, episode);
            if (sample != null) { asked = true; for (Sub s : sample) if (osCode(s.lang).equals(code)) out.add(s); }
        }
        return asked ? out : null;
    }

    /** The file as a local UTF-8 .srt / .ass / .vtt (downloaded, unpacked and converted once, then kept). Off the main thread. */
    static SubFetch.Fetched fetch(Sub s) throws java.io.IOException {
        java.io.File dir = new java.io.File(App.app.getCacheDir(), "subs");
        dir.mkdirs();
        return SubFetch.fetch(HTTP, s.url, s.encoding, s.lang, dir);
    }

    /** How far (dp) subtitles sit above the default spot (20 dp from the bottom); negative = lower. Kept across videos. */
    static int offset() {
        if (!App.prefs.getBoolean("subBase20", false)) {
            // Saved when the default spot was 100 dp up: a spot somebody picked stays where it was (20 + old + 80 = 100 + old). Default stays default.
            int old = App.prefs.getInt("subOffset", 0);
            android.content.SharedPreferences.Editor e = App.prefs.edit().putBoolean("subBase20", true);
            if (old != 0) e.putInt("subOffset", old + 80);
            e.apply();
        }
        return App.prefs.getInt("subOffset", 0);
    }

    static String offsetLabel() {
        int o = offset();
        return o == 0 ? App.t(R.string.subs_default) : App.t(o > 0 ? R.string.subs_up : R.string.subs_down, Math.abs(o));
    }

    /** Subtitle text size in percent of the default (22 sp). */
    static int size() {
        return App.prefs.getInt("subSize", 100);
    }

    static String sizeLabel() {
        return size() == 100 ? App.t(R.string.subs_default) : size() + "%";
    }

    static final int[] COLORS = {0xFFFFFFFF, 0xFFFFE14D, 0xFF7FE7FF, 0xFF8CFF8C, 0xFFFFB3D1, 0xFFFFA64D};
    static final int[] COLOR_NAMES = {R.string.subs_white, R.string.subs_yellow, R.string.subs_cyan, R.string.subs_green, R.string.subs_pink, R.string.subs_orange};

    /** Subtitle text color, an index into COLORS. */
    static int colorIndex() {
        return Math.floorMod(App.prefs.getInt("subColor", 0), COLORS.length);
    }

    static String colorName() {
        return App.t(COLOR_NAMES[colorIndex()]);
    }

    static int color() {
        return COLORS[colorIndex()];
    }

    /** The box behind subtitles, percent opacity: 0 = none (the text keeps its shadow). */
    static int background() {
        return App.prefs.getInt("subBg", 50);
    }

    static String backgroundLabel() {
        return background() == 0 ? App.t(R.string.player_off) : background() + "%";
    }

    /** Movies and episodes start with subtitles in your language (stream's own, else OpenSubtitles). Off by default. */
    static boolean auto() {
        return App.prefs.getBoolean("subAuto", false);
    }

    /** ISO 639-2 code of the last OpenSubtitles language picked, else the device's language. */
    static String preferred() {
        return App.prefs.getString("subLang", Locale.getDefault().getISO3Language());
    }

    /** The file picked for a movie or episode (`key` from the player), null if none: it comes back by itself next time. */
    static Sub remembered(String key) {
        try {
            JSONObject o = new JSONObject(App.prefs.getString("subPicks", "{}")).optJSONObject(key);
            if (o == null) return null;
            Sub s = new Sub();
            s.url = o.getString("url");
            s.lang = o.optString("lang", "und");
            s.release = o.optString("release");
            s.encoding = o.optString("encoding");
            s.source = o.optString("source", "OpenSubtitles");
            return s;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Keeps the pick for `key` (null forgets it) and makes its language the preferred one. The newest 300 are kept. */
    /** The stream's own subtitle track picked for a video (same keys as remember): its language, its name and its place in the list; null when none. */
    static JSONObject rememberedTrack(String key) {
        try {
            return new JSONObject(App.prefs.getString("subTracks", "{}")).optJSONObject(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** Remembers (lang null: forgets) the stream's own subtitle track picked for a video, so resuming it turns the same one on. */
    static void rememberTrack(String key, String lang, String label, int index) {
        try {
            JSONObject all = new JSONObject(App.prefs.getString("subTracks", "{}"));
            all.remove(key);
            if (lang != null) all.put(key, new JSONObject().put("lang", lang).put("label", label).put("index", index));
            while (all.length() > 300) all.remove(all.keys().next());
            App.prefs.edit().putString("subTracks", all.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void remember(String key, Sub s) {
        try {
            JSONObject all = new JSONObject(App.prefs.getString("subPicks", "{}"));
            all.remove(key);
            if (s != null) all.put(key, new JSONObject().put("url", s.url).put("lang", s.lang).put("release", s.release).put("encoding", s.encoding).put("source", s.source));
            // Android's JSONObject keeps insertion order: the first keys are the oldest
            while (all.length() > 300) all.remove(all.keys().next());
            android.content.SharedPreferences.Editor e = App.prefs.edit().putString("subPicks", all.toString());
            if (s != null) e.putString("subLang", s.lang);
            e.apply();
        } catch (org.json.JSONException ignored) { }
    }

    /** Subtitle timing for the video playing now, in ms (not saved): positive shows them later, negative earlier. Read on the player's thread. */
    static volatile long delayMs;

    static String delayLabel() {
        return delayMs == 0 ? App.t(R.string.subs_in_sync) : String.format(Locale.ROOT, "%+.1f s", delayMs / 1000f);
    }

    private static void bump(String key, int def, int by, int min, int max) {
        App.prefs.edit().putInt(key, Math.max(min, Math.min(max, App.prefs.getInt(key, def) + by))).apply();
    }

    /**
     * One panel for how subtitles look: Size, Position and (in the player) Timing, each with − and + buttons, and Reset.
     * Every press applies at once; `changed` runs after each, `closed` when the panel goes away. It sits at the top so the subtitles stay in view.
     */
    static void panel(android.app.Activity a, boolean timing, Runnable changed, Runnable closed) {
        android.widget.LinearLayout body = new android.widget.LinearLayout(a);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        List<Runnable> refresh = new ArrayList<>();
        Runnable after = () -> { for (Runnable r : refresh) r.run(); changed.run(); };
        row(a, body, refresh, App.t(R.string.subs_size), Subs::sizeLabel, by -> bump("subSize", 100, 10 * by, 50, 250), after);
        row(a, body, refresh, App.t(R.string.subs_position), Subs::offsetLabel, by -> bump("subOffset", 0, 10 * by, -20, 280), after);   // 20 dp up is the default spot: down stops at the edge
        row(a, body, refresh, App.t(R.string.subs_color), Subs::colorName, by -> App.prefs.edit().putInt("subColor", Math.floorMod(colorIndex() + by, COLORS.length)).apply(), after);
        row(a, body, refresh, App.t(R.string.subs_background), Subs::backgroundLabel, by -> bump("subBg", 50, 25 * by, 0, 100), after);
        if (timing) row(a, body, refresh, App.t(R.string.subs_timing), Subs::delayLabel, by -> delayMs = Math.max(-600000, Math.min(600000, delayMs + 100L * by)), after);
        show(a, App.t(R.string.player_subtitles), body, App.t(timing ? R.string.subs_help_player : R.string.subs_help_settings), () -> {
            App.prefs.edit().remove("subSize").remove("subOffset").remove("subBg").remove("subColor").apply();
            if (timing) delayMs = 0;
            after.run();
        }, closed);
    }

    /**
     * The frame of a "− value +" panel: title, the rows in `body` (they scroll when the screen is short, a phone in landscape), a help
     * line and Reset / Done. It sits at the top so what it changes (subtitles) stays in view; `closed` runs when it goes away.
     */
    static void show(android.app.Activity a, String heading, android.widget.LinearLayout body, String helpText, Runnable reset, Runnable closed) {
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Home.dp(a, 20);
        box.setPadding(pad, pad, pad, Home.dp(a, 12));
        box.setBackground(Home.rounded(0xF0181320, Home.dp(a, 18)));
        android.widget.TextView title = new android.widget.TextView(a);
        title.setText(heading);
        title.setTextColor(Home.TEXT);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(0, 0, 0, Home.dp(a, 8));
        box.addView(title);
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.setFillViewport(false);
        scroll.addView(body);
        box.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.TextView help = new android.widget.TextView(a);
        help.setText(helpText);
        help.setTextColor(Home.MUTED);
        help.setTextSize(13);
        help.setPadding(0, Home.dp(a, 6), 0, Home.dp(a, 4));
        body.addView(help);
        android.widget.LinearLayout foot = new android.widget.LinearLayout(a);
        foot.setGravity(android.view.Gravity.END);
        foot.addView(button(a, App.t(R.string.subs_reset), 0, v -> reset.run()));
        foot.addView(button(a, App.t(R.string.subs_done), 0, v -> d.dismiss()));
        box.addView(foot);
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        w.setGravity(android.view.Gravity.TOP);
        w.setDimAmount(0.15f);
        // As tall as it needs, but never past the screen (then the rows scroll)
        int fullH = a.getResources().getDisplayMetrics().heightPixels - Home.dp(a, 32);
        box.measure(android.view.View.MeasureSpec.makeMeasureSpec(Math.min(a.getResources().getDisplayMetrics().widthPixels - Home.dp(a, 24), Home.dp(a, 560)), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.UNSPECIFIED);
        w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels - Home.dp(a, 24), Home.dp(a, 560)), Math.min(fullH, box.getMeasuredHeight()));
        w.getAttributes().y = Home.dp(a, 16);
        d.setOnDismissListener(x -> closed.run());
        d.show();
        body.findViewWithTag("plus").requestFocus();   // the remote starts on the first row's +
    }

    /**
     * A confirmation in the app's own look: a title, a row (an icon and a line of text) for each thing it says, and two icon buttons:
     * Cancel, and the one that does it (filled).
     */
    static void ask(android.app.Activity a, String heading, int[] icons, String[] lines, int okIcon, String okLabel, Runnable ok) {
        ask(a, heading, icons, lines, okIcon, okLabel, ok, null, null);
    }

    /** Same, and a row whose entry in `edits` is not null can be pressed (a pencil at its end): that runs, after the dialog has closed. */
    static void ask(android.app.Activity a, String heading, int[] icons, String[] lines, int okIcon, String okLabel, Runnable ok,
                    Runnable[] edits, String[] editLabels) {
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Home.dp(a, 22);
        box.setPadding(pad, pad, pad, Home.dp(a, 16));
        box.setBackground(Home.rounded(0xF0181320, Home.dp(a, 18)));
        android.widget.TextView title = new android.widget.TextView(a);
        title.setText(heading);
        title.setTextColor(Home.TEXT);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(0, 0, 0, Home.dp(a, 10));
        box.addView(title);
        for (int i = 0; i < lines.length; i++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Home.dp(a, 7), 0, Home.dp(a, 7));
            android.widget.ImageView icon = new android.widget.ImageView(a);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(Home.MUTED);
            android.widget.LinearLayout.LayoutParams il = new android.widget.LinearLayout.LayoutParams(Home.dp(a, 22), Home.dp(a, 22));
            il.rightMargin = Home.dp(a, 14);
            row.addView(icon, il);
            android.widget.TextView line = new android.widget.TextView(a);
            line.setText(lines[i]);
            line.setTextColor(Home.TEXT);
            line.setTextSize(16);
            line.setMaxLines(4);
            row.addView(line, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
            if (edits != null && edits[i] != null) {
                final Runnable edit = edits[i];
                android.widget.ImageView pencil = new android.widget.ImageView(a);
                pencil.setImageResource(R.drawable.ic_edit);
                pencil.setColorFilter(Home.ACCENT);
                pencil.setContentDescription(editLabels[i]);
                android.widget.LinearLayout.LayoutParams pl = new android.widget.LinearLayout.LayoutParams(Home.dp(a, 22), Home.dp(a, 22));
                pl.leftMargin = Home.dp(a, 12);
                row.addView(pencil, pl);
                // looks like every other row (same padding, no box); the remote's ring shows only when it is on it
                android.graphics.drawable.GradientDrawable ring = Home.rounded(0, Home.dp(a, 10));
                ring.setStroke(Home.dp(a, 2), android.graphics.Color.WHITE);
                android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
                st.addState(new int[]{android.R.attr.state_focused}, ring);
                row.setBackground(st);
                row.setFocusable(true);
                row.setClickable(true);
                row.setOnClickListener(v -> { d.dismiss(); edit.run(); });
            }
            box.addView(row);
        }
        android.widget.LinearLayout foot = new android.widget.LinearLayout(a);
        foot.setGravity(android.view.Gravity.END);
        foot.setPadding(0, Home.dp(a, 14), 0, 0);
        foot.addView(pill(a, R.drawable.ic_p_close, a.getString(android.R.string.cancel), false, v -> d.dismiss()));
        android.widget.LinearLayout yes = pill(a, okIcon, okLabel, true, v -> { d.dismiss(); ok.run(); });
        foot.addView(yes);
        box.addView(foot);
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        w.setGravity(android.view.Gravity.CENTER);
        w.setDimAmount(0.4f);
        w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels - Home.dp(a, 24), Home.dp(a, 560)), -2);
        d.show();
        yes.requestFocus();
    }

    /** The folder for downloads: a preset or one of your own (under Download, Movies or Documents); `done` runs after it changed. */
    static void pickFolder(android.app.Activity a, Runnable done) {
        String[] presets = {"Download/AnantPlay", "Movies/AnantPlay", "Documents/AnantPlay"};
        String[] items = {Downloads.root() + "/" + presets[0], Downloads.root() + "/" + presets[1], Downloads.root() + "/" + presets[2], App.t(R.string.dl_folder_other)};
        new android.app.AlertDialog.Builder(a).setTitle(App.t(R.string.dl_folder)).setOnCancelListener(d -> done.run()).setItems(items, (d, i) -> {
            if (i < presets.length) { App.prefs.edit().putString("dlFolder", presets[i]).apply(); done.run(); return; }
            android.widget.EditText in = WebActivity.field(a, Downloads.folder(), android.text.InputType.TYPE_CLASS_TEXT);
            in.setText(Downloads.folder());
            new android.app.AlertDialog.Builder(a).setTitle(App.t(R.string.dl_folder_prompt)).setView(WebActivity.box(a, in))
                .setPositiveButton(android.R.string.ok, (d2, w) -> {
                    String f = in.getText().toString().trim().replaceAll("[\\\\:*?\"<>|]", "").replaceAll("^/+|/+$", "");
                    if (!f.matches("(?i)(Download|Movies|Documents)(/.*)?")) { android.widget.Toast.makeText(a, App.t(R.string.dl_folder_bad), android.widget.Toast.LENGTH_LONG).show(); return; }
                    String root = f.split("/")[0];
                    f = root.substring(0, 1).toUpperCase() + root.substring(1).toLowerCase() + f.substring(root.length());   // Android wants Download, Movies, Documents
                    App.prefs.edit().putString("dlFolder", f).apply();
                    done.run();
                }).setNegativeButton(android.R.string.cancel, (d2, w) -> done.run()).setOnCancelListener(d2 -> done.run()).show();
        }).show();
    }

    /** The file name (without its extension): whatever is typed is kept, minus what a file system refuses. `done` gets the name, or the old one if it was cancelled. */
    static void pickName(android.app.Activity a, String current, java.util.function.Consumer<String> done) {
        android.widget.EditText in = WebActivity.field(a, current, android.text.InputType.TYPE_CLASS_TEXT);
        in.setText(current);
        in.setSelection(current.length());
        new android.app.AlertDialog.Builder(a).setTitle(App.t(R.string.dl_name_title)).setView(WebActivity.box(a, in))
            .setPositiveButton(android.R.string.ok, (d, w) -> done.accept(Downloads.safeName(in.getText().toString())))
            .setNegativeButton(android.R.string.cancel, (d, w) -> done.accept(current))
            .setOnCancelListener(d -> done.accept(current)).show();
    }

    /** A rounded button of an icon and a short label; filled with the accent when it is the one that does the thing. */
    static android.widget.LinearLayout pill(android.app.Activity a, int icon, String label, boolean filled, android.view.View.OnClickListener click) {
        android.widget.LinearLayout b = new android.widget.LinearLayout(a);
        b.setGravity(android.view.Gravity.CENTER);
        b.setPadding(Home.dp(a, 18), 0, Home.dp(a, 20), 0);
        android.widget.ImageView ic = new android.widget.ImageView(a);
        ic.setImageResource(icon);
        ic.setColorFilter(android.graphics.Color.WHITE);
        android.widget.LinearLayout.LayoutParams il = new android.widget.LinearLayout.LayoutParams(Home.dp(a, 20), Home.dp(a, 20));
        il.rightMargin = Home.dp(a, 8);
        b.addView(ic, il);
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(label);
        t.setTextColor(android.graphics.Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.addView(t);
        int r = Home.dp(a, 22);
        android.graphics.drawable.GradientDrawable on = Home.rounded(filled ? Home.ACCENT : 0x40FFFFFF, r);
        on.setStroke(Home.dp(a, 3), android.graphics.Color.WHITE);   // the remote's place
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, Home.rounded(filled ? Home.ACCENT : 0x26FFFFFF, r));
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, Home.dp(a, 44));
        lp.leftMargin = Home.dp(a, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /** Label, − button, value, + button. `step` gets −1 or +1. */
    static void row(android.app.Activity a, android.widget.LinearLayout box, List<Runnable> refresh, String label,
                            java.util.function.Supplier<String> value, java.util.function.IntConsumer step, Runnable after) {
        android.widget.LinearLayout r = new android.widget.LinearLayout(a);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(0, Home.dp(a, 4), 0, Home.dp(a, 4));
        android.widget.TextView name = new android.widget.TextView(a);
        name.setText(label);
        name.setTextColor(Home.TEXT);
        name.setTextSize(17);
        r.addView(name, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        android.widget.TextView now = new android.widget.TextView(a);
        now.setTextColor(Home.TEXT);
        now.setTextSize(17);
        now.setGravity(android.view.Gravity.CENTER);
        now.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        refresh.add(() -> now.setText(value.get()));
        now.setText(value.get());
        r.addView(button(a, "\u2212", Home.dp(a, 52), v -> { step.accept(-1); after.run(); }));
        r.addView(now, new android.widget.LinearLayout.LayoutParams(Home.dp(a, 130), -2));
        android.widget.TextView plus = button(a, "+", Home.dp(a, 52), v -> { step.accept(1); after.run(); });
        plus.setTag("plus");
        r.addView(plus);
        box.addView(r);
    }

    /** A rounded button with the accent ring when focused (remote) or pressed; `size` 0 = as wide as its text. */
    private static android.widget.TextView button(android.app.Activity a, String text, int size, android.view.View.OnClickListener click) {
        android.widget.TextView b = new android.widget.TextView(a);
        b.setText(text);
        b.setTextColor(Home.TEXT);
        b.setTextSize(size > 0 ? 24 : 16);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        int r = Home.dp(a, 26);
        android.graphics.drawable.GradientDrawable on = Home.rounded(0x55FF3B5C, r);
        on.setStroke(Home.dp(a, 3), Home.ACCENT);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{android.R.attr.state_pressed}, on);
        bg.addState(new int[]{}, Home.rounded(0x26FFFFFF, r));
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        android.widget.LinearLayout.LayoutParams lp = size > 0 ? new android.widget.LinearLayout.LayoutParams(size, size) : new android.widget.LinearLayout.LayoutParams(-2, Home.dp(a, 44));
        lp.setMargins(Home.dp(a, 6), 0, Home.dp(a, 6), 0);
        if (size == 0) b.setPadding(Home.dp(a, 20), 0, Home.dp(a, 20), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** season 0 = a movie. Null when OpenSubtitles can't be reached. Call off the main thread. */
    static List<Sub> find(String imdb, int season, int episode) {
        String path = season > 0 ? "series/" + imdb + ":" + season + ":" + episode : "movie/" + imdb;
        try {
            JSONArray a = new JSONObject(Sites.readAll(Warp.open(BASE + path + ".json").getInputStream())).getJSONArray("subtitles");
            List<Sub> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sub s = new Sub();
                s.url = o.getString("url");
                s.lang = o.optString("lang", "und");
                // The file name tells releases apart (the movie name is the same on every row)
                s.release = o.optString("subtitleFileName", "").isEmpty() ? o.optString("movieReleaseName", App.t(R.string.subs_file_n, i + 1)) : o.optString("subtitleFileName");
                out.add(s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** IMDb id of the best Cinemeta match for a page's title, "" when nothing matches. */
    static String imdbFor(String title, boolean series) {
        List<Catalog.Title> hit = Catalog.list(series ? "series" : "movie", "search=" + android.net.Uri.encode(title));
        return hit == null || hit.isEmpty() ? "" : hit.get(0).id;
    }

    // OpenSubtitles' own codes that are not ISO languages
    static final Map<String, String> ODD = new LinkedHashMap<>();
    static {
        ODD.put("pob", "Portuguese (Brazil)"); ODD.put("zht", "Chinese (Traditional)"); ODD.put("zhc", "Chinese (Cantonese)");
        ODD.put("zhe", "Chinese (bilingual)"); ODD.put("spl", "Spanish (Latin America)"); ODD.put("spn", "Spanish (Spain)");
        ODD.put("ex", "Extremaduran"); ODD.put("ze", "Chinese (bilingual)"); ODD.put("pom", "Portuguese");
        ODD.put("scc", "Serbian");
    }

    static String language(String iso3) {
        if (ODD.containsKey(iso3)) return ODD.get(iso3);
        // the /B codes OpenSubtitles uses (ger, fre) through their /T spelling, which Android names
        for (String p : new String[]{"deu ger", "fra fre", "nld dut", "zho chi", "ces cze", "ron rum", "fas per", "slk slo", "msa may", "ell gre"})
            if (p.endsWith(" " + iso3)) iso3 = p.substring(0, 3);
        String n = new Locale(iso3).getDisplayLanguage();
        return n.isEmpty() || n.equals(iso3) ? iso3.toUpperCase(Locale.ROOT) : n;
    }
}
