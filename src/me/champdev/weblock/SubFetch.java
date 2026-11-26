package me.champdev.weblock;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an OpenSubtitles download (gzip, zip or plain; UTF-8 or a legacy code page) into a local UTF-8 subtitle file the
 * player can load. Android-free. Files are cached in the given folder as sha1(url).ext, so a second call costs nothing.
 */
final class SubFetch {
    static final int MAX = 5 * 1024 * 1024;

    static final class Fetched {
        File file;
        /** "srt", "ass" or "vtt". */
        String ext;
        /** What the bytes were decoded as (a cache hit says UTF-8: the stored file is UTF-8). */
        String charset;
    }

    static Fetched fetch(SubOs.Http http, String url, String encodingHint, String lang, File dir) throws IOException {
        String key = sha1(url);
        for (String e : new String[]{"srt", "ass", "vtt"}) {
            File f = new File(dir, key + "." + e);
            if (f.length() > 0) return result(f, e, "UTF-8");
        }
        byte[] b;
        try (InputStream in = http.open(url)) {
            b = SubOs.read(in, MAX, "download");
        }
        String entryExt = "";
        if (b.length > 1 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
            try (InputStream z = new GZIPInputStream(new ByteArrayInputStream(b))) {
                b = SubOs.read(z, MAX, "unpacked subtitle");
            }
        } else if (b.length > 3 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            Object[] best = unzip(b);
            b = (byte[]) best[0];
            entryExt = (String) best[1];
        }

        Charset cs = pick(b, encodingHint, lang);
        String t = new String(b, cs);
        if (t.startsWith("﻿")) t = t.substring(1);
        t = t.replace("\r\n", "\n").replace('\r', '\n');
        int end = t.length();
        while (end > 0 && Character.isWhitespace(t.charAt(end - 1))) end--;
        t = t.substring(0, end);

        String ext = t.startsWith("WEBVTT") ? "vtt" : t.contains("[Script Info]") || entryExt.equals("ass") || entryExt.equals("ssa") ? "ass" : "srt";
        // A quota or block page (HTML, plain text) must not be cached as if it were the subtitle
        if (!ext.equals("ass") && !t.contains("-->")) throw new IOException("not a subtitle file");

        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File out = new File(dir, key + "." + ext);
        File tmp = File.createTempFile(key, ".tmp", dir);
        try {
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(t.getBytes(StandardCharsets.UTF_8));
            }
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
        } finally {
            tmp.delete();
        }
        return result(out, ext, cs.name());
    }

    private static Fetched result(File f, String ext, String charset) {
        Fetched r = new Fetched();
        r.file = f;
        r.ext = ext;
        r.charset = charset;
        return r;
    }

    /** The best subtitle in a zip: .srt, then .ass/.ssa, then .vtt (nfo, txt, url and the rest are skipped). Entry names are only read for their extension. */
    private static Object[] unzip(byte[] zip) throws IOException {
        byte[] best = null;
        String bestExt = "";
        int bestRank = 0, left = MAX;
        // Latin-1 names never fail to decode (release names in CP850/CP1252 are common); we only look at the extension
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.ISO_8859_1)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                // every entry counts against the cap, wanted or not: a zip bomb hides in the unwanted ones
                byte[] d = SubOs.read(z, left, "unpacked subtitle");
                left -= d.length;
                String name = e.getName();
                String x = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                x = x.lastIndexOf('.') < 0 ? "" : x.substring(x.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                int rank = x.equals("srt") ? 1 : x.equals("ass") || x.equals("ssa") ? 2 : x.equals("vtt") ? 3 : 0;
                if (e.isDirectory() || rank == 0 || d.length == 0) continue;
                if (best == null || rank < bestRank || rank == bestRank && d.length > best.length) {
                    best = d;
                    bestRank = rank;
                    bestExt = x;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("bad zip: " + e.getMessage()); // a name flagged UTF-8 that is not
        }
        if (best == null) throw new IOException("no subtitle in zip");
        return new Object[]{best, bestExt};
    }

    /** BOM wins, then strict UTF-8, then the server's encoding hint, then the usual code page of the language. */
    static Charset pick(byte[] b, String hint, String lang) {
        if (b.length > 1 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return StandardCharsets.UTF_16LE;
        if (b.length > 1 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return StandardCharsets.UTF_16BE;
        if (b.length > 2 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return StandardCharsets.UTF_8;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            // not UTF-8: fall through
        }
        Charset c = charset(hint);
        // These bytes just failed as UTF-8/ASCII, so a hint saying so is wrong
        if (c != null && !c.name().startsWith("UTF-") && !c.equals(StandardCharsets.US_ASCII)) {
            // Latin-1 as the web means it: windows-1252 is the same plus the printable 0x80-0x9F
            return c.equals(StandardCharsets.ISO_8859_1) && charset("windows-1252") != null ? charset("windows-1252") : c;
        }
        return forLang(lang);
    }

    /** "CP1256", "cp1256", "windows-1256", "ISO-8859-6", ...: the matching charset, or null when blank or unsupported here. */
    static Charset charset(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String n = name.trim();
        String m = n.toLowerCase(Locale.ROOT).replaceFirst("^(cp|windows|win)[-_ ]?(\\d{3,4})$", "windows-$2");
        for (String s : new String[]{n, m, "x-" + m}) {
            try {
                if (Charset.isSupported(s)) return Charset.forName(s);
            } catch (IllegalArgumentException e) {
                // illegal charset name: try the next spelling
            }
        }
        return null;
    }

    /** A language code lower-cased, with Sinhala's spellings (si, si-LK) as "sin" like the subtitle sources use. */
    static String languageCode(String lang) {
        String code = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        return code.equals("si") || code.startsWith("si-") ? "sin" : code;
    }

    /** The body of a GET; HTTP 202 (an addon still working on the file) is a RetryLaterException, any other failure an IOException. */
    static InputStream openDownload(java.net.HttpURLConnection connection) throws IOException {
        try {
            if (connection.getResponseCode() == java.net.HttpURLConnection.HTTP_ACCEPTED)
                throw new RetryLaterException();
            return new java.io.FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { connection.disconnect(); }
                }
            };
        } catch (IOException e) {
            connection.disconnect();
            throw e;
        }
    }

    /** Legacy code page by OpenSubtitles language code (ISO 639-2/B); Latin languages and unknown codes get windows-1252. */
    static Charset forLang(String lang) {
        String n;
        switch (languageCode(lang)) {
            case "ara": case "per": case "fas": case "urd": n = "windows-1256"; break;
            case "heb": n = "windows-1255"; break;
            case "rus": case "bul": case "ukr": case "srp": case "scc": case "mkd": case "mac": case "bel": n = "windows-1251"; break;
            case "ell": case "gre": n = "windows-1253"; break;
            case "tur": case "aze": n = "windows-1254"; break;
            case "pol": case "cze": case "slo": case "slv": case "hun": case "rum": case "rom": case "hrv": case "alb": case "bos": n = "windows-1250"; break;
            case "est": case "lav": case "lit": n = "windows-1257"; break;
            case "vie": n = "windows-1258"; break;
            case "tha": n = "windows-874"; break;
            case "kor": n = "EUC-KR"; break;
            case "chi": case "zho": case "zhe": n = "GB18030"; break;
            case "zht": n = "Big5"; break;
            case "jpn": n = "Shift_JIS"; break;
            case "sin": n = "UTF-8"; break;   // Sinhala files come as UTF-8 (the BOM is stripped in fetch)
            default: n = "windows-1252";
        }
        Charset c = charset(n);
        return c != null ? c : StandardCharsets.ISO_8859_1;
    }

    /** A 202 from a subtitle addon: the file is being made, ask again shortly. */
    static class RetryLaterException extends IOException {
        RetryLaterException() { super("Subtitle not ready yet (HTTP 202)"); }
    }

    static String sha1(String s) throws IOException {
        try {
            StringBuilder h = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", x & 0xff));
            return h.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
