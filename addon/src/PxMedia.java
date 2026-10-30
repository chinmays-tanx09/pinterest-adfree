package app.hushpinterest.extension.pinterest.actions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paradox download add-on (Pinterest Ad-Free v1.1). Finds every piece of media a pin carries:
 * the best MP4 or the HLS playlist of a video, the original of an image, every page of an idea
 * pin and every slot of a carousel. Pure Java (plus org.json), so it is unit-tested on a JVM.
 */
public final class PxMedia {
    private PxMedia() {}

    public static final class Item {
        public final String url;
        public final boolean video;
        public final boolean hls;
        /** An image size standing in for the original; the saver asks the media host for the original first. */
        public final boolean standIn;

        Item(String url, boolean video, boolean hls, boolean standIn) {
            this.url = url; this.video = video; this.hls = hls; this.standIn = standIn;
        }

        @Override public String toString() {
            return (video ? (hls ? "HLS " : "MP4 ") : (standIn ? "IMG~ " : "IMG ")) + url;
        }
    }

    // ---------------------------------------------------------------- model reading

    private static final Map<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();

    static Map<String, Field> fieldsOf(Class<?> type) {
        Map<String, Field> known = FIELDS.get(type);
        if (known != null) return known;
        Map<String, Field> found = new HashMap<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            Field[] declared;
            try { declared = at.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : declared) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String name = jsonName(f);
                if (name == null || found.containsKey(name)) continue;
                try { f.setAccessible(true); found.put(name, f); } catch (Throwable ignored) { }
            }
        }
        FIELDS.put(type, found);
        return found;
    }

    static String jsonName(Field field) {
        Annotation[] annotations;
        try { annotations = field.getDeclaredAnnotations(); } catch (Throwable t) { return null; }
        for (Annotation a : annotations) {
            try {
                Method value = a.annotationType().getDeclaredMethod("value");
                if (value.getReturnType() != String.class) continue;
                Object name = value.invoke(a);
                if (name instanceof String && !((String) name).isEmpty()) return (String) name;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    public static Object field(Object model, String name) {
        if (model == null) return null;
        if (model instanceof JSONObject) {
            Object v = ((JSONObject) model).opt(name);
            return v == JSONObject.NULL ? null : v;
        }
        if (model instanceof Map<?, ?>) return ((Map<?, ?>) model).get(name);
        if (model instanceof String || model instanceof Number || model instanceof Boolean) return null;
        Field f = fieldsOf(model.getClass()).get(name);
        if (f == null) return null;
        try { return f.get(model); } catch (Throwable t) { return null; }
    }

    static List<Object> list(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof List<?>) out.addAll((List<?>) value);
        else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) { Object v = a.opt(i); if (v != null && v != JSONObject.NULL) out.add(v); }
        }
        return out;
    }

    static Map<String, Object> map(Object value) {
        Map<String, Object> out = new java.util.TreeMap<>();
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) if (e.getKey() instanceof String && e.getValue() != null) out.put((String) e.getKey(), e.getValue());
        } else if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); Object v = o.opt(k); if (v != null && v != JSONObject.NULL) out.put(k, v); }
        }
        return out;
    }

    static String str(Object value) { return value instanceof String ? (String) value : null; }

    static double num(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) try { return Double.parseDouble((String) value); } catch (NumberFormatException ignored) { }
        return 0;
    }

    /** True for a pin model: it has an id and both media fields Pinterest declares on every pin. */
    public static boolean isPin(Object model) {
        if (model == null) return false;
        if (model instanceof JSONObject) return ((JSONObject) model).has("id") && ((JSONObject) model).has("images");
        if (model instanceof Map || model instanceof String || model.getClass().isPrimitive()) return false;
        String n = model.getClass().getName();
        if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.")) return false;
        Map<String, Field> f = fieldsOf(model.getClass());
        if (!(f.containsKey("id") && f.containsKey("images") && f.containsKey("videos"))) return false;
        Object id = field(model, "id");
        return id instanceof String && ((String) id).matches("[0-9]{1,30}");
    }

    public static String id(Object pin) {
        Object id = field(pin, "id");
        return id instanceof String && ((String) id).matches("[0-9]{1,30}") ? (String) id : null;
    }

    // ---------------------------------------------------------------- selection

    static boolean pinimg(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        int end = url.indexOf('/', 8);
        String host = (end < 0 ? url.substring(8) : url.substring(8, end)).toLowerCase(Locale.ROOT);
        return host.equals("pinimg.com") || host.endsWith(".pinimg.com");
    }

    static String path(String url) {
        int q = url.indexOf('?');
        return (q < 0 ? url : url.substring(0, q)).toLowerCase(Locale.ROOT);
    }

    private static final Pattern TIER = Pattern.compile("_t(\\d+)\\.mp4$");

    /** Quality rank among MP4s of equal size: Pinterest's 720p/1080p encodes beat the expMp4 tiers, higher tiers beat lower. */
    static int rank(String url) {
        String p = path(url);
        if (p.contains("/expmp4/")) {
            Matcher m = TIER.matcher(p);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
        if (p.contains("/1080p/")) return 200;
        if (p.contains("/720p/")) return 150;
        return 100;
    }

    /** The best playable video in a video_list: the largest MP4 (by its quality rank among equals), else its HLS playlist. */
    public static Item bestVideo(Object videoList) {
        String bestMp4 = null, hls = null;
        double bestArea = -1; int bestRank = -1; double hlsArea = -1;
        for (Map.Entry<String, Object> e : map(videoList).entrySet()) {
            Object v = e.getValue();
            String url = str(field(v, "url"));
            if (!pinimg(url)) continue;
            double area = num(field(v, "width")) * num(field(v, "height"));
            String p = path(url);
            if (p.endsWith(".mp4")) {
                int r = rank(url);
                if (area > bestArea || (area == bestArea && r > bestRank)) { bestMp4 = url; bestArea = area; bestRank = r; }
            } else if (p.endsWith(".m3u8")) {
                if (hls == null || area > hlsArea) { hls = url; hlsArea = area; }
            }
        }
        if (bestMp4 != null) return new Item(bestMp4, true, false, false);
        if (hls != null) return new Item(hls, true, true, false);
        return null;
    }

    private static final Pattern SIZE_KEY = Pattern.compile("[1-9][0-9]{0,4}x");

    /** The original image (orig / originals), else the widest uncropped size as a stand-in. */
    public static Item bestImage(Object images) {
        Map<String, Object> all = map(images);
        for (String key : new String[]{"orig", "originals"}) {
            String url = str(field(all.get(key), "url"));
            if (pinimg(url)) return new Item(url, false, false, false);
        }
        String best = null; int widest = -1;
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (!SIZE_KEY.matcher(e.getKey()).matches()) continue;
            String url = str(field(e.getValue(), "url"));
            if (!pinimg(url)) continue;
            int w = Integer.parseInt(e.getKey().substring(0, e.getKey().length() - 1));
            if (w > widest) { widest = w; best = url; }
        }
        if (best != null) return new Item(best, false, false, !path(best).contains("/originals/"));
        return null;
    }

    /** One item for an idea-pin page: its video if it has one, else its picture. */
    static Item page(Object page) {
        Item video = bestVideo(field(field(page, "video"), "video_list"));
        Item image = null;
        for (Object block : list(field(page, "blocks"))) {
            if (video == null) video = bestVideo(field(field(block, "video"), "video_list"));
            if (image == null) image = bestImage(field(field(block, "image"), "images"));
        }
        if (video != null) return video;
        if (image == null) image = bestImage(field(field(page, "image"), "images"));
        if (image == null) image = bestImage(field(field(page, "image_adjusted"), "images"));
        return image;
    }

    /** Every piece of media the pin itself carries, in order. Empty when the model holds none. */
    public static List<Item> items(Object pin) {
        List<Item> out = new ArrayList<>();
        if (pin == null) return out;
        for (Object p : list(field(field(pin, "story_pin_data"), "pages"))) {
            Item it = page(p);
            if (it != null) out.add(it);
        }
        if (!out.isEmpty()) return out;
        for (Object slot : list(field(field(pin, "carousel_data"), "carousel_slots"))) {
            Item it = bestImage(field(slot, "images"));
            if (it != null) out.add(it);
        }
        if (out.size() > 1) return out;
        out.clear();
        Item video = bestVideo(field(field(pin, "videos"), "video_list"));
        if (video != null) { out.add(video); return out; }
        if (looksVideo(pin)) return out; // the still image is not the video the person asked for
        Item image = bestImage(field(pin, "images"));
        if (image != null) out.add(image);
        return out;
    }

    /** A video whose media the model did not bring: an idea pin with running time, or is_video. */
    public static boolean looksVideo(Object pin) {
        if (Boolean.TRUE.equals(field(pin, "is_video"))) return true;
        String d = str(field(field(pin, "story_pin_data"), "total_video_duration"));
        return d != null && d.matches(".*[1-9].*");
    }

    // ---------------------------------------------------------------- web fallback

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /** The pin's media as Pinterest's own web page describes it, for a pin whose app model lacks it. Worker threads only. */
    public static List<Item> webItems(String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.pinterest.com/pin/" + id + "/").openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        try {
            if (c.getResponseCode() != 200) return new ArrayList<>();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            }
            return itemsFromHtml(buf.toString("UTF-8"), id);
        } finally { c.disconnect(); }
    }

    /** Both page shapes Pinterest serves: the redux pin object, or the relay answer to its pin query. */
    public static List<Item> itemsFromHtml(String html, String id) {
        JSONObject pin = pinFromHtml(html, id);
        if (pin != null) {
            List<Item> fromVideos = videosIn(pin);
            if (!fromVideos.isEmpty()) return fromVideos;
            List<Item> items = items(pin);
            if (!items.isEmpty()) return items;
        }
        String marker = "__PWS_RELAY_REGISTER_COMPLETED_REQUEST__(";
        for (int at = html.indexOf(marker); at >= 0; at = html.indexOf(marker, at + 1)) {
            int end = html.indexOf("</script>", at);
            if (end < 0) break;
            String call = html.substring(at, end);
            if (!call.contains("v3GetPinQuery")) continue;
            int open = call.indexOf("\",");
            int brace = open < 0 ? -1 : call.indexOf('{', open);
            int close = call.lastIndexOf('}');
            if (brace < 0 || close < brace) continue;
            try {
                JSONObject root = new JSONObject(call.substring(brace, close + 1));
                JSONObject data = findKey(root, "v3GetPinQueryv2", 0);
                if (data != null && data.optJSONObject("data") != null) data = data.optJSONObject("data");
                if (data == null) continue;
                List<Item> videos = videosIn(data);
                if (!videos.isEmpty()) return videos;
                List<Item> images = new ArrayList<>();
                List<String> originals = new ArrayList<>();
                collect(data.opt("carouselData"), originals, 0);
                collect(data.opt("storyPinData"), originals, 0);
                for (String u : originals) if (path(u).contains("/originals/") && !path(u).contains("/videos/")) images.add(new Item(u, false, false, false));
                if (!images.isEmpty()) return images;
                String orig = str(field(data.optJSONObject("images_orig"), "url"));
                if (pinimg(orig)) { images.add(new Item(orig, false, false, false)); return images; }
            } catch (Exception ignored) { }
        }
        return new ArrayList<>();
    }

    private static JSONObject findKey(Object node, String key, int depth) {
        if (depth > 8) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.optJSONObject(key) != null) return o.optJSONObject(key);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { JSONObject f = findKey(o.opt(it.next()), key, depth + 1); if (f != null) return f; }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) { JSONObject f = findKey(a.opt(i), key, depth + 1); if (f != null) return f; }
        }
        return null;
    }

    static void collect(Object node, List<String> out, int depth) {
        if (node == null || depth > 20) return;
        if (node instanceof String) { if (((String) node).startsWith("https://")) out.add((String) node); }
        else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) collect(o.opt(it.next()), out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, depth + 1);
        }
    }

    private static final Pattern VIDEO_URL = Pattern.compile("^https://v\\d*\\.pinimg\\.com/videos/.+/([0-9a-f]{16,64})(_t\\d+|_\\d+w|_audio)?\\.(mp4|m3u8)$");

    /** Every video in a pin's JSON, one per video file in order of appearance: its best MP4, else its HLS playlist. */
    public static List<Item> videosIn(Object json) {
        List<String> urls = new ArrayList<>();
        collect(json, urls, 0);
        java.util.LinkedHashMap<String, String[]> byHash = new java.util.LinkedHashMap<>();
        for (String u : urls) {
            Matcher m = VIDEO_URL.matcher(u.split("\\?")[0]);
            if (!m.find()) continue;
            String hash = m.group(1);
            String[] best = byHash.get(hash);
            if (best == null) { best = new String[2]; byHash.put(hash, best); }
            if (m.group(3).equals("mp4")) {
                if (best[0] == null || rank(u) > rank(best[0])) best[0] = u;
            } else if (m.group(2) == null) {
                best[1] = u;
            }
        }
        List<Item> out = new ArrayList<>();
        for (String[] b : byHash.values()) {
            if (b[0] != null) out.add(new Item(b[0], true, false, false));
            else if (b[1] != null) out.add(new Item(b[1], true, true, false));
        }
        return out;
    }

    static JSONObject pinFromHtml(String html, String id) {
        for (String script : new String[]{"__PWS_INITIAL_PROPS__", "__PWS_DATA__"}) {
            int at = html.indexOf("id=\"" + script + "\"");
            if (at < 0) continue;
            int start = html.indexOf('>', at) + 1, end = html.indexOf("</script>", start);
            if (start <= 0 || end < 0) continue;
            try {
                JSONObject root = new JSONObject(html.substring(start, end));
                JSONObject found = find(root, id, 0);
                if (found != null) return found;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static JSONObject find(Object node, String id, int depth) {
        if (depth > 12) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (id.equals(o.optString("id")) && o.has("images") && (o.has("videos") || o.has("story_pin_data"))) return o;
            JSONObject pins = o.optJSONObject("pins");
            if (pins != null && pins.optJSONObject(id) != null) return pins.optJSONObject(id);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                JSONObject f = find(o.opt(it.next()), id, depth + 1);
                if (f != null) return f;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) { JSONObject f = find(a.opt(i), id, depth + 1); if (f != null) return f; }
        }
        return null;
    }

    /** The pin's media, asking Pinterest's web page when the app model lacks it. Worker threads only. */
    public static List<Item> resolveAll(Object pin) {
        List<Item> items = items(pin);
        boolean needWeb = items.isEmpty() || (looksVideo(pin) && !hasVideo(items));
        if (needWeb) {
            String id = pin instanceof JSONObject ? ((JSONObject) pin).optString("id") : id(pin);
            if (id != null) {
                try {
                    List<Item> fromWeb = webItems(id);
                    if (!fromWeb.isEmpty()) return fromWeb;
                } catch (Exception ignored) { }
            }
        }
        return items;
    }

    static boolean hasVideo(List<Item> items) {
        for (Item i : items) if (i.video) return true;
        return false;
    }

    // ---------------------------------------------------------------- derived addresses

    private static final Pattern HLS_PATH = Pattern.compile("^(https://[^/]+/videos/[^/]+/)hls/(.+?)\\.m3u8$");

    /** Direct MP4 addresses Pinterest usually keeps beside an HLS playlist, best first. */
    public static List<String> mp4Beside(String hls) {
        List<String> out = new ArrayList<>();
        Matcher m = HLS_PATH.matcher(hls == null ? "" : hls.split("\\?")[0]);
        if (!m.find()) return out;
        String base = m.group(1), rest = m.group(2);
        if (rest.matches(".*_(\\d+w|audio)$")) rest = rest.replaceAll("_(\\d+w|audio)$", "");
        out.add(base + "720p/" + rest + ".mp4");
        for (int t = 4; t >= 1; t--) out.add(base + "expMp4/" + rest + "_t" + t + ".mp4");
        return out;
    }

    private static final Pattern SIZE_DIR = Pattern.compile("^(https://[^/]+/)(\\d+x\\d*|[a-z0-9_]+x)/(.+)\\.(jpg|jpeg|png|gif|webp)$", Pattern.CASE_INSENSITIVE);

    /** Where the media host keeps the original behind a supplied size, one address per likely type. */
    public static List<String> originalsOf(String url) {
        List<String> out = new ArrayList<>();
        Matcher m = SIZE_DIR.matcher(url == null ? "" : url.split("\\?")[0]);
        if (!m.find()) return out;
        String ext = m.group(4).toLowerCase(Locale.ROOT);
        String stem = m.group(1) + "originals/" + m.group(3) + ".";
        out.add(stem + ext);
        for (String e : new String[]{"jpg", "png", "gif", "webp"}) if (!e.equals(ext)) out.add(stem + e);
        return out;
    }

    // ---------------------------------------------------------------- Hush menu bridge

    /**
     * Called after HushPinterest resolves a pin. A video it refuses (HLS-only or an idea pin) gets a
     * video source so its menu offers Download; this add-on's saver then does the work.
     */
    public static Object fixResolution(Object pin, Object resolution) {
        try {
            if (resolution == null || pin == null) return resolution;
            Class<?> rc = resolution.getClass();
            Field sourceF = rc.getDeclaredField("source"); sourceF.setAccessible(true);
            if (sourceF.get(resolution) != null) return resolution;
            Field refusalF = rc.getDeclaredField("refusal"); refusalF.setAccessible(true);
            Object refusal = refusalF.get(resolution);
            String name = refusal == null ? "" : ((Enum<?>) refusal).name();
            if (!name.equals("MP4_MISSING") && !name.equals("ADAPTIVE_VIDEO")) return resolution;
            List<Item> items = items(pin);
            String url = null;
            for (Item i : items) if (i.video) { url = i.url; break; }
            if (url == null) {
                String id = id(pin);
                if (id == null) return resolution;
                url = PENDING_PREFIX + id + ".mp4";
            }
            Field idF = rc.getDeclaredField("id"); idF.setAccessible(true);
            Class<?> sourceClass = Class.forName("app.hushpinterest.extension.pinterest.actions.PinMedia$Source");
            java.lang.reflect.Constructor<?> sc = sourceClass.getDeclaredConstructor(String.class, String.class, String.class);
            sc.setAccessible(true);
            Object source = sc.newInstance(url, "video/mp4", ".mp4");
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> c : rc.getDeclaredConstructors()) if (c.getParameterTypes().length == 5) ctor = c;
            if (ctor == null) return resolution;
            ctor.setAccessible(true);
            return ctor.newInstance(idF.get(resolution), source, null, null, null);
        } catch (Throwable t) {
            return resolution;
        }
    }

    /** A placeholder on the media host for an idea-pin video the app model didn't carry. */
    public static final String PENDING_PREFIX = "https://i.pinimg.com/paradox-pending/";

    /**
     * Called by HushPinterest's Downloads-queue builder (batch downloads) for a source it got from
     * {@link #fixResolution}: a playlist or placeholder becomes the direct MP4 the media host keeps.
     * Worker threads only.
     */
    public static String directForQueue(String url) {
        try {
            if (url == null) return null;
            String p = path(url);
            if (url.startsWith(PENDING_PREFIX)) {
                String id = url.substring(PENDING_PREFIX.length()).replace(".mp4", "");
                for (Item i : webItems(id)) if (i.video) { url = i.url; p = path(url); break; }
            }
            if (p.endsWith(".m3u8")) {
                for (String c : mp4Beside(url)) if (PxNet.present(c, "video/")) return c;
            }
        } catch (Throwable ignored) { }
        return url;
    }
}
