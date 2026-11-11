// javac -d /tmp/sniffer $(find test/stubs -name '*.java') src/me/champdev/weblock/Sniffer.java test/SnifferTest.java && java -cp /tmp/sniffer me.champdev.weblock.SnifferTest
package me.champdev.weblock;

import android.os.Handler;

public class SnifferTest {
    static int fails;

    static void check(String name, Object got, Object want) {
        if (!got.equals(want)) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    /** A sniffer that counts how many times it offered a stream to the player. */
    static final class Rig {
        int found;
        final Sniffer s = new Sniffer(() -> { }, () -> found++);

        Rig play(String page, String stream) {
            s.page(page);
            s.request(stream, null);
            Handler.drain();
            return this;
        }
    }

    static final String SITE1 = "https://site.example/watch/1", SITE2 = "https://site.example/watch/2";
    static final String X = "https://cdn.a/hls/ep1/master.m3u8", X_VARIANT = "https://cdn.a/hls/ep1/720/index.m3u8";
    static final String Y = "https://cdn.b/hls/ep2/master.m3u8";

    public static void main(String[] a) {
        // the stream's own address wins over an address carried in its query (where it was asked from)
        check("a stream with ?ref=https://site is still seen as a stream", Sniffer.path("https://cdn.x/a/b/master.m3u8?ref=https%3A%2F%2Fsite.com%2Fwatch"), "https://cdn.x/a/b/master.m3u8");
        check("a proxy still gives the address it carries", Sniffer.path("https://site.com/proxy/hls?url=https%3A%2F%2Fcdn.x%2Fa%2Fmaster.m3u8%3Ft%3D1"), "https://cdn.x/a/master.m3u8");
        check("master by file name", new Sniffer.Hit("https://cdn.x/a/master.m3u8", false, null).master, true);
        check("a show named Master in the folder is not a master playlist", new Sniffer.Hit("https://cdn.x/masterchef-s01e02/720p.m3u8", false, null).master, false);
        Sniffer withRef = new Sniffer(() -> { }, () -> { });
        withRef.request("https://cdn.x/v/index.m3u8?origin=https://site.com", null);
        check("request with a referrer in its query is taken", withRef.has(), true);
        Rig r = new Rig().play(SITE1, X);
        check("first stream is offered once", r.found, 1);
        check("and it is the one asked for", r.s.best().url, X);

        r.s.handedOff(r.s.best());
        r.s.request(X, null);
        r.s.request(X_VARIANT, null);
        Handler.drain();
        check("the stream handed over stays quiet, with its own playlists", r.found, 1);

        r.s.page(SITE2);
        r.s.request(X, null);
        r.s.request(X_VARIANT, null);
        Handler.drain();
        check("next episode's page: the old player's requests are not mistaken for it", r.found, 1);
        r.s.request(Y, null);
        Handler.drain();
        check("a different stream is offered", r.found, 2);
        check("and it is the new one", r.s.best().url, Y);

        // No hand-off yet: nothing was left behind, so the same stream asked for again after a page change still counts
        Rig early = new Rig();
        early.s.page(SITE1);
        early.s.request(X, null);
        early.s.page(SITE2);
        early.s.request(X, null);
        Handler.drain();
        check("a page change before any hand-off still takes the stream", early.found, 1);
        check("and keeps it for the menu", early.s.has(), true);

        Rig back = new Rig().play(SITE1, X);
        back.s.handedOff(back.s.best());
        back.s.request(Y, null);
        Handler.drain();
        check("after Back, a stream from somewhere else is offered", back.found, 2);

        // Files at a host root: the old one is blocked by its exact path only, never the whole host
        Rig root = new Rig().play(SITE1, "https://cdn.c/ep1.m3u8");
        root.s.handedOff(root.s.best());
        root.s.page(SITE2);
        root.s.request("https://cdn.c/ep1.m3u8", null);
        Handler.drain();
        check("host root: the old file is ignored", root.found, 1);
        root.s.request("https://cdn.c/ep2.m3u8", null);
        Handler.drain();
        check("host root: another file there is offered", root.found, 2);

        // A site's proxy (/proxy/hls?url=...): the address inside it tells streams apart (Sub, then Dub of the same episode)
        String sub = "https://site.example/proxy/hls?url=https%3A%2F%2Fcdn.d%2Fv8%2Fsub123%2Fmaster.m3u8&exp=1";
        String dub = "https://site.example/proxy/hls?url=https%3A%2F%2Fcdn.d%2Fv8%2Fdub456%2Fmaster.m3u8&exp=1";
        Rig px = new Rig().play(SITE1, sub);
        px.s.handedOff(px.s.best());
        px.s.switching();
        px.s.request(sub, null);
        Handler.drain();
        check("proxy: the old stream is ignored", px.found, 1);
        px.s.request(dub, null);
        Handler.drain();
        check("proxy: the other one behind the same proxy is offered", px.found, 2);

        Rig promo = new Rig().play(SITE1, "https://cdn.e/promo/site-4k/v2/master.m3u8");
        check("a site's promo clip is not a stream", promo.found, 0);

        // A Sub / Dub press that did not switch: the stream held back as the old one is handed over again, and the button finds it
        String ep = "https://cdn.f/hls/ep7/master.m3u8";
        Rig sw = new Rig().play(SITE1, ep);
        sw.s.switching();
        sw.s.request(ep, null);
        Handler.drain();
        check("held back after a switch", sw.s.best() == null, true);
        check("but the button still finds it", sw.s.lastSeen().url, ep);
        sw.s.unswitch();
        Handler.drain();
        check("a switch that did not happen hands it over", sw.found, 2);

        System.out.println(fails == 0 ? "all passed" : fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
