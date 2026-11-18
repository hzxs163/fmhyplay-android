package me.champdev.weblock;

import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * The numbers behind the player's debug overlay (More, Debug overlay): what the stream is (size, codec, bitrates), what the network
 * gives, and what the player does with it. Listens to the player from the start so the totals cover the whole video.
 * The pure helpers at the bottom are plain Java (test/PlayerStatsTest.java).
 */
final class PlayerStats implements AnalyticsListener {
    private long dropped, loadedBytes, estimate, lastBytes, lastMs, vBytes, vMs, aBytes, aMs;   // v and a: bytes and seconds of picture and of sound downloaded, for the bitrate when the stream does not say
    private String videoDecoder = "", audioDecoder = "";
    private Format variant;                                        // the quality the loader is fetching (HLS / DASH): the playing format can lack a bitrate
    private final ArrayDeque<long[]> loads = new ArrayDeque<>();   // {time ms, bytes} of the last 30 s of downloads

    @Override public void onDroppedVideoFrames(EventTime t, int frames, long elapsedMs) { dropped += frames; }

    @Override public void onVideoDecoderInitialized(EventTime t, String name, long at, long took) { videoDecoder = name; }

    @Override public void onAudioDecoderInitialized(EventTime t, String name, long at, long took) { audioDecoder = name; }

    @Override public void onBandwidthEstimate(EventTime t, int ms, long bytes, long bitsPerSecond) { estimate = bitsPerSecond; }

    @Override public void onVideoInputFormatChanged(EventTime t, Format f, DecoderReuseEvaluation reuse) { variant = f; }

    @Override public void onDownstreamFormatChanged(EventTime t, MediaLoadData m) {
        if (m.trackType == C.TRACK_TYPE_VIDEO && m.trackFormat != null) variant = m.trackFormat;
    }

    @Override public void onLoadCompleted(EventTime t, LoadEventInfo l, MediaLoadData m) {
        if (m.dataType != C.DATA_TYPE_MEDIA || l.bytesLoaded <= 0) return;
        loadedBytes += l.bytesLoaded;
        lastBytes = l.bytesLoaded;
        lastMs = l.loadDurationMs;
        long media = m.mediaEndTimeMs - m.mediaStartTimeMs;   // how much playing time the download holds
        if (m.mediaStartTimeMs != C.TIME_UNSET && m.mediaEndTimeMs != C.TIME_UNSET && media > 0) {
            if (m.trackType == C.TRACK_TYPE_AUDIO) { aBytes += l.bytesLoaded; aMs += media; } else { vBytes += l.bytesLoaded; vMs += media; }
        }
        long now = SystemClock.elapsedRealtime();
        loads.add(new long[]{now, l.bytesLoaded});
        while (!loads.isEmpty() && now - loads.peekFirst()[0] > 30000) loads.pollFirst();
    }

    /** The overlay's text, about a dozen short lines. */
    String text(ExoPlayer p, PlayerActivity a) {
        StringBuilder s = new StringBuilder();
        Format v = p.getVideoFormat(), au = p.getAudioFormat();
        VideoSize size = p.getVideoSize();
        float fps = v != null && v.frameRate > 0 ? v.frameRate : a.measuredFps;
        int w = size.width > 0 ? size.width : v != null ? v.width : 0, h = size.height > 0 ? size.height : v != null ? v.height : 0;
        if (w > 0 && h > 0) {
            s.append(w).append('x').append(h);
            if (size.pixelWidthHeightRatio != 1 && size.pixelWidthHeightRatio > 0) s.append(String.format(Locale.US, " (pixels %.2f)", size.pixelWidthHeightRatio));
            if (fps > 0) s.append(String.format(Locale.US, " @ %s fps%s", trim(fps), v != null && v.frameRate > 0 ? "" : " (measured)"));
            s.append("  ").append(codec(v == null ? null : v.sampleMimeType)).append('\n');
        }
        if (v != null) {
            s.append(v.codecs == null ? "" : v.codecs + " | ").append(videoDecoder.isEmpty() ? "no decoder yet" : videoDecoder + (software(videoDecoder) ? " (sw)" : " (hw)")).append('\n');
            Format q = v.averageBitrate > 0 || v.peakBitrate > 0 || variant == null ? v : variant;
            s.append("video ").append(q.averageBitrate > 0 ? bits(q.averageBitrate) : vMs > 0 ? "~" + bits(vBytes * 8000 / vMs) : "?")
                .append(q.peakBitrate > 0 ? " (peak " + bits(q.peakBitrate) + ")" : "").append(" | ").append(range(v.colorInfo)).append('\n');
        }
        if (au != null) {
            s.append("audio ").append(codec(au.sampleMimeType)).append(' ').append(au.channelCount > 0 ? au.channelCount + "ch " : "")
                .append(au.sampleRate > 0 ? trim(au.sampleRate / 1000f) + " kHz " : "")
                .append(au.averageBitrate > 0 ? bits(au.averageBitrate) : au.peakBitrate > 0 ? bits(au.peakBitrate) : aMs > 0 ? "~" + bits(aBytes * 8000 / aMs) : "")
                .append(au.language == null || au.language.equals("und") ? "" : " (" + au.language + ")").append('\n');
            if (!audioDecoder.isEmpty()) s.append("audio dec ").append(audioDecoder).append(software(audioDecoder) ? " (sw)" : " (hw)").append('\n');
        }
        long now = SystemClock.elapsedRealtime(), recent = 0;
        for (long[] l : loads) recent += l[1];
        s.append("net ").append(lastMs > 0 ? bits(lastBytes * 8000 / lastMs) + " last segment" : "no download yet").append(" | est ").append(bits(estimate)).append('\n');
        s.append("avg ").append(bits(loads.isEmpty() ? 0 : recent * 8000 / Math.max(5000, now - loads.peekFirst()[0]))).append(" (30 s) | total ").append(bytes(loadedBytes)).append('\n');
        s.append("buffer ").append(String.format(Locale.US, "%.1f s ahead", p.getTotalBufferedDuration() / 1000f)).append('\n');
        DecoderCounters c = p.getVideoDecoderCounters();   // the renderer's own count of every frame it showed, dropped or skipped
        if (c != null) {
            c.ensureUpdated();
            s.append("frames ").append(c.renderedOutputBufferCount).append(" shown | ").append(c.droppedBufferCount).append(" dropped (")
                .append(pct(c.droppedBufferCount, c.renderedOutputBufferCount + c.droppedBufferCount)).append(") | ").append(c.skippedOutputBufferCount).append(" skipped\n");
            if (c.droppedBufferCount > 0) s.append("worst run ").append(c.maxConsecutiveDroppedBufferCount).append(" in a row | to keyframe ").append(c.droppedToKeyframeCount).append('\n');
            if (c.videoFrameProcessingOffsetCount > 0) {
                long ms = c.totalVideoFrameProcessingOffsetUs / c.videoFrameProcessingOffsetCount / 1000;   // how far ahead of its time a frame is ready: negative is late
                s.append("frame timing avg ").append(Math.abs(ms)).append(" ms ").append(ms >= 0 ? "early" : "LATE").append('\n');
            }
        } else s.append("frames: no counters yet | dropped reported ").append(dropped).append('\n');
        Object m = p.getCurrentManifest();
        String kind = m == null ? "file" : m.getClass().getSimpleName().replace("Manifest", "").toUpperCase(Locale.ROOT);
        android.net.Uri uri = p.getCurrentMediaItem() == null || p.getCurrentMediaItem().localConfiguration == null ? null : p.getCurrentMediaItem().localConfiguration.uri;
        s.append(kind).append(" | ").append(uri == null ? "?" : uri.getHost()).append(" | WARP ").append(Warp.state == Warp.State.ON ? "on" : "off").append('\n');
        s.append(stateName(p.getPlaybackState())).append(p.isPlaying() ? " playing " : " paused ").append(trim(p.getPlaybackParameters().speed)).append("x | ")
            .append(p.isCurrentMediaItemLive() ? "live" : "not live").append('\n');
        long dur = p.getDuration();
        s.append(PlayerActivity.clock(p.getCurrentPosition())).append(dur > 0 ? " / " + PlayerActivity.clock(dur) : "")
            .append(a.readyAt > 0 ? String.format(Locale.US, " | opened in %.1f s", (a.readyAt - a.createdAt) / 1000f) : " | opening...").append('\n');
        Runtime r = Runtime.getRuntime();
        s.append("view ").append(a.surface.getWidth()).append('x').append(a.surface.getHeight()).append(" | ").append(App.t(PlayerActivity.ZOOMS[a.zoom]))
            .append(String.format(Locale.US, " | %.0f Hz | heap %d/%d MB", a.getWindowManager().getDefaultDisplay().getRefreshRate(),
                (r.totalMemory() - r.freeMemory()) >> 20, r.maxMemory() >> 20));
        return s.toString();
    }

    // ---- plain Java ----

    /** 4_200_000 -> "4.2 Mbps"; unknown (zero or less) -> "?". */
    static String bits(long bps) {
        if (bps <= 0) return "?";
        if (bps < 1000) return bps + " bps";
        if (bps < 1_000_000) return Math.round(bps / 1000f) + " kbps";
        return String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f);
    }

    /** pct(3, 1200) -> "0.3%"; nothing to divide -> "0%". */
    static String pct(long part, long whole) {
        return whole <= 0 ? "0%" : String.format(Locale.US, "%.1f%%", part * 100f / whole);
    }

    /** 123_456_789 -> "117.7 MB". */
    static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return Math.round(b / 1024f) + " KB";
        if (b < 1L << 30) return String.format(Locale.US, "%.1f MB", b / 1048576f);
        return String.format(Locale.US, "%.2f GB", b / 1073741824f);
    }

    /** 23.976 -> "23.98", 24.0 -> "24", 1.0 -> "1". */
    static String trim(float f) {
        String s = String.format(Locale.US, "%.2f", f);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** The codec a MIME type stands for. */
    static String codec(String mime) {
        if (mime == null) return "?";
        switch (mime) {
            case "video/avc": return "H.264";
            case "video/hevc": return "H.265 (HEVC)";
            case "video/dolby-vision": return "Dolby Vision";
            case "video/av01": return "AV1";
            case "video/x-vnd.on2.vp9": return "VP9";
            case "video/x-vnd.on2.vp8": return "VP8";
            case "video/mp4v-es": return "MPEG-4";
            case "video/mpeg2": return "MPEG-2";
            case "audio/mp4a-latm": return "AAC";
            case "audio/ac3": return "AC-3";
            case "audio/eac3": return "E-AC-3";
            case "audio/eac3-joc": return "E-AC-3 JOC (Atmos)";
            case "audio/true-hd": return "TrueHD";
            case "audio/vnd.dts": case "audio/vnd.dts.hd": return "DTS";
            case "audio/opus": return "Opus";
            case "audio/vorbis": return "Vorbis";
            case "audio/mpeg": return "MP3";
            case "audio/flac": return "FLAC";
            default: return mime.replaceFirst("^(video|audio)/", "");
        }
    }

    /** Whether a decoder runs on the CPU (the platform's software ones) rather than the device's hardware. */
    static boolean software(String decoder) {
        String d = decoder.toLowerCase(Locale.ROOT);
        return d.startsWith("omx.google.") || d.startsWith("c2.android.") || d.startsWith("c2.google.") || d.contains("sw");
    }

    static String range(ColorInfo c) {
        if (c == null) return "SDR";
        String bits = c.lumaBitdepth > 8 ? " " + c.lumaBitdepth + "-bit" : "";
        if (c.colorTransfer == C.COLOR_TRANSFER_ST2084) return "HDR10 (PQ)" + bits;
        if (c.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG" + bits;
        return "SDR" + bits;
    }

    static String stateName(int state) {
        return state == Player.STATE_BUFFERING ? "BUFFERING" : state == Player.STATE_READY ? "READY" : state == Player.STATE_ENDED ? "ENDED" : "IDLE";
    }

    /**
     * How much of the stream the player keeps ahead, in bytes. It sits in the app's heap, and the player's own figure (144 MB) is
     * more than a small TV's whole heap (80 MB on a 1 GB one: it stayed full and frames were skipped while memory was cleared).
     * A quarter of the heap, never under 12 MB (a few seconds of 1080p) or over the player's own figure.
     */
    static int bufferBytes(long heap) {
        return (int) Math.max(12L << 20, Math.min(heap / 4, 144L << 20));
    }
}
