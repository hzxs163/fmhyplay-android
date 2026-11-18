package me.champdev.weblock;

import android.widget.TextView;

import java.util.List;

/** Every site the sources list for one row ("category") or one language, as a grid (picks first). Same cards and menus as Home. */
public class CategoryActivity extends Home {
    @Override boolean tabs() { return false; }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        String language = getIntent().getStringExtra("language"), category = getIntent().getStringExtra("category");
        String name = language != null ? language : category;
        List<Sites.Site> sites = language != null ? Sites.languages().get(language) : Sites.load().get(category);

        TextView title = text(name + (sites == null ? "" : "  ·  " + sites.size()), sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);
        TextView hint = text(App.t(App.tv ? R.string.category_hint_tv : R.string.category_hint_touch), 13, MUTED, false);
        hint.setPadding(0, 0, 0, dp(14));
        col.addView(hint);
        if (sites == null) return;

        Grid grid = new Grid(col);
        String group = null;
        for (Sites.Site s : sites) {
            // Picks first, then one block per section of the list (P-Stream forks, multi-server sites... each with its own heading)
            String now = s.pick ? App.t(R.string.category_picks) : s.index ? App.t(R.string.category_more_lists) : language != null ? App.t(R.string.category_more_sites) : s.group;
            if (!now.equals(group)) {
                group = now;
                col.addView(heading(now));
                grid.breakRow();
            }
            grid.add(s);
        }
        android.view.View first = firstCard;   // at once; not when coming back to this list (the focus returns to the card it left)
        if (App.tv && first != null && !skipFocus && !first.requestFocus()) col.post(first::requestFocus);
    }
}
