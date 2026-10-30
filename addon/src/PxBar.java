package app.hushpinterest.extension.pinterest.actions;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Paradox v1.1 pin action bar: the react (heart) button becomes Download and the comment button
 * becomes Copy, in the closeup's primary action bar. Called once as the bar finishes building.
 */
public final class PxBar {
    private PxBar() {}

    static final String TAG = "paradox_px_button";

    /** Hook: end of the closeup primary action bar's constructor, with its react and comment views. */
    public static void attach(LinearLayout bar, View react, View comments) {
        try {
            if (bar == null) return;
            replace(bar, react, true);
            replace(bar, comments, false);
        } catch (Throwable ignored) {
            // Never break the pin page: Pinterest's own buttons stay if anything here fails.
        }
    }

    static void replace(LinearLayout bar, View original, boolean download) {
        if (original == null || original.getParent() != bar) return;
        Context ctx = bar.getContext();
        int index = bar.indexOfChild(original);
        ImageView button = new ImageView(ctx);
        button.setTag(TAG);
        int size = dimen(ctx, "closeup_action_button_size", 48);
        int glyph = dp(ctx, 24);
        int pad = Math.max(0, (size - glyph) / 2);
        button.setPadding(pad, pad, pad, pad);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable icon = drawable(ctx, download ? new String[]{"ic_vr_download_gestalt", "download_icon"}
                : new String[]{"ic_vr_duplicate_gestalt", "ic_vr_copy_to_clipboard_gestalt", "abc_ic_menu_copy_mtrl_am_alpha"});
        if (icon != null) button.setImageDrawable(icon);
        button.setImageTintList(ColorStateList.valueOf(iconColor(ctx)));
        button.setContentDescription(download ? "Download" : "Copy");
        button.setClickable(true);
        button.setFocusable(true);
        TypedValue ripple = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true) && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        int min = dimen(ctx, "primary_action_bar_action_min_height", 0);
        if (min > 0) button.setMinimumHeight(min);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, Math.max(size, min > 0 ? min : size));
        lp.gravity = Gravity.CENTER_VERTICAL;
        int gap = dp(ctx, 4);
        lp.setMarginStart(gap);
        lp.setMarginEnd(gap);
        button.setOnClickListener(v -> {
            Object pin = findPin(v);
            if (pin == null) {
                PxSave.toast(v.getContext().getApplicationContext(), "Couldn't read this pin. Use ⋯ > Download pin.");
                return;
            }
            if (download) PxSave.download(pin, v.getContext()); else PxSave.copy(pin, v.getContext());
        });
        bar.removeViewAt(index);
        bar.addView(button, index, lp);
    }

    static int iconColor(Context ctx) {
        try {
            TypedArray a = ctx.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
            int c = a.getColor(0, 0);
            a.recycle();
            if (c != 0) return c;
        } catch (Throwable ignored) { }
        boolean night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFFFFFFFF : 0xFF111111;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static int dimen(Context ctx, String name, int fallbackDp) {
        try {
            int id = ctx.getResources().getIdentifier(name, "dimen", ctx.getPackageName());
            if (id != 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return fallbackDp > 0 ? dp(ctx, fallbackDp) : 0;
    }

    static Drawable drawable(Context ctx, String[] names) {
        for (String n : names) {
            try {
                int id = ctx.getResources().getIdentifier(n, "drawable", ctx.getPackageName());
                if (id != 0) return ctx.getDrawable(id).mutate();
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** The pin of the closeup holding [view]: the nearest ancestor that keeps a pin model, directly or one object deep. */
    static Object findPin(View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            Object pin = pinIn(at, 0);
            if (pin != null) return pin;
            if (!(((View) at).getParent() instanceof View)) break;
        }
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            Object pin = pinIn(at, 1);
            if (pin != null) return pin;
            if (!(((View) at).getParent() instanceof View)) break;
        }
        return null;
    }

    static Object pinIn(Object owner, int depth) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String cn = c.getName();
            if (cn.startsWith("android.") || cn.startsWith("androidx.") || cn.startsWith("java.")) break;
            Field[] fields;
            try { fields = c.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Object value;
                try { f.setAccessible(true); value = f.get(owner); } catch (Throwable t) { continue; }
                if (value == null || value instanceof View || value instanceof ViewParent || value instanceof Context) continue;
                if (PxMedia.isPin(value)) return value;
                if (depth > 0 && !(value instanceof CharSequence) && !value.getClass().isArray()) {
                    String vn = value.getClass().getName();
                    if (vn.startsWith("java.") || vn.startsWith("android.") || vn.startsWith("kotlin.")) continue;
                    Object inner = pinIn(value, depth - 1);
                    if (inner != null) return inner;
                }
            }
        }
        return null;
    }
}
