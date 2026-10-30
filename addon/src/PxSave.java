package app.hushpinterest.extension.pinterest.actions;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paradox download add-on: saves every item of a pin straight into the gallery
 * (Pictures/Pinterest, Movies/Pinterest) with a progress notification, and copies a pin's
 * picture or video to the clipboard.
 */
public final class PxSave {
    private PxSave() {}

    static final String CHANNEL = "paradox_pin_downloads";
    static final String PICTURES = Environment.DIRECTORY_PICTURES + "/Pinterest";
    static final String MOVIES = Environment.DIRECTORY_MOVIES + "/Pinterest";
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "paradox-pin-save");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger NOTIFY = new AtomicInteger(0x50A0);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void toast(Context c, String text) {
        MAIN.post(() -> { try { Toast.makeText(c, text, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { } });
    }

    // ------------------------------------------------------------- entry points (smali hooks)

    /** HushPinterest's PinDownloads.start(pin, context): every single-pin Download goes through here. */
    public static boolean download(Object pin, Context context) {
        if (pin == null || context == null || PxMedia.id(pin) == null) return false;
        Context app = context.getApplicationContext();
        toast(app, "Downloading…");
        WORKER.execute(() -> save(app, pin, false));
        return true;
    }

    /** HushPinterest's batch PinDownloads.start(pin, context, listener): only pins its own path can't save. */
    public static boolean downloadBatch(Object pin, Context context, Object listener) {
        try {
            if (pin == null || context == null || PxMedia.id(pin) == null) return false;
            List<PxMedia.Item> items = PxMedia.items(pin);
            boolean special = items.isEmpty() || items.size() > 1 || items.get(0).hls;
            if (!special) return false;
            Context app = context.getApplicationContext();
            WORKER.execute(() -> save(app, pin, true));
            if (listener instanceof java.util.function.Consumer) {
                Class<?> result = Class.forName("app.hushpinterest.extension.pinterest.actions.PinDownloads$Result");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object queued = Enum.valueOf((Class) result, "QUEUED_UNTRACKED");
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<Object> c = (java.util.function.Consumer<Object>) listener;
                MAIN.post(() -> c.accept(queued));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** HushPinterest's Downloads-queue builder (batch downloads): a playlist becomes the MP4 beside it. */
    public static Object queueSource(Object source) {
        try {
            if (source == null) return null;
            java.lang.reflect.Field f = source.getClass().getDeclaredField("url");
            f.setAccessible(true);
            String url = (String) f.get(source);
            if (url == null) return source;
            if (!PxMedia.path(url).endsWith(".m3u8") && !url.startsWith(PxMedia.PENDING_PREFIX)) return source;
            String direct = PxMedia.directForQueue(url);
            if (direct == null || direct.equals(url)) return source;
            java.lang.reflect.Constructor<?> c = source.getClass().getDeclaredConstructor(String.class, String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(direct, "video/mp4", ".mp4");
        } catch (Throwable t) {
            return source;
        }
    }

    // ------------------------------------------------------------- saving

    static final class Saved { Uri uri; String mime; }

    static List<Saved> save(Context app, Object pin, boolean quiet) {
        List<Saved> saved = new ArrayList<>();
        int id = NOTIFY.incrementAndGet();
        Notifier n = new Notifier(app, id);
        try {
            List<PxMedia.Item> items = PxMedia.resolveAll(pin);
            if (items.isEmpty()) {
                n.cancel();
                if (!quiet) toast(app, "Couldn't find this pin's photo or video.");
                return saved;
            }
            String pinId = PxMedia.id(pin);
            int failed = 0;
            for (int i = 0; i < items.size(); i++) {
                PxMedia.Item item = items.get(i);
                String label = items.size() > 1 ? "Saving pin (" + (i + 1) + "/" + items.size() + ")" : "Saving pin";
                n.progress(label, 0, 0);
                try {
                    String name = "Pinterest_" + pinId + (items.size() > 1 ? "_" + (i + 1) : "") + "_" + (System.currentTimeMillis() % 100000);
                    saved.add(saveItem(app, item, name, (done, total) -> n.progress(label, done, total)));
                } catch (Throwable t) {
                    failed++;
                }
            }
            int ok = saved.size();
            if (ok == 0) {
                n.done("Download failed", "Couldn't save this pin. Check your connection.");
                if (!quiet) toast(app, "Couldn't save this pin.");
            } else {
                String text = ok == 1 ? "Saved to gallery" : "Saved " + ok + " items to gallery";
                if (failed > 0) text += " (" + failed + " failed)";
                n.done(text, saved.get(0).mime.startsWith("video") ? MOVIES : PICTURES);
                if (!quiet) toast(app, text);
            }
        } catch (Throwable t) {
            n.done("Download failed", String.valueOf(t.getMessage()));
            if (!quiet) toast(app, "Couldn't save this pin.");
        }
        return saved;
    }

    static Saved saveItem(Context app, PxMedia.Item item, String name, PxNet.Progress p) throws Exception {
        if (!item.video) {
            String url = item.url;
            if (item.standIn) for (String o : PxMedia.originalsOf(url)) if (PxNet.present(o, "image/")) { url = o; break; }
            return direct(app, url, name, false, p);
        }
        if (!item.hls) return direct(app, item.url, name, true, p);
        for (String mp4 : PxMedia.mp4Beside(item.url)) if (PxNet.present(mp4, "video/")) return direct(app, mp4, name, true, p);
        return hls(app, item.url, name, p);
    }

    static Uri insert(ContentResolver r, String name, String mime, boolean video) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, video ? MOVIES : PICTURES);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return r.insert(collection, v);
    }

    static void publish(ContentResolver r, Uri uri) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        r.update(uri, v, null, null);
    }

    static String ext(String mime, String url) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("png")) return ".png";
        if (m.contains("gif")) return ".gif";
        if (m.contains("webp")) return ".webp";
        if (m.contains("jpeg") || m.contains("jpg")) return ".jpg";
        if (m.startsWith("video")) return ".mp4";
        String p = PxMedia.path(url);
        int dot = p.lastIndexOf('.');
        return dot > p.lastIndexOf('/') ? p.substring(dot) : ".jpg";
    }

    static String mimeFor(String url, boolean video) {
        if (video) return "video/mp4";
        String p = PxMedia.path(url);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    static Saved direct(Context app, String url, String name, boolean video, PxNet.Progress p) throws IOException {
        ContentResolver r = app.getContentResolver();
        String mime = mimeFor(url, video);
        Uri uri = insert(r, name + ext(mime, url), mime, video);
        if (uri == null) throw new IOException("Gallery refused the file");
        try (OutputStream os = r.openOutputStream(uri, "w")) {
            if (os == null) throw new IOException("No output");
            PxNet.fetch(url, null, os, p, 0, -1);
        } catch (IOException | RuntimeException e) {
            r.delete(uri, null, null);
            throw e;
        }
        publish(r, uri);
        Saved s = new Saved(); s.uri = uri; s.mime = mime;
        return s;
    }

    static Saved hls(Context app, String url, String name, PxNet.Progress p) throws Exception {
        File dir = new File(app.getCacheDir(), "paradox_hls");
        dir.mkdirs();
        File[] parts = null;
        File out = new File(dir, "px_out_" + System.nanoTime() + ".mp4");
        try {
            parts = PxNet.fetchHls(url, dir, p);
            mux(parts[0], parts[1], out);
            ContentResolver r = app.getContentResolver();
            Uri uri = insert(r, name + ".mp4", "video/mp4", true);
            if (uri == null) throw new IOException("Gallery refused the file");
            try (OutputStream os = r.openOutputStream(uri, "w"); InputStream in = new FileInputStream(out)) {
                if (os == null) throw new IOException("No output");
                PxNet.copy(in, os, null, 0, -1);
            } catch (IOException | RuntimeException e) {
                r.delete(uri, null, null);
                throw e;
            }
            publish(r, uri);
            Saved s = new Saved(); s.uri = uri; s.mime = "video/mp4";
            return s;
        } finally {
            if (parts != null) for (File f : parts) if (f != null) f.delete();
            out.delete();
        }
    }

    /** Remuxes the downloaded rendition(s) into one MP4 without re-encoding. */
    static void mux(File videoFile, File audioFile, File out) throws IOException {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = null;
        MediaMuxer muxer = null;
        try {
            ve.setDataSource(videoFile.getPath());
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] veMap = new int[ve.getTrackCount()];
            java.util.Arrays.fill(veMap, -1);
            boolean haveVideo = false, haveAudio = false;
            for (int i = 0; i < ve.getTrackCount(); i++) {
                MediaFormat f = ve.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                boolean v = mime.startsWith("video/"), a = mime.startsWith("audio/");
                if ((v && !haveVideo) || (a && !haveAudio && audioFile == null)) {
                    if (v && f.containsKey("rotation-degrees")) muxer.setOrientationHint(f.getInteger("rotation-degrees"));
                    ve.selectTrack(i);
                    veMap[i] = muxer.addTrack(f);
                    haveVideo |= v; haveAudio |= a;
                }
            }
            if (!haveVideo) throw new IOException("No video track");
            int[] aeMap = new int[0];
            if (audioFile != null) {
                ae = new MediaExtractor();
                ae.setDataSource(audioFile.getPath());
                aeMap = new int[ae.getTrackCount()];
                java.util.Arrays.fill(aeMap, -1);
                for (int i = 0; i < ae.getTrackCount(); i++) {
                    MediaFormat f = ae.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { ae.selectTrack(i); aeMap[i] = muxer.addTrack(f); break; }
                }
            }
            muxer.start();
            ByteBuffer buf = ByteBuffer.allocateDirect(8 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean vDone = false, aDone = ae == null;
            while (!vDone || !aDone) {
                MediaExtractor from;
                int[] map;
                if (aDone || (!vDone && ve.getSampleTime() <= ae.getSampleTime())) { from = ve; map = veMap; }
                else { from = ae; map = aeMap; }
                int track = from.getSampleTrackIndex();
                if (track < 0) { if (from == ve) vDone = true; else aDone = true; continue; }
                buf.clear();
                int size = from.readSampleData(buf, 0);
                if (size < 0) { if (from == ve) vDone = true; else aDone = true; continue; }
                if (track < map.length && map[track] >= 0) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = from.getSampleTime();
                    int flags = from.getSampleFlags();
                    info.flags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(map[track], buf, info);
                }
                from.advance();
            }
            muxer.stop();
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
            ve.release();
            if (ae != null) ae.release();
        }
    }

    // ------------------------------------------------------------- copy

    /** Copies the pin's picture (via HushPinterest's Copy image) or its video to the clipboard. */
    public static void copy(Object pin, Context context) {
        if (pin == null || context == null) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                List<PxMedia.Item> items = PxMedia.resolveAll(pin);
                if (items.isEmpty()) { toast(app, "Couldn't find this pin's photo or video."); return; }
                PxMedia.Item first = items.get(0);
                if (!first.video && hushCopy(pin, context)) return;
                toast(app, first.video ? "Copying video…" : "Copying…");
                String name = "Pinterest_" + PxMedia.id(pin) + "_" + (System.currentTimeMillis() % 100000);
                Saved s = saveItem(app, first, name, null);
                Uri clip = first.video ? wrap(app, s.uri, s.mime) : s.uri;
                ClipData data = new ClipData(new ClipDescription("Pinterest " + (first.video ? "video" : "image"), new String[]{s.mime}), new ClipData.Item(clip));
                MAIN.post(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(data);
                        Toast.makeText(app, first.video ? "Copied video (also saved to gallery)" : "Copied", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(app, "Couldn't copy this pin.", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                toast(app, "Couldn't copy this pin.");
            }
        });
    }

    /** HushPinterest's Copy image: the original picture, served from its own cache through its provider. */
    static boolean hushCopy(Object pin, Context context) {
        final boolean[] result = {false};
        final Object lock = new Object();
        MAIN.post(() -> {
            try {
                Class<?> c = Class.forName("app.hushpinterest.extension.pinterest.actions.CopyImage");
                java.lang.reflect.Method m = c.getDeclaredMethod("start", Object.class, Context.class);
                m.setAccessible(true);
                result[0] = Boolean.TRUE.equals(m.invoke(null, pin, context));
            } catch (Throwable ignored) { }
            synchronized (lock) { lock.notifyAll(); }
        });
        synchronized (lock) { try { lock.wait(3000); } catch (InterruptedException ignored) { } }
        return result[0];
    }

    static Uri wrap(Context app, Uri uri, String mime) {
        try {
            Class<?> c = Class.forName("app.hushpinterest.extension.pinterest.actions.SharePreviewProvider");
            java.lang.reflect.Method m = c.getDeclaredMethod("wrap", Context.class, Uri.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, app, uri, mime);
            return r instanceof Uri ? (Uri) r : uri;
        } catch (Throwable t) {
            return uri;
        }
    }

    // ------------------------------------------------------------- notification

    static final class Notifier {
        final Context app; final int id; final NotificationManager nm; long lastPost;
        Notifier(Context app, int id) {
            this.app = app; this.id = id;
            nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            try {
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL, "Pin downloads", NotificationManager.IMPORTANCE_LOW);
                    nm.createNotificationChannel(ch);
                }
            } catch (Throwable ignored) { }
        }
        void progress(String title, long done, long total) {
            long now = System.currentTimeMillis();
            if (done > 0 && now - lastPost < 400) return;
            lastPost = now;
            try {
                Notification.Builder b = new Notification.Builder(app, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true);
                if (total > 0) b.setProgress(1000, (int) Math.min(1000, done * 1000 / total), false)
                        .setContentText((done >> 20) + " / " + (total >> 20) + " MB");
                else if (total < 0) b.setProgress((int) -total, (int) done, false);
                else b.setProgress(0, 0, true).setContentText(done > 0 ? (done >> 20) + " MB" : "");
                if (nm != null) nm.notify(id, b.build());
            } catch (Throwable ignored) { }
        }
        void done(String title, String text) {
            try {
                Notification.Builder b = new Notification.Builder(app, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(title).setContentText(text).setAutoCancel(true);
                if (nm != null) nm.notify(id, b.build());
            } catch (Throwable ignored) { }
        }
        void cancel() { try { if (nm != null) nm.cancel(id); } catch (Throwable ignored) { } }
    }
}
