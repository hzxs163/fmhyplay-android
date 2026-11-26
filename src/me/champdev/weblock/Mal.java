package me.champdev.weblock;

import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * MyAnimeList: you sign in once in a small browser (AuthActivity; PKCE, the app has no secret), then the anime episodes you watch
 * in the native player (80% or more) are set as your progress there. The token lasts a month and is renewed with its refresh token.
 */
final class Mal {
    static final String CLIENT_ID = "61cb89cdcecd5081238403a22779b624";   // a public client (no secret): it ships in every copy of the app
    /** Registered at myanimelist.net/apiconfig. Never loaded: the sign-in browser stops when the page is sent here. */
    static final String REDIRECT = "https://anantplay.github.io/android/mal";
    static final String AUTH = "https://myanimelist.net/v1/oauth2/authorize", TOKEN = "https://myanimelist.net/v1/oauth2/token";
    static final String API = "https://api.myanimelist.net/v2";
    private static String verifier = "";   // PKCE "plain": the challenge is the verifier itself

    static boolean signedIn() {
        return !App.prefs.getString("malRefresh", "").isEmpty();
    }

    static void signOut() {
        App.prefs.edit().remove("malToken").remove("malRefresh").remove("malUntil").apply();
    }

    /** The address for the sign-in browser (a new verifier each time). */
    static String loginUrl() {
        StringBuilder v = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < 96; i++) v.append(chars.charAt(r.nextInt(chars.length())));
        verifier = v.toString();
        return AUTH + "?response_type=code&client_id=" + CLIENT_ID + "&code_challenge=" + verifier + "&code_challenge_method=plain&redirect_uri=" + Uri.encode(REDIRECT);
    }

    /** What the sign-in browser ended on: https://.../mal?code=... Trades the code for the tokens. True when signed in. Off the main thread. */
    static boolean accept(Uri u) {
        String code = u == null ? null : u.getQueryParameter("code");
        if (code == null || verifier.isEmpty()) return false;
        try {
            int[] status = new int[1];
            String reply = post(TOKEN, form("client_id", CLIENT_ID, "grant_type", "authorization_code", "code", code, "code_verifier", verifier, "redirect_uri", REDIRECT), status);
            if (status[0] != 200) return false;
            keep(new JSONObject(reply));
            verifier = "";
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private static void keep(JSONObject o) {
        App.prefs.edit().putString("malToken", o.optString("access_token")).putString("malRefresh", o.optString("refresh_token"))
            .putLong("malUntil", System.currentTimeMillis() + o.optLong("expires_in", 2_678_400) * 1000L).apply();
    }

    private static synchronized String token() throws IOException {
        if (!signedIn()) return null;
        if (System.currentTimeMillis() < App.prefs.getLong("malUntil", 0) - 86_400_000L) return App.prefs.getString("malToken", "");
        try {
            int[] status = new int[1];
            String reply = post(TOKEN, form("client_id", CLIENT_ID, "grant_type", "refresh_token", "refresh_token", App.prefs.getString("malRefresh", "")), status);
            if (status[0] == 200) { JSONObject o = new JSONObject(reply); keep(o); return o.getString("access_token"); }
            if (status[0] == 400 || status[0] == 401) signOut();   // the grant ran out or was revoked
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Sets the progress of anime `id` (MyAnimeList's) to `episode`, never lower than it already is; the last episode completes it. Off the main thread. */
    static void watched(int id, int episode) {
        if (!signedIn() || id <= 0 || episode <= 0) return;
        try {
            String t = token();
            if (t == null) return;
            int[] status = new int[1];
            JSONObject have = new JSONObject(request("GET", API + "/anime/" + id + "?fields=num_episodes,my_list_status", null, t, status));
            if (status[0] != 200) return;
            JSONObject mine = have.optJSONObject("my_list_status");
            if (mine != null && mine.optInt("num_episodes_watched") >= episode) return;   // already there
            int total = have.optInt("num_episodes", 0);
            String reply = request("PATCH", API + "/anime/" + id + "/my_list_status",
                form("status", total > 0 && episode >= total ? "completed" : "watching", "num_watched_episodes", String.valueOf(episode)), t, status);
            Log.i("Mal", "progress " + id + " ep " + episode + " " + status[0] + " " + reply.substring(0, Math.min(160, reply.length())));
            if (status[0] == 200) App.main.post(() -> android.widget.Toast.makeText(App.app, App.t(R.string.mal_marked), android.widget.Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.w("Mal", "progress failed", e);
        }
    }

    private static String form(String... kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) sb.append(i == 0 ? "" : "&").append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        return sb.toString();
    }

    private static String post(String url, String body, int[] status) throws IOException {
        return request("POST", url, body, null, status);
    }

    private static String request(String method, String url, String body, String token, int[] status) throws IOException {
        HttpURLConnection c = Warp.open(url);
        c.setRequestProperty("User-Agent", "AnantPlay/" + Update.installed());
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestMethod(method);   // PATCH is fine on Android 8+ (the minimum)
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        status[0] = code;
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        return in == null ? "{}" : Sites.readAll(in);
    }
}
