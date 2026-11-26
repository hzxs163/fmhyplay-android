// Tests SubOs.java (OpenSubtitles legacy search) and SubFetch.java (download -> local UTF-8 file). From the project folder:
// JSON=~/.gradle/caches/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar
// javac --release 8 -cp $JSON -d /tmp/sub src/me/champdev/weblock/SubOs.java src/me/champdev/weblock/SubFetch.java test/SubFetchTest.java && java -cp /tmp/sub:$JSON me.champdev.weblock.SubFetchTest
// The offline part uses synthetic bytes. The LIVE part (last) hits rest.opensubtitles.org (2 searches) and dl.opensubtitles.org
// (2 downloads; the site allows about 200 a day per address). It prints SKIP when offline; add -Dsub.offline=1 to skip it on purpose.
package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SubFetchTest {
    static int fails, urls, calls;
    static File root;

    interface Thrower { void run() throws Exception; }

    static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    static void ioFails(String name, Thrower t) {
        try {
            t.run();
            fails++;
            System.out.println("FAIL " + name + ": no exception");
        } catch (IOException e) {
            // wanted
        } catch (Exception e) {
            fails++;
            System.out.println("FAIL " + name + ": " + e);
        }
    }

    // ---- synthetic material -------------------------------------------------------------------------------------------

    static final String AR1 = "مرحبا بالعالم";
    static final String AR2 = "كيف حالك؟";
    static final String RU = "Привет, мир";

    /** Two cues; the expected result of a fetch is eol "\n" with nothing after the last line. */
    static String srt(String a, String b, String eol) {
        return "1" + eol + "00:00:01,000 --> 00:00:03,000" + eol + a + eol + eol + "2" + eol + "00:00:04,000 --> 00:00:06,000" + eol + b;
    }

    static final String WANT_AR = srt(AR1, AR2, "\n");

    static byte[] bytes(String s, String charset) throws Exception {
        return s.getBytes(charset);
    }

    static byte[] gz(byte[] b) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(o)) { z.write(b); }
        return o.toByteArray();
    }

    /** name, bytes, name, bytes, ... */
    static byte[] zip(Object... parts) throws Exception {
        return zip(StandardCharsets.UTF_8, parts);
    }

    static byte[] zip(Charset names, Object... parts) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o, names)) {
            for (int i = 0; i < parts.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) parts[i]));
                z.write((byte[]) parts[i + 1]);
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] big(int n, char c) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) c);
        return b;
    }

    static SubOs.Http serving(final byte[] body) {
        return new SubOs.Http() {
            @Override public java.io.InputStream open(String url) { calls++; return new ByteArrayInputStream(body); }
        };
    }

    static File newDir() throws Exception {
        return Files.createTempDirectory(root.toPath(), "d").toFile();
    }

    /** Fetches synthetic bytes under a fresh URL and fresh cache folder. */
    static SubFetch.Fetched fetch(byte[] body, String hint, String lang) throws Exception {
        return SubFetch.fetch(serving(body), "http://fake/" + (urls++) + ".gz", hint, lang, newDir());
    }

    static byte[] file(SubFetch.Fetched f) throws Exception {
        return Files.readAllBytes(f.file.toPath());
    }

    static String text(SubFetch.Fetched f) throws Exception {
        return new String(file(f), StandardCharsets.UTF_8);
    }

    /** The file must be strict UTF-8, no BOM, no CR. */
    static void cleanOutput(String name, SubFetch.Fetched f) throws Exception {
        byte[] b = file(f);
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b));
        check(name + " no BOM", b.length > 2 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB, false);
        check(name + " no CR", new String(b, StandardCharsets.UTF_8).indexOf('\r'), -1);
        check(name + " file name", f.file.getName().matches("[0-9a-f]{40}\\." + f.ext), true);
    }

    // ---- offline ------------------------------------------------------------------------------------------------------

    static void offline() throws Exception {
        // gzip of a windows-1256 file, hint as OpenSubtitles writes it
        byte[] cp1256 = bytes(srt(AR1, AR2, "\r\n") + "\r\n\r\n", "windows-1256");
        SubFetch.Fetched f = fetch(gz(cp1256), "CP1256", "ara");
        check("gz cp1256 text", text(f), WANT_AR);
        check("gz cp1256 ext", f.ext, "srt");
        check("gz cp1256 charset", f.charset, "windows-1256");
        cleanOutput("gz cp1256", f);
        // the same without a usable hint: the language default
        check("no hint, ara", fetch(gz(cp1256), "", "ara").charset, "windows-1256");
        check("no hint, ara text", text(fetch(gz(cp1256), null, "ara")), WANT_AR);
        check("per default", fetch(gz(cp1256), "", "per").charset, "windows-1256");
        // plain (not compressed) body is treated as the text
        check("raw cp1256", text(fetch(cp1256, "windows-1256", "ara")), WANT_AR);

        // zip: the srt, not the nfo/txt/url
        f = fetch(zip("readme.nfo", bytes("visit us", "UTF-8"), "site.url", bytes("[InternetShortcut]", "UTF-8"), "notes.txt", bytes("x", "UTF-8"),
                "The.Matrix.srt", bytes(srt(AR1, AR2, "\r\n"), "UTF-8")), "UTF-8", "ara");
        check("zip srt text", text(f), WANT_AR);
        check("zip srt ext", f.ext, "srt");
        check("zip srt charset", f.charset, "UTF-8");
        // srt beats ass beats vtt, whatever the order
        String ass = "[Script Info]\nTitle: t\n[Events]\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,," + AR1;
        f = fetch(zip("a.ass", bytes(ass, "UTF-8"), "b.vtt", bytes("WEBVTT\n\n00:01.000 --> 00:03.000\nx", "UTF-8"), "c.srt", bytes(srt(AR1, AR2, "\n"), "UTF-8")), "", "ara");
        check("zip prefers srt", f.ext + text(f).equals(WANT_AR), "srttrue");
        f = fetch(zip("b.vtt", bytes("WEBVTT\n\n00:01.000 --> 00:03.000\nx", "UTF-8"), "a.SSA", bytes(ass, "UTF-8")), "", "ara");
        check("zip ass over vtt", f.ext, "ass");
        check("zip ass text", text(f), ass);
        ioFails("zip with only nfo", () -> fetch(zip("a.nfo", bytes("x", "UTF-8")), "", "eng"));
        // an entry name is only an extension: no path escapes the folder
        File dir = newDir();
        f = SubFetch.fetch(serving(zip("../../evil.srt", bytes(srt("a", "b", "\n"), "UTF-8"))), "http://fake/evil", "", "eng", dir);
        check("evil name stays in dir", f.file.getParentFile().getCanonicalPath(), dir.getCanonicalPath());
        check("evil name leaves one file", dir.list().length, 1);
        check("evil name not written next to dir", new File(dir.getParentFile(), "evil.srt").exists() || new File(dir.getParentFile().getParentFile(), "evil.srt").exists(), false);
        // a zip entry whose name is not UTF-8 (CP850/Latin-1 release names are common)
        f = fetch(zip(StandardCharsets.ISO_8859_1, "Café del Mar.srt", bytes(srt("a", "b", "\n"), "UTF-8")), "", "eng");
        check("latin-1 entry name", text(f), srt("a", "b", "\n"));

        // BOMs
        f = fetch(cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes(srt(AR1, AR2, "\r\n"), "UTF-8")), "CP1256", "ara");
        check("utf-8 bom text", text(f), WANT_AR);
        check("utf-8 bom charset", f.charset, "UTF-8");
        cleanOutput("utf-8 bom", f);
        f = fetch(cat(new byte[]{(byte) 0xFF, (byte) 0xFE}, bytes(srt(AR1, AR2, "\r\n"), "UTF-16LE")), "CP1252", "eng");
        check("utf-16le text", text(f), WANT_AR);
        check("utf-16le charset", f.charset, "UTF-16LE");
        cleanOutput("utf-16le", f);
        f = fetch(cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, bytes(srt(AR1, AR2, "\n"), "UTF-16BE")), "", "eng");
        check("utf-16be text", text(f), WANT_AR);
        check("utf-16be charset", f.charset, "UTF-16BE");

        // valid UTF-8 wins over any hint or language
        f = fetch(bytes(srt(AR1, AR2, "\n"), "UTF-8"), "CP1252", "fre");
        check("utf-8 passthrough", text(f), WANT_AR);
        check("utf-8 passthrough charset", f.charset, "UTF-8");
        f = fetch(bytes(srt("plain ascii", "ok", "\n"), "US-ASCII"), "ASCII", "eng");
        check("ascii", f.charset, "UTF-8");

        // Cyrillic windows-1251 with its hint, with a hint spelled differently, and by language only
        byte[] cp1251 = bytes(srt(RU, RU, "\r\n"), "windows-1251");
        f = fetch(cp1251, "CP1251", "rus");
        check("cp1251 text", text(f), srt(RU, RU, "\n"));
        check("cp1251 charset", f.charset, "windows-1251");
        check("cp1251 hint wins over lang", fetch(cp1251, "windows-1251", "ara").charset, "windows-1251");
        // unsupported hint -> language default; a hint that cannot be right (UTF-8 for non-UTF-8 bytes) -> language default
        f = fetch(cp1251, "FOO-BAR-9", "rus");
        check("bad hint -> lang", f.charset + text(f).equals(srt(RU, RU, "\n")), "windows-1251true");
        check("hint with spaces -> lang", fetch(cp1251, "not a charset!", "bul").charset, "windows-1251");
        check("UTF-8 hint, non-UTF-8 bytes -> lang", fetch(cp1256, "UTF-8", "ara").charset, "windows-1256");
        check("blank hint, unknown lang -> windows-1252", fetch(bytes(srt("café", "ok", "\n"), "windows-1252"), "", "xxx").charset, "windows-1252");
        check("blank hint, no lang -> windows-1252", text(fetch(bytes(srt("café", "ok", "\n"), "windows-1252"), "", "")), srt("café", "ok", "\n"));
        // ISO-8859-1 as labelled by sites really is windows-1252 (smart quotes live in 0x80-0x9F)
        f = fetch(bytes(srt("“quoted”", "ok", "\n"), "windows-1252"), "ISO-8859-1", "eng");
        check("latin1 hint is 1252", f.charset + text(f).contains("“quoted”"), "windows-1252true");
        // other scripts
        check("hebrew", text(fetch(bytes(srt("שלום", "ok", "\n"), "windows-1255"), "", "heb")), srt("שלום", "ok", "\n"));
        check("korean", text(fetch(bytes(srt("안녕", "ok", "\n"), "EUC-KR"), "", "kor")), srt("안녕", "ok", "\n"));
        check("chinese", text(fetch(bytes(srt("你好", "ok", "\n"), "GB18030"), "", "chi")), srt("你好", "ok", "\n"));
        check("japanese", text(fetch(bytes(srt("こんにちは", "ok", "\n"), "Shift_JIS"), "", "jpn")), srt("こんにちは", "ok", "\n"));
        check("thai", text(fetch(bytes(srt("สวัสดี", "ok", "\n"), "windows-874"), "", "tha")), srt("สวัสดี", "ok", "\n"));
        check("polish", text(fetch(bytes(srt("żółw", "ok", "\n"), "windows-1250"), "", "pol")), srt("żółw", "ok", "\n"));
        check("greek", text(fetch(bytes(srt("γεια", "ok", "\n"), "windows-1253"), "", "ell")), srt("γεια", "ok", "\n"));
        check("turkish", text(fetch(bytes(srt("ğşı", "ok", "\n"), "windows-1254"), "", "tur")), srt("ğşı", "ok", "\n"));

        // charset names and the language table
        for (String n : new String[]{"CP1256", "cp1256", "CP-1256", "windows-1256", "Windows-1256", "win1256", "ISO-8859-6"}) {
            Charset c = SubFetch.charset(n);
            check("name " + n, c != null && (c.name().equals("windows-1256") || c.name().equals("ISO-8859-6")), true);
        }
        check("name CP1251", SubFetch.charset("CP1251").name(), "windows-1251");
        // every SubEncoding value met live (CP949 and CP950 are the two Java spells x-windows-949/950)
        for (String n : new String[]{"CP1250", "CP1252", "CP1253", "CP1254", "CP1255", "CP1257", "CP949", "CP950", "ISO-8859-8", "ISO-8859-2", "BIG5", "GB2312", "SHIFT_JIS", "EUC-KR"}) {
            check("name " + n, SubFetch.charset(n) != null, true);
        }
        check("hint CP949", text(fetch(bytes(srt("안녕", "ok", "\n"), "x-windows-949"), "CP949", "eng")), srt("안녕", "ok", "\n"));
        check("hint CP950", text(fetch(bytes(srt("你好", "ok", "\n"), "x-windows-950"), "CP950", "eng")), srt("你好", "ok", "\n"));
        check("name blank", SubFetch.charset("  "), null);
        check("name null", SubFetch.charset(null), null);
        check("name unknown", SubFetch.charset("CP99999"), null);
        String[][] table = {{"ara", "windows-1256"}, {"per", "windows-1256"}, {"fas", "windows-1256"}, {"urd", "windows-1256"}, {"heb", "windows-1255"},
                {"rus", "windows-1251"}, {"bul", "windows-1251"}, {"ukr", "windows-1251"}, {"srp", "windows-1251"}, {"scc", "windows-1251"}, {"mkd", "windows-1251"}, {"bel", "windows-1251"},
                {"ell", "windows-1253"}, {"tur", "windows-1254"}, {"aze", "windows-1254"},
                {"pol", "windows-1250"}, {"cze", "windows-1250"}, {"slo", "windows-1250"}, {"slv", "windows-1250"}, {"hun", "windows-1250"}, {"rum", "windows-1250"}, {"rom", "windows-1250"}, {"hrv", "windows-1250"}, {"alb", "windows-1250"}, {"bos", "windows-1250"},
                {"est", "windows-1257"}, {"lav", "windows-1257"}, {"lit", "windows-1257"}, {"vie", "windows-1258"},
                {"kor", "EUC-KR"}, {"chi", "GB18030"}, {"zho", "GB18030"}, {"jpn", "Shift_JIS"},
                {"fre", "windows-1252"}, {"pob", "windows-1252"}, {"eng", "windows-1252"}, {"", "windows-1252"}, {"ARA", "windows-1256"},
                {"sin", "UTF-8"}, {"si", "UTF-8"}, {"SI-LK", "UTF-8"}};
        for (String[] r : table) check("lang " + r[0], SubFetch.forLang(r[0]).name(), r[1]);
        check("lang tha", SubFetch.forLang("tha").name().endsWith("874"), true);
        check("code si", SubFetch.languageCode(" si "), "sin");
        check("code eng", SubFetch.languageCode("ENG"), "eng");
        check("code null", SubFetch.languageCode(null), "");

        // an addon still making the file answers 202: RetryLaterException, anything else is a body or an IOException
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/wait", x -> { x.sendResponseHeaders(202, -1); x.close(); });
        srv.createContext("/ok", x -> { byte[] b = "hello".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        srv.createContext("/gone", x -> { x.sendResponseHeaders(404, -1); x.close(); });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            try (InputStream in = SubFetch.openDownload((HttpURLConnection) new URL(base + "/ok").openConnection())) { check("download 200", new String(SubOs.read(in, 100, "t"), StandardCharsets.UTF_8), "hello"); }
            try { SubFetch.openDownload((HttpURLConnection) new URL(base + "/wait").openConnection()); check("download 202", "returned", "RetryLaterException"); }
            catch (SubFetch.RetryLaterException e) { check("download 202", "retry", "retry"); }
            try { SubFetch.openDownload((HttpURLConnection) new URL(base + "/gone").openConnection()); check("download 404", "returned", "IOException"); }
            catch (SubFetch.RetryLaterException e) { check("download 404", "retry", "IOException"); }
            catch (IOException e) { check("download 404", "io", "io"); }
        } finally { srv.stop(0); }

        // text normalisation: CRLF and lone CR -> LF, trailing whitespace trimmed, nothing else touched
        f = fetch(bytes(srt("a  ", "b\rc", "\r\n") + " \r\n\t\r\n\n  ", "UTF-8"), "", "eng");
        check("eol and trim", text(f), "1\n00:00:01,000 --> 00:00:03,000\na  \n\n2\n00:00:04,000 --> 00:00:06,000\nb\nc");

        // formats
        f = fetch(bytes("WEBVTT\n\n00:01.000 --> 00:03.000\nHi\n", "UTF-8"), "", "eng");
        check("vtt ext", f.ext, "vtt");
        check("vtt text", text(f), "WEBVTT\n\n00:01.000 --> 00:03.000\nHi");
        f = fetch(bytes(ass, "UTF-8"), "", "eng");
        check("ass ext", f.ext, "ass");
        check("ass keeps text", text(f), ass);
        f = fetch(cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes("WEBVTT\r\n\r\n00:01.000 --> 00:03.000\r\nHi", "UTF-8")), "", "eng");
        check("vtt behind a BOM", f.ext, "vtt");
        cleanOutput("vtt", f);
        check("plain is srt", fetch(bytes(srt("a", "b", "\n"), "UTF-8"), "", "eng").ext, "srt");

        // caps: 5 MB download, 5 MB unpacked (gzip and zip bombs), and things that are not subtitles at all
        ioFails("download over cap", () -> fetch(big(SubFetch.MAX + 1, 'a'), "", "eng"));
        ioFails("gzip bomb", () -> fetch(gz(big(SubFetch.MAX + 1, 'a')), "", "eng"));
        ioFails("zip bomb in an unwanted entry", () -> fetch(zip("a.nfo", big(SubFetch.MAX + 1, 'a'), "b.srt", bytes(srt("a", "b", "\n"), "UTF-8")), "", "eng"));
        ioFails("zip entries add up", () -> fetch(zip("a.nfo", big(3 << 20, 'a'), "b.srt", cat(bytes(srt("a", "b", "\n"), "UTF-8"), big(3 << 20, 'a'))), "", "eng"));
        ioFails("truncated gzip", () -> fetch(Arrays.copyOf(gz(cp1256), 20), "", "ara"));
        ioFails("empty body", () -> fetch(new byte[0], "", "eng"));
        dir = newDir();
        final File d = dir;
        ioFails("html page is not cached", () -> SubFetch.fetch(serving(bytes("<html><body>Download quota exceeded</body></html>", "UTF-8")), "http://fake/quota", "", "eng", d));
        ioFails("html page in gzip", () -> SubFetch.fetch(serving(gz(bytes("<html>blocked</html>", "UTF-8"))), "http://fake/quota2", "", "eng", d));
        check("nothing left behind", d.list().length, 0);
        check("ok just under the cap", fetch(cat(bytes(srt("a", "b", "\n") + "\n", "UTF-8"), big(SubFetch.MAX - 100, ' ')), "", "eng").ext, "srt");

        // cache: second call needs no download; an empty cached file is not a hit; the folder is created
        dir = new File(newDir(), "sub/folder");
        calls = 0;
        SubOs.Http h = serving(gz(cp1256));
        f = SubFetch.fetch(h, "http://fake/cache", "CP1256", "ara", dir);
        SubFetch.Fetched g = SubFetch.fetch(h, "http://fake/cache", "CP1256", "ara", dir);
        check("cache downloads once", calls, 1);
        check("cache same file", g.file, f.file);
        check("cache ext", g.ext, "srt");
        check("cache text", text(g), WANT_AR);
        try (FileOutputStream o = new FileOutputStream(f.file)) { o.write(new byte[0]); }
        g = SubFetch.fetch(h, "http://fake/cache", "CP1256", "ara", dir);
        check("empty cache refetched", calls + "" + text(g).equals(WANT_AR), "2true");
        check("no temp files left", dir.list().length, 1);

        offlineSearch();
    }

    // ---- SubOs without the network --------------------------------------------------------------------------------------

    static JSONObject entry(String link, String lang, String name, String downloads) throws Exception {
        JSONObject o = new JSONObject();
        if (link != null) o.put("SubDownloadLink", link);
        o.put("SubLanguageID", lang).put("SubFileName", name).put("SubDownloadsCnt", downloads).put("SubFormat", "srt").put("SubEncoding", "CP1256")
                .put("SubBad", "0").put("SubHearingImpaired", "0").put("MovieReleaseName", "Movie.Release");
        return o;
    }

    static void offlineSearch() throws Exception {
        String B = SubOs.BASE;
        check("url movie", SubOs.url("tt0133093", 0, 0, "ara"), B + "imdbid-0133093/sublanguageid-ara");
        check("url movie padded", SubOs.url("tt133093", 0, 0, "ara"), B + "imdbid-0133093/sublanguageid-ara");
        check("url movie bare digits, upper-case lang", SubOs.url(" 0133093 ", 0, 0, "ARA"), B + "imdbid-0133093/sublanguageid-ara");
        check("url movie all languages", SubOs.url("tt0133093", 0, 0, ""), B + "imdbid-0133093");
        check("url movie null lang", SubOs.url("tt0133093", 0, 0, null), B + "imdbid-0133093");
        check("url episode", SubOs.url("tt0903747", 1, 1, "eng"), B + "episode-1/imdbid-0903747/season-1/sublanguageid-eng");
        check("url episode all languages", SubOs.url("tt0903747", 2, 13, ""), B + "episode-13/imdbid-0903747/season-2");
        check("url two languages", SubOs.url("tt0903747", 0, 0, "eng,ara"), B + "imdbid-0903747/sublanguageid-eng,ara");
        ioFails("url bad lang", () -> SubOs.url("tt0133093", 0, 0, "../x"));
        ioFails("url bad lang 2", () -> SubOs.url("tt0133093", 0, 0, "english"));
        ioFails("url bad imdb", () -> SubOs.url("tt01x", 0, 0, ""));
        ioFails("url empty imdb", () -> SubOs.url("", 0, 0, ""));

        JSONArray a = new JSONArray();
        a.put(entry("http://dl/a.gz", "ara", "a.srt", "5"));
        a.put(entry("http://dl/b.gz", "ara", "b.srt", "10").put("SubHearingImpaired", "1"));
        a.put(entry(null, "ara", "nolink.srt", "999"));
        a.put(entry("", "ara", "emptylink.srt", "999"));
        a.put(entry("http://dl/bad.gz", "ara", "bad.srt", "999").put("SubBad", "1"));
        a.put(entry("http://dl/sub.gz", "ara", "microdvd.sub", "999").put("SubFormat", "sub"));
        a.put(entry("http://dl/rel.gz", "ara", "", "7"));
        a.put(entry("http://dl/ass.gz", "ara", "x.ass", "6").put("SubFormat", "SSA"));
        a.put(entry("http://dl/nodl.gz", "ara", "nodl.srt", "x"));
        a.put("junk");
        final List<String> asked = new ArrayList<>();
        SubOs.Http h = u -> { asked.add(u); return new ByteArrayInputStream(a.toString().getBytes(StandardCharsets.UTF_8)); };
        List<SubOs.Result> r = SubOs.search(h, "tt0133093", 0, 0, "ara");
        check("asked", asked.toString(), "[" + B + "imdbid-0133093/sublanguageid-ara]");
        StringBuilder order = new StringBuilder();
        for (SubOs.Result x : r) order.append(x.url.substring(x.url.lastIndexOf('/') + 1)).append(' ');
        check("filtered and sorted", order.toString(), "b.gz rel.gz ass.gz a.gz nodl.gz ");
        check("fields", r.get(0).lang + "|" + r.get(0).release + "|" + r.get(0).format + "|" + r.get(0).encoding + "|" + r.get(0).downloads + "|" + r.get(0).hearingImpaired,
                "ara|b.srt|srt|CP1256|10|true");
        check("release falls back to MovieReleaseName", r.get(1).release, "Movie.Release");
        check("format lower-cased", r.get(2).format, "ssa");

        JSONArray many = new JSONArray();
        for (int i = 0; i < 150; i++) many.put(entry("http://dl/" + i + ".gz", "eng", "f" + i, "" + i));
        r = SubOs.search(u -> new ByteArrayInputStream(many.toString().getBytes(StandardCharsets.UTF_8)), "tt1", 0, 0, "");
        check("capped at 100", r.size(), 100);
        check("most downloaded first", r.get(0).downloads + "," + r.get(99).downloads, "149,50");
        check("empty list", SubOs.search(serving("[]".getBytes()), "tt1", 0, 0, "ara").size(), 0);

        ioFails("html answer", () -> SubOs.search(serving("<html><body>Too many requests</body></html>".getBytes()), "tt1", 0, 0, "ara"));
        ioFails("text answer", () -> SubOs.search(serving("Rate limit exceeded".getBytes()), "tt1", 0, 0, "ara"));
        ioFails("json object answer", () -> SubOs.search(serving("{\"status\":\"429\"}".getBytes()), "tt1", 0, 0, "ara"));
        ioFails("cut-off json", () -> SubOs.search(serving("[{\"SubDownloadLink\":\"http".getBytes()), "tt1", 0, 0, "ara"));
        ioFails("empty answer", () -> SubOs.search(serving(new byte[0]), "tt1", 0, 0, "ara"));
        try {
            SubOs.search(serving("<html>blocked</html>".getBytes()), "tt1", 0, 0, "ara");
        } catch (IOException e) {
            check("short message", e.getMessage().length() < 120 && e.getMessage().contains("blocked"), true);
        }
    }

    // ---- live ---------------------------------------------------------------------------------------------------------

    static final SubOs.Http NET = url -> {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", SubOs.UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        return c.getInputStream();
    };

    static boolean unreachable(IOException e) {
        return e instanceof UnknownHostException || e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof NoRouteToHostException;
    }

    static boolean arabic(String s) {
        for (char c : s.toCharArray()) if (c >= 0x0600 && c <= 0x06FF) return true;
        return false;
    }

    /** First non-empty line is a cue number, the next one a time range. */
    static boolean looksLikeSrt(String s) {
        String[] l = s.trim().split("\n");
        return l.length > 2 && l[0].trim().matches("\\d+") && l[1].contains("-->");
    }

    static void fetchLive(String name, SubOs.Result r, String charset) throws Exception {
        File dir = newDir();
        calls = 0;
        SubOs.Http counting = u -> { calls++; return NET.open(u); };
        SubFetch.Fetched f = SubFetch.fetch(counting, r.url, r.encoding, r.lang, dir);
        String t = text(f);
        System.out.println("  " + name + ": " + r.release + " (" + r.encoding + ") -> " + f.ext + " as " + f.charset + ", " + t.length() + " chars");
        check(name + " has Arabic letters", arabic(t), true);
        check(name + " is srt", f.ext.equals("srt") && looksLikeSrt(t), true);
        cleanOutput(name, f);
        if (charset != null) check(name + " charset", f.charset, charset);
        check(name + " cache hit", SubFetch.fetch(counting, r.url, r.encoding, r.lang, dir).file, f.file);
        check(name + " one download", calls, 1);
    }

    static void live() throws Exception {
        if (System.getProperty("sub.offline") != null) { System.out.println("SKIP live checks (sub.offline)"); return; }
        System.out.println("LIVE checks");
        try {
            List<SubOs.Result> ara = SubOs.search(NET, "tt0133093", 0, 0, "ara");
            System.out.println("  The Matrix, ara: " + ara.size() + " results");
            check("matrix ara > 10", ara.size() > 10, true);
            boolean sorted = true, allAra = true, https = true;
            for (int i = 0; i < ara.size(); i++) {
                allAra &= "ara".equals(ara.get(i).lang);
                https &= ara.get(i).url.startsWith("https://");
                if (i > 0) sorted &= ara.get(i - 1).downloads >= ara.get(i).downloads;
            }
            check("matrix ara all ara", allAra, true);
            check("matrix ara sorted", sorted, true);
            check("matrix ara https links", https, true);

            List<SubOs.Result> bb = SubOs.search(NET, "tt0903747", 1, 1, "eng");
            System.out.println("  Breaking Bad S01E01, eng: " + bb.size() + " results");
            check("breaking bad s01e01 eng has results", bb.size() > 0, true);

            if (ara.isEmpty()) return;
            fetchLive("first arabic result", ara.get(0), null);
            for (SubOs.Result r : ara) {
                if ("CP1256".equalsIgnoreCase(r.encoding) && r.format.equals("srt")) { fetchLive("first CP1256 arabic result", r, "windows-1256"); break; }
            }
        } catch (IOException e) {
            if (unreachable(e)) { System.out.println("SKIP live checks (offline: " + e + ")"); return; }
            fails++;
            System.out.println("FAIL live: " + e);
        }
    }

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory("subtest").toFile();
        try {
            offline();
            live();
        } finally {
            deleteAll(root);
        }
        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("sub tests passed");
    }

    static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
