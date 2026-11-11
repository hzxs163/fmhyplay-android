package me.champdev.weblock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Domain blocklist (HaGeZi Pro mini + pop-up ads). Bundled copy, refreshed weekly. */
final class AdBlock {
    static final String[] SOURCES = {
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.mini-onlydomains.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt"};
    static final long WEEK = 7L * 24 * 3600 * 1000;

    // Rotating ad-SDK script paths seen on Chinese pirate sites: /api/s/<hex>.js, /api/r/<hex>.js
    private static final Pattern AD_SCRIPT = Pattern.compile("^/api/[a-z]/[0-9a-f]{8,32}\\.js$", Pattern.CASE_INSENSITIVE);

    // Ad hosts that rotate faster than the public lists; checked on top of the downloaded set
    private static final Set<String> CUSTOM = new HashSet<>(Arrays.asList(
        "ljk1qjylmzsym.top", "r70uy2.top", "hbaqqua.cn", "hbaooka.cn", "hbaooua.cn", "yk395.com"));

    // ponytail: ~120k domains in a HashSet is ~12MB; switch to a sorted array if low-RAM TVs struggle
    static volatile Set<String> hosts = Collections.emptySet();

    static boolean enabled() {
        return App.prefs.getBoolean("adblock", true);
    }

    /** True for the host or any parent domain on the list. */
    static boolean blocked(String host) {
        Set<String> set = hosts;
        for (String h = host; h != null; ) {
            if (CUSTOM.contains(h) || set.contains(h)) return true;
            int dot = h.indexOf('.');
            h = dot < 0 ? null : h.substring(dot + 1);
        }
        return false;
    }

    /** True for a blocked host or an ad-SDK script path on any (rotating) domain. */
    static boolean blocked(String host, String path) {
        return blocked(host) || (path != null && AD_SCRIPT.matcher(path).matches());
    }

    /** JS: hides links to the custom ad hosts, and the ad box holding them (a banner can't be blocked by domain alone). */
    static String hideScript() {
        StringBuilder b = new StringBuilder("(function(){var B=[");
        for (String h : CUSTOM) b.append('"').append(h).append("\",");
        b.append("];function bad(h){for(var i=0;i<B.length;i++){var x=B[i];if(h===x||h.slice(-x.length-1)==='.'+x)return true}return false}");
        b.append("function scan(){var l=document.querySelectorAll('a[href]');for(var i=0;i<l.length;i++){var a=l[i];try{var h=new URL(a.getAttribute('href'),location.href).hostname;if(!bad(h))continue;var p=a.closest('.module-adslist,.ads,.ad');(p||a).style.display='none'}catch(e){}}}");
        b.append("function start(){scan();var t=0,mo=new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=0;scan()},250)});mo.observe(document.documentElement,{childList:true,subtree:true});setTimeout(function(){mo.disconnect()},15000)}");
        b.append("if(document.documentElement)start();else document.addEventListener('DOMContentLoaded',start)})()");
        return b.toString();
    }

    static void load() {
        File f = new File(App.app.getFilesDir(), "blocklist.txt");
        try (InputStream in = f.exists() ? new java.io.FileInputStream(f) : App.app.getAssets().open("blocklist.txt")) {
            hosts = read(in, new HashSet<>());
        } catch (IOException ignored) { }
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) Warp.whenSettled(() -> App.io.execute(() -> refresh(f)));
    }

    static void refresh(File f) {
        Set<String> fresh = new HashSet<>();
        try {
            for (String url : SOURCES) read(Warp.open(url).getInputStream(), fresh);
        } catch (IOException e) {
            return;
        }
        if (fresh.size() < 10000) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(String.join("\n", fresh).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (tmp.renameTo(f)) hosts = fresh;
    }

    static Set<String> read(InputStream in, Set<String> into) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (!line.isEmpty() && line.charAt(0) != '#') into.add(line);
            }
        }
        return into;
    }
}
