package me.champdev.weblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the app alive in the background while downloads run (so leaving the app or switching the screen off does not stop them) and
 * shows their progress in the notification: a bar, the speed, and the name (or how many). Stops itself when nothing is left to do.
 */
public class DownloadService extends Service {
    static final String TAG = "DownloadService";
    static final int ID = 2;
    static volatile DownloadService live;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, DownloadService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, e);   // the system would not allow it from here: the downloads still run while the app is open
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        live = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("downloads", App.t(R.string.dl_title), NotificationManager.IMPORTANCE_LOW));
        Notification n = build(this, -1, App.t(R.string.dl_title), App.t(R.string.dl_starting));
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (RuntimeException e) {
            Log.w(TAG, e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        live = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    static Notification build(Context c, int pct, String title, String text) {
        Notification.Builder b = new Notification.Builder(c, "downloads").setSmallIcon(R.drawable.ic_download).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, DownloadsActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (pct >= 0) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    /** The bar, the title and the line under it. */
    static void update(int pct, String title, String text) {
        DownloadService s = live;
        if (s != null) s.getSystemService(NotificationManager.class).notify(ID, build(s, pct, title, text));
    }

    /** A download is whole: a notification of its own, left in the shade. */
    static void finished(String name) {
        Context c = App.app;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("downloads_done", App.t(R.string.dl_done_channel), NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, "downloads_done").setSmallIcon(R.drawable.ic_download).setContentTitle(name).setContentText(App.t(R.string.dl_done))
            .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c, 1, new Intent(c, DownloadsActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
        nm.notify(1000 + (name.hashCode() & 0xFFFF), n);
    }

    /** Called when a download ends: if none is running or waiting, the service (and its notification) goes. */
    static void stopIfIdle() {
        DownloadService s = live;
        if (s != null && Downloads.active() == 0) s.stopSelf();
    }
}
