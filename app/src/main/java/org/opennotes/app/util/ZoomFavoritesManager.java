package org.opennotes.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages user-favorited zoom levels for quick selection in NoteEditorActivity.
 */
public class ZoomFavoritesManager {
    private static final String PREF_NAME = "opennotes_zoom_prefs";
    private static final String KEY_FAVORITES = "favorite_zoom_levels";

    public static final int[] PRESET_ZOOM_LEVELS = {10, 20, 25, 50, 75, 100, 150, 200, 400, 800, 1600};

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns a sorted list of favorite zoom percentage levels.
     */
    public static synchronized List<Integer> getFavoriteZoomLevels(Context context) {
        SharedPreferences prefs = getPrefs(context);
        Set<String> set = prefs.getStringSet(KEY_FAVORITES, null);
        List<Integer> list = new ArrayList<>();
        if (set == null) {
            // Default initial favorite zoom levels
            int[] defaults = {50, 100, 150, 200};
            Set<String> defSet = new HashSet<>();
            for (int z : defaults) {
                list.add(z);
                defSet.add(String.valueOf(z));
            }
            prefs.edit().putStringSet(KEY_FAVORITES, defSet).apply();
        } else {
            for (String s : set) {
                try {
                    list.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Checks if a zoom percentage is in the favorites list.
     */
    public static synchronized boolean isFavorite(Context context, int zoomPercent) {
        List<Integer> favorites = getFavoriteZoomLevels(context);
        for (int fav : favorites) {
            if (fav == zoomPercent) return true;
        }
        return false;
    }

    /**
     * Adds a zoom percentage to favorites and persists it.
     */
    public static synchronized void addFavorite(Context context, int zoomPercent) {
        if (zoomPercent < 10 || zoomPercent > 2000) return;
        SharedPreferences prefs = getPrefs(context);
        Set<String> current = prefs.getStringSet(KEY_FAVORITES, null);
        Set<String> set = (current != null) ? new HashSet<>(current) : new HashSet<>();
        set.add(String.valueOf(zoomPercent));
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply();
    }

    /**
     * Removes a zoom percentage from favorites and persists it.
     */
    public static synchronized void removeFavorite(Context context, int zoomPercent) {
        SharedPreferences prefs = getPrefs(context);
        Set<String> current = prefs.getStringSet(KEY_FAVORITES, null);
        if (current == null) return;
        Set<String> set = new HashSet<>(current);
        set.remove(String.valueOf(zoomPercent));
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply();
    }

    private static final String KEY_DEFAULT_RESET_ZOOM = "default_reset_zoom_level";
    public static final String PDF_RESET_MODE_FIT_PAGE = "FIT_PAGE";
    public static final String PDF_RESET_MODE_FAVORITE_CENTERED = "FAVORITE_CENTERED";
    private static final String KEY_PDF_RESET_MODE = "pdf_reset_mode";

    /**
     * Gets the user's preferred zoom percentage to reset to (defaults to 100%).
     */
    public static synchronized int getDefaultResetZoom(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs.getInt(KEY_DEFAULT_RESET_ZOOM, 100);
    }

    /**
     * Sets the user's preferred zoom percentage to reset to.
     */
    public static synchronized void setDefaultResetZoom(Context context, int zoomPercent) {
        if (zoomPercent < 10 || zoomPercent > 2000) return;
        SharedPreferences prefs = getPrefs(context);
        prefs.edit().putInt(KEY_DEFAULT_RESET_ZOOM, zoomPercent).apply();
    }

    /**
     * Gets the preferred PDF reset mode (defaults to FIT_PAGE).
     */
    public static synchronized String getPdfResetMode(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs.getString(KEY_PDF_RESET_MODE, PDF_RESET_MODE_FIT_PAGE);
    }

    /**
     * Sets the preferred PDF reset mode (FIT_PAGE or FAVORITE_CENTERED).
     */
    public static synchronized void setPdfResetMode(Context context, String mode) {
        if (mode == null) return;
        SharedPreferences prefs = getPrefs(context);
        prefs.edit().putString(KEY_PDF_RESET_MODE, mode).apply();
    }
}
