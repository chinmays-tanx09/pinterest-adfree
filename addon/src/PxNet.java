package app.hushpinterest.extension.pinterest.actions;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Network side of the Paradox download add-on: probing, plain downloads and HLS playlists. Pure Java. */
public final class PxNet {
    private PxNet() {}

    public interface Progress { void update(long done, long total); }

    static HttpURLConnection open(String url, String method, long[] range) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", PxMedia.UA);
        if (range != null) c.setRequestProperty("Range", "bytes=" + range[1] + "-" + (range[1] + range[0] - 1));
        return c;
    }

    /** True when the media host answers 200 with a type starting with [typePrefix]. */
    public static boolean present(String url, String typePrefix) {
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD", null);
            int code = c.getResponseCode();
            String type = c.getContentType();
            return code == 200 && type != null && type.toLowerCase(Locale.ROOT).startsWith(typePrefix);
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String text(String url) throws IOException {
        HttpURLConnection c = open(url, "GET", null);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode() + " for playlist");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { copy(in, b, null, 0, -1); }
            return b.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** Downloads [url] (or one byte range of it) into [out]; returns the type the host named. */
    public static String fetch(String url, long[] range, OutputStream out, Progress p, long base, long total) throws IOException {
        HttpURLConnection c = open(url, "GET", range);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            if (range != null && code != 206) throw new IOException("Range not honoured");
            long len = c.getContentLengthLong();
            try (InputStream in = c.getInputStream()) { copy(in, out, p, base, total > 0 ? total : len); }
            return c.getContentType();
        } finally { c.disconnect(); }
    }

    static long copy(InputStream in, OutputStream out, Progress p, long base, long total) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long done = 0, last = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && done - last > 512 * 1024) { last = done; p.update(base + done, total); }
        }
        if (p != null) p.update(base + done, total);
        return done;
    }

    // ------------------------------------------------------------------ HLS

    /** One rendition: an optional init section and its media segments, each with an optional byte range {length, offset}. */
    public static final class Track {
        public String map; public long[] mapRange;
        public final List<String> segments = new ArrayList<>();
        public final List<long[]> ranges = new ArrayList<>();
        public boolean encrypted;

        /** The single file every part comes from, when the parts tile it from byte 0 (CMAF single-file HLS). */
        public String singleFile() {
            String file = map != null ? map : (segments.isEmpty() ? null : segments.get(0));
            if (file == null) return null;
            long next = 0;
            List<long[]> parts = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            if (map != null) { urls.add(map); parts.add(mapRange); }
            urls.addAll(segments); parts.addAll(ranges);
            for (int i = 0; i < urls.size(); i++) {
                if (!file.equals(urls.get(i))) return null;
                long[] r = parts.get(i);
                if (r == null || r[1] != next) return null;
                next = r[1] + r[0];
            }
            return file;
        }
    }

    static String resolve(String base, String ref) {
        try { return URI.create(base).resolve(ref.trim()).toString(); } catch (RuntimeException e) { return ref; }
    }

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    static String attr(String line, String name) {
        Matcher m = ATTR.matcher(line.substring(line.indexOf(':') + 1));
        while (m.find()) {
            if (m.group(1).equals(name)) {
                String v = m.group(2);
                return v.startsWith("\"") ? v.substring(1, v.length() - 1) : v;
            }
        }
        return null;
    }

    static long[] byteRange(String spec, long next) {
        if (spec == null) return null;
        String[] p = spec.split("@");
        long len = Long.parseLong(p[0].trim());
        long off = p.length > 1 ? Long.parseLong(p[1].trim()) : next;
        return new long[]{len, off};
    }

    public static boolean isMaster(String text) { return text.contains("#EXT-X-STREAM-INF"); }

    /** {video playlist, audio playlist or null}: the highest-bandwidth variant and the audio group it names. */
    public static String[] pickVariant(String masterUrl, String text) {
        String[] lines = text.split("\r?\n");
        String best = null, bestGroup = null; double bestScore = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String n = lines[j].trim();
                if (n.isEmpty() || n.startsWith("#")) continue;
                uri = n; break;
            }
            if (uri == null) continue;
            double bw = 0, area = 0;
            try { bw = Double.parseDouble(attr(l, "BANDWIDTH")); } catch (Exception ignored) { }
            String res = attr(l, "RESOLUTION");
            if (res != null && res.contains("x")) try {
                String[] wh = res.split("x"); area = Double.parseDouble(wh[0]) * Double.parseDouble(wh[1]);
            } catch (Exception ignored) { }
            double score = area * 1e9 + bw;
            if (score > bestScore) { bestScore = score; best = resolve(masterUrl, uri); bestGroup = attr(l, "AUDIO"); }
        }
        String audio = null;
        if (bestGroup != null) {
            for (String l : lines) {
                l = l.trim();
                if (!l.startsWith("#EXT-X-MEDIA") || !"AUDIO".equals(attr(l, "TYPE")) || !bestGroup.equals(attr(l, "GROUP-ID"))) continue;
                String u = attr(l, "URI");
                if (u == null) continue;
                if (audio == null || "YES".equals(attr(l, "DEFAULT"))) audio = resolve(masterUrl, u);
            }
        }
        return new String[]{best, audio};
    }

    public static Track parseMedia(String url, String text) {
        Track t = new Track();
        long next = 0;
        String pendingRange = null;
        for (String raw : text.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-KEY")) {
                String method = attr(l, "METHOD");
                if (method != null && !method.equals("NONE")) t.encrypted = true;
            } else if (l.startsWith("#EXT-X-MAP")) {
                t.map = resolve(url, attr(l, "URI"));
                t.mapRange = byteRange(attr(l, "BYTERANGE"), 0);
            } else if (l.startsWith("#EXT-X-BYTERANGE")) {
                pendingRange = l.substring(l.indexOf(':') + 1);
            } else if (!l.startsWith("#")) {
                String seg = resolve(url, l);
                long[] r = pendingRange == null ? null : byteRange(pendingRange, seg.equals(lastSeg(t)) ? next : 0);
                t.segments.add(seg);
                t.ranges.add(r);
                next = r == null ? 0 : r[1] + r[0];
                pendingRange = null;
            }
        }
        return t;
    }

    private static String lastSeg(Track t) { return t.segments.isEmpty() ? null : t.segments.get(t.segments.size() - 1); }

    static long knownSize(Track t) {
        long sum = 0;
        if (t.map != null) { if (t.mapRange == null) return -1; sum += t.mapRange[0]; }
        for (long[] r : t.ranges) { if (r == null) return -1; sum += r[0]; }
        return sum;
    }

    /** Writes one rendition, init section first, as a single file a demuxer can read. */
    public static void fetchTrack(Track t, File out, Progress p) throws IOException {
        if (t.encrypted) throw new IOException("This video is encrypted");
        if (t.segments.isEmpty()) throw new IOException("Empty playlist");
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 20)) {
            String single = t.singleFile();
            if (single != null) {
                long total = knownSize(t);
                // One request for the whole file; anything past the last segment is cut off.
                HttpURLConnection c = open(single, "GET", total > 0 ? new long[]{total, 0} : null);
                try {
                    int code = c.getResponseCode();
                    if (code / 100 != 2) throw new IOException("HTTP " + code);
                    try (InputStream in = c.getInputStream()) {
                        InputStream limited = total > 0 ? new Limited(in, total) : in;
                        copy(limited, os, p, 0, total);
                    }
                } finally { c.disconnect(); }
                return;
            }
            long total = knownSize(t), base = 0;
            if (t.map != null) base += countingFetch(t.map, t.mapRange, os, p, base, total);
            for (int i = 0; i < t.segments.size(); i++) {
                base += countingFetch(t.segments.get(i), t.ranges.get(i), os, p, base, total);
                if (total <= 0 && p != null) p.update(i + 1, -(long) t.segments.size());
            }
        }
    }

    private static long countingFetch(String url, long[] range, OutputStream os, Progress p, long base, long total) throws IOException {
        final long[] got = {0};
        CountingStream cs = new CountingStream(os);
        fetch(url, range, cs, total > 0 ? p : null, base, total);
        return cs.count;
    }

    static final class CountingStream extends OutputStream {
        final OutputStream out; long count;
        CountingStream(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int o, int n) throws IOException { out.write(b, o, n); count += n; }
        @Override public void flush() throws IOException { out.flush(); }
    }

    static final class Limited extends InputStream {
        final InputStream in; long left;
        Limited(InputStream in, long left) { this.in = in; this.left = left; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int b = in.read(); if (b >= 0) left--; return b; }
        @Override public int read(byte[] b, int o, int n) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, o, (int) Math.min(n, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    /** Downloads an HLS video as one or two demuxable files: {video(+audio), separate audio or null}. */
    public static File[] fetchHls(String url, File dir, Progress p) throws IOException {
        String text = text(url);
        String videoUrl = url, audioUrl = null;
        if (isMaster(text)) {
            String[] pick = pickVariant(url, text);
            if (pick[0] == null) throw new IOException("No playable variant");
            videoUrl = pick[0]; audioUrl = pick[1];
            text = text(videoUrl);
        }
        Track video = parseMedia(videoUrl, text);
        Track audio = audioUrl == null ? null : parseMedia(audioUrl, text(audioUrl));
        String vExt = video.map != null || pathEnds(videoUrl, video, ".cmfv", ".mp4", ".m4s", ".m4v") ? ".mp4" : ".ts";
        File v = new File(dir, "px_video_" + System.nanoTime() + vExt);
        fetchTrack(video, v, p);
        File a = null;
        if (audio != null) {
            String aExt = audio.map != null ? ".mp4" : (pathEnds(audioUrl, audio, ".aac") ? ".aac" : ".ts");
            a = new File(dir, "px_audio_" + System.nanoTime() + aExt);
            fetchTrack(audio, a, null);
        }
        return new File[]{v, a};
    }

    private static boolean pathEnds(String url, Track t, String... exts) {
        String first = t.segments.isEmpty() ? url : t.segments.get(0);
        String p = PxMedia.path(first);
        for (String e : exts) if (p.endsWith(e)) return true;
        return false;
    }
}
