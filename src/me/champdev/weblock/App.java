package me.champdev.weblock;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    static App app;
    static SharedPreferences prefs;
    static final Handler main = new Handler(Looper.getMainLooper());
    static final ExecutorService io = Executors.newFixedThreadPool(4);
    static boolean tv;
    static String flavor = "";   // "tv" or "android": which APK this is (updates fetch the same one)

    /** UI text in the phone's or TV's language (res/values-xx/strings_*.xml, English otherwise), with format arguments. */
    static String t(int id, Object... args) { return args.length == 0 ? app.getString(id) : app.getString(id, args); }

    /**
     * The viewer's country ("IN"), "" when unknown or switched off in Settings: the mobile network's, else the time zone's
     * (a TV has no SIM and its language is often English (US) anywhere), else the device language's.
     */
    static String region() {
        String set = prefs.getString("region", "");
        if (!set.isEmpty()) return set.equals("-") ? "" : set;
        String c = "";
        try {
            android.telephony.TelephonyManager tm = app.getSystemService(android.telephony.TelephonyManager.class);
            if (tm != null) c = tm.getNetworkCountryIso();
            if ((c == null || c.isEmpty()) && tm != null) c = tm.getSimCountryIso();
        } catch (RuntimeException ignored) { }
        try {
            if (c == null || c.isEmpty()) c = android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().getID());
        } catch (RuntimeException ignored) { }   // a zone with no country (UTC)
        if (c == null || c.length() != 2) c = java.util.Locale.getDefault().getCountry();
        return c.toUpperCase(java.util.Locale.ROOT);
    }

    /** The system is nearly out of memory, or we left the screen: decoded pictures go, they are on disk. */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) Home.images.evictAll();
    }

    @Override public void onCreate() {
        super.onCreate();
        app = this;
        prefs = getSharedPreferences("fmhyplay", MODE_PRIVATE);   // the name from before the rename: changing it would wipe everyone's settings
        Update.schedule(this, false);   // the hourly check, with the app closed too
        // A new version may read things the kept brand file (6 hours) does not have yet: after an update it is fetched again
        try {
            long updated = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            if (prefs.getLong("brandsFor", 0) != updated) {
                Catalog.file(Catalog.COLLECTIONS).delete();
                prefs.edit().putLong("brandsFor", updated).apply();
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        // The TV and Android builds each carry a "flavor" asset; anything else (old builds) guesses from the device
        try (java.io.InputStream in = getAssets().open("flavor")) { byte[] b = new byte[16]; flavor = new String(b, 0, Math.max(0, in.read(b))).trim(); } catch (java.io.IOException ignored) { }
        // Phones and tablets (touchscreen, not in TV mode) are never "tv", whichever APK was installed
        boolean looksLikeTv = getSystemService(UiModeManager.class).getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION
            || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        tv = !flavor.equals("android") && looksLikeTv;

        // WebLock kept one locked URL; carry it over as a favorite
        String old = getSharedPreferences("weblock", MODE_PRIVATE).getString("url", null);
        if (old != null && !prefs.getBoolean("migrated", false)) {
            Sites.addCustom(old);
            prefs.edit().putBoolean("migrated", true).apply();
        }

        // v2.1 makes the cursor the default way to browse a site: switch it on once for people updating from 2.0
        if (!prefs.getBoolean("cursorDefault21", false)) prefs.edit().putBoolean("pointer", true).putBoolean("cursorDefault21", true).apply();

        if (prefs.getBoolean("warp", true)) Warp.start();
        io.execute(AdBlock::load);
        // The TV listens for casts from phones (not in the WebView's sandboxed processes, which also start an App)
        if (android.os.Build.VERSION.SDK_INT < 28 || getPackageName().equals(getProcessName())) {
            if (tv) Cast.start(this);
            else Cast.discover(this, () -> main.post(() -> { if (PlayerActivity.current != null) PlayerActivity.current.refreshChips(); }));   // TVs to cast to, ready before the player opens
        }

        // No screen of ours visible for 2 minutes: stop WARP (no keepalive traffic in the background), restart on return
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            int visible;
            final Runnable idleStop = new Runnable() {
                @Override public void run() {
                    if (visible != 0 || Warp.state != Warp.State.ON) return;
                    if (Downloads.active() > 0) { main.postDelayed(this, 60000); return; }   // a download still needs it: look again in a minute
                    Warp.pause();
                }
            };

            @Override public void onActivityStarted(Activity a) {
                visible++;
                main.removeCallbacks(idleStop);
                if (prefs.getBoolean("warp", true) && Warp.state == Warp.State.OFF) Warp.start();
            }

            @Override public void onActivityStopped(Activity a) {
                if (--visible == 0) main.postDelayed(idleStop, 120000);
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
