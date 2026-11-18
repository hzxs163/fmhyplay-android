package me.champdev.weblock;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One streaming service: its brands as tiles (Disney+ > Marvel, Pixar...), then rows of what is on it, movies or series: popular,
 * top rated, new, and a row per genre.
 * Opened with "brand" it is that brand's page: rows of what is new, popular and top rated, a row per group of the brand
 * (Marvel: MCU, Avengers, X-Men...), then its movies and its series, newest first.
 * Every row shows 15 and ends in More, which opens all of it as a grid in the same order.
 */
public class PlatformActivity extends Home {
    String kind;   // the service's page: movie | series; a brand's page: "" both, movie | series
    boolean focusKind;   // a kind chip was just pressed: the remote stays on it

    @Override boolean tabs() { return false; }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        nextPage = null;
        stepCards = 0;
        built++;
        String id = getIntent().getStringExtra("platform"), brand = getIntent().getStringExtra("brand");
        String[] s = Catalog.service(id);
        if (s == null && brand == null) { finish(); return; }   // a brand can also come from a list that is no service (studios, kids)
        final int at = built;
        if (brand != null) {
            if (kind == null) kind = "";
            focusFirst = App.tv && !skipFocus;
            brandPage(id, brand, at);
            return;
        }
        if (kind == null) kind = "series".equals(getIntent().getStringExtra("kind")) ? "series" : "movie";

        TextView title = text(s[1], sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(6));
        col.addView(title);
        // Brands arrive a moment later (one small file, kept for 6 hours); a service without any keeps no gap
        LinearLayout brands = new LinearLayout(this);
        brands.setOrientation(LinearLayout.VERTICAL);
        col.addView(brands);
        App.io.execute(() -> {
            List<Catalog.Brand> list = Catalog.brands(id);
            runOnUiThread(() -> {
                if (at != built || list.isEmpty()) return;
                LinearLayout row = row(brands);
                for (Catalog.Brand b : list)
                    row.addView(platformTile(b.name, b.color, v -> startActivity(new Intent(this, PlatformActivity.class).putExtra("platform", id).putExtra("brand", b.id))));
            });
        });

        boolean pressed = focusKind;
        kindChips(col, new String[][]{{"movie", App.t(R.string.home_tab_movies)}, {"series", App.t(R.string.title_show)}}, this::build);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);
        String extra = "genre=" + Catalog.PLATFORM + id, type = kind;
        List<Catalog.Title> hit = Catalog.cached(type, extra);
        if (hit != null && !hit.isEmpty()) platformRows(holder, s[1], hit);
        else {
            skeleton(row(holder), posterW());
            App.io.execute(() -> {
                List<Catalog.Title> list = Catalog.list(type, extra);
                runOnUiThread(() -> {
                    if (at != built) return;
                    holder.removeAllViews();
                    if (list == null || list.isEmpty()) {
                        TextView retry = text(App.t(list == null ? R.string.home_catalog_retry : R.string.home_nothing_here), sp(15, 13), TEXT, true);
                        retry.setPadding(dp(14), dp(12), dp(14), dp(12));
                        retry.setBackground(rounded(CARD, dp(14)));
                        focusable(retry, dp(14));
                        retry.setOnClickListener(v -> build());
                        holder.addView(retry, new LinearLayout.LayoutParams(-2, -2));
                        focusContent(retry);
                    } else {
                        platformRows(holder, s[1], list);
                        if (firstCard != null) focusContent(firstCard);
                    }
                });
            });
        }
        focusFirst = App.tv && firstCard == null && !skipFocus && !pressed;   // the list is not here yet: it hands the remote over when it is
        android.view.View first = firstCard;   // a list that was here at once: the remote starts on its first poster (not when coming back to this page)
        if (App.tv && first != null && !skipFocus && !pressed && !first.requestFocus()) col.post(first::requestFocus);
    }

    /** Chips that pick the kind; the one pressed keeps the remote while the page is drawn again. */
    void kindChips(LinearLayout into, String[][] chips, Runnable redraw) {
        LinearLayout row = row(into);
        for (String[] c : chips) {
            boolean on = c[0].equals(kind);
            TextView chip = chip(v -> { kind = c[0]; focusKind = true; redraw.run(); });
            chip.setText(c[1]);
            chip.setTextColor(on ? BG : TEXT);
            chip.setBackground(rounded(on ? TEXT : CARD, dp(22)));
            focusable(chip, dp(22));
            if (App.tv && focusKind && on) chip.post(chip::requestFocus);
            row.addView(chip);
        }
        focusKind = false;
    }

    void titlesRow(LinearLayout into, String page, String name, String count, List<Catalog.Title> list) {
        into.addView(heading(name, count));
        posterRow(row(into), page + "  ·  " + name, list);
    }

    /** A service's list as rows: as it came (popular first), the best rated, the newest, then one row per genre with enough titles. */
    void platformRows(LinearLayout into, String page, List<Catalog.Title> list) {
        titlesRow(into, page, App.t(R.string.home_popular), "", list);
        List<Catalog.Title> rated = new ArrayList<>(), fresh = new ArrayList<>();
        for (Catalog.Title t : list) {
            if (Catalog.Brand.number(t.rating) > 0) rated.add(t);
            if (!t.released.isEmpty()) fresh.add(t);
        }
        rated.sort((x, y) -> Float.compare(Catalog.Brand.number(y.rating), Catalog.Brand.number(x.rating)));
        fresh.sort((x, y) -> y.released.compareTo(x.released));
        if (rated.size() >= 5) titlesRow(into, page, App.t(R.string.home_top_rated), "", rated);
        if (fresh.size() >= 5) titlesRow(into, page, App.t(R.string.home_new), "", fresh);
        Map<String, List<Catalog.Title>> genres = new java.util.LinkedHashMap<>();
        for (Catalog.Title t : list)
            for (String g : t.genres.split(" · ")) if (!g.isEmpty()) genres.computeIfAbsent(g, k -> new ArrayList<>()).add(t);
        List<Map.Entry<String, List<Catalog.Title>>> rows = new ArrayList<>(genres.entrySet());
        rows.sort((x, y) -> y.getValue().size() - x.getValue().size());
        for (Map.Entry<String, List<Catalog.Title>> g : rows.subList(0, Math.min(10, rows.size())))
            if (g.getValue().size() >= 6) titlesRow(into, page, g.getKey(), String.valueOf(g.getValue().size()), g.getValue());
    }

    void brandPage(String platform, String brand, int at) {
        TextView title = text("", sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(6));
        col.addView(title);
        App.io.execute(() -> {
            Catalog.Brand found = null;
            for (Catalog.Brand b : Catalog.brands(platform)) if (b.id.equals(brand)) found = b;
            final Catalog.Brand b = found;
            runOnUiThread(() -> {
                if (at != built) return;
                if (b == null) { col.addView(note(App.t(R.string.home_catalog_offline))); return; }
                title.setText(b.name);
                try { title.setTextColor(android.graphics.Color.parseColor(b.color)); } catch (RuntimeException ignored) { }
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                col.addView(body);
                showBrand(b, body);
                if (App.tv && firstCard != null && !skipFocus) focusContent(firstCard);
            });
        });
    }

    /** All / Movies / Series chips, then the brand's rows for the picked kind. */
    void showBrand(Catalog.Brand b, LinearLayout body) {
        body.removeAllViews();
        firstCard = null;
        boolean movies = false, series = false;
        for (Catalog.Title t : b.titles) { if (t.series()) series = true; else movies = true; }
        if (movies && series)
            kindChips(body, new String[][]{{"", App.t(R.string.home_all)}, {"movie", App.t(R.string.home_tab_movies)}, {"series", App.t(R.string.title_show)}}, () -> showBrand(b, body));
        // What is new, popular and top rated (a short list is all in its other rows already), the brand's groups, then everything
        boolean many = b.list(kind, "new").size() >= 30;
        List<String[]> rows = new ArrayList<>();   // {row, name}
        rows.add(new String[]{"new", App.t(R.string.home_new)});
        rows.add(new String[]{"popular", App.t(R.string.home_popular)});
        rows.add(new String[]{"rated", App.t(R.string.home_top_rated)});
        for (String[] g : b.groups)
            rows.add(new String[]{g[0], g[0].equals("animation") ? App.t(R.string.home_animation) : g[0].equals("documentary") ? App.t(R.string.home_documentaries) : g[1]});
        rows.add(new String[]{"movie", App.t(R.string.home_tab_movies)});
        rows.add(new String[]{"series", App.t(R.string.title_show)});
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            List<Catalog.Title> list = b.list(kind, r[0]);
            boolean top = i < 3;
            // an older file has no numbers to order by: no such rows
            if (list.isEmpty() || top && (!many || list.size() < 5 || list.get(0).pop <= 0 && list.get(0).votes <= 0)) continue;
            titlesRow(body, b.name, r[1], top ? "" : String.valueOf(list.size()), list);
        }
    }
}
