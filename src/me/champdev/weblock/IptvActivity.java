package me.champdev.weblock;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.AbsListView;
import android.widget.GridView;
import android.graphics.Color;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One IPTV group (a category, a country, favorites, adult, or everything) as a filterable grid of square tiles. OK plays, hold OK or Menu stars a channel. */
public class IptvActivity extends Home {
    final List<Iptv.Channel> all = new ArrayList<>(), shown = new ArrayList<>();
    String type, key;
    GridView list;
    int cell;   // tile edge in px
    String query = "";
    String category = "", language = "";   // category key and language name; "" = all
    android.widget.ImageView pin;
    LinearLayout filters;
    TextView count;
    BaseAdapter adapter;

    @Override boolean tabs() { return false; }

    @Override boolean lazyImages() { return false; }   // the grid makes tiles only for what is on screen

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);   // Home draws its own page first; replaced below
        type = getIntent().getStringExtra("type");
        key = getIntent().getStringExtra("key");
        all.addAll(Iptv.list(type, key));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(side, App.tv ? dp(28) : dp(10), side, 0);
        TextView title = text(getIntent().getStringExtra("title"), sp(26, 22), TEXT, true);
        root.addView(title);
        count = text("", sp(14, 12), MUTED, false);
        count.setPadding(0, dp(2), 0, dp(8));
        root.addView(count);

        EditText filter = new EditText(this);
        filter.setHint(App.t(R.string.iptv_filter));
        filter.setHintTextColor(MUTED);
        filter.setTextColor(TEXT);
        filter.setTextSize(sp(16, 15));
        filter.setSingleLine();
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.setBackground(rounded(CARD, dp(12)));
        filter.setPadding(dp(14), dp(10), dp(14), dp(10));
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }

            @Override public void afterTextChanged(Editable s) { }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView mic = iconButton(R.drawable.ic_mic, App.t(R.string.search_voice), v -> listen(filter::setText));
        ((LinearLayout.LayoutParams) mic.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        mic.setColorFilter(TEXT);
        bar.addView(mic);
        // Pin: this list with the chips that are on becomes a row of its own on the IPTV tab
        pin = iconButton(R.drawable.ic_pin, App.t(R.string.iptv_pin), v -> {
            boolean on = Iptv.togglePin(type, key, getIntent().getStringExtra("title"), category, language);
            toast(App.t(on ? R.string.iptv_pinned : R.string.iptv_unpinned, pinTitle()));
            pinState();
        });
        ((LinearLayout.LayoutParams) pin.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        bar.addView(pin);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        // Category and language chips (each row only when the list has more than one to choose from)
        filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.VERTICAL);
        root.addView(filters, new LinearLayout.LayoutParams(-1, -2));

        // Square tiles, 6 across on a TV and 3 on a phone
        int cols = App.tv ? 6 : 3, gap = dp(gapDp);
        cell = (winW() - 2 * side - gap * (cols - 1)) / cols - 1;
        list = new GridView(this);
        list.setNumColumns(cols);
        list.setHorizontalSpacing(gap);
        list.setVerticalSpacing(gap);
        list.setClipToPadding(false);
        list.setPadding(0, dp(10), 0, dp(20));
        GradientDrawable ring = rounded(0x22FF3B5C, dp(14));
        ring.setStroke(dp(3), ACCENT);
        // Only while the grid has the remote (or a finger is on a tile): a plain shape stays drawn on the last tile when the
        // remote moves up to the chips, which looks like two things have focus
        android.graphics.drawable.StateListDrawable sel = new android.graphics.drawable.StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_focused}, ring);
        sel.addState(new int[]{android.R.attr.state_pressed}, ring);
        sel.addState(new int[0], new android.graphics.drawable.ColorDrawable(0));
        list.setSelector(sel);
        list.setDrawSelectorOnTop(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }

            @Override public Object getItem(int i) { return shown.get(i); }

            @Override public long getItemId(int i) { return i; }

            @Override public View getView(int i, View v, ViewGroup parent) { View t = channelTile(shown.get(i), v, cell); t.setLayoutParams(new AbsListView.LayoutParams(cell, cell)); return t; }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((a, v, i, id) -> Iptv.play(this, shown.get(i)));
        list.setOnItemLongClickListener((a, v, i, id) -> { star(shown.get(i)); return true; });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(withNav(root));

        if (getIntent().hasExtra("cat")) category = getIntent().getStringExtra("cat");   // opened from a pinned row
        if (getIntent().hasExtra("lang")) language = getIntent().getStringExtra("lang");
        chips();
        String q = getIntent().getStringExtra("q");   // from Search's Channels row: the same words, every match
        if (q != null) filter.setText(q); else filter("");
        if (getIntent().getBooleanExtra("voice", false)) {
            list.requestFocus();
            list.post(() -> listen(filter::setText));
        } else if (getIntent().getBooleanExtra("typing", false)) {
            filter.requestFocus();
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        } else list.requestFocus();
    }

    @Override void build() {
        if (adapter == null) return;
        all.clear();
        all.addAll(Iptv.list(type, key));   // a star or a channel that just failed may have changed it
        filter(query);
    }

    String pinTitle() {
        return getIntent().getStringExtra("title") + (category.isEmpty() ? "" : " \u00B7 " + groupName(category)) + (language.isEmpty() ? "" : " \u00B7 " + language);
    }

    void pinState() {
        boolean on = Iptv.isPinned(type, key, category, language);
        pin.setColorFilter(on ? ACCENT : TEXT);
        pin.setContentDescription(App.t(on ? R.string.iptv_unpin : R.string.iptv_pin));
        if (android.os.Build.VERSION.SDK_INT >= 26) pin.setTooltipText(pin.getContentDescription());
    }

    /** The chip rows: the categories and languages in this list, the most common first. */
    void chips() {
        pinState();
        filters.removeAllViews();
        chipRow(App.t(R.string.iptv_all_categories), counts(true), category, true, v -> { category = v; chips(); filter(query); });
        chipRow(App.t(R.string.iptv_all_languages), counts(false), language, false, v -> { language = v; chips(); filter(query); });
    }

    /** The categories (as shown) or languages in this list, ranked like the IPTV tab: Iptv.categoryRank / Iptv.rankLanguages. */
    java.util.List<String> counts(boolean cats) {
        java.util.Map<String, Integer> n = new java.util.HashMap<>();
        java.util.Map<String, String> shownToKey = new java.util.HashMap<>();
        for (Iptv.Channel c : all) for (String v : cats ? c.cats : c.langs) {
            String label = cats ? groupName(v) : v;
            n.merge(label, 1, Integer::sum);
            shownToKey.put(label, v);
        }
        if (!cats) return Iptv.rankLanguages(n);
        java.util.Comparator<String> rank = Iptv.categoryRank(k -> n.get(groupName(k)));
        java.util.List<String> keys = new ArrayList<>(shownToKey.values());
        keys.sort(rank);
        return keys;
    }

    void chipRow(String allLabel, java.util.List<String> values, String now, boolean cats, java.util.function.Consumer<String> pick) {
        if (values.size() < 2) return;
        LinearLayout row = row(filters);
        values.add(0, "");
        TextView picked = null;
        for (String v : values) {
            TextView c = chip(x -> pick.accept(v));
            c.setText(v.isEmpty() ? allLabel : cats ? groupName(v) : v);
            boolean on = v.equals(now);
            c.setTextColor(on ? Color.WHITE : TEXT);
            c.setBackground(rounded(on ? ACCENT : CARD, dp(22)));
            row.addView(c);
            if (on && !v.isEmpty()) picked = c;
        }
        // Keep the chosen chip in view (the row is rebuilt after each pick)
        TextView keep = picked;
        if (keep != null) row.post(() -> ((android.widget.HorizontalScrollView) row.getParent()).scrollTo(Math.max(0, keep.getLeft() - dp(40)), 0));
    }

    boolean matches(Iptv.Channel c) {
        if (!category.isEmpty()) {
            boolean in = false;
            for (String v : c.cats) if (v.equals(category)) in = true;
            if (!in) return false;
        }
        if (!language.isEmpty()) {
            boolean in = false;
            for (String v : c.langs) if (v.equals(language)) in = true;
            if (!in) return false;
        }
        return true;
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        int i = list == null ? -1 : list.getSelectedItemPosition();
        if (k == KeyEvent.KEYCODE_MENU && list.hasFocus() && i >= 0 && i < shown.size()) { star(shown.get(i)); return true; }
        return super.onKeyUp(k, e);
    }

    void filter(String q) {
        query = q;
        String f = q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Iptv.Channel c : all) if (matches(c) && (f.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(f) || c.source.toLowerCase(Locale.ROOT).contains(f) || c.group.toLowerCase(Locale.ROOT).contains(f))) shown.add(c);
        adapter.notifyDataSetChanged();
        count.setText((shown.size() == 1 ? App.t(R.string.iptv_one_channel) : App.t(R.string.iptv_channels, shown.size()))
            + "  ·  " + App.t(App.tv ? R.string.iptv_hint_tv : R.string.iptv_hint_touch));
    }

    void star(Iptv.Channel c) {
        Iptv.toggleFavorite(c);
        toast(App.t(Iptv.isFavorite(c) ? R.string.iptv_starred : R.string.iptv_unstarred, c.name));
        if (type.equals("fav")) { all.remove(c); filter(""); } else adapter.notifyDataSetChanged();
    }
}
