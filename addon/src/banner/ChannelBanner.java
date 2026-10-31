package paradox.banner;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Paradox WhatsApp channel welcome card. Shown once per cold start (process),
 * never again after "Don't show again" (or after "Join channel").
 * Pure framework code, no resources, every path wrapped so it can never crash the host app.
 */
public final class ChannelBanner {
    public static final String URL = "https://whatsapp.com/channel/0029Vb96Yl6IHphOLQa5H10O";
    private static final String PREFS = "paradox_channel_banner";
    private static final String KEY_HIDE = "hide";
    private static boolean shownThisProcess;
    private static Boolean forcedDark;
    /** One Paradox card at a time: the channel card and the update card queue behind each other. */
    static boolean busy;
    private static boolean channelPending;
    private static Runnable waiting;

    private ChannelBanner() {}

    /** For apps that paint their own theme: pass the app's own dark/light decision. */
    public static void show(final Activity a, boolean dark) {
        forcedDark = Boolean.valueOf(dark);
        show(a);
    }

    /** Shows only when another package (e.g. MicroG for YouTube Ultra) is installed, so it never stacks on a setup sheet. */
    public static void showIfInstalled(final Activity a, String pkg) {
        try {
            a.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Throwable notInstalled) {
            return;
        }
        show(a);
    }

    /** YouTube Ultra: wait until MicroG is installed (the MicroG sheet comes first on a fresh install). */
    public static void showWithMicroG(final Activity a) {
        showIfInstalled(a, "app.revanced.android.gms");
    }

    public static void show(final Activity a) {
        // Update check (GitHub releases, at most every 12 h, background thread, silent on any error).
        try { UpdateCheck.start(a); } catch (Throwable ignored) {}
        try {
            if (a == null || shownThisProcess) return;
            if (prefs(a).getBoolean(KEY_HIDE, false)) { shownThisProcess = true; return; }
            if (channelPending) return;
            channelPending = true;
            final View decor = a.getWindow().getDecorView();
            decor.postDelayed(new Runnable() {
                @Override public void run() { safeShow(a); }
            }, 900);
        } catch (Throwable ignored) { channelPending = false; }
    }

    /** Runs r now if no Paradox card is on screen or about to be, else right after it closes. */
    static void whenFree(Runnable r) {
        if (busy || channelPending) { waiting = r; return; }
        try { r.run(); } catch (Throwable ignored) {}
    }

    /** Called when a Paradox card closes (or the pending channel card decides not to show). */
    static void freed(final Activity a) {
        busy = false;
        final Runnable r = waiting;
        waiting = null;
        if (r == null) return;
        try {
            a.getWindow().getDecorView().postDelayed(r, 450);
        } catch (Throwable t) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean dead(Activity a) {
        if (a.isFinishing()) return true;
        return Build.VERSION.SDK_INT >= 17 && a.isDestroyed();
    }

    private static void safeShow(final Activity a) {
        if (busy) {
            // The update card is on screen: show the channel card after it.
            channelPending = false;
            waiting = new Runnable() { @Override public void run() { channelPending = true; safeShow(a); } };
            return;
        }
        boolean showed = false;
        try {
            if (!shownThisProcess && !dead(a)) showed = build(a);
        } catch (Throwable ignored) {}
        channelPending = false;
        if (!showed) freed(a);
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable pill(int fill, int stroke, int strokeW, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setColor(fill);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    static Drawable ripple(Drawable content, int rippleColor, float radius) {
        if (Build.VERSION.SDK_INT < 21) return content;
        GradientDrawable mask = pill(Color.WHITE, 0, 0, radius);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static TextView button(Context c, String text, int textColor, Drawable bg, float sizeSp, boolean medium) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(false);
        b.setSingleLine(true);
        if (medium) b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setClickable(true);
        b.setFocusable(true);
        if (bg != null) b.setBackground(bg);
        return b;
    }

    private static boolean build(final Activity a) {
        final boolean night = isDark(a);
        final int card = night ? 0xFF111113 : 0xFFFFFFFF;
        final int border = night ? 0xFF2A2A2F : 0xFFE4E4E8;
        final int primary = night ? 0xFFF4F4F6 : 0xFF0B0B0D;
        final int secondary = night ? 0xFFA3A3AB : 0xFF5A5A63;
        final int onPrimary = night ? 0xFF0B0B0D : 0xFFFFFFFF;
        final int outline = night ? 0xFF3A3A41 : 0xFFD2D2D8;
        final int rippleOnFill = night ? 0x33000000 : 0x33FFFFFF;
        final int rippleOnCard = night ? 0x22FFFFFF : 0x14000000;
        final float r = dp(a, 24);

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(a, 24);
        root.setPadding(pad, dp(a, 26), pad, dp(a, 14));
        root.setBackground(pill(card, border, Math.max(1, dp(a, 1)), dp(a, 28)));

        // App's own (Paradox) icon on a subtle tile
        try {
            Drawable icon = a.getApplicationInfo().loadIcon(a.getPackageManager());
            if (icon != null) {
                ImageView iv = new ImageView(a);
                iv.setImageDrawable(icon);
                int s = dp(a, 56);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
                lp.bottomMargin = dp(a, 16);
                root.addView(iv, lp);
            }
        } catch (Throwable ignored) {}

        TextView chip = new TextView(a);
        chip.setText("WHATSAPP CHANNEL");
        chip.setTextColor(secondary);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setLetterSpacing(0.12f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        chip.setBackground(pill(0x00000000, outline, Math.max(1, dp(a, 1)), dp(a, 12)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(a, 12);
        root.addView(chip, clp);

        TextView title = new TextView(a);
        title.setText("Join Paradox on WhatsApp");
        title.setTextColor(primary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(a);
        body.setText("Updates, new apps and support, first on the Paradox channel.");
        body.setTextColor(secondary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(a, 8);
        blp.bottomMargin = dp(a, 22);
        root.addView(body, blp);

        int bh = dp(a, 50);
        TextView join = button(a, "Join channel", onPrimary, ripple(pill(primary, 0, 0, r), rippleOnFill, r), 15.5f, true);
        root.addView(join, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh));

        TextView later = button(a, "Later", primary, ripple(pill(0x00000000, outline, Math.max(1, dp(a, 1)), r), rippleOnCard, r), 15.5f, true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh);
        llp.topMargin = dp(a, 10);
        root.addView(later, llp);

        TextView never = button(a, "Don't show again", secondary, ripple(pill(0x00000000, 0, 0, r), rippleOnCard, r), 13.5f, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44));
        nlp.topMargin = dp(a, 6);
        root.addView(never, nlp);

        join.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Joining also retires the card: someone who joined is not asked again.
                hideForever(a);
                dismiss(d);
                open(a);
            }
        });
        later.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(d); }
        });
        never.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideForever(a); dismiss(d); }
        });

        d.setContentView(root);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(false);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(screen - dp(a, 40), dp(a, 360));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(night ? 0.72f : 0.55f);
        }
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) { freed(a); }
        });
        if (dead(a)) return false;
        d.show();
        busy = true;
        shownThisProcess = true;
        return true;
    }

    /** Follows the host app's own theme: its window background colour, else the night-mode flag. */
    static boolean isDark(Activity a) {
        if (forcedDark != null) return forcedDark.booleanValue();
        boolean sys = (a.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        try {
            TypedValue tv = new TypedValue();
            if (a.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)
                    && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                int c = tv.data;
                if (Color.alpha(c) > 200) {
                    double l = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c);
                    return l < 128;
                }
            }
        } catch (Throwable ignored) {}
        return sys;
    }

    static void dismiss(Dialog d) {
        try { d.dismiss(); } catch (Throwable ignored) {}
    }

    private static void hideForever(Context c) {
        try { prefs(c).edit().putBoolean(KEY_HIDE, true).apply(); } catch (Throwable ignored) {}
    }

    private static void open(Activity a) {
        Uri uri = Uri.parse(URL);
        try {
            // WhatsApp claims whatsapp.com/channel links; without it any browser opens the page.
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            a.startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.getApplicationContext().startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Throwable ignored) {}
        try { Toast.makeText(a, "No app can open " + URL, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
