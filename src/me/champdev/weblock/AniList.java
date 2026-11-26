package me.champdev.weblock;

import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * AniList, an anime list: you sign in once in a small browser (AuthActivity), then the anime episodes you watch in the native
 * player (80% or more) are set as your progress there. AniList has no code sign-in, so the token comes back in the address
 * the sign-in page ends on. It lasts a year; when it stops working you sign in again.
 */
final class AniList {
    static final String CLIENT_ID = "57956";   // public: the sign-in uses the implicit grant, so the app has no secret in it
    /** Registered at anilist.co/settings/developer. Never loaded: the sign-in browser stops when the page is sent here. */
    static final String REDIRECT = "https://anantplay.github.io/android/anilist";
    static final String LOGIN = "https://anilist.co/api/v2/oauth/authorize?response_type=token&client_id=" + CLIENT_ID;
    static final String API = "https://graphql.anilist.co";

    static boolean signedIn() {
        return !App.prefs.getString("aniToken", "").isEmpty();
    }

    static void signOut() {
        App.prefs.edit().remove("aniToken").apply();
    }

    /** What the sign-in browser ended on: https://.../anilist#access_token=...&token_type=Bearer&expires_in=... True when it holds a token. */
    static boolean accept(Uri u) {
        String f = u == null ? null : u.getFragment();
        if (f == null) return false;
        for (String kv : f.split("&")) {
            if (kv.startsWith("access_token=") && kv.length() > 13) {
                App.prefs.edit().putString("aniToken", Uri.decode(kv.substring(13))).apply();
                return true;
            }
        }
        return false;
    }

    /** Sets the progress of anime `id` (AniList's) to `episode`, never lower than it already is; the last episode completes it. Off the main thread. */
    static void watched(int id, int episode) {
        String token = App.prefs.getString("aniToken", "");
        if (token.isEmpty() || id <= 0 || episode <= 0) return;
        try {
            JSONObject have = new JSONObject(call("query($id:Int){Media(id:$id,type:ANIME){episodes mediaListEntry{progress status}}}", new JSONObject().put("id", id), token));
            if (have.has("errors") && !have.has("data")) return;
            JSONObject media = have.getJSONObject("data").getJSONObject("Media");
            JSONObject entry = media.optJSONObject("mediaListEntry");
            if (entry != null && entry.optInt("progress") >= episode) return;   // already there (a rewatch or an older episode)
            int total = media.optInt("episodes", 0);
            String status = total > 0 && episode >= total ? "COMPLETED" : "CURRENT";
            String reply = call("mutation($id:Int,$p:Int,$s:MediaListStatus){SaveMediaListEntry(mediaId:$id,progress:$p,status:$s){id}}",
                new JSONObject().put("id", id).put("p", episode).put("s", status), token);
            Log.i("AniList", "progress " + id + " ep " + episode + " " + reply.substring(0, Math.min(160, reply.length())));
            JSONObject saved = new JSONObject(reply).optJSONObject("data");   // an error reply has "SaveMediaListEntry": null
            if (saved != null && saved.optJSONObject("SaveMediaListEntry") != null)
                App.main.post(() -> android.widget.Toast.makeText(App.app, App.t(R.string.anilist_marked), android.widget.Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.w("AniList", "progress failed", e);
        }
    }

    private static String call(String query, JSONObject vars, String token) throws IOException, JSONException {
        HttpURLConnection c = Warp.open(API);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "AnantPlay/" + Update.installed());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(new JSONObject().put("query", query).put("variables", vars).toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String reply = in == null ? "{}" : Sites.readAll(in);
        if (code == 401 || reply.contains("Invalid token")) signOut();   // the token ran out or was revoked
        return reply;
    }
}
