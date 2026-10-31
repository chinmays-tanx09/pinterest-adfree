package paradox.banner;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paradox "Update available" card.
 *
 * On app start (at most once every 12 hours) it reads the app's own GitHub releases list,
 * https://api.github.com/repos/chinmays-tanx09/REPO/releases, and picks the newest release that is
 * not a draft or pre-release, has an .apk asset and a "versionCode: N" line in its notes. If N is
 * higher than the installed versionCode it shows a card: Download (opens the APK link in the
 * browser), Later, Skip this version. No installing inside the app, no telemetry, no extra
 * permissions (INTERNET only), and any network or parse error is silent.
 */
public final class UpdateCheck {
    public static final String OWNER = "chinmays-tanx09";
    private static final String PREFS = "paradox_update";
    private static final long OK_INTERVAL = 12L * 60 * 60 * 1000;   // after a successful check
    private static final long RETRY_INTERVAL = 2L * 60 * 60 * 1000; // after a failed one (offline etc.)
    private static boolean started;

    private UpdateCheck() {}

    /** package name -> { GitHub repo, app name } */
    static String[] lookup(String pkg) {
        if (pkg == null) return null;
        if (pkg.equals("bot.paradox.wifi")) return new String[]{"college-wifi", "College WiFi"};
        if (pkg.equals("app.paradox.youtube")) return new String[]{"youtube-ultra", "YouTube Ultra"};
        if (pkg.equals("com.pinterest")) return new String[]{"pinterest-adfree", "Pinterest Ad-Free"};
        if (pkg.equals("com.aipro.select")) return new String[]{"ai-pro", "AI Pro"};
        if (pkg.equals("bot.paradox.enhance")) return new String[]{"paradox-enhance", "Paradox Enhance"};
        if (pkg.equals("bot.paradox.downloader")) return new String[]{"paradox-downloader", "Paradox Downloader"};
        if (pkg.equals("dev.jason.com.google.android.inputmethod.latin")) return new String[]{"gboardwu", "GboardWu"};
        if (pkg.equals("com.JindoBlu.OfflineGames.paradox")) return new String[]{"offline-games", "Offline Games"};
        return null;
    }

    /** One release the checker may offer. */
    static final class Found {
        long code;
        String name = "", notes = "", url = "";
    }

    public static void start(Activity a) {
        try {
            if (a == null || started) return;
            final Context app = a.getApplicationContext();
            final String[] info = lookup(app.getPackageName());
            if (info == null) return;
            started = true;
            final SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long next = p.getLong("next", 0);
            // "next - now <= OK_INTERVAL" also recovers from a clock that was set far ahead.
            if (now < next && next - now <= OK_INTERVAL) return;
            p.edit().putLong("next", now + RETRY_INTERVAL).apply();
            final long installed = installedCode(app);
            if (installed <= 0) return;
            final WeakReference<Activity> ref = new WeakReference<Activity>(a);
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String json = fetch("https://api.github.com/repos/" + OWNER + "/" + info[0] + "/releases?per_page=15");
                        if (json == null) return;
                        final Found f = pick(json);
                        p.edit().putLong("next", System.currentTimeMillis() + OK_INTERVAL).apply();
                        if (f == null || f.code <= installed || f.code == p.getLong("skip", -1)) return;
                        if (f.name.length() == 0) f.name = info[1];
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() { offer(ref, p, f); }
                        });
                    } catch (Throwable ignored) {}
                }
            }, "paradox-update");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    static String fetch(String u) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(6000);
            h.setReadTimeout(8000);
            h.setUseCaches(false);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "Paradox-UpdateCheck");
            if (h.getResponseCode() != 200) return null;
            InputStream in = h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 2 * 1024 * 1024) return null; // a releases list is far smaller
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (h != null) h.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static final Pattern CODE = Pattern.compile("(?i)versionCode\\s*[:=]\\s*(\\d{1,12})");

    /** Highest-versionCode release that is published, stable, has an .apk and a versionCode line. */
    static Found pick(String json) {
        Found best = null;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject r = arr.getJSONObject(i);
                    if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) continue;
                    String body = r.optString("body", "");
                    Matcher m = CODE.matcher(body);
                    if (!m.find()) continue;
                    long code = Long.parseLong(m.group(1));
                    String url = apkUrl(r.optJSONArray("assets"));
                    if (url == null) continue;
                    if (best != null && code <= best.code) continue;
                    Found f = new Found();
                    f.code = code;
                    f.url = url;
                    String name = r.optString("name", "");
                    if (name == null || name.equals("null")) name = "";
                    f.name = name.trim().length() > 0 ? name.trim() : r.optString("tag_name", "");
                    f.notes = notes(body, f.name);
                    best = f;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            return null;
        }
        return best;
    }

    /** The app's .apk (not a helper such as MicroG); only github.com links are ever opened. */
    static String apkUrl(JSONArray assets) {
        if (assets == null) return null;
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject x = assets.optJSONObject(i);
            if (x == null) continue;
            String n = x.optString("name", "").toLowerCase();
            String u = x.optString("browser_download_url", "");
            if (!n.endsWith(".apk") || !u.startsWith("https://github.com/")) continue;
            if (n.contains("microg") || n.contains("gmscore")) { if (fallback == null) fallback = u; continue; }
            return u;
        }
        return null; // a release whose only APK is a helper is not this app's release
    }

    /** First lines of the notes, without markdown, versionCode/sha256 lines or the title. */
    static String notes(String body, String title) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String raw : body.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.length() == 0) continue;
            String low = l.toLowerCase();
            if (low.contains("versioncode") || low.contains("sha-256") || low.contains("sha256")) continue;
            l = l.replaceAll("^#+\\s*", "").replaceAll("^[-*•]\\s+", "• ").replace("**", "").replace("`", "").trim();
            l = l.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
            if (l.length() == 0 || l.equalsIgnoreCase(title)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
            if (++lines >= 3 || sb.length() >= 220) break;
        }
        String s = sb.toString();
        if (s.length() > 240) s = s.substring(0, 237).trim() + "…";
        return s;
    }

    private static void offer(final WeakReference<Activity> ref, final SharedPreferences p, final Found f) {
        try {
            Activity a = ref.get();
            if (a == null || ChannelBanner.dead(a)) { p.edit().putLong("next", 0).apply(); return; }
            ChannelBanner.whenFree(new Runnable() {
                @Override public void run() {
                    try {
                        Activity a = ref.get();
                        if (a == null || ChannelBanner.dead(a) || !build(a, p, f)) {
                            p.edit().putLong("next", 0).apply();
                            if (a != null) ChannelBanner.freed(a);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean build(final Activity a, final SharedPreferences p, final Found f) {
        final boolean night = ChannelBanner.isDark(a);
        final int card = night ? 0xFF111113 : 0xFFFFFFFF;
        final int border = night ? 0xFF2A2A2F : 0xFFE4E4E8;
        final int primary = night ? 0xFFF4F4F6 : 0xFF0B0B0D;
        final int secondary = night ? 0xFFA3A3AB : 0xFF5A5A63;
        final int onPrimary = night ? 0xFF0B0B0D : 0xFFFFFFFF;
        final int outline = night ? 0xFF3A3A41 : 0xFFD2D2D8;
        final int rippleOnFill = night ? 0x33000000 : 0x33FFFFFF;
        final int rippleOnCard = night ? 0x22FFFFFF : 0x14000000;
        final float r = ChannelBanner.dp(a, 24);
        final int one = Math.max(1, ChannelBanner.dp(a, 1));

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ChannelBanner.dp(a, 24);
        root.setPadding(pad, ChannelBanner.dp(a, 26), pad, ChannelBanner.dp(a, 14));
        root.setBackground(ChannelBanner.pill(card, border, one, ChannelBanner.dp(a, 28)));

        try {
            Drawable icon = a.getApplicationInfo().loadIcon(a.getPackageManager());
            if (icon != null) {
                ImageView iv = new ImageView(a);
                iv.setImageDrawable(icon);
                int s = ChannelBanner.dp(a, 56);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
                lp.bottomMargin = ChannelBanner.dp(a, 16);
                root.addView(iv, lp);
            }
        } catch (Throwable ignored) {}

        TextView chip = new TextView(a);
        chip.setText("NEW VERSION");
        chip.setTextColor(secondary);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setLetterSpacing(0.12f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setPadding(ChannelBanner.dp(a, 10), ChannelBanner.dp(a, 4), ChannelBanner.dp(a, 10), ChannelBanner.dp(a, 4));
        chip.setBackground(ChannelBanner.pill(0x00000000, outline, one, ChannelBanner.dp(a, 12)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = ChannelBanner.dp(a, 12);
        root.addView(chip, clp);

        TextView title = new TextView(a);
        title.setText("Update available");
        title.setTextColor(primary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView ver = new TextView(a);
        ver.setText(f.name);
        ver.setTextColor(primary);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ver.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = ChannelBanner.dp(a, 6);
        root.addView(ver, vlp);

        TextView body = new TextView(a);
        body.setText(f.notes.length() > 0 ? f.notes : "A new version is ready to download.");
        body.setTextColor(secondary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.15f);
        body.setMaxLines(5);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = ChannelBanner.dp(a, 8);
        blp.bottomMargin = ChannelBanner.dp(a, 22);
        root.addView(body, blp);

        int bh = ChannelBanner.dp(a, 50);
        TextView dl = ChannelBanner.button(a, "Download", onPrimary, ChannelBanner.ripple(ChannelBanner.pill(primary, 0, 0, r), rippleOnFill, r), 15.5f, true);
        root.addView(dl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh));

        TextView later = ChannelBanner.button(a, "Later", primary, ChannelBanner.ripple(ChannelBanner.pill(0x00000000, outline, one, r), rippleOnCard, r), 15.5f, true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh);
        llp.topMargin = ChannelBanner.dp(a, 10);
        root.addView(later, llp);

        TextView skip = ChannelBanner.button(a, "Skip this version", secondary, ChannelBanner.ripple(ChannelBanner.pill(0x00000000, 0, 0, r), rippleOnCard, r), 13.5f, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ChannelBanner.dp(a, 44));
        slp.topMargin = ChannelBanner.dp(a, 6);
        root.addView(skip, slp);

        dl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ChannelBanner.dismiss(d); open(a, f.url); }
        });
        later.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ChannelBanner.dismiss(d); }
        });
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { p.edit().putLong("skip", f.code).apply(); } catch (Throwable ignored) {}
                ChannelBanner.dismiss(d);
            }
        });

        d.setContentView(root);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(false);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) { ChannelBanner.freed(a); }
        });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(screen - ChannelBanner.dp(a, 40), ChannelBanner.dp(a, 360));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(night ? 0.72f : 0.55f);
        }
        if (ChannelBanner.dead(a)) return false;
        d.show();
        ChannelBanner.busy = true;
        return true;
    }

    private static void open(Activity a, String url) {
        Uri uri = Uri.parse(url);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            a.startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            a.getApplicationContext().startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
    }
}
