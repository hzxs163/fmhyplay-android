package me.champdev.weblock;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.function.Consumer;

/** Looks for a newer release on GitHub and installs its APK over this app (same signing key, so Android takes it as an update). */
final class Update {
    static final String API = "https://api.github.com/repos/AnantPlay/android/releases?per_page=15";   // newest first
    static final long EVERY = 3600L * 1000;   // an hour (and at every boot, below)
    static final int HOURLY = 7001, AT_BOOT = 7002;
    private static volatile boolean busy;
    private static BroadcastReceiver waiting;   // listens for the installer's answer; one at a time

    static String installed() {
        try { return App.app.getPackageManager().getPackageInfo(App.app.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    /** On unless switched off in Settings. */
    static boolean auto() {
        return App.prefs.getBoolean("updateCheck", true);
    }

    /**
     * The background checks, which Android runs with the app closed: every hour (kept across reboots) and, from the boot receiver,
     * once at every boot as soon as the network is up. No other limit; the Settings switch still turns them off.
     */
    static void schedule(Context c, boolean boot) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            ComponentName cn = new ComponentName(c, Job.class);
            if (js.getPendingJob(HOURLY) == null)
                js.schedule(new JobInfo.Builder(HOURLY, cn).setPeriodic(EVERY).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
            if (boot) js.schedule(new JobInfo.Builder(AT_BOOT, cn).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        } catch (RuntimeException ignored) { }   // a ROM that refuses jobs: the check on Home still runs
    }

    public static class Job extends JobService {
        @Override public boolean onStartJob(JobParameters p) {
            if (!auto()) return false;
            App.io.execute(() -> { check(); jobFinished(p, false); });
            return true;
        }

        @Override public boolean onStopJob(JobParameters p) { return false; }
    }

    static boolean due() {
        return auto() && !busy && System.currentTimeMillis() - App.prefs.getLong("updateAt", 0) > EVERY;
    }

    /** Asks GitHub for the latest release and remembers it, with the notes of every release newer than this one. Off the main thread. False when GitHub can't be reached. */
    static boolean check() {
        busy = true;
        try {
            JSONArray all = new JSONArray(Sites.readAll(Warp.openPublic(API, 6000, 20000)));
            JSONObject r = null;
            StringBuilder notes = new StringBuilder();
            for (int i = 0; i < all.length(); i++) {
                JSONObject x = all.getJSONObject(i);
                if (x.optBoolean("draft") || x.optBoolean("prerelease")) continue;
                if (r == null) r = x;
                String v = x.getString("tag_name").replaceFirst("^v", "");
                if (!newer(v, installed())) break;
                notes.append(notes.length() == 0 ? "" : "\n\n").append(v).append('\n').append(x.optString("body").trim());
            }
            if (r == null) return false;
            String want = App.flavor.equals("android") ? "-Android-" : "-TV-", apk = "";
            JSONArray assets = r.getJSONArray("assets");
            for (int i = 0; i < assets.length() && apk.isEmpty(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.getString("name").endsWith(".apk") && a.getString("name").contains(want)) apk = a.getString("browser_download_url");
            }
            App.prefs.edit().putString("updateVer", r.getString("tag_name").replaceFirst("^v", "")).putString("updateUrl", apk)
                .putString("updateNotes", notes.toString()).putLong("updateAt", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            busy = false;
        }
    }

    /** The newer version GitHub has, "" when this one is the latest (or the release has no APK for this build). */
    static String latest() {
        String v = App.prefs.getString("updateVer", "");
        return !App.prefs.getString("updateUrl", "").isEmpty() && newer(v, installed()) ? v : "";
    }

    /** Same as latest(), but "" after the user dismissed that version. */
    static String available() {
        String v = latest();
        return v.equals(App.prefs.getString("updateSkip", "")) ? "" : v;
    }

    /** What changed since this version, newest release first, as plain text ("" when unknown). */
    static String notes() {
        return plain(App.prefs.getString("updateNotes", ""));
    }

    /** The changelog's Markdown made readable in a dialog: bullets, no ** or backticks. */
    static String plain(String md) {
        return md.replaceAll("(?m)^#{1,6}\\s*", "").replaceAll("(?m)^\\s*[-*] ", "\u2022 ").replace("**", "").replace("`", "").trim();
    }

    static void skip(String version) {
        App.prefs.edit().putString("updateSkip", version).apply();
    }

    static boolean newer(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
    }

    private static int[] nums(String v) {
        int[] n = new int[3];
        String[] p = v.replaceFirst("^\\D+", "").split("\\D+");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try { n[i] = Integer.parseInt(p[i]); } catch (NumberFormatException ignored) { }
        }
        return n;
    }

    /**
     * Downloads the APK straight into an install session and hands it to Android's installer, which asks the user to confirm.
     * @param status told (on the main thread) how it goes: "Downloading 40%", "Installing", or why it failed
     */
    static void install(Consumer<String> status) {
        String url = App.prefs.getString("updateUrl", "");
        if (url.isEmpty()) { status.accept(App.t(R.string.update_no_download)); return; }
        App.io.execute(() -> {
            Context app = App.app;
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            PackageInstaller.Session session = null;
            try {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = pi.createSession(params);
                session = pi.openSession(id);
                HttpURLConnection c = Warp.open(url);
                c.setReadTimeout(60000);
                long total = c.getContentLengthLong(), done = 0;
                int shown = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("update", 0, total)) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != shown) { shown = pct; post(status, App.t(R.string.update_downloading, pct)); }
                    }
                    session.fsync(out);
                }
                post(status, App.t(R.string.update_installing));
                if (waiting != null) try { app.unregisterReceiver(waiting); } catch (IllegalArgumentException ignored) { }   // an earlier try that was abandoned
                BroadcastReceiver r = waiting = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent i) {
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            ctx.startActivity(((Intent) i.getParcelableExtra(Intent.EXTRA_INTENT)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            return;
                        }
                        app.unregisterReceiver(this);
                        if (st != PackageInstaller.STATUS_SUCCESS) post(status, App.t(R.string.update_failed, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
                    }
                };
                IntentFilter f = new IntentFilter("me.champdev.weblock.UPDATE");
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); else app.registerReceiver(r, f);
                session.commit(PendingIntent.getBroadcast(app, id, new Intent("me.champdev.weblock.UPDATE").setPackage(app.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)).getIntentSender());
            } catch (Exception e) {
                if (session != null) session.abandon();
                post(status, App.t(R.string.update_failed, e.getMessage()));
            } finally {
                if (session != null) session.close();
            }
        });
    }

    private static void post(Consumer<String> status, String s) {
        App.main.post(() -> status.accept(s));
    }
}
