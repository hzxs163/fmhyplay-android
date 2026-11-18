package me.champdev.weblock;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PunchPlay, a free watch tracker (docs.punchplay.tv): connected once with a code at punchplay.tv/link, then what you watch in the
 * native player is sent there (start, pause, resume, stop; a stop at 80% or more is a watch). Only catalog titles: they carry the IMDb id.
 */
final class PunchPlay {
    static final String CLIENT_ID = "ppc_3776d09af94f4f1806dc2188";   // public client: no secret
    static final String API = "https://punchplay.tv/api/platform/v1";
    static final String SCOPE = "profile:read playback:read playback:write history:read history:write";
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();   // events go out in order
    private static String started;   // playback_session_id of the last start: a later start in it is a resume

    static boolean signedIn() {
        return !App.prefs.getString("ppRefresh", "").isEmpty();
    }

    static void signOut() {
        App.prefs.edit().remove("ppToken").remove("ppRefresh").remove("ppUntil").apply();
    }

    /** A sign-in code to type in at punchplay.tv/link. */
    static final class Code {
        String user, device, page;
        int interval = 5;
        long until;
    }

    /** Off the main thread. Null when PunchPlay can't be reached. */
    static Code newCode() {
        try {
            int[] status = new int[1];
            JSONObject o = new JSONObject(call("/auth/device/code", new JSONObject().put("client_id", CLIENT_ID).put("scope", SCOPE), status, null));
            if (status[0] != 200) return null;
            Code c = new Code();
            c.user = o.getString("user_code");
            c.device = o.getString("device_code");
            c.page = o.optString("verification_uri", "https://punchplay.tv/link");
            c.until = System.currentTimeMillis() + o.optInt("expires_in", 600) * 1000L;
            return c;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Asks once whether the code was entered: "ok" (signed in now), "wait", "expired" or "error"; a 429 stretches c.interval. Off the main thread. */
    static String check(Code c) {
        if (System.currentTimeMillis() > c.until) return "expired";
        try {
            int[] status = new int[2];
            JSONObject o = new JSONObject(call("/auth/device/token", new JSONObject().put("client_id", CLIENT_ID).put("device_code", c.device)
                .put("device_id", device()).put("device_name", "AnantPlay (" + android.os.Build.MODEL + ")"), status, null));
            if (status[0] == 200 && o.has("refresh_token")) { keep(o); return "ok"; }
            if (status[0] == 429) { c.interval = Math.max(c.interval, status[1]); return "wait"; }
            switch (o.optString("error")) {
                case "authorization_pending": return "wait";
                case "slow_down": c.interval += 5; return "wait";
                case "expired": return "expired";
                default: return status[0] >= 500 ? "wait" : "error";
            }
        } catch (IOException | JSONException e) {
            return "wait";   // a network hiccup: try again at the next tick
        }
    }

    // commit, not apply: each refresh hands out a new refresh token and the old one stops working, so it must not be lost
    private static void keep(JSONObject o) {
        App.prefs.edit().putString("ppToken", o.optString("access_token")).putString("ppRefresh", o.optString("refresh_token"))
            .putLong("ppUntil", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000L).commit();
    }

    /** This install's id for PunchPlay (device_id), made once. */
    private static String device() {
        String d = App.prefs.getString("ppDevice", "");
        if (d.isEmpty()) App.prefs.edit().putString("ppDevice", d = UUID.randomUUID().toString()).apply();
        return d;
    }

    /** The access token (an hour long), renewed a minute before it runs out. Null when signed out or PunchPlay can't renew it now. */
    private static synchronized String token(boolean renew) throws IOException {
        if (!signedIn()) return null;
        if (!renew && System.currentTimeMillis() < App.prefs.getLong("ppUntil", 0) - 60_000) return App.prefs.getString("ppToken", "");
        try {
            int[] status = new int[1];
            JSONObject o = new JSONObject(call("/auth/refresh", new JSONObject().put("client_id", CLIENT_ID)
                .put("refresh_token", App.prefs.getString("ppRefresh", "")), status, null));
            if (status[0] == 200 && o.has("refresh_token")) { keep(o); return o.getString("access_token"); }
            if (status[0] == 400 || status[0] == 401) signOut();   // revoked at punchplay.tv/settings/connected-apps, or ran out
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Tells PunchPlay what is playing. action = start | pause | stop (a start after a pause goes as resume).
     * @param episode {season, episode} of the show `imdb`, null for a movie
     * @param session one id per player screen (playback_session_id)
     */
    static void scrobble(String action, String imdb, String title, int[] episode, long positionMs, long durationMs, String session) {
        if (!signedIn() || imdb == null || !imdb.startsWith("tt") || durationMs <= 0 || (episode != null && episode[1] < 1)) return;
        String act = action.equals("start") && session.equals(started) ? "resume" : action;
        if (action.equals("start")) started = session;
        long at = System.currentTimeMillis();
        double progress = Math.max(0, Math.min(1, positionMs / (double) durationMs));
        queue.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("event_id", UUID.randomUUID().toString()).put("event_created_at", at)
                    .put("media_type", episode == null ? "movie" : "episode").put("title", title == null || title.trim().isEmpty() ? imdb : title.trim())
                    .put("imdb_id", imdb).put("progress", Math.round(progress * 10000) / 10000.0)
                    .put("position_seconds", positionMs / 1000).put("duration_seconds", durationMs / 1000)
                    .put("device_id", device()).put("playback_session_id", session).put("client_version", Update.installed());
                if (episode != null) body.put("season", episode[0]).put("episode", episode[1]);
                if (act.equals("stop")) body.put("watched_threshold", 0.8);   // same bar as Simkl
                boolean renew = false;
                for (int attempt = 0; attempt < 2; attempt++) {
                    String t = token(renew);
                    if (t == null) return;
                    int[] status = new int[2];
                    String reply = call("/playback/" + act, body, status, t);
                    Log.i("PunchPlay", act + " " + status[0] + " " + reply.substring(0, Math.min(300, reply.length())));
                    if (status[0] == 401 && attempt == 0) { renew = true; continue; }   // token cut short: renew it and send again
                    if (status[0] == 429 && attempt == 0) { Thread.sleep(Math.min(60, status[1]) * 1000L); continue; }   // as Retry-After asks
                    if (act.equals("stop") && status[0] == 200 && progress >= 0.8 && !reply.contains("\"deduped\":true") && !reply.contains("\"ignored\""))
                        App.main.post(() -> android.widget.Toast.makeText(App.app, App.t(R.string.punchplay_marked), android.widget.Toast.LENGTH_SHORT).show());
                    return;
                }
            } catch (Exception e) {
                Log.w("PunchPlay", act + " failed", e);
            }
        });
    }

    // ---- HTTP ----

    /** POSTs JSON, naming the app in the User-Agent. status[0] = HTTP code, status[1] = Retry-After seconds (5 if missing). Returns the reply, also on errors. */
    private static String call(String path, JSONObject body, int[] status, String token) throws IOException {
        HttpURLConnection c = Warp.open(API + path);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "AnantPlay/" + Update.installed());
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        status[0] = code;
        if (status.length > 1) {
            try { status[1] = Math.max(1, Integer.parseInt(c.getHeaderField("Retry-After").trim())); } catch (Exception e) { status[1] = 5; }
        }
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        return in == null ? "{}" : Sites.readAll(in);
    }
}
