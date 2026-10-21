package org.opennotes.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Manages persistent reading and canvas viewing positions per notebook.
 * Remembers the exact page, vertical scroll offset, pan coordinates, and zoom level
 * so that returning to any notebook instantly restores the user's exact context
 * with smooth, professional animations.
 */
public final class NotebookViewStateManager {
    private static final String PREF_NAME = "opennotes_notebook_view_states";
    private static final String KEY_PAGE = "page_";
    private static final String KEY_SCROLL = "scroll_";
    private static final String KEY_ZOOM = "zoom_";
    private static final String KEY_PAN_X = "pan_x_";
    private static final String KEY_PAN_Y = "pan_y_";
    private static final String KEY_TIMESTAMP = "time_";

    public static class ViewState {
        public final int pageIndex;
        public final float scrollY;
        public final float zoomPercent;
        public final float panX;
        public final float panY;
        public final long timestamp;

        public ViewState(int pageIndex, float scrollY, float zoomPercent, float panX, float panY, long timestamp) {
            this.pageIndex = Math.max(0, pageIndex);
            this.scrollY = Math.max(0f, scrollY);
            this.zoomPercent = Math.max(10f, zoomPercent);
            this.panX = panX;
            this.panY = panY;
            this.timestamp = timestamp;
        }

        public boolean hasSpecificPosition() {
            return pageIndex > 0 || scrollY > 30f || Math.abs(zoomPercent - 100f) > 5f
                    || Math.abs(panX) > 15f || Math.abs(panY) > 15f;
        }
    }

    private NotebookViewStateManager() {}

    public static void saveState(Context context, long notebookId, int pageIndex,
                                 float scrollY, float zoomPercent, float panX, float panY) {
        if (context == null || notebookId <= 0) return;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_PAGE + notebookId, Math.max(0, pageIndex))
                    .putFloat(KEY_SCROLL + notebookId, Math.max(0f, scrollY))
                    .putFloat(KEY_ZOOM + notebookId, Math.max(10f, zoomPercent))
                    .putFloat(KEY_PAN_X + notebookId, panX)
                    .putFloat(KEY_PAN_Y + notebookId, panY)
                    .putLong(KEY_TIMESTAMP + notebookId, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
    }

    @Nullable
    public static ViewState loadState(Context context, long notebookId) {
        if (context == null || notebookId <= 0) return null;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (!sp.contains(KEY_PAGE + notebookId) && !sp.contains(KEY_SCROLL + notebookId)) {
                return null;
            }
            int page = sp.getInt(KEY_PAGE + notebookId, 0);
            float scroll = sp.getFloat(KEY_SCROLL + notebookId, 0f);
            float zoom = sp.getFloat(KEY_ZOOM + notebookId, 100f);
            float panX = sp.getFloat(KEY_PAN_X + notebookId, 0f);
            float panY = sp.getFloat(KEY_PAN_Y + notebookId, 0f);
            long time = sp.getLong(KEY_TIMESTAMP + notebookId, 0L);
            return new ViewState(page, scroll, zoom, panX, panY, time);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void clearState(Context context, long notebookId) {
        if (context == null || notebookId <= 0) return;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit()
                    .remove(KEY_PAGE + notebookId)
                    .remove(KEY_SCROLL + notebookId)
                    .remove(KEY_ZOOM + notebookId)
                    .remove(KEY_PAN_X + notebookId)
                    .remove(KEY_PAN_Y + notebookId)
                    .remove(KEY_TIMESTAMP + notebookId)
                    .apply();
        } catch (Throwable ignored) {}
    }
}
