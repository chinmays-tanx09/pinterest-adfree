import app.hushpinterest.extension.pinterest.actions.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.json.*;

public class PxTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        String dir = a[0];
        // 1. redux-format page (story pin, one video page)
        String redux = new String(Files.readAllBytes(Paths.get(dir, "sample_redux.html")), "UTF-8");
        List<PxMedia.Item> r = PxMedia.itemsFromHtml(redux, "69594756734161271");
        System.out.println("redux -> " + r);
        check(r.size() == 1 && r.get(0).video && r.get(0).url.contains("/720p/"), "redux page picks the 720p MP4 (best bitrate) over expMp4 tiers");
        // 2. same pin, relay-format page (HLS only in the JSON)
        String relay = new String(Files.readAllBytes(Paths.get(dir, "sample_relay.html")), "UTF-8");
        List<PxMedia.Item> rr = PxMedia.itemsFromHtml(relay, "69594756734161271");
        System.out.println("relay -> " + rr);
        check(rr.size() >= 1 && rr.get(0).video, "relay page finds the video");
        // 3. app-model-like object: the redux pin JSON goes through items() (pages -> blocks -> video)
        int s = redux.indexOf("id=\"__PWS_INITIAL_PROPS__\""); s = redux.indexOf('>', s) + 1;
        JSONObject root = new JSONObject(redux.substring(s, redux.indexOf("</script>", s)));
        JSONObject pin = root.getJSONObject("initialReduxState").getJSONObject("pins").getJSONObject("69594756734161271");
        List<PxMedia.Item> m = PxMedia.items(pin);
        System.out.println("model items -> " + m);
        check(m.size() == 1 && m.get(0).url.contains("/720p/"), "story pin pages/blocks resolve to best MP4");
        // 4. HLS-only list
        JSONObject vl = new JSONObject().put("V_HLSV3_MOBILE", new JSONObject().put("url", "https://v1.pinimg.com/videos/mc/hls/0f/c5/c8/0fc5c8f84c371fcbf3773678b1f39eaf.m3u8").put("width",1080).put("height",1920));
        PxMedia.Item h = PxMedia.bestVideo(vl);
        check(h != null && h.hls, "HLS-only video_list gives an HLS item");
        List<String> beside = PxMedia.mp4Beside(h.url);
        System.out.println("beside -> " + beside);
        String found = null;
        for (String c : beside) if (PxNet.present(c, "video/")) { found = c; break; }
        check(found != null && found.contains("/720p/"), "direct 720p MP4 found beside the playlist: " + found);
        // 5. HLS download path (used when no MP4 exists)
        File out = new File(a[1]); out.mkdirs();
        long t0 = System.currentTimeMillis();
        File[] parts = PxNet.fetchHls(h.url, out, (d, t) -> {});
        System.out.println("hls parts -> " + parts[0] + " (" + parts[0].length() + "), " + parts[1] + " (" + (parts[1] == null ? 0 : parts[1].length()) + ") in " + (System.currentTimeMillis() - t0) + " ms");
        check(parts[0].length() > 1000000 && parts[1] != null && parts[1].length() > 100000, "HLS video+audio renditions downloaded");
        // 6. images: 736x stand-in -> originals
        List<String> o = PxMedia.originalsOf("https://i.pinimg.com/736x/3d/3c/0d/3d3c0da9bf2070a3ff98070c80f0a177.jpg");
        System.out.println("originals -> " + o);
        check(PxNet.present(o.get(0), "image/"), "original image exists behind the 736x size");
        JSONObject imgs = new JSONObject().put("736x", new JSONObject().put("url","https://i.pinimg.com/736x/3d/3c/0d/3d3c0da9bf2070a3ff98070c80f0a177.jpg"))
            .put("236x", new JSONObject().put("url","https://i.pinimg.com/236x/3d/3c/0d/3d3c0da9bf2070a3ff98070c80f0a177.jpg"))
            .put("150x150", new JSONObject().put("url","https://i.pinimg.com/150x150/3d/3c/0d/3d3c0da9bf2070a3ff98070c80f0a177.jpg"));
        PxMedia.Item bi = PxMedia.bestImage(imgs);
        check(bi.standIn && bi.url.contains("/736x/"), "widest uncropped size chosen as stand-in");
        // 7. carousel
        JSONObject car = new JSONObject().put("id","1").put("images", imgs).put("videos", JSONObject.NULL)
          .put("carousel_data", new JSONObject().put("carousel_slots", new JSONArray()
             .put(new JSONObject().put("images", new JSONObject().put("orig", new JSONObject().put("url","https://i.pinimg.com/originals/aa/bb/cc/1.jpg"))))
             .put(new JSONObject().put("images", new JSONObject().put("orig", new JSONObject().put("url","https://i.pinimg.com/originals/aa/bb/cc/2.png"))))));
        check(PxMedia.items(car).size() == 2, "carousel gives every slot");
        // 8. GIF
        JSONObject gif = new JSONObject().put("id","2").put("images", new JSONObject().put("orig", new JSONObject().put("url","https://i.pinimg.com/originals/aa/bb/cc/x.gif"))).put("videos", JSONObject.NULL);
        check(PxMedia.items(gif).get(0).url.endsWith(".gif"), "GIF original kept as GIF");
        // 9. other live pin page (if present)
        String other = new String(Files.readAllBytes(Paths.get(dir, "sample_4644.html")), "UTF-8");
        System.out.println("4644 -> " + PxMedia.itemsFromHtml(other, "4644405861793493"));
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    }
}
