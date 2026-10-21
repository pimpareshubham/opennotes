package org.opennotes.app.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Utility for ensuring all dialog buttons and UI action elements dynamically adapt
 * to the active device theme (Dark Mode / Light Mode) and app environment.
 * Guarantees crisp readability, high contrast, and tactile pill button styling
 * that will never blend into dark or light backgrounds.
 */
public final class ThemeUtils {
    private ThemeUtils() {}

    /**
     * Checks if the active environment (surface background or device theme) is Dark.
     * Evaluates theme colorSurface and windowBackground luminance so buttons dynamically
     * guarantee high-contrast readability in dark app environments even when the OS is in light mode.
     */
    public static boolean isDarkEnvironment(Context context) {
        if (context == null) return true;

        // 1. Check the context/dialog theme's colorSurface attribute
        TypedValue typedValue = new TypedValue();
        if (context.getTheme() != null && context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return ColorUtils.calculateLuminance(typedValue.data) < 0.5;
            }
        }

        // 2. Check windowBackground
        if (context.getTheme() != null && context.getTheme().resolveAttribute(android.R.attr.windowBackground, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return ColorUtils.calculateLuminance(typedValue.data) < 0.5;
            }
        }

        // 3. Fallback to system UI mode configuration
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags != Configuration.UI_MODE_NIGHT_NO;
    }

    /**
     * Checks if the active environment or system configuration is in Dark Mode.
     */
    public static boolean isDarkMode(Context context) {
        return isDarkEnvironment(context);
    }

    public static int dp(Context context, int value) {
        if (context == null) return value;
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int dp(Context context, float value) {
        if (context == null) return Math.round(value);
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Helper to create a styled AlertDialog from a builder with dynamic button themes.
     */
    public static AlertDialog createStyledDialog(@NonNull MaterialAlertDialogBuilder builder, Context context) {
        AlertDialog dialog = builder.create();
        styleDialog(dialog, context);
        return dialog;
    }

    /**
     * Helper to show a styled AlertDialog directly from a builder.
     */
    public static AlertDialog showStyledDialog(@NonNull MaterialAlertDialogBuilder builder, Context context) {
        AlertDialog dialog = createStyledDialog(builder, context);
        dialog.show();
        return dialog;
    }

    /**
     * Dynamically styles AlertDialog positive, negative, and neutral buttons according to
     * the device theme and surface background, ensuring bold, high-contrast, beautiful buttons.
     */
    public static void styleDialog(AlertDialog dialog, Context context) {
        if (dialog == null) return;
        Context ctx = (context != null) ? context : dialog.getContext();
        boolean isDark = isDarkMode(ctx);
        if (dialog.getWindow() != null) {
            GradientDrawable windowBg = new GradientDrawable();
            windowBg.setShape(GradientDrawable.RECTANGLE);
            windowBg.setCornerRadius(dp(ctx, 24));
            if (isDark) {
                windowBg.setColors(new int[] { 0xF526293E, 0xEC1A1C2B });
                windowBg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
                windowBg.setStroke(dp(ctx, 1.2f), 0x48FFFFFF);
            } else {
                windowBg.setColor(0xFFFFFFFF);
                windowBg.setStroke(dp(ctx, 1), 0xFFE2E8F0);
            }
            dialog.getWindow().setBackgroundDrawable(windowBg);

            // Android 12+ (API 31+) - Hardware compositor blur behind window (SurfaceFlinger 120 FPS zero-overhead)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    dialog.getWindow().getAttributes().setBlurBehindRadius(dp(ctx, 28));
                } catch (Throwable ignored) {}
            }
        }
        dialog.setOnShowListener(d -> applyDialogButtonTheme(dialog, ctx));
        if (dialog.isShowing()) {
            applyDialogButtonTheme(dialog, ctx);
        }
    }

    public static void applyDialogButtonTheme(AlertDialog dialog, Context context) {
        if (dialog == null) return;
        Context ctx = (context != null) ? context : dialog.getContext();
        boolean isDark = isDarkMode(ctx);

        // Positive button: StarNote vibrant Sky Blue on dark (#38BDF8), Deep Royal Blue on light (#0284C7)
        int positiveTextColor = isDark ? 0xFF38BDF8 : 0xFF0284C7;
        int positiveBgColor   = isDark ? 0x2E38BDF8 : 0x1E0284C7;
        int positiveStroke    = isDark ? 0x6638BDF8 : 0x550284C7;

        // Negative button: Crisp Pure White/Silver on dark (#F8FAFC), Slate Slate-900 on light (#0F172A)
        int negativeTextColor = isDark ? 0xFFF8FAFC : 0xFF0F172A;
        int negativeBgColor   = isDark ? 0x1AFFFFFF : 0x14000000;
        int negativeStroke    = isDark ? 0x33FFFFFF : 0x28000000;

        // Neutral button: Secondary Slate (#94A3B8 on dark, #475569 on light)
        int neutralTextColor  = isDark ? 0xFF94A3B8 : 0xFF475569;
        int neutralBgColor    = isDark ? 0x10FFFFFF : 0x0A000000;
        int neutralStroke     = isDark ? 0x22FFFFFF : 0x1A000000;

        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) {
            styleActionButton(posBtn, ctx, positiveTextColor, positiveBgColor, positiveStroke, true);
        }

        Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negBtn != null) {
            styleActionButton(negBtn, ctx, negativeTextColor, negativeBgColor, negativeStroke, true);
        }

        Button neuBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neuBtn != null) {
            styleActionButton(neuBtn, ctx, neutralTextColor, neutralBgColor, neutralStroke, false);
        }
    }

    /**
     * Styles a standalone Button (or view button) with high-contrast, theme-aware styling.
     */
    public static void styleButton(Button button, Context context, boolean isPrimary) {
        if (button == null) return;
        Context ctx = (context != null) ? context : button.getContext();
        boolean isDark = isDarkMode(ctx);

        int textColor = isPrimary
                ? (isDark ? 0xFF38BDF8 : 0xFF0284C7)
                : (isDark ? 0xFFF8FAFC : 0xFF0F172A);
        int bgColor = isPrimary
                ? (isDark ? 0x2E38BDF8 : 0x1E0284C7)
                : (isDark ? 0x1AFFFFFF : 0x14000000);
        int strokeColor = isPrimary
                ? (isDark ? 0x6638BDF8 : 0x550284C7)
                : (isDark ? 0x33FFFFFF : 0x28000000);

        styleActionButton(button, ctx, textColor, bgColor, strokeColor, true);
    }

    /**
     * Styles a danger/destructive Button (e.g. Delete, Reset, Clear) with bold red high-contrast styling.
     */
    public static void styleDangerButton(Button button, Context context) {
        if (button == null) return;
        Context ctx = (context != null) ? context : button.getContext();
        int textColor = 0xFFEF4444;
        int bgColor = 0x22EF4444;
        int strokeColor = 0x66EF4444;
        styleActionButton(button, ctx, textColor, bgColor, strokeColor, true);
    }

    private static void styleActionButton(Button button, Context ctx, int textColor, int bgColor, int strokeColor, boolean isBold) {
        if (button == null) return;
        button.setTextColor(textColor);
        button.setTypeface(isBold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setAllCaps(false);

        // Tactile pill background with subtle border and touch ripple
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(ctx, 16));
        shape.setColor(bgColor);
        if (strokeColor != 0) {
            shape.setStroke(dp(ctx, 1), strokeColor);
        }

        int rippleColor = ColorUtils.setAlphaComponent(textColor, 48);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null);
        button.setBackground(ripple);

        int padH = dp(ctx, 16);
        int padV = dp(ctx, 8);
        button.setPadding(padH, padV, padH, padV);
        button.setMinimumHeight(dp(ctx, 38));

        // Add comfortable horizontal margin between action buttons
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            llp.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
            button.setLayoutParams(llp);
        }
    }
}
