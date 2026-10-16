package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CanvasView extends View {

    public interface OnCanvasChangeListener {
        void onCanvasChanged();
        void onRequestTextInput(float x, float y);
        void onScrollChanged(float scrollY, float maxScrollY);
    }

    public interface OnZoomChangeListener {
        void onZoomChanged(float zoomPercent);
    }

    public interface OnLassoSelectionListener {
        void onSelectionChanged(boolean hasSelection, RectF screenBounds);
    }

    private Page currentPage;
    private PaperTemplate paperTemplate = PaperTemplate.RULED;
    private int pageBackgroundColor = 0xFF121214; // Default sleek dark note background matching video
    private boolean isInfinite = true;
    private boolean isStylusOnly = false;
    private boolean isViewportLocked = false;
    private float contentHeight = 0f;
    private float viewportOffsetY = 0f;

    // Zoom & Pan transformation (20% to 1600%)
    private float scaleFactor = 1.0f;
    private float panOffsetX = 0f;
    private float panOffsetY = 0f;
    private ScaleGestureDetector scaleDetector;

    private DrawingTool currentTool = DrawingTool.FOUNTAIN;
    private int currentColor = 0xFFFFFFFF; // default white ink on dark canvas
    private float currentStrokeWidth = 6.0f;
    private float pressureSensitivity = 0.8f;
    private float strokeStabilization = 0.35f;
    private boolean straightLineAssist = false;
    private boolean isAutoBeautifyEnabled = true;
    private String beautifyLanguage = "MARATHI";
    private String beautifyFontKey = "KALAM";
    private boolean isShirorekhaSnapEnabled = true;
    private float beautifySmoothing = 0.65f;
    private float smoothedX = 0f;
    private float smoothedY = 0f;
    private long strokeStartTime = 0;
    private ShapeItem.Type currentShapeType = ShapeItem.Type.RECTANGLE;

    // Laser pointer presentation mode (ephemeral glowing trail)
    public static class LaserPoint {
        public float x, y;
        public long timestamp;
        public LaserPoint(float x, float y, long timestamp) {
            this.x = x;
            this.y = y;
            this.timestamp = timestamp;
        }
    }
    private final List<LaserPoint> laserTrail = new ArrayList<>();
    private Paint laserGlowPaint;
    private Paint laserCorePaint;
    private Paint laserTipPaint;

    // Drawing state
    private Stroke activeStroke;
    private float lastTouchX, lastTouchY;

    // Pan / Scroll physics
    private OverScroller scroller;
    private VelocityTracker velocityTracker;
    private boolean isPanning = false;
    private float panStartY = 0f;
    private float panInitialOffsetY = 0f;
    private float twoFingerStartX = 0f, twoFingerStartY = 0f;
    private float initialPanOffsetX = 0f, initialPanOffsetY = 0f;

    // Shape drawing state
    private boolean isDrawingShape = false;
    private float shapeStartX, shapeStartY, shapeCurrentX, shapeCurrentY;

    // Lasso selection state
    private final Path lassoPath = new Path();
    private final List<DrawingPoint> lassoPoints = new ArrayList<>();
    private boolean isDrawingLasso = false;
    private boolean hasLassoSelection = false;
    private final List<Stroke> selectedStrokes = new ArrayList<>();
    private final List<ShapeItem> selectedShapes = new ArrayList<>();
    private final RectF selectionBounds = new RectF();
    private boolean isMovingSelection = false;
    private float lassoMoveStartX, lassoMoveStartY;

    // Clipboard for Cut / Copy / Paste
    private final List<Stroke> clipboardStrokes = new ArrayList<>();
    private final List<ShapeItem> clipboardShapes = new ArrayList<>();

    // Scrollbar indicator
    private final Paint scrollbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Undo / Redo history
    private static class ActionRecord {
        enum Type { ADD_STROKE, REMOVE_STROKE, ADD_SHAPE, REMOVE_SHAPE, ADD_TEXT, MOVE_SELECTION, CLEAR_ALL, STYLE_CHANGE, CONVERT_TO_TEXT }
        Type type;
        Stroke stroke;
        ShapeItem shape;
        TextItem text;
        float moveDx, moveDy;
        List<Stroke> movedStrokes;
        List<ShapeItem> movedShapes;
        List<Stroke> prevStrokes;
        List<ShapeItem> prevShapes;
        List<TextItem> prevTexts;
    }

    private final Deque<ActionRecord> undoStack = new ArrayDeque<>();
    private final Deque<ActionRecord> redoStack = new ArrayDeque<>();

    // Paints
    private Paint strokePaint;
    private Paint fountainPaint;
    private Paint brushPaint;
    private Paint highlighterPaint;
    private Paint templateLinePaint;
    private Paint templateMarginPaint;
    private Paint templateDotPaint;
    private Paint textPaint;
    private Paint previewShapePaint;
    private Paint lassoPaint;
    private Paint selectionBoxPaint;
    private Paint selectionHandlePaint;

    // Zero-lag high performance rendering buffers
    private final RectF visibleViewport = new RectF();
    private final Path reusableSegPath = new Path();

    private OnCanvasChangeListener changeListener;
    private OnZoomChangeListener zoomListener;
    private OnLassoSelectionListener lassoSelectionListener;

    public CanvasView(Context context) {
        super(context);
        init();
    }

    public CanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        scroller = new OverScroller(getContext());

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (isViewportLocked) return true;
                float factor = detector.getScaleFactor();
                float prevScale = scaleFactor;
                scaleFactor *= factor;
                // Clamp between 20% and 1600% (matching StarNote tablet app in vdo.mp4)
                scaleFactor = Math.max(0.20f, Math.min(16.0f, scaleFactor));

                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                panOffsetX += (focusX - panOffsetX) * (1f - scaleFactor / prevScale);
                panOffsetY += (focusY - panOffsetY) * (1f - scaleFactor / prevScale);

                invalidate();
                notifyZoomChange();
                notifyLassoSelection();
                return true;
            }
        });

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        fountainPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        fountainPaint.setStyle(Paint.Style.STROKE);
        fountainPaint.setStrokeJoin(Paint.Join.ROUND);
        fountainPaint.setStrokeCap(Paint.Cap.ROUND);

        brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        brushPaint.setStyle(Paint.Style.STROKE);
        brushPaint.setStrokeJoin(Paint.Join.ROUND);
        brushPaint.setStrokeCap(Paint.Cap.ROUND);

        highlighterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        highlighterPaint.setStyle(Paint.Style.STROKE);
        highlighterPaint.setStrokeJoin(Paint.Join.BEVEL);
        highlighterPaint.setStrokeCap(Paint.Cap.SQUARE);

        templateLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        templateLinePaint.setColor(0x2EFFFFFF);
        templateLinePaint.setStrokeWidth(1.5f);

        templateMarginPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        templateMarginPaint.setColor(0x66F87171);
        templateMarginPaint.setStrokeWidth(2.0f);

        templateDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        templateDotPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFF1F5F9);
        textPaint.setTextSize(40f);

        previewShapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        previewShapePaint.setStyle(Paint.Style.STROKE);
        previewShapePaint.setStrokeCap(Paint.Cap.ROUND);
        previewShapePaint.setStrokeJoin(Paint.Join.ROUND);

        scrollbarPaint.setColor(0x6638BDF8);
        scrollbarPaint.setStyle(Paint.Style.FILL);

        lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setColor(0xFF38BDF8);
        lassoPaint.setStrokeWidth(2.5f);
        lassoPaint.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));

        selectionBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionBoxPaint.setStyle(Paint.Style.STROKE);
        selectionBoxPaint.setColor(0xFF38BDF8);
        selectionBoxPaint.setStrokeWidth(2.0f);
        selectionBoxPaint.setPathEffect(new DashPathEffect(new float[]{10, 6}, 0));

        selectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionHandlePaint.setStyle(Paint.Style.FILL);
        selectionHandlePaint.setColor(0xFF38BDF8);

        // Neon Laser Presentation Tool
        laserGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        laserGlowPaint.setStyle(Paint.Style.STROKE);
        laserGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        laserGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        laserGlowPaint.setColor(0xFFFF2255);

        laserCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        laserCorePaint.setStyle(Paint.Style.STROKE);
        laserCorePaint.setStrokeCap(Paint.Cap.ROUND);
        laserCorePaint.setStrokeJoin(Paint.Join.ROUND);
        laserCorePaint.setColor(0xFFFFFFFF);

        laserTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        laserTipPaint.setStyle(Paint.Style.FILL);
        laserTipPaint.setColor(0xFFFF2255);
    }

    public void setOnCanvasChangeListener(OnCanvasChangeListener listener) {
        this.changeListener = listener;
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomListener = listener;
    }

    public void setOnLassoSelectionListener(OnLassoSelectionListener listener) {
        this.lassoSelectionListener = listener;
    }

    public void setPage(Page page) {
        this.currentPage = page;
        if (page != null) {
            this.paperTemplate = page.template;
            this.pageBackgroundColor = page.backgroundColor != 0 ? page.backgroundColor : 0xFF121214;
            this.isInfinite = page.isInfinite;
            this.contentHeight = page.canvasHeight > 0 ? page.canvasHeight : 0f;

            // Auto-contrast ink check: guarantee drawing is instantly visible
            if (isColorDark(pageBackgroundColor) && isColorDark(currentColor)) {
                currentColor = 0xFFFFFFFF; // White ink on dark page
            } else if (!isColorDark(pageBackgroundColor) && !isColorDark(currentColor)) {
                currentColor = 0xFF1E293B; // Dark ink on light page
            }
        }
        viewportOffsetY = 0f;
        scaleFactor = 1.0f;
        panOffsetX = 0f;
        panOffsetY = 0f;
        clearSelection();
        cancelPendingAutoConversion();
        undoStack.clear();
        redoStack.clear();
        invalidate();
        notifyZoomChange();
    }

    public Page getPage() {
        return currentPage;
    }

    public void setPageBackgroundColor(int color) {
        this.pageBackgroundColor = color;
        if (currentPage != null) {
            currentPage.backgroundColor = color;
        }
        // Auto-contrast ink check: Ensure active drawing ink is visible against new background
        boolean isDark = isColorDark(color);
        if (isDark && isColorDark(currentColor)) {
            setColor(0xFFFFFFFF);
        } else if (!isDark && !isColorDark(currentColor)) {
            setColor(0xFF1E293B);
        }
        invalidate();
        notifyChange();
    }

    public int getPageBackgroundColor() {
        return pageBackgroundColor;
    }

    public void setInfinite(boolean infinite) {
        this.isInfinite = infinite;
        if (currentPage != null) {
            currentPage.isInfinite = infinite;
        }
        if (!infinite) {
            viewportOffsetY = 0;
        } else {
            if (contentHeight < getHeight()) {
                contentHeight = getHeight() * 2f;
            }
        }
        invalidate();
        notifyChange();
    }

    public boolean isInfinite() {
        return isInfinite;
    }

    public void setStylusOnly(boolean stylusOnly) {
        this.isStylusOnly = stylusOnly;
    }

    public boolean isStylusOnly() {
        return isStylusOnly;
    }

    public void setViewportLocked(boolean locked) {
        this.isViewportLocked = locked;
    }

    public boolean isViewportLocked() {
        return isViewportLocked;
    }

    public void setZoom(float zoomPercent) {
        this.scaleFactor = Math.max(0.20f, Math.min(16.0f, zoomPercent / 100f));
        invalidate();
        notifyZoomChange();
        notifyLassoSelection();
    }

    public void resetZoom() {
        float startScale = scaleFactor;
        float startPanX = panOffsetX;
        float startPanY = panOffsetY;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(280);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        anim.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            scaleFactor = startScale + (1.0f - startScale) * fraction;
            panOffsetX = startPanX + (0f - startPanX) * fraction;
            panOffsetY = startPanY + (0f - startPanY) * fraction;
            invalidate();
            notifyZoomChange();
            notifyLassoSelection();
        });
        anim.start();
    }

    public void setStrokeStabilization(float val) {
        this.strokeStabilization = Math.max(0f, Math.min(1.0f, val));
    }

    public float getStrokeStabilization() {
        return strokeStabilization;
    }

    public void setStraightLineAssist(boolean assist) {
        this.straightLineAssist = assist;
    }

    public boolean isStraightLineAssist() {
        return straightLineAssist;
    }

    public void setAutoBeautifyEnabled(boolean enabled) {
        this.isAutoBeautifyEnabled = enabled;
    }

    public boolean isAutoBeautifyEnabled() {
        return isAutoBeautifyEnabled;
    }

    public void setBeautifyLanguage(String language) {
        this.beautifyLanguage = (language != null) ? language : "MARATHI";
    }

    public String getBeautifyLanguage() {
        return beautifyLanguage;
    }

    public void setBeautifyFontKey(String fontKey) {
        this.beautifyFontKey = (fontKey != null) ? fontKey : "KALAM";
    }

    public String getBeautifyFontKey() {
        return beautifyFontKey;
    }

    public void setShirorekhaSnapEnabled(boolean enabled) {
        this.isShirorekhaSnapEnabled = enabled;
    }

    public boolean isShirorekhaSnapEnabled() {
        return isShirorekhaSnapEnabled;
    }

    public void setBeautifySmoothing(float smoothing) {
        this.beautifySmoothing = Math.max(0.1f, Math.min(1.0f, smoothing));
    }

    public float getBeautifySmoothing() {
        return beautifySmoothing;
    }

    public void beautifySelection() {
        if (!hasSelection() || currentPage == null) return;
        for (Stroke s : selectedStrokes) {
            applyAutoBeautification(s);
        }
        invalidate();
        notifyChange();
    }

    public void applyAutoBeautification(Stroke stroke) {
        if (stroke == null || stroke.points.size() < 3) return;

        // 1. Devanagari Shirorekha (Top Line) Snapping for Hindi / Marathi
        if (isShirorekhaSnapEnabled && ("MARATHI".equalsIgnoreCase(beautifyLanguage) || "HINDI".equalsIgnoreCase(beautifyLanguage))) {
            DrawingPoint firstPt = stroke.points.get(0);
            DrawingPoint lastPt = stroke.points.get(stroke.points.size() - 1);
            float dx = Math.abs(lastPt.x - firstPt.x);
            float dy = Math.abs(lastPt.y - firstPt.y);
            float totalDist = (float) Math.hypot(dx, dy);

            if (totalDist > 28f && (dy / Math.max(1f, dx)) < 0.24f) {
                float avgY = 0f;
                for (DrawingPoint pt : stroke.points) avgY += pt.y;
                avgY /= stroke.points.size();

                boolean isFlat = true;
                for (DrawingPoint pt : stroke.points) {
                    if (Math.abs(pt.y - avgY) > 22f) {
                        isFlat = false;
                        break;
                    }
                }

                if (isFlat) {
                    for (DrawingPoint pt : stroke.points) {
                        pt.y = avgY;
                    }
                    stroke.markDirty();
                    return;
                }
            }
        }

        // 2. Chaikin's Corner-Cutting Algorithm for Calligraphic Curve Smoothing
        int iterations = (beautifySmoothing > 0.5f) ? 2 : 1;
        List<DrawingPoint> smoothed = new ArrayList<>(stroke.points);

        for (int it = 0; it < iterations; it++) {
            if (smoothed.size() < 3) break;
            List<DrawingPoint> nextPass = new ArrayList<>();
            nextPass.add(smoothed.get(0));

            for (int i = 0; i < smoothed.size() - 1; i++) {
                DrawingPoint p0 = smoothed.get(i);
                DrawingPoint p1 = smoothed.get(i + 1);

                float qx = 0.75f * p0.x + 0.25f * p1.x;
                float qy = 0.75f * p0.y + 0.25f * p1.y;
                float qp = 0.75f * p0.pressure + 0.25f * p1.pressure;

                float rx = 0.25f * p0.x + 0.75f * p1.x;
                float ry = 0.25f * p0.y + 0.75f * p1.y;
                float rp = 0.25f * p0.pressure + 0.75f * p1.pressure;

                nextPass.add(new DrawingPoint(qx, qy, qp));
                nextPass.add(new DrawingPoint(rx, ry, rp));
            }

            nextPass.add(smoothed.get(smoothed.size() - 1));
            smoothed = nextPass;
        }

        // 3. Prune tiny accidental stylus-lift hook artifact if detected
        int ptCount = smoothed.size();
        if (ptCount >= 4) {
            DrawingPoint pLast = smoothed.get(ptCount - 1);
            DrawingPoint pPrev = smoothed.get(ptCount - 2);
            DrawingPoint pPrev2 = smoothed.get(ptCount - 3);

            float dLast = (float) Math.hypot(pLast.x - pPrev.x, pLast.y - pPrev.y);
            float dot = (pLast.x - pPrev.x) * (pPrev.x - pPrev2.x) + (pLast.y - pPrev.y) * (pPrev.y - pPrev2.y);
            // Sharp angle reversal with small length -> stylus lift hook
            if (dot < 0 && dLast < 14f) {
                smoothed.remove(ptCount - 1);
            }
        }

        // 4. Calligraphic entry and exit tapering (smooth gentle modulation)
        int count = smoothed.size();
        if (count > 6) {
            for (int i = 0; i < Math.min(3, count / 3); i++) {
                float taper = 0.5f + (i * 0.18f);
                smoothed.get(i).pressure *= taper;
                smoothed.get(count - 1 - i).pressure *= taper;
            }
        }

        stroke.points.clear();
        stroke.points.addAll(smoothed);
        stroke.markDirty();
    }

    private boolean isAutoConvertTextEnabled = false;
    private final List<Stroke> pendingAutoConvertStrokes = new ArrayList<>();
    private final Handler autoConvertHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoConvertRunnable = () -> {
        if (!isAutoConvertTextEnabled || pendingAutoConvertStrokes.isEmpty()) return;
        List<Stroke> toConvert = new ArrayList<>(pendingAutoConvertStrokes);
        pendingAutoConvertStrokes.clear();
        if (currentPage != null) {
            toConvert.retainAll(currentPage.strokes);
            if (!toConvert.isEmpty()) {
                convertStrokesToText(toConvert);
            }
        }
    };

    public void cancelPendingAutoConversion() {
        autoConvertHandler.removeCallbacks(autoConvertRunnable);
        pendingAutoConvertStrokes.clear();
    }

    public void setAutoConvertTextEnabled(boolean enabled) {
        this.isAutoConvertTextEnabled = enabled;
        if (!enabled) {
            cancelPendingAutoConversion();
        }
    }

    public boolean isAutoConvertTextEnabled() {
        return isAutoConvertTextEnabled;
    }

    public void convertSelectionToText() {
        if (!hasSelection() || selectedStrokes.isEmpty()) return;
        convertStrokesToText(new ArrayList<>(selectedStrokes));
        clearSelection();
    }

    public void convertStrokesToText(List<Stroke> strokesToConvert) {
        if (currentPage == null || strokesToConvert == null || strokesToConvert.isEmpty()) return;
        final List<Stroke> requestedStrokes = new ArrayList<>(strokesToConvert);
        final Page requestedPage = currentPage;
        final int textColor = currentColor;
        org.opennotes.app.util.HandwritingRecognitionEngine.recognizeStrokes(
                getContext(), requestedStrokes, beautifyLanguage,
                new org.opennotes.app.util.HandwritingRecognitionEngine.Callback() {
                    @Override
                    public void onSuccess(org.opennotes.app.util.HandwritingRecognitionEngine.RecognitionResult res) {
                        if (currentPage != requestedPage || res == null || res.text.trim().isEmpty()) return;
                        List<Stroke> stillPresent = new ArrayList<>();
                        for (Stroke stroke : requestedStrokes) {
                            if (requestedPage.strokes.contains(stroke)) stillPresent.add(stroke);
                        }
                        if (stillPresent.isEmpty()) return;

                        String fontKey = beautifyFontKey;
                        if (fontKey == null || fontKey.isEmpty()) {
                            fontKey = "ENGLISH".equalsIgnoreCase(res.language)
                                    ? org.opennotes.app.util.TypefaceManager.FONT_CAVEAT
                                    : org.opennotes.app.util.TypefaceManager.FONT_KALAM;
                        }
                        requestedPage.strokes.removeAll(stillPresent);
                        TextItem textItem = new TextItem(res.text, res.bounds.left,
                                res.bounds.bottom - 4f, textColor, res.fontSize, fontKey);
                        requestedPage.texts.add(textItem);
                        ActionRecord rec = new ActionRecord();
                        rec.type = ActionRecord.Type.CONVERT_TO_TEXT;
                        rec.prevStrokes = stillPresent;
                        rec.text = textItem;
                        undoStack.push(rec);
                        redoStack.clear();
                        invalidate();
                        notifyChange();
                    }

                    @Override
                    public void onFailure(Exception error) {
                        // Keep the user's ink unchanged if model download or recognition fails.
                    }
                });
    }

    public float getZoomPercent() {
        return scaleFactor * 100f;
    }

    public void setPressureSensitivity(float sensitivity) {
        this.pressureSensitivity = Math.max(0.1f, Math.min(1.0f, sensitivity));
    }

    public float getPressureSensitivity() {
        return pressureSensitivity;
    }

    public float getContentHeight() {
        return Math.max(getHeight(), contentHeight);
    }

    public void setPaperTemplate(PaperTemplate template) {
        this.paperTemplate = template;
        if (currentPage != null) {
            currentPage.template = template;
        }
        invalidate();
        notifyChange();
    }

    public PaperTemplate getPaperTemplate() {
        return paperTemplate;
    }

    public void setTool(DrawingTool tool) {
        this.currentTool = tool;
        if (tool != DrawingTool.LASSO) {
            clearSelection();
        }
    }

    public DrawingTool getTool() {
        return currentTool;
    }

    public void setColor(int color) {
        this.currentColor = color;
    }

    public int getColor() {
        return currentColor;
    }

    public void setStrokeWidth(float width) {
        this.currentStrokeWidth = width;
    }

    public float getStrokeWidth() {
        return currentStrokeWidth;
    }

    public void setShapeType(ShapeItem.Type type) {
        this.currentShapeType = type;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (undoStack.isEmpty() || currentPage == null) return;
        ActionRecord rec = undoStack.pop();
        switch (rec.type) {
            case ADD_STROKE:
                currentPage.strokes.remove(rec.stroke);
                break;
            case REMOVE_STROKE:
                currentPage.strokes.add(rec.stroke);
                break;
            case ADD_SHAPE:
                currentPage.shapes.remove(rec.shape);
                break;
            case REMOVE_SHAPE:
                currentPage.shapes.add(rec.shape);
                break;
            case ADD_TEXT:
                currentPage.texts.remove(rec.text);
                break;
            case MOVE_SELECTION:
                translateItems(rec.movedStrokes, rec.movedShapes, -rec.moveDx, -rec.moveDy);
                break;
            case CLEAR_ALL:
                currentPage.strokes = new ArrayList<>(rec.prevStrokes);
                currentPage.shapes = new ArrayList<>(rec.prevShapes);
                currentPage.texts = new ArrayList<>(rec.prevTexts);
                break;
            case CONVERT_TO_TEXT:
                if (rec.text != null) {
                    currentPage.texts.remove(rec.text);
                }
                if (rec.prevStrokes != null) {
                    currentPage.strokes.addAll(rec.prevStrokes);
                }
                break;
            default:
                break;
        }
        clearSelection();
        redoStack.push(rec);
        invalidate();
        notifyChange();
    }

    public void redo() {
        if (redoStack.isEmpty() || currentPage == null) return;
        ActionRecord rec = redoStack.pop();
        switch (rec.type) {
            case ADD_STROKE:
                currentPage.strokes.add(rec.stroke);
                break;
            case REMOVE_STROKE:
                currentPage.strokes.remove(rec.stroke);
                break;
            case ADD_SHAPE:
                currentPage.shapes.add(rec.shape);
                break;
            case REMOVE_SHAPE:
                currentPage.shapes.remove(rec.shape);
                break;
            case ADD_TEXT:
                currentPage.texts.add(rec.text);
                break;
            case MOVE_SELECTION:
                translateItems(rec.movedStrokes, rec.movedShapes, rec.moveDx, rec.moveDy);
                break;
            case CLEAR_ALL:
                currentPage.strokes.clear();
                currentPage.shapes.clear();
                currentPage.texts.clear();
                break;
            case CONVERT_TO_TEXT:
                if (rec.prevStrokes != null) {
                    currentPage.strokes.removeAll(rec.prevStrokes);
                }
                if (rec.text != null) {
                    currentPage.texts.add(rec.text);
                }
                break;
            default:
                break;
        }
        clearSelection();
        undoStack.push(rec);
        invalidate();
        notifyChange();
    }

    public void clearAll() {
        if (currentPage == null) return;
        if (currentPage.strokes.isEmpty() && currentPage.shapes.isEmpty() && currentPage.texts.isEmpty()) return;

        ActionRecord rec = new ActionRecord();
        rec.type = ActionRecord.Type.CLEAR_ALL;
        rec.prevStrokes = new ArrayList<>(currentPage.strokes);
        rec.prevShapes = new ArrayList<>(currentPage.shapes);
        rec.prevTexts = new ArrayList<>(currentPage.texts);

        currentPage.strokes.clear();
        currentPage.shapes.clear();
        currentPage.texts.clear();

        undoStack.push(rec);
        redoStack.clear();
        clearSelection();
        invalidate();
        notifyChange();
    }

    public void addTextItem(String text, float x, float y, String fontKey) {
        if (currentPage == null || text == null || text.trim().isEmpty()) return;
        float actualX = (x - panOffsetX) / scaleFactor;
        float actualY = (y - panOffsetY) / scaleFactor + viewportOffsetY;

        String fKey = (fontKey != null && !fontKey.isEmpty()) ? fontKey : beautifyFontKey;
        TextItem item = new TextItem(text, actualX, actualY, currentColor, 44f, fKey);
        currentPage.texts.add(item);

        ActionRecord rec = new ActionRecord();
        rec.type = ActionRecord.Type.ADD_TEXT;
        rec.text = item;
        undoStack.push(rec);
        redoStack.clear();

        ensureContentHeight(actualY + 100);
        invalidate();
        notifyChange();
    }

    public void addTextItem(String text, float x, float y) {
        addTextItem(text, x, y, beautifyFontKey);
    }

    private void ensureContentHeight(float targetY) {
        if (isInfinite) {
            float minHeight = targetY + 800f;
            if (minHeight > contentHeight) {
                contentHeight = minHeight;
                if (currentPage != null) {
                    currentPage.canvasHeight = contentHeight;
                }
            }
        }
    }

    public void scrollToTop() {
        viewportOffsetY = 0;
        invalidate();
        if (changeListener != null) {
            changeListener.onScrollChanged(0, getMaxScrollY());
        }
    }

    private float getMaxScrollY() {
        if (!isInfinite) return 0;
        return Math.max(0, getContentHeight() - getHeight());
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (scroller.computeScrollOffset()) {
            viewportOffsetY = scroller.getCurrY();
            clampViewport();
            invalidate();
            if (changeListener != null) {
                changeListener.onScrollChanged(viewportOffsetY, getMaxScrollY());
            }
        }
    }

    private void clampViewport() {
        float max = getMaxScrollY();
        if (viewportOffsetY < 0) viewportOffsetY = 0;
        if (viewportOffsetY > max) viewportOffsetY = max;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (contentHeight < h) {
            contentHeight = isInfinite ? h * 2.5f : h;
            if (currentPage != null && isInfinite) {
                currentPage.canvasHeight = contentHeight;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        float effectiveHeight = getContentHeight();

        boolean isDarkBackground = isColorDark(pageBackgroundColor);

        // 1. Draw Page Background
        canvas.drawColor(pageBackgroundColor);

        // Zero-lag visible viewport computation for infinite document culling
        float vLeft = -panOffsetX / scaleFactor;
        float vTop = (0f - panOffsetY) / scaleFactor + viewportOffsetY;
        float vRight = (viewWidth - panOffsetX) / scaleFactor;
        float vBottom = (viewHeight - panOffsetY) / scaleFactor + viewportOffsetY;
        float pad = 120f / Math.max(0.2f, scaleFactor);
        visibleViewport.set(vLeft - pad, vTop - pad, vRight + pad, vBottom + pad);

        canvas.save();
        // Zoom and Pan transformations
        canvas.translate(panOffsetX, panOffsetY - viewportOffsetY * scaleFactor);
        canvas.scale(scaleFactor, scaleFactor);

        // 2. Draw Paper Template lines (culled to visible viewport)
        drawPaperTemplate(canvas, viewWidth, effectiveHeight, isDarkBackground);

        if (currentPage != null) {
            // 3. Draw Committed Shapes (culled to visible screen)
            for (ShapeItem shape : currentPage.shapes) {
                if (!RectF.intersects(shape.getBounds(), visibleViewport)) continue;
                drawShape(canvas, shape.type, shape.startX, shape.startY, shape.endX, shape.endY, shape.color, shape.strokeWidth);
            }

            // 4. Draw Committed Text (culled to visible screen)
            for (TextItem item : currentPage.texts) {
                if (!RectF.intersects(item.getBounds(), visibleViewport)) continue;
                textPaint.setColor(item.color);
                textPaint.setTextSize(item.fontSize);
                textPaint.setTypeface(org.opennotes.app.util.TypefaceManager.getTypeface(getContext(), item.fontKey));
                canvas.drawText(item.text, item.x, item.y, textPaint);
            }

            // 5. Draw Committed Strokes (culled to visible screen with pre-cached GPU paths)
            for (Stroke stroke : currentPage.strokes) {
                if (!RectF.intersects(stroke.getBounds(), visibleViewport)) continue;
                drawStroke(canvas, stroke);
            }

            // 6. Draw Active Stroke
            if (activeStroke != null) {
                drawStroke(canvas, activeStroke);
            }

            // 7. Draw Live Shape Preview
            if (isDrawingShape) {
                drawShape(canvas, currentShapeType, shapeStartX, shapeStartY, shapeCurrentX, shapeCurrentY, currentColor, currentStrokeWidth);
            }

            // 8. Draw Lasso path & selection
            if (isDrawingLasso) {
                canvas.drawPath(lassoPath, lassoPaint);
            }
            if (hasLassoSelection && !selectionBounds.isEmpty()) {
                canvas.drawRoundRect(selectionBounds, 12f, 12f, selectionBoxPaint);

                // Draw Corner Transform Handles
                float handleRadius = 6f / Math.max(0.5f, scaleFactor);
                canvas.drawCircle(selectionBounds.left, selectionBounds.top, handleRadius, selectionHandlePaint);
                canvas.drawCircle(selectionBounds.right, selectionBounds.top, handleRadius, selectionHandlePaint);
                canvas.drawCircle(selectionBounds.left, selectionBounds.bottom, handleRadius, selectionHandlePaint);
                canvas.drawCircle(selectionBounds.right, selectionBounds.bottom, handleRadius, selectionHandlePaint);
            }

            // 9. Draw Laser Pointer Presentation Trail & Glowing Tip
            if (!laserTrail.isEmpty()) {
                long now = System.currentTimeMillis();
                for (int i = laserTrail.size() - 1; i >= 0; i--) {
                    if (now - laserTrail.get(i).timestamp > 1200) {
                        laserTrail.remove(i);
                    }
                }
                if (laserTrail.size() >= 2) {
                    LaserPoint pStart = laserTrail.get(0);
                    float lastMidX = pStart.x;
                    float lastMidY = pStart.y;
                    for (int i = 1; i < laserTrail.size(); i++) {
                        LaserPoint pPrev = laserTrail.get(i - 1);
                        LaserPoint pCurr = laserTrail.get(i);
                        float midX = (pPrev.x + pCurr.x) / 2.0f;
                        float midY = (pPrev.y + pCurr.y) / 2.0f;
                        float lifeRatio = Math.max(0.05f, 1.0f - (float)(now - pCurr.timestamp) / 1200f);

                        laserGlowPaint.setAlpha((int) (200 * lifeRatio));
                        laserGlowPaint.setStrokeWidth(16f * lifeRatio);

                        reusableSegPath.reset();
                        reusableSegPath.moveTo(lastMidX, lastMidY);
                        reusableSegPath.quadTo(pPrev.x, pPrev.y, midX, midY);
                        canvas.drawPath(reusableSegPath, laserGlowPaint);

                        laserCorePaint.setAlpha((int) (255 * lifeRatio));
                        laserCorePaint.setStrokeWidth(4.5f * lifeRatio);
                        canvas.drawPath(reusableSegPath, laserCorePaint);

                        lastMidX = midX;
                        lastMidY = midY;
                    }
                }
                if (!laserTrail.isEmpty()) {
                    LaserPoint tip = laserTrail.get(laserTrail.size() - 1);
                    canvas.drawCircle(tip.x, tip.y, 8f, laserTipPaint);
                    canvas.drawCircle(tip.x, tip.y, 4f, laserCorePaint);
                    postInvalidateOnAnimation();
                }
            }
        }

        canvas.restore();

        // 9. Draw Scrollbar indicator for Infinite mode
        if (isInfinite && effectiveHeight > viewHeight) {
            drawScrollbar(canvas, viewWidth, viewHeight, effectiveHeight);
        }
    }

    public RectF getSelectionScreenBounds() {
        if (!hasLassoSelection || selectionBounds.isEmpty()) return null;
        float left = selectionBounds.left * scaleFactor + panOffsetX;
        float top = (selectionBounds.top - viewportOffsetY) * scaleFactor + panOffsetY;
        float right = selectionBounds.right * scaleFactor + panOffsetX;
        float bottom = (selectionBounds.bottom - viewportOffsetY) * scaleFactor + panOffsetY;
        return new RectF(left, top, right, bottom);
    }

    private void notifyLassoSelection() {
        if (lassoSelectionListener != null) {
            lassoSelectionListener.onSelectionChanged(hasSelection(), getSelectionScreenBounds());
        }
    }

    private void notifyZoomChange() {
        if (zoomListener != null) {
            zoomListener.onZoomChanged(getZoomPercent());
        }
    }

    private boolean isColorDark(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b);
        return lum < 128.0;
    }

    private void drawScrollbar(Canvas canvas, int width, int height, float totalHeight) {
        float barWidth = 6f;
        float thumbHeight = Math.max(48f, (height / totalHeight) * height);
        float scrollPercent = (totalHeight > height) ? (viewportOffsetY / (totalHeight - height)) : 0f;
        float thumbTop = scrollPercent * (height - thumbHeight);

        RectF rect = new RectF(width - barWidth - 4f, thumbTop, width - 4f, thumbTop + thumbHeight);
        scrollbarPaint.setAlpha(120);
        canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, scrollbarPaint);
    }

    private void drawPaperTemplate(Canvas canvas, int width, float totalHeight, boolean isDark) {
        if (paperTemplate == PaperTemplate.BLANK) return;

        templateLinePaint.setColor(isDark ? 0x22FFFFFF : 0xFFE2E8F0);
        templateMarginPaint.setColor(isDark ? 0x55F87171 : 0xFFFCA5A5);

        if (paperTemplate == PaperTemplate.RULED) {
            float lineSpacing = 60f;
            float startY = 120f;
            float marginX = 140f;
            if (marginX >= visibleViewport.left && marginX <= visibleViewport.right) {
                canvas.drawLine(marginX, Math.max(0, visibleViewport.top), marginX, Math.min(totalHeight, visibleViewport.bottom), templateMarginPaint);
            }

            float firstLineY = Math.max(startY, (float) Math.floor(visibleViewport.top / lineSpacing) * lineSpacing);
            float lastLineY = Math.min(totalHeight, visibleViewport.bottom + lineSpacing);
            float drawX1 = Math.max(0, visibleViewport.left);
            float drawX2 = Math.min(width * 2f, visibleViewport.right);

            for (float y = firstLineY; y <= lastLineY; y += lineSpacing) {
                canvas.drawLine(drawX1, y, drawX2, y, templateLinePaint);
            }
        } else if (paperTemplate == PaperTemplate.GRID) {
            float gridSize = 50f;
            float firstX = Math.max(0, (float) Math.floor(visibleViewport.left / gridSize) * gridSize);
            float lastX = Math.min(width * 2f, visibleViewport.right + gridSize);
            float minY = Math.max(0, visibleViewport.top);
            float maxY = Math.min(totalHeight, visibleViewport.bottom);

            for (float x = firstX; x <= lastX; x += gridSize) {
                canvas.drawLine(x, minY, x, maxY, templateLinePaint);
            }

            float firstY = Math.max(0, (float) Math.floor(visibleViewport.top / gridSize) * gridSize);
            float lastY = Math.min(totalHeight, visibleViewport.bottom + gridSize);
            float minX = Math.max(0, visibleViewport.left);
            float maxX = Math.min(width * 2f, visibleViewport.right);

            for (float y = firstY; y <= lastY; y += gridSize) {
                canvas.drawLine(minX, y, maxX, y, templateLinePaint);
            }
        } else if (paperTemplate == PaperTemplate.DOTS) {
            float dotSpacing = 50f;
            templateDotPaint.setColor(isDark ? 0x33FFFFFF : 0xFFCBD5E1);
            float firstX = Math.max(dotSpacing, (float) Math.floor(visibleViewport.left / dotSpacing) * dotSpacing);
            float lastX = Math.min(width * 2f, visibleViewport.right + dotSpacing);
            float firstY = Math.max(dotSpacing, (float) Math.floor(visibleViewport.top / dotSpacing) * dotSpacing);
            float lastY = Math.min(totalHeight, visibleViewport.bottom + dotSpacing);

            for (float x = firstX; x <= lastX; x += dotSpacing) {
                for (float y = firstY; y <= lastY; y += dotSpacing) {
                    canvas.drawCircle(x, y, 2.5f, templateDotPaint);
                }
            }
        } else if (paperTemplate == PaperTemplate.CORNELL) {
            float headerY = 140f;
            float marginX = 240f;
            if (headerY >= visibleViewport.top && headerY <= visibleViewport.bottom) {
                canvas.drawLine(Math.max(0, visibleViewport.left), headerY, Math.min(width * 2f, visibleViewport.right), headerY, templateLinePaint);
            }
            if (marginX >= visibleViewport.left && marginX <= visibleViewport.right) {
                canvas.drawLine(marginX, Math.max(headerY, visibleViewport.top), marginX, Math.min(totalHeight, visibleViewport.bottom), templateMarginPaint);
            }
            float lineSpacing = 55f;
            float firstY = Math.max(headerY + lineSpacing, (float) Math.floor(visibleViewport.top / lineSpacing) * lineSpacing);
            float lastY = Math.min(totalHeight, visibleViewport.bottom + lineSpacing);
            float minLineX = Math.max(marginX, visibleViewport.left);
            float maxLineX = Math.min(width * 2f, visibleViewport.right);

            for (float y = firstY; y <= lastY; y += lineSpacing) {
                canvas.drawLine(minLineX, y, maxLineX, y, templateLinePaint);
            }
        }
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points == null || stroke.points.isEmpty()) return;

        // Support single tap dots / periods
        if (stroke.points.size() == 1) {
            DrawingPoint pt = stroke.points.get(0);
            Paint dotPaint = (stroke.tool == DrawingTool.BRUSH) ? brushPaint : strokePaint;
            dotPaint.setColor(stroke.color);
            dotPaint.setStyle(Paint.Style.FILL);
            float dotRadius = Math.max(2.5f, stroke.width / 2.0f);
            if (stroke.tool == DrawingTool.HIGHLIGHTER) {
                dotPaint.setAlpha(100);
                dotRadius = stroke.width * 1.5f;
            } else if (stroke.tool == DrawingTool.PENCIL) {
                dotPaint.setAlpha(180);
                dotRadius = Math.max(1.5f, stroke.width * 0.4f);
            } else {
                dotPaint.setAlpha(255);
            }
            canvas.drawCircle(pt.x, pt.y, dotRadius, dotPaint);
            dotPaint.setStyle(Paint.Style.STROKE);
            return;
        }

        Paint paint;
        if (stroke.tool == DrawingTool.HIGHLIGHTER) {
            paint = highlighterPaint;
            paint.setColor(stroke.color);
            paint.setAlpha(100);
            paint.setStrokeWidth(stroke.width * 3.5f);
        } else if (stroke.tool == DrawingTool.PENCIL) {
            paint = strokePaint;
            paint.setColor(stroke.color);
            paint.setAlpha(180);
            paint.setStrokeWidth(Math.max(2.0f, stroke.width * 0.7f));
        } else if (stroke.tool == DrawingTool.BRUSH) {
            drawBrushStroke(canvas, stroke);
            return;
        } else if (stroke.tool == DrawingTool.FOUNTAIN) {
            drawFountainStroke(canvas, stroke);
            return;
        } else {
            paint = strokePaint;
            paint.setColor(stroke.color);
            paint.setAlpha(255);
            paint.setStrokeWidth(stroke.width);
        }

        canvas.drawPath(stroke.getPath(), paint);
    }

    private void drawFountainStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        if (stroke.points.size() == 1) {
            DrawingPoint p = stroke.points.get(0);
            fountainPaint.setColor(stroke.color);
            fountainPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(p.x, p.y, stroke.width * 0.5f, fountainPaint);
            fountainPaint.setStyle(Paint.Style.STROKE);
            return;
        }

        fountainPaint.setColor(stroke.color);
        fountainPaint.setStyle(Paint.Style.STROKE);

        // Smooth width filter initialized with first point's pressure
        float basePressure = stroke.points.get(0).pressure;
        if (basePressure <= 0.05f) basePressure = 0.5f;
        float currentWidth = stroke.width * (0.6f + (float) Math.pow(basePressure, 0.75) * (0.7f + pressureSensitivity * 0.5f));

        DrawingPoint p0 = stroke.points.get(0);
        float lastMidX = p0.x;
        float lastMidY = p0.y;

        for (int i = 1; i < stroke.points.size(); i++) {
            DrawingPoint pPrev = stroke.points.get(i - 1);
            DrawingPoint pCurr = stroke.points.get(i);

            float midX = (pPrev.x + pCurr.x) / 2.0f;
            float midY = (pPrev.y + pCurr.y) / 2.0f;

            float p = pCurr.pressure;
            if (p <= 0.05f) p = 0.5f;
            float targetWidth = stroke.width * (0.6f + (float) Math.pow(p, 0.75) * (0.7f + pressureSensitivity * 0.5f));
            // Low-pass width smoothing eliminates sharp hooks / shoe-like bulges
            currentWidth = currentWidth * 0.65f + targetWidth * 0.35f;
            fountainPaint.setStrokeWidth(currentWidth);

            reusableSegPath.reset();
            reusableSegPath.moveTo(lastMidX, lastMidY);
            reusableSegPath.quadTo(pPrev.x, pPrev.y, midX, midY);
            canvas.drawPath(reusableSegPath, fountainPaint);

            lastMidX = midX;
            lastMidY = midY;
        }

        DrawingPoint lastPt = stroke.points.get(stroke.points.size() - 1);
        reusableSegPath.reset();
        reusableSegPath.moveTo(lastMidX, lastMidY);
        reusableSegPath.lineTo(lastPt.x, lastPt.y);
        canvas.drawPath(reusableSegPath, fountainPaint);
    }

    private void drawBrushStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        brushPaint.setColor(stroke.color);
        brushPaint.setAlpha(220);

        if (stroke.points.size() == 1) {
            DrawingPoint p = stroke.points.get(0);
            brushPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(p.x, p.y, stroke.width * 0.6f, brushPaint);
            brushPaint.setStyle(Paint.Style.STROKE);
            return;
        }

        float baseP = stroke.points.get(0).pressure;
        if (baseP <= 0.05f) baseP = 0.5f;
        float currentWidth = stroke.width * (0.5f + (float) Math.pow(baseP, 0.8) * (1.0f + pressureSensitivity * 0.7f));

        DrawingPoint p0 = stroke.points.get(0);
        float lastMidX = p0.x;
        float lastMidY = p0.y;

        for (int i = 1; i < stroke.points.size(); i++) {
            DrawingPoint pPrev = stroke.points.get(i - 1);
            DrawingPoint pCurr = stroke.points.get(i);

            float midX = (pPrev.x + pCurr.x) / 2.0f;
            float midY = (pPrev.y + pCurr.y) / 2.0f;

            float p = pCurr.pressure;
            if (p <= 0.05f) p = 0.5f;
            float targetWidth = stroke.width * (0.5f + (float) Math.pow(p, 0.8) * (1.0f + pressureSensitivity * 0.7f));
            currentWidth = currentWidth * 0.65f + targetWidth * 0.35f;
            brushPaint.setStrokeWidth(currentWidth);

            reusableSegPath.reset();
            reusableSegPath.moveTo(lastMidX, lastMidY);
            reusableSegPath.quadTo(pPrev.x, pPrev.y, midX, midY);
            canvas.drawPath(reusableSegPath, brushPaint);

            lastMidX = midX;
            lastMidY = midY;
        }

        DrawingPoint lastPt = stroke.points.get(stroke.points.size() - 1);
        reusableSegPath.reset();
        reusableSegPath.moveTo(lastMidX, lastMidY);
        reusableSegPath.lineTo(lastPt.x, lastPt.y);
        canvas.drawPath(reusableSegPath, brushPaint);
    }

    private void drawShape(Canvas canvas, ShapeItem.Type type, float x1, float y1, float x2, float y2, int color, float strokeWidth) {
        previewShapePaint.setColor(color);
        previewShapePaint.setStrokeWidth(strokeWidth);

        switch (type) {
            case LINE:
                canvas.drawLine(x1, y1, x2, y2, previewShapePaint);
                break;
            case ARROW:
                canvas.drawLine(x1, y1, x2, y2, previewShapePaint);
                drawArrowHead(canvas, x1, y1, x2, y2, previewShapePaint);
                break;
            case RECTANGLE:
                float left = Math.min(x1, x2);
                float top = Math.min(y1, y2);
                float right = Math.max(x1, x2);
                float bottom = Math.max(y1, y2);
                canvas.drawRect(left, top, right, bottom, previewShapePaint);
                break;
            case CIRCLE:
                RectF oval = new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
                canvas.drawOval(oval, previewShapePaint);
                break;
            case TRIANGLE:
                drawTriangle(canvas, x1, y1, x2, y2, previewShapePaint);
                break;
            case STAR:
                drawStar(canvas, x1, y1, x2, y2, previewShapePaint);
                break;
            case COORDINATE_AXIS:
                drawCoordinateAxis(canvas, x1, y1, x2, y2, previewShapePaint);
                break;
            case TABLE:
                float tLeft = Math.min(x1, x2);
                float tTop = Math.min(y1, y2);
                float tRight = Math.max(x1, x2);
                float tBottom = Math.max(y1, y2);
                canvas.drawRect(tLeft, tTop, tRight, tBottom, previewShapePaint);
                float rowHeight = (tBottom - tTop) / 3f;
                float colWidth = (tRight - tLeft) / 3f;
                for (int r = 1; r < 3; r++) {
                    canvas.drawLine(tLeft, tTop + r * rowHeight, tRight, tTop + r * rowHeight, previewShapePaint);
                }
                for (int c = 1; c < 3; c++) {
                    canvas.drawLine(tLeft + c * colWidth, tTop, tLeft + c * colWidth, tBottom, previewShapePaint);
                }
                break;
        }
    }

    private void drawTriangle(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        Path p = new Path();
        float midX = (x1 + x2) / 2.0f;
        p.moveTo(midX, Math.min(y1, y2));
        p.lineTo(Math.max(x1, x2), Math.max(y1, y2));
        p.lineTo(Math.min(x1, x2), Math.max(y1, y2));
        p.close();
        canvas.drawPath(p, paint);
    }

    private void drawStar(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float cx = (x1 + x2) / 2.0f;
        float cy = (y1 + y2) / 2.0f;
        float r = Math.min(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 2.0f;
        if (r < 5) return;
        float innerR = r * 0.4f;

        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double angle = i * Math.PI / 5 - Math.PI / 2;
            float radius = (i % 2 == 0) ? r : innerR;
            float x = (float) (cx + radius * Math.cos(angle));
            float y = (float) (cy + radius * Math.sin(angle));
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        canvas.drawPath(p, paint);
    }

    private void drawCoordinateAxis(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        float midY = (minY + maxY) / 2.0f;
        float midX = (minX + maxX) / 2.0f;

        canvas.drawLine(minX, midY, maxX, midY, paint);
        drawArrowHead(canvas, minX, midY, maxX, midY, paint);

        canvas.drawLine(midX, maxY, midX, minY, paint);
        drawArrowHead(canvas, midX, maxY, midX, minY, paint);
    }

    private void drawArrowHead(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float deltaX = x2 - x1;
        float deltaY = y2 - y1;
        double angle = Math.atan2(deltaY, deltaX);
        float arrowSize = 36f;

        float xA = (float) (x2 - arrowSize * Math.cos(angle - Math.PI / 6));
        float yA = (float) (y2 - arrowSize * Math.sin(angle - Math.PI / 6));
        float xB = (float) (x2 - arrowSize * Math.cos(angle + Math.PI / 6));
        float yB = (float) (y2 - arrowSize * Math.sin(angle + Math.PI / 6));

        canvas.drawLine(x2, y2, xA, yA, paint);
        canvas.drawLine(x2, y2, xB, yB, paint);
    }

    public void clearSelection() {
        hasLassoSelection = false;
        selectedStrokes.clear();
        selectedShapes.clear();
        selectionBounds.setEmpty();
        lassoPath.reset();
        lassoPoints.clear();
        notifyLassoSelection();
    }

    public boolean hasSelection() {
        return hasLassoSelection && (!selectedStrokes.isEmpty() || !selectedShapes.isEmpty());
    }

    public void deleteSelection() {
        if (!hasSelection() || currentPage == null) return;
        currentPage.strokes.removeAll(selectedStrokes);
        currentPage.shapes.removeAll(selectedShapes);
        clearSelection();
        invalidate();
        notifyChange();
    }

    public void cutSelection() {
        copySelection();
        deleteSelection();
    }

    public void copySelection() {
        if (!hasSelection()) return;
        clipboardStrokes.clear();
        clipboardShapes.clear();
        for (Stroke s : selectedStrokes) {
            Stroke clone = new Stroke(s.color, s.width, s.tool);
            for (DrawingPoint pt : s.points) {
                clone.addPoint(pt.x, pt.y, pt.pressure);
            }
            clipboardStrokes.add(clone);
        }
        for (ShapeItem sh : selectedShapes) {
            ShapeItem clone = new ShapeItem(sh.type, sh.startX, sh.startY, sh.endX, sh.endY, sh.color, sh.strokeWidth);
            clipboardShapes.add(clone);
        }
    }

    public void pasteSelection() {
        if (currentPage == null || (clipboardStrokes.isEmpty() && clipboardShapes.isEmpty())) return;
        float offset = 50f;
        List<Stroke> pastedStrokes = new ArrayList<>();
        for (Stroke s : clipboardStrokes) {
            Stroke clone = new Stroke(s.color, s.width, s.tool);
            for (DrawingPoint pt : s.points) {
                clone.addPoint(pt.x + offset, pt.y + offset, pt.pressure);
            }
            pastedStrokes.add(clone);
            currentPage.strokes.add(clone);
        }
        List<ShapeItem> pastedShapes = new ArrayList<>();
        for (ShapeItem sh : clipboardShapes) {
            ShapeItem clone = new ShapeItem(sh.type, sh.startX + offset, sh.startY + offset,
                    sh.endX + offset, sh.endY + offset, sh.color, sh.strokeWidth);
            pastedShapes.add(clone);
            currentPage.shapes.add(clone);
        }
        selectedStrokes.clear();
        selectedStrokes.addAll(pastedStrokes);
        selectedShapes.clear();
        selectedShapes.addAll(pastedShapes);
        selectionBounds.offset(offset, offset);
        hasLassoSelection = true;
        invalidate();
        notifyChange();
        notifyLassoSelection();
    }

    public void duplicateSelection() {
        if (!hasSelection() || currentPage == null) return;
        float offset = 40f;
        List<Stroke> newStrokes = new ArrayList<>();
        for (Stroke s : selectedStrokes) {
            Stroke clone = new Stroke(s.color, s.width, s.tool);
            for (DrawingPoint pt : s.points) {
                clone.addPoint(pt.x + offset, pt.y + offset, pt.pressure);
            }
            newStrokes.add(clone);
            currentPage.strokes.add(clone);
        }
        List<ShapeItem> newShapes = new ArrayList<>();
        for (ShapeItem sh : selectedShapes) {
            ShapeItem clone = new ShapeItem(sh.type, sh.startX + offset, sh.startY + offset,
                    sh.endX + offset, sh.endY + offset, sh.color, sh.strokeWidth);
            newShapes.add(clone);
            currentPage.shapes.add(clone);
        }
        selectedStrokes.clear();
        selectedStrokes.addAll(newStrokes);
        selectedShapes.clear();
        selectedShapes.addAll(newShapes);
        selectionBounds.offset(offset, offset);
        invalidate();
        notifyChange();
        notifyLassoSelection();
    }

    public void setSelectionColor(int color) {
        if (!hasSelection()) return;
        for (Stroke s : selectedStrokes) {
            s.color = color;
        }
        for (ShapeItem sh : selectedShapes) {
            sh.color = color;
        }
        invalidate();
        notifyChange();
    }

    public void setSelectionStrokeWidth(float width) {
        if (!hasSelection()) return;
        for (Stroke s : selectedStrokes) {
            s.width = width;
            s.markDirty();
        }
        for (ShapeItem sh : selectedShapes) {
            sh.strokeWidth = width;
            sh.markDirty();
        }
        invalidate();
        notifyChange();
    }

    private void translateItems(List<Stroke> strokes, List<ShapeItem> shapes, float dx, float dy) {
        if (strokes != null) {
            for (Stroke s : strokes) {
                for (DrawingPoint pt : s.points) {
                    pt.x += dx;
                    pt.y += dy;
                }
                s.markDirty();
            }
        }
        if (shapes != null) {
            for (ShapeItem sh : shapes) {
                sh.startX += dx;
                sh.startY += dy;
                sh.endX += dx;
                sh.endY += dy;
                sh.markDirty();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        if (pointerCount >= 2) {
            scaleDetector.onTouchEvent(event);
        }

        int toolType = event.getToolType(0);
        boolean isStylus = (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER);

        // Stylus-Only Mode check: palm rejection
        if (isStylusOnly && !isStylus) {
            if (pointerCount >= 2 && !isViewportLocked) {
                handleTwoFingerPanZoom(event, action);
            }
            return true;
        }

        // Two-Finger Pinch-to-Zoom & Pan (Universal across all tools)
        if (pointerCount >= 2 && !isViewportLocked) {
            if (activeStroke != null) {
                activeStroke = null;
                invalidate();
            }
            handleTwoFingerPanZoom(event, action);
            return true;
        }

        // Single-finger Hand Pan Tool
        if (currentTool == DrawingTool.HAND && !isViewportLocked) {
            float touchX = event.getX();
            float touchY = event.getY();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    twoFingerStartX = touchX;
                    twoFingerStartY = touchY;
                    initialPanOffsetX = panOffsetX;
                    initialPanOffsetY = panOffsetY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panOffsetX = initialPanOffsetX + (touchX - twoFingerStartX);
                    panOffsetY = initialPanOffsetY + (touchY - twoFingerStartY);
                    invalidate();
                    notifyLassoSelection();
                    return true;
            }
            return true;
        }

        // Map touch coordinates to natural unscaled canvas coordinates
        float rawX = event.getX();
        float rawY = event.getY();
        float canvasX = (rawX - panOffsetX) / scaleFactor;
        float canvasY = (rawY - panOffsetY) / scaleFactor + viewportOffsetY;
        float pressure = event.getPressure();

        // 2b. Laser Pointer Tool (ephemeral glowing presentation trail)
        if (currentTool == DrawingTool.LASER) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    laserTrail.add(new LaserPoint(canvasX, canvasY, System.currentTimeMillis()));
                    postInvalidateOnAnimation();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int historySize = event.getHistorySize();
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < historySize; i++) {
                        float histRawX = event.getHistoricalX(i);
                        float histRawY = event.getHistoricalY(i);
                        float histX = (histRawX - panOffsetX) / scaleFactor;
                        float histY = (histRawY - panOffsetY) / scaleFactor + viewportOffsetY;
                        laserTrail.add(new LaserPoint(histX, histY, now));
                    }
                    laserTrail.add(new LaserPoint(canvasX, canvasY, now));
                    postInvalidateOnAnimation();
                    return true;
                case MotionEvent.ACTION_UP:
                    postInvalidateOnAnimation();
                    return true;
            }
            return true;
        }

        // 3. Lasso Tool
        if (currentTool == DrawingTool.LASSO) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (hasLassoSelection && selectionBounds.contains(canvasX, canvasY)) {
                        isMovingSelection = true;
                        lassoMoveStartX = canvasX;
                        lassoMoveStartY = canvasY;
                    } else {
                        clearSelection();
                        isDrawingLasso = true;
                        lassoPath.reset();
                        lassoPath.moveTo(canvasX, canvasY);
                        lassoPoints.clear();
                        lassoPoints.add(new DrawingPoint(canvasX, canvasY, 1f));
                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isMovingSelection) {
                        float dx = canvasX - lassoMoveStartX;
                        float dy = canvasY - lassoMoveStartY;
                        translateItems(selectedStrokes, selectedShapes, dx, dy);
                        selectionBounds.offset(dx, dy);
                        lassoMoveStartX = canvasX;
                        lassoMoveStartY = canvasY;
                        invalidate();
                        notifyLassoSelection();
                    } else if (isDrawingLasso) {
                        lassoPath.lineTo(canvasX, canvasY);
                        lassoPoints.add(new DrawingPoint(canvasX, canvasY, 1f));
                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (isMovingSelection) {
                        isMovingSelection = false;
                        notifyChange();
                        notifyLassoSelection();
                    } else if (isDrawingLasso) {
                        isDrawingLasso = false;
                        lassoPath.close();
                        processLassoSelection();
                        invalidate();
                        notifyLassoSelection();
                    }
                    return true;
            }
            return true;
        }

        // 4. Text Tool
        if (currentTool == DrawingTool.TEXT) {
            if (action == MotionEvent.ACTION_UP) {
                if (changeListener != null) {
                    changeListener.onRequestTextInput(rawX, rawY);
                }
            }
            return true;
        }

        // 5. Shape Tool
        if (currentTool == DrawingTool.SHAPE) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    isDrawingShape = true;
                    shapeStartX = canvasX;
                    shapeStartY = canvasY;
                    shapeCurrentX = canvasX;
                    shapeCurrentY = canvasY;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    shapeCurrentX = canvasX;
                    shapeCurrentY = canvasY;
                    ensureContentHeight(canvasY);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    isDrawingShape = false;
                    shapeCurrentX = canvasX;
                    shapeCurrentY = canvasY;
                    if (currentPage != null && (Math.abs(shapeCurrentX - shapeStartX) > 10 || Math.abs(shapeCurrentY - shapeStartY) > 10)) {
                        ShapeItem shape = new ShapeItem(currentShapeType, shapeStartX, shapeStartY, shapeCurrentX, shapeCurrentY, currentColor, currentStrokeWidth);
                        currentPage.shapes.add(shape);

                        ActionRecord rec = new ActionRecord();
                        rec.type = ActionRecord.Type.ADD_SHAPE;
                        rec.shape = shape;
                        undoStack.push(rec);
                        redoStack.clear();

                        ensureContentHeight(canvasY);
                        notifyChange();
                    }
                    invalidate();
                    return true;
            }
            return true;
        }

        // 6. Stroke Eraser
        if (currentTool == DrawingTool.ERASER_STROKE) {
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
                checkStrokeErasure(canvasX, canvasY);
            }
            return true;
        }

        // 7. Stroke Drawing (Fountain, Pen, Brush, Highlighter, Pencil)
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                smoothedX = canvasX;
                smoothedY = canvasY;
                strokeStartTime = System.currentTimeMillis();
                activeStroke = new Stroke(currentColor, currentStrokeWidth, currentTool);
                activeStroke.addPoint(canvasX, canvasY, pressure);
                lastTouchX = canvasX;
                lastTouchY = canvasY;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeStroke != null) {
                    float filterWeight = 1.0f - (strokeStabilization * 0.70f);
                    int historySize = event.getHistorySize();
                    for (int i = 0; i < historySize; i++) {
                        float histRawX = event.getHistoricalX(i);
                        float histRawY = event.getHistoricalY(i);
                        float histX = (histRawX - panOffsetX) / scaleFactor;
                        float histY = (histRawY - panOffsetY) / scaleFactor + viewportOffsetY;

                        smoothedX = smoothedX + (histX - smoothedX) * filterWeight;
                        smoothedY = smoothedY + (histY - smoothedY) * filterWeight;
                        activeStroke.addPoint(smoothedX, smoothedY, event.getHistoricalPressure(i));
                    }
                    smoothedX = smoothedX + (canvasX - smoothedX) * filterWeight;
                    smoothedY = smoothedY + (canvasY - smoothedY) * filterWeight;

                    activeStroke.addPoint(smoothedX, smoothedY, pressure);
                    lastTouchX = smoothedX;
                    lastTouchY = smoothedY;

                    ensureContentHeight(canvasY);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (activeStroke != null) {
                    activeStroke.addPoint(canvasX, canvasY, pressure);

                    // Straight-Line Snapping Assist
                    if (straightLineAssist && activeStroke.points.size() > 5) {
                        DrawingPoint firstPt = activeStroke.points.get(0);
                        DrawingPoint lastPt = activeStroke.points.get(activeStroke.points.size() - 1);
                        float dist = (float) Math.hypot(lastPt.x - firstPt.x, lastPt.y - firstPt.y);
                        if (dist > 60f) {
                            activeStroke.points.clear();
                            activeStroke.addPoint(firstPt.x, firstPt.y, 1.0f);
                            activeStroke.addPoint(lastPt.x, lastPt.y, 1.0f);
                            activeStroke.markDirty();
                        }
                    }

                    // Auto-Beautification of Handwritten Notes (English, Hindi, Marathi)
                    if (isAutoBeautifyEnabled && activeStroke.points.size() >= 3) {
                        applyAutoBeautification(activeStroke);
                    }

                    if (currentPage != null && !activeStroke.points.isEmpty()) {
                        currentPage.strokes.add(activeStroke);

                        ActionRecord rec = new ActionRecord();
                        rec.type = ActionRecord.Type.ADD_STROKE;
                        rec.stroke = activeStroke;
                        undoStack.push(rec);
                        redoStack.clear();

                        ensureContentHeight(canvasY);
                        notifyChange();

                        // Real-time automatic handwriting to beautified font conversion
                        if (isAutoConvertTextEnabled) {
                            pendingAutoConvertStrokes.add(activeStroke);
                            autoConvertHandler.removeCallbacks(autoConvertRunnable);
                            autoConvertHandler.postDelayed(autoConvertRunnable, 900);
                        }
                    }
                    activeStroke = null;
                    invalidate();
                }
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void handleTwoFingerPanZoom(MotionEvent event, int action) {
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    twoFingerStartX = (event.getX(0) + event.getX(1)) / 2.0f;
                    twoFingerStartY = (event.getY(0) + event.getY(1)) / 2.0f;
                    initialPanOffsetX = panOffsetX;
                    initialPanOffsetY = panOffsetY;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2 && !scaleDetector.isInProgress()) {
                    float currentCenterX = (event.getX(0) + event.getX(1)) / 2.0f;
                    float currentCenterY = (event.getY(0) + event.getY(1)) / 2.0f;
                    panOffsetX = initialPanOffsetX + (currentCenterX - twoFingerStartX);
                    panOffsetY = initialPanOffsetY + (currentCenterY - twoFingerStartY);
                    invalidate();
                    notifyLassoSelection();
                }
                break;
        }
    }

    private void processLassoSelection() {
        if (currentPage == null || lassoPoints.size() < 3) return;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (DrawingPoint pt : lassoPoints) {
            if (pt.x < minX) minX = pt.x;
            if (pt.x > maxX) maxX = pt.x;
            if (pt.y < minY) minY = pt.y;
            if (pt.y > maxY) maxY = pt.y;
        }

        RectF lassoBounds = new RectF(minX, minY, maxX, maxY);
        selectedStrokes.clear();
        selectedShapes.clear();

        for (Stroke s : currentPage.strokes) {
            for (DrawingPoint pt : s.points) {
                if (lassoBounds.contains(pt.x, pt.y)) {
                    selectedStrokes.add(s);
                    break;
                }
            }
        }

        for (ShapeItem sh : currentPage.shapes) {
            if (lassoBounds.contains(sh.startX, sh.startY) || lassoBounds.contains(sh.endX, sh.endY)) {
                selectedShapes.add(sh);
            }
        }

        if (!selectedStrokes.isEmpty() || !selectedShapes.isEmpty()) {
            hasLassoSelection = true;
            selectionBounds.set(lassoBounds);
            selectionBounds.inset(-16f, -16f);
        } else {
            clearSelection();
        }
    }

    private void checkStrokeErasure(float touchX, float touchY) {
        if (currentPage == null || currentPage.strokes.isEmpty()) return;
        float hitRadius = 35f / scaleFactor;

        for (int i = currentPage.strokes.size() - 1; i >= 0; i--) {
            Stroke s = currentPage.strokes.get(i);
            boolean hit = false;
            for (DrawingPoint pt : s.points) {
                float dx = pt.x - touchX;
                float dy = pt.y - touchY;
                if ((dx * dx + dy * dy) <= (hitRadius * hitRadius)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                currentPage.strokes.remove(i);
                ActionRecord rec = new ActionRecord();
                rec.type = ActionRecord.Type.REMOVE_STROKE;
                rec.stroke = s;
                undoStack.push(rec);
                redoStack.clear();
                invalidate();
                notifyChange();
                break;
            }
        }
    }

    private void notifyChange() {
        if (changeListener != null) {
            changeListener.onCanvasChanged();
        }
    }

    public Bitmap renderToBitmap(int targetWidth, int targetHeight) {
        float effectiveHeight = getContentHeight();
        float scaleX = (float) targetWidth / (float) Math.max(1, getWidth());
        float scaleY = (float) targetHeight / Math.max(1, effectiveHeight);

        Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scaleX, scaleY);

        boolean isDark = isColorDark(pageBackgroundColor);
        canvas.drawColor(pageBackgroundColor);
        visibleViewport.set(0, 0, Math.max(getWidth(), targetWidth) * 2f, effectiveHeight);
        drawPaperTemplate(canvas, getWidth(), effectiveHeight, isDark);

        if (currentPage != null) {
            for (ShapeItem shape : currentPage.shapes) {
                drawShape(canvas, shape.type, shape.startX, shape.startY, shape.endX, shape.endY, shape.color, shape.strokeWidth);
            }
            for (TextItem item : currentPage.texts) {
                textPaint.setColor(item.color);
                textPaint.setTextSize(item.fontSize);
                textPaint.setTypeface(org.opennotes.app.util.TypefaceManager.getTypeface(getContext(), item.fontKey));
                canvas.drawText(item.text, item.x, item.y, textPaint);
            }
            for (Stroke stroke : currentPage.strokes) {
                drawStroke(canvas, stroke);
            }
        }

        return bitmap;
    }
}
