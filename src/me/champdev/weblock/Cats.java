package me.champdev.weblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Which home rows a site belongs in: decided by its FMHY section and its own tags. Pure Java, tested off-device. */
final class Cats {
    static final String MOVIES = "Movies", TV = "TV", SPORTS = "Sports", ANIME = "Anime", LIVE = "Live TV", MORE = "More";
    /** Row order on the home screen. */
    static final String[] ORDER = {MOVIES, TV, SPORTS, ANIME, LIVE, MORE};

    static List<String> of(String section, List<String> tags) {
        List<String> out = new ArrayList<>();
        switch (section) {
            case "Anime Streaming": out.add(ANIME); return out;
            case "Live Sports": case "Sports Replays": out.add(SPORTS); return out;
            case "Stream Lounges": case "Specialty Streaming": out.add(MORE); return out;
            // "TV" on a live-channel site means channels, not shows: keep them in Live TV (and Sports if tagged)
            case "Live TV":
                out.add(LIVE);
                for (String t : tags) if (t.toLowerCase(Locale.ROOT).matches(".*(sport|football|motorsport|mma|wrestling|boxing|basketball|baseball|live event).*")) add(out, SPORTS);
                return out;
            case "Cartoon Streaming": case "Drama Streaming": case "TV Streaming": out.add(TV); break;
            case "Stream Aggregators": case "P-Stream Forks": case "Dedicated-Server": case "Multi-Server":
            case "Video Streaming": case "Free w/ Ads": case "Classics / Public Domain": case "Film Archives": break;
            default: return out;                                   // not a streaming section
        }
        for (String raw : tags) {
            String t = raw.toLowerCase(Locale.ROOT);
            if (t.matches(".*(movie|film).*")) add(out, MOVIES);
            if (t.matches(".*(\\btv\\b|shows?|series|cartoon|drama).*")) add(out, TV);
            if (t.matches(".*(anime|donghua).*")) add(out, ANIME);
            if (t.matches(".*(sport|football|motorsport|mma|wrestling|boxing|basketball|baseball|live event).*")) add(out, SPORTS);
        }
        if (out.isEmpty()) {
            switch (section) {
                // FMHY's tags here are now only quality / feature words (4K, Dual Audio), but every site in these sections plays movies and TV
                case "Stream Aggregators": case "P-Stream Forks": case "Dedicated-Server": case "Multi-Server": out.add(MOVIES); out.add(TV); break;
                case "Classics / Public Domain": case "Film Archives": case "Video Streaming": out.add(MOVIES); break;
                default: out.add(MORE);
            }
        }
        return out;
    }

    static void add(List<String> list, String c) {
        if (!list.contains(c)) list.add(c);
    }
}
