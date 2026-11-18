package me.champdev.weblock;

import android.app.Activity;
import android.content.Intent;
import android.widget.TextView;

import java.util.List;

/** All of a row's titles as a grid, in the row's order: what a row's More card opens. */
public class ListActivity extends Home {
    // ponytail: the list is handed over in memory (rows come from brand lists, My list and search: no one address to load it again from);
    // after the system restarts the app in the background this screen closes back to the row
    static String name = "";
    static List<Catalog.Title> titles;

    static void open(Activity from, String name, List<Catalog.Title> titles) {
        ListActivity.name = name;
        ListActivity.titles = titles;
        from.startActivity(new Intent(from, ListActivity.class));
    }

    @Override boolean tabs() { return false; }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        if (titles == null) { finish(); return; }
        TextView title = text(name + "  ·  " + titles.size(), sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);
        Grid grid = new Grid(col, posterCols());
        for (Catalog.Title t : titles) grid.add(posterCard(t, grid.cell));
        android.view.View first = firstCard;   // at once; not when coming back to this list (the focus returns to the card it left)
        if (App.tv && first != null && !skipFocus && !first.requestFocus()) col.post(first::requestFocus);
    }
}
