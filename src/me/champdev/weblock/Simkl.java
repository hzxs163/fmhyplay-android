package me.champdev.weblock;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simkl, a free watch tracker: you connect your account once with a code (simkl.com/pin), then the movies and episodes you watch in
 * the native player are marked watched there (at 80% or more). Only titles opened from the catalog: they carry the IMDb id.
 */
final class Simkl {
    static final String CLIENT_ID = "fdc9c9da795fdb1d5fd717f714a8f793d88e86f3ee24a935b8c76d10c21bb36c";   // public: a device-type app has no secret
    static final String API = "https://api.simkl.com";
    // Simkl allows one scrobble call at a time per user, so they go out in order on one thread
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();

    static boolean signedIn() {
        return !App.prefs.getString("simklRefresh", "").isEmpty();
    }

    static void signOut() {
        App.prefs.edit().remove("simklToken").remove("simklRefresh").remove("simklUntil").apply();
    }

    /** A sign-in code to type in at simkl.com/pin. */
    static final class Code {
        String user, device, page;
        int interval;
        long until;
    }

    /** Off the main thread. Null when Simkl can't be reached. */
    static Code newCode() {
        try {
            JSONObject o = new JSONObject(call("POST", "/oauth2/device", form("client_id", CLIENT_ID, "scope", "media:read media:write"), false, null));
            Code c = new Code();
            c.user = o.getString("user_code");
            c.device = o.getString("device_code");
            c.page = o.optString("verification_uri", "https://simkl.com/pin");
            c.interval = Math.max(5, o.optInt("interval", 5));
            c.until = System.currentTimeMillis() + o.optInt("expires_in", 900) * 1000L;
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
            JSONObject o = new JSONObject(call("POST", "/oauth2/token", form("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "client_id", CLIENT_ID, "device_code", c.device), false, status));
            if (status[0] == 200 && o.has("access_token")) { keep(o); return "ok"; }
            switch (o.optString("error")) {
                case "authorization_pending": return "wait";
                case "slow_down": return "slow";
                case "expired_token": return "expired";
                default: return "error";
            }
        } catch (IOException | JSONException e) {
            return "wait";   // a network hiccup: try again at the next tick
        }
    }

    private static void keep(JSONObject o) {
        App.prefs.edit().putString("simklToken", o.optString("access_token")).putString("simklRefresh", o.optString("refresh_token"))
            .putLong("simklUntil", System.currentTimeMillis() + o.optLong("expires_in", 604800) * 1000L).apply();
    }

    /** The access token, renewed when it is about to run out (they last a week, the renewal half a year). Null when signed out. */
    private static synchronized String token() throws IOException {
        if (!signedIn()) return null;
        if (System.currentTimeMillis() < App.prefs.getLong("simklUntil", 0) - 3600_000) return App.prefs.getString("simklToken", "");
        try {
            int[] status = new int[1];
            JSONObject o = new JSONObject(call("POST", "/oauth2/token", form("grant_type", "refresh_token", "client_id", CLIENT_ID,
                "refresh_token", App.prefs.getString("simklRefresh", "")), false, status));
            if (status[0] == 200 && o.has("access_token")) { keep(o); return o.getString("access_token"); }
            if (status[0] == 400 || status[0] == 401) signOut();   // the grant was revoked on simkl.com
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Tells Simkl what is playing. action = start | pause | stop; stop at 80% or more marks it watched.
     * @param episode {season, number} for an episode of `imdb` (the show), null for a movie
     */
    static void scrobble(String action, String imdb, int[] episode, double progress) {
        if (!signedIn() || imdb == null || !imdb.startsWith("tt")) return;
        queue.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("progress", Math.round(Math.max(0, Math.min(100, progress)) * 100) / 100.0);
                JSONObject ids = new JSONObject().put("ids", new JSONObject().put("imdb", imdb));
                if (episode == null) body.put("movie", ids);
                else body.put("show", ids).put("episode", new JSONObject().put("season", episode[0]).put("number", episode[1]));
                for (int attempt = 0; attempt < 2; attempt++) {
                    String t = token();
                    if (t == null) return;
                    int[] status = new int[1];
                    String reply = call("POST", "/scrobble/" + action, body.toString(), true, status, t);
                    // Simkl keeps one call per user going for up to 20 s: the final stop (the one that marks it watched) waits and tries again
                    if (status[0] == 429 && action.equals("stop") && attempt == 0) { Thread.sleep(21000); continue; }
                    if (action.equals("stop") && status[0] < 300 && reply.contains("\"scrobble\""))
                        App.main.post(() -> android.widget.Toast.makeText(App.app, App.t(R.string.simkl_marked), android.widget.Toast.LENGTH_SHORT).show());
                    Log.i("Simkl", action + " " + status[0] + " " + reply.substring(0, Math.min(300, reply.length())));
                    return;
                }
            } catch (Exception e) {
                Log.w("Simkl", action + " failed", e);
            }
        });
    }

    // ---- HTTP ----

    private static String form(String... kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) sb.append(i == 0 ? "" : "&").append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        return sb.toString();
    }

    private static String call(String method, String path, String body, boolean json, int[] status) throws IOException {
        return call(method, path, body, json, status, null);
    }

    /** Every call names the app (client_id, app-name, app-version and a User-Agent), as Simkl asks. Returns the reply, also on errors. */
    private static String call(String method, String path, String body, boolean json, int[] status, String token) throws IOException {
        String version = Update.installed();
        HttpURLConnection c = Warp.open(API + path + "?client_id=" + CLIENT_ID + "&app-name=anantplay&app-version=" + version);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "AnantPlay/" + version);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", json ? "application/json" : "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        if (status != null) status[0] = code;
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        return in == null ? "{}" : Sites.readAll(in);
    }
}
