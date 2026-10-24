package org.opennotes.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.ui.CanvasView;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages persistent per-notebook and per-imported-PDF settings and configuration:
 * - Active drawing tool (Pen, Fountain, Highlighter, Brush, Pencil, Laser, etc.)
 * - Stroke width / pen thickness (current and per-tool)
 * - Ink color (current and per-tool)
 * - Pressure dynamics sensitivity
 * - Stroke stabilization smoothing
 * - Straight-line assist snapping
 * - Straight highlighter lock (0° / 90°)
 * - Eraser mode (highlighter only vs all strokes)
 * - Stylus-only palm rejection mode
 * - Single-finger move canvas (hand tool)
 * - Super AMOLED Dark Mode for PDFs
 * - Continuous vertical scroll mode
 * - Viewport lock state
 * - Laser pointer duration
 * - Auto-beautification & text conversion
 * - Lasso selection mode (freehand vs rectangular)
 * - Laser pointer mode (trail line vs pointer dot)
 */
public final class NotebookConfigManager {
    private static final String PREF_NAME = "opennotes_notebook_configs";

    private static final String KEY_HAS_CONFIG = "has_config_";
    private static final String KEY_TOOL = "tool_";
    private static final String KEY_STROKE_WIDTH = "stroke_width_";
    private static final String KEY_INK_COLOR = "ink_color_";
    private static final String KEY_PRESSURE = "pressure_";
    private static final String KEY_STABILIZATION = "stabilization_";
    private static final String KEY_STRAIGHT_LINE = "straight_line_";
    private static final String KEY_STRAIGHT_HIGHLIGHTER = "straight_highlighter_";
    private static final String KEY_ERASE_ONLY_HIGHLIGHTER = "erase_only_highlighter_";
    private static final String KEY_STYLUS_ONLY = "stylus_only_";
    private static final String KEY_FINGER_MOVE = "finger_move_";
    private static final String KEY_AMOLED_DARK = "amoled_dark_";
    private static final String KEY_CONTINUOUS_SCROLL = "continuous_scroll_";
    private static final String KEY_VIEWPORT_LOCKED = "viewport_locked_";
    private static final String KEY_LASER_DURATION = "laser_duration_";
    private static final String KEY_AUTO_BEAUTIFY = "auto_beautify_";
    private static final String KEY_LASSO_MODE = "lasso_mode_";
    private static final String KEY_LASER_MODE = "laser_mode_";
    private static final String KEY_DEFAULT_RESET_ZOOM = "default_reset_zoom_";
    private static final String KEY_PDF_RESET_MODE = "pdf_reset_mode_";
    private static final String KEY_SHAPE_TYPE = "shape_type_";
    private static final String KEY_TOOL_WIDTH_PREFIX = "tw_";
    private static final String KEY_TOOL_COLOR_PREFIX = "tc_";

    public static class NotebookConfig {
        public DrawingTool tool;
        public float strokeWidth = -1f;
        public int inkColor = Integer.MIN_VALUE;
        public float pressureSensitivity = -1f;
        public float stabilization = -1f;
        public Boolean straightLineAssist = null;
        public Boolean straightHighlighterLock = null;
        public Boolean eraseOnlyHighlighter = null;
        public Boolean stylusOnly = null;
        public Boolean fingerMoveCanvas = null;
        public Boolean pdfAmoledDarkMode = null;
        public Boolean continuousVerticalScroll = null;
        public Boolean viewportLocked = null;
        public Long laserDurationMs = null;
        public Boolean autoBeautifyEnabled = null;
        public CanvasView.LassoMode lassoMode = null;
        public CanvasView.LaserMode laserMode = null;
        public Integer defaultResetZoom = null;
        public String pdfResetMode = null;
        public String shapeType = null;

        public final Map<DrawingTool, Float> toolWidths = new HashMap<>();
        public final Map<DrawingTool, Integer> toolColors = new HashMap<>();

        public float getToolWidth(DrawingTool t) {
            if (t != null && toolWidths.containsKey(t)) {
                Float w = toolWidths.get(t);
                if (w != null && w > 0f) return w;
            }
            return strokeWidth;
        }

        public void setToolWidth(DrawingTool t, float width) {
            if (t != null && width > 0f) {
                toolWidths.put(t, width);
            }
        }

        public int getToolColor(DrawingTool t) {
            if (t != null && toolColors.containsKey(t)) {
                Integer c = toolColors.get(t);
                if (c != null) return c;
            }
            return inkColor;
        }

        public void setToolColor(DrawingTool t, int color) {
            if (t != null) {
                toolColors.put(t, color);
            }
        }
    }

    private NotebookConfigManager() {}

    public static boolean hasConfig(@NonNull Context context, long notebookId) {
        if (notebookId <= 0) return false;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_HAS_CONFIG + notebookId, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    public static NotebookConfig loadConfig(@NonNull Context context, long notebookId) {
        if (notebookId <= 0) return null;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (!sp.getBoolean(KEY_HAS_CONFIG + notebookId, false)) {
                return null;
            }
            NotebookConfig config = new NotebookConfig();

            String toolName = sp.getString(KEY_TOOL + notebookId, null);
            if (toolName != null) {
                try {
                    config.tool = DrawingTool.valueOf(toolName);
                } catch (Throwable ignored) {}
            }

            if (sp.contains(KEY_STROKE_WIDTH + notebookId)) {
                config.strokeWidth = sp.getFloat(KEY_STROKE_WIDTH + notebookId, -1f);
            }
            if (sp.contains(KEY_INK_COLOR + notebookId)) {
                config.inkColor = sp.getInt(KEY_INK_COLOR + notebookId, Integer.MIN_VALUE);
            }
            if (sp.contains(KEY_PRESSURE + notebookId)) {
                config.pressureSensitivity = sp.getFloat(KEY_PRESSURE + notebookId, -1f);
            }
            if (sp.contains(KEY_STABILIZATION + notebookId)) {
                config.stabilization = sp.getFloat(KEY_STABILIZATION + notebookId, -1f);
            }
            if (sp.contains(KEY_STRAIGHT_LINE + notebookId)) {
                config.straightLineAssist = sp.getBoolean(KEY_STRAIGHT_LINE + notebookId, false);
            }
            if (sp.contains(KEY_STRAIGHT_HIGHLIGHTER + notebookId)) {
                config.straightHighlighterLock = sp.getBoolean(KEY_STRAIGHT_HIGHLIGHTER + notebookId, false);
            }
            if (sp.contains(KEY_ERASE_ONLY_HIGHLIGHTER + notebookId)) {
                config.eraseOnlyHighlighter = sp.getBoolean(KEY_ERASE_ONLY_HIGHLIGHTER + notebookId, false);
            }
            if (sp.contains(KEY_STYLUS_ONLY + notebookId)) {
                config.stylusOnly = sp.getBoolean(KEY_STYLUS_ONLY + notebookId, false);
            }
            if (sp.contains(KEY_FINGER_MOVE + notebookId)) {
                config.fingerMoveCanvas = sp.getBoolean(KEY_FINGER_MOVE + notebookId, false);
            }
            if (sp.contains(KEY_AMOLED_DARK + notebookId)) {
                config.pdfAmoledDarkMode = sp.getBoolean(KEY_AMOLED_DARK + notebookId, false);
            }
            if (sp.contains(KEY_CONTINUOUS_SCROLL + notebookId)) {
                config.continuousVerticalScroll = sp.getBoolean(KEY_CONTINUOUS_SCROLL + notebookId, false);
            }
            if (sp.contains(KEY_VIEWPORT_LOCKED + notebookId)) {
                config.viewportLocked = sp.getBoolean(KEY_VIEWPORT_LOCKED + notebookId, false);
            }
            if (sp.contains(KEY_LASER_DURATION + notebookId)) {
                config.laserDurationMs = sp.getLong(KEY_LASER_DURATION + notebookId, 1500L);
            }
            if (sp.contains(KEY_AUTO_BEAUTIFY + notebookId)) {
                config.autoBeautifyEnabled = sp.getBoolean(KEY_AUTO_BEAUTIFY + notebookId, true);
            }
            if (sp.contains(KEY_LASSO_MODE + notebookId)) {
                String lm = sp.getString(KEY_LASSO_MODE + notebookId, null);
                if (lm != null) {
                    try { config.lassoMode = CanvasView.LassoMode.valueOf(lm); } catch (Throwable ignored) {}
                }
            }
            if (sp.contains(KEY_LASER_MODE + notebookId)) {
                String lzm = sp.getString(KEY_LASER_MODE + notebookId, null);
                if (lzm != null) {
                    try { config.laserMode = CanvasView.LaserMode.valueOf(lzm); } catch (Throwable ignored) {}
                }
            }
            if (sp.contains(KEY_DEFAULT_RESET_ZOOM + notebookId)) {
                config.defaultResetZoom = sp.getInt(KEY_DEFAULT_RESET_ZOOM + notebookId, 100);
            }
            if (sp.contains(KEY_PDF_RESET_MODE + notebookId)) {
                config.pdfResetMode = sp.getString(KEY_PDF_RESET_MODE + notebookId, null);
            }
            if (sp.contains(KEY_SHAPE_TYPE + notebookId)) {
                config.shapeType = sp.getString(KEY_SHAPE_TYPE + notebookId, null);
            }

            // Restore tool-specific widths and colors
            for (DrawingTool t : DrawingTool.values()) {
                String wKey = KEY_TOOL_WIDTH_PREFIX + t.name() + "_" + notebookId;
                if (sp.contains(wKey)) {
                    config.toolWidths.put(t, sp.getFloat(wKey, -1f));
                }
                String cKey = KEY_TOOL_COLOR_PREFIX + t.name() + "_" + notebookId;
                if (sp.contains(cKey)) {
                    config.toolColors.put(t, sp.getInt(cKey, 0));
                }
            }

            return config;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void saveConfig(@NonNull Context context, long notebookId, @NonNull NotebookConfig config) {
        if (notebookId <= 0 || config == null) return;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean(KEY_HAS_CONFIG + notebookId, true);

            if (config.tool != null) {
                editor.putString(KEY_TOOL + notebookId, config.tool.name());
            }
            if (config.strokeWidth > 0f) {
                editor.putFloat(KEY_STROKE_WIDTH + notebookId, config.strokeWidth);
            }
            if (config.inkColor != Integer.MIN_VALUE) {
                editor.putInt(KEY_INK_COLOR + notebookId, config.inkColor);
            }
            if (config.pressureSensitivity >= 0f) {
                editor.putFloat(KEY_PRESSURE + notebookId, config.pressureSensitivity);
            }
            if (config.stabilization >= 0f) {
                editor.putFloat(KEY_STABILIZATION + notebookId, config.stabilization);
            }
            if (config.straightLineAssist != null) {
                editor.putBoolean(KEY_STRAIGHT_LINE + notebookId, config.straightLineAssist);
            }
            if (config.straightHighlighterLock != null) {
                editor.putBoolean(KEY_STRAIGHT_HIGHLIGHTER + notebookId, config.straightHighlighterLock);
            }
            if (config.eraseOnlyHighlighter != null) {
                editor.putBoolean(KEY_ERASE_ONLY_HIGHLIGHTER + notebookId, config.eraseOnlyHighlighter);
            }
            if (config.stylusOnly != null) {
                editor.putBoolean(KEY_STYLUS_ONLY + notebookId, config.stylusOnly);
            }
            if (config.fingerMoveCanvas != null) {
                editor.putBoolean(KEY_FINGER_MOVE + notebookId, config.fingerMoveCanvas);
            }
            if (config.pdfAmoledDarkMode != null) {
                editor.putBoolean(KEY_AMOLED_DARK + notebookId, config.pdfAmoledDarkMode);
            }
            if (config.continuousVerticalScroll != null) {
                editor.putBoolean(KEY_CONTINUOUS_SCROLL + notebookId, config.continuousVerticalScroll);
            }
            if (config.viewportLocked != null) {
                editor.putBoolean(KEY_VIEWPORT_LOCKED + notebookId, config.viewportLocked);
            }
            if (config.laserDurationMs != null) {
                editor.putLong(KEY_LASER_DURATION + notebookId, config.laserDurationMs);
            }
            if (config.autoBeautifyEnabled != null) {
                editor.putBoolean(KEY_AUTO_BEAUTIFY + notebookId, config.autoBeautifyEnabled);
            }
            if (config.lassoMode != null) {
                editor.putString(KEY_LASSO_MODE + notebookId, config.lassoMode.name());
            }
            if (config.laserMode != null) {
                editor.putString(KEY_LASER_MODE + notebookId, config.laserMode.name());
            }
            if (config.defaultResetZoom != null && config.defaultResetZoom >= 10) {
                editor.putInt(KEY_DEFAULT_RESET_ZOOM + notebookId, config.defaultResetZoom);
            }
            if (config.pdfResetMode != null) {
                editor.putString(KEY_PDF_RESET_MODE + notebookId, config.pdfResetMode);
            }
            if (config.shapeType != null) {
                editor.putString(KEY_SHAPE_TYPE + notebookId, config.shapeType);
            }

            for (Map.Entry<DrawingTool, Float> entry : config.toolWidths.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0f) {
                    editor.putFloat(KEY_TOOL_WIDTH_PREFIX + entry.getKey().name() + "_" + notebookId, entry.getValue());
                }
            }
            for (Map.Entry<DrawingTool, Integer> entry : config.toolColors.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    editor.putInt(KEY_TOOL_COLOR_PREFIX + entry.getKey().name() + "_" + notebookId, entry.getValue());
                }
            }

            editor.apply();
        } catch (Throwable ignored) {}
    }

    public static void clearConfig(@NonNull Context context, long notebookId) {
        if (notebookId <= 0) return;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.remove(KEY_HAS_CONFIG + notebookId);
            editor.remove(KEY_TOOL + notebookId);
            editor.remove(KEY_STROKE_WIDTH + notebookId);
            editor.remove(KEY_INK_COLOR + notebookId);
            editor.remove(KEY_PRESSURE + notebookId);
            editor.remove(KEY_STABILIZATION + notebookId);
            editor.remove(KEY_STRAIGHT_LINE + notebookId);
            editor.remove(KEY_STRAIGHT_HIGHLIGHTER + notebookId);
            editor.remove(KEY_ERASE_ONLY_HIGHLIGHTER + notebookId);
            editor.remove(KEY_STYLUS_ONLY + notebookId);
            editor.remove(KEY_FINGER_MOVE + notebookId);
            editor.remove(KEY_AMOLED_DARK + notebookId);
            editor.remove(KEY_CONTINUOUS_SCROLL + notebookId);
            editor.remove(KEY_VIEWPORT_LOCKED + notebookId);
            editor.remove(KEY_LASER_DURATION + notebookId);
            editor.remove(KEY_AUTO_BEAUTIFY + notebookId);
            editor.remove(KEY_LASSO_MODE + notebookId);
            editor.remove(KEY_LASER_MODE + notebookId);
            editor.remove(KEY_DEFAULT_RESET_ZOOM + notebookId);
            editor.remove(KEY_PDF_RESET_MODE + notebookId);
            editor.remove(KEY_SHAPE_TYPE + notebookId);

            for (DrawingTool t : DrawingTool.values()) {
                editor.remove(KEY_TOOL_WIDTH_PREFIX + t.name() + "_" + notebookId);
                editor.remove(KEY_TOOL_COLOR_PREFIX + t.name() + "_" + notebookId);
            }
            editor.apply();
        } catch (Throwable ignored) {}
    }
}
