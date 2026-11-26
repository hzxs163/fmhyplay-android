package me.champdev.weblock;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An anime episode watched in the native player (80% or more) goes to the anime lists you signed in to: AniList and MyAnimeList. */
final class AnimeSync {
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();

    static boolean on() {
        return AniList.signedIn() || Mal.signedIn();
    }

    /** `show` is the anime from the catalog (its IMDb id finds the anime ids, as for the intro and credits times). */
    static void watched(Catalog.Title show, int season, int episode) {
        if (!on() || show == null || !show.anime || episode <= 0) return;
        queue.execute(() -> {
            Map<String, String> ids = Catalog.animeEntry(show, season, episode);
            int n = num(ids.get("episode"));
            if (n <= 0) return;
            if (AniList.signedIn()) AniList.watched(num(ids.get("anilist")), n);
            if (Mal.signedIn()) Mal.watched(num(ids.get("mal")), n);
        });
    }

    private static int num(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
