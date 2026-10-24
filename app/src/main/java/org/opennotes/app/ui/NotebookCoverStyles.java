package org.opennotes.app.ui;

import android.graphics.drawable.GradientDrawable;

/** Curated cover palettes shared by notebook creation, editing, and the shelf. */
public final class NotebookCoverStyles {
    public static final String[] NAMES = {
            "Aurora", "Ocean", "Lagoon", "Glacier", "Midnight", "Galaxy",
            "Amethyst", "Orchid", "Rose", "Blush", "Ember", "Sunset",
            "Terracotta", "Canyon", "Saffron", "Honey", "Olive", "Forest",
            "Jade", "Mint", "Sage", "Silver", "Graphite", "Cocoa",
            "Velvet", "Iris", "Electric", "Cosmic", "Coral", "Pearl"
    };

    private static final int[][] COLORS = {
            {0xFF0F766E, 0xFF312E81}, {0xFF075985, 0xFF1D4ED8}, {0xFF0E7490, 0xFF0F766E},
            {0xFF334155, 0xFF0891B2}, {0xFF111827, 0xFF334155}, {0xFF312E81, 0xFF7E22CE},
            {0xFF581C87, 0xFFBE185D}, {0xFF86198F, 0xFF4F46E5}, {0xFF9F1239, 0xFF6D28D9},
            {0xFFBE185D, 0xFFFB7185}, {0xFF7C2D12, 0xFFB91C1C}, {0xFF9A3412, 0xFFDB2777},
            {0xFF7C2D12, 0xFFB45309}, {0xFF78350F, 0xFF9F1239}, {0xFF854D0E, 0xFFEAB308},
            {0xFF92400E, 0xFFF59E0B}, {0xFF365314, 0xFF854D0E}, {0xFF14532D, 0xFF166534},
            {0xFF065F46, 0xFF0E7490}, {0xFF047857, 0xFF2DD4BF}, {0xFF3F6212, 0xFF0F766E},
            {0xFF334155, 0xFF64748B}, {0xFF18181B, 0xFF475569}, {0xFF3F2A24, 0xFF9A3412},
            {0xFF4C1D95, 0xFFBE185D}, {0xFF4338CA, 0xFF7C3AED}, {0xFF0369A1, 0xFF06B6D4},
            {0xFF1E1B4B, 0xFFBE185D}, {0xFFBE123C, 0xFFFB923C}, {0xFF334155, 0xFF94A3B8}
    };

    private NotebookCoverStyles() {}

    public static int count() { return COLORS.length; }

    public static int startColor(int index) {
        return COLORS[clamp(index)][0];
    }

    public static GradientDrawable drawable(int index) {
        int[] colors = COLORS[clamp(index)];
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        gradient.setCornerRadius(18f);
        gradient.setStroke(1, 0x5538BDF8);
        return gradient;
    }

    public static GradientDrawable legacyDrawable(int color) {
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, blend(color, 0xFF000000, 0.24f)});
        gradient.setCornerRadius(18f);
        gradient.setStroke(1, 0x33FFFFFF);
        return gradient;
    }

    private static int clamp(int index) {
        return Math.max(0, Math.min(COLORS.length - 1, index));
    }

    private static int blend(int from, int to, float amount) {
        int a = (int) (((from >>> 24) & 255) * (1f - amount) + ((to >>> 24) & 255) * amount);
        int r = (int) (((from >>> 16) & 255) * (1f - amount) + ((to >>> 16) & 255) * amount);
        int g = (int) (((from >>> 8) & 255) * (1f - amount) + ((to >>> 8) & 255) * amount);
        int b = (int) ((from & 255) * (1f - amount) + (to & 255) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
