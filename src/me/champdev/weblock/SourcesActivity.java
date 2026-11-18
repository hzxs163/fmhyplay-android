package me.champdev.weblock;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, Sources: AnantPlay's index is always offered, added only on request, plus any index added by link. An added index lists
 * its sources, each with its own Add; only added sources are downloaded. 18+ ones show once 18+ is switched on.
 */
public class SourcesActivity extends Home {
    static final List<String> busy = new ArrayList<>();   // indexes and sources being downloaded right now

    @Override boolean tabs() { return false; }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        TextView title = text(App.t(R.string.sources_title), sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);
        TextView hint = text(App.t(R.string.sources_hint), 13, MUTED, false);
        hint.setPadding(0, 0, 0, dp(14));
        col.addView(hint);

        List<String> indexes = new ArrayList<>(Sources.indexes());
        if (!indexes.contains(Sources.INDEX)) indexes.add(0, Sources.INDEX);
        for (String index : indexes) indexCard(index);

        single(R.drawable.ic_add);
        secBox.addView(settingRow(R.drawable.ic_add, App.t(R.string.sources_add_index), App.t(R.string.sources_add_index_help), null, v -> addIndexDialog()));
        android.view.View first = firstCard;
        if (App.tv && first != null && !skipFocus && !first.requestFocus()) col.post(first::requestFocus);
    }

    String indexName(String url) {
        return url.equals(Sources.INDEX) ? App.t(R.string.sources_index_name) : Sites.hostOf(url);
    }

    void indexCard(String index) {
        boolean added = Sources.indexes().contains(index);
        section(indexName(index), R.drawable.ic_folder, true);
        String help = index.equals(Sources.INDEX) ? App.t(R.string.sources_index_help) : index;
        secBox.addView(settingRow(R.drawable.ic_language, App.t(added ? R.string.sources_index_added : R.string.sources_index_add), help, null, v -> {
            if (!Sources.indexes().contains(index)) { Sources.addIndex(index); fetchIndex(index); }
            else confirm(App.t(R.string.sources_remove_index_title), App.t(R.string.sources_remove_index_msg, indexName(index)), App.t(R.string.home_remove), () -> {
                Sources.removeIndex(index);
                build();
            });
        }));
        if (!added) return;

        List<Sources.Source> listed = Sources.listed(index);
        if (listed.isEmpty()) {
            boolean loading = busy.contains(index);
            secBox.addView(settingRow(R.drawable.ic_refresh, App.t(loading ? R.string.sources_loading : R.string.sources_unreachable), index, null, v -> fetchIndex(index)));
            if (!loading && !Sources.indexFile(index).exists()) fetchIndex(index);
            return;
        }
        boolean adult = App.prefs.getBoolean("adult", false);
        for (Sources.Source s : listed) {
            if (s.adult && !adult) continue;
            boolean on = Sources.isAdded(s.url), loading = busy.contains(s.url);
            TextView value = new TextView(this);
            String name = s.name.isEmpty() ? App.t(R.string.sources_title_links) : s.name;
            String kind = App.t(s.type.equals(Sources.SITES) ? R.string.sources_type_sites : s.type.equals(Sources.PLAYLIST) ? R.string.sources_type_playlist : R.string.sources_type_deeplinks);
            String about = !s.known() ? App.t(R.string.sources_newer) : (kind.equals(name) ? "" : kind) + (s.adult ? " · 18+" : "") + (s.description.isEmpty() ? "" : " · " + s.description);
            about = about.replaceFirst("^ · ", "");
            int icon = s.type.equals(Sources.PLAYLIST) ? R.drawable.ic_live : s.type.equals(Sources.SITES) ? R.drawable.ic_language : R.drawable.ic_movie;
            secBox.addView(settingRow(icon, name, about, value, v -> {
                if (!s.known() || busy.contains(s.url)) return;
                if (Sources.isAdded(s.url)) { Sources.remove(s.url); build(); }
                else add(s);
            }));
            value.setText(App.t(!s.known() ? R.string.home_none : loading ? R.string.sources_loading : on ? R.string.sources_added : R.string.sources_add));
            value.setTextColor(on ? GREEN : s.known() ? ACCENT : MUTED);
        }
    }

    void fetchIndex(String index) {
        if (busy.contains(index)) return;
        busy.add(index);
        build();
        Warp.whenSettled(() -> App.io.execute(() -> {
            boolean ok = Sources.fetchIndex(index);
            runOnUiThread(() -> {
                busy.remove(index);
                if (!ok) toast(App.t(R.string.sources_unreachable_toast));
                if (!isFinishing()) build();
            });
        }));
    }

    void add(Sources.Source s) {
        Sources.add(s);
        busy.add(s.url);
        build();
        Warp.whenSettled(() -> App.io.execute(() -> {
            Sources.fetch(s);
            Sources.changed();
            boolean ok = Sources.data(s).exists();
            runOnUiThread(() -> {
                busy.remove(s.url);
                toast(App.t(ok ? R.string.sources_added_toast : R.string.sources_unreachable_toast, s.name.isEmpty() ? App.t(R.string.sources_title_links) : s.name));
                if (!isFinishing()) build();
            });
        }));
    }

    void addIndexDialog() {
        EditText in = WebActivity.field(this, "https://example.com/index.json", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle(App.t(R.string.sources_add_index)).setMessage(App.t(R.string.sources_add_index_help)).setView(WebActivity.box(this, in))
            .setPositiveButton(App.t(R.string.home_save), (d, w) -> {
                String url = in.getText().toString().trim();
                if (url.isEmpty()) return;
                if (!url.matches("(?i)https?://.+")) url = "https://" + url;
                Sources.addIndex(url);
                fetchIndex(url);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
