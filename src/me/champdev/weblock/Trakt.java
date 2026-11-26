package me.champdev.weblock;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trakt, a free watch tracker: you connect your account once with a code (trakt.tv/activate), then the movies and episodes you
 * watch in the native player are scrobbled there (a stop at 80% or more is a watch). Only titles opened from the catalog: they carry the IMDb id.
 */
final class Trakt {
    // Trakt's device sign-in needs the app's secret as well: it ships in every copy of the app, so it is only a name for AnantPlay, not a secret
    static final String CLIENT_ID = "";
    static final String CLIENT_SECRET = "";
    static final String API = "https://api.trakt.tv";
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();   // scrobbles go out in order

    static boolean signedIn() {
        return !App.prefs.getString("traktRefresh", "").isEmpty();
    }

    static void signOut() {
        App.prefs.edit().remove("traktToken").remove("traktRefresh").remove("traktUntil").apply();
    }

    /** A sign-in code to type in at trakt.tv/activate. */
    static final class Code {
        String user, device, page;
        int interval = 5;
        long until;
    }

    /** Off the main thread. Null when Trakt can't be reached. */
    static Code newCode() {
        try {
            int[] status = new int[1];
            JSONObject o = new JSONObject(call("/oauth/device/code", new JSONObject().put("client_id", CLIENT_ID), status, null));
            if (status[0] != 200) return null;
            Code c = new Code();
            c.user = o.getString("user_code");
            c.device = o.getString("device_code");
            c.page = o.optString("verification_url", "https://trakt.tv/activate");
            c.interval = Math.max(5, o.optInt("interval", 5));
            c.until = System.currentTimeMillis() + o.optInt("expires_in", 600) * 1000L;
            return c;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Asks once whether the code was entered: "ok" (signed in now), "wait", "slow" (ask less often), "expired" or "error". Off the main thread. */
    static String check(Code c) {
        if (System.currentTimeMillis() > c.until) return "expired";
        try {
            int[] status = new int[1];
            String reply = call("/oauth/device/token", new JSONObject().put("code", c.device).put("client_id", CLIENT_ID).put("client_secret", CLIENT_SECRET), status, null);
            switch (status[0]) {
                case 200: keep(new JSONObject(reply)); return "ok";
                case 400: return "wait";   // not entered yet
                case 429: return "slow";
                case 410: return "expired";
                default: return "error";   // 404 wrong code, 409 used, 418 refused
            }
        } catch (IOException | JSONException e) {
            return "wait";   // a network hiccup: try again at the next tick
        }
    }

    private static void keep(JSONObject o) {
        App.prefs.edit().putString("traktToken", o.optString("access_token")).putString("traktRefresh", o.optString("refresh_token"))
            .putLong("traktUntil", System.currentTimeMillis() + o.optLong("expires_in", 7_776_000) * 1000L).apply();
    }

    /** The access token, renewed when it is about to run out (they last three months). Null when signed out. */
    private static synchronized String token() throws IOException {
        if (!signedIn()) return null;
        if (System.currentTimeMillis() < App.prefs.getLong("traktUntil", 0) - 86_400_000L) return App.prefs.getString("traktToken", "");
        try {
            int[] status = new int[1];
            String reply = call("/oauth/token", new JSONObject().put("refresh_token", App.prefs.getString("traktRefresh", "")).put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET).put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"), status, null);
            if (status[0] == 200) { JSONObject o = new JSONObject(reply); keep(o); return o.getString("access_token"); }
            if (status[0] == 400 || status[0] == 401) signOut();   // the grant was revoked on trakt.tv
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Tells Trakt what is playing. action = start | pause | stop; stop at 80% or more makes it a watch.
     * @param episode {season, number} for an episode of `imdb` (the show), null for a movie
     */
    static void scrobble(String action, String imdb, int[] episode, double progress) {
        if (!signedIn() || imdb == null || !imdb.startsWith("tt")) return;
        queue.execute(() -> {
            try {
                String t = token();
                if (t == null) return;
                JSONObject body = new JSONObject().put("progress", Math.round(Math.max(0, Math.min(100, progress)) * 100) / 100.0);
                JSONObject ids = new JSONObject().put("ids", new JSONObject().put("imdb", imdb));
                if (episode == null) body.put("movie", ids);
                else body.put("show", ids).put("episode", new JSONObject().put("season", episode[0]).put("number", episode[1]));
                int[] status = new int[1];
                String reply = call("/scrobble/" + action, body, status, t);
                // a stop answers 201 with the action, or 409 when the same watch was sent in the last hour (already marked)
                if (action.equals("stop") && progress >= 80 && status[0] == 201)
                    App.main.post(() -> android.widget.Toast.makeText(App.app, App.t(R.string.trakt_marked), android.widget.Toast.LENGTH_SHORT).show());
                Log.i("Trakt", action + " " + status[0] + " " + reply.substring(0, Math.min(200, reply.length())));
            } catch (Exception e) {
                Log.w("Trakt", action + " failed", e);
            }
        });
    }

    /** One JSON POST; returns the reply, also on errors (status[0] says which). */
    private static String call(String path, JSONObject body, int[] status, String token) throws IOException {
        HttpURLConnection c = Warp.open(API + path);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "AnantPlay/" + Update.installed());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", CLIENT_ID);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        if (status != null) status[0] = code;
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        return in == null ? "{}" : Sites.readAll(in);
    }
}
