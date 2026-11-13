package me.champdev.weblock;

import android.app.AlertDialog;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The download manager's screen: every download with its file path, progress, speed and connections. The buttons are icons: pause
 * while it runs, play (resume) while it is paused, play (open) when it is done, and the bin to delete it; the tick marks a finished one.
 */
public class DownloadsActivity extends Home {
    @Override boolean tabs() { return false; }

    private int shown = -1;   // Downloads.version the list on screen was drawn for
    /** The numbers of one row, kept so the clock tick can update them in place. */
    private static final class Row {
        TextView done, pct, total, speed;
        ProgressBar bar;
    }

    private final Map<String, Row> rows = new HashMap<>();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            tick();
            col.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        col.removeCallbacks(refresh);
        col.postDelayed(refresh, 1000);
    }

    @Override protected void onPause() {
        col.removeCallbacks(refresh);
        super.onPause();
    }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        rows.clear();
        List<Downloads.Item> list = Downloads.list();
        shown = Downloads.version;
        TextView title = text(App.t(R.string.dl_title) + (list.isEmpty() ? "" : "  ·  " + list.size()), sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);
        TextView hint = text(App.t(list.isEmpty() ? R.string.dl_empty : App.tv ? R.string.dl_hint_tv : R.string.dl_hint_touch), 13, MUTED, false);
        hint.setPadding(0, 0, 0, dp(14));
        col.addView(hint);
        for (Downloads.Item it : list) col.addView(card(it));
        View first = firstCard;
        if (App.tv && first != null && !skipFocus && !first.requestFocus()) col.post(first::requestFocus);
    }

    /**
     * One download, compact: a tinted disc on the left says where it is (tick: done, arrow: running, pause bars: paused), then the name
     * with its path under it, then one line: downloaded, a bar, the percent, the full size. The buttons are on the right.
     */
    View card(Downloads.Item it) {
        boolean done = it.status.equals("done"), running = it.status.equals("running"), failed = it.status.equals("failed");
        int tone = done ? GREEN : running || failed ? ACCENT : MUTED;
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(u(12, 10), u(8, 7), u(8, 6), u(8, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(5);
        card.setLayoutParams(lp);
        card.setBackground(rounded(CARD, dp(12)));

        ImageView disc = new ImageView(this);
        disc.setImageResource(done ? R.drawable.ic_p_check : running ? R.drawable.ic_download : failed ? R.drawable.ic_p_close : R.drawable.ic_pb_pause);
        disc.setColorFilter(tone);
        disc.setPadding(dp(8), dp(8), dp(8), dp(8));
        disc.setBackground(rounded((tone & 0xFFFFFF) | 0x26000000, dp(18)));   // the state colour at 15%
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(36), dp(36));
        dl.rightMargin = dp(12);
        card.addView(disc, dl);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        Row row = new Row();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(it.name, sp(14, 13), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        row.speed = text("", sp(11, 10), ACCENT, true);   // only while it runs
        row.speed.setPadding(dp(10), 0, dp(4), 0);
        top.addView(row.speed);
        mid.addView(top);
        TextView where = text(Downloads.pathOf(it), sp(10.5f, 10), MUTED, false);   // /sdcard/Download/FMHYPlay/Name.ext
        where.setMaxLines(2);   // the whole path: a long one wraps instead of being cut in the middle
        where.setEllipsize(TextUtils.TruncateAt.END);
        if (!where.getText().toString().isEmpty()) mid.addView(where);

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setPadding(0, dp(4), 0, 0);
        if (failed) {
            TextView why = text(App.t(R.string.dl_failed, it.error), sp(11, 10), ACCENT, false);
            why.setSingleLine();
            why.setEllipsize(TextUtils.TruncateAt.END);
            stats.addView(why);
        } else {
            row.done = text("", sp(11, 10), MUTED, false);
            stats.addView(row.done);
            row.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            row.bar.setMax(1000);
            row.bar.setProgressTintList(android.content.res.ColorStateList.valueOf(tone));
            row.bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x26FFFFFF));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(4), 1);
            bl.setMargins(dp(8), 0, dp(8), 0);
            stats.addView(row.bar, bl);
            row.pct = text("", sp(11, 10), TEXT, true);
            row.pct.setMinWidth(dp(34));
            stats.addView(row.pct);
            row.total = text("", sp(11, 10), MUTED, false);
            row.total.setPadding(dp(8), 0, 0, 0);
            stats.addView(row.total);
        }
        mid.addView(stats);
        card.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        rows.put(it.id, row);
        fill(row, it);

        // pause while it runs, play to resume or (once done) to watch, and the bin
        ImageView main = it.active() ? button(R.drawable.ic_pb_pause, App.t(R.string.dl_pause), () -> Downloads.pause(it))
            : done ? button(R.drawable.ic_pb_play, App.t(R.string.dl_play), () -> play(it)) : button(R.drawable.ic_pb_play, App.t(R.string.dl_resume), () -> Downloads.resume(it));
        main.setTag("d" + it.id + "m");
        card.addView(main);
        ImageView bin = button(R.drawable.ic_delete, App.t(R.string.dl_delete), () -> confirmDelete(it));
        bin.setTag("d" + it.id + "x");
        card.addView(bin);
        if (firstCard == null) firstCard = main;
        return card;
    }

    /** The numbers of a row for the download as it is now. */
    void fill(Row row, Downloads.Item it) {
        boolean running = it.status.equals("running");
        row.speed.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) row.speed.setText(App.t(R.string.dl_speed, Formatter.formatFileSize(this, it.speed), it.conns));
        if (row.done == null) return;   // a failed one shows its reason instead
        boolean done = it.status.equals("done");
        long got = done && it.total > 0 ? it.total : it.doneBytes();
        row.done.setText(Formatter.formatFileSize(this, got));
        row.bar.setProgress(done ? 1000 : it.percent() * 10);
        row.pct.setText(done ? "100%" : it.percent() + "%");
        row.total.setText(it.total > 0 ? Formatter.formatFileSize(this, it.total) : "?");
    }

    /** A round icon button with the app's ring when the remote is on it. */
    ImageView button(int icon, String description, Runnable click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(TEXT);
        b.setContentDescription(description);
        int pad = dp(8);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(rounded(0x1FFFFFFF, dp(18)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        focusable(b, dp(18));
        b.setOnClickListener(v -> { click.run(); tick(); });
        return b;
    }

    /** Once a second: the numbers and bars in place; a changed list (a new download, a state change) is drawn again, keeping the remote's place. */
    void tick() {
        if (Downloads.version != shown) { rebuild(); return; }
        for (Downloads.Item it : Downloads.list()) {
            Row row = rows.get(it.id);
            if (row != null) fill(row, it);
        }
    }

    void confirmDelete(Downloads.Item it) {
        Subs.ask(this, it.name, new int[]{R.drawable.ic_delete}, new String[]{App.t(R.string.dl_delete_confirm)},
            R.drawable.ic_delete, App.t(R.string.dl_delete), () -> { Downloads.remove(it, true); tick(); });
    }

    /** In the app's own player. */
    void play(Downloads.Item it) {
        if (it.out.isEmpty()) return;
        String uri = it.out.startsWith("content://") ? it.out : android.net.Uri.fromFile(new java.io.File(it.out)).toString();
        startActivity(PlayerActivity.open(this, uri, false, new HashMap<>(), new java.util.ArrayList<>(), it.name).putExtra("mime", Downloads.mimeOf(it.ext)));
    }
}
