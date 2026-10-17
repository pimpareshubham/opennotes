package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.OverScroller;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.util.LongSparseArray;
import android.view.HapticFeedbackConstants;
import android.view.ViewConfiguration;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityViewCommand;
import androidx.core.view.ViewCompat;
import androidx.input.motionprediction.MotionEventPredictor;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.ImageItem;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;
import org.opennotes.app.util.SelectionClipboard;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CanvasView extends View {
    private static final float STROKE_GRID_CELL_SIZE = 512f;
    private static final long MAX_STROKE_GRID_CELLS = 64L;
    private static final long MAX_VISIBLE_GRID_CELLS = 128L;
    private static final int LASER_FADE_BUCKETS = 8;
    private static final ExecutorService RECOGNITION_PREPROCESSOR =
            Executors.newSingleThreadExecutor();

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
    private boolean isFingerMoveCanvasEnabled = false;
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
    private float smoothedPressure = 0.5f;
    private float previousRawX;
    private float previousRawY;
    private float filteredVelocityX;
    private float filteredVelocityY;
    private long previousRawTimeMillis = -1L;
    private MotionEventPredictor motionEventPredictor;
    private boolean predictorStreamValid;
    private boolean hasPredictedInkPoint;
    private float predictedInkX;
    private float predictedInkY;
    private float predictedInkPressure = 0.5f;
    private final Path predictedInkPath = new Path();
    private final Paint predictedInkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private ShapeItem.Type currentShapeType = ShapeItem.Type.RECTANGLE;

    // Laser pointer presentation mode (ephemeral glowing trail)
    public static class LaserPoint {
        public float x, y;
        public long timestamp;
        public boolean startsSubpath;
        public LaserPoint(float x, float y, long timestamp) {
            this(x, y, timestamp, false);
        }
        public LaserPoint(float x, float y, long timestamp, boolean startsSubpath) {
            this.x = x;
            this.y = y;
            this.timestamp = timestamp;
            this.startsSubpath = startsSubpath;
        }
    }
    private final List<LaserPoint> laserTrail = new ArrayList<>(256);
    private final Path[] laserFadePaths = new Path[LASER_FADE_BUCKETS];
    private final boolean[] usedLaserFadePaths = new boolean[LASER_FADE_BUCKETS];
    private Page spatialIndexPage;
    private int spatialIndexStrokeCount;
    private final LongSparseArray<List<Integer>> strokeSpatialGrid = new LongSparseArray<>();
    private final List<Integer> oversizedStrokeIndices = new ArrayList<>();
    private final BitSet visibleStrokeIndices = new BitSet();
    // Avoid a synchronized TypefaceManager lookup for every visible text item on each frame.
    private final Map<String, Typeface> textTypefaceCache = new HashMap<>();
    private boolean strokeSpatialIndexDirty = true;
    private Paint laserGlowPaint;
    private Paint laserCorePaint;
    private Paint laserTipPaint;

    // Drawing state
    private Stroke activeStroke;
    private int activeInputPointerId = -1;
    private boolean scaleDetectorSuppressedForStylus;
    private long lastStylusActiveTime = 0L;
    private float lastTouchX, lastTouchY;

    // Pan / Scroll physics
    private OverScroller scroller;
    private VelocityTracker velocityTracker;
    private boolean isPanning = false;
    private float panStartY = 0f;
    private float panInitialOffsetY = 0f;
    private float twoFingerStartX = 0f, twoFingerStartY = 0f;
    private float initialPanOffsetX = 0f, initialPanOffsetY = 0f;
    private float twoFingerStartX0, twoFingerStartY0, twoFingerStartX1, twoFingerStartY1;
    private float twoFingerStartSpan;
    private boolean manualTwoFingerPanConfirmed;
    private float scaleGestureStartSpan;
    private boolean scaleGestureConfirmed;

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
    private final List<TextItem> selectedTexts = new ArrayList<>();
    private final List<ImageItem> selectedImages = new ArrayList<>();
    private final RectF selectionBounds = new RectF();
    private boolean isMovingSelection = false;
    private float lassoMoveStartX, lassoMoveStartY;
    private float selectionMoveDx, selectionMoveDy;
    private String lastAnnouncedSelection = "";

    // Clipboard for Cut / Copy / Paste
    private final List<Stroke> clipboardStrokes = new ArrayList<>();
    private final List<ShapeItem> clipboardShapes = new ArrayList<>();
    private final List<TextItem> clipboardTexts = new ArrayList<>();
    private final List<ImageItem> clipboardImages = new ArrayList<>();
    private float clipboardCenterX;
    private float clipboardCenterY;

    // Bitmap cache for fast rendering of on-page images
    private final Map<String, Bitmap> imageCache = new HashMap<>();

    // Scrollbar indicator
    private final Paint scrollbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Undo / Redo history
    private static class ActionRecord {
        enum Type { ADD_STROKE, REMOVE_STROKE, ADD_SHAPE, REMOVE_SHAPE, ADD_TEXT, REMOVE_TEXT, ADD_IMAGE, REMOVE_IMAGE, MOVE_SELECTION, ADD_SELECTION, DELETE_SELECTION, CLEAR_ALL, STYLE_CHANGE, BEAUTIFY_SELECTION, CONVERT_TO_TEXT, ROTATE_SELECTION, SCALE_SELECTION }
        Type type;
        Stroke stroke;
        ShapeItem shape;
        TextItem text;
        ImageItem image;
        float moveDx, moveDy;
        float rotateAngle;
        float rotateCenterX, rotateCenterY;
        float scaleX = 1f, scaleY = 1f;
        float scaleAnchorX, scaleAnchorY;
        List<Stroke> rotatedStrokes;
        List<ShapeItem> rotatedShapes;
        List<TextItem> rotatedTexts;
        List<ImageItem> rotatedImages;
        List<Stroke> scaledStrokes;
        List<ShapeItem> scaledShapes;
        List<TextItem> scaledTexts;
        List<ImageItem> scaledImages;
        List<Stroke> movedStrokes;
        List<ShapeItem> movedShapes;
        List<TextItem> movedTexts;
        List<ImageItem> movedImages;
        List<Stroke> prevStrokes;
        List<ShapeItem> prevShapes;
        List<TextItem> prevTexts;
        List<ImageItem> prevImages;
        List<Stroke> styleTargetsStrokes;
        List<ShapeItem> styleTargetsShapes;
        List<TextItem> styleTargetsTexts;
        List<Stroke> beforeBeautifyStrokes;
        List<Stroke> afterBeautifyStrokes;
        List<ItemStyleSnapshot> previousStrokeStyles;
        List<ItemStyleSnapshot> previousShapeStyles;
        List<ItemStyleSnapshot> previousTextStyles;
        List<ItemStyleSnapshot> nextStrokeStyles;
        List<ItemStyleSnapshot> nextShapeStyles;
        List<ItemStyleSnapshot> nextTextStyles;
        List<TextItem> convertedTexts;
    }

    private static final class ItemStyleSnapshot {
        final int color;
        final float size;
        final Stroke.LineStyle lineStyle;
        final Integer fillColor;

        ItemStyleSnapshot(int color, float size, Stroke.LineStyle lineStyle, Integer fillColor) {
            this.color = color;
            this.size = size;
            this.lineStyle = lineStyle;
            this.fillColor = fillColor;
        }
    }



    private static final class RecognizedStrokeLine {
        final List<Stroke> strokes;
        final org.opennotes.app.util.HandwritingRecognitionEngine.RecognitionResult result;

        RecognizedStrokeLine(List<Stroke> strokes,
                             org.opennotes.app.util.HandwritingRecognitionEngine.RecognitionResult result) {
            this.strokes = strokes;
            this.result = result;
        }
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
    private Paint previewShapeFillPaint;
    private Paint lassoPaint;
    private Paint selectionBoxPaint;
    private Paint selectionHandlePaint;
    private Paint selectionHandleBorderPaint;
    private Paint selectionRotateHandlePaint;
    private Paint selectionRotateBorderPaint;
    private Paint selectionRotateIconPaint;
    private Paint selectionRotateBadgePaint;
    private Paint selectionRotateTextPaint;

    // Hand pan tool dedicated tracking
    private float handPanStartX, handPanStartY;
    private float handInitialPanX, handInitialPanY;
    private boolean handPanDragging = false;

    // Selection rotation tracking
    private boolean isRotatingSelection = false;
    private float rotationCenterX, rotationCenterY;
    private float lastRotationTouchAngle;
    private float currentLiveRotation = 0f;

    // Selection interactive transform handles & resizing tracking
    public enum LassoHandle {
        NONE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        ROTATE
    }
    private LassoHandle activeLassoHandle = LassoHandle.NONE;
    private boolean isResizingSelection = false;
    private float transformAnchorX, transformAnchorY;
    private float initialTouchDistance = 1f;
    private float currentLiveScaleX = 1f;
    private float currentLiveScaleY = 1f;
    private final RectF initialSelectionBounds = new RectF();

    // Zero-lag high performance rendering buffers
    private final RectF visibleViewport = new RectF();
    private final RectF eraserQueryBounds = new RectF();
    private final Rect visibleClip = new Rect();

    private OnCanvasChangeListener changeListener;
    public interface OnCanvasLongPressListener {
        void onCanvasLongPress(float rawX, float rawY, float canvasX, float canvasY);
    }
    private OnCanvasLongPressListener longPressListener;
    public void setOnCanvasLongPressListener(OnCanvasLongPressListener listener) {
        this.longPressListener = listener;
    }

    private float touchSlop = 24f;
    private float touchDownRawX;
    private float touchDownRawY;
    private float touchDownCanvasX;
    private float touchDownCanvasY;
    private boolean longPressTriggered = false;

    private final Runnable longPressPasteRunnable = new Runnable() {
        @Override
        public void run() {
            if (hasClipboardContents()) {
                longPressTriggered = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (activeStroke != null && activeStroke.points != null && activeStroke.points.size() <= 6) {
                    activeStroke = null;
                    invalidate();
                }
                if (longPressListener != null) {
                    longPressListener.onCanvasLongPress(touchDownRawX, touchDownRawY, touchDownCanvasX, touchDownCanvasY);
                }
            }
        }
    };

    private OnZoomChangeListener zoomListener;
    private OnLassoSelectionListener lassoSelectionListener;
    private int selectAllAccessibilityAction = -1;
    private int copyAccessibilityAction = -1;
    private int cutAccessibilityAction = -1;
    private int deleteAccessibilityAction = -1;
    private int duplicateAccessibilityAction = -1;
    private int pasteAccessibilityAction = -1;
    private int undoAccessibilityAction = -1;
    private int redoAccessibilityAction = -1;

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
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setFocusable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Note page. Use the lasso tool to select ink, shapes, and text. Drag a selection to move it.");
        syncAccessibilityActions();
        try {
            motionEventPredictor = MotionEventPredictor.newInstance(this);
        } catch (RuntimeException ignored) {
            // Input prediction is an optional visual enhancement. The actual
            // MotionEvent path remains fully functional if a device has no provider.
            motionEventPredictor = null;
        }
        for (int i = 0; i < laserFadePaths.length; i++) {
            laserFadePaths[i] = new Path();
        }

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (detector.getCurrentSpan() < dp(45f)) return false;
                scaleGestureStartSpan = detector.getCurrentSpan();
                scaleGestureConfirmed = false;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (isViewportLocked) return true;
                if (!scaleGestureConfirmed) {
                    float intentionalSpanChange = dp(22f);
                    if (Math.abs(detector.getCurrentSpan() - scaleGestureStartSpan)
                            < intentionalSpanChange) return true;
                    scaleGestureConfirmed = true;
                    manualTwoFingerPanConfirmed = false;
                }
                float factor = detector.getScaleFactor();
                float targetScale = scaleFactor * factor;
                // Clamp between 10% and 1600% for expansive infinite canvas overview
                zoomAround(Math.max(0.10f, Math.min(16.0f, targetScale)),
                        detector.getFocusX(), detector.getFocusY());

                invalidate();
                notifyZoomChange();
                notifyLassoSelection();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                scaleGestureConfirmed = false;
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

        previewShapeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        previewShapeFillPaint.setStyle(Paint.Style.FILL);

        scrollbarPaint.setColor(0x6638BDF8);
        scrollbarPaint.setStyle(Paint.Style.FILL);

        lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setColor(0xFF38BDF8);
        lassoPaint.setStrokeWidth(2.5f);
        lassoPaint.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));

        selectionBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionBoxPaint.setStyle(Paint.Style.STROKE);
        selectionBoxPaint.setColor(0xFF625AFF);
        selectionBoxPaint.setStrokeWidth(2.0f);
        selectionBoxPaint.setPathEffect(new DashPathEffect(new float[]{10, 6}, 0));

        selectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionHandlePaint.setStyle(Paint.Style.FILL);
        selectionHandlePaint.setColor(0xFFFFFFFF);

        selectionHandleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionHandleBorderPaint.setStyle(Paint.Style.STROKE);
        selectionHandleBorderPaint.setColor(0xFF625AFF);
        selectionHandleBorderPaint.setStrokeWidth(2.2f);

        selectionRotateHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionRotateHandlePaint.setStyle(Paint.Style.FILL);
        selectionRotateHandlePaint.setColor(0xFF625AFF);

        selectionRotateBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionRotateBorderPaint.setStyle(Paint.Style.STROKE);
        selectionRotateBorderPaint.setColor(0xFFFFFFFF);
        selectionRotateBorderPaint.setStrokeWidth(2.0f);

        selectionRotateIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionRotateIconPaint.setStyle(Paint.Style.STROKE);
        selectionRotateIconPaint.setColor(0xFFFFFFFF);
        selectionRotateIconPaint.setStrokeCap(Paint.Cap.ROUND);
        selectionRotateIconPaint.setStrokeJoin(Paint.Join.ROUND);

        selectionRotateBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionRotateBadgePaint.setStyle(Paint.Style.FILL);
        selectionRotateBadgePaint.setColor(0xEE1E1E2E);

        selectionRotateTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionRotateTextPaint.setColor(0xFFFFFFFF);
        selectionRotateTextPaint.setTextAlign(Paint.Align.CENTER);
        selectionRotateTextPaint.setFakeBoldText(true);

        predictedInkPaint.setStyle(Paint.Style.STROKE);
        predictedInkPaint.setStrokeCap(Paint.Cap.ROUND);
        predictedInkPaint.setStrokeJoin(Paint.Join.ROUND);

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
        strokeSpatialIndexDirty = true;
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
        syncAccessibilityActions();
        invalidate();
        notifyZoomChange();
    }

    /** Restores the user's viewing position after the editor is recreated. */
    public void restoreViewport(float zoomPercent, float panX, float panY, float scrollY) {
        scaleFactor = Math.max(0.10f, Math.min(16.0f, zoomPercent / 100f));
        panOffsetX = panX;
        panOffsetY = panY;
        viewportOffsetY = isInfinite ? Math.max(0f, Math.min(scrollY, getMaxScrollY())) : 0f;
        invalidate();
        notifyZoomChange();
        notifyLassoSelection();
    }

    public float getPanOffsetX() { return panOffsetX; }
    public float getPanOffsetY() { return panOffsetY; }
    public float getViewportOffsetY() { return viewportOffsetY; }

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

    public void setFingerMoveCanvasEnabled(boolean enabled) {
        this.isFingerMoveCanvasEnabled = enabled;
        if (!enabled) {
            handPanDragging = false;
        }
        invalidate();
    }

    public boolean isFingerMoveCanvasEnabled() {
        return isFingerMoveCanvasEnabled;
    }

    public void setViewportLocked(boolean locked) {
        this.isViewportLocked = locked;
    }

    public boolean isViewportLocked() {
        return isViewportLocked;
    }

    public void setZoom(float zoomPercent) {
        float targetScale = Math.max(0.20f, Math.min(16.0f, zoomPercent / 100f));
        zoomAround(targetScale, getWidth() * 0.5f, getHeight() * 0.5f);
        invalidate();
        notifyZoomChange();
        notifyLassoSelection();
    }

    public void resetZoom() {
        float startScale = scaleFactor;
        float focusX = getWidth() * 0.5f;
        float focusY = getHeight() * 0.5f;
        float anchoredCanvasX = (focusX - panOffsetX) / Math.max(0.01f, startScale);
        float anchoredCanvasY = (focusY - panOffsetY) / Math.max(0.01f, startScale)
                + viewportOffsetY;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(280);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        anim.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            scaleFactor = startScale + (1.0f - startScale) * fraction;
            panOffsetX = focusX - anchoredCanvasX * scaleFactor;
            panOffsetY = focusY - (anchoredCanvasY - viewportOffsetY) * scaleFactor;
            invalidate();
            notifyZoomChange();
            notifyLassoSelection();
        });
        anim.start();
    }

    /** Keep the canvas point under the requested screen focus fixed while changing zoom. */
    private void zoomAround(float targetScale, float focusX, float focusY) {
        float oldScale = Math.max(0.01f, scaleFactor);
        float canvasFocusX = (focusX - panOffsetX) / oldScale;
        float canvasFocusY = (focusY - panOffsetY) / oldScale + viewportOffsetY;
        scaleFactor = Math.max(0.10f, Math.min(16.0f, targetScale));
        panOffsetX = focusX - canvasFocusX * scaleFactor;
        panOffsetY = focusY - (canvasFocusY - viewportOffsetY) * scaleFactor;
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
        if (!enabled) {
            this.isAutoConvertTextEnabled = false;
            cancelPendingAutoConversion();
        }
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
        if (selectedStrokes.isEmpty()) return;
        ActionRecord record = new ActionRecord();
        record.type = ActionRecord.Type.BEAUTIFY_SELECTION;
        record.styleTargetsStrokes = new ArrayList<>(selectedStrokes);
        record.beforeBeautifyStrokes = cloneStrokes(selectedStrokes);
        for (Stroke s : selectedStrokes) {
            applyAutoBeautification(s);
        }
        record.afterBeautifyStrokes = cloneStrokes(selectedStrokes);
        undoStack.push(record);
        redoStack.clear();
        strokeSpatialIndexDirty = true;
        updateSelectionBounds();
        invalidate();
        notifyChange();
        notifyLassoSelection();
    }

    public void applyAutoBeautification(Stroke stroke) {
        if (stroke == null || stroke.points.size() < 3) return;



        // Gently relax interior samples in place. The renderer already uses
        // quadratic segments, so subdividing every sample (which used to grow
        // each stroke by 2x or 4x) only increased redraw and save costs.
        List<DrawingPoint> smoothed = stroke.points;
        if (smoothed.size() >= 3) {
            float neighborWeight = 0.16f * beautifySmoothing;
            float centerWeight = 1f - neighborWeight * 2f;
            DrawingPoint first = smoothed.get(0);
            float previousX = first.x;
            float previousY = first.y;
            float previousPressure = first.pressure;
            for (int i = 1; i < smoothed.size() - 1; i++) {
                DrawingPoint current = smoothed.get(i);
                DrawingPoint next = smoothed.get(i + 1);
                float originalX = current.x;
                float originalY = current.y;
                float originalPressure = current.pressure;
                current.x = previousX * neighborWeight + originalX * centerWeight
                        + next.x * neighborWeight;
                current.y = previousY * neighborWeight + originalY * centerWeight
                        + next.y * neighborWeight;
                current.pressure = previousPressure * neighborWeight + originalPressure * centerWeight
                        + next.pressure * neighborWeight;
                previousX = originalX;
                previousY = originalY;
                previousPressure = originalPressure;
            }
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

        stroke.markDirty();
    }

    private boolean isAutoConvertTextEnabled = false;
    private final List<Stroke> pendingAutoConvertStrokes = new ArrayList<>();
    private final Handler autoConvertHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoConvertRunnable = () -> {
        if (!isAutoConvertTextEnabled || !isAutoBeautifyEnabled || pendingAutoConvertStrokes.isEmpty()) {
            pendingAutoConvertStrokes.clear();
            return;
        }
        if (currentPage == null) {
            pendingAutoConvertStrokes.clear();
            return;
        }
        pendingAutoConvertStrokes.retainAll(currentPage.strokes);
        if (pendingAutoConvertStrokes.isEmpty()) return;
        List<Stroke> toConvert = new ArrayList<>(pendingAutoConvertStrokes);
        if (hasEnoughInkForAutoConversion(toConvert)) {
            pendingAutoConvertStrokes.clear();
            convertStrokesToText(toConvert);
        }
    };

    /** Checks if strokes contain sufficient handwriting ink for conversion (handles single words, short words, or long cursive lines). */
    private boolean hasEnoughInkForAutoConversion(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return false;
        int pointCount = 0;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Stroke stroke : strokes) {
            if (stroke == null) continue;
            List<DrawingPoint> points = stroke.getRecognitionPoints();
            if (points == null || points.isEmpty()) points = stroke.points;
            if (points == null) continue;
            pointCount += points.size();
            for (DrawingPoint point : points) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
        }
        if (pointCount < 2 || minX == Float.MAX_VALUE) return false;
        float width = maxX - minX;
        float height = maxY - minY;
        return (width >= 3f || height >= 4f);
    }

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
        convertStrokesToText(new ArrayList<>(selectedStrokes), false);
        clearSelection();
    }

    /** Starts recognition for every handwritten stroke on the current page. */
    public boolean convertPageToBeautifulText() {
        if (currentPage == null || currentPage.strokes == null || currentPage.strokes.isEmpty()) {
            return false;
        }
        cancelPendingAutoConversion();
        clearSelection();
        convertStrokesToText(new ArrayList<>(currentPage.strokes), true);
        return true;
    }

    public void convertStrokesToText(List<Stroke> strokesToConvert) {
        convertStrokesToText(strokesToConvert, false);
    }

    private void convertStrokesToText(List<Stroke> strokesToConvert, boolean letUserChooseCandidate) {
        if (currentPage == null || strokesToConvert == null || strokesToConvert.isEmpty()) return;
        final List<Stroke> requestedStrokes = new ArrayList<>(strokesToConvert);
        final Page requestedPage = currentPage;
        final int textColor = currentColor;

        List<org.opennotes.app.ai.SemanticStrokeClusterer.StrokeLine> segmentedLines =
                org.opennotes.app.ai.SemanticStrokeClusterer.segmentIntoLines(requestedStrokes);
        if (segmentedLines == null || segmentedLines.isEmpty()) return;

        if (segmentedLines.size() == 1) {
            final String preContext = recognitionPreContext(requestedPage, requestedStrokes);

            org.opennotes.app.util.HandwritingRecognitionEngine.Callback callback =
                    new org.opennotes.app.util.HandwritingRecognitionEngine.Callback() {
                        @Override
                        public void onSuccess(org.opennotes.app.util.HandwritingRecognitionEngine.RecognitionResult result) {
                            if (currentPage != requestedPage || result == null || result.text.trim().isEmpty()) return;
                            List<RecognizedStrokeLine> lines = Collections.singletonList(
                                    new RecognizedStrokeLine(requestedStrokes, result));
                            if (letUserChooseCandidate && result.alternatives.size() > 1) {
                                showRecognitionReview(requestedPage, lines, textColor);
                            } else {
                                applyRecognizedText(requestedPage, lines, textColor,
                                        Collections.singletonList(result.text));
                            }
                        }

                        @Override
                        public void onFailure(Exception error) {
                            if (org.opennotes.app.util.HandwritingRecognitionEngine.isModelDownloadCancellation(error)) {
                                return;
                            }
                            android.widget.Toast.makeText(getContext(),
                                    "Could not decipher handwriting. Your ink was kept.",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    };

            if (letUserChooseCandidate) {
                org.opennotes.app.util.HandwritingRecognitionEngine.recognizeStrokesWithFilteredSuggestions(
                        getContext(), requestedStrokes, beautifyLanguage, preContext, callback);
            } else {
                org.opennotes.app.util.HandwritingRecognitionEngine.recognizeStrokes(
                        getContext(), requestedStrokes, beautifyLanguage, preContext, callback);
            }
        } else {
            // Multi-line handwriting: recognize each line independently to preserve
            // character accuracy, language context, and distinct vertical baselines
            recognizeLinesSequentially(requestedPage, segmentedLines, 0,
                    new ArrayList<>(), textColor, letUserChooseCandidate);
        }
    }

    private void recognizeLinesSequentially(Page requestedPage,
                                           List<org.opennotes.app.ai.SemanticStrokeClusterer.StrokeLine> segmentedLines,
                                           int index,
                                           List<RecognizedStrokeLine> accumulatedLines,
                                           int textColor,
                                           boolean letUserChooseCandidate) {
        if (currentPage != requestedPage) return;
        if (index >= segmentedLines.size()) {
            if (accumulatedLines.isEmpty()) {
                android.widget.Toast.makeText(getContext(),
                        "Could not decipher handwriting. Your ink was kept.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (letUserChooseCandidate) {
                showRecognitionReview(requestedPage, accumulatedLines, textColor);
            } else {
                List<String> reviewed = new ArrayList<>();
                for (RecognizedStrokeLine l : accumulatedLines) {
                    reviewed.add(l.result.text);
                }
                applyRecognizedText(requestedPage, accumulatedLines, textColor, reviewed);
            }
            return;
        }

        org.opennotes.app.ai.SemanticStrokeClusterer.StrokeLine strokeLine = segmentedLines.get(index);
        final List<Stroke> lineStrokes = strokeLine.strokes;
        String preContext = recognitionPreContext(requestedPage, lineStrokes, accumulatedLines);

        org.opennotes.app.util.HandwritingRecognitionEngine.Callback lineCallback =
                new org.opennotes.app.util.HandwritingRecognitionEngine.Callback() {
                    @Override
                    public void onSuccess(org.opennotes.app.util.HandwritingRecognitionEngine.RecognitionResult result) {
                        if (currentPage != requestedPage) return;
                        if (result != null && !result.text.trim().isEmpty()) {
                            accumulatedLines.add(new RecognizedStrokeLine(lineStrokes, result));
                        }
                        recognizeLinesSequentially(requestedPage, segmentedLines, index + 1,
                                accumulatedLines, textColor, letUserChooseCandidate);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        if (org.opennotes.app.util.HandwritingRecognitionEngine.isModelDownloadCancellation(error)) {
                            return;
                        }
                        recognizeLinesSequentially(requestedPage, segmentedLines, index + 1,
                                accumulatedLines, textColor, letUserChooseCandidate);
                    }
                };

        if (letUserChooseCandidate) {
            org.opennotes.app.util.HandwritingRecognitionEngine.recognizeStrokesWithFilteredSuggestions(
                    getContext(), lineStrokes, beautifyLanguage, preContext, lineCallback);
        } else {
            org.opennotes.app.util.HandwritingRecognitionEngine.recognizeStrokes(
                    getContext(), lineStrokes, beautifyLanguage, preContext, lineCallback);
        }
    }

    private void showRecognitionReview(Page requestedPage,
                                       List<RecognizedStrokeLine> lines, int textColor) {
        if (currentPage != requestedPage) return;
        android.widget.LinearLayout rows = new android.widget.LinearLayout(getContext());
        rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = dp(16);
        rows.setPadding(padding, padding, padding, padding);
        android.widget.TextView hint = new android.widget.TextView(getContext());
        hint.setText("Review or edit each line. Your handwriting stays unchanged until you choose Convert.");
        rows.addView(hint);

        List<android.widget.EditText> editors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            RecognizedStrokeLine line = lines.get(i);
            android.widget.TextView label = new android.widget.TextView(getContext());
            label.setText("Line " + (i + 1));
            label.setPadding(0, dp(14), 0, dp(4));
            rows.addView(label);

            android.widget.EditText editor = new android.widget.EditText(getContext());
            editor.setSingleLine(true);
            editor.setTextSize(17f);
            editor.setText(line.result.text);
            editor.setSelection(editor.length());
            rows.addView(editor, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            editors.add(editor);

            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(getContext());
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

            if (line.result.alternatives.size() > 1) {
                android.widget.Button alternatives = new android.widget.Button(getContext());
                alternatives.setText("Suggestions");
                org.opennotes.app.util.ThemeUtils.styleButton(alternatives, getContext(), false);
                alternatives.setOnClickListener(view -> {
                    CharSequence[] options = line.result.alternatives.toArray(new String[0]);
                    com.google.android.material.dialog.MaterialAlertDialogBuilder sugBuilder =
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                                    .setTitle("Suggestions for this line")
                                    .setItems(options, (dialog, which) -> editor.setText(options[which]))
                                    .setNegativeButton("Cancel", null);
                    org.opennotes.app.util.ThemeUtils.showStyledDialog(sugBuilder, getContext());
                });
                btnRow.addView(alternatives);
            }

            android.widget.Button btnTeach = new android.widget.Button(getContext());
            btnTeach.setText("🎓 Teach Style");
            org.opennotes.app.util.ThemeUtils.styleButton(btnTeach, getContext(), true);
            btnTeach.setOnClickListener(view -> {
                String intended = editor.getText().toString().trim();
                if (!intended.isEmpty()) {
                    org.opennotes.app.ai.UserHandwritingModel.getInstance(getContext())
                            .learnCorrection(line.result.text, intended, line.strokes);
                    android.widget.Toast.makeText(getContext(),
                            "✨ Learned! Style saved for \"" + intended + "\"",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            btnRow.addView(btnTeach);
            rows.addView(btnRow);
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
        scrollView.setFillViewport(true);
        scrollView.addView(rows);
        scrollView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(440)));

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle("Review handwriting conversion")
                .setView(scrollView)
                .setNegativeButton("Keep handwriting", null)
                .setPositiveButton("Convert", (d, which) -> {
                    List<String> reviewedText = new ArrayList<>();
                    for (int i = 0; i < editors.size(); i++) {
                        String edited = editors.get(i).getText().toString().trim();
                        reviewedText.add(edited);
                        if (i < lines.size()) {
                            RecognizedStrokeLine l = lines.get(i);
                            if (!edited.isEmpty() && !edited.equals(l.result.text)) {
                                org.opennotes.app.ai.UserHandwritingModel.getInstance(getContext())
                                        .learnCorrection(l.result.text, edited, l.strokes);
                            }
                        }
                    }
                    applyRecognizedText(requestedPage, lines, textColor, reviewedText);
                })
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, getContext());
        dialog.show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String recognitionPreContext(Page page, List<Stroke> strokes) {
        return recognitionPreContext(page, strokes, null);
    }

    private String recognitionPreContext(Page page, List<Stroke> strokes,
                                         List<RecognizedStrokeLine> precedingRecognizedLines) {
        RectF inkBounds = new RectF();
        boolean found = false;
        for (Stroke stroke : strokes) {
            if (stroke == null) continue;
            List<DrawingPoint> points = stroke.getRecognitionPoints();
            if (points == null) continue;
            for (DrawingPoint point : points) {
                if (point == null || Float.isNaN(point.x) || Float.isNaN(point.y)
                        || Float.isInfinite(point.x) || Float.isInfinite(point.y)) continue;
                if (!found) {
                    inkBounds.set(point.x, point.y, point.x, point.y);
                    found = true;
                } else {
                    inkBounds.union(point.x, point.y);
                }
            }
        }
        if (!found) return "";

        List<TextItem> preceding = new ArrayList<>();
        float baseline = inkBounds.bottom - 4f;
        float lineTolerance = Math.max(24f, inkBounds.height() * 0.65f);
        if (page.texts != null) {
            for (TextItem item : page.texts) {
                if (item == null || item.text == null || item.text.isEmpty()) continue;
                if (item.x >= inkBounds.left || Math.abs(item.y - baseline) > lineTolerance) continue;
                if (item.getBounds().right > inkBounds.left + Math.max(8f, item.fontSize * 0.15f)) continue;
                preceding.add(item);
            }
        }
        // Real-time conversion turns earlier lines into TextItems. When the
        // current handwriting starts on the next ruled line, that prior line
        // is still useful language context even though it is not on the same
        // baseline. Restrict the fallback to the nearest line in the same
        // writing column so a distant heading or neighboring column is not
        // fed to the recognizer.
        if (preceding.isEmpty() && page.texts != null) {
            float maxPreviousLineGap = Math.max(48f, inkBounds.height() * 1.8f);
            float columnTolerance = Math.max(32f, inkBounds.height() * 1.25f);
            float nearestBaseline = Float.NEGATIVE_INFINITY;
            for (TextItem item : page.texts) {
                if (item == null || item.text == null || item.text.isEmpty()) continue;
                float verticalGap = baseline - item.y;
                if (verticalGap <= lineTolerance || verticalGap > maxPreviousLineGap
                        || Math.abs(item.x - inkBounds.left) > columnTolerance) continue;
                nearestBaseline = Math.max(nearestBaseline, item.y);
            }
            if (nearestBaseline != Float.NEGATIVE_INFINITY) {
                for (TextItem item : page.texts) {
                    if (item == null || item.text == null || item.text.isEmpty()) continue;
                    float verticalGap = baseline - item.y;
                    if (verticalGap > lineTolerance && verticalGap <= maxPreviousLineGap
                            && Math.abs(item.y - nearestBaseline) <= Math.max(8f, item.fontSize * 0.35f)
                            && Math.abs(item.x - inkBounds.left) <= columnTolerance) {
                        preceding.add(item);
                    }
                }
            }
        }
        Collections.sort(preceding, (left, right) -> Float.compare(left.x, right.x));
        StringBuilder context = new StringBuilder();
        if (precedingRecognizedLines != null) {
            for (RecognizedStrokeLine line : precedingRecognizedLines) {
                if (line == null || line.result == null || line.result.text == null) continue;
                appendContextPart(context, line.result.text);
            }
        }
        for (TextItem item : preceding) {
            appendContextPart(context, item.text);
        }
        return lastCodePoints(context.toString(), 20);
    }

    private static void appendContextPart(StringBuilder context, String part) {
        if (part == null) return;
        String trimmed = part.trim();
        if (trimmed.isEmpty()) return;
        if (context.length() > 0) context.append(' ');
        context.append(trimmed);
    }

    private static String lastCodePoints(String text, int maxCodePoints) {
        if (text == null || text.isEmpty()) return "";
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount > maxCodePoints) {
            int start = text.offsetByCodePoints(0, codePointCount - maxCodePoints);
            return text.substring(start);
        }
        return text;
    }

    private void applyRecognizedText(Page requestedPage, List<RecognizedStrokeLine> lines,
                                     int textColor, List<String> reviewedText) {
        if (currentPage != requestedPage || lines == null || reviewedText == null) return;
        List<Stroke> stillPresent = new ArrayList<>();
        Set<Stroke> requestedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        List<TextItem> convertedTexts = new ArrayList<>();
        for (int i = 0; i < lines.size() && i < reviewedText.size(); i++) {
            String text = reviewedText.get(i);
            RecognizedStrokeLine line = lines.get(i);
            if (text == null || text.trim().isEmpty()) continue;
            requestedSet.addAll(line.strokes);
            org.opennotes.app.util.HandwritingRecognitionEngine.RecognitionResult result = line.result;
            String fontKey = beautifyFontKey != null && !beautifyFontKey.isEmpty()
                    ? beautifyFontKey
                    : ("ENGLISH".equalsIgnoreCase(result.language)
                    ? org.opennotes.app.util.TypefaceManager.FONT_CAVEAT
                    : org.opennotes.app.util.TypefaceManager.FONT_KALAM);

            String fullText = text.trim();
            String[] subLines = fullText.split("\r?\n");
            float linePitch = result.fontSize * 1.35f;
            if (subLines.length > 1) {
                float computedPitch = result.bounds.height() / (float) subLines.length;
                if (computedPitch >= result.fontSize * 0.8f && computedPitch <= result.fontSize * 2.5f) {
                    linePitch = computedPitch;
                }
            }
            float baseStartX = (result.startX != 0f || result.startY != 0f) ? result.startX : result.bounds.left;
            float baseStartY = (result.startX != 0f || result.startY != 0f) ? result.startY : result.bounds.bottom - 4f;
            if (subLines.length > 1 && (result.startX == 0f && result.startY == 0f || baseStartY >= result.bounds.bottom - 10f)) {
                baseStartY = result.bounds.top + linePitch * 0.85f;
            }

            for (int sIdx = 0; sIdx < subLines.length; sIdx++) {
                String subLine = subLines[sIdx].trim();
                if (subLine.isEmpty()) continue;
                float lineBaselineY = baseStartY + sIdx * linePitch;
                float lineStartX = baseStartX;

                String[] words = subLine.split("\\s+");
                textPaint.setTextSize(result.fontSize);
                textPaint.setTypeface(org.opennotes.app.util.TypefaceManager.getTypeface(getContext(), fontKey));
                float spaceWidth = textPaint.measureText(" ");

                if (words.length <= 1) {
                    convertedTexts.add(new TextItem(subLine, lineStartX, lineBaselineY, textColor, result.fontSize, fontKey, 0f));
                } else {
                    float totalWordsWidth = 0f;
                    float[] wordWidths = new float[words.length];
                    for (int w = 0; w < words.length; w++) {
                        wordWidths[w] = textPaint.measureText(words[w]);
                        totalWordsWidth += wordWidths[w];
                    }
                    float availableWidth = Math.max(result.bounds.width(), totalWordsWidth + (words.length - 1) * spaceWidth);
                    float computedSpace = Math.max(spaceWidth, (availableWidth - totalWordsWidth) / (words.length - 1));
                    computedSpace = Math.min(computedSpace, spaceWidth * 2.5f);

                    float curX = lineStartX;
                    for (int w = 0; w < words.length; w++) {
                        convertedTexts.add(new TextItem(words[w], curX, lineBaselineY, textColor, result.fontSize, fontKey, 0f));
                        curX += wordWidths[w] + computedSpace;
                    }
                }
            }
        }
        if (requestedSet.isEmpty()) return;
        Iterator<Stroke> pageStrokes = requestedPage.strokes.iterator();
        while (pageStrokes.hasNext()) {
            Stroke stroke = pageStrokes.next();
            if (requestedSet.contains(stroke)) {
                stillPresent.add(stroke);
                pageStrokes.remove();
            }
        }
        if (stillPresent.isEmpty()) return;
        requestedPage.texts.addAll(convertedTexts);
        strokeSpatialIndexDirty = true;
        ActionRecord rec = new ActionRecord();
        rec.type = ActionRecord.Type.CONVERT_TO_TEXT;
        rec.prevStrokes = stillPresent;
        rec.convertedTexts = convertedTexts;
        if (!convertedTexts.isEmpty()) rec.text = convertedTexts.get(0);
        undoStack.push(rec);
        redoStack.clear();
        invalidate();
        notifyChange();
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
        if (tool == DrawingTool.HAND) {
            setFingerMoveCanvasEnabled(true);
            return;
        }
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
            case ADD_IMAGE:
                if (currentPage.images != null) currentPage.images.remove(rec.image);
                break;
            case REMOVE_IMAGE:
                if (currentPage.images != null) currentPage.images.add(rec.image);
                break;
            case ADD_TEXT:
                currentPage.texts.remove(rec.text);
                break;
            case REMOVE_TEXT:
                currentPage.texts.add(rec.text);
                break;
            case MOVE_SELECTION:
                translateItems(rec.movedStrokes, rec.movedShapes, rec.movedTexts, rec.movedImages,
                        -rec.moveDx, -rec.moveDy);
                break;
            case ROTATE_SELECTION:
                rotateItemsAround(rec.rotatedStrokes, rec.rotatedShapes, rec.rotatedTexts, rec.rotatedImages,
                        rec.rotateCenterX, rec.rotateCenterY, -rec.rotateAngle);
                break;
            case SCALE_SELECTION:
                scaleItemsAround(rec.scaledStrokes, rec.scaledShapes, rec.scaledTexts, rec.scaledImages,
                        rec.scaleAnchorX, rec.scaleAnchorY, 1f / rec.scaleX, 1f / rec.scaleY);
                break;
            case ADD_SELECTION:
                removeRecordedSelection(rec);
                break;
            case DELETE_SELECTION:
                addRecordedSelection(rec);
                break;
            case STYLE_CHANGE:
                restoreSelectionStyle(rec, false);
                break;
            case BEAUTIFY_SELECTION:
                restoreBeautifiedSelection(rec, false);
                break;
            case CLEAR_ALL:
                currentPage.strokes = new ArrayList<>(rec.prevStrokes);
                currentPage.shapes = new ArrayList<>(rec.prevShapes);
                currentPage.texts = new ArrayList<>(rec.prevTexts);
                if (rec.prevImages != null) {
                    currentPage.images = new ArrayList<>(rec.prevImages);
                }
                break;
            case CONVERT_TO_TEXT:
                if (rec.convertedTexts != null) {
                    currentPage.texts.removeAll(rec.convertedTexts);
                } else if (rec.text != null) {
                    currentPage.texts.remove(rec.text);
                }
                if (rec.prevStrokes != null) {
                    currentPage.strokes.addAll(rec.prevStrokes);
                }
                strokeSpatialIndexDirty = true;
                break;
            default:
                break;
        }
        strokeSpatialIndexDirty = true;
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
            case ADD_IMAGE:
                if (currentPage.images != null) currentPage.images.add(rec.image);
                break;
            case REMOVE_IMAGE:
                if (currentPage.images != null) currentPage.images.remove(rec.image);
                break;
            case ADD_TEXT:
                currentPage.texts.add(rec.text);
                break;
            case REMOVE_TEXT:
                currentPage.texts.remove(rec.text);
                break;
            case MOVE_SELECTION:
                translateItems(rec.movedStrokes, rec.movedShapes, rec.movedTexts, rec.movedImages,
                        rec.moveDx, rec.moveDy);
                break;
            case ROTATE_SELECTION:
                rotateItemsAround(rec.rotatedStrokes, rec.rotatedShapes, rec.rotatedTexts, rec.rotatedImages,
                        rec.rotateCenterX, rec.rotateCenterY, rec.rotateAngle);
                break;
            case SCALE_SELECTION:
                scaleItemsAround(rec.scaledStrokes, rec.scaledShapes, rec.scaledTexts, rec.scaledImages,
                        rec.scaleAnchorX, rec.scaleAnchorY, rec.scaleX, rec.scaleY);
                break;
            case ADD_SELECTION:
                addRecordedSelection(rec);
                break;
            case DELETE_SELECTION:
                removeRecordedSelection(rec);
                break;
            case STYLE_CHANGE:
                restoreSelectionStyle(rec, true);
                break;
            case BEAUTIFY_SELECTION:
                restoreBeautifiedSelection(rec, true);
                break;
            case CLEAR_ALL:
                currentPage.strokes.clear();
                currentPage.shapes.clear();
                currentPage.texts.clear();
                if (currentPage.images != null) currentPage.images.clear();
                break;
            case CONVERT_TO_TEXT:
                if (rec.prevStrokes != null) {
                    currentPage.strokes.removeAll(rec.prevStrokes);
                }
                if (rec.convertedTexts != null) {
                    currentPage.texts.addAll(rec.convertedTexts);
                } else if (rec.text != null) {
                    currentPage.texts.add(rec.text);
                }
                strokeSpatialIndexDirty = true;
                break;
            default:
                break;
        }
        strokeSpatialIndexDirty = true;
        clearSelection();
        undoStack.push(rec);
        invalidate();
        notifyChange();
    }

    public void clearAll() {
        if (currentPage == null) return;
        boolean hasStrokes = currentPage.strokes != null && !currentPage.strokes.isEmpty();
        boolean hasShapes = currentPage.shapes != null && !currentPage.shapes.isEmpty();
        boolean hasTexts = currentPage.texts != null && !currentPage.texts.isEmpty();
        boolean hasImages = currentPage.images != null && !currentPage.images.isEmpty();
        if (!hasStrokes && !hasShapes && !hasTexts && !hasImages) return;

        ActionRecord rec = new ActionRecord();
        rec.type = ActionRecord.Type.CLEAR_ALL;
        rec.prevStrokes = new ArrayList<>(currentPage.strokes);
        rec.prevShapes = new ArrayList<>(currentPage.shapes);
        rec.prevTexts = new ArrayList<>(currentPage.texts);
        if (currentPage.images != null) {
            rec.prevImages = new ArrayList<>(currentPage.images);
            currentPage.images.clear();
        }

        currentPage.strokes.clear();
        currentPage.shapes.clear();
        currentPage.texts.clear();

        undoStack.push(rec);
        redoStack.clear();
        clearSelection();
        invalidate();
        notifyChange();
    }

    private void addRecordedSelection(ActionRecord record) {
        if (currentPage == null || record == null) return;
        if (record.prevStrokes != null) currentPage.strokes.addAll(record.prevStrokes);
        if (record.prevShapes != null) currentPage.shapes.addAll(record.prevShapes);
        if (record.prevTexts != null) currentPage.texts.addAll(record.prevTexts);
        if (record.prevImages != null && currentPage.images != null) currentPage.images.addAll(record.prevImages);
    }

    private void removeRecordedSelection(ActionRecord record) {
        if (currentPage == null || record == null) return;
        if (record.prevStrokes != null) currentPage.strokes.removeAll(record.prevStrokes);
        if (record.prevShapes != null) currentPage.shapes.removeAll(record.prevShapes);
        if (record.prevTexts != null) currentPage.texts.removeAll(record.prevTexts);
        if (record.prevImages != null && currentPage.images != null) currentPage.images.removeAll(record.prevImages);
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

    public void addImageItem(ImageItem item) {
        if (currentPage == null || item == null) return;
        if (currentPage.images == null) {
            currentPage.images = new ArrayList<>();
        }
        currentPage.images.add(item);

        ActionRecord rec = new ActionRecord();
        rec.type = ActionRecord.Type.ADD_IMAGE;
        rec.image = item;
        undoStack.push(rec);
        redoStack.clear();

        ensureContentHeight(item.y + item.height + 100);
        invalidate();
        notifyChange();
    }

    public float getCenterCanvasX() {
        return ((getWidth() * 0.5f) - panOffsetX) / scaleFactor;
    }

    public float getCenterCanvasY() {
        return ((getHeight() * 0.5f) - panOffsetY) / scaleFactor + viewportOffsetY;
    }

    private void drawImage(Canvas canvas, ImageItem img) {
        if (img == null || img.imagePath == null) return;
        Bitmap bmp = imageCache.get(img.imagePath);
        if (bmp == null || bmp.isRecycled()) {
            try {
                File file = new File(img.imagePath);
                if (file.exists()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(img.imagePath, opts);
                    opts.inSampleSize = 1;
                    int maxDim = Math.max(opts.outWidth, opts.outHeight);
                    while (maxDim / opts.inSampleSize > 2048) {
                        opts.inSampleSize *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    bmp = BitmapFactory.decodeFile(img.imagePath, opts);
                    if (bmp != null) {
                        imageCache.put(img.imagePath, bmp);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (bmp != null && !bmp.isRecycled()) {
            canvas.drawBitmap(bmp, null, img.getBounds(), null);
        } else {
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(0x88888888);
            border.setStrokeWidth(2f);
            canvas.drawRect(img.getBounds(), border);
        }
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

    private void prepareVisibleStrokeIndices(RectF viewport) {
        if (currentPage == null || currentPage.strokes == null) {
            visibleStrokeIndices.clear();
            return;
        }
        ensureStrokeSpatialIndex();
        List<Stroke> strokes = currentPage.strokes;
        visibleStrokeIndices.clear();
        if (isMovingSelection) {
            visibleStrokeIndices.set(0, strokes.size());
            return;
        }

        int minCellX = (int) Math.floor(viewport.left / STROKE_GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(viewport.right / STROKE_GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(viewport.top / STROKE_GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(viewport.bottom / STROKE_GRID_CELL_SIZE);
        long cellsWide = (long) maxCellX - minCellX + 1L;
        long cellsHigh = (long) maxCellY - minCellY + 1L;
        if (cellsWide <= 0L || cellsHigh <= 0L
                || cellsWide > MAX_VISIBLE_GRID_CELLS / cellsHigh) {
            visibleStrokeIndices.set(0, strokes.size());
            return;
        }

        for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<Integer> cellStrokes = strokeSpatialGrid.get(strokeCellKey(cellX, cellY));
                if (cellStrokes == null) continue;
                for (int index : cellStrokes) visibleStrokeIndices.set(index);
            }
        }
        for (int index : oversizedStrokeIndices) visibleStrokeIndices.set(index);
    }

    private void ensureStrokeSpatialIndex() {
        List<Stroke> strokes = currentPage.strokes;
        if (spatialIndexPage != currentPage) strokeSpatialIndexDirty = true;
        if (!strokeSpatialIndexDirty && strokes.size() < spatialIndexStrokeCount) {
            strokeSpatialIndexDirty = true;
        }

        if (strokeSpatialIndexDirty) {
            spatialIndexPage = currentPage;
            strokeSpatialGrid.clear();
            oversizedStrokeIndices.clear();
            for (int i = 0; i < strokes.size(); i++) addStrokeToSpatialIndex(strokes.get(i), i);
            spatialIndexStrokeCount = strokes.size();
            strokeSpatialIndexDirty = false;
        } else {
            for (int i = spatialIndexStrokeCount; i < strokes.size(); i++) {
                addStrokeToSpatialIndex(strokes.get(i), i);
            }
            spatialIndexStrokeCount = strokes.size();
        }
    }

    private void addStrokeToSpatialIndex(Stroke stroke, int index) {
        if (stroke == null) return;
        RectF bounds = stroke.getBounds();
        int minCellX = (int) Math.floor(bounds.left / STROKE_GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(bounds.right / STROKE_GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(bounds.top / STROKE_GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(bounds.bottom / STROKE_GRID_CELL_SIZE);
        long cellsWide = (long) maxCellX - minCellX + 1L;
        long cellsHigh = (long) maxCellY - minCellY + 1L;
        if (cellsWide <= 0L || cellsHigh <= 0L
                || cellsWide > MAX_STROKE_GRID_CELLS / cellsHigh) {
            oversizedStrokeIndices.add(index);
            return;
        }
        for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                long key = strokeCellKey(cellX, cellY);
                List<Integer> cellStrokes = strokeSpatialGrid.get(key);
                if (cellStrokes == null) {
                    cellStrokes = new ArrayList<>();
                    strokeSpatialGrid.put(key, cellStrokes);
                }
                cellStrokes.add(index);
            }
        }
    }

    private long strokeCellKey(int cellX, int cellY) {
        return ((long) cellX << 32) | (cellY & 0xffffffffL);
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
        if (!canvas.getClipBounds(visibleClip)) {
            visibleClip.set(0, 0, viewWidth, viewHeight);
        }
        if (!visibleClip.intersect(0, 0, viewWidth, viewHeight)) {
            visibleClip.set(0, 0, viewWidth, viewHeight);
        }
        float vLeft = (visibleClip.left - panOffsetX) / scaleFactor;
        float vTop = (visibleClip.top - panOffsetY) / scaleFactor + viewportOffsetY;
        float vRight = (visibleClip.right - panOffsetX) / scaleFactor;
        float vBottom = (visibleClip.bottom - panOffsetY) / scaleFactor + viewportOffsetY;
        float pad = 2f / Math.max(0.2f, scaleFactor);
        visibleViewport.set(vLeft - pad, vTop - pad, vRight + pad, vBottom + pad);

        canvas.save();
        // Zoom and Pan transformations
        canvas.translate(panOffsetX, panOffsetY - viewportOffsetY * scaleFactor);
        canvas.scale(scaleFactor, scaleFactor);

        // 2. Draw Paper Template lines (culled to visible viewport)
        drawPaperTemplate(canvas, viewWidth, effectiveHeight, isDarkBackground, scaleFactor);

        if (currentPage != null) {
            prepareVisibleStrokeIndices(visibleViewport);

            // 2.5. Draw Committed Images (culled to visible screen)
            if (currentPage.images != null) {
                for (ImageItem img : currentPage.images) {
                    if (img != null && RectF.intersects(img.getBounds(), visibleViewport)) {
                        if (isTransformingSelection() && selectedImages.contains(img)) continue;
                        drawImage(canvas, img);
                    }
                }
            }

            // 3. Draw Committed Shapes (culled to visible screen)
            for (ShapeItem shape : currentPage.shapes) {
                if (!RectF.intersects(shape.getBounds(), visibleViewport)) continue;
                if (isTransformingSelection() && selectedShapes.contains(shape)) continue;
                drawShape(canvas, shape.type, shape.startX, shape.startY, shape.endX, shape.endY, shape.color, shape.strokeWidth, shape.lineStyle, shape.fillColor);
            }

            // 4. Draw Committed Text (culled to visible screen)
            for (TextItem item : currentPage.texts) {
                if (!RectF.intersects(item.getBounds(), visibleViewport)) continue;
                if (isTransformingSelection() && selectedTexts.contains(item)) continue;
                textPaint.setColor(item.color);
                textPaint.setTextSize(item.fontSize);
                textPaint.setTypeface(getCachedTypeface(item.fontKey));
                if (Math.abs(item.rotationAngle) > 0.5f) {
                    canvas.save();
                    canvas.rotate(item.rotationAngle, item.x, item.y);
                    canvas.drawText(item.text, item.x, item.y, textPaint);
                    canvas.restore();
                } else {
                    canvas.drawText(item.text, item.x, item.y, textPaint);
                }
            }

            // 5. Draw only the visible spatial-grid candidates, preserving ink order.
            for (int index = visibleStrokeIndices.nextSetBit(0); index >= 0;
                 index = visibleStrokeIndices.nextSetBit(index + 1)) {
                if (index >= currentPage.strokes.size()) break;
                Stroke stroke = currentPage.strokes.get(index);
                if (!RectF.intersects(stroke.getBounds(), visibleViewport)) continue;
                if (isTransformingSelection() && selectedStrokes.contains(stroke)) continue;
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
                canvas.save();
                if (isRotatingSelection && Math.abs(currentLiveRotation) > 0.01f) {
                    canvas.rotate(currentLiveRotation, rotationCenterX, rotationCenterY);
                } else if (isResizingSelection && Math.abs(currentLiveScaleX - 1.0f) > 0.001f) {
                    canvas.scale(currentLiveScaleX, currentLiveScaleY, transformAnchorX, transformAnchorY);
                }

                if (isTransformingSelection()) {
                    drawTransformingSelectedItems(canvas);
                }

                canvas.drawRoundRect(selectionBounds, 12f, 12f, selectionBoxPaint);

                // Draw 4 Corner Handles (StarNote style 100x100 circle: white filled with #625AFF border)
                float handleRadius = 8f / Math.max(0.5f, scaleFactor);
                drawSelectionCornerHandle(canvas, selectionBounds.left, selectionBounds.top, handleRadius);
                drawSelectionCornerHandle(canvas, selectionBounds.right, selectionBounds.top, handleRadius);
                drawSelectionCornerHandle(canvas, selectionBounds.left, selectionBounds.bottom, handleRadius);
                drawSelectionCornerHandle(canvas, selectionBounds.right, selectionBounds.bottom, handleRadius);

                // Draw Rotation Stalk and Handle Knob on Right Side of Selection
                float stalkLength = 46f / Math.max(0.5f, scaleFactor);
                float rotKnobX = selectionBounds.right + stalkLength;
                float rotKnobY = selectionBounds.centerY();
                float rotKnobRadius = 18f / Math.max(0.5f, scaleFactor);

                canvas.drawLine(selectionBounds.right, rotKnobY, rotKnobX, rotKnobY, selectionBoxPaint);
                canvas.drawCircle(rotKnobX, rotKnobY, rotKnobRadius, selectionRotateHandlePaint);
                selectionRotateBorderPaint.setStrokeWidth(2.5f / Math.max(0.5f, scaleFactor));
                canvas.drawCircle(rotKnobX, rotKnobY, rotKnobRadius, selectionRotateBorderPaint);

                drawRotationIcon(canvas, rotKnobX, rotKnobY, rotKnobRadius * 0.58f);

                canvas.restore();

                if (isRotatingSelection) {
                    float stalkL = 46f / Math.max(0.5f, scaleFactor);
                    float unrotKnobX = selectionBounds.right + stalkL;
                    float unrotKnobY = selectionBounds.centerY();
                    double rad = Math.toRadians(currentLiveRotation);
                    float cos = (float) Math.cos(rad);
                    float sin = (float) Math.sin(rad);
                    float dx = unrotKnobX - rotationCenterX;
                    float dy = unrotKnobY - rotationCenterY;
                    float badgeX = rotationCenterX + dx * cos - dy * sin;
                    float badgeY = rotationCenterY + dx * sin + dy * cos;
                    float rRadius = 18f / Math.max(0.5f, scaleFactor);
                    drawRotationAngleBadge(canvas, badgeX, badgeY - rRadius - 12f / Math.max(0.5f, scaleFactor));
                } else if (isResizingSelection) {
                    int percent = Math.round(currentLiveScaleX * 100f);
                    drawResizingScaleBadge(canvas, selectionBounds.centerX(), selectionBounds.top - 18f / Math.max(0.5f, scaleFactor), percent + "%");
                }
            }

            // 9. Draw Laser Pointer Presentation Trail & Glowing Tip
            if (!laserTrail.isEmpty()) {
                long now = SystemClock.uptimeMillis();
                int expiredCount = 0;
                while (expiredCount < laserTrail.size()
                        && now - laserTrail.get(expiredCount).timestamp > 1200) {
                    expiredCount++;
                }
                if (expiredCount > 0) laserTrail.subList(0, expiredCount).clear();
                Arrays.fill(usedLaserFadePaths, false);
                for (Path path : laserFadePaths) path.reset();
                if (laserTrail.size() >= 2) {
                    for (int i = 0; i < laserTrail.size() - 1; i++) {
                        LaserPoint p1 = laserTrail.get(i);
                        LaserPoint p2 = laserTrail.get(i + 1);
                        // A pen lift starts a new path; never draw a connector
                        // between independent laser gestures.
                        if (p2.startsSubpath) continue;
                        LaserPoint p0 = i > 0 && !p1.startsSubpath
                                ? laserTrail.get(i - 1) : p1;
                        LaserPoint p3 = i + 2 < laserTrail.size()
                                && !laserTrail.get(i + 2).startsSubpath
                                ? laserTrail.get(i + 2) : p2;
                        float lifeRatio = Math.max(0.05f,
                                1.0f - (float) (now - p2.timestamp) / 1200f);
                        int bucket = Math.min(LASER_FADE_BUCKETS - 1,
                                (int) (lifeRatio * LASER_FADE_BUCKETS));
                        Path path = laserFadePaths[bucket];
                        float control1X = p1.x + (p2.x - p0.x) / 6f;
                        float control1Y = p1.y + (p2.y - p0.y) / 6f;
                        float control2X = p2.x - (p3.x - p1.x) / 6f;
                        float control2Y = p2.y - (p3.y - p1.y) / 6f;
                        path.moveTo(p1.x, p1.y);
                        path.cubicTo(control1X, control1Y, control2X, control2Y,
                                p2.x, p2.y);
                        usedLaserFadePaths[bucket] = true;
                    }
                }
                for (int bucket = 0; bucket < LASER_FADE_BUCKETS; bucket++) {
                    if (!usedLaserFadePaths[bucket]) continue;
                    float lifeRatio = (bucket + 0.5f) / LASER_FADE_BUCKETS;
                    laserGlowPaint.setAlpha((int) (200 * lifeRatio));
                    laserGlowPaint.setStrokeWidth(16f * lifeRatio);
                    canvas.drawPath(laserFadePaths[bucket], laserGlowPaint);
                    laserCorePaint.setAlpha((int) (255 * lifeRatio));
                    laserCorePaint.setStrokeWidth(4.5f * lifeRatio);
                    canvas.drawPath(laserFadePaths[bucket], laserCorePaint);
                }
                if (!laserTrail.isEmpty()) {
                    LaserPoint tip = laserTrail.get(laserTrail.size() - 1);
                    canvas.drawCircle(tip.x, tip.y, 8f, laserTipPaint);
                    laserCorePaint.setAlpha(255);
                    laserCorePaint.setStrokeWidth(4.5f);
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

    private Typeface getCachedTypeface(String fontKey) {
        String key = (fontKey == null || fontKey.isEmpty())
                ? org.opennotes.app.util.TypefaceManager.FONT_KALAM : fontKey;
        Typeface typeface = textTypefaceCache.get(key);
        if (typeface == null) {
            typeface = org.opennotes.app.util.TypefaceManager.getTypeface(getContext(), key);
            textTypefaceCache.put(key, typeface);
        }
        return typeface;
    }

    public void clearTextTypefaceCache() {
        textTypefaceCache.clear();
        invalidate();
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
        syncAccessibilityActions();
        if (lassoSelectionListener != null) {
            lassoSelectionListener.onSelectionChanged(hasSelection(), getSelectionScreenBounds());
        }
    }

    private boolean hasPageContent() {
        return currentPage != null && ((!currentPage.strokes.isEmpty())
                || (!currentPage.shapes.isEmpty()) || (!currentPage.texts.isEmpty()));
    }

    private int updateAccessibilityAction(int actionId, boolean available, CharSequence label,
                                          AccessibilityViewCommand command, boolean[] changed) {
        if (available && actionId < 0) {
            changed[0] = true;
            return ViewCompat.addAccessibilityAction(this, label, command);
        }
        if (!available && actionId >= 0) {
            ViewCompat.removeAccessibilityAction(this, actionId);
            changed[0] = true;
            return -1;
        }
        return actionId;
    }

    private void syncAccessibilityActions() {
        boolean selection = hasSelection();
        boolean[] changed = {false};
        selectAllAccessibilityAction = updateAccessibilityAction(selectAllAccessibilityAction,
                hasPageContent(), "Select all content",
                (view, arguments) -> selectAllContent(), changed);
        copyAccessibilityAction = updateAccessibilityAction(copyAccessibilityAction, selection,
                "Copy selection", (view, arguments) -> {
                    copySelection();
                    announceForAccessibility("Selection copied. Use Paste to place it.");
                    return true;
                }, changed);
        cutAccessibilityAction = updateAccessibilityAction(cutAccessibilityAction, selection,
                "Cut selection", (view, arguments) -> {
                    cutSelection();
                    return true;
                }, changed);
        deleteAccessibilityAction = updateAccessibilityAction(deleteAccessibilityAction, selection,
                "Delete selection", (view, arguments) -> {
                    deleteSelection();
                    return true;
                }, changed);
        duplicateAccessibilityAction = updateAccessibilityAction(duplicateAccessibilityAction, selection,
                "Duplicate selection", (view, arguments) -> {
                    duplicateSelection();
                    return true;
                }, changed);
        pasteAccessibilityAction = updateAccessibilityAction(pasteAccessibilityAction,
                hasClipboardContents(), "Paste clipboard content",
                (view, arguments) -> {
                    pasteSelection();
                    return true;
                }, changed);
        undoAccessibilityAction = updateAccessibilityAction(undoAccessibilityAction, canUndo(),
                "Undo last canvas change", (view, arguments) -> {
                    undo();
                    announceForAccessibility("Last canvas change undone.");
                    return true;
                }, changed);
        redoAccessibilityAction = updateAccessibilityAction(redoAccessibilityAction, canRedo(),
                "Redo canvas change", (view, arguments) -> {
                    redo();
                    announceForAccessibility("Canvas change redone.");
                    return true;
                }, changed);
        if (changed[0]) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
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

    private void drawPaperTemplate(Canvas canvas, int width, float totalHeight, boolean isDark, float drawingScale) {
        if (paperTemplate == PaperTemplate.BLANK) return;

        // Thin template guides otherwise fade into sub-pixel coverage at low zoom. Keep
        // their screen-space weight legible while preserving the lighter look at 1x+.
        float safeScale = Math.max(0.01f, drawingScale);
        float lowZoomBoost = Math.max(0f, Math.min(1f, (0.75f - safeScale) / 0.55f));
        int lightLineColor = blendTemplateColor(0xFFE2E8F0, 0xFFB8C2CE, lowZoomBoost);
        int darkLineColor = blendTemplateColor(0x22FFFFFF, 0x66FFFFFF, lowZoomBoost);
        templateLinePaint.setColor(isDark ? darkLineColor : lightLineColor);
        templateLinePaint.setStrokeWidth(Math.max(1.25f, 1.5f * safeScale) / safeScale);
        templateMarginPaint.setColor(isDark ? 0x55F87171 : 0xFFFCA5A5);
        templateMarginPaint.setStrokeWidth(Math.max(1.5f, 2.0f * safeScale) / safeScale);

        if (paperTemplate == PaperTemplate.RULED) {
            float lineSpacing = 60f;
            float marginX = 140f;
            if (marginX >= visibleViewport.left - 50f && marginX <= visibleViewport.right + 50f) {
                canvas.drawLine(marginX, visibleViewport.top - 100f, marginX, visibleViewport.bottom + 100f, templateMarginPaint);
            }

            // Generate seamlessly across the entire visible screen at any zoom level
            float firstLineY = ((float) Math.floor((visibleViewport.top - lineSpacing) / lineSpacing)) * lineSpacing;
            float lastLineY = visibleViewport.bottom + lineSpacing * 2f;
            float drawX1 = visibleViewport.left - 200f;
            float drawX2 = visibleViewport.right + 200f;

            for (float y = firstLineY; y <= lastLineY; y += lineSpacing) {
                canvas.drawLine(drawX1, y, drawX2, y, templateLinePaint);
            }
        } else if (paperTemplate == PaperTemplate.GRID) {
            float gridSize = 50f;
            float firstX = ((float) Math.floor((visibleViewport.left - gridSize) / gridSize)) * gridSize;
            float lastX = visibleViewport.right + gridSize * 2f;
            float firstY = ((float) Math.floor((visibleViewport.top - gridSize) / gridSize)) * gridSize;
            float lastY = visibleViewport.bottom + gridSize * 2f;

            for (float x = firstX; x <= lastX; x += gridSize) {
                canvas.drawLine(x, firstY, x, lastY, templateLinePaint);
            }

            for (float y = firstY; y <= lastY; y += gridSize) {
                canvas.drawLine(firstX, y, lastX, y, templateLinePaint);
            }
        } else if (paperTemplate == PaperTemplate.DOTS) {
            float dotSpacing = 50f;
            int stride = safeScale < 0.25f ? 2 : 1;
            float effectiveSpacing = dotSpacing * stride;
            templateDotPaint.setColor(isDark
                    ? blendTemplateColor(0x33FFFFFF, 0x77FFFFFF, lowZoomBoost)
                    : blendTemplateColor(0xFFCBD5E1, 0xFFAAB4C0, lowZoomBoost));
            float dotRadius = Math.max(0.75f, 2.5f * safeScale) / safeScale;
            float firstX = ((float) Math.floor((visibleViewport.left - effectiveSpacing) / effectiveSpacing)) * effectiveSpacing;
            float lastX = visibleViewport.right + effectiveSpacing;
            float firstY = ((float) Math.floor((visibleViewport.top - effectiveSpacing) / effectiveSpacing)) * effectiveSpacing;
            float lastY = visibleViewport.bottom + effectiveSpacing;

            for (float x = firstX; x <= lastX; x += effectiveSpacing) {
                for (float y = firstY; y <= lastY; y += effectiveSpacing) {
                    canvas.drawCircle(x, y, dotRadius, templateDotPaint);
                }
            }
        } else if (paperTemplate == PaperTemplate.CORNELL) {
            float headerY = 140f;
            float marginX = 240f;
            if (headerY >= visibleViewport.top - 50f && headerY <= visibleViewport.bottom + 50f) {
                canvas.drawLine(visibleViewport.left - 100f, headerY, visibleViewport.right + 100f, headerY, templateLinePaint);
            }
            if (marginX >= visibleViewport.left - 50f && marginX <= visibleViewport.right + 50f) {
                canvas.drawLine(marginX, Math.max(headerY, visibleViewport.top - 100f), marginX, visibleViewport.bottom + 100f, templateMarginPaint);
            }
            float lineSpacing = 55f;
            float firstY = Math.max(headerY + lineSpacing, ((float) Math.floor((visibleViewport.top - lineSpacing) / lineSpacing)) * lineSpacing);
            float lastY = visibleViewport.bottom + lineSpacing * 2f;
            float minLineX = Math.max(marginX, visibleViewport.left - 100f);
            float maxLineX = visibleViewport.right + 100f;

            for (float y = firstY; y <= lastY; y += lineSpacing) {
                canvas.drawLine(minLineX, y, maxLineX, y, templateLinePaint);
            }
        }
    }

    private static int blendTemplateColor(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t);
        int g = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points == null || stroke.points.isEmpty()) return;

        // Fountain and Brush calculate their own smooth calligraphic pressure paths (including single dots)
        if (stroke.tool == DrawingTool.FOUNTAIN) {
            drawFountainStroke(canvas, stroke);
            return;
        } else if (stroke.tool == DrawingTool.BRUSH) {
            drawBrushStroke(canvas, stroke);
            return;
        }

        // Support single tap dots / periods for pen, pencil, and highlighter
        if (stroke.points.size() == 1) {
            DrawingPoint pt = stroke.points.get(0);
            Paint dotPaint = strokePaint;
            dotPaint.setColor(stroke.color);
            dotPaint.setStyle(Paint.Style.FILL);
            float dotRadius = Math.max(1.0f, stroke.width / 2.0f);
            if (stroke.tool == DrawingTool.HIGHLIGHTER) {
                dotPaint.setAlpha(100);
                dotRadius = stroke.width * 1.5f;
            } else if (stroke.tool == DrawingTool.PENCIL) {
                dotPaint.setAlpha(180);
                dotRadius = Math.max(1.0f, stroke.width * 0.35f);
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
        } else {
            paint = strokePaint;
            paint.setColor(stroke.color);
            paint.setAlpha(255);
            paint.setStrokeWidth(stroke.width);
        }

        if (stroke.lineStyle == Stroke.LineStyle.DASH) {
            float dash = Math.max(8f, stroke.width * 3.0f);
            float gap = Math.max(6f, stroke.width * 2.0f);
            paint.setPathEffect(new DashPathEffect(new float[]{dash, gap}, 0f));
        } else if (stroke.lineStyle == Stroke.LineStyle.DOT) {
            float dot = Math.max(2f, stroke.width * 0.8f);
            float gap = Math.max(6f, stroke.width * 2.2f);
            paint.setPathEffect(new DashPathEffect(new float[]{dot, gap}, 0f));
        } else {
            paint.setPathEffect(null);
        }

        canvas.drawPath(stroke.getPath(), paint);
        paint.setPathEffect(null);
    }

    private void drawFountainStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        fountainPaint.setColor(stroke.color);
        fountainPaint.setAlpha(255);
        fountainPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(stroke.getPressurePath(pressureSensitivity), fountainPaint);
        fountainPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawBrushStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        brushPaint.setColor(stroke.color);
        brushPaint.setAlpha(220);
        brushPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(stroke.getPressurePath(pressureSensitivity), brushPaint);
        brushPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawRotationIcon(Canvas canvas, float cx, float cy, float r) {
        float strokeW = Math.max(1.8f, 2.2f / Math.max(0.5f, scaleFactor));
        selectionRotateIconPaint.setStrokeWidth(strokeW);
        RectF arcRect = new RectF(cx - r, cy - r, cx + r, cy + r);
        // Draw 260° arc from -40° to 220° (clockwise)
        canvas.drawArc(arcRect, -40f, 260f, false, selectionRotateIconPaint);

        // Arrow head at arc start (-40 degrees)
        double rad = Math.toRadians(-40.0);
        float tipX = cx + (float) (r * Math.cos(rad));
        float tipY = cy + (float) (r * Math.sin(rad));
        float arrowSize = r * 0.70f;
        canvas.drawLine(tipX, tipY, tipX + arrowSize * 0.75f, tipY - arrowSize * 0.15f, selectionRotateIconPaint);
        canvas.drawLine(tipX, tipY, tipX - arrowSize * 0.15f, tipY - arrowSize * 0.75f, selectionRotateIconPaint);
    }

    private void drawRotationAngleBadge(Canvas canvas, float cx, float topY) {
        int rounded = Math.round(currentLiveRotation);
        while (rounded > 180) rounded -= 360;
        while (rounded <= -180) rounded += 360;
        String label = (rounded > 0 ? "+" : "") + rounded + "°";
        float textSize = Math.max(11f, 13f / Math.max(0.5f, scaleFactor));
        selectionRotateTextPaint.setTextSize(textSize);
        float textW = selectionRotateTextPaint.measureText(label);
        float padH = 10f / Math.max(0.5f, scaleFactor);
        float padV = 5f / Math.max(0.5f, scaleFactor);
        float pillW = textW + padH * 2f;
        float pillH = textSize + padV * 2f;
        RectF pillRect = new RectF(cx - pillW / 2f, topY - pillH, cx + pillW / 2f, topY);
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, selectionRotateBadgePaint);
        Paint.FontMetrics fm = selectionRotateTextPaint.getFontMetrics();
        float textY = pillRect.centerY() - (fm.descent + fm.ascent) / 2f;
        canvas.drawText(label, cx, textY, selectionRotateTextPaint);
    }

    private void drawResizingScaleBadge(Canvas canvas, float cx, float topY, String label) {
        float textSize = Math.max(11f, 13f / Math.max(0.5f, scaleFactor));
        selectionRotateTextPaint.setTextSize(textSize);
        float textW = selectionRotateTextPaint.measureText(label);
        float padH = 10f / Math.max(0.5f, scaleFactor);
        float padV = 5f / Math.max(0.5f, scaleFactor);
        float pillW = textW + padH * 2f;
        float pillH = textSize + padV * 2f;
        RectF pillRect = new RectF(cx - pillW / 2f, topY - pillH, cx + pillW / 2f, topY);
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, selectionRotateBadgePaint);
        Paint.FontMetrics fm = selectionRotateTextPaint.getFontMetrics();
        float textY = pillRect.centerY() - (fm.descent + fm.ascent) / 2f;
        canvas.drawText(label, cx, textY, selectionRotateTextPaint);
    }

    private void drawSelectionCornerHandle(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, selectionHandlePaint);
        selectionHandleBorderPaint.setStrokeWidth(2.2f / Math.max(0.5f, scaleFactor));
        canvas.drawCircle(x, y, radius, selectionHandleBorderPaint);
    }

    private boolean isTransformingSelection() {
        return (isRotatingSelection && Math.abs(currentLiveRotation) > 0.01f)
                || (isResizingSelection && Math.abs(currentLiveScaleX - 1.0f) > 0.001f);
    }

    private void drawTransformingSelectedItems(Canvas canvas) {
        if (selectedImages != null) {
            for (ImageItem img : selectedImages) {
                if (img != null) drawImage(canvas, img);
            }
        }
        if (selectedShapes != null) {
            for (ShapeItem shape : selectedShapes) {
                if (shape != null) {
                    drawShape(canvas, shape.type, shape.startX, shape.startY, shape.endX, shape.endY, shape.color, shape.strokeWidth, shape.lineStyle, shape.fillColor);
                }
            }
        }
        if (selectedTexts != null) {
            for (TextItem item : selectedTexts) {
                if (item != null) {
                    textPaint.setColor(item.color);
                    textPaint.setTextSize(item.fontSize);
                    textPaint.setTypeface(getCachedTypeface(item.fontKey));
                    if (Math.abs(item.rotationAngle) > 0.5f) {
                        canvas.save();
                        canvas.rotate(item.rotationAngle, item.x, item.y);
                        canvas.drawText(item.text, item.x, item.y, textPaint);
                        canvas.restore();
                    } else {
                        canvas.drawText(item.text, item.x, item.y, textPaint);
                    }
                }
            }
        }
        if (selectedStrokes != null) {
            for (Stroke stroke : selectedStrokes) {
                if (stroke != null) drawStroke(canvas, stroke);
            }
        }
    }

    private void drawShape(Canvas canvas, ShapeItem.Type type, float x1, float y1, float x2, float y2, int color, float strokeWidth) {
        drawShape(canvas, type, x1, y1, x2, y2, color, strokeWidth, Stroke.LineStyle.SOLID, null);
    }

    private void drawShape(Canvas canvas, ShapeItem.Type type, float x1, float y1, float x2, float y2, int color, float strokeWidth, Stroke.LineStyle lineStyle, Integer fillColor) {
        if (fillColor != null && fillColor != 0) {
            previewShapeFillPaint.setColor(fillColor);
            previewShapeFillPaint.setStyle(Paint.Style.FILL);
            switch (type) {
                case RECTANGLE:
                case TABLE:
                    canvas.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), previewShapeFillPaint);
                    break;
                case CIRCLE:
                    RectF oval = new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
                    canvas.drawOval(oval, previewShapeFillPaint);
                    break;
                case TRIANGLE:
                    drawTriangle(canvas, x1, y1, x2, y2, previewShapeFillPaint);
                    break;
                case STAR:
                    drawStar(canvas, x1, y1, x2, y2, previewShapeFillPaint);
                    break;
                default:
                    break;
            }
        }

        previewShapePaint.setColor(color);
        previewShapePaint.setStrokeWidth(strokeWidth);
        if (lineStyle == Stroke.LineStyle.DASH) {
            float dash = Math.max(8f, strokeWidth * 3.0f);
            float gap = Math.max(6f, strokeWidth * 2.0f);
            previewShapePaint.setPathEffect(new DashPathEffect(new float[]{dash, gap}, 0f));
        } else if (lineStyle == Stroke.LineStyle.DOT) {
            float dot = Math.max(2f, strokeWidth * 0.8f);
            float gap = Math.max(6f, strokeWidth * 2.2f);
            previewShapePaint.setPathEffect(new DashPathEffect(new float[]{dot, gap}, 0f));
        } else {
            previewShapePaint.setPathEffect(null);
        }

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
        previewShapePaint.setPathEffect(null);
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
        isMovingSelection = false;
        selectionMoveDx = 0f;
        selectionMoveDy = 0f;
        hasLassoSelection = false;
        selectedStrokes.clear();
        selectedShapes.clear();
        selectedTexts.clear();
        selectedImages.clear();
        selectionBounds.setEmpty();
        lassoPath.reset();
        lassoPoints.clear();
        lastAnnouncedSelection = "";
        setContentDescription("Note page. Use the lasso tool to select ink, shapes, images, and text. Drag a selection to move it.");
        invalidate();
        notifyLassoSelection();
    }

    /** Select every content object on the current page using lasso semantics. */
    public boolean selectAllContent() {
        if (currentPage == null) return false;
        selectedStrokes.clear();
        selectedShapes.clear();
        selectedTexts.clear();
        selectedImages.clear();
        if (currentPage.strokes != null) {
            for (Stroke item : currentPage.strokes) {
                if (item != null && !item.getBounds().isEmpty()) selectedStrokes.add(item);
            }
        }
        if (currentPage.shapes != null) {
            for (ShapeItem item : currentPage.shapes) if (item != null) selectedShapes.add(item);
        }
        if (currentPage.texts != null) {
            for (TextItem item : currentPage.texts) if (item != null) selectedTexts.add(item);
        }
        if (currentPage.images != null) {
            for (ImageItem item : currentPage.images) if (item != null) selectedImages.add(item);
        }
        lassoPath.reset();
        lassoPoints.clear();
        lastAnnouncedSelection = "";
        updateSelectionBounds();
        hasLassoSelection = !selectedStrokes.isEmpty() || !selectedShapes.isEmpty()
                || !selectedTexts.isEmpty() || !selectedImages.isEmpty();
        announceSelectionCount();
        invalidate();
        notifyLassoSelection();
        return hasLassoSelection;
    }

    /** Select every handwritten stroke on the current page using lasso semantics. */
    public boolean selectAllHandwriting() {
        if (currentPage == null || currentPage.strokes == null || currentPage.strokes.isEmpty()) {
            return false;
        }
        selectedStrokes.clear();
        for (Stroke item : currentPage.strokes) {
            if (item != null && !item.getBounds().isEmpty()) selectedStrokes.add(item);
        }
        selectedShapes.clear();
        selectedTexts.clear();
        selectedImages.clear();
        lassoPath.reset();
        lassoPoints.clear();
        lastAnnouncedSelection = "";
        updateSelectionBounds();
        hasLassoSelection = !selectionBounds.isEmpty();
        announceSelectionCount();
        invalidate();
        notifyLassoSelection();
        return hasLassoSelection;
    }

    public boolean hasSelection() {
        return hasLassoSelection && (!selectedStrokes.isEmpty() || !selectedShapes.isEmpty()
                || !selectedTexts.isEmpty() || !selectedImages.isEmpty());
    }

    public boolean hasSelectedText() {
        return !selectedTexts.isEmpty();
    }

    public boolean hasSelectedHandwriting() {
        return !selectedStrokes.isEmpty();
    }

    public List<Stroke> getSelectedStrokes() {
        return new ArrayList<>(selectedStrokes);
    }

    public List<TextItem> getSelectedTexts() {
        return new ArrayList<>(selectedTexts);
    }

    public void replaceSelectionWithText(String newText) {
        if (currentPage == null || newText == null || newText.trim().isEmpty()) return;
        if (!selectedTexts.isEmpty()) {
            TextItem target = selectedTexts.get(0);
            target.text = newText.trim();
            target.markDirty();
            clearSelection();
            invalidate();
            notifyChange();
            return;
        }
        if (!selectedStrokes.isEmpty()) {
            RectF bounds = getStrokesBounds(selectedStrokes);
            currentPage.strokes.removeAll(selectedStrokes);
            float fontSize = Math.max(24f, bounds.height() * 0.75f);
            String fontKey = beautifyFontKey != null ? beautifyFontKey : "KALAM";
            TextItem item = new TextItem(newText.trim(), bounds.left, bounds.bottom - 4f, currentColor, fontSize, fontKey);
            currentPage.texts.add(item);
            ActionRecord rec = new ActionRecord();
            rec.type = ActionRecord.Type.CONVERT_TO_TEXT;
            rec.prevStrokes = new ArrayList<>(selectedStrokes);
            rec.convertedTexts = Collections.singletonList(item);
            rec.text = item;
            undoStack.push(rec);
            redoStack.clear();
            clearSelection();
            invalidate();
            notifyChange();
        }
    }

    public boolean hasSystemClipboardText() {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            return cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null
                    && cb.getPrimaryClip().getItemCount() > 0
                    && cb.getPrimaryClip().getItemAt(0).getText() != null
                    && cb.getPrimaryClip().getItemAt(0).getText().length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasClipboardContents() {
        return !clipboardStrokes.isEmpty() || !clipboardShapes.isEmpty() || !clipboardTexts.isEmpty()
                || !clipboardImages.isEmpty()
                || SelectionClipboard.hasContents(getContext())
                || hasSystemClipboardText();
    }

    public void deleteSelection() {
        deleteSelectionInternal(false);
    }

    public void cutSelection() {
        if (!hasSelection() || currentPage == null) return;
        copySelection();
        deleteSelectionInternal(true);
    }

    public void copySelection() {
        if (!hasSelection()) return;
        clipboardStrokes.clear();
        clipboardShapes.clear();
        clipboardTexts.clear();
        clipboardImages.clear();
        clipboardCenterX = selectionBounds.centerX();
        clipboardCenterY = selectionBounds.centerY();
        for (Stroke s : selectedStrokes) {
            clipboardStrokes.add(cloneStroke(s, 0f, 0f));
        }
        for (ShapeItem sh : selectedShapes) {
            clipboardShapes.add(cloneShape(sh, 0f, 0f));
        }
        for (TextItem item : selectedTexts) clipboardTexts.add(cloneText(item, 0f, 0f));
        for (ImageItem img : selectedImages) {
            clipboardImages.add(new ImageItem(img.imagePath, img.x, img.y, img.width, img.height));
        }
        SelectionClipboard.set(getContext(), new SelectionClipboard.Payload(
                clipboardStrokes, clipboardShapes, clipboardTexts,
                clipboardCenterX, clipboardCenterY));
        syncAccessibilityActions();
    }

    public void pasteSelection() {
        float targetX = ((getWidth() * 0.5f) - panOffsetX) / scaleFactor;
        float targetY = ((getHeight() * 0.5f) - panOffsetY) / scaleFactor + viewportOffsetY;
        pasteAt(targetX, targetY);
    }

    public void pasteAt(float targetX, float targetY) {
        if (currentPage == null || !hasClipboardContents()) return;
        final Page targetPage = currentPage;
        SelectionClipboard.load(getContext(), payload -> {
            if (currentPage != targetPage) return;
            if (payload != null && payload.hasContents()) {
                pasteClipboardPayloadAt(payload, targetX, targetY);
            } else if (hasSystemClipboardText()) {
                pasteSystemClipboardTextAt(targetX, targetY);
            }
        });
    }

    private void pasteSystemClipboardTextAt(float targetX, float targetY) {
        if (currentPage == null) return;
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null
                    && cb.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cb.getPrimaryClip().getItemAt(0).getText();
                if (cs != null && cs.length() > 0) {
                    addTextItem(cs.toString(), targetX * scaleFactor + panOffsetX,
                            (targetY - viewportOffsetY) * scaleFactor + panOffsetY);
                }
            }
        } catch (Exception ignored) {}
    }

    private void pasteClipboardPayloadAt(SelectionClipboard.Payload payload, float targetX, float targetY) {
        if (currentPage == null || payload == null || !payload.hasContents()) return;
        float offsetX = targetX - payload.centerX;
        float offsetY = targetY - payload.centerY;
        List<Stroke> pastedStrokes = new ArrayList<>();
        for (Stroke s : payload.strokes) {
            Stroke clone = cloneStroke(s, offsetX, offsetY);
            pastedStrokes.add(clone);
        }
        List<ShapeItem> pastedShapes = new ArrayList<>();
        for (ShapeItem sh : payload.shapes) {
            pastedShapes.add(cloneShape(sh, offsetX, offsetY));
        }
        List<TextItem> pastedTexts = new ArrayList<>();
        for (TextItem item : payload.texts) pastedTexts.add(cloneText(item, offsetX, offsetY));
        addSelectionToPage(pastedStrokes, pastedShapes, pastedTexts);
        recordAddedSelection(pastedStrokes, pastedShapes, pastedTexts);
    }

    public void duplicateSelection() {
        if (!hasSelection() || currentPage == null) return;
        float offset = 40f / Math.max(0.2f, scaleFactor);
        List<Stroke> newStrokes = new ArrayList<>();
        for (Stroke s : selectedStrokes) newStrokes.add(cloneStroke(s, offset, offset));
        List<ShapeItem> newShapes = new ArrayList<>();
        for (ShapeItem sh : selectedShapes) newShapes.add(cloneShape(sh, offset, offset));
        List<TextItem> newTexts = new ArrayList<>();
        for (TextItem item : selectedTexts) newTexts.add(cloneText(item, offset, offset));
        List<ImageItem> newImages = new ArrayList<>();
        for (ImageItem img : selectedImages) newImages.add(new ImageItem(img.imagePath, img.x + offset, img.y + offset, img.width, img.height));
        addSelectionToPage(newStrokes, newShapes, newTexts, newImages);
        recordAddedSelection(newStrokes, newShapes, newTexts, newImages);
    }

    public void setSelectionColor(int color) {
        if (!hasSelection()) return;
        ActionRecord record = beginSelectionStyleChange();
        for (Stroke s : selectedStrokes) {
            s.color = color;
        }
        for (ShapeItem sh : selectedShapes) {
            sh.color = color;
        }
        for (TextItem item : selectedTexts) item.color = color;
        finishSelectionStyleChange(record);
    }

    public void setSelectionStrokeWidth(float width) {
        if (!hasSelection()) return;
        ActionRecord record = beginSelectionStyleChange();
        for (Stroke s : selectedStrokes) {
            s.width = width;
            s.markDirty();
        }
        for (ShapeItem sh : selectedShapes) {
            sh.strokeWidth = width;
            sh.markDirty();
        }
        for (TextItem item : selectedTexts) {
            item.fontSize = Math.max(12f, width * 4f);
            item.markDirty();
        }
        finishSelectionStyleChange(record);
    }

    private void deleteSelectionInternal(boolean cut) {
        if (!hasSelection() || currentPage == null) return;
        ActionRecord record = new ActionRecord();
        record.type = ActionRecord.Type.DELETE_SELECTION;
        record.prevStrokes = new ArrayList<>(selectedStrokes);
        record.prevShapes = new ArrayList<>(selectedShapes);
        record.prevTexts = new ArrayList<>(selectedTexts);
        record.prevImages = new ArrayList<>(selectedImages);
        removeRecordedSelection(record);
        undoStack.push(record);
        redoStack.clear();
        strokeSpatialIndexDirty = true;
        clearSelection();
        invalidate();
        notifyChange();
        announceForAccessibility(cut
                ? "Selection cut to clipboard. Undo is available."
                : "Selection deleted. Undo is available.");
    }

    private void addSelectionToPage(List<Stroke> strokes, List<ShapeItem> shapes,
                                    List<TextItem> texts) {
        addSelectionToPage(strokes, shapes, texts, null);
    }

    private void addSelectionToPage(List<Stroke> strokes, List<ShapeItem> shapes,
                                    List<TextItem> texts, List<ImageItem> images) {
        if (currentPage == null) return;
        currentPage.strokes.addAll(strokes);
        currentPage.shapes.addAll(shapes);
        currentPage.texts.addAll(texts);
        if (images != null) {
            if (currentPage.images == null) currentPage.images = new ArrayList<>();
            currentPage.images.addAll(images);
        }
        selectedStrokes.clear();
        selectedStrokes.addAll(strokes);
        selectedShapes.clear();
        selectedShapes.addAll(shapes);
        selectedTexts.clear();
        selectedTexts.addAll(texts);
        selectedImages.clear();
        if (images != null) selectedImages.addAll(images);
        updateSelectionBounds();
        hasLassoSelection = hasAnySelectedItems();
        lastAnnouncedSelection = "";
        strokeSpatialIndexDirty = true;
        invalidate();
        notifyChange();
        notifyLassoSelection();
        announceSelectionCount();
    }

    private void recordAddedSelection(List<Stroke> strokes, List<ShapeItem> shapes,
                                      List<TextItem> texts) {
        recordAddedSelection(strokes, shapes, texts, null);
    }

    private void recordAddedSelection(List<Stroke> strokes, List<ShapeItem> shapes,
                                      List<TextItem> texts, List<ImageItem> images) {
        ActionRecord record = new ActionRecord();
        record.type = ActionRecord.Type.ADD_SELECTION;
        record.prevStrokes = new ArrayList<>(strokes);
        record.prevShapes = new ArrayList<>(shapes);
        record.prevTexts = new ArrayList<>(texts);
        if (images != null) record.prevImages = new ArrayList<>(images);
        undoStack.push(record);
        redoStack.clear();
        syncAccessibilityActions();
    }

    private Stroke cloneStroke(Stroke source, float dx, float dy) {
        Stroke clone = new Stroke(source.color, source.width, source.tool);
        clone.lineStyle = source.lineStyle;
        if (source.points != null) {
            for (DrawingPoint point : source.points) {
                clone.addPoint(point.x + dx, point.y + dy, point.pressure, point.timeMillis);
            }
        }
        clone.copyRecognitionPointsFrom(source, dx, dy);
        return clone;
    }

    private ShapeItem cloneShape(ShapeItem source, float dx, float dy) {
        ShapeItem sh = new ShapeItem(source.type, source.startX + dx, source.startY + dy,
                source.endX + dx, source.endY + dy, source.color, source.strokeWidth);
        sh.lineStyle = source.lineStyle;
        sh.fillColor = source.fillColor;
        return sh;
    }

    private TextItem cloneText(TextItem source, float dx, float dy) {
        return new TextItem(source.text, source.x + dx, source.y + dy,
                source.color, source.fontSize, source.fontKey, source.rotationAngle);
    }

    private ActionRecord beginSelectionStyleChange() {
        ActionRecord record = new ActionRecord();
        record.type = ActionRecord.Type.STYLE_CHANGE;
        record.styleTargetsStrokes = new ArrayList<>(selectedStrokes);
        record.styleTargetsShapes = new ArrayList<>(selectedShapes);
        record.styleTargetsTexts = new ArrayList<>(selectedTexts);
        record.previousStrokeStyles = snapshotStrokeStyles(selectedStrokes);
        record.previousShapeStyles = snapshotShapeStyles(selectedShapes);
        record.previousTextStyles = snapshotTextStyles(selectedTexts);
        return record;
    }

    private void finishSelectionStyleChange(ActionRecord record) {
        record.nextStrokeStyles = snapshotStrokeStyles(selectedStrokes);
        record.nextShapeStyles = snapshotShapeStyles(selectedShapes);
        record.nextTextStyles = snapshotTextStyles(selectedTexts);
        undoStack.push(record);
        redoStack.clear();
        strokeSpatialIndexDirty = true;
        updateSelectionBounds();
        invalidate();
        notifyChange();
        notifyLassoSelection();
    }

    private List<ItemStyleSnapshot> snapshotStrokeStyles(List<Stroke> items) {
        List<ItemStyleSnapshot> snapshots = new ArrayList<>(items.size());
        for (Stroke item : items) snapshots.add(new ItemStyleSnapshot(item.color, item.width, item.lineStyle, null));
        return snapshots;
    }

    private List<ItemStyleSnapshot> snapshotShapeStyles(List<ShapeItem> items) {
        List<ItemStyleSnapshot> snapshots = new ArrayList<>(items.size());
        for (ShapeItem item : items) snapshots.add(new ItemStyleSnapshot(item.color, item.strokeWidth, item.lineStyle, item.fillColor));
        return snapshots;
    }

    private List<ItemStyleSnapshot> snapshotTextStyles(List<TextItem> items) {
        List<ItemStyleSnapshot> snapshots = new ArrayList<>(items.size());
        for (TextItem item : items) snapshots.add(new ItemStyleSnapshot(item.color, item.fontSize, Stroke.LineStyle.SOLID, null));
        return snapshots;
    }

    private List<Stroke> cloneStrokes(List<Stroke> items) {
        List<Stroke> copies = new ArrayList<>(items.size());
        for (Stroke item : items) copies.add(cloneStroke(item, 0f, 0f));
        return copies;
    }

    private void restoreSelectionStyle(ActionRecord record, boolean restoreAfter) {
        if (record == null) return;
        List<ItemStyleSnapshot> strokeSnapshots = restoreAfter
                ? record.nextStrokeStyles : record.previousStrokeStyles;
        List<ItemStyleSnapshot> shapeSnapshots = restoreAfter
                ? record.nextShapeStyles : record.previousShapeStyles;
        List<ItemStyleSnapshot> textSnapshots = restoreAfter
                ? record.nextTextStyles : record.previousTextStyles;
        if (record.styleTargetsStrokes != null && strokeSnapshots != null) {
            for (int i = 0; i < Math.min(record.styleTargetsStrokes.size(), strokeSnapshots.size()); i++) {
                Stroke target = record.styleTargetsStrokes.get(i);
                ItemStyleSnapshot snapshot = strokeSnapshots.get(i);
                target.color = snapshot.color;
                target.width = snapshot.size;
                if (snapshot.lineStyle != null) target.lineStyle = snapshot.lineStyle;
                target.markDirty();
            }
        }
        if (record.styleTargetsShapes != null && shapeSnapshots != null) {
            for (int i = 0; i < Math.min(record.styleTargetsShapes.size(), shapeSnapshots.size()); i++) {
                ShapeItem target = record.styleTargetsShapes.get(i);
                ItemStyleSnapshot snapshot = shapeSnapshots.get(i);
                target.color = snapshot.color;
                target.strokeWidth = snapshot.size;
                if (snapshot.lineStyle != null) target.lineStyle = snapshot.lineStyle;
                target.fillColor = snapshot.fillColor;
                target.markDirty();
            }
        }
        if (record.styleTargetsTexts != null && textSnapshots != null) {
            for (int i = 0; i < Math.min(record.styleTargetsTexts.size(), textSnapshots.size()); i++) {
                TextItem target = record.styleTargetsTexts.get(i);
                ItemStyleSnapshot snapshot = textSnapshots.get(i);
                target.color = snapshot.color;
                target.fontSize = snapshot.size;
                target.markDirty();
            }
        }
        strokeSpatialIndexDirty = true;
    }

    private void restoreBeautifiedSelection(ActionRecord record, boolean restoreAfter) {
        if (record == null || record.styleTargetsStrokes == null) return;
        List<Stroke> snapshots = restoreAfter
                ? record.afterBeautifyStrokes : record.beforeBeautifyStrokes;
        if (snapshots == null) return;
        for (int i = 0; i < Math.min(record.styleTargetsStrokes.size(), snapshots.size()); i++) {
            Stroke target = record.styleTargetsStrokes.get(i);
            Stroke snapshot = snapshots.get(i);
            target.color = snapshot.color;
            target.width = snapshot.width;
            target.tool = snapshot.tool;
            target.points = cloneStroke(snapshot, 0f, 0f).points;
            target.copyRecognitionPointsFrom(snapshot, 0f, 0f);
            target.markDirty();
        }
        strokeSpatialIndexDirty = true;
    }

    private void translateItems(List<Stroke> strokes, List<ShapeItem> shapes,
                                List<TextItem> texts, float dx, float dy) {
        translateItems(strokes, shapes, texts, selectedImages, dx, dy);
    }

    private void translateItems(List<Stroke> strokes, List<ShapeItem> shapes,
                                List<TextItem> texts, List<ImageItem> images, float dx, float dy) {
        if (strokes != null) {
            for (Stroke s : strokes) {
                for (DrawingPoint pt : s.points) {
                    pt.x += dx;
                    pt.y += dy;
                }
                if (s.recognitionPoints != null) {
                    for (DrawingPoint point : s.recognitionPoints) {
                        point.x += dx;
                        point.y += dy;
                    }
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
        if (texts != null) {
            for (TextItem item : texts) {
                item.x += dx;
                item.y += dy;
                item.markDirty();
            }
        }
        if (images != null) {
            for (ImageItem img : images) {
                img.x += dx;
                img.y += dy;
                img.markDirty();
            }
        }
    }

    private void rotateItemsAround(List<Stroke> strokes, List<ShapeItem> shapes,
                                   List<TextItem> texts, List<ImageItem> images,
                                   float cx, float cy, float angleDelta) {
        if (Math.abs(angleDelta) < 0.001f) return;
        double rad = Math.toRadians(angleDelta);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        if (strokes != null) {
            for (Stroke s : strokes) {
                if (s == null) continue;
                if (s.points != null) {
                    for (DrawingPoint pt : s.points) {
                        float dx = pt.x - cx;
                        float dy = pt.y - cy;
                        pt.x = cx + dx * cos - dy * sin;
                        pt.y = cy + dx * sin + dy * cos;
                    }
                }
                if (s.recognitionPoints != null) {
                    for (DrawingPoint pt : s.recognitionPoints) {
                        float dx = pt.x - cx;
                        float dy = pt.y - cy;
                        pt.x = cx + dx * cos - dy * sin;
                        pt.y = cy + dx * sin + dy * cos;
                    }
                }
                s.markDirty();
            }
        }

        if (shapes != null) {
            for (ShapeItem sh : shapes) {
                if (sh == null) continue;
                float dx1 = sh.startX - cx;
                float dy1 = sh.startY - cy;
                sh.startX = cx + dx1 * cos - dy1 * sin;
                sh.startY = cy + dx1 * sin + dy1 * cos;

                float dx2 = sh.endX - cx;
                float dy2 = sh.endY - cy;
                sh.endX = cx + dx2 * cos - dy2 * sin;
                sh.endY = cy + dx2 * sin + dy2 * cos;
                sh.markDirty();
            }
        }

        if (texts != null) {
            for (TextItem item : texts) {
                if (item == null) continue;
                float dx = item.x - cx;
                float dy = item.y - cy;
                item.x = cx + dx * cos - dy * sin;
                item.y = cy + dx * sin + dy * cos;
                item.rotationAngle = (item.rotationAngle + angleDelta) % 360f;
                if (item.rotationAngle > 180f) item.rotationAngle -= 360f;
                if (item.rotationAngle < -180f) item.rotationAngle += 360f;
                item.markDirty();
            }
        }

        if (images != null) {
            for (ImageItem img : images) {
                if (img == null) continue;
                float dx = img.x - cx;
                float dy = img.y - cy;
                img.x = cx + dx * cos - dy * sin;
                img.y = cy + dx * sin + dy * cos;
                img.markDirty();
            }
        }
        strokeSpatialIndexDirty = true;
    }

    public float getSelectionRotation() {
        if (!selectedTexts.isEmpty()) {
            return selectedTexts.get(0).rotationAngle;
        }
        return 0f;
    }

    public void rotateSelection(float angleDelta) {
        if (!hasSelection() || Math.abs(angleDelta) < 0.001f) return;
        float cx = selectionBounds.centerX();
        float cy = selectionBounds.centerY();
        rotateItemsAround(selectedStrokes, selectedShapes, selectedTexts, selectedImages, cx, cy, angleDelta);

        ActionRecord record = new ActionRecord();
        record.type = ActionRecord.Type.ROTATE_SELECTION;
        record.rotateAngle = angleDelta;
        record.rotateCenterX = cx;
        record.rotateCenterY = cy;
        record.rotatedStrokes = new ArrayList<>(selectedStrokes);
        record.rotatedShapes = new ArrayList<>(selectedShapes);
        record.rotatedTexts = new ArrayList<>(selectedTexts);
        record.rotatedImages = new ArrayList<>(selectedImages);
        undoStack.push(record);
        redoStack.clear();

        updateSelectionBounds();
        invalidate();
        notifyChange();
        notifyLassoSelection();
    }

    public void scaleItemsAround(List<Stroke> strokes, List<ShapeItem> shapes,
                                 List<TextItem> texts, List<ImageItem> images,
                                 float anchorX, float anchorY, float scaleX, float scaleY) {
        if (Math.abs(scaleX - 1.0f) < 0.0001f && Math.abs(scaleY - 1.0f) < 0.0001f) return;
        float avgScale = (Math.abs(scaleX) + Math.abs(scaleY)) / 2f;

        if (strokes != null) {
            for (Stroke s : strokes) {
                if (s == null) continue;
                if (s.points != null) {
                    for (DrawingPoint pt : s.points) {
                        float dx = pt.x - anchorX;
                        float dy = pt.y - anchorY;
                        pt.x = anchorX + dx * scaleX;
                        pt.y = anchorY + dy * scaleY;
                    }
                }
                if (s.recognitionPoints != null) {
                    for (DrawingPoint pt : s.recognitionPoints) {
                        float dx = pt.x - anchorX;
                        float dy = pt.y - anchorY;
                        pt.x = anchorX + dx * scaleX;
                        pt.y = anchorY + dy * scaleY;
                    }
                }
                s.width = Math.max(0.5f, s.width * avgScale);
                s.markDirty();
            }
        }

        if (shapes != null) {
            for (ShapeItem sh : shapes) {
                if (sh == null) continue;
                float dx1 = sh.startX - anchorX;
                float dy1 = sh.startY - anchorY;
                sh.startX = anchorX + dx1 * scaleX;
                sh.startY = anchorY + dy1 * scaleY;

                float dx2 = sh.endX - anchorX;
                float dy2 = sh.endY - anchorY;
                sh.endX = anchorX + dx2 * scaleX;
                sh.endY = anchorY + dy2 * scaleY;

                sh.strokeWidth = Math.max(0.5f, sh.strokeWidth * avgScale);
                sh.markDirty();
            }
        }

        if (texts != null) {
            for (TextItem item : texts) {
                if (item == null) continue;
                float dx = item.x - anchorX;
                float dy = item.y - anchorY;
                item.x = anchorX + dx * scaleX;
                item.y = anchorY + dy * scaleY;
                item.fontSize = Math.max(8f, item.fontSize * avgScale);
                item.markDirty();
            }
        }

        if (images != null) {
            for (ImageItem img : images) {
                if (img == null) continue;
                float dx = img.x - anchorX;
                float dy = img.y - anchorY;
                img.x = anchorX + dx * scaleX;
                img.y = anchorY + dy * scaleY;
                img.width = Math.max(10f, img.width * scaleX);
                img.height = Math.max(10f, img.height * scaleY);
                img.markDirty();
            }
        }
        strokeSpatialIndexDirty = true;
    }

    public void beginLiveRotation() {
        currentLiveRotation = 0f;
        if (hasSelection()) {
            rotationCenterX = selectionBounds.centerX();
            rotationCenterY = selectionBounds.centerY();
        }
    }

    public void updateLiveRotation(float angleDeltaFromLast) {
        if (!hasSelection()) return;
        currentLiveRotation += angleDeltaFromLast;
        postInvalidateOnAnimation();
    }

    public void finishLiveRotation() {
        if (Math.abs(currentLiveRotation) > 0.05f && hasSelection()) {
            float cx = rotationCenterX != 0f ? rotationCenterX : selectionBounds.centerX();
            float cy = rotationCenterY != 0f ? rotationCenterY : selectionBounds.centerY();
            rotateItemsAround(selectedStrokes, selectedShapes, selectedTexts, selectedImages, cx, cy, currentLiveRotation);
            ActionRecord record = new ActionRecord();
            record.type = ActionRecord.Type.ROTATE_SELECTION;
            record.rotateAngle = currentLiveRotation;
            record.rotateCenterX = cx;
            record.rotateCenterY = cy;
            record.rotatedStrokes = new ArrayList<>(selectedStrokes);
            record.rotatedShapes = new ArrayList<>(selectedShapes);
            record.rotatedTexts = new ArrayList<>(selectedTexts);
            record.rotatedImages = new ArrayList<>(selectedImages);
            undoStack.push(record);
            redoStack.clear();
            updateSelectionBounds();
            notifyChange();
            notifyLassoSelection();
            invalidate();
        }
        currentLiveRotation = 0f;
    }

    public void cancelLiveRotation() {
        currentLiveRotation = 0f;
        invalidate();
    }

    public void beginLiveResizing(LassoHandle handle, float anchorX, float anchorY, float initialDistance) {
        this.activeLassoHandle = handle;
        this.isResizingSelection = true;
        this.transformAnchorX = anchorX;
        this.transformAnchorY = anchorY;
        this.initialTouchDistance = Math.max(1f, initialDistance);
        this.currentLiveScaleX = 1f;
        this.currentLiveScaleY = 1f;
    }

    public void updateLiveResizing(float currentDistance) {
        if (!isResizingSelection) return;
        float scale = initialTouchDistance > 1f ? (currentDistance / initialTouchDistance) : 1f;
        scale = Math.max(0.15f, Math.min(8.0f, scale));
        this.currentLiveScaleX = scale;
        this.currentLiveScaleY = scale;
        postInvalidateOnAnimation();
    }

    public void finishLiveResizing() {
        if (isResizingSelection && Math.abs(currentLiveScaleX - 1.0f) > 0.005f && hasSelection()) {
            scaleItemsAround(selectedStrokes, selectedShapes, selectedTexts, selectedImages,
                    transformAnchorX, transformAnchorY, currentLiveScaleX, currentLiveScaleY);
            ActionRecord record = new ActionRecord();
            record.type = ActionRecord.Type.SCALE_SELECTION;
            record.scaleX = currentLiveScaleX;
            record.scaleY = currentLiveScaleY;
            record.scaleAnchorX = transformAnchorX;
            record.scaleAnchorY = transformAnchorY;
            record.scaledStrokes = new ArrayList<>(selectedStrokes);
            record.scaledShapes = new ArrayList<>(selectedShapes);
            record.scaledTexts = new ArrayList<>(selectedTexts);
            record.scaledImages = new ArrayList<>(selectedImages);
            undoStack.push(record);
            redoStack.clear();
            updateSelectionBounds();
            notifyChange();
            notifyLassoSelection();
            invalidate();
        }
        isResizingSelection = false;
        activeLassoHandle = LassoHandle.NONE;
        currentLiveScaleX = 1f;
        currentLiveScaleY = 1f;
    }

    public boolean hasSelectedShape() {
        return !selectedShapes.isEmpty();
    }

    public Stroke.LineStyle getSelectionLineStyle() {
        if (!selectedStrokes.isEmpty()) return selectedStrokes.get(0).lineStyle;
        if (!selectedShapes.isEmpty()) return selectedShapes.get(0).lineStyle;
        return Stroke.LineStyle.SOLID;
    }

    public void setSelectionLineStyle(Stroke.LineStyle lineStyle) {
        if (!hasSelection() || lineStyle == null) return;
        ActionRecord record = beginSelectionStyleChange();
        for (Stroke s : selectedStrokes) {
            s.lineStyle = lineStyle;
            s.markDirty();
        }
        for (ShapeItem sh : selectedShapes) {
            sh.lineStyle = lineStyle;
            sh.markDirty();
        }
        finishSelectionStyleChange(record);
    }

    public float getSelectionStrokeWidth() {
        if (!selectedStrokes.isEmpty()) return selectedStrokes.get(0).width;
        if (!selectedShapes.isEmpty()) return selectedShapes.get(0).strokeWidth;
        return 4f;
    }

    public int getSelectionColor() {
        if (!selectedStrokes.isEmpty()) return selectedStrokes.get(0).color;
        if (!selectedShapes.isEmpty()) return selectedShapes.get(0).color;
        if (!selectedTexts.isEmpty()) return selectedTexts.get(0).color;
        return currentColor;
    }

    public Integer getSelectionFillColor() {
        if (!selectedShapes.isEmpty()) return selectedShapes.get(0).fillColor;
        return null;
    }

    public void setSelectionFillColor(Integer fillColor) {
        if (!hasSelection() || selectedShapes.isEmpty()) return;
        ActionRecord record = beginSelectionStyleChange();
        for (ShapeItem sh : selectedShapes) {
            sh.fillColor = fillColor;
            sh.markDirty();
        }
        finishSelectionStyleChange(record);
    }

    public void setSelectionTypeface(String fontKey) {
        if (!hasSelection() || selectedTexts.isEmpty()) return;
        ActionRecord record = beginSelectionStyleChange();
        for (TextItem item : selectedTexts) {
            item.fontKey = fontKey;
            item.markDirty();
        }
        finishSelectionStyleChange(record);
    }

    private void finishSelectionMove() {
        if (Math.abs(selectionMoveDx) > 0.001f || Math.abs(selectionMoveDy) > 0.001f) {
            ActionRecord record = new ActionRecord();
            record.type = ActionRecord.Type.MOVE_SELECTION;
            record.movedStrokes = new ArrayList<>(selectedStrokes);
            record.movedShapes = new ArrayList<>(selectedShapes);
            record.movedTexts = new ArrayList<>(selectedTexts);
            record.movedImages = new ArrayList<>(selectedImages);
            record.moveDx = selectionMoveDx;
            record.moveDy = selectionMoveDy;
            undoStack.push(record);
            redoStack.clear();
            strokeSpatialIndexDirty = true;
            announceForAccessibility("Selected content moved.");
        }
        selectionMoveDx = 0f;
        selectionMoveDy = 0f;
    }

    private boolean hasAnySelectedItems() {
        return !selectedStrokes.isEmpty() || !selectedShapes.isEmpty()
                || !selectedTexts.isEmpty() || !selectedImages.isEmpty();
    }

    private void updateSelectionBounds() {
        selectionBounds.setEmpty();
        for (Stroke item : selectedStrokes) unionSelectionBounds(item == null ? null : item.getBounds());
        for (ShapeItem item : selectedShapes) unionSelectionBounds(item == null ? null : item.getBounds());
        for (TextItem item : selectedTexts) unionSelectionBounds(item == null ? null : item.getBounds());
        for (ImageItem item : selectedImages) unionSelectionBounds(item == null ? null : item.getBounds());
        if (!selectionBounds.isEmpty()) selectionBounds.inset(-16f, -16f);
    }

    private void unionSelectionBounds(RectF itemBounds) {
        if (itemBounds == null || itemBounds.isEmpty()) return;
        if (selectionBounds.isEmpty()) selectionBounds.set(itemBounds);
        else selectionBounds.union(itemBounds);
    }

    private void announceSelectionCount() {
        if (!hasSelection()) return;
        String summary = "Selected " + selectedStrokes.size() + " ink strokes, "
                + selectedShapes.size() + " shapes, "
                + selectedImages.size() + " images, and " + selectedTexts.size()
                + " text items. Drag to move or use the selection actions.";
        if (!summary.equals(lastAnnouncedSelection)) {
            setContentDescription("Note page. " + summary);
            announceForAccessibility(summary);
            lastAnnouncedSelection = summary;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();
        recordMotionEventForPrediction(event);

        if (action == MotionEvent.ACTION_DOWN) {
            setInputFrameRatePreference(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            setInputFrameRatePreference(false);
        }

        int stylusPointerIndex = findStylusPointerIndex(event);
        boolean hasStylusPointer = stylusPointerIndex >= 0;
        if (hasStylusPointer) {
            handPanDragging = false;
            lastStylusActiveTime = SystemClock.uptimeMillis();
        }
        boolean isPalm = isPalmTouch(event);
        boolean mixedStylusTouch = hasStylusPointer && pointerCount > 1;
        boolean canceledPointerUp = action == MotionEvent.ACTION_POINTER_UP
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0;

        // 0. Single-Finger & Multi-Finger Canvas Move Gesture Mode
        // When enabled, fingers only move & zoom the canvas. Stylus always draws with the active pen/tool!
        boolean fingerMoveActive = isFingerMoveCanvasEnabled || (currentTool == DrawingTool.HAND);
        if (fingerMoveActive && !hasStylusPointer) {
            if (isPalm) return true;

            if (getParent() != null) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                } else {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (isViewportLocked) return true;

            // Two-finger pinch-zoom and pan support while in Finger Move mode
            if (pointerCount >= 2) {
                handPanDragging = false;
                scaleDetector.onTouchEvent(event);
                handleTwoFingerPanZoom(event, action);
                return true;
            }

            // Single-finger dragging to move canvas
            float touchX = event.getX(0);
            float touchY = event.getY(0);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    handPanStartX = touchX;
                    handPanStartY = touchY;
                    handInitialPanX = panOffsetX;
                    handInitialPanY = panOffsetY;
                    handPanDragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (handPanDragging) {
                        panOffsetX = handInitialPanX + (touchX - handPanStartX);
                        panOffsetY = handInitialPanY + (touchY - handPanStartY);
                        invalidate();
                        notifyLassoSelection();
                    } else {
                        handPanStartX = touchX;
                        handPanStartY = touchY;
                        handInitialPanX = panOffsetX;
                        handInitialPanY = panOffsetY;
                        handPanDragging = true;
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    handPanDragging = false;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handPanDragging = false;
                    return true;
            }
            return true;
        }

        boolean isWithinStylusCooldown = (SystemClock.uptimeMillis() - lastStylusActiveTime) < 800L;

        // Android can report a palm as a finger before canceling it. Never let
        // a finger touching during a live stylus stroke start a pinch detector.
        // Also suppress scale detector during stylus writing cooldown or when palm is detected.
        if (mixedStylusTouch || canceledPointerUp || isWithinStylusCooldown || isPalm) {
            if (!scaleDetectorSuppressedForStylus) cancelScaleGestureDetector(event);
            scaleDetectorSuppressedForStylus = true;
        } else {
            scaleDetectorSuppressedForStylus = false;
            scaleDetector.onTouchEvent(event);
        }

        if (pointerCount >= 2) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        if (action == MotionEvent.ACTION_DOWN && hasStylusPointer) {
            // Avoid waiting for batched move events during handwriting. We still
            // consume historical samples if the platform supplies them.
            requestUnbufferedDispatch(event);
        }

        boolean canceledUp = action == MotionEvent.ACTION_UP
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0;
        if (action == MotionEvent.ACTION_CANCEL || canceledUp) {
            cancelInProgressGesture();
            return true;
        }
        if (canceledPointerUp) {
            int canceledPointerId = event.getPointerId(event.getActionIndex());
            if (canceledPointerId == activeInputPointerId) cancelInProgressGesture();
            return true;
        }

        // Stylus pointer-up may arrive while a palm/finger pointer remains.
        // Treat that as the end of ink, not the end of the whole multi-touch stream.
        if (action == MotionEvent.ACTION_POINTER_UP && hasStylusPointer
                && event.getActionIndex() == stylusPointerIndex) {
            action = MotionEvent.ACTION_UP;
            predictorStreamValid = false;
            hasPredictedInkPoint = false;
        }

        // Reject accidental palm touches when not using stylus
        if (!hasStylusPointer && isPalm) {
            return true;
        }

        // Stylus-only mode rejects all finger drawing, but allows a real
        // two-finger navigation gesture when the stylus is not in contact and outside cooldown.
        if (isStylusOnly && !hasStylusPointer) {
            if (pointerCount >= 2 && !isViewportLocked && !isWithinStylusCooldown && !isPalm) {
                if (activeStroke != null) {
                    cancelInProgressGesture();
                }
                handleTwoFingerPanZoom(event, action);
            }
            return true;
        }

        // Fingers navigate when no stylus is touching and not during cooldown/palm.
        if (!hasStylusPointer && !isWithinStylusCooldown && !isPalm && pointerCount >= 2 && !isViewportLocked) {
            if (activeStroke != null) {
                cancelInProgressGesture();
            }
            handleTwoFingerPanZoom(event, action);
            return true;
        }

        if (activeStroke != null && activeInputPointerId >= 0
                && event.findPointerIndex(activeInputPointerId) < 0 && !hasStylusPointer) {
            cancelInProgressGesture();
            return true;
        }

        int activePointerIndex = activeInputPointerId < 0
                ? -1 : event.findPointerIndex(activeInputPointerId);
        int inputPointerIndex = hasStylusPointer ? stylusPointerIndex
                : (activePointerIndex >= 0 ? activePointerIndex : 0);
        if (action == MotionEvent.ACTION_UP && hasStylusPointer
                && event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            inputPointerIndex = event.getActionIndex();
        }


        // Map touch coordinates to natural unscaled canvas coordinates
        float rawX = event.getX(inputPointerIndex);
        float rawY = event.getY(inputPointerIndex);
        float canvasX = (rawX - panOffsetX) / scaleFactor;
        float canvasY = (rawY - panOffsetY) / scaleFactor + viewportOffsetY;
        int primaryToolType = event.getToolType(inputPointerIndex);
        float pressure = normalizeInputPressure(event.getPressure(inputPointerIndex), primaryToolType);

        // Long-press gesture tracking for "Press and hold to paste wherever we want"
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownRawX = rawX;
            touchDownRawY = rawY;
            touchDownCanvasX = canvasX;
            touchDownCanvasY = canvasY;
            longPressTriggered = false;
            removeCallbacks(longPressPasteRunnable);
            if (hasClipboardContents() && currentTool != DrawingTool.LASSO) {
                postDelayed(longPressPasteRunnable, 420);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!longPressTriggered && Math.hypot(rawX - touchDownRawX, rawY - touchDownRawY) > touchSlop) {
                removeCallbacks(longPressPasteRunnable);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPressPasteRunnable);
            if (longPressTriggered) {
                longPressTriggered = false;
                return true;
            }
        }

        // 2b. Laser Pointer Tool (ephemeral glowing presentation trail)
        if (currentTool == DrawingTool.LASER) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    laserTrail.add(new LaserPoint(canvasX, canvasY,
                            event.getEventTime(), true));
                    postInvalidateOnAnimation();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int historySize = event.getHistorySize();
                    for (int i = 0; i < historySize; i++) {
                        float histRawX = event.getHistoricalX(inputPointerIndex, i);
                        float histRawY = event.getHistoricalY(inputPointerIndex, i);
                        float histX = (histRawX - panOffsetX) / scaleFactor;
                        float histY = (histRawY - panOffsetY) / scaleFactor + viewportOffsetY;
                        laserTrail.add(new LaserPoint(histX, histY,
                                event.getHistoricalEventTime(i)));
                    }
                    laserTrail.add(new LaserPoint(canvasX, canvasY, event.getEventTime()));
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
                    if (hasLassoSelection && !selectionBounds.isEmpty()) {
                        float stalkLength = 46f / Math.max(0.5f, scaleFactor);
                        float rotKnobX = selectionBounds.right + stalkLength;
                        float rotKnobY = selectionBounds.centerY();
                        float hitRadius = 50f / Math.max(0.5f, scaleFactor);
                        float hdx = canvasX - rotKnobX;
                        float hdy = canvasY - rotKnobY;
                        if (hdx * hdx + hdy * hdy <= hitRadius * hitRadius) {
                            activeLassoHandle = LassoHandle.ROTATE;
                            isRotatingSelection = true;
                            rotationCenterX = selectionBounds.centerX();
                            rotationCenterY = selectionBounds.centerY();
                            lastRotationTouchAngle = (float) Math.toDegrees(Math.atan2(canvasY - rotationCenterY, canvasX - rotationCenterX));
                            beginLiveRotation();
                            try {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            } catch (Exception ignored) {}
                            return true;
                        }

                        // Check 4 Corner Handles for Resizing (StarNote 4 corners)
                        float cornerHitRadius = 46f / Math.max(0.5f, scaleFactor);
                        float cornerHitSq = cornerHitRadius * cornerHitRadius;

                        // Top-Left (Anchor is Bottom-Right)
                        float tlDx = canvasX - selectionBounds.left;
                        float tlDy = canvasY - selectionBounds.top;
                        if (tlDx * tlDx + tlDy * tlDy <= cornerHitSq) {
                            float anchorX = selectionBounds.right;
                            float anchorY = selectionBounds.bottom;
                            float initDist = (float) Math.hypot(canvasX - anchorX, canvasY - anchorY);
                            beginLiveResizing(LassoHandle.TOP_LEFT, anchorX, anchorY, initDist);
                            try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Exception ignored) {}
                            return true;
                        }

                        // Top-Right (Anchor is Bottom-Left)
                        float trDx = canvasX - selectionBounds.right;
                        float trDy = canvasY - selectionBounds.top;
                        if (trDx * trDx + trDy * trDy <= cornerHitSq) {
                            float anchorX = selectionBounds.left;
                            float anchorY = selectionBounds.bottom;
                            float initDist = (float) Math.hypot(canvasX - anchorX, canvasY - anchorY);
                            beginLiveResizing(LassoHandle.TOP_RIGHT, anchorX, anchorY, initDist);
                            try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Exception ignored) {}
                            return true;
                        }

                        // Bottom-Left (Anchor is Top-Right)
                        float blDx = canvasX - selectionBounds.left;
                        float blDy = canvasY - selectionBounds.bottom;
                        if (blDx * blDx + blDy * blDy <= cornerHitSq) {
                            float anchorX = selectionBounds.right;
                            float anchorY = selectionBounds.top;
                            float initDist = (float) Math.hypot(canvasX - anchorX, canvasY - anchorY);
                            beginLiveResizing(LassoHandle.BOTTOM_LEFT, anchorX, anchorY, initDist);
                            try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Exception ignored) {}
                            return true;
                        }

                        // Bottom-Right (Anchor is Top-Left)
                        float brDx = canvasX - selectionBounds.right;
                        float brDy = canvasY - selectionBounds.bottom;
                        if (brDx * brDx + brDy * brDy <= cornerHitSq) {
                            float anchorX = selectionBounds.left;
                            float anchorY = selectionBounds.top;
                            float initDist = (float) Math.hypot(canvasX - anchorX, canvasY - anchorY);
                            beginLiveResizing(LassoHandle.BOTTOM_RIGHT, anchorX, anchorY, initDist);
                            try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Exception ignored) {}
                            return true;
                        }
                    }

                    if (hasLassoSelection && selectionBounds.contains(canvasX, canvasY)) {
                        isMovingSelection = true;
                        lassoMoveStartX = canvasX;
                        lassoMoveStartY = canvasY;
                        selectionMoveDx = 0f;
                        selectionMoveDy = 0f;
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
                    if (isRotatingSelection) {
                        float currentAngle = (float) Math.toDegrees(Math.atan2(canvasY - rotationCenterY, canvasX - rotationCenterX));
                        float delta = currentAngle - lastRotationTouchAngle;
                        if (delta > 180f) delta -= 360f;
                        else if (delta < -180f) delta += 360f;
                        updateLiveRotation(delta);
                        lastRotationTouchAngle = currentAngle;
                    } else if (isResizingSelection) {
                        float curDist = (float) Math.hypot(canvasX - transformAnchorX, canvasY - transformAnchorY);
                        updateLiveResizing(curDist);
                    } else if (isMovingSelection) {
                        float dx = canvasX - lassoMoveStartX;
                        float dy = canvasY - lassoMoveStartY;
                        translateItems(selectedStrokes, selectedShapes, selectedTexts, dx, dy);
                        selectionMoveDx += dx;
                        selectionMoveDy += dy;
                        selectionBounds.offset(dx, dy);
                        lassoMoveStartX = canvasX;
                        lassoMoveStartY = canvasY;
                        postInvalidateOnAnimation();
                    } else if (isDrawingLasso) {
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            appendLassoPoint(
                                    (event.getHistoricalX(inputPointerIndex, i) - panOffsetX) / scaleFactor,
                                    (event.getHistoricalY(inputPointerIndex, i) - panOffsetY) / scaleFactor + viewportOffsetY);
                        }
                        appendLassoPoint(canvasX, canvasY);
                        postInvalidateOnAnimation();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (isRotatingSelection) {
                        isRotatingSelection = false;
                        activeLassoHandle = LassoHandle.NONE;
                        finishLiveRotation();
                    } else if (isResizingSelection) {
                        finishLiveResizing();
                    } else if (isMovingSelection) {
                        float finalDx = canvasX - lassoMoveStartX;
                        float finalDy = canvasY - lassoMoveStartY;
                        if (Math.abs(finalDx) > 0.001f || Math.abs(finalDy) > 0.001f) {
                            translateItems(selectedStrokes, selectedShapes, selectedTexts, finalDx, finalDy);
                            selectionMoveDx += finalDx;
                            selectionMoveDy += finalDy;
                            selectionBounds.offset(finalDx, finalDy);
                        }
                        isMovingSelection = false;
                        finishSelectionMove();
                        notifyChange();
                        notifyLassoSelection();
                    } else if (isDrawingLasso) {
                        isDrawingLasso = false;
                        appendLassoPoint(canvasX, canvasY);
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
                // User is actively writing: pause/reset any pending auto-convert timer immediately
                if (isAutoConvertTextEnabled && isAutoBeautifyEnabled) {
                    autoConvertHandler.removeCallbacks(autoConvertRunnable);
                    // Continuous Writing Flow at any angle:
                    // Keep strokes together while writing nearby (whether horizontal, diagonal, or multi-word).
                    // Only trigger intermediate conversion if the user makes a distant spatial jump across the page.
                    if (!pendingAutoConvertStrokes.isEmpty()) {
                        RectF lastBounds = getStrokesBounds(pendingAutoConvertStrokes);
                        if (!lastBounds.isEmpty() && hasEnoughInkForAutoConversion(pendingAutoConvertStrokes)) {
                            float distFromLast = (float) Math.hypot(canvasX - lastBounds.centerX(), canvasY - lastBounds.centerY());
                            if (distFromLast > 450f) {
                                List<Stroke> completed = new ArrayList<>(pendingAutoConvertStrokes);
                                pendingAutoConvertStrokes.clear();
                                convertStrokesToText(completed);
                            }
                        }
                    }
                }

                smoothedX = canvasX;
                smoothedY = canvasY;
                smoothedPressure = pressure;
                resetStrokeFilter(canvasX, canvasY, event.getEventTime());
                activeStroke = new Stroke(currentColor, currentStrokeWidth, currentTool);
                activeInputPointerId = event.getPointerId(inputPointerIndex);
                activeStroke.addPoint(canvasX, canvasY, pressure, event.getEventTime());
                activeStroke.addRecognitionPoint(canvasX, canvasY, pressure, event.getEventTime());
                lastTouchX = canvasX;
                lastTouchY = canvasY;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeStroke != null) {
                    float dirtyMinX = lastTouchX;
                    float dirtyMinY = lastTouchY;
                    float dirtyMaxX = lastTouchX;
                    float dirtyMaxY = lastTouchY;
                    boolean hadPredictedPoint = hasPredictedInkPoint;
                    float previousPredictedX = predictedInkX;
                    float previousPredictedY = predictedInkY;
                    int historySize = event.getHistorySize();
                    for (int i = 0; i < historySize; i++) {
                        float histRawX = event.getHistoricalX(inputPointerIndex, i);
                        float histRawY = event.getHistoricalY(inputPointerIndex, i);
                        float histX = (histRawX - panOffsetX) / scaleFactor;
                        float histY = (histRawY - panOffsetY) / scaleFactor + viewportOffsetY;
                        float historicalPressure = normalizeInputPressure(
                                event.getHistoricalPressure(inputPointerIndex, i), primaryToolType);
                        activeStroke.addRecognitionPoint(histX, histY, historicalPressure,
                                event.getHistoricalEventTime(i));

                        filterStrokeSample(histX, histY, historicalPressure,
                                event.getHistoricalEventTime(i));
                        activeStroke.addPoint(smoothedX, smoothedY, smoothedPressure,
                                event.getHistoricalEventTime(i));
                        dirtyMinX = Math.min(dirtyMinX, smoothedX);
                        dirtyMinY = Math.min(dirtyMinY, smoothedY);
                        dirtyMaxX = Math.max(dirtyMaxX, smoothedX);
                        dirtyMaxY = Math.max(dirtyMaxY, smoothedY);
                    }
                    filterStrokeSample(canvasX, canvasY, pressure, event.getEventTime());

                    activeStroke.addRecognitionPoint(canvasX, canvasY, pressure, event.getEventTime());
                    activeStroke.addPoint(smoothedX, smoothedY, smoothedPressure, event.getEventTime());
                    dirtyMinX = Math.min(dirtyMinX, smoothedX);
                    dirtyMinY = Math.min(dirtyMinY, smoothedY);
                    dirtyMaxX = Math.max(dirtyMaxX, smoothedX);
                    dirtyMaxY = Math.max(dirtyMaxY, smoothedY);
                    lastTouchX = smoothedX;
                    lastTouchY = smoothedY;

                    ensureContentHeight(canvasY);
                    updatePredictedInk(event, primaryToolType, inputPointerIndex);
                    if (hadPredictedPoint) {
                        dirtyMinX = Math.min(dirtyMinX, previousPredictedX);
                        dirtyMinY = Math.min(dirtyMinY, previousPredictedY);
                        dirtyMaxX = Math.max(dirtyMaxX, previousPredictedX);
                        dirtyMaxY = Math.max(dirtyMaxY, previousPredictedY);
                    }
                    if (hasPredictedInkPoint) {
                        dirtyMinX = Math.min(dirtyMinX, predictedInkX);
                        dirtyMinY = Math.min(dirtyMinY, predictedInkY);
                        dirtyMaxX = Math.max(dirtyMaxX, predictedInkX);
                        dirtyMaxY = Math.max(dirtyMaxY, predictedInkY);
                    }
                    invalidateStrokeBounds(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY,
                            activeStroke.width);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (activeStroke != null) {
                    // Finish at the real pen-up point. Filtered coordinates are only
                    // for the visible outline; recognition always gets raw samples.
                    smoothedX = canvasX;
                    smoothedY = canvasY;
                    smoothedPressure = pressure;
                    activeStroke.addRecognitionPoint(canvasX, canvasY, pressure, event.getEventTime());
                    activeStroke.addPoint(smoothedX, smoothedY, smoothedPressure, event.getEventTime());

                    // Straight-Line Snapping Assist: strictly for long deliberate ruler divider lines,
                    // NEVER hijack or flatten handwriting strokes or letters into slashes!
                    if (straightLineAssist && !isAutoConvertTextEnabled && activeStroke.points.size() > 10) {
                        DrawingPoint firstPt = activeStroke.points.get(0);
                        DrawingPoint lastPt = activeStroke.points.get(activeStroke.points.size() - 1);
                        float dist = (float) Math.hypot(lastPt.x - firstPt.x, lastPt.y - firstPt.y);
                        if (dist > 250f) {
                            float maxDeviation = 0f;
                            for (DrawingPoint point : activeStroke.points) {
                                float deviation = Math.abs((lastPt.y - firstPt.y) * point.x
                                        - (lastPt.x - firstPt.x) * point.y
                                        + lastPt.x * firstPt.y - lastPt.y * firstPt.x) / dist;
                                maxDeviation = Math.max(maxDeviation, deviation);
                            }
                            if (maxDeviation <= Math.max(4f, dist * 0.015f)) {
                                activeStroke.points.clear();
                                activeStroke.addPoint(firstPt.x, firstPt.y, 1.0f, firstPt.timeMillis);
                                activeStroke.addPoint(lastPt.x, lastPt.y, 1.0f, lastPt.timeMillis);
                                activeStroke.markDirty();
                            }
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
                        if (isAutoConvertTextEnabled && isAutoBeautifyEnabled) {
                            pendingAutoConvertStrokes.add(activeStroke);
                            autoConvertHandler.removeCallbacks(autoConvertRunnable);
                            boolean isIndic = "MARATHI".equalsIgnoreCase(beautifyLanguage) || "HINDI".equalsIgnoreCase(beautifyLanguage);
                            int strokeCount = pendingAutoConvertStrokes.size();
                            // Comfortable pause delay so continuous handwriting or multi-word sentences are not interrupted
                            long delay = isIndic ? (strokeCount <= 2 ? 3000L : 2600L) : 1800L;
                            autoConvertHandler.postDelayed(autoConvertRunnable, delay);
                        }
                    }
                    activeStroke = null;
                    activeInputPointerId = -1;
                    hasPredictedInkPoint = false;
                    invalidate();
                }
                return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    private RectF getStrokesBounds(List<Stroke> strokes) {
        RectF r = new RectF();
        for (Stroke s : strokes) {
            if (s != null && s.getBounds() != null && !s.getBounds().isEmpty()) {
                if (r.isEmpty()) r.set(s.getBounds());
                else r.union(s.getBounds());
            }
        }
        return r;
    }

    private void resetStrokeFilter(float x, float y, long timeMillis) {
        previousRawX = x;
        previousRawY = y;
        previousRawTimeMillis = timeMillis;
        filteredVelocityX = 0f;
        filteredVelocityY = 0f;
    }

    /** Time-based One Euro filtering: stable at slow speeds and responsive on fast strokes. */
    private void filterStrokeSample(float x, float y, float pressure, long timeMillis) {
        long elapsedMillis = previousRawTimeMillis < 0L
                ? 8L : Math.max(1L, Math.min(50L, timeMillis - previousRawTimeMillis));
        float dt = elapsedMillis / 1000f;
        float derivativeAlpha = lowPassAlpha(dt, 1.0f);
        float velocityX = (x - previousRawX) * scaleFactor / dt;
        float velocityY = (y - previousRawY) * scaleFactor / dt;
        filteredVelocityX += (velocityX - filteredVelocityX) * derivativeAlpha;
        filteredVelocityY += (velocityY - filteredVelocityY) * derivativeAlpha;

        float minimumCutoff = 5.5f - strokeStabilization * 2.5f;
        float speedResponsiveness = 0.015f + (1f - strokeStabilization) * 0.055f;
        float cutoffX = minimumCutoff + speedResponsiveness * Math.abs(filteredVelocityX);
        float cutoffY = minimumCutoff + speedResponsiveness * Math.abs(filteredVelocityY);
        smoothedX += (x - smoothedX) * lowPassAlpha(dt, cutoffX);
        smoothedY += (y - smoothedY) * lowPassAlpha(dt, cutoffY);
        smoothedPressure += (pressure - smoothedPressure) * lowPassAlpha(dt, 10f);

        previousRawX = x;
        previousRawY = y;
        previousRawTimeMillis = Math.max(previousRawTimeMillis, timeMillis);
    }

    private float lowPassAlpha(float dt, float cutoffHz) {
        float safeCutoff = Math.max(0.1f, cutoffHz);
        float tau = 1f / (2f * (float) Math.PI * safeCutoff);
        return dt / (dt + tau);
    }

    private void recordMotionEventForPrediction(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            predictorStreamValid = false;
            hasPredictedInkPoint = false;
            try {
                motionEventPredictor = MotionEventPredictor.newInstance(this);
            } catch (RuntimeException ignored) {
                motionEventPredictor = null;
            }
        }
        if (motionEventPredictor != null) {
            try {
                motionEventPredictor.record(event);
                if (action == MotionEvent.ACTION_DOWN) predictorStreamValid = true;
            } catch (IllegalArgumentException ignored) {
                // A vendor may split/cancel streams unusually. Drop only the
                // prediction; never drop or alter the real input event.
                predictorStreamValid = false;
                hasPredictedInkPoint = false;
                try {
                    motionEventPredictor = MotionEventPredictor.newInstance(this);
                } catch (RuntimeException unavailable) {
                    motionEventPredictor = null;
                }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            predictorStreamValid = false;
            hasPredictedInkPoint = false;
        }
    }

    private void updatePredictedInk(MotionEvent event, int toolType, int pointerIndex) {
        hasPredictedInkPoint = false;
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS || !predictorStreamValid
                || motionEventPredictor == null) return;
        MotionEvent prediction = null;
        try {
            prediction = motionEventPredictor.predict();
            if (prediction == null || pointerIndex < 0 || pointerIndex >= event.getPointerCount()
                    || pointerIndex >= prediction.getPointerCount()
                    || prediction.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS
                    || prediction.getPointerId(pointerIndex) != event.getPointerId(pointerIndex)) return;
            long horizon = prediction.getEventTime() - event.getEventTime();
            float dx = prediction.getX(pointerIndex) - event.getX(pointerIndex);
            float dy = prediction.getY(pointerIndex) - event.getY(pointerIndex);
            float maxDistance = 40f * getResources().getDisplayMetrics().density;
            if (horizon <= 0L || horizon > 40L || (dx * dx + dy * dy) > maxDistance * maxDistance) return;
            predictedInkX = (prediction.getX(pointerIndex) - panOffsetX) / scaleFactor;
            predictedInkY = (prediction.getY(pointerIndex) - panOffsetY) / scaleFactor + viewportOffsetY;
            predictedInkPressure = normalizeInputPressure(prediction.getPressure(pointerIndex), toolType);
            hasPredictedInkPoint = true;
        } catch (RuntimeException ignored) {
            hasPredictedInkPoint = false;
        } finally {
            if (prediction != null) prediction.recycle();
        }
    }

    /** Draw a short, disposable predicted tail. It is never appended to Stroke.points. */
    private void drawPredictedInk(Canvas canvas) {
        if (!hasPredictedInkPoint || activeStroke == null || activeStroke.points.isEmpty()) return;
        DrawingPoint last = activeStroke.points.get(activeStroke.points.size() - 1);
        predictedInkPath.reset();
        predictedInkPath.moveTo(last.x, last.y);
        predictedInkPath.lineTo(predictedInkX, predictedInkY);
        float pressureWidth = activeStroke.width * (0.6f + predictedInkPressure * 0.8f);
        predictedInkPaint.setColor(activeStroke.color);
        predictedInkPaint.setAlpha(activeStroke.tool == DrawingTool.HIGHLIGHTER ? 150 : 230);
        predictedInkPaint.setStrokeWidth(Math.max(1f, pressureWidth));
        canvas.drawPath(predictedInkPath, predictedInkPaint);
    }

    /** Invalidate only the screen patch changed by the most recent ink samples. */
    private void invalidateStrokeBounds(float minX, float minY, float maxX, float maxY,
                                        float strokeWidth) {
        float pad = Math.max(8f, strokeWidth * 2.5f) * scaleFactor + 3f;
        float left = minX * scaleFactor + panOffsetX - pad;
        float right = maxX * scaleFactor + panOffsetX + pad;
        float top = (minY - viewportOffsetY) * scaleFactor + panOffsetY - pad;
        float bottom = (maxY - viewportOffsetY) * scaleFactor + panOffsetY + pad;
        int l = Math.max(0, (int) Math.floor(left));
        int t = Math.max(0, (int) Math.floor(top));
        int r = Math.min(getWidth(), (int) Math.ceil(right));
        int b = Math.min(getHeight(), (int) Math.ceil(bottom));
        if (l < r && t < b) invalidate(l, t, r, b);
    }

    private void cancelInProgressGesture() {
        boolean movedSelection = isMovingSelection;
        if (isRotatingSelection) {
            isRotatingSelection = false;
            finishLiveRotation();
        }
        if (isResizingSelection) {
            finishLiveResizing();
        }
        activeLassoHandle = LassoHandle.NONE;
        activeStroke = null;
        activeInputPointerId = -1;
        isDrawingShape = false;
        isDrawingLasso = false;
        isMovingSelection = false;
        isRotatingSelection = false;
        isResizingSelection = false;
        isPanning = false;
        lassoPath.reset();
        lassoPoints.clear();
        laserTrail.clear();
        hasPredictedInkPoint = false;
        if (movedSelection) {
            finishSelectionMove();
            notifyChange();
            notifyLassoSelection();
        }
        invalidate();
    }

    /** Ask Android for the fastest supported writing cadence only during input. */
    private void setInputFrameRatePreference(boolean active) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        if (!active) {
            setRequestedFrameRate(0f);
            return;
        }
        Display display = getDisplay();
        float preferredRate = display == null ? 60f : display.getRefreshRate();
        if (display != null) {
            for (float supportedRate : display.getSupportedRefreshRates()) {
                preferredRate = Math.max(preferredRate, supportedRate);
            }
        }
        // 120 Hz is the target for this app; allow lower-rate displays to use
        // their own maximum rather than requesting an impossible cadence.
        setRequestedFrameRate(Math.min(120f, preferredRate));
    }

    @Override
    protected void onDetachedFromWindow() {
        setInputFrameRatePreference(false);
        predictorStreamValid = false;
        hasPredictedInkPoint = false;
        super.onDetachedFromWindow();
    }

    private float normalizeInputPressure(float pressure, int toolType) {
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS) return 0.5f;
        if (Float.isNaN(pressure) || Float.isInfinite(pressure) || pressure <= 0f) return 0.5f;
        return Math.max(0.05f, Math.min(1.0f, pressure));
    }

    private void handleTwoFingerPanZoom(MotionEvent event, int action) {
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    twoFingerStartX0 = event.getX(0);
                    twoFingerStartY0 = event.getY(0);
                    twoFingerStartX1 = event.getX(1);
                    twoFingerStartY1 = event.getY(1);
                    twoFingerStartX = (twoFingerStartX0 + twoFingerStartX1) / 2f;
                    twoFingerStartY = (twoFingerStartY0 + twoFingerStartY1) / 2f;
                    twoFingerStartSpan = (float) Math.hypot(
                            twoFingerStartX1 - twoFingerStartX0,
                            twoFingerStartY1 - twoFingerStartY0);
                    if (twoFingerStartSpan < dp(45f)) {
                        manualTwoFingerPanConfirmed = false;
                        break;
                    }
                    manualTwoFingerPanConfirmed = false;
                    initialPanOffsetX = panOffsetX;
                    initialPanOffsetY = panOffsetY;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2 && !scaleGestureConfirmed) {
                    float currentX0 = event.getX(0);
                    float currentY0 = event.getY(0);
                    float currentX1 = event.getX(1);
                    float currentY1 = event.getY(1);
                    float moveX0 = currentX0 - twoFingerStartX0;
                    float moveY0 = currentY0 - twoFingerStartY0;
                    float moveX1 = currentX1 - twoFingerStartX1;
                    float moveY1 = currentY1 - twoFingerStartY1;
                    float moveLength0 = (float) Math.hypot(moveX0, moveY0);
                    float moveLength1 = (float) Math.hypot(moveX1, moveY1);
                    float currentSpan = (float) Math.hypot(currentX1 - currentX0,
                            currentY1 - currentY0);
                    float gestureSlop = dp(8f);
                    if (!manualTwoFingerPanConfirmed) {
                        float directionAgreement = moveLength0 < 0.001f || moveLength1 < 0.001f
                                ? -1f : (moveX0 * moveX1 + moveY0 * moveY1)
                                / (moveLength0 * moveLength1);
                        boolean stableSpan = Math.abs(currentSpan - twoFingerStartSpan)
                                <= gestureSlop;
                        manualTwoFingerPanConfirmed = stableSpan
                                && moveLength0 >= gestureSlop && moveLength1 >= gestureSlop
                                && directionAgreement >= 0.65f;
                    }
                    if (!manualTwoFingerPanConfirmed) break;
                    float currentCenterX = (currentX0 + currentX1) / 2f;
                    float currentCenterY = (currentY0 + currentY1) / 2f;
                    panOffsetX = initialPanOffsetX + (currentCenterX - twoFingerStartX);
                    panOffsetY = initialPanOffsetY + (currentCenterY - twoFingerStartY);
                    invalidate();
                    notifyLassoSelection();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                manualTwoFingerPanConfirmed = false;
                break;
        }
    }

    private static int findStylusPointerIndex(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                    || toolType == MotionEvent.TOOL_TYPE_ERASER) return i;
        }
        return -1;
    }

    private boolean isPalmTouch(MotionEvent event) {
        if (event == null) return false;
        int count = event.getPointerCount();
        for (int i = 0; i < count; i++) {
            if (event.getToolType(i) == 5 /* TOOL_TYPE_PALM */) return true;
            float touchMajor = event.getTouchMajor(i);
            if (touchMajor > dp(65f)) return true;
            float size = event.getSize(i);
            if (size > 0.45f) return true;
        }
        return false;
    }

    private void cancelScaleGestureDetector(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        scaleDetector.onTouchEvent(cancel);
        cancel.recycle();
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
        selectedTexts.clear();

        prepareVisibleStrokeIndices(lassoBounds);
        for (int index = visibleStrokeIndices.nextSetBit(0); index >= 0;
             index = visibleStrokeIndices.nextSetBit(index + 1)) {
            if (index >= currentPage.strokes.size()) break;
            Stroke s = currentPage.strokes.get(index);
            if (s != null && RectF.intersects(s.getBounds(), lassoBounds)
                    && strokeIntersectsLasso(s, lassoBounds)) selectedStrokes.add(s);
        }

        for (ShapeItem sh : currentPage.shapes) {
            if (sh != null && RectF.intersects(sh.getBounds(), lassoBounds)
                    && shapeIntersectsLasso(sh)) selectedShapes.add(sh);
        }

        for (TextItem item : currentPage.texts) {
            if (item != null && RectF.intersects(item.getBounds(), lassoBounds)
                    && boundsIntersectLasso(item.getBounds(), lassoBounds)) selectedTexts.add(item);
        }

        selectedImages.clear();
        if (currentPage.images != null) {
            for (ImageItem item : currentPage.images) {
                if (item != null && RectF.intersects(item.getBounds(), lassoBounds)
                        && boundsIntersectLasso(item.getBounds(), lassoBounds)) selectedImages.add(item);
            }
        }

        hasLassoSelection = hasAnySelectedItems();
        if (hasLassoSelection) {
            updateSelectionBounds();
            announceSelectionCount();
        } else {
            clearSelection();
            announceForAccessibility("No content selected.");
        }
    }

    private void appendLassoPoint(float x, float y) {
        if (!lassoPoints.isEmpty()) {
            DrawingPoint last = lassoPoints.get(lassoPoints.size() - 1);
            float dx = x - last.x;
            float dy = y - last.y;
            if (dx * dx + dy * dy < 1.44f) return;
        }
        lassoPath.lineTo(x, y);
        lassoPoints.add(new DrawingPoint(x, y, 1f));
    }

    private boolean strokeIntersectsLasso(Stroke stroke, RectF lassoBounds) {
        List<DrawingPoint> points = stroke.points;
        if (points == null || points.isEmpty()) return false;
        DrawingPoint previous = points.get(0);
        if (lassoBounds.contains(previous.x, previous.y)
                && isPointInsideLasso(previous.x, previous.y)) return true;
        for (int i = 1; i < points.size(); i++) {
            DrawingPoint current = points.get(i);
            if (lassoBounds.contains(current.x, current.y)
                    && isPointInsideLasso(current.x, current.y)) return true;
            if (segmentIntersectsLasso(previous.x, previous.y, current.x, current.y)) return true;
            previous = current;
        }
        return false;
    }

    private boolean shapeIntersectsLasso(ShapeItem shape) {
        float left = Math.min(shape.startX, shape.endX);
        float right = Math.max(shape.startX, shape.endX);
        float top = Math.min(shape.startY, shape.endY);
        float bottom = Math.max(shape.startY, shape.endY);
        switch (shape.type) {
            case LINE:
                return segmentIntersectsLasso(shape.startX, shape.startY, shape.endX, shape.endY);
            case ARROW:
                return segmentIntersectsLasso(shape.startX, shape.startY, shape.endX, shape.endY)
                        || arrowHeadIntersectsLasso(shape.startX, shape.startY, shape.endX, shape.endY);
            case RECTANGLE:
                return polylineIntersectsLasso(Arrays.asList(
                        new DrawingPoint(left, top, 1f), new DrawingPoint(right, top, 1f),
                        new DrawingPoint(right, bottom, 1f), new DrawingPoint(left, bottom, 1f)), true);
            case CIRCLE:
                List<DrawingPoint> ovalPoints = new ArrayList<>(48);
                float centerX = (left + right) * 0.5f;
                float centerY = (top + bottom) * 0.5f;
                float radiusX = (right - left) * 0.5f;
                float radiusY = (bottom - top) * 0.5f;
                for (int i = 0; i < 48; i++) {
                    double angle = i * Math.PI * 2.0 / 48.0;
                    ovalPoints.add(new DrawingPoint(centerX + radiusX * (float) Math.cos(angle),
                            centerY + radiusY * (float) Math.sin(angle), 1f));
                }
                return polylineIntersectsLasso(ovalPoints, true);
            case TRIANGLE:
                return polylineIntersectsLasso(Arrays.asList(
                        new DrawingPoint((shape.startX + shape.endX) * 0.5f, top, 1f),
                        new DrawingPoint(right, bottom, 1f), new DrawingPoint(left, bottom, 1f)), true);
            case STAR:
                List<DrawingPoint> starPoints = new ArrayList<>(10);
                float starCenterX = (shape.startX + shape.endX) * 0.5f;
                float starCenterY = (shape.startY + shape.endY) * 0.5f;
                float outerRadius = Math.min(right - left, bottom - top) * 0.5f;
                for (int i = 0; i < 10; i++) {
                    double angle = i * Math.PI / 5.0 - Math.PI / 2.0;
                    float radius = i % 2 == 0 ? outerRadius : outerRadius * 0.4f;
                    starPoints.add(new DrawingPoint(starCenterX + radius * (float) Math.cos(angle),
                            starCenterY + radius * (float) Math.sin(angle), 1f));
                }
                return polylineIntersectsLasso(starPoints, true);
            case COORDINATE_AXIS:
                float midX = (left + right) * 0.5f;
                float midY = (top + bottom) * 0.5f;
                return segmentIntersectsLasso(left, midY, right, midY)
                        || arrowHeadIntersectsLasso(left, midY, right, midY)
                        || segmentIntersectsLasso(midX, bottom, midX, top)
                        || arrowHeadIntersectsLasso(midX, bottom, midX, top);
            case TABLE:
                if (polylineIntersectsLasso(Arrays.asList(
                        new DrawingPoint(left, top, 1f), new DrawingPoint(right, top, 1f),
                        new DrawingPoint(right, bottom, 1f), new DrawingPoint(left, bottom, 1f)), true)) {
                    return true;
                }
                float rowHeight = (bottom - top) / 3f;
                float colWidth = (right - left) / 3f;
                for (int i = 1; i < 3; i++) {
                    if (segmentIntersectsLasso(left, top + i * rowHeight, right, top + i * rowHeight)
                            || segmentIntersectsLasso(left + i * colWidth, top,
                            left + i * colWidth, bottom)) return true;
                }
                return false;
            default:
                return false;
        }
    }

    private boolean polylineIntersectsLasso(List<DrawingPoint> points, boolean close) {
        if (points.isEmpty()) return false;
        for (DrawingPoint point : points) {
            if (isPointInsideLasso(point.x, point.y)) return true;
        }
        for (int i = 1; i < points.size(); i++) {
            DrawingPoint from = points.get(i - 1), to = points.get(i);
            if (segmentIntersectsLasso(from.x, from.y, to.x, to.y)) return true;
        }
        if (close && points.size() > 2) {
            DrawingPoint from = points.get(points.size() - 1), to = points.get(0);
            return segmentIntersectsLasso(from.x, from.y, to.x, to.y);
        }
        return false;
    }

    private boolean arrowHeadIntersectsLasso(float x1, float y1, float x2, float y2) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        float arrowSize = 36f;
        float ax = (float) (x2 - arrowSize * Math.cos(angle - Math.PI / 6));
        float ay = (float) (y2 - arrowSize * Math.sin(angle - Math.PI / 6));
        float bx = (float) (x2 - arrowSize * Math.cos(angle + Math.PI / 6));
        float by = (float) (y2 - arrowSize * Math.sin(angle + Math.PI / 6));
        return segmentIntersectsLasso(x2, y2, ax, ay) || segmentIntersectsLasso(x2, y2, bx, by);
    }

    private boolean boundsIntersectLasso(RectF bounds, RectF lassoBounds) {
        if (bounds == null || bounds.isEmpty() || !RectF.intersects(bounds, lassoBounds)) return false;
        if (isPointInsideLasso(bounds.left, bounds.top)
                || isPointInsideLasso(bounds.right, bounds.top)
                || isPointInsideLasso(bounds.right, bounds.bottom)
                || isPointInsideLasso(bounds.left, bounds.bottom)) return true;
        for (DrawingPoint point : lassoPoints) {
            if (bounds.contains(point.x, point.y)) return true;
        }
        return polygonEdgeIntersectsRect(bounds);
    }

    private boolean polygonEdgeIntersectsRect(RectF bounds) {
        for (int i = 0; i < lassoPoints.size(); i++) {
            DrawingPoint start = lassoPoints.get(i);
            DrawingPoint end = lassoPoints.get((i + 1) % lassoPoints.size());
            if (segmentIntersectsRect(start.x, start.y, end.x, end.y, bounds)) return true;
        }
        return false;
    }

    private boolean segmentIntersectsRect(float x1, float y1, float x2, float y2, RectF rect) {
        if (rect.contains(x1, y1) || rect.contains(x2, y2)) return true;
        return segmentsIntersect(x1, y1, x2, y2, rect.left, rect.top, rect.right, rect.top)
                || segmentsIntersect(x1, y1, x2, y2, rect.right, rect.top, rect.right, rect.bottom)
                || segmentsIntersect(x1, y1, x2, y2, rect.right, rect.bottom, rect.left, rect.bottom)
                || segmentsIntersect(x1, y1, x2, y2, rect.left, rect.bottom, rect.left, rect.top);
    }

    private boolean segmentIntersectsLasso(float x1, float y1, float x2, float y2) {
        if (isPointInsideLasso(x1, y1) || isPointInsideLasso(x2, y2)) return true;
        for (int i = 0; i < lassoPoints.size(); i++) {
            DrawingPoint start = lassoPoints.get(i);
            DrawingPoint end = lassoPoints.get((i + 1) % lassoPoints.size());
            if (segmentsIntersect(x1, y1, x2, y2, start.x, start.y, end.x, end.y)) return true;
        }
        return false;
    }

    private boolean isPointInsideLasso(float x, float y) {
        boolean inside = false;
        for (int i = 0, j = lassoPoints.size() - 1; i < lassoPoints.size(); j = i++) {
            DrawingPoint a = lassoPoints.get(i);
            DrawingPoint b = lassoPoints.get(j);
            boolean crosses = (a.y > y) != (b.y > y)
                    && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x;
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private boolean segmentsIntersect(float ax, float ay, float bx, float by,
                                      float cx, float cy, float dx, float dy) {
        float abC = orientation(ax, ay, bx, by, cx, cy);
        float abD = orientation(ax, ay, bx, by, dx, dy);
        float cdA = orientation(cx, cy, dx, dy, ax, ay);
        float cdB = orientation(cx, cy, dx, dy, bx, by);
        if (((abC > 0f && abD < 0f) || (abC < 0f && abD > 0f))
                && ((cdA > 0f && cdB < 0f) || (cdA < 0f && cdB > 0f))) return true;
        return Math.abs(abC) < 0.001f && pointOnSegment(cx, cy, ax, ay, bx, by)
                || Math.abs(abD) < 0.001f && pointOnSegment(dx, dy, ax, ay, bx, by)
                || Math.abs(cdA) < 0.001f && pointOnSegment(ax, ay, cx, cy, dx, dy)
                || Math.abs(cdB) < 0.001f && pointOnSegment(bx, by, cx, cy, dx, dy);
    }

    private float orientation(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private boolean pointOnSegment(float px, float py, float ax, float ay, float bx, float by) {
        return px >= Math.min(ax, bx) - 0.001f && px <= Math.max(ax, bx) + 0.001f
                && py >= Math.min(ay, by) - 0.001f && py <= Math.max(ay, by) + 0.001f;
    }

    private void checkStrokeErasure(float touchX, float touchY) {
        if (currentPage == null) return;
        float hitRadius = 35f / scaleFactor;

        // Check text erasure first (so user can erase individual converted words)
        if (currentPage.texts != null && !currentPage.texts.isEmpty()) {
            for (int i = currentPage.texts.size() - 1; i >= 0; i--) {
                TextItem item = currentPage.texts.get(i);
                if (item == null) continue;
                RectF b = item.getBounds();
                if (touchX >= b.left - hitRadius && touchX <= b.right + hitRadius
                        && touchY >= b.top - hitRadius && touchY <= b.bottom + hitRadius) {
                    TextItem removed = currentPage.texts.remove(i);
                    ActionRecord rec = new ActionRecord();
                    rec.type = ActionRecord.Type.REMOVE_TEXT;
                    rec.text = removed;
                    undoStack.push(rec);
                    redoStack.clear();
                    invalidate();
                    notifyChange();
                    return;
                }
            }
        }

        if (currentPage.strokes.isEmpty()) return;
        eraserQueryBounds.set(touchX - hitRadius, touchY - hitRadius,
                touchX + hitRadius, touchY + hitRadius);
        prepareVisibleStrokeIndices(eraserQueryBounds);

        for (int i = visibleStrokeIndices.previousSetBit(currentPage.strokes.size() - 1);
             i >= 0; i = visibleStrokeIndices.previousSetBit(i - 1)) {
            Stroke s = currentPage.strokes.get(i);
            RectF bounds = s.getBounds();
            if (touchX < bounds.left - hitRadius || touchX > bounds.right + hitRadius
                    || touchY < bounds.top - hitRadius || touchY > bounds.bottom + hitRadius) {
                continue;
            }
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
        syncAccessibilityActions();
        if (changeListener != null) {
            changeListener.onCanvasChanged();
        }
    }

    public RectF getPageContentBounds() {
        return getPageContentBounds(currentPage);
    }

    public RectF getPageContentBounds(Page page) {
        if (page == null) {
            float fallbackW = Math.max(1080f, (float) Math.max(1, getWidth()));
            float fallbackH = Math.max(1528f, (float) Math.max(1, getHeight()));
            return new RectF(0f, 0f, fallbackW, fallbackH);
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean hasContent = false;

        if (page.strokes != null) {
            for (Stroke s : page.strokes) {
                if (s == null) continue;
                RectF b = s.getBounds();
                if (b != null && !b.isEmpty()) {
                    minX = Math.min(minX, b.left);
                    minY = Math.min(minY, b.top);
                    maxX = Math.max(maxX, b.right);
                    maxY = Math.max(maxY, b.bottom);
                    hasContent = true;
                }
            }
        }

        if (page.shapes != null) {
            for (ShapeItem sh : page.shapes) {
                if (sh == null) continue;
                RectF b = sh.getBounds();
                if (b != null && !b.isEmpty()) {
                    minX = Math.min(minX, b.left);
                    minY = Math.min(minY, b.top);
                    maxX = Math.max(maxX, b.right);
                    maxY = Math.max(maxY, b.bottom);
                    hasContent = true;
                }
            }
        }

        if (page.texts != null) {
            for (TextItem t : page.texts) {
                if (t == null) continue;
                RectF b = t.getBounds();
                if (b != null && !b.isEmpty()) {
                    minX = Math.min(minX, b.left);
                    minY = Math.min(minY, b.top);
                    maxX = Math.max(maxX, b.right);
                    maxY = Math.max(maxY, b.bottom);
                    hasContent = true;
                }
            }
        }

        if (page.images != null) {
            for (ImageItem img : page.images) {
                if (img == null) continue;
                RectF b = img.getBounds();
                if (b != null && !b.isEmpty()) {
                    minX = Math.min(minX, b.left);
                    minY = Math.min(minY, b.top);
                    maxX = Math.max(maxX, b.right);
                    maxY = Math.max(maxY, b.bottom);
                    hasContent = true;
                }
            }
        }

        if (!hasContent) {
            float fallbackW = Math.max(1080f, (float) Math.max(1, getWidth()));
            float fallbackH = Math.max(1528f, (float) Math.max(1, getHeight()));
            return new RectF(0f, 0f, fallbackW, fallbackH);
        }

        float pad = 90f;
        float left = minX - pad;
        float top = minY - pad;
        float right = maxX + pad;
        float bottom = maxY + pad;

        float width = right - left;
        float height = bottom - top;

        float minWidth = Math.max(1080f, (float) Math.max(1, getWidth()));
        if (width < minWidth) {
            float diff = minWidth - width;
            left -= diff / 2f;
            right += diff / 2f;
            width = minWidth;
        }

        float minHeight = (height >= width) ? Math.max(1528f, width * 1.35f) : Math.max(800f, minWidth * 0.70f);
        if (height < minHeight) {
            float diff = minHeight - height;
            bottom += diff;
        }

        return new RectF(left, top, right, bottom);
    }

    public float getActualContentBottom() {
        return getPageContentBounds().bottom;
    }

    public Bitmap renderToBitmap() {
        RectF bounds = getPageContentBounds();
        float contentW = bounds.width();
        float contentH = bounds.height();

        int targetWidth;
        int targetHeight;
        if (contentW > contentH) {
            targetWidth = 2560;
            targetHeight = Math.max(800, (int) Math.ceil(targetWidth * (contentH / contentW)));
        } else {
            targetWidth = 1800;
            targetHeight = Math.max(1000, (int) Math.ceil(targetWidth * (contentH / contentW)));
        }

        return renderToBitmap(targetWidth, targetHeight, bounds);
    }

    public Bitmap renderToBitmap(int targetWidth, int targetHeight) {
        return renderToBitmap(targetWidth, targetHeight, getPageContentBounds());
    }

    public Bitmap renderToBitmap(int targetWidth, int targetHeight, float effectiveHeight) {
        RectF bounds = getPageContentBounds();
        return renderToBitmap(targetWidth, targetHeight, bounds);
    }

    public Bitmap renderToBitmap(int targetWidth, int targetHeight, RectF contentBounds) {
        if (contentBounds == null) contentBounds = getPageContentBounds();
        float contentW = contentBounds.width();
        float contentH = contentBounds.height();

        float scaleX = (float) targetWidth / contentW;
        float scaleY = (float) targetHeight / contentH;
        float uniformScale = Math.min(scaleX, scaleY);

        float extraX = (targetWidth - contentW * uniformScale) * 0.5f;
        float extraY = (targetHeight - contentH * uniformScale) * 0.5f;

        Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        boolean isDark = isColorDark(pageBackgroundColor);
        canvas.drawColor(pageBackgroundColor);

        canvas.save();
        canvas.translate(extraX, extraY);
        canvas.scale(uniformScale, uniformScale);
        canvas.translate(-contentBounds.left, -contentBounds.top);

        float viewLeft = contentBounds.left - extraX / uniformScale;
        float viewRight = contentBounds.right + extraX / uniformScale;
        float viewTop = contentBounds.top - extraY / uniformScale;
        float viewBottom = contentBounds.bottom + extraY / uniformScale;

        visibleViewport.set(viewLeft, viewTop, viewRight, viewBottom);
        drawPaperTemplate(canvas, (int) (viewRight - viewLeft), viewBottom - viewTop, isDark, uniformScale);

        if (currentPage != null) {
            if (currentPage.images != null) {
                for (ImageItem img : currentPage.images) {
                    drawImage(canvas, img);
                }
            }
            if (currentPage.shapes != null) {
                for (ShapeItem shape : currentPage.shapes) {
                    drawShape(canvas, shape.type, shape.startX, shape.startY, shape.endX, shape.endY, shape.color, shape.strokeWidth);
                }
            }
            if (currentPage.texts != null) {
                for (TextItem item : currentPage.texts) {
                    textPaint.setColor(item.color);
                    textPaint.setTextSize(item.fontSize);
                    textPaint.setTypeface(org.opennotes.app.util.TypefaceManager.getTypeface(getContext(), item.fontKey));
                    if (Math.abs(item.rotationAngle) > 0.5f) {
                        canvas.save();
                        canvas.rotate(item.rotationAngle, item.x, item.y);
                        canvas.drawText(item.text, item.x, item.y, textPaint);
                        canvas.restore();
                    } else {
                        canvas.drawText(item.text, item.x, item.y, textPaint);
                    }
                }
            }
            if (currentPage.strokes != null) {
                for (Stroke stroke : currentPage.strokes) {
                    drawStroke(canvas, stroke);
                }
            }
        }

        canvas.restore();
        return bitmap;
    }
}
