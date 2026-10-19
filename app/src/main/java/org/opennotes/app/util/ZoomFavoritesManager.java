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
}
