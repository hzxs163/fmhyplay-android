package me.champdev.weblock;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Type to find a site (by name, tag, language or row) or something you watched. */
public class SearchActivity extends Home {
    static final int MAX_SITES = 120, DEBOUNCE = 450;   // ms of quiet after the last key
    // Its own threads: the shared pool is often busy with poster downloads, and the answer would wait behind them
    static final ExecutorService lane = Executors.newCachedThreadPool();
    EditText box;
    LinearLayout results;
    String query = "";
    boolean keyboardShown;
    final Runnable render = this::showResults;
    final Runnable fetchTitles = this::searchTitles;
    List<Catalog.Title> titles = new ArrayList<>();
    String titlesFor = "";
    boolean titlesFailed;   // the catalog could not be reached for titlesFor

    @Override boolean tabs() { return false; }

    @Override boolean kindBadges() { return true; }   // movies, series and anime share one grid here

    @Override protected void onDestroy() {
        App.main.removeCallbacks(render);   // a pending redraw must not outlive the screen
        App.main.removeCallbacks(fetchTitles);
        super.onDestroy();
    }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        // The box drops down your recent searches (all of them while it is empty, the ones that start the same while typing)
        android.widget.AutoCompleteTextView ac = new android.widget.AutoCompleteTextView(this) {
            @Override public boolean enoughToFilter() { return true; }   // an empty box lists them all (else the list closes itself)
        };
        ac.setHint(App.t(R.string.search_hint));
        ac.setInputType(InputType.TYPE_CLASS_TEXT);
        ac.setTypeface(android.graphics.Typeface.DEFAULT);
        ac.setThreshold(1);
        ac.setAdapter(recentAdapter());
        ac.setDropDownBackgroundDrawable(rounded(CARD, dp(14)));
        ac.setDropDownVerticalOffset(dp(6));
        ac.setOnItemClickListener((p, v, i, id) -> searchNow());
        ac.setOnClickListener(v -> showRecent());
        box = ac;
        box.setSingleLine(true);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setTextColor(TEXT);
        box.setHintTextColor(MUTED);
        box.setTextSize(sp(20, 17));
        box.setBackground(rounded(CARD, dp(14)));
        box.setPadding(u(18, 14), u(14, 10), u(18, 14), u(14, 10));
        iconize(box, R.drawable.ic_search, MUTED);
        box.setTextColor(TEXT);
        focusable(box, dp(14));
        android.view.View.OnFocusChangeListener ring = box.getOnFocusChangeListener();   // focusable's ring, plus the recent searches
        box.setOnFocusChangeListener((v, has) -> { ring.onFocusChange(v, has); if (has) showRecent(); });
        box.setText(query);
        box.setSelection(query.length());
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                query = e.toString();
                if (query.isEmpty()) showRecent();
                if (App.tv) return;   // a TV searches on Enter (the keyboard's Search key): the page stays still while the remote types
                // Nothing is searched or redrawn until typing pauses
                App.main.removeCallbacks(render);
                App.main.removeCallbacks(fetchTitles);
                App.main.postDelayed(render, DEBOUNCE);
                App.main.postDelayed(fetchTitles, DEBOUNCE);
            }
        });
        // "Search" on the keyboard: hide it so the arrows can move over the results
        box.setOnEditorActionListener((v, action, ev) -> { searchNow(); return true; });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView mic = iconButton(R.drawable.ic_mic, App.t(R.string.search_voice), v -> listen(this::spoken));
        ((LinearLayout.LayoutParams) mic.getLayoutParams()).setMargins(0, 0, dp(10), 0);
        mic.setColorFilter(TEXT);
        bar.addView(mic);   // left of the box
        bar.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results, new LinearLayout.LayoutParams(-1, -2));
        showResults();

        // Keyboard up on the first visit only, not every time you come back from a site
        if (!keyboardShown) {
            keyboardShown = true;
            box.requestFocus();
            if (getIntent().getBooleanExtra("voice", false)) box.post(() -> listen(this::spoken));   // opened from the mic chip
            else if (!App.tv) getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    /** What was said goes in the box and is searched at once (no Enter to press after speaking). */
    void spoken(String said) {
        box.setText(said);
        box.setSelection(said.length());
        searchNow();
    }

    /** Search pressed (or a recent search picked): no waiting, the search is kept, the keyboard goes so the arrows reach the results. */
    boolean focusResults;   // TV: Enter was pressed, the results are on their way

    void searchNow() {
        query = box.getText().toString();
        focusResults = App.tv;
        remember(query);
        App.main.removeCallbacks(render);
        App.main.removeCallbacks(fetchTitles);
        ((android.widget.AutoCompleteTextView) box).dismissDropDown();
        showResults();
        searchTitles();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(box.getWindowToken(), 0);
        if (firstCard != null) firstCard.requestFocus();
    }

    // ---- recent searches: prefs "searches", newest first ----

    static final int RECENT = 15;

    static List<String> recent() {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(App.prefs.getString("searches", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    /** Kept when it was searched for real: Search pressed, a recent one picked, or something in the results opened. */
    static void remember(String q) {
        q = q.trim().replaceAll("\\s+", " ");
        if (q.length() < 2) return;
        List<String> all = recent();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).equalsIgnoreCase(q)) all.remove(i);
        all.add(0, q);
        App.prefs.edit().putString("searches", new org.json.JSONArray(all.subList(0, Math.min(RECENT, all.size()))).toString()).apply();
    }

    android.widget.ArrayAdapter<String> recentAdapter() {
        return new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, recent()) {
            @Override public android.view.View getView(int i, android.view.View v, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(i, v, parent);
                t.setTextColor(TEXT);
                t.setTextSize(sp(17, 15));
                t.setPadding(u(18, 14), u(12, 10), u(18, 14), u(12, 10));
                android.graphics.drawable.Drawable d = getDrawable(R.drawable.ic_search).mutate();
                d.setTint(MUTED);
                d.setBounds(0, 0, dp(20), dp(20));
                t.setCompoundDrawablesRelative(d, null, null, null);
                t.setCompoundDrawablePadding(dp(14));
                return t;
            }
        };
    }

    /** The whole list while the box is empty (an empty box filters nothing by itself). */
    void showRecent() {
        android.widget.AutoCompleteTextView ac = (android.widget.AutoCompleteTextView) box;
        if (!ac.hasFocus() || ac.getText().length() > 0 || isFinishing()) return;
        ac.setAdapter(recentAdapter());   // read again: a search made on this screen is in it
        ((android.widget.ArrayAdapter<?>) ac.getAdapter()).getFilter().filter(null, n -> { if (n > 0 && ac.hasFocus() && ac.getText().length() == 0 && !isFinishing()) ac.showDropDown(); });
    }

    /** Opening a result (a title, a site, a channel) keeps the search; a screen lock, Home or the voice dialog does not. */
    @Override public void startActivity(android.content.Intent in) {
        remember(query);
        super.startActivity(in);
    }

    void showResults() {
        results.removeAllViews();
        firstCard = null;
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (query.trim().isEmpty()) {
            TextView tip = text(App.t(R.string.search_try), sp(14, 13), MUTED, false);
            tip.setPadding(dp(4), dp(16), 0, 0);
            results.addView(tip);
            return;
        }

        List<History.Entry> seen = new ArrayList<>();
        for (History.Entry e : History.list()) if (matches(e.title + " " + e.site + " " + e.episode, words)) seen.add(e);

        List<Sites.Site> found = new ArrayList<>();
        for (Sites.Site s : Sites.all()) {
            String hay = s.name + " " + s.tags + " " + s.group + " " + android.net.Uri.parse(s.url).getHost();
            if (matches(hay, words)) found.add(s);
        }
        final String first = words[0];
        found.sort((a, b) -> Integer.compare(rank(a, first), rank(b, first)));

        String q = query.trim();
        boolean pending = q.length() >= 2 && !titlesFor.equals(q);
        boolean haveTitles = titlesFor.equals(q) && !titles.isEmpty(), failed = titlesFor.equals(q) && titlesFailed;
        if (pending) {
            results.addView(heading(App.t(R.string.search_movies_series)));
            results.addView(searching());
        } else if (haveTitles) {
            // One sideways row each, so channels and sites below stay in view
            for (boolean series : new boolean[]{false, true}) {
                List<Catalog.Title> kind = new ArrayList<>();
                for (Catalog.Title t : titles) if (t.series() == series) kind.add(t);
                if (kind.isEmpty()) continue;
                String name = App.t(series ? R.string.search_series_n : R.string.search_movies_n, kind.size());
                results.addView(heading(name));
                posterRow(row(results), query, kind);
            }
        } else if (failed) {
            results.addView(heading(App.t(R.string.search_movies_series)));
            results.addView(note(App.t(R.string.search_catalog_failed)));
        }
        // IPTV channels whose name, group or list has every typed word (plain contains: there are thousands)
        List<Iptv.Channel> chans = new ArrayList<>();
        for (Iptv.Channel c : Iptv.list("all", "")) {
            String hay = (c.name + " " + c.group + " " + c.source).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { all = false; break; }
            if (all) chans.add(c);
        }

        if (seen.isEmpty() && found.isEmpty() && chans.isEmpty() && !haveTitles && !pending && !failed) {
            TextView none = text(App.t(R.string.search_nothing, q), sp(16, 14), MUTED, false);
            none.setPadding(dp(4), dp(16), 0, 0);
            results.addView(none);
            return;
        }
        if (!seen.isEmpty()) {
            results.addView(heading(App.t(R.string.search_you_watched)));
            LinearLayout row = row(results);
            for (History.Entry e : seen) row.addView(historyCard(e));
        }
        if (!chans.isEmpty()) {
            results.addView(heading(App.t(R.string.search_channels_n, chans.size())));
            int cols = App.tv ? 6 : 3;
            LinearLayout row = row(results);
            for (Iptv.Channel c : chans.subList(0, Math.min(14, chans.size()))) row.addView(channelCard(c, cols, "search"));
            if (chans.size() > 14) {
                TextView more = text(App.t(R.string.search_all_n, chans.size()), sp(17, 15), TEXT, true);
                more.setGravity(Gravity.CENTER);
                more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
                more.setBackground(rounded(0xFF2A2236, dp(14)));
                focusable(more, dp(14));
                more.setOnClickListener(v -> startActivity(new android.content.Intent(this, IptvActivity.class).putExtra("type", "all").putExtra("key", "")
                    .putExtra("title", App.t(R.string.search_channels_for, q)).putExtra("q", q)));
                row.addView(more);
            }
        }
        if (!found.isEmpty()) {
            boolean cut = found.size() > MAX_SITES;
            results.addView(heading(cut ? App.t(R.string.search_sites_cut, found.size(), MAX_SITES) : App.t(R.string.search_sites_n, found.size())));
            Grid grid = new Grid(results);
            for (Sites.Site s : cut ? found.subList(0, MAX_SITES) : found) grid.add(s);
        }
    }

    /** Asks the catalog for movies and series matching what is typed (after a short pause), then redraws. */
    void searchTitles() {
        final String q = query.trim();
        if (q.length() < 2 || q.equals(titlesFor)) return;
        String extra = "search=" + android.net.Uri.encode(q);
        // Movies, series and Kitsu's anime list (only for the Anime label) at the same time
        {
            CompletableFuture<java.util.Set<String>> anime = CompletableFuture.supplyAsync(() -> Catalog.animeIds(q), lane);
            CompletableFuture.supplyAsync(() -> Catalog.list("movie", extra), lane)
                .thenCombine(CompletableFuture.supplyAsync(() -> Catalog.list("series", extra), lane), (movies, series) -> {
                    List<Catalog.Title> all = new ArrayList<>();
                    if (movies != null) all.addAll(movies.subList(0, Math.min(20, movies.size())));   // 20 a kind at most
                    if (series != null) all.addAll(series.subList(0, Math.min(20, series.size())));
                    java.util.Set<String> ids = anime.join();
                    for (Catalog.Title t : all) t.anime = ids.contains(t.id);
                    // Cinemeta also returns loose matches (a series named "...Ankara..." for "kantara"): titles with every typed word come first,
                    // then newest to oldest in each group
                    String[] words = q.toLowerCase(Locale.ROOT).split("\\s+");
                    all.sort(java.util.Comparator.<Catalog.Title, Boolean>comparing(t -> !matches(t.name, words))
                        .thenComparing(t -> -Catalog.startYear(t.year)));
                    boolean failed = movies == null && series == null;
                    runOnUiThread(() -> {
                        if (!q.equals(query.trim())) return;
                        titles = all;
                        titlesFor = q;
                        titlesFailed = failed;
                        showResults();
                        // Enter on a TV: the results come after it, so the remote goes to the first one now (unless it already moved there)
                        if (focusResults && firstCard != null) { focusResults = false; if (!inside(getCurrentFocus(), results)) firstCard.requestFocus(); }
                    });
                    return null;
                });
        };
    }

    /** A spinner and a line while the catalog answers. */
    LinearLayout searching() {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(4), dp(8), 0, dp(8));
        line.addView(new Spinner(this), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = text(App.t(R.string.search_searching), sp(15, 13), MUTED, false);
        t.setPadding(dp(12), 0, 0, 0);
        line.addView(t);
        return line;
    }

    /** Every typed word must appear somewhere in the text, accents ignored ("amelie" finds Amélie). */
    static boolean matches(String text, String[] words) {
        String t = plain(text);
        for (String w : words) if (!w.isEmpty() && !t.contains(plain(w))) return false;
        return true;
    }

    static String plain(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Name starts with it, then name contains it, then the lists' picks, then the rest. */
    static int rank(Sites.Site s, String word) {
        String n = s.name.toLowerCase(Locale.ROOT);
        return (n.startsWith(word) ? 0 : n.contains(word) ? 2 : 4) + (s.pick ? 0 : 1);
    }
}
