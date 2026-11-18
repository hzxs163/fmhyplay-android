package me.champdev.weblock;

/** The Home rows sites are shown in. A "sites" source names them by key (KEYS), the app by title (ORDER), in the same order. */
final class Cats {
    static final String MOVIES = "Movies", TV = "TV", SPORTS = "Sports", ANIME = "Anime", LIVE = "Live TV", MORE = "More";
    /** Row order on the home screen. */
    static final String[] ORDER = {MOVIES, TV, SPORTS, ANIME, LIVE, MORE};
    static final String[] KEYS = {"movies", "tv", "sports", "anime", "live", "more"};
}
