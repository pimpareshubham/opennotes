package org.opennotes.app.db;

import android.content.Context;
import android.content.SharedPreferences;

import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.PaperTemplate;

/**
 * Manages persistent user preferences across app sessions and note editors:
 * - Active drawing tool
 * - Stroke width / thickness
 * - Active ink color
 * - Pressure dynamics sensitivity
 * - Stroke stabilization smoothing
 * - Straight-line assist snapping
 * - Stylus-only palm rejection mode
 * - Default page background color
 * - Default paper template
 * - Shelf grid/list view mode
 * - Active folder filter
 */
public class AppSettings {
    private static final String PREFS_NAME = "opennotes_user_settings";

    private static final String KEY_LAST_TOOL = "pref_last_tool";
    private static final String KEY_STROKE_WIDTH = "pref_stroke_width";
    private static final String KEY_INK_COLOR = "pref_ink_color";
    private static final String KEY_PRESSURE_SENSITIVITY = "pref_pressure_sensitivity";
    private static final String KEY_STABILIZATION = "pref_stabilization";
    private static final String KEY_LASER_DURATION_MS = "pref_laser_duration_ms";
    private static final String KEY_STRAIGHT_LINE_ASSIST = "pref_straight_line_assist";
    private static final String KEY_STYLUS_ONLY = "pref_stylus_only";
    private static final String KEY_FINGER_MOVE_CANVAS = "pref_finger_move_canvas";
    private static final String KEY_PAGE_BG_COLOR = "pref_default_page_bg_color";
    private static final String KEY_PAPER_TEMPLATE = "pref_default_paper_template";
    private static final String KEY_GRID_VIEW = "pref_is_grid_view";
    private static final String KEY_ACTIVE_FOLDER = "pref_active_folder";

    private static AppSettings sInstance;
    private final SharedPreferences prefs;

    private AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // The original release defaulted real-time conversion off. Enable it once for
        // existing installs so the handwriting conversion feature works after upgrade.
        if (prefs.getInt("pref_auto_convert_default_migration", 0) < 1) {
            prefs.edit()
                    .putBoolean(KEY_AUTO_CONVERT_TEXT, true)
                    .putInt("pref_auto_convert_default_migration", 1)
                    .apply();
        }
    }

    public static synchronized AppSettings getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AppSettings(context);
        }
        return sInstance;
    }

    // Last Tool
    public DrawingTool getLastTool() {
        String name = prefs.getString(KEY_LAST_TOOL, DrawingTool.FOUNTAIN.name());
        try {
            DrawingTool tool = DrawingTool.valueOf(name);
            if (tool == DrawingTool.HAND) return DrawingTool.FOUNTAIN;
            return tool;
        } catch (Exception e) {
            return DrawingTool.FOUNTAIN;
        }
    }

    public void setLastTool(DrawingTool tool) {
        if (tool != null) {
            prefs.edit().putString(KEY_LAST_TOOL, tool.name()).apply();
        }
    }

    // Stroke Width
    public float getStrokeWidth() {
        return prefs.getFloat(KEY_STROKE_WIDTH, 3.0f);
    }

    public void setStrokeWidth(float width) {
        prefs.edit().putFloat(KEY_STROKE_WIDTH, width).apply();
    }

    // Ink Color
    public int getInkColor() {
        return prefs.getInt(KEY_INK_COLOR, 0xFFFFFFFF);
    }

    public void setInkColor(int color) {
        prefs.edit().putInt(KEY_INK_COLOR, color).apply();
    }

    // Pressure Sensitivity
    public float getPressureSensitivity() {
        return prefs.getFloat(KEY_PRESSURE_SENSITIVITY, 0.5f);
    }

    public void setPressureSensitivity(float value) {
        prefs.edit().putFloat(KEY_PRESSURE_SENSITIVITY, value).apply();
    }

    // Stroke Stabilization
    public float getStabilization() {
        return prefs.getFloat(KEY_STABILIZATION, 0.3f);
    }

    public void setStabilization(float value) {
        prefs.edit().putFloat(KEY_STABILIZATION, value).apply();
    }

    public long getLaserDurationMs() {
        return prefs.getLong(KEY_LASER_DURATION_MS, 1500L);
    }

    public void setLaserDurationMs(long durationMs) {
        prefs.edit().putLong(KEY_LASER_DURATION_MS, Math.max(400L, Math.min(5000L, durationMs))).apply();
    }

    // Straight Line Assist
    public boolean isStraightLineAssist() {
        return prefs.getBoolean(KEY_STRAIGHT_LINE_ASSIST, false);
    }

    public void setStraightLineAssist(boolean enabled) {
        prefs.edit().putBoolean(KEY_STRAIGHT_LINE_ASSIST, enabled).apply();
    }

    // Stylus Only (Palm Rejection)
    public boolean isStylusOnly() {
        return prefs.getBoolean(KEY_STYLUS_ONLY, false);
    }

    public void setStylusOnly(boolean enabled) {
        prefs.edit().putBoolean(KEY_STYLUS_ONLY, enabled).apply();
    }

    // Single Finger Move Canvas
    public boolean isFingerMoveCanvas() {
        return prefs.getBoolean(KEY_FINGER_MOVE_CANVAS, false);
    }

    public void setFingerMoveCanvas(boolean enabled) {
        prefs.edit().putBoolean(KEY_FINGER_MOVE_CANVAS, enabled).apply();
    }

    // Default Page Background Color
    public int getDefaultPageBgColor() {
        return prefs.getInt(KEY_PAGE_BG_COLOR, 0xFF121214);
    }

    public void setDefaultPageBgColor(int color) {
        prefs.edit().putInt(KEY_PAGE_BG_COLOR, color).apply();
    }

    // Default Paper Template (remembers last created notebook template, defaults to BLANK)
    public PaperTemplate getDefaultPaperTemplate() {
        String name = prefs.getString(KEY_PAPER_TEMPLATE, PaperTemplate.BLANK.name());
        try {
            return PaperTemplate.valueOf(name);
        } catch (Exception e) {
            return PaperTemplate.BLANK;
        }
    }

    public void setDefaultPaperTemplate(PaperTemplate template) {
        if (template != null) {
            prefs.edit().putString(KEY_PAPER_TEMPLATE, template.name()).apply();
        }
    }

    // Grid View vs List View
    public boolean isGridView() {
        return prefs.getBoolean(KEY_GRID_VIEW, true);
    }

    public void setGridView(boolean isGrid) {
        prefs.edit().putBoolean(KEY_GRID_VIEW, isGrid).apply();
    }

    // Active Folder Filter
    public String getActiveFolder() {
        return prefs.getString(KEY_ACTIVE_FOLDER, "All");
    }

    public void setActiveFolder(String folder) {
        prefs.edit().putString(KEY_ACTIVE_FOLDER, folder != null ? folder : "All").apply();
    }

    // Auto-Beautification Settings (English, Hindi, Marathi)
    private static final String KEY_AUTO_BEAUTIFY_ENABLED = "pref_auto_beautify_enabled";
    private static final String KEY_BEAUTIFY_LANGUAGE = "pref_beautify_language";
    private static final String KEY_BEAUTIFY_FONT = "pref_beautify_font";
    private static final String KEY_SHIROREKHA_SNAP = "pref_shirorekha_snap";
    private static final String KEY_BEAUTIFY_SMOOTHING = "pref_beautify_smoothing";

    public boolean isAutoBeautifyEnabled() {
        return prefs.getBoolean(KEY_AUTO_BEAUTIFY_ENABLED, true);
    }

    public void setAutoBeautifyEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_BEAUTIFY_ENABLED, enabled).apply();
    }

    public String getBeautifyLanguage() {
        return prefs.getString(KEY_BEAUTIFY_LANGUAGE, "MARATHI");
    }

    public void setBeautifyLanguage(String language) {
        prefs.edit().putString(KEY_BEAUTIFY_LANGUAGE, language).apply();
    }

    public String getBeautifyFont() {
        return prefs.getString(KEY_BEAUTIFY_FONT, "KALAM");
    }

    public void setBeautifyFont(String font) {
        prefs.edit().putString(KEY_BEAUTIFY_FONT, font).apply();
    }

    public boolean isShirorekhaSnapEnabled() {
        return prefs.getBoolean(KEY_SHIROREKHA_SNAP, true);
    }

    public void setShirorekhaSnapEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHIROREKHA_SNAP, enabled).apply();
    }

    public float getBeautifySmoothing() {
        return prefs.getFloat(KEY_BEAUTIFY_SMOOTHING, 0.65f);
    }

    public void setBeautifySmoothing(float smoothing) {
        prefs.edit().putFloat(KEY_BEAUTIFY_SMOOTHING, smoothing).apply();
    }

    private static final String KEY_AUTO_CONVERT_TEXT = "pref_auto_convert_text";

    public boolean isAutoConvertTextEnabled() {
        return prefs.getBoolean(KEY_AUTO_CONVERT_TEXT, false);
    }

    public void setAutoConvertTextEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CONVERT_TEXT, enabled).apply();
    }

    public boolean hasAcceptedHandwritingModelDownload(String languageTag) {
        return prefs.getBoolean("pref_model_download_consent_" + languageTag, false);
    }

    public void setAcceptedHandwritingModelDownload(String languageTag, boolean accepted) {
        prefs.edit().putBoolean("pref_model_download_consent_" + languageTag, accepted).apply();
    }

    public boolean hasShownHandwritingModelSetup() {
        return prefs.getBoolean("pref_handwriting_model_setup_shown", false);
    }

    public void setShownHandwritingModelSetup() {
        prefs.edit().putBoolean("pref_handwriting_model_setup_shown", true).apply();
    }

    public boolean hasDismissedHandwritingModelSetupToday() {
        long lastDismissed = prefs.getLong("pref_handwriting_model_dismissed_time", 0L);
        return (System.currentTimeMillis() - lastDismissed) < (24 * 60 * 60 * 1000L);
    }

    public void setDismissedHandwritingModelSetupToday() {
        prefs.edit().putLong("pref_handwriting_model_dismissed_time", System.currentTimeMillis()).apply();
    }

    public void clearDismissedHandwritingModelSetup() {
        prefs.edit().remove("pref_handwriting_model_dismissed_time").apply();
    }

    // Page Swipe Gesture Preferences (for imported PDFs and multi-page notebooks)
    private static final String KEY_SWIPE_PAGE_ENABLED = "pref_swipe_page_enabled";
    private static final String KEY_SWIPE_TWO_FINGER = "pref_swipe_two_finger";
    private static final String KEY_SWIPE_SINGLE_FINGER = "pref_swipe_single_finger";
    private static final String KEY_SWIPE_EDGE_ENABLED = "pref_swipe_edge_enabled";
    private static final String KEY_SWIPE_SENSITIVITY = "pref_swipe_sensitivity";

    public boolean isSwipePageEnabled() {
        return prefs.getBoolean(KEY_SWIPE_PAGE_ENABLED, true);
    }

    public void setSwipePageEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SWIPE_PAGE_ENABLED, enabled).apply();
    }

    public boolean isSwipeTwoFingerEnabled() {
        return prefs.getBoolean(KEY_SWIPE_TWO_FINGER, true);
    }

    public void setSwipeTwoFingerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SWIPE_TWO_FINGER, enabled).apply();
    }

    public boolean isSwipeSingleFingerEnabled() {
        return prefs.getBoolean(KEY_SWIPE_SINGLE_FINGER, true);
    }

    public void setSwipeSingleFingerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SWIPE_SINGLE_FINGER, enabled).apply();
    }

    public boolean isSwipeEdgeEnabled() {
        return prefs.getBoolean(KEY_SWIPE_EDGE_ENABLED, true);
    }

    public void setSwipeEdgeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SWIPE_EDGE_ENABLED, enabled).apply();
    }

    public String getSwipeSensitivity() {
        return prefs.getString(KEY_SWIPE_SENSITIVITY, "NORMAL");
    }

    public void setSwipeSensitivity(String sensitivity) {
        prefs.edit().putString(KEY_SWIPE_SENSITIVITY, sensitivity != null ? sensitivity : "NORMAL").apply();
    }

    // Forced Super AMOLED Dark Mode for imported PDFs
    private static final String KEY_PDF_AMOLED_DARK_MODE = "pref_pdf_amoled_dark_mode";

    public boolean isPdfAmoledDarkMode() {
        return prefs.getBoolean(KEY_PDF_AMOLED_DARK_MODE, false);
    }

    public void setPdfAmoledDarkMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_PDF_AMOLED_DARK_MODE, enabled).apply();
    }

    // Search & Assist Trigger Target (Circle to Search vs Gemini vs Google Lens)
    private static final String KEY_SEARCH_ASSIST_TARGET = "pref_search_assist_target";
    public static final String SEARCH_TARGET_CIRCLE_TO_SEARCH = "CIRCLE_TO_SEARCH";
    public static final String SEARCH_TARGET_GEMINI = "GEMINI";
    public static final String SEARCH_TARGET_LENS = "GOOGLE_LENS";

    public String getSearchAssistTarget() {
        return prefs.getString(KEY_SEARCH_ASSIST_TARGET, SEARCH_TARGET_CIRCLE_TO_SEARCH);
    }

    public void setSearchAssistTarget(String target) {
        prefs.edit().putString(KEY_SEARCH_ASSIST_TARGET, target != null ? target : SEARCH_TARGET_CIRCLE_TO_SEARCH).apply();
    }
}
