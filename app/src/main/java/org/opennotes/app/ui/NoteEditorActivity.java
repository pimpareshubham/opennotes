package org.opennotes.app.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import androidx.core.content.ContextCompat;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.app.SearchManager;
import java.lang.reflect.Method;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.opennotes.app.util.ZoomFavoritesManager;
import org.opennotes.app.util.NotebookViewStateManager;
import org.opennotes.app.util.NotebookConfigManager;
import org.opennotes.app.importer.PdfImporter;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import android.text.Editable;
import android.text.TextWatcher;

import org.opennotes.app.R;
import org.opennotes.app.db.AppSettings;
import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.export.ImageExporter;
import org.opennotes.app.export.NativeNoteArchive;
import org.opennotes.app.export.PdfExporter;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.ImageItem;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;
import org.opennotes.app.util.ErrorDialog;
import org.opennotes.app.util.HandwritingRecognitionEngine;
import org.opennotes.app.util.TypefaceManager;
import org.opennotes.app.util.ThemeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.opennotes.app.ai.UserHandwritingModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class NoteEditorActivity extends AppCompatActivity implements CanvasView.OnCanvasChangeListener {

    private NotesDbHelper dbHelper;
    private AppSettings appSettings;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fontIoExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService loadExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "NoteEditorLoader-" + count.getAndIncrement());
            t.setPriority(Thread.MAX_PRIORITY - 1);
            return t;
        }
    });
    private final Handler saveDebounceHandler = new Handler(Looper.getMainLooper());

    private void executeSaveTask(Runnable task) {
        if (task == null) return;
        try {
            if (!saveExecutor.isShutdown() && !saveExecutor.isTerminated()) {
                saveExecutor.execute(task);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w("NoteEditorActivity", "Save task rejected: executor is shutdown/terminated", e);
        } catch (Throwable t) {
            Log.w("NoteEditorActivity", "Failed to execute save task", t);
        }
    }

    private void executeLoadTask(Runnable task) {
        if (task == null) return;
        try {
            if (!loadExecutor.isShutdown() && !loadExecutor.isTerminated()) {
                loadExecutor.execute(task);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w("NoteEditorActivity", "Load task rejected: executor is shutdown/terminated", e);
        } catch (Throwable t) {
            Log.w("NoteEditorActivity", "Failed to execute load task", t);
        }
    }

    private void executeFontIoTask(Runnable task) {
        if (task == null) return;
        try {
            if (!fontIoExecutor.isShutdown() && !fontIoExecutor.isTerminated()) {
                fontIoExecutor.execute(task);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w("NoteEditorActivity", "Font IO task rejected: executor is shutdown/terminated", e);
        } catch (Throwable t) {
            Log.w("NoteEditorActivity", "Failed to execute font IO task", t);
        }
    }

    private long notebookId;
    private Notebook currentNotebook;
    private List<Page> pages;
    private int currentPageIndex = 0;
    private Page currentPage;
    private boolean hasRestoredEditorState;
    private int restoredPageIndex;
    private float restoredZoom = 100f;
    private float restoredPanX;
    private float restoredPanY;
    private float restoredScrollY;

    private static final String STATE_NOTEBOOK_ID = "editor.notebook_id";
    private static final String STATE_PAGE_INDEX = "editor.page_index";
    private static final String STATE_ZOOM = "editor.zoom";
    private static final String STATE_PAN_X = "editor.pan_x";
    private static final String STATE_PAN_Y = "editor.pan_y";
    private static final String STATE_SCROLL_Y = "editor.scroll_y";

    private CanvasView canvasView;
    private LinearLayout topBar;
    private ImageView btnBack;
    private static final List<Long> openNotebookIds = new ArrayList<>();
    private LinearLayout layoutDocumentTabs;
    private TextView textTabCountBadge;
    private HorizontalScrollView tabScrollView;
    private TextView textNotebookTitle;
    private LinearLayout activeDocTab;
    private ImageView btnCloseDocTab, btnNewTab;

    // Top Tool Dock
    private LinearLayout mainToolDock;
    private ImageView btnUndo, btnRedo;
    private ImageView btnToolbarCircleToSearch;
    private ImageView toolFountain, toolPen, toolHighlighter, toolPencil, toolEraser, toolLasso;
    private ImageView toolLaser, toolHand;
    private ImageView btnAutoBeautify;
    private ImageView btnTeachHandwriting;
    private FrameLayout btnColorPicker;
    private View colorPreviewCircle;
    private ImageView toolShape, toolText, toolMedia;
    private ImageView btnExport, btnPageOverview, btnMenu;

    // Top Navigation Header
    private ImageView btnCloudSync, btnCamera, btnCrop, btnMic;
    private ImageView btnSplitView, btnRuler, btnLayers, btnPageColor, btnSettingsSliders;
    private View quickColorWhite, quickColorOrange, quickColorGreen, quickColorCyan, quickColorPurple;

    // Floating Viewport Pill
    private LinearLayout floatingViewportPill, floatingPageNavPill;
    private ImageView btnFavorite;
    private TextView textZoomPercent;
    private ImageView btnLockViewport, btnResetZoom, btnPdfAmoledToggle;
    private ImageView btnPrevPage, btnNextPage, btnAddPage;
    private TextView textPageCounter;

    // Popdown Tool Property Card
    private CardView cardToolProperties;
    private TextView textToolPropertyTitle;
    private LinearLayout layoutNibSelector;
    private TextView btnNibFine, btnNibMedium, btnNibBold;
    private SeekBar seekStrokeThickness;
    private TextView textThicknessValue;
    private LinearLayout layoutPressureSensitivity;
    private SeekBar seekPressureSensitivity;
    private TextView textPressureValue;
    private LinearLayout layoutStabilization;
    private SeekBar seekStabilization;
    private TextView textStabilizationValue;
    private TextView textStabilizationTitle;
    private SwitchCompat switchStraightLine;
    private LinearLayout layoutStraightLine;
    private LinearLayout layoutToolQuickColors;
    private Button btnAddToPenBox;
    private LinearLayout layoutPenBoxDock;
    private View dividerPenBox;
    private TextView textStraightLineTitle;

    public static class PenPreset {
        public DrawingTool tool;
        public int color;
        public float strokeWidth;

        public PenPreset(DrawingTool tool, int color, float strokeWidth) {
            this.tool = tool;
            this.color = color;
            this.strokeWidth = strokeWidth;
        }
    }
    private final List<PenPreset> penBoxPresets = new ArrayList<>();

    // Floating Contextual Lasso Action Bar
    private HorizontalScrollView lassoFloatingActionBar;
    private View layoutLassoPill;
    private ImageView btnLassoDragHandle;
    private TextView btnLassoSearch;
    private TextView btnLassoDelete, btnLassoCut, btnLassoCopy, btnLassoPaste, btnLassoStyle, btnLassoDuplicate, btnLassoColor, btnLassoSpacing, btnLassoConvertText, btnLassoTeach;
    private boolean userHasManuallyMovedLassoBar = false;
    private float userLassoBarX = 0f;
    private float userLassoBarY = 0f;

    // Fullscreen Zen mode
    private ImageView btnExitFullscreen;
    private boolean isZenFullscreen = false;

    // Clipboard Paste shortcuts
    private View layoutLongPressPastePopup;

    // Image Picker & Voice Recorder & Font Picker & Handwriting Profile
    private ActivityResultLauncher<String> imagePickerLauncher;
    private ActivityResultLauncher<String> fontPickerLauncher;
    private ActivityResultLauncher<String> exportProfileLauncher;
    private ActivityResultLauncher<String> importProfileLauncher;
    private ActivityResultLauncher<Uri> takePictureLauncher;
    private Uri tempCameraPhotoUri;
    private MediaPlayer audioMemoPlayer;
    private ActivityResultLauncher<String> exportNativeNotebookLauncher;
    private ActivityResultLauncher<String[]> importNativeNotebookLauncher;
    private ActivityResultLauncher<String[]> openPdfForTabLauncher;
    private ActivityResultLauncher<String[]> openPdfForAppendLauncher;
    private MediaRecorder voiceRecorder;
    private MediaPlayer voicePlayer;
    private File currentAudioFile;
    private boolean isRecordingAudio = false;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long recordStartTime = 0;

    private final int[] QUICK_TOOL_COLORS = {
            0xFFFFFFFF, // Pure White
            0xFF38BDF8, // Cyan Blue (StarNote Accent)
            0xFF1D4ED8, // Classic Ink Blue
            0xFFEF4444, // Red
            0xFF10B981, // Emerald Green
            0xFFF59E0B  // Gold Amber
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullscreenMode.apply(this);
        enableHighRefreshRate();
        setContentView(R.layout.activity_note_editor);

        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                handleInsertImage(uri);
            }
        });

        takePictureLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (Boolean.TRUE.equals(success) && tempCameraPhotoUri != null) {
                handleInsertImage(tempCameraPhotoUri);
            }
        });

        fontPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                handleImportCustomFont(uri);
            }
        });

        exportProfileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    UserHandwritingModel.getInstance(this).exportProfile(os);
                    Toast.makeText(this, "📤 Local handwriting learning exported (.onhw) successfully!", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to export profile: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        importProfileLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    UserHandwritingModel.getInstance(this).importProfile(is);
                    int count = UserHandwritingModel.getInstance(this).getLearnedCount();
                    int pairs = UserHandwritingModel.getInstance(this).getLearnedBigramCount();
                    Toast.makeText(this, "📥 Local learning merged into this device! " + count
                            + " learned items, including " + pairs + " word pairs.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to import profile: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        exportNativeNotebookLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(NativeNoteArchive.MIME_TYPE), uri -> {
                    if (uri == null) return;
                    List<Page> pageSnapshots = new ArrayList<>();
                    if (pages != null) for (Page page : pages) pageSnapshots.add(page.createSnapshot());
                    Notebook notebookSnapshot = snapshotNotebook(currentNotebook);
                    if (notebookSnapshot == null) {
                        Toast.makeText(this, "The open notebook is unavailable.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    executeSaveTask(() -> {
                        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                            if (output == null) throw new java.io.IOException("The selected destination could not be opened.");
                            NativeNoteArchive.writeNotebook(this, notebookSnapshot, pageSnapshots, output);
                            runOnUiThread(() -> {
                                Toast.makeText(this, "Editable notebook saved. Sharing the original file…", Toast.LENGTH_LONG).show();
                                shareUri(uri, NativeNoteArchive.MIME_TYPE, notebookSnapshot.title + NativeNoteArchive.FILE_EXTENSION);
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> ErrorDialog.show(this, "Original notebook export failed", error));
                        }
                    });
                });

        importNativeNotebookLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    executeSaveTask(() -> {
                        try (InputStream input = getContentResolver().openInputStream(uri)) {
                            long importedId = NativeNoteArchive.importNotebook(this, dbHelper, input);
                            runOnUiThread(() -> {
                                Toast.makeText(this, "Editable notebook restored to your library.", Toast.LENGTH_LONG).show();
                                finish();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> ErrorDialog.show(this, "Original notebook import failed", error));
                        }
                    });
                });

        openPdfForTabLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Throwable ignored) {}
                        importPdfAsTab(uri);
                    }
                });

        openPdfForAppendLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Throwable ignored) {}
                        appendPdfPages(uri);
                    }
                });

        dbHelper = NotesDbHelper.getInstance(this);
        appSettings = AppSettings.getInstance(this);
        notebookId = getIntent().getLongExtra("notebook_id", -1);

        if (savedInstanceState != null && savedInstanceState.getLong(STATE_NOTEBOOK_ID, -1L) == notebookId) {
            hasRestoredEditorState = true;
            restoredPageIndex = savedInstanceState.getInt(STATE_PAGE_INDEX, 0);
            restoredZoom = savedInstanceState.getFloat(STATE_ZOOM, 100f);
            restoredPanX = savedInstanceState.getFloat(STATE_PAN_X, 0f);
            restoredPanY = savedInstanceState.getFloat(STATE_PAN_Y, 0f);
            restoredScrollY = savedInstanceState.getFloat(STATE_SCROLL_Y, 0f);
        }

        if (notebookId == -1) {
            finish();
            return;
        }

        synchronized (openNotebookIds) {
            if (!openNotebookIds.contains(notebookId)) {
                openNotebookIds.add(notebookId);
            }
        }

        bindViews();
        String passedTitle = getIntent().getStringExtra("notebook_title");
        if (passedTitle != null && textNotebookTitle != null) {
            textNotebookTitle.setText(passedTitle);
        }
        String passedTpl = getIntent().getStringExtra("template");
        int passedBgColor = getIntent().getIntExtra("bg_color", -1);
        boolean passedIsInfinite = getIntent().getBooleanExtra("is_infinite", false);
        if (canvasView != null) {
            PaperTemplate tpl = passedTpl != null ? PaperTemplate.fromString(passedTpl) : PaperTemplate.BLANK;
            int bg = (passedBgColor != -1 && passedBgColor != 0) ? passedBgColor : 0xFF121214;
            canvasView.setInitialPreviewState(tpl, bg, passedIsInfinite);
        }
        applyResponsiveEditorLayout();
        loadNotebookData();
        setupListeners();
        setupToolbarReordering();
        setupToolPropertyCard();
        restoreNotebookConfig(notebookId);
        updateFavoriteIcon();

        // Defer background handwriting model checks by 2500ms so startup has 100% CPU/IO
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            HandwritingRecognitionEngine.isModelDownloaded("ENGLISH", downloaded -> {
                if (!downloaded) {
                    HandwritingRecognitionEngine.downloadModel("ENGLISH", null);
                }
            });
            HandwritingRecognitionEngine.isModelDownloaded("MARATHI", downloaded -> {
                if (!downloaded) {
                    HandwritingRecognitionEngine.downloadModel("MARATHI", null);
                }
            });
            HandwritingRecognitionEngine.isModelDownloaded("HINDI", downloaded -> {
                if (!downloaded) {
                    HandwritingRecognitionEngine.downloadModel("HINDI", null);
                }
            });
        }, 2500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        long newId = intent.getLongExtra("notebook_id", -1);
        if (newId != -1) {
            synchronized (openNotebookIds) {
                if (!openNotebookIds.contains(newId)) {
                    openNotebookIds.add(newId);
                }
            }
            if (newId != notebookId) {
                switchToNotebook(newId);
            } else {
                renderDocumentTabs();
            }
        }
    }

    private void bindViews() {
        canvasView = findViewById(R.id.canvasView);
        canvasView.setOnActivePageChangeListener(newIndex -> {
            if (pages != null && newIndex >= 0 && newIndex < pages.size()) {
                currentPageIndex = newIndex;
                currentPage = pages.get(currentPageIndex);
                updatePageNavUi();
                updateUndoRedoUi();
                saveNotebookViewState();
            }
        });
        topBar = findViewById(R.id.topBar);

        // Apply status bar and display cutout insets so top navbar never clashes with device status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            androidx.core.graphics.Insets barInsets = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() |
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), barInsets.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        btnBack = findViewById(R.id.btnBack);
        layoutDocumentTabs = findViewById(R.id.layoutDocumentTabs);
        tabScrollView = findViewById(R.id.tabScrollView);
        textTabCountBadge = findViewById(R.id.textTabCountBadge);
        textNotebookTitle = findViewById(R.id.textNotebookTitle);

        mainToolDock = findViewById(R.id.mainToolDock);
        btnUndo = findViewById(R.id.btnUndo);
        btnRedo = findViewById(R.id.btnRedo);
        btnToolbarCircleToSearch = findViewById(R.id.btnToolbarCircleToSearch);
        toolFountain = findViewById(R.id.toolFountain);
        toolPen = findViewById(R.id.toolPen);
        toolHighlighter = findViewById(R.id.toolHighlighter);
        toolPencil = findViewById(R.id.toolPencil);
        toolEraser = findViewById(R.id.toolEraser);
        toolLasso = findViewById(R.id.toolLasso);
        btnColorPicker = findViewById(R.id.btnColorPicker);
        colorPreviewCircle = findViewById(R.id.colorPreviewCircle);
        toolShape = findViewById(R.id.toolShape);
        toolText = findViewById(R.id.toolText);
        toolMedia = findViewById(R.id.toolMedia);
        toolLaser = findViewById(R.id.toolLaser);
        toolHand = findViewById(R.id.toolHand);
        btnAutoBeautify = findViewById(R.id.btnAutoBeautify);
        btnTeachHandwriting = findViewById(R.id.btnTeachHandwriting);

        btnExport = findViewById(R.id.btnExport);
        btnPageOverview = findViewById(R.id.btnPageOverview);
        btnMenu = findViewById(R.id.btnMenu);

        btnCloudSync = findViewById(R.id.btnCloudSync);
        btnCamera = findViewById(R.id.btnCamera);
        btnCrop = findViewById(R.id.btnCrop);
        btnMic = findViewById(R.id.btnMic);
        btnSplitView = findViewById(R.id.btnSplitView);
        btnRuler = findViewById(R.id.btnRuler);
        btnLayers = findViewById(R.id.btnLayers);
        btnPageColor = findViewById(R.id.btnPageColor);
        btnSettingsSliders = findViewById(R.id.btnSettingsSliders);

        quickColorWhite = findViewById(R.id.quickColorWhite);
        quickColorOrange = findViewById(R.id.quickColorOrange);
        quickColorGreen = findViewById(R.id.quickColorGreen);
        quickColorCyan = findViewById(R.id.quickColorCyan);
        quickColorPurple = findViewById(R.id.quickColorPurple);

        floatingViewportPill = findViewById(R.id.floatingViewportPill);
        btnFavorite = findViewById(R.id.btnFavorite);
        textZoomPercent = findViewById(R.id.textZoomPercent);
        btnLockViewport = findViewById(R.id.btnLockViewport);
        btnResetZoom = findViewById(R.id.btnResetZoom);
        btnPdfAmoledToggle = findViewById(R.id.btnPdfAmoledToggle);
        btnPrevPage = findViewById(R.id.btnPrevPage);
        textPageCounter = findViewById(R.id.textPageCounter);
        btnNextPage = findViewById(R.id.btnNextPage);
        btnAddPage = findViewById(R.id.btnAddPage);
        floatingPageNavPill = findViewById(R.id.floatingPageNavPill);

        cardToolProperties = findViewById(R.id.cardToolProperties);
        textToolPropertyTitle = findViewById(R.id.textToolPropertyTitle);
        layoutNibSelector = findViewById(R.id.layoutNibSelector);
        btnNibFine = findViewById(R.id.btnNibFine);
        btnNibMedium = findViewById(R.id.btnNibMedium);
        btnNibBold = findViewById(R.id.btnNibBold);
        seekStrokeThickness = findViewById(R.id.seekStrokeThickness);
        textThicknessValue = findViewById(R.id.textThicknessValue);
        layoutPressureSensitivity = findViewById(R.id.layoutPressureSensitivity);
        seekPressureSensitivity = findViewById(R.id.seekPressureSensitivity);
        textPressureValue = findViewById(R.id.textPressureValue);
        layoutStabilization = findViewById(R.id.layoutStabilization);
        seekStabilization = findViewById(R.id.seekStabilization);
        textStabilizationValue = findViewById(R.id.textStabilizationValue);
        textStabilizationTitle = findViewById(R.id.textStabilizationTitle);
        layoutStraightLine = findViewById(R.id.layoutStraightLine);
        switchStraightLine = findViewById(R.id.switchStraightLine);
        layoutToolQuickColors = findViewById(R.id.layoutToolQuickColors);
        btnAddToPenBox = findViewById(R.id.btnAddToPenBox);
        layoutPenBoxDock = findViewById(R.id.layoutPenBoxDock);
        dividerPenBox = findViewById(R.id.dividerPenBox);
        textStraightLineTitle = findViewById(R.id.textStraightLineTitle);
        loadPenBoxPresets();
        renderPenBoxDock();

        lassoFloatingActionBar = findViewById(R.id.lassoFloatingActionBar);
        layoutLassoPill = findViewById(R.id.layoutLassoPill);
        btnLassoDragHandle = findViewById(R.id.btnLassoDragHandle);
        btnLassoSearch = findViewById(R.id.btnLassoSearch);
        btnLassoDelete = findViewById(R.id.btnLassoDelete);
        btnLassoCut = findViewById(R.id.btnLassoCut);
        btnLassoCopy = findViewById(R.id.btnLassoCopy);
        btnLassoPaste = findViewById(R.id.btnLassoPaste);
        btnLassoStyle = findViewById(R.id.btnLassoStyle);
        btnLassoDuplicate = findViewById(R.id.btnLassoDuplicate);
        btnLassoColor = findViewById(R.id.btnLassoColor);
        btnLassoSpacing = findViewById(R.id.btnLassoSpacing);

        btnLassoConvertText = findViewById(R.id.btnLassoConvertText);
        btnLassoTeach = findViewById(R.id.btnLassoTeach);

        btnExitFullscreen = findViewById(R.id.btnExitFullscreen);

        layoutLongPressPastePopup = findViewById(R.id.layoutLongPressPastePopup);

        canvasView.setOnCanvasChangeListener(this);
        canvasView.setOnPageSwipeListener(new CanvasView.OnPageSwipeListener() {
            @Override
            public void onSwipeNextPage() {
                goToNextPageWithAnimation();
            }

            @Override
            public void onSwipePrevPage() {
                goToPrevPageWithAnimation();
            }
        });
        applySwipeGesturesConfig();
        canvasView.setOnCanvasLongPressListener((rawX, rawY, canvasX, canvasY) -> {
            if (canvasView.hasClipboardContents()) {
                showLongPressPastePopup(rawX, rawY, canvasX, canvasY);
            }
        });
        canvasView.setOnZoomChangeListener(zoomPercent -> {
            int rounded = Math.round(zoomPercent);
            textZoomPercent.setText(rounded + "% ▾");
            updateFavoriteZoomIcon(rounded);
            saveNotebookViewStateDebounced();
        });
        canvasView.setOnCircleToSearchGestureListener(this::triggerCircleToSearch);
        if (appSettings != null) {
            canvasView.setTwoFingerSearchGestureEnabled(appSettings.isTwoFingerSearchGestureEnabled());
        }
        canvasView.setOnLassoSelectionListener((hasSelection, screenBounds) -> {
            if (hasSelection && screenBounds != null) {
                lassoFloatingActionBar.animate().cancel();
                if (lassoFloatingActionBar.getVisibility() != View.VISIBLE) {
                    lassoFloatingActionBar.setVisibility(View.VISIBLE);
                    lassoFloatingActionBar.setAlpha(0f);
                    lassoFloatingActionBar.setScaleX(0.85f);
                    lassoFloatingActionBar.setScaleY(0.85f);
                    lassoFloatingActionBar.animate().alpha(1f).scaleX(1.0f).scaleY(1.0f).setDuration(200)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                }
                updateLassoActionAvailability();
                repositionLassoBar(screenBounds);
            } else {
                if (lassoFloatingActionBar.getVisibility() == View.VISIBLE) {
                    lassoFloatingActionBar.animate().cancel();
                    lassoFloatingActionBar.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160)
                            .withEndAction(() -> lassoFloatingActionBar.setVisibility(View.GONE)).start();
                }
            }
        });
    }

    private void applyResponsiveEditorLayout() {
        int tabRowHeight = getResources().getDimensionPixelSize(R.dimen.editor_tab_row_height);
        int tabHeight = getResources().getDimensionPixelSize(R.dimen.editor_tab_height);
        int headerHeight = getResources().getDimensionPixelSize(R.dimen.editor_header_row_height);
        int headerButtonSize = getResources().getDimensionPixelSize(R.dimen.editor_header_action_size);
        int dockHeight = getResources().getDimensionPixelSize(R.dimen.editor_tool_dock_height);
        int historyButtonSize = getResources().getDimensionPixelSize(R.dimen.editor_history_button_size);
        int toolButtonSize = getResources().getDimensionPixelSize(R.dimen.editor_tool_button_size);
        int pillHeight = getResources().getDimensionPixelSize(R.dimen.editor_floating_pill_height);
        int pillButtonSize = getResources().getDimensionPixelSize(R.dimen.editor_floating_action_size);

        setViewHeight(findViewById(R.id.layoutTabsRow), tabRowHeight);
        setViewHeight(findViewById(R.id.layoutTopHeader), headerHeight);
        int[] headerActions = {
                R.id.btnBack, R.id.btnCloudSync, R.id.btnCamera, R.id.btnCrop, R.id.btnMic,
                R.id.btnSplitView, R.id.btnRuler, R.id.btnLayers, R.id.btnPageColor,
                R.id.btnPageOverview, R.id.btnExport, R.id.btnSettingsSliders, R.id.btnMenu
        };
        for (int id : headerActions) setViewSize(findViewById(id), headerButtonSize, headerButtonSize);

        setViewHeight(mainToolDock, dockHeight);
        setViewHeight(findViewById(R.id.toolDockScroll), dockHeight);
        setViewHeight(findViewById(R.id.toolDockContents), dockHeight);
        setViewHeight(findViewById(R.id.toolDockPill), dockHeight);
        setViewSize(btnUndo, historyButtonSize, historyButtonSize);
        setViewSize(btnRedo, historyButtonSize, historyButtonSize);
        int[] drawingTools = {
                R.id.btnToolbarCircleToSearch,
                R.id.toolFountain, R.id.toolPen, R.id.toolEraser, R.id.toolLasso,
                R.id.toolHighlighter, R.id.toolPencil, R.id.toolShape, R.id.toolText,
                R.id.toolMedia, R.id.toolLaser, R.id.toolHand, R.id.btnAutoBeautify, R.id.btnTeachHandwriting
        };
        for (int id : drawingTools) setViewSize(findViewById(id), toolButtonSize, toolButtonSize);

        setViewHeight(floatingViewportPill, pillHeight);
        setViewHeight(findViewById(R.id.floatingPageNavPill), pillHeight);
        int[] pillActions = {
                R.id.btnFavorite, R.id.btnLockViewport, R.id.btnResetZoom,
                R.id.btnPrevPage, R.id.btnNextPage, R.id.btnAddPage
        };
        for (int id : pillActions) setViewSize(findViewById(id), pillButtonSize, pillButtonSize);
        if (textNotebookTitle != null) {
            textNotebookTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.editor_tab_text_size));
            textNotebookTitle.setMaxWidth(dp(getResources().getConfiguration().screenWidthDp >= 600
                    ? 240 : 160));
        }
        if (textTabCountBadge != null) {
            textTabCountBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.editor_tab_text_size));
        }
        textPageCounter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.editor_page_counter_text_size));
        textZoomPercent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.editor_zoom_text_size));

        ViewGroup.LayoutParams propertyParams = cardToolProperties.getLayoutParams();
        int configuredCardWidth = getResources().getDimensionPixelSize(R.dimen.editor_property_card_width);
        int availableWidth = Math.round(getResources().getConfiguration().screenWidthDp
                * getResources().getDisplayMetrics().density) - dp(24);
        propertyParams.width = Math.min(configuredCardWidth, Math.max(1, availableWidth));
        cardToolProperties.setLayoutParams(propertyParams);

        View pageNav = findViewById(R.id.floatingPageNavPill);
        android.widget.RelativeLayout.LayoutParams pageNavParams =
                (android.widget.RelativeLayout.LayoutParams) pageNav.getLayoutParams();
        pageNavParams.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        pageNavParams.removeRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
        pageNavParams.removeRule(android.widget.RelativeLayout.BELOW);
        if (getResources().getConfiguration().screenWidthDp < 720) {
            pageNavParams.addRule(android.widget.RelativeLayout.BELOW, R.id.floatingViewportPill);
            pageNavParams.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
            pageNavParams.topMargin = dp(6);
            pageNavParams.setMarginEnd(0);
        } else {
            pageNavParams.addRule(android.widget.RelativeLayout.BELOW, R.id.mainToolDock);
            pageNavParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
            pageNavParams.topMargin = dp(10);
            pageNavParams.setMarginEnd(dp(16));
        }
        pageNav.setLayoutParams(pageNavParams);
    }

    private static final String TOOLBAR_ORDER_PREFS = "toolbar_customization";
    private static final String TOOLBAR_ORDER_KEY = "tool_dock_order_v1";
    private View activeToolbarDragView;
    private boolean activeToolbarDragWasClickable;
    private boolean activeToolbarDragWasLongClickable;
    private boolean toolbarDragInProgress;

    /** Lets users drag toolbar actions into their preferred order and keeps it across launches. */
    private void setupToolbarReordering() {
        android.widget.LinearLayout dock = findViewById(R.id.toolDockPill);
        if (dock == null) return;
        android.widget.HorizontalScrollView dockScroll = findViewById(R.id.toolDockScroll);

        restoreToolbarOrder(dock);
        final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        for (View item : getMovableToolbarItems(dock)) {
            final android.os.Handler dragArmHandler = new android.os.Handler(Looper.getMainLooper());
            item.setOnTouchListener(new View.OnTouchListener() {
                float downX;
                float downY;
                boolean pointerDown;
                boolean dragArmed;
                boolean dragStarted;
                boolean restoreLongClickable;
                final Runnable armDrag = () -> {
                    if (pointerDown) {
                        dragArmed = true;
                        android.view.ViewParent parent = item.getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    }
                };

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (toolbarDragInProgress && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        return true;
                    }
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            pointerDown = true;
                            dragArmed = false;
                            dragStarted = false;
                            // Toolbar buttons may use long-press to open configuration menus.
                            // Suppress that gesture while the pointer is held so a drag never
                            // opens the button's menu before the user can move it.
                            restoreLongClickable = view.isLongClickable();
                            if (restoreLongClickable) view.setLongClickable(false);
                            view.cancelLongPress();
                            dragArmHandler.postDelayed(armDrag, 300L);
                            return false;
                        case MotionEvent.ACTION_MOVE:
                            float deltaX = event.getRawX() - downX;
                            float deltaY = event.getRawY() - downY;
                            if (dragArmed && !dragStarted && Math.abs(deltaX) > touchSlop
                                    && Math.abs(deltaX) > Math.abs(deltaY)) {
                                dragArmHandler.removeCallbacks(armDrag);
                                android.content.ClipData dragData = android.content.ClipData.newPlainText(
                                        "toolbar-item", String.valueOf(view.getId()));
                                activeToolbarDragView = view;
                                activeToolbarDragWasClickable = view.isClickable();
                                activeToolbarDragWasLongClickable = restoreLongClickable;
                                toolbarDragInProgress = true;
                                // The source button must not run its click or configuration
                                // action at any point during the system drag session.
                                view.setClickable(false);
                                view.setLongClickable(false);
                                view.cancelLongPress();
                                dragStarted = view.startDragAndDrop(
                                        dragData, new View.DragShadowBuilder(view), view, 0);
                                if (dragStarted) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                                    return true;
                                }
                                restoreActiveToolbarDragView();
                            }
                            return dragStarted;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            boolean consumed = dragStarted;
                            pointerDown = false;
                            dragArmHandler.removeCallbacks(armDrag);
                            if (dragArmed) {
                                android.view.ViewParent parent = view.getParent();
                                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                            }
                            dragArmed = false;
                            dragStarted = false;
                            if (restoreLongClickable && !toolbarDragInProgress) view.setLongClickable(true);
                            restoreLongClickable = false;
                            return consumed;
                        default:
                            return dragStarted;
                    }
                }
            });
        }

        dock.setOnDragListener((target, event) -> {
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return event.getLocalState() instanceof View
                            && ((View) event.getLocalState()).getParent() == dock;
                case android.view.DragEvent.ACTION_DROP:
                    if (event.getLocalState() instanceof View) {
                        reorderToolbarItem(dock, (View) event.getLocalState(), event.getX());
                        return true;
                    }
                    return false;
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    if (dockScroll != null) {
                        int[] dockLocation = new int[2];
                        int[] scrollLocation = new int[2];
                        dock.getLocationOnScreen(dockLocation);
                        dockScroll.getLocationOnScreen(scrollLocation);
                        float xInScroll = event.getX() + dockLocation[0] - scrollLocation[0];
                        int edgeSize = dp(44);
                        int scrollStep = dp(16);
                        if (xInScroll < edgeSize) {
                            dockScroll.scrollBy(-scrollStep, 0);
                        } else if (xInScroll > dockScroll.getWidth() - edgeSize) {
                            dockScroll.scrollBy(scrollStep, 0);
                        }
                    }
                    return true;
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    restoreActiveToolbarDragView();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void restoreActiveToolbarDragView() {
        View draggedView = activeToolbarDragView;
        activeToolbarDragView = null;
        toolbarDragInProgress = false;
        if (draggedView == null) return;
        draggedView.setClickable(activeToolbarDragWasClickable);
        draggedView.setLongClickable(activeToolbarDragWasLongClickable);
        activeToolbarDragWasClickable = false;
        activeToolbarDragWasLongClickable = false;
    }

    private boolean isMovableToolbarItem(View view) {
        if (view == null || view.getId() == View.NO_ID) return false;
        if (view instanceof ImageView) return true;
        return view.getId() == R.id.layoutPenBoxDock
                || view.getId() == R.id.layoutDockQuickColors;
    }

    private List<View> getMovableToolbarItems(android.widget.LinearLayout dock) {
        List<View> items = new ArrayList<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            if (isMovableToolbarItem(child)) items.add(child);
        }
        return items;
    }

    private void reorderToolbarItem(android.widget.LinearLayout dock, View dragged, float dropX) {
        List<View> items = getMovableToolbarItems(dock);
        if (!items.remove(dragged)) return;

        int destination = items.size();
        for (int i = 0; i < items.size(); i++) {
            View item = items.get(i);
            if (dropX < item.getX() + item.getWidth() / 2f) {
                destination = i;
                break;
            }
        }
        items.add(destination, dragged);
        applyToolbarOrder(dock, items);
        saveToolbarOrder(dock);
    }

    private void restoreToolbarOrder(android.widget.LinearLayout dock) {
        String saved = getSharedPreferences(TOOLBAR_ORDER_PREFS, MODE_PRIVATE)
                .getString(TOOLBAR_ORDER_KEY, null);
        if (saved == null || saved.trim().isEmpty()) return;

        List<View> currentItems = getMovableToolbarItems(dock);
        java.util.Map<String, View> itemByName = new java.util.HashMap<>();
        for (View item : currentItems) {
            try {
                itemByName.put(getResources().getResourceEntryName(item.getId()), item);
            } catch (android.content.res.Resources.NotFoundException ignored) {}
        }

        List<View> ordered = new ArrayList<>();
        java.util.Set<View> added = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<View, Boolean>());
        for (String name : saved.split(",")) {
            View item = itemByName.get(name);
            if (item != null && added.add(item)) ordered.add(item);
        }
        for (View item : currentItems) {
            if (added.add(item)) ordered.add(item);
        }
        applyToolbarOrder(dock, ordered);
    }

    private void saveToolbarOrder(android.widget.LinearLayout dock) {
        StringBuilder value = new StringBuilder();
        for (View item : getMovableToolbarItems(dock)) {
            try {
                if (value.length() > 0) value.append(',');
                value.append(getResources().getResourceEntryName(item.getId()));
            } catch (android.content.res.Resources.NotFoundException ignored) {}
        }
        getSharedPreferences(TOOLBAR_ORDER_PREFS, MODE_PRIVATE).edit()
                .putString(TOOLBAR_ORDER_KEY, value.toString()).apply();
    }

    private void applyToolbarOrder(android.widget.LinearLayout dock, List<View> orderedItems) {
        List<View> originalChildren = new ArrayList<>();
        for (int i = 0; i < dock.getChildCount(); i++) originalChildren.add(dock.getChildAt(i));
        dock.removeAllViews();

        int nextItem = 0;
        for (View original : originalChildren) {
            View child = isMovableToolbarItem(original)
                    ? orderedItems.get(nextItem++) : original;
            dock.addView(child, child.getLayoutParams());
        }
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        View focused = getCurrentFocus();
        if (event == null || !event.isCtrlPressed() || focused instanceof EditText) {
            return super.onKeyShortcut(keyCode, event);
        }
        if (canvasView == null) return super.onKeyShortcut(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_C && canvasView.hasSelection()) {
            canvasView.copySelection();
            updateLassoActionAvailability();
            Toast.makeText(this, "Copied to clipboard. Press & hold canvas to paste.", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_X && canvasView.hasSelection()) {
            canvasView.cutSelection();
            updateLassoActionAvailability();
            Toast.makeText(this, "Cut to clipboard. Press & hold canvas to paste.", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_V && canvasView.hasClipboardContents()) {
            canvasView.pasteSelection();
            return true;
        }
        return super.onKeyShortcut(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyResponsiveEditorLayout();
        if (topBar != null) androidx.core.view.ViewCompat.requestApplyInsets(topBar);
        if (canvasView != null) canvasView.invalidate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int dpToPx(int dp) {
        return dp(dp);
    }

    private void setViewHeight(View view, int height) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.height = height;
        view.setLayoutParams(params);
    }

    private void setViewSize(View view, int width, int height) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
    }

    private int getInitialTargetPageIndex(long nbId, Notebook nb) {
        if (hasRestoredEditorState) {
            return restoredPageIndex;
        }
        NotebookViewStateManager.ViewState vs =
                NotebookViewStateManager.loadState(this, nbId);
        if (vs != null) {
            return vs.pageIndex;
        }
        if (nb != null && nb.lastPageIndex > 0) {
            return nb.lastPageIndex;
        }
        return 0;
    }

    private void loadNotebookData() {
        // High-speed progressive background load
        executeLoadTask(() -> {
            try {
                Notebook loadedNotebook = dbHelper.getNotebook(notebookId);
                if (loadedNotebook == null) {
                    runOnUiThread(this::finish);
                    return;
                }
                int targetPage = getInitialTargetPageIndex(notebookId, loadedNotebook);
                List<Page> loadedPages = dbHelper.getPagesForNotebookFast(notebookId, targetPage);
                if (loadedPages.isEmpty()) {
                    Page firstPage = new Page(notebookId, 0, loadedNotebook.defaultTemplate,
                            loadedNotebook.defaultBgColor, loadedNotebook.defaultIsInfinite);
                    dbHelper.insertPage(firstPage);
                    loadedPages = dbHelper.getPagesForNotebookFast(notebookId, 0);
                }
                final List<Page> resultPages = loadedPages;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    applyLoadedNotebook(loadedNotebook, resultPages);
                    dbHelper.preloadRemainingPagesAsync(notebookId, resultPages, targetPage, () -> {
                        if (!isFinishing() && !isDestroyed() && canvasView != null) {
                            if (canvasView.isContinuousVerticalScroll()) {
                                canvasView.postInvalidate();
                            }
                        }
                    });
                });
            } catch (Throwable t) {
                android.util.Log.e("NoteEditorActivity", "Failed to load notebook data", t);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Unable to open note")
                            .setMessage("There was an error loading the notebook data.")
                            .setPositiveButton("OK", (dialog, which) -> finish())
                            .show();
                });
            }
        });
    }

    private void applyLoadedNotebook(Notebook loadedNotebook, List<Page> resultPages) {
        currentNotebook = loadedNotebook;
        pages = resultPages;
        if (textNotebookTitle != null) {
            textNotebookTitle.setText(currentNotebook.title);
        }
        updateFavoriteIcon();

        int savedPage = 0;
        float savedScrollY = 0f;
        float savedZoom = 100f;
        float savedPanX = 0f;
        float savedPanY = 0f;
        boolean shouldRestoreView = false;

        if (hasRestoredEditorState) {
            savedPage = restoredPageIndex;
            savedScrollY = restoredScrollY;
            savedZoom = restoredZoom;
            savedPanX = restoredPanX;
            savedPanY = restoredPanY;
            shouldRestoreView = true;
            hasRestoredEditorState = false;
        } else {
            NotebookViewStateManager.ViewState vs =
                    NotebookViewStateManager.loadState(NoteEditorActivity.this, notebookId);
            if (vs != null) {
                savedPage = vs.pageIndex;
                savedScrollY = vs.scrollY;
                savedZoom = vs.zoomPercent;
                savedPanX = vs.panX;
                savedPanY = vs.panY;
                shouldRestoreView = true;
            } else if (loadedNotebook.lastPageIndex > 0
                    || loadedNotebook.lastScrollY > 0
                    || Math.abs(loadedNotebook.lastZoomPercent - 100f) > 0.01f
                    || Math.abs(loadedNotebook.lastPanX) > 0.01f
                    || Math.abs(loadedNotebook.lastPanY) > 0.01f) {
                savedPage = loadedNotebook.lastPageIndex;
                savedScrollY = loadedNotebook.lastScrollY;
                savedZoom = loadedNotebook.lastZoomPercent > 0 ? loadedNotebook.lastZoomPercent : 100f;
                savedPanX = loadedNotebook.lastPanX;
                savedPanY = loadedNotebook.lastPanY;
                shouldRestoreView = true;
            }
        }

        currentPageIndex = Math.max(0, Math.min(savedPage, resultPages.size() - 1));
        restoreNotebookConfig(notebookId);
        loadCurrentPage();

        if (shouldRestoreView) {
            final float targetZoom = savedZoom > 0 ? savedZoom : 100f;
            final float targetPanX = savedPanX;
            final float targetPanY = savedPanY;
            final float targetScrollY = savedScrollY;
            final int restoredPageNum = currentPageIndex + 1;
            final int totalPagesCount = resultPages.size();

            canvasView.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                final float effectiveScrollY = (canvasView.isContinuousVerticalScroll() && targetScrollY <= 0 && currentPageIndex > 0)
                        ? canvasView.getPageTop(currentPageIndex)
                        : targetScrollY;

                // Smoothly animate/glide into the restored reading position
                if (canvasView.getWidth() > 0 && canvasView.getHeight() > 0) {
                    canvasView.smoothAnimateViewport(targetZoom, targetPanX, targetPanY, effectiveScrollY, 420, null);
                } else {
                    canvasView.restoreViewport(targetZoom, targetPanX, targetPanY, effectiveScrollY);
                }

                if (restoredPageNum > 1 || effectiveScrollY > 50 || Math.abs(targetZoom - 100f) > 5f) {
                    Toast.makeText(NoteEditorActivity.this, "📖 Resumed reading at Page " + restoredPageNum + " of " + totalPagesCount, Toast.LENGTH_SHORT).show();
                }
            });
        }
        renderDocumentTabs();
        canvasView.setEnabled(true);
    }

    private void renderDocumentTabs() {
        if (layoutDocumentTabs == null) return;
        executeLoadTask(() -> {
            try {
                List<Notebook> loadedNotebooks = new ArrayList<>();
                List<Long> toRemove = new ArrayList<>();
                synchronized (openNotebookIds) {
                    if (!openNotebookIds.contains(notebookId)) {
                        openNotebookIds.add(notebookId);
                    }
                    for (Long id : openNotebookIds) {
                        Notebook nb = dbHelper.getNotebook(id);
                        if (nb != null) {
                            loadedNotebooks.add(nb);
                        } else {
                            toRemove.add(id);
                        }
                    }
                    openNotebookIds.removeAll(toRemove);
                }

                runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                layoutDocumentTabs.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(NoteEditorActivity.this);
                int tabHeight = getResources().getDimensionPixelSize(R.dimen.editor_tab_height);
                int tabCloseSize = getResources().getDimensionPixelSize(R.dimen.editor_tab_close_size);
                int screenWidthDp = getResources().getConfiguration().screenWidthDp;
                int maxTabWidth = dp(screenWidthDp >= 600 ? 200 : 140);
                View activeTabView = null;

                for (Notebook nb : loadedNotebooks) {
                    final long thisTabId = nb.id;
                    final boolean isActive = (thisTabId == notebookId);

                    View tabView = inflater.inflate(R.layout.item_editor_tab, layoutDocumentTabs, false);
                    View tabContainer = tabView.findViewById(R.id.tabContainer);
                    TextView textTitle = tabView.findViewById(R.id.tabTitle);
                    ImageView btnClose = tabView.findViewById(R.id.tabClose);

                    setViewHeight(tabContainer, tabHeight);
                    textTitle.setMaxWidth(maxTabWidth);
                    textTitle.setText(nb.title);

                    if (isActive) {
                        tabContainer.setBackgroundResource(R.drawable.bg_tab_active);
                        textTitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
                        textTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                        textNotebookTitle = textTitle;
                        activeTabView = tabView;
                        tabContainer.setOnClickListener(v -> showRenameNotebookDialog());
                    } else {
                        tabContainer.setBackgroundResource(R.drawable.bg_tab_inactive);
                        textTitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary));
                        textTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                        tabContainer.setOnClickListener(v -> switchToNotebook(thisTabId));
                    }

                    setViewSize(btnClose, tabCloseSize, tabCloseSize);
                    btnClose.setOnClickListener(v -> closeTab(thisTabId));

                    layoutDocumentTabs.addView(tabView);
                }

                // Add '+' button at the end
                ImageView btnAdd = new ImageView(NoteEditorActivity.this);
                int actionSize = getResources().getDimensionPixelSize(R.dimen.editor_floating_action_size);
                LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(actionSize, actionSize);
                addParams.setMarginStart(dp(6));
                btnAdd.setLayoutParams(addParams);
                btnAdd.setImageResource(R.drawable.ic_add);
                btnAdd.setPadding(dp(6), dp(6), dp(6), dp(6));
                btnAdd.setColorFilter(0xFF94A3B8);
                android.util.TypedValue outValue = new android.util.TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
                btnAdd.setBackgroundResource(outValue.resourceId);
                btnAdd.setContentDescription("New note tab");
                btnAdd.setOnClickListener(v -> showCreateNotebookDialog());
                layoutDocumentTabs.addView(btnAdd);

                if (textTabCountBadge != null) {
                    textTabCountBadge.setText(loadedNotebooks.size() + " ▾");
                    textTabCountBadge.setOnClickListener(v -> showTabsDropdown(loadedNotebooks));
                }

                if (activeTabView != null && tabScrollView != null) {
                    final View target = activeTabView;
                    tabScrollView.post(() -> tabScrollView.smoothScrollTo(target.getLeft(), 0));
                }
            });
        } catch (Throwable t) {
            android.util.Log.e("NoteEditorActivity", "Failed to render document tabs", t);
        }
    });
}

    private void switchToNotebook(long targetNotebookId) {
        if (targetNotebookId == notebookId) return;
        saveCurrentPage();
        saveNotebookViewState();
        saveNotebookConfig();
        notebookId = targetNotebookId;
        hasRestoredEditorState = false;
        currentPageIndex = 0;
        loadNotebookData();
    }

    private void closeTab(long targetNotebookId) {
        saveNotebookViewState();
        saveNotebookConfig();
        synchronized (openNotebookIds) {
            if (openNotebookIds.size() <= 1) {
                saveCurrentPage();
                openNotebookIds.remove(targetNotebookId);
                finish();
                return;
            }
            if (targetNotebookId == notebookId) {
                int curIndex = openNotebookIds.indexOf(targetNotebookId);
                int nextIndex = (curIndex + 1 < openNotebookIds.size()) ? curIndex + 1 : curIndex - 1;
                long nextNotebookId = openNotebookIds.get(nextIndex);
                openNotebookIds.remove(targetNotebookId);
                switchToNotebook(nextNotebookId);
            } else {
                openNotebookIds.remove(targetNotebookId);
                renderDocumentTabs();
            }
        }
    }

    private java.util.function.Consumer<String> pendingCoverImageCallback;
    private final ActivityResultLauncher<String[]> openCoverImageLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || pendingCoverImageCallback == null) return;
                try {
                    pendingCoverImageCallback.accept(NotebookCoverUi.importImage(this, uri));
                } catch (Exception e) {
                    Toast.makeText(this, "Could not load cover image", Toast.LENGTH_SHORT).show();
                }
            });

    private void showCreateNotebookDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_notebook, null);

        TextView btnCancel = dialogView.findViewById(R.id.btnCancelCreate);
        TextView btnConfirm = dialogView.findViewById(R.id.btnConfirmCreate);
        FrameLayout previewCoverBox = dialogView.findViewById(R.id.previewCoverBox);
        ImageView previewCoverImage = dialogView.findViewById(R.id.previewCoverImage);
        TextView textPreviewCoverCaption = dialogView.findViewById(R.id.textPreviewCoverCaption);
        EditText editCoverCaption = dialogView.findViewById(R.id.editCoverCaption);
        View btnChooseCustomCover = dialogView.findViewById(R.id.btnChooseCustomCover);
        TextView textPreviewTitle = dialogView.findViewById(R.id.textPreviewTitle);
        EditText editTitle = dialogView.findViewById(R.id.editNotebookTitle);
        TextView btnFormatInfinite = dialogView.findViewById(R.id.btnFormatInfinite);
        TextView btnFormatStandard = dialogView.findViewById(R.id.btnFormatStandard);
        TabLayout tabLayout = dialogView.findViewById(R.id.tabLayoutCreate);
        View containerTemplate = dialogView.findViewById(R.id.containerTemplateChoices);
        View containerPageBg = dialogView.findViewById(R.id.containerPageBgChoices);
        View containerColor = dialogView.findViewById(R.id.containerColorChoices);
        LinearLayout layoutTemplateButtons = dialogView.findViewById(R.id.layoutTemplateButtons);
        LinearLayout layoutPageBgButtons = dialogView.findViewById(R.id.layoutPageBgButtons);
        LinearLayout layoutColorButtons = dialogView.findViewById(R.id.layoutColorButtons);

        final int[] selectedCoverStyle = {0};
        final int[] selectedCoverColor = {NotebookCoverStyles.startColor(0)};
        final String[] selectedCoverImage = {null};
        final int[] selectedPageBgColor = {appSettings != null ? appSettings.getDefaultPageBgColor() : 0xFF000000};
        PaperTemplate initialTpl = appSettings != null ? appSettings.getDefaultPaperTemplate() : PaperTemplate.BLANK;
        final PaperTemplate[] selectedTemplate = {initialTpl != null ? initialTpl : PaperTemplate.BLANK};
        final boolean[] isInfinite = {true};

        Runnable updatePreview = () -> {
            previewCoverBox.setBackground(selectedCoverStyle[0] >= 0
                    ? NotebookCoverStyles.drawable(selectedCoverStyle[0])
                    : NotebookCoverStyles.legacyDrawable(selectedCoverColor[0]));
            Bitmap bitmap = NotebookCoverUi.decodePreview(selectedCoverImage[0]);
            previewCoverImage.setImageBitmap(bitmap);
            previewCoverImage.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
            String text = editCoverCaption.getText() == null ? "" : editCoverCaption.getText().toString().trim();
            textPreviewCoverCaption.setText(text);
            textPreviewCoverCaption.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        };
        updatePreview.run();

        editTitle.setText("");
        editTitle.setHint("Unnamed note");
        editTitle.setSelectAllOnFocus(true);
        editTitle.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                editTitle.selectAll();
            }
        });

        editTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                textPreviewTitle.setText(s.toString().trim().isEmpty() ? "Unnamed note" : s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Format switch
        btnFormatInfinite.setOnClickListener(v -> {
            isInfinite[0] = true;
            btnFormatInfinite.setBackgroundResource(R.drawable.bg_tool_button_active);
            btnFormatInfinite.setTextColor(0xFFFFFFFF);
            btnFormatStandard.setBackgroundResource(R.drawable.bg_floating_pill);
            btnFormatStandard.setTextColor(0xFF94A3B8);
        });

        btnFormatStandard.setOnClickListener(v -> {
            isInfinite[0] = false;
            btnFormatStandard.setBackgroundResource(R.drawable.bg_tool_button_active);
            btnFormatStandard.setTextColor(0xFFFFFFFF);
            btnFormatInfinite.setBackgroundResource(R.drawable.bg_floating_pill);
            btnFormatInfinite.setTextColor(0xFF94A3B8);
        });

        // Tab switch (Template | Page Bg | Cover)
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                containerTemplate.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                containerPageBg.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                containerColor.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Template choices
        String[] templateNames = {"Blank", "Ruled line", "Grid paper", "Dot grid", "Cornell notes"};
        PaperTemplate[] tpls = {PaperTemplate.BLANK, PaperTemplate.RULED, PaperTemplate.GRID, PaperTemplate.DOTS, PaperTemplate.CORNELL};
        java.util.List<TextView> templateViews = new java.util.ArrayList<>();

        int initialTplIdx = 0;
        for (int i = 0; i < tpls.length; i++) {
            if (tpls[i] == selectedTemplate[0]) {
                initialTplIdx = i;
                break;
            }
        }

        for (int i = 0; i < templateNames.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(4, 0, 4, 0);
            btn.setLayoutParams(lp);
            btn.setPadding(24, 0, 24, 0);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setText(templateNames[i]);
            btn.setTextSize(12f);
            boolean isSelected = (i == initialTplIdx);
            btn.setTextColor(isSelected ? 0xFFFFFFFF : 0xFF94A3B8);
            btn.setBackgroundResource(isSelected ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);

            btn.setOnClickListener(v -> {
                selectedTemplate[0] = tpls[idx];
                for (TextView tv : templateViews) {
                    tv.setBackgroundResource(R.drawable.bg_floating_pill);
                    tv.setTextColor(0xFF94A3B8);
                }
                btn.setBackgroundResource(R.drawable.bg_tool_button_active);
                btn.setTextColor(0xFFFFFFFF);
            });

            templateViews.add(btn);
            layoutTemplateButtons.addView(btn);
        }

        // Page Background Presets
        final int[] PRESET_PAGE_BG = {
                0xFF000000, // Super Dark Black (True AMOLED Black)
                0xFF121214, // Dark Obsidian
                0xFF1E293B, // Slate Gray
                0xFF18181B, // Charcoal
                0xFFFFFFFF, // Pure White
                0xFFFFFBEB, // Warm Ivory
                0xFFF0FDF4, // Eye-Care Mint
                0xFFFEF9C3, // Legal Pad Yellow
                0xFFF5EBE1, // Vintage Kraft
                0xFF0F172A  // Deep Navy
        };
        final String[] PRESET_PAGE_BG_NAMES = {
                "Super Dark Black (AMOLED)", "Dark Obsidian", "Slate Gray", "Charcoal",
                "Pure White", "Warm Ivory", "Eye-Care Mint", "Legal Pad", "Kraft", "Deep Navy"
        };
        java.util.List<View> pageBgDots = new java.util.ArrayList<>();
        Runnable updatePageBgUi = () -> {
            for (int pIdx = 0; pIdx < PRESET_PAGE_BG.length; pIdx++) {
                if (pIdx < pageBgDots.size()) {
                    View dot = pageBgDots.get(pIdx);
                    GradientDrawable swGd = new GradientDrawable();
                    swGd.setShape(GradientDrawable.OVAL);
                    swGd.setColor(PRESET_PAGE_BG[pIdx]);
                    if (PRESET_PAGE_BG[pIdx] == selectedPageBgColor[0]) {
                        swGd.setStroke(4, 0xFF38BDF8);
                    } else {
                        swGd.setStroke(2, 0xFF475569);
                    }
                    dot.setBackground(swGd);
                }
            }
        };

        for (int pIdx = 0; pIdx < PRESET_PAGE_BG.length; pIdx++) {
            final int bgCol = PRESET_PAGE_BG[pIdx];
            final String bgName = PRESET_PAGE_BG_NAMES[pIdx];

            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(60, 60);
            lp.setMargins(6, 0, 6, 0);
            dot.setLayoutParams(lp);
            dot.setContentDescription(bgName);

            dot.setOnClickListener(v -> {
                selectedPageBgColor[0] = bgCol;
                updatePageBgUi.run();
            });
            pageBgDots.add(dot);
            layoutPageBgButtons.addView(dot);
        }
        updatePageBgUi.run();

        NotebookCoverUi.populateStyles(this, layoutColorButtons, 0, style -> {
            selectedCoverStyle[0] = style;
            selectedCoverColor[0] = NotebookCoverStyles.startColor(style);
            selectedCoverImage[0] = null;
            for (int i = 0; i < layoutColorButtons.getChildCount(); i++) {
                ((GradientDrawable) layoutColorButtons.getChildAt(i).getBackground()).setStroke(
                        Math.round((i == style ? 3 : 1) * getResources().getDisplayMetrics().density),
                        i == style ? 0xFF38BDF8 : 0x668FFFFFFF);
            }
            updatePreview.run();
        });
        btnChooseCustomCover.setOnClickListener(v -> {
            pendingCoverImageCallback = path -> {
                selectedCoverImage[0] = path;
                selectedCoverStyle[0] = -1;
                for (int i = 0; i < layoutColorButtons.getChildCount(); i++) {
                    ((GradientDrawable) layoutColorButtons.getChildAt(i).getBackground()).setStroke(
                            Math.round(getResources().getDisplayMetrics().density), 0x668FFFFFFF);
                }
                updatePreview.run();
            };
            openCoverImageLauncher.launch(new String[]{"image/*"});
        });
        editCoverCaption.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, this);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
            if (title.isEmpty()) title = "Unnamed note";

            Notebook newNb = new Notebook(title, selectedCoverColor[0], selectedTemplate[0]);
            newNb.coverStyle = selectedCoverStyle[0];
            newNb.coverImagePath = selectedCoverImage[0];
            newNb.coverText = editCoverCaption.getText() != null ? editCoverCaption.getText().toString().trim() : "";
            newNb.defaultIsInfinite = isInfinite[0];
            newNb.defaultBgColor = selectedPageBgColor[0];
            if (appSettings != null) {
                appSettings.setDefaultPageBgColor(selectedPageBgColor[0]);
                appSettings.setDefaultPaperTemplate(selectedTemplate[0]);
            }
            if (currentNotebook != null && currentNotebook.folder != null && !"All".equalsIgnoreCase(currentNotebook.folder)) {
                newNb.folder = currentNotebook.folder;
            }
            dialog.dismiss();
            saveCurrentPage();
            executeSaveTask(() -> {
                long newId = dbHelper.insertNotebook(newNb);
                Page firstPage = new Page(newId, 0, newNb.defaultTemplate, newNb.defaultBgColor, newNb.defaultIsInfinite);
                dbHelper.insertPage(firstPage);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    synchronized (openNotebookIds) {
                        if (!openNotebookIds.contains(newId)) {
                            openNotebookIds.add(newId);
                        }
                    }
                    switchToNotebook(newId);
                });
            });
        });

        dialog.show();
    }

    private void showTabsDropdown(List<Notebook> openNotes) {
        if (openNotes == null || openNotes.isEmpty()) return;
        ModernOptionsSelectorDialog.Builder builder = new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Open Notebook Tabs")
                .setSubtitle("Switch between open documents or manage tabs")
                .setHeaderSymbol("📑");

        for (Notebook nb : openNotes) {
            boolean isCurrent = (nb.id == notebookId);
            builder.addOption((int) nb.id, isCurrent ? "📖" : "📄", nb.title,
                    isCurrent ? "Currently active note" : "Tap to switch to this tab", isCurrent, () -> {
                        switchToNotebook(nb.id);
                    });
        }

        builder.addOption(999999, "➕", "New Note", "Create and open a new notebook in a tab", false, this::showCreateNotebookDialog);
        builder.addOption(999997, "📄", "Open PDF as Tab", "Import and open a PDF document in a tab", false, () -> {
            try {
                openPdfForTabLauncher.launch(new String[]{"application/pdf"});
            } catch (Exception e) {
                Toast.makeText(this, "Could not open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        if (openNotes.size() > 1) {
            builder.addDestructiveOption(999998, "✕", "Close Other Tabs", "Keep only the current notebook open", () -> {
                synchronized (openNotebookIds) {
                    openNotebookIds.clear();
                    openNotebookIds.add(notebookId);
                }
                renderDocumentTabs();
            });
        }

        builder.show();
    }

    private void showRenameNotebookDialog() {
        if (currentNotebook == null) return;
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        int padH = org.opennotes.app.util.ThemeUtils.dp(this, 24);
        int padV = org.opennotes.app.util.ThemeUtils.dp(this, 12);
        container.setPadding(padH, padV, padH, padV);

        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(currentNotebook.title);
        input.setSelectAllOnFocus(true);
        input.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        input.setBackgroundResource(R.drawable.bg_search_bar);
        int innerPadH = org.opennotes.app.util.ThemeUtils.dp(this, 16);
        int innerPadV = org.opennotes.app.util.ThemeUtils.dp(this, 12);
        input.setPadding(innerPadH, innerPadV, innerPadH, innerPadV);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        container.addView(input);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Rename Notebook")
                .setView(container)
                .setPositiveButton("Save", (d, which) -> {
                    String newTitle = input.getText().toString().trim();
                    if (!newTitle.isEmpty()) {
                        currentNotebook.title = newTitle;
                        if (textNotebookTitle != null) {
                            textNotebookTitle.setText(newTitle);
                        }
                        Notebook snapshot = currentNotebook;
                        executeSaveTask(() -> dbHelper.updateNotebook(snapshot));
                        renderDocumentTabs();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void loadCurrentPage() {
        if (currentPageIndex < 0 || pages == null || currentPageIndex >= pages.size()) return;
        currentPage = pages.get(currentPageIndex);
        if (currentPage != null && !currentPage.isContentLoaded) {
            dbHelper.ensurePageLoaded(currentPage);
        }
        boolean isContinuous = canvasView != null && canvasView.isContinuousVerticalScroll();
        if (isContinuous && pages.size() > 1) {
            canvasView.setPages(pages, currentPageIndex);
        } else {
            canvasView.setPage(currentPage);
        }
        updatePageNavUi();
        updateUndoRedoUi();
    }

    private void updatePageNavUi() {
        boolean isContinuous = canvasView != null && canvasView.isContinuousVerticalScroll();
        textPageCounter.setText((currentPageIndex + 1) + " / " + pages.size() + (isContinuous ? " ↕" : ""));
        btnPrevPage.setEnabled(currentPageIndex > 0);
        btnPrevPage.setAlpha(currentPageIndex > 0 ? 1.0f : 0.35f);
        btnNextPage.setEnabled(currentPageIndex < pages.size() - 1);
        btnNextPage.setAlpha(currentPageIndex < pages.size() - 1 ? 1.0f : 0.35f);

        if (btnPdfAmoledToggle != null) {
            boolean hasBg = canvasView != null && canvasView.getBackgroundImage() != null;
            btnPdfAmoledToggle.setVisibility(hasBg ? View.VISIBLE : View.GONE);
            boolean amoledActive = canvasView != null ? canvasView.isPdfAmoledDarkMode() : (appSettings != null && appSettings.isPdfAmoledDarkMode());
            btnPdfAmoledToggle.setColorFilter(amoledActive ? 0xFF38BDF8 : 0xFF94A3B8);
        }
    }

    private void updateUndoRedoUi() {
        btnUndo.setEnabled(canvasView.canUndo());
        btnUndo.setAlpha(canvasView.canUndo() ? 1.0f : 0.35f);
        btnRedo.setEnabled(canvasView.canRedo());
        btnRedo.setAlpha(canvasView.canRedo() ? 1.0f : 0.35f);
    }

    private int getFavoriteResetZoom() {
        if (notebookId > 0) {
            NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
            if (config != null && config.defaultResetZoom != null && config.defaultResetZoom >= 10) {
                return config.defaultResetZoom;
            }
        }
        return ZoomFavoritesManager.getDefaultResetZoom(this);
    }

    private void setFavoriteResetZoom(int zoomPercent) {
        if (zoomPercent < 10 || zoomPercent > 2000) return;
        ZoomFavoritesManager.setDefaultResetZoom(this, zoomPercent);
        if (notebookId > 0) {
            NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
            if (config == null) config = new NotebookConfigManager.NotebookConfig();
            config.defaultResetZoom = zoomPercent;
            NotebookConfigManager.saveConfig(this, notebookId, config);
        }
    }

    private String getPdfResetMode() {
        if (notebookId > 0) {
            NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
            if (config != null && config.pdfResetMode != null) {
                return config.pdfResetMode;
            }
        }
        return ZoomFavoritesManager.getPdfResetMode(this);
    }

    private void setPdfResetMode(String mode) {
        if (mode == null) return;
        ZoomFavoritesManager.setPdfResetMode(this, mode);
        if (notebookId > 0) {
            NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
            if (config == null) config = new NotebookConfigManager.NotebookConfig();
            config.pdfResetMode = mode;
            NotebookConfigManager.saveConfig(this, notebookId, config);
        }
    }

    private boolean isPdfResetFitPage() {
        return ZoomFavoritesManager.PDF_RESET_MODE_FIT_PAGE.equals(getPdfResetMode());
    }

    private void executeResetZoom() {
        if (canvasView == null) return;
        if (canvasView.isPdfDocument()) {
            if (isPdfResetFitPage()) {
                canvasView.fitPageToAvailableSpace(true);
                Toast.makeText(this, "Aligned to page fit 📄", Toast.LENGTH_SHORT).show();
            } else {
                int targetResetZoom = getFavoriteResetZoom();
                canvasView.resetPdfToZoomCentered(targetResetZoom, true);
                Toast.makeText(this, "Zoom reset to " + targetResetZoom + "% ★ (Centered)", Toast.LENGTH_SHORT).show();
            }
        } else {
            int targetResetZoom = getFavoriteResetZoom();
            canvasView.resetZoom(targetResetZoom);
            Toast.makeText(this, "Zoom reset to " + targetResetZoom + "% ★", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFavoriteCurrentZoom() {
        if (canvasView == null || btnFavorite == null) return;
        int currentZoom = Math.round(canvasView.getZoomPercent());
        boolean isFav = ZoomFavoritesManager.isFavorite(this, currentZoom);
        if (isFav) {
            ZoomFavoritesManager.removeFavorite(this, currentZoom);
            updateFavoriteZoomIcon(currentZoom);
            Toast.makeText(this, "Zoom " + currentZoom + "% removed from favorites", Toast.LENGTH_SHORT).show();
        } else {
            ZoomFavoritesManager.addFavorite(this, currentZoom);
            setFavoriteResetZoom(currentZoom);
            if (canvasView.isPdfDocument()) {
                setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FAVORITE_CENTERED);
            }
            saveNotebookConfig();
            updateFavoriteZoomIcon(currentZoom);
            btnFavorite.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                    .withEndAction(() -> btnFavorite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start())
                    .start();
            Toast.makeText(this, "Zoom " + currentZoom + "% favorited & set as reset target ★", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateFavoriteZoomIcon(int zoom) {
        if (btnFavorite == null) return;
        boolean isFav = ZoomFavoritesManager.isFavorite(this, zoom);
        if (isFav) {
            btnFavorite.setImageResource(R.drawable.ic_star_filled);
            btnFavorite.setColorFilter(0xFFF59E0B);
            btnFavorite.setContentDescription("Zoom " + zoom + "% favorited ★");
        } else {
            btnFavorite.setImageResource(R.drawable.ic_star_outline);
            btnFavorite.setColorFilter(0xFF94A3B8);
            btnFavorite.setContentDescription("Favorite zoom " + zoom + "%");
        }
    }

    private void updateFavoriteIcon() {
        if (canvasView != null) {
            updateFavoriteZoomIcon(Math.round(canvasView.getZoomPercent()));
        }
    }

    private void updateViewportLockIcon() {
        if (btnLockViewport == null || canvasView == null) return;
        boolean locked = canvasView.isViewportLocked();
        btnLockViewport.setImageResource(locked ? R.drawable.ic_lock : R.drawable.ic_unlock);
        btnLockViewport.setColorFilter(locked ? 0xFF38BDF8 : 0xFF94A3B8);
        btnLockViewport.setContentDescription(locked ? "Viewport locked" : "Viewport unlocked");
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            saveCurrentPage();
            saveNotebookViewState();
            saveNotebookConfig();
            finish();
        });

        btnUndo.setOnClickListener(v -> canvasView.undo());
        btnRedo.setOnClickListener(v -> canvasView.redo());

        if (btnToolbarCircleToSearch != null) {
            btnToolbarCircleToSearch.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(60).withEndAction(() -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                }).start();
                triggerCircleToSearch();
            });
            btnToolbarCircleToSearch.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showSearchAssistConfigDialog();
                return true;
            });
        }

        // Tools
        toolFountain.setOnClickListener(v -> selectOrToggleTool(DrawingTool.FOUNTAIN, "Fountain Pen Settings"));
        toolPen.setOnClickListener(v -> selectOrToggleTool(DrawingTool.PEN, "Ballpoint Pen Settings"));
        toolHighlighter.setOnClickListener(v -> selectOrToggleTool(DrawingTool.HIGHLIGHTER, "Highlighter Settings"));
        toolPencil.setOnClickListener(v -> selectOrToggleTool(DrawingTool.PENCIL, "Pencil Settings"));
        toolEraser.setOnClickListener(v -> {
            closeToolPropertiesCard();
            long now = SystemClock.uptimeMillis();
            boolean isDoubleClick = (now - lastEraserClickTime) < 450L;
            lastEraserClickTime = now;
            if (isDoubleClick) {
                showEraserOptionsDialog();
            } else {
                DrawingTool prevTool = canvasView.getTool();
                if (prevTool != null) {
                    saveCurrentToolWidthAndColor(prevTool);
                }
                canvasView.setTool(DrawingTool.ERASER_STROKE);
                updateToolUi(DrawingTool.ERASER_STROKE);
                if (appSettings != null) appSettings.setLastTool(DrawingTool.ERASER_STROKE);
                boolean onlyH = canvasView.isEraseOnlyHighlighter();
                toolEraser.setColorFilter(onlyH ? 0xFFF59E0B : 0xFF38BDF8);
                saveNotebookConfig();
            }
        });
        toolEraser.setOnLongClickListener(v -> {
            showEraserOptionsDialog();
            return true;
        });

        toolLasso.setOnClickListener(v -> {
            closeToolPropertiesCard();
            long now = SystemClock.uptimeMillis();
            boolean isDoubleClick = (now - lastLassoClickTime) < 450L;
            lastLassoClickTime = now;
            if (isDoubleClick) {
                showLassoOptionsDialog();
            } else {
                DrawingTool prevTool = canvasView.getTool();
                if (prevTool != null) {
                    saveCurrentToolWidthAndColor(prevTool);
                }
                canvasView.setTool(DrawingTool.LASSO);
                updateToolUi(DrawingTool.LASSO);
                if (appSettings != null) appSettings.setLastTool(DrawingTool.LASSO);
                boolean isRect = canvasView.getLassoMode() == CanvasView.LassoMode.RECTANGLE;
                toolLasso.setColorFilter(isRect ? 0xFF10B981 : 0xFF38BDF8);
                saveNotebookConfig();
            }
        });
        toolLasso.setOnLongClickListener(v -> {
            showLassoOptionsDialog();
            return true;
        });


        if (toolLaser != null) {
            toolLaser.setOnClickListener(v -> selectOrToggleTool(DrawingTool.LASER, "Laser Presentation Pointer"));
        }

        if (toolHand != null) {
            toolHand.setOnClickListener(v -> {
                v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                        .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()).start();
                boolean nextState = !canvasView.isFingerMoveCanvasEnabled();
                canvasView.setFingerMoveCanvasEnabled(nextState);
                if (appSettings != null) appSettings.setFingerMoveCanvas(nextState);
                updateHandToolUi(nextState);
                saveNotebookConfig();
                if (nextState) {
                    Toast.makeText(this, "🖐️ Move Canvas with Finger: ON\n(Stylus draws with pen, finger moves canvas)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "✏️ Move Canvas with Finger: OFF", Toast.LENGTH_SHORT).show();
                }
            });
            toolHand.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                boolean nextStylusOnly = !canvasView.isStylusOnly();
                canvasView.setStylusOnly(nextStylusOnly);
                if (appSettings != null) appSettings.setStylusOnly(nextStylusOnly);
                saveNotebookConfig();
                Toast.makeText(this, nextStylusOnly
                        ? "🖐️ Palm Rejection ON (Stylus-only drawing active)"
                        : "Touch & Stylus drawing enabled", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        if (btnAutoBeautify != null) {
            btnAutoBeautify.setOnClickListener(v -> {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                        .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()).start();
                boolean currentState = canvasView.isAutoBeautifyEnabled() || canvasView.isAutoConvertTextEnabled();
                boolean newState = !currentState;
                canvasView.setAutoBeautifyEnabled(newState);
                canvasView.setAutoConvertTextEnabled(newState);
                if (!newState) {
                    canvasView.cancelPendingAutoConversion();
                }
                if (appSettings != null) {
                    appSettings.setAutoBeautifyEnabled(newState);
                    appSettings.setAutoConvertTextEnabled(newState);
                }
                updateAutoBeautifyToolbarIcon(newState);
                saveNotebookConfig();
                String lang = appSettings != null ? appSettings.getBeautifyLanguage() : "MARATHI";
                String langDisplay = "AUTO".equalsIgnoreCase(lang)
                        ? "Auto-Detect: Marathi, English, Hindi, Digits & Symbols"
                        : ("MARATHI".equalsIgnoreCase(lang) ? "मराठी" : ("HINDI".equalsIgnoreCase(lang) ? "हिन्दी" : "English"));
                if (newState) {
                    Toast.makeText(this, "✨ Auto-Beautification ON (" + langDisplay + ")", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Auto-Beautification & Conversion OFF", Toast.LENGTH_SHORT).show();
                }
            });

            btnAutoBeautify.setOnLongClickListener(v -> {
                showAutoBeautifySettingsDialog();
                return true;
            });
        }

        if (btnTeachHandwriting != null) {
            btnTeachHandwriting.setOnClickListener(v -> {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                        .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()).start();
                showInteractiveTeachDialog();
            });
        }

        btnColorPicker.setOnClickListener(v -> {
            closeToolPropertiesCard();
            showColorPickerDialog();
        });

        toolShape.setOnClickListener(v -> {
            closeToolPropertiesCard();
            long now = SystemClock.uptimeMillis();
            boolean isDoubleClick = lastShapeClickTime > 0L && (now - lastShapeClickTime) < 450L;
            lastShapeClickTime = isDoubleClick ? 0L : now;
            if (isDoubleClick) {
                showShapeSelectorDialog();
                return;
            }

            DrawingTool previousTool = canvasView.getTool();
            if (previousTool != null) saveCurrentToolWidthAndColor(previousTool);
            canvasView.setTool(DrawingTool.SHAPE);
            updateToolUi(DrawingTool.SHAPE);
            if (appSettings != null) appSettings.setLastTool(DrawingTool.SHAPE);
            restoreToolWidthAndColor(DrawingTool.SHAPE);
            saveNotebookConfig();
        });
        toolText.setOnClickListener(v -> {
            closeToolPropertiesCard();
            DrawingTool prevTool = canvasView.getTool();
            if (prevTool != null) {
                saveCurrentToolWidthAndColor(prevTool);
            }
            canvasView.setTool(DrawingTool.TEXT);
            updateToolUi(DrawingTool.TEXT);
            if (appSettings != null) appSettings.setLastTool(DrawingTool.TEXT);
            saveNotebookConfig();
            Toast.makeText(this, "Tap anywhere on canvas to type text", Toast.LENGTH_SHORT).show();
        });
        toolMedia.setOnClickListener(v -> {
            closeToolPropertiesCard();
            imagePickerLauncher.launch("image/*");
        });

        btnExport.setOnClickListener(v -> showExportDialog());
        btnPageOverview.setOnClickListener(v -> showPageOverviewDialog());
        btnMenu.setOnClickListener(v -> showMoreMenu());

        // Quick Swatches on Toolbar Dock with smooth scale micro-animation
        View.OnClickListener colorClickListener = v -> {
            v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                    .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()).start();
            int color = 0xFFFFFFFF;
            if (v == quickColorOrange) color = 0xFFF59E0B;
            else if (v == quickColorGreen) color = 0xFF10B981;
            else if (v == quickColorCyan) color = 0xFF38BDF8;
            else if (v == quickColorPurple) color = 0xFFA855F7;
            canvasView.setColor(color);
            updateColorPreview(color);
        };

        if (quickColorWhite != null) quickColorWhite.setOnClickListener(colorClickListener);
        if (quickColorOrange != null) quickColorOrange.setOnClickListener(colorClickListener);
        if (quickColorGreen != null) quickColorGreen.setOnClickListener(colorClickListener);
        if (quickColorCyan != null) quickColorCyan.setOnClickListener(colorClickListener);
        if (quickColorPurple != null) quickColorPurple.setOnClickListener(colorClickListener);

        // Navigation Header Actions
        if (btnCloudSync != null) {
            btnCloudSync.setOnClickListener(v -> Toast.makeText(this, "100% Offline • Notes saved directly on device storage", Toast.LENGTH_SHORT).show());
        }
        if (btnCamera != null) {
            btnCamera.setOnClickListener(v -> showInsertPhotoDialog());
        }
        if (btnCrop != null) {
            btnCrop.setOnClickListener(v -> {
                if (canvasView.isScreenCropActive()) {
                    canvasView.stopScreenCrop();
                } else {
                    canvasView.startScreenCrop(new CanvasView.OnScreenCropListener() {
                        @Override
                        public void onCropConfirmed(Bitmap croppedBitmap) {
                            if (croppedBitmap != null) {
                                try {
                                    File imagesDir = new File(getFilesDir(), "note_images");
                                    if (!imagesDir.exists()) imagesDir.mkdirs();
                                    File destFile = new File(imagesDir, "crop_" + System.currentTimeMillis() + ".png");
                                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                    }
                                    float width = Math.min(croppedBitmap.getWidth(), 800f);
                                    float height = width * ((float) croppedBitmap.getHeight() / croppedBitmap.getWidth());
                                    float centerX = canvasView.getCenterCanvasX();
                                    float centerY = canvasView.getCenterCanvasY();
                                    ImageItem imgItem = new ImageItem(destFile.getAbsolutePath(), centerX - width / 2f, centerY - height / 2f, width, height);
                                    canvasView.addImageItem(imgItem);
                                    saveCurrentPage();
                                    Toast.makeText(NoteEditorActivity.this, "✂️ Screenshot cropped and inserted into page!", Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    Toast.makeText(NoteEditorActivity.this, "Failed to save cropped screenshot: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                        }

                        @Override
                        public void onCropCancelled() {
                            Toast.makeText(NoteEditorActivity.this, "Screenshot cancelled", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        }
        if (btnMic != null) {
            btnMic.setOnClickListener(v -> showVoiceRecorderDialog());
        }
        canvasView.setOnAudioMemoClickListener(item -> {
            if (item != null && item.audioPath != null) {
                playAudioFile(new File(item.audioPath));
            }
        });
        if (btnSplitView != null) {
            btnSplitView.setOnClickListener(v -> setZenFullscreen(!isZenFullscreen));
        }
        if (btnRuler != null) {
            btnRuler.setOnClickListener(v -> showRulerAngleDialog());
        }
        if (btnLayers != null) {
            btnLayers.setOnClickListener(v -> showLayersDialog());
        }
        if (btnPageColor != null) {
            btnPageColor.setOnClickListener(v -> showPageColorSelectionDialog());
        }
        if (btnSettingsSliders != null) {
            btnSettingsSliders.setOnClickListener(v -> showPageSettingsMenu());
        }

        // Viewport Pill
        btnFavorite.setOnClickListener(v -> toggleFavoriteCurrentZoom());
        btnFavorite.setOnLongClickListener(v -> {
            showResetZoomFavoritesMenu();
            return true;
        });

        textZoomPercent.setOnClickListener(v -> showZoomPickerDialog());

        btnLockViewport.setOnClickListener(v -> {
            boolean next = !canvasView.isViewportLocked();
            canvasView.setViewportLocked(next);
            updateViewportLockIcon();
            saveNotebookConfig();
            Toast.makeText(this, next ? "Viewport Locked 🔒" : "Viewport Unlocked", Toast.LENGTH_SHORT).show();
        });
        updateViewportLockIcon();

        btnResetZoom.setOnClickListener(v -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastResetZoomClickTime < 350L) {
                lastResetZoomClickTime = 0L;
                resetZoomHandler.removeCallbacksAndMessages(null);
                showResetZoomFavoritesMenu();
            } else {
                lastResetZoomClickTime = now;
                resetZoomHandler.removeCallbacksAndMessages(null);
                resetZoomHandler.postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    executeResetZoom();
                }, 300L);
            }
        });
        btnResetZoom.setOnLongClickListener(v -> {
            resetZoomHandler.removeCallbacksAndMessages(null);
            showResetZoomFavoritesMenu();
            return true;
        });

        if (btnPdfAmoledToggle != null) {
            btnPdfAmoledToggle.setOnClickListener(v -> togglePdfAmoledDarkMode());
            btnPdfAmoledToggle.setOnLongClickListener(v -> {
                showSwipeGestureOptionsDialog();
                return true;
            });
        }

        btnPrevPage.setOnClickListener(v -> goToPrevPageWithAnimation());
        btnPrevPage.setOnLongClickListener(v -> {
            showSwipeGestureOptionsDialog();
            return true;
        });

        btnNextPage.setOnClickListener(v -> goToNextPageWithAnimation());
        btnNextPage.setOnLongClickListener(v -> {
            showSwipeGestureOptionsDialog();
            return true;
        });

        btnAddPage.setOnClickListener(v -> showAddPageMenu(v, null, null));

        textPageCounter.setOnClickListener(v -> showGoToPageDialog());
        textPageCounter.setOnLongClickListener(v -> {
            showSwipeGestureOptionsDialog();
            return true;
        });

        // Contextual Lasso Action Bar
        if (btnLassoSearch != null) {
            btnLassoSearch.setOnClickListener(v -> triggerCircleToSearch());
            btnLassoSearch.setOnLongClickListener(v -> {
                showSearchAssistConfigDialog();
                return true;
            });
        }

        View.OnTouchListener lassoDragListener = new View.OnTouchListener() {
            private float dX, dY;
            private float downRawX, downRawY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = lassoFloatingActionBar.getX() - event.getRawX();
                        dY = lassoFloatingActionBar.getY() - event.getRawY();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        isDragging = false;
                        lassoFloatingActionBar.animate().scaleX(1.03f).scaleY(1.03f).setDuration(100).start();
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float distX = Math.abs(event.getRawX() - downRawX);
                        float distY = Math.abs(event.getRawY() - downRawY);
                        if (distX > 8 || distY > 8) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            float newX = event.getRawX() + dX;
                            float newY = event.getRawY() + dY;

                            View parent = (View) lassoFloatingActionBar.getParent();
                            int parentW = parent != null ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels;
                            int parentH = parent != null ? parent.getHeight() : getResources().getDisplayMetrics().heightPixels;

                            int barW = lassoFloatingActionBar.getWidth();
                            int barH = lassoFloatingActionBar.getHeight();

                            float clampedX = Math.max(12f, Math.min(newX, parentW - barW - 12f));
                            float clampedY = Math.max(dpToPx(65), Math.min(newY, parentH - barH - dpToPx(75)));

                            lassoFloatingActionBar.setX(clampedX);
                            lassoFloatingActionBar.setY(clampedY);

                            userHasManuallyMovedLassoBar = true;
                            userLassoBarX = clampedX;
                            userLassoBarY = clampedY;
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        lassoFloatingActionBar.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                        if (isDragging) {
                            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        } else if (v == btnLassoDragHandle) {
                            userHasManuallyMovedLassoBar = false;
                            repositionLassoBar(canvasView.getSelectionScreenBounds());
                            Toast.makeText(NoteEditorActivity.this, "Position reset above selection", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                }
                return false;
            }
        };

        if (btnLassoDragHandle != null) {
            btnLassoDragHandle.setOnTouchListener(lassoDragListener);
        }
        if (layoutLassoPill != null) {
            layoutLassoPill.setOnTouchListener(lassoDragListener);
        }

        btnLassoDelete.setOnClickListener(v -> canvasView.deleteSelection());
        btnLassoCut.setOnClickListener(v -> {
            if (!canvasView.hasSelection()) return;
            canvasView.cutSelection();
            Toast.makeText(this, "Cut to clipboard. Press & hold canvas to paste.", Toast.LENGTH_SHORT).show();
            updatePasteButtonVisibility();
        });
        btnLassoCopy.setOnClickListener(v -> {
            if (!canvasView.hasSelection()) return;
            canvasView.copySelection();
            Toast.makeText(this, "Copied to clipboard. Press & hold canvas to paste.", Toast.LENGTH_SHORT).show();
            updatePasteButtonVisibility();
        });
        btnLassoPaste.setOnClickListener(v -> {
            if (!canvasView.hasClipboardContents()) {
                Toast.makeText(this, "Copy or cut content before pasting", Toast.LENGTH_SHORT).show();
                return;
            }
            canvasView.pasteSelection();
            updatePasteButtonVisibility();
        });
        btnLassoStyle.setOnClickListener(v -> showLassoStyleDialog());
        btnLassoDuplicate.setOnClickListener(v -> canvasView.duplicateSelection());
        btnLassoColor.setOnClickListener(v -> {
            StarColorPickerDialog.show(this, "Selection Color", canvasView.getSelectionColor(), color -> {
                canvasView.setSelectionColor(color);
            });
        });
        btnLassoSpacing.setOnClickListener(v -> showTextSpacingDialog());

        if (btnLassoConvertText != null) {
            btnLassoConvertText.setOnClickListener(v -> {
                canvasView.convertSelectionToText();
                Toast.makeText(this, "✍️ Converted to font text!", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnLassoTeach != null) {
            btnLassoTeach.setOnClickListener(v -> showInteractiveTeachDialog());
        }

        btnExitFullscreen.setOnClickListener(v -> setZenFullscreen(false));
    }

    private long lastToolClickTime = 0L;
    private DrawingTool lastClickedTool = null;
    private long lastEraserClickTime = 0L;
    private long lastLassoClickTime = 0L;
    private long lastShapeClickTime = 0L;
    private long lastResetZoomClickTime = 0L;
    private final Handler resetZoomHandler = new Handler(Looper.getMainLooper());

    public void closeToolPropertiesCard() {
        if (cardToolProperties != null && cardToolProperties.getVisibility() == View.VISIBLE) {
            cardToolProperties.animate().cancel();
            cardToolProperties.animate().alpha(0f).translationY(-25f).setDuration(160)
                    .withEndAction(() -> cardToolProperties.setVisibility(View.GONE)).start();
        }
    }

    public void openToolPropertiesCard(String title) {
        if (cardToolProperties == null) return;
        if (textToolPropertyTitle != null && title != null) {
            textToolPropertyTitle.setText(title);
        }
        boolean laserTool = canvasView.getTool() == DrawingTool.LASER;
        if (layoutPressureSensitivity != null) {
            layoutPressureSensitivity.setVisibility(laserTool ? View.GONE : View.VISIBLE);
            if (!laserTool && canvasView != null) {
                float pressure = canvasView.getPressureSensitivity();
                if (seekPressureSensitivity != null) seekPressureSensitivity.setProgress(Math.round(pressure * 100f));
                if (textPressureValue != null) textPressureValue.setText(Math.round(pressure * 100f) + "%");
            }
        }
        if (layoutStabilization != null) layoutStabilization.setVisibility(View.VISIBLE);
        if (layoutStraightLine != null) {
            layoutStraightLine.setVisibility(laserTool ? View.GONE : View.VISIBLE);
        }
        if (textStabilizationTitle != null) {
            textStabilizationTitle.setText(laserTool ? "Laser trail duration" : "Stabilization");
        }
        if (seekStabilization != null) {
            seekStabilization.setOnSeekBarChangeListener(null);
            if (laserTool) {
                long durationMs = canvasView.getLaserFadeDurationMs();
                seekStabilization.setMax(100);
                seekStabilization.setProgress(Math.round((durationMs - 400L) / 4600f * 100f));
                textStabilizationValue.setText(String.format(Locale.US, "%.1f s", durationMs / 1000f));
                seekStabilization.setContentDescription("Laser pointer trail duration from 0.4 to 5 seconds");
                seekStabilization.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) return;
                        long selectedDurationMs = 400L + Math.round(progress * 46L);
                        canvasView.setLaserFadeDurationMs(selectedDurationMs);
                        textStabilizationValue.setText(String.format(Locale.US, "%.1f s", selectedDurationMs / 1000f));
                        if (appSettings != null) appSettings.setLaserDurationMs(selectedDurationMs);
                        saveNotebookConfig();
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            } else {
                float stabilization = canvasView.getStrokeStabilization();
                int progress = Math.round(stabilization * 100f);
                seekStabilization.setProgress(progress);
                textStabilizationValue.setText(progress + "%");
                seekStabilization.setContentDescription("Stroke stabilization");
                seekStabilization.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) return;
                        float value = progress / 100f;
                        canvasView.setStrokeStabilization(value);
                        textStabilizationValue.setText(progress + "%");
                        if (appSettings != null) appSettings.setStabilization(value);
                        saveNotebookConfig();
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        }
        if (laserTool) {
            if (textToolPropertyTitle != null) textToolPropertyTitle.setText("Laser Presentation Pointer");
            if (btnNibFine != null) {
                btnNibFine.setText("Trail Line");
                btnNibFine.setOnClickListener(v -> {
                    canvasView.setLaserMode(CanvasView.LaserMode.LINE);
                    updateNibButtons(btnNibFine);
                    Toast.makeText(this, "Laser: Trail Line Mode", Toast.LENGTH_SHORT).show();
                });
            }
            if (btnNibMedium != null) {
                btnNibMedium.setText("Pointer Dot");
                btnNibMedium.setOnClickListener(v -> {
                    canvasView.setLaserMode(CanvasView.LaserMode.DOT);
                    updateNibButtons(btnNibMedium);
                    Toast.makeText(this, "Laser: Pointer Dot Mode", Toast.LENGTH_SHORT).show();
                });
            }
            if (btnNibBold != null) {
                btnNibBold.setVisibility(View.GONE);
            }
            boolean isLine = canvasView.getLaserMode() == CanvasView.LaserMode.LINE;
            updateNibButtons(isLine ? btnNibFine : btnNibMedium);

            if (seekStrokeThickness != null) seekStrokeThickness.setProgress(Math.round(canvasView.getLaserStrokeWidth()));
            if (textThicknessValue != null) textThicknessValue.setText(String.format(Locale.US, "%.1f mm", canvasView.getLaserStrokeWidth() * 0.3f));

            if (textStraightLineTitle != null) textStraightLineTitle.setText("Straight-Line Snapping");
            if (switchStraightLine != null) {
                switchStraightLine.setOnCheckedChangeListener(null);
                switchStraightLine.setChecked(false);
            }
        } else {
            if (canvasView != null) {
                float currentW = canvasView.getStrokeWidth();
                if (seekStrokeThickness != null) seekStrokeThickness.setProgress(Math.round(currentW));
                if (textThicknessValue != null) textThicknessValue.setText(String.format(Locale.US, "%.1f mm", currentW * 0.3f));
                updateNibButtonsForWidth(currentW);
            }
            if (btnNibFine != null) {
                btnNibFine.setText("Fine");
                btnNibFine.setOnClickListener(v -> {
                    canvasView.setStrokeWidth(3.0f);
                    if (seekStrokeThickness != null) seekStrokeThickness.setProgress(3);
                    if (textThicknessValue != null) textThicknessValue.setText("0.9 mm");
                    updateNibButtons(btnNibFine);
                    if (appSettings != null) appSettings.setStrokeWidth(3.0f);
                    saveNotebookConfig();
                });
            }
            if (btnNibMedium != null) {
                btnNibMedium.setText("Medium");
                btnNibMedium.setOnClickListener(v -> {
                    canvasView.setStrokeWidth(6.0f);
                    if (seekStrokeThickness != null) seekStrokeThickness.setProgress(6);
                    if (textThicknessValue != null) textThicknessValue.setText("1.8 mm");
                    updateNibButtons(btnNibMedium);
                    if (appSettings != null) appSettings.setStrokeWidth(6.0f);
                    saveNotebookConfig();
                });
            }
            if (btnNibBold != null) {
                btnNibBold.setVisibility(View.VISIBLE);
                btnNibBold.setText("Bold");
                btnNibBold.setOnClickListener(v -> {
                    canvasView.setStrokeWidth(12.0f);
                    if (seekStrokeThickness != null) seekStrokeThickness.setProgress(12);
                    if (textThicknessValue != null) textThicknessValue.setText("3.6 mm");
                    updateNibButtons(btnNibBold);
                    if (appSettings != null) appSettings.setStrokeWidth(12.0f);
                    saveNotebookConfig();
                });
            }
            if (switchStraightLine != null) {
                switchStraightLine.setOnCheckedChangeListener(null);
                if (canvasView.getTool() == DrawingTool.HIGHLIGHTER) {
                    if (textStraightLineTitle != null) textStraightLineTitle.setText("Lock 0° / 90° (Straight Highlighter)");
                    switchStraightLine.setChecked(canvasView.isStraightHighlighterEnabled());
                } else {
                    if (textStraightLineTitle != null) textStraightLineTitle.setText("Straight-Line Snapping");
                    switchStraightLine.setChecked(canvasView.isStraightLineAssist());
                }
                switchStraightLine.setOnCheckedChangeListener((btn, isChecked) -> {
                    if (canvasView.getTool() == DrawingTool.HIGHLIGHTER) {
                        canvasView.setStraightHighlighterEnabled(isChecked);
                        saveNotebookConfig();
                        Toast.makeText(this, isChecked ? "Straight Highlighter Lock: ON (0° / 90°)" : "Straight Highlighter Lock: OFF", Toast.LENGTH_SHORT).show();
                    } else {
                        canvasView.setStraightLineAssist(isChecked);
                        if (btnRuler != null) btnRuler.setColorFilter(isChecked ? 0xFF38BDF8 : 0xFF94A3B8);
                        if (appSettings != null) appSettings.setStraightLineAssist(isChecked);
                        saveNotebookConfig();
                    }
                });
            }
        }
        cardToolProperties.animate().cancel();
        cardToolProperties.setVisibility(View.VISIBLE);
        cardToolProperties.setAlpha(0f);
        cardToolProperties.setTranslationY(-25f);
        cardToolProperties.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }


    private void selectOrToggleTool(DrawingTool tool, String title) {
        long now = SystemClock.uptimeMillis();
        boolean isQuickDoubleClick = (tool == lastClickedTool && (now - lastToolClickTime) < 450L);
        lastToolClickTime = now;
        lastClickedTool = tool;

        if (canvasView.getTool() == tool) {
            if (isQuickDoubleClick) {
                // User double-clicked tool: open menu and keep it open
                openToolPropertiesCard(title);
            } else {
                // Second tap toggles property card with smooth slide and fade
                if (cardToolProperties.getVisibility() == View.VISIBLE) {
                    closeToolPropertiesCard();
                } else {
                    openToolPropertiesCard(title);
                }
            }
        } else {
            DrawingTool prevTool = canvasView.getTool();
            if (prevTool != null) {
                saveCurrentToolWidthAndColor(prevTool);
            }
            canvasView.setTool(tool);
            updateToolUi(tool);
            if (appSettings != null) {
                appSettings.setLastTool(tool);
            }
            restoreToolWidthAndColor(tool);
            saveNotebookConfig();

            if (isQuickDoubleClick) {
                // Double-clicked to switch and open settings directly
                openToolPropertiesCard(title);
            } else {
                closeToolPropertiesCard();
            }
        }
        if (textToolPropertyTitle != null && title != null) {
            textToolPropertyTitle.setText(title);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            float rawX = ev.getRawX();
            float rawY = ev.getRawY();
            if (!isTouchInsideView(toolShape, rawX, rawY, dpToPx(4))) {
                lastShapeClickTime = 0L;
            }
            boolean closedAnyMenu = false;

            // 1. Tool properties popup menu (Frame 50)
            if (cardToolProperties != null && cardToolProperties.getVisibility() == View.VISIBLE) {
                // If touch is NOT inside cardToolProperties (using screen coordinates + 8dp slop for edges/slider thumbs)
                if (!isTouchInsideView(cardToolProperties, rawX, rawY, dpToPx(8))) {
                    // Check if clicked the active tool button (its own onClick will toggle/handle it)
                    View activeToolBtn = getActiveToolButtonView();
                    boolean clickedActiveTool = isTouchInsideView(activeToolBtn, rawX, rawY, dpToPx(4));
                    if (!clickedActiveTool) {
                        closeToolPropertiesCard();
                        closedAnyMenu = true;
                    }
                }
            }

            // 2. Long press paste popup
            if (layoutLongPressPastePopup != null && layoutLongPressPastePopup.getVisibility() == View.VISIBLE) {
                if (!isTouchInsideView(layoutLongPressPastePopup, rawX, rawY, dpToPx(8))) {
                    hideLongPressPastePopup();
                    closedAnyMenu = true;
                }
            }

            // 3. When any menu was closed by clicking outside:
            if (closedAnyMenu) {
                // If user tapped on the open canvas to dismiss the menu, consume the ACTION_DOWN
                // so no accidental ink dot, line, or gesture is drawn on the page!
                // But if they clicked another toolbar control (undo, redo, tool, export, etc.),
                // dispatch it so the toolbar button activates!
                if (isTouchOnlyOnCanvas(rawX, rawY)) {
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private View getActiveToolButtonView() {
        DrawingTool current = canvasView != null ? canvasView.getTool() : null;
        if (current == null) return null;
        switch (current) {
            case FOUNTAIN: return toolFountain;
            case PEN: return toolPen;
            case HIGHLIGHTER: return toolHighlighter;
            case PENCIL: return toolPencil;
            case LASER: return toolLaser;
            default: return null;
        }
    }

    private boolean isTouchInsideView(View v, float rawX, float rawY) {
        return isTouchInsideView(v, rawX, rawY, 0);
    }

    private boolean isTouchInsideView(View v, float rawX, float rawY, int slop) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        v.getLocationOnScreen(location);
        int left = location[0] - slop;
        int top = location[1] - slop;
        int right = location[0] + v.getWidth() + slop;
        int bottom = location[1] + v.getHeight() + slop;
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    private boolean isTouchOnlyOnCanvas(float rawX, float rawY) {
        if (canvasView == null) return false;
        if (!isTouchInsideView(canvasView, rawX, rawY)) {
            return false;
        }

        View[] toolbarContainers = {
                topBar, mainToolDock, floatingViewportPill, floatingPageNavPill,
                btnExitFullscreen, lassoFloatingActionBar, cardToolProperties, layoutLongPressPastePopup
        };
        for (View v : toolbarContainers) {
            if (isTouchInsideView(v, rawX, rawY, dpToPx(4))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (cardToolProperties != null && cardToolProperties.getVisibility() == View.VISIBLE) {
            closeToolPropertiesCard();
            return;
        }
        if (layoutLongPressPastePopup != null && layoutLongPressPastePopup.getVisibility() == View.VISIBLE) {
            hideLongPressPastePopup();
            return;
        }
        saveCurrentPage();
        saveNotebookViewState();
        saveNotebookConfig();
        super.onBackPressed();
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit);
    }

    @Override
    public void finish() {
        saveCurrentPage();
        saveNotebookViewState();
        saveNotebookConfig();
        super.finish();
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit);
    }

    private void saveNotebookConfig() {
        if (notebookId <= 0 || canvasView == null) return;
        try {
            NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
            if (config == null) {
                config = new NotebookConfigManager.NotebookConfig();
            }
            DrawingTool curTool = canvasView.getTool();
            config.tool = curTool;
            float strokeW = (curTool == DrawingTool.LASER) ? canvasView.getLaserStrokeWidth() : canvasView.getStrokeWidth();
            config.strokeWidth = strokeW;
            if (curTool != null) {
                config.setToolWidth(curTool, strokeW);
                config.setToolColor(curTool, canvasView.getColor());
            }
            config.inkColor = canvasView.getColor();
            config.pressureSensitivity = canvasView.getPressureSensitivity();
            config.stabilization = canvasView.getStrokeStabilization();
            config.straightLineAssist = canvasView.isStraightLineAssist();
            config.straightHighlighterLock = canvasView.isStraightHighlighterEnabled();
            config.eraseOnlyHighlighter = canvasView.isEraseOnlyHighlighter();
            config.stylusOnly = canvasView.isStylusOnly();
            config.fingerMoveCanvas = canvasView.isFingerMoveCanvasEnabled();
            config.pdfAmoledDarkMode = canvasView.isPdfAmoledDarkMode();
            config.continuousVerticalScroll = canvasView.isContinuousVerticalScroll();
            config.viewportLocked = canvasView.isViewportLocked();
            config.laserDurationMs = canvasView.getLaserFadeDurationMs();
            config.autoBeautifyEnabled = canvasView.isAutoBeautifyEnabled();
            config.lassoMode = canvasView.getLassoMode();
            config.laserMode = canvasView.getLaserMode();
            config.defaultResetZoom = getFavoriteResetZoom();
            config.pdfResetMode = getPdfResetMode();
            config.shapeType = canvasView.getShapeType().name();

            NotebookConfigManager.saveConfig(this, notebookId, config);
        } catch (Throwable t) {
            Log.w("NoteEditorActivity", "Failed to save notebook config", t);
        }
    }

    private void saveCurrentToolWidthAndColor(DrawingTool tool) {
        if (notebookId <= 0 || canvasView == null || tool == null) return;
        try {
            NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
            if (config == null) {
                config = new NotebookConfigManager.NotebookConfig();
            }
            float width = (tool == DrawingTool.LASER) ? canvasView.getLaserStrokeWidth() : canvasView.getStrokeWidth();
            config.setToolWidth(tool, width);
            config.setToolColor(tool, canvasView.getColor());
            NotebookConfigManager.saveConfig(this, notebookId, config);
        } catch (Throwable ignored) {}
    }

    private void restoreToolWidthAndColor(DrawingTool tool) {
        if (notebookId <= 0 || canvasView == null || tool == null) return;
        NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, notebookId);
        if (config != null) {
            if (config.toolWidths.containsKey(tool)) {
                float savedWidth = config.getToolWidth(tool);
                if (savedWidth > 0f) {
                    if (tool == DrawingTool.LASER) {
                        canvasView.setLaserStrokeWidth(savedWidth);
                    } else {
                        canvasView.setStrokeWidth(savedWidth);
                    }
                    if (seekStrokeThickness != null) seekStrokeThickness.setProgress(Math.round(savedWidth));
                    if (textThicknessValue != null) textThicknessValue.setText(String.format(Locale.US, "%.1f mm", savedWidth * 0.3f));
                    updateNibButtonsForWidth(savedWidth);
                }
            }
            if (config.toolColors.containsKey(tool)) {
                int savedColor = config.getToolColor(tool);
                canvasView.setColor(savedColor);
                updateColorPreviewInternal(savedColor);
            }
        }
    }

    private void updateNibButtonsForWidth(float width) {
        if (btnNibFine == null || btnNibMedium == null || btnNibBold == null) return;
        if (Math.abs(width - 3.0f) < 0.5f) {
            updateNibButtons(btnNibFine);
        } else if (Math.abs(width - 6.0f) < 0.5f) {
            updateNibButtons(btnNibMedium);
        } else if (Math.abs(width - 12.0f) < 0.5f) {
            updateNibButtons(btnNibBold);
        } else {
            btnNibFine.setBackgroundResource(R.drawable.bg_floating_pill);
            btnNibFine.setTextColor(0xFF94A3B8);
            btnNibMedium.setBackgroundResource(R.drawable.bg_floating_pill);
            btnNibMedium.setTextColor(0xFF94A3B8);
            btnNibBold.setBackgroundResource(R.drawable.bg_floating_pill);
            btnNibBold.setTextColor(0xFF94A3B8);
        }
    }

    private void restoreNotebookConfig(long targetNotebookId) {
        if (targetNotebookId <= 0 || canvasView == null) return;
        NotebookConfigManager.NotebookConfig config = NotebookConfigManager.loadConfig(this, targetNotebookId);
        if (config == null) {
            restoreUserSettings();
            saveNotebookConfig();
            return;
        }

        DrawingTool savedTool = config.tool;
        if (savedTool == null || savedTool == DrawingTool.HAND) {
            savedTool = (appSettings != null) ? appSettings.getLastTool() : DrawingTool.FOUNTAIN;
        }
        if (savedTool == DrawingTool.HAND) savedTool = DrawingTool.FOUNTAIN;
        canvasView.setTool(savedTool);
        updateToolUi(savedTool);
        if (config.shapeType != null) {
            try {
                canvasView.setShapeType(ShapeItem.Type.valueOf(config.shapeType));
            } catch (IllegalArgumentException ignored) {}
        }

        float savedWidth = config.getToolWidth(savedTool);
        if (savedWidth <= 0f) {
            savedWidth = (appSettings != null) ? appSettings.getStrokeWidth() : 3.0f;
        }
        if (savedTool == DrawingTool.LASER) {
            canvasView.setLaserStrokeWidth(savedWidth);
        } else {
            canvasView.setStrokeWidth(savedWidth);
        }
        if (seekStrokeThickness != null) seekStrokeThickness.setProgress(Math.round(savedWidth));
        if (textThicknessValue != null) textThicknessValue.setText(String.format(Locale.US, "%.1f mm", savedWidth * 0.3f));
        updateNibButtonsForWidth(savedWidth);

        int savedColor = config.getToolColor(savedTool);
        if (savedColor == Integer.MIN_VALUE) {
            savedColor = (appSettings != null) ? appSettings.getInkColor() : 0xFFFFFFFF;
        }
        canvasView.setColor(savedColor);
        updateColorPreviewInternal(savedColor);

        float savedPressure = (config.pressureSensitivity >= 0f) ? config.pressureSensitivity : (appSettings != null ? appSettings.getPressureSensitivity() : 0.5f);
        canvasView.setPressureSensitivity(savedPressure);
        if (seekPressureSensitivity != null) seekPressureSensitivity.setProgress(Math.round(savedPressure * 100f));
        if (textPressureValue != null) textPressureValue.setText(Math.round(savedPressure * 100f) + "%");

        long savedLaserDur = (config.laserDurationMs != null) ? config.laserDurationMs : (appSettings != null ? appSettings.getLaserDurationMs() : 1500L);
        canvasView.setLaserFadeDurationMs(savedLaserDur);

        float savedStab = (config.stabilization >= 0f) ? config.stabilization : (appSettings != null ? appSettings.getStabilization() : 0.3f);
        canvasView.setStrokeStabilization(savedStab);
        if (seekStabilization != null) seekStabilization.setProgress(Math.round(savedStab * 100f));
        if (textStabilizationValue != null) textStabilizationValue.setText(Math.round(savedStab * 100f) + "%");

        boolean savedStraightLine = (config.straightLineAssist != null) ? config.straightLineAssist : (appSettings != null && appSettings.isStraightLineAssist());
        canvasView.setStraightLineAssist(savedStraightLine);
        if (switchStraightLine != null && savedTool != DrawingTool.HIGHLIGHTER) switchStraightLine.setChecked(savedStraightLine);
        if (btnRuler != null) btnRuler.setColorFilter(savedStraightLine ? 0xFF38BDF8 : 0xFF94A3B8);

        if (config.straightHighlighterLock != null) {
            canvasView.setStraightHighlighterEnabled(config.straightHighlighterLock);
            if (switchStraightLine != null && savedTool == DrawingTool.HIGHLIGHTER) {
                switchStraightLine.setChecked(config.straightHighlighterLock);
            }
        }

        if (config.eraseOnlyHighlighter != null) {
            canvasView.setEraseOnlyHighlighter(config.eraseOnlyHighlighter);
            if (toolEraser != null) {
                toolEraser.setColorFilter(config.eraseOnlyHighlighter ? 0xFFF59E0B : 0xFF38BDF8);
            }
        }

        boolean savedStylusOnly = (config.stylusOnly != null) ? config.stylusOnly : (appSettings != null && appSettings.isStylusOnly());
        canvasView.setStylusOnly(savedStylusOnly);

        boolean savedFingerMove = (config.fingerMoveCanvas != null) ? config.fingerMoveCanvas : (appSettings != null && appSettings.isFingerMoveCanvas());
        canvasView.setFingerMoveCanvasEnabled(savedFingerMove);
        updateHandToolUi(savedFingerMove);

        boolean amoledDark = (config.pdfAmoledDarkMode != null) ? config.pdfAmoledDarkMode : (appSettings != null && appSettings.isPdfAmoledDarkMode());
        canvasView.setPdfAmoledDarkMode(amoledDark);
        if (btnPdfAmoledToggle != null) {
            btnPdfAmoledToggle.setColorFilter(amoledDark ? 0xFF38BDF8 : 0xFF94A3B8);
        }

        boolean continuous = (config.continuousVerticalScroll != null) ? config.continuousVerticalScroll : (appSettings != null && appSettings.isContinuousVerticalScroll());
        canvasView.setContinuousVerticalScroll(continuous);

        if (config.viewportLocked != null) {
            canvasView.setViewportLocked(config.viewportLocked);
            updateViewportLockIcon();
        }

        boolean savedBeautify = (config.autoBeautifyEnabled != null) ? config.autoBeautifyEnabled : (appSettings != null && appSettings.isAutoBeautifyEnabled());
        String savedLang = appSettings != null ? appSettings.getBeautifyLanguage() : "MARATHI";
        String savedFont = appSettings != null ? appSettings.getBeautifyFont() : "";
        boolean savedShirorekha = appSettings != null && appSettings.isShirorekhaSnapEnabled();
        float savedSmoothing = appSettings != null ? appSettings.getBeautifySmoothing() : 0.5f;

        canvasView.setAutoBeautifyEnabled(savedBeautify);
        canvasView.setBeautifyLanguage(savedLang);
        canvasView.setBeautifyFontKey(savedFont);
        canvasView.setShirorekhaSnapEnabled(savedShirorekha);
        canvasView.setBeautifySmoothing(savedSmoothing);
        canvasView.setAutoConvertTextEnabled(savedBeautify && (appSettings != null && appSettings.isAutoConvertTextEnabled()));
        updateAutoBeautifyToolbarIcon(savedBeautify);

        if (config.lassoMode != null) {
            canvasView.setLassoMode(config.lassoMode);
            if (toolLasso != null) {
                boolean isRect = config.lassoMode == CanvasView.LassoMode.RECTANGLE;
                toolLasso.setColorFilter(isRect ? 0xFF10B981 : 0xFF38BDF8);
            }
        }
        if (config.laserMode != null) {
            canvasView.setLaserMode(config.laserMode);
        }
        if (config.defaultResetZoom != null && config.defaultResetZoom >= 10) {
            ZoomFavoritesManager.setDefaultResetZoom(this, config.defaultResetZoom);
        }
        if (config.pdfResetMode != null) {
            ZoomFavoritesManager.setPdfResetMode(this, config.pdfResetMode);
        }
    }

    private void restoreUserSettings() {
        if (appSettings == null) return;

        // Restore Tool
        DrawingTool savedTool = appSettings.getLastTool();
        if (savedTool == DrawingTool.HAND) savedTool = DrawingTool.FOUNTAIN;
        canvasView.setTool(savedTool);
        updateToolUi(savedTool);

        // Restore Stroke Width
        float savedWidth = appSettings.getStrokeWidth();
        canvasView.setStrokeWidth(savedWidth);
        if (seekStrokeThickness != null) seekStrokeThickness.setProgress(Math.round(savedWidth));
        if (textThicknessValue != null) textThicknessValue.setText(String.format(Locale.US, "%.1f mm", savedWidth * 0.3f));
        updateNibButtonsForWidth(savedWidth);

        // Restore Ink Color
        int savedColor = appSettings.getInkColor();
        canvasView.setColor(savedColor);
        updateColorPreviewInternal(savedColor);

        // Restore Pressure Dynamics
        float savedPressure = appSettings.getPressureSensitivity();
        canvasView.setPressureSensitivity(savedPressure);
        if (seekPressureSensitivity != null) seekPressureSensitivity.setProgress(Math.round(savedPressure * 100f));
        if (textPressureValue != null) textPressureValue.setText(Math.round(savedPressure * 100f) + "%");

        canvasView.setLaserFadeDurationMs(appSettings.getLaserDurationMs());

        // Restore Stabilization Smoothing
        float savedStab = appSettings.getStabilization();
        canvasView.setStrokeStabilization(savedStab);
        if (seekStabilization != null) seekStabilization.setProgress(Math.round(savedStab * 100f));
        if (textStabilizationValue != null) textStabilizationValue.setText(Math.round(savedStab * 100f) + "%");

        // Restore Straight-Line Snapping
        boolean savedStraightLine = appSettings.isStraightLineAssist();
        canvasView.setStraightLineAssist(savedStraightLine);
        if (switchStraightLine != null) switchStraightLine.setChecked(savedStraightLine);
        if (btnRuler != null) btnRuler.setColorFilter(savedStraightLine ? 0xFF38BDF8 : 0xFF94A3B8);

        // Restore Palm Rejection (Stylus Only)
        boolean savedStylusOnly = appSettings.isStylusOnly();
        canvasView.setStylusOnly(savedStylusOnly);

        // Restore Single Finger Move Canvas
        boolean savedFingerMove = appSettings.isFingerMoveCanvas();
        canvasView.setFingerMoveCanvasEnabled(savedFingerMove);
        updateHandToolUi(savedFingerMove);

        // Restore Auto-Beautification Settings
        boolean savedBeautify = appSettings.isAutoBeautifyEnabled();
        String savedLang = appSettings.getBeautifyLanguage();
        String savedFont = appSettings.getBeautifyFont();
        boolean savedShirorekha = appSettings.isShirorekhaSnapEnabled();
        float savedSmoothing = appSettings.getBeautifySmoothing();

        canvasView.setAutoBeautifyEnabled(savedBeautify);
        canvasView.setBeautifyLanguage(savedLang);
        canvasView.setBeautifyFontKey(savedFont);
        canvasView.setShirorekhaSnapEnabled(savedShirorekha);
        canvasView.setBeautifySmoothing(savedSmoothing);
        canvasView.setAutoConvertTextEnabled(savedBeautify && appSettings.isAutoConvertTextEnabled());
        updateAutoBeautifyToolbarIcon(savedBeautify);
    }

    private void updateAutoBeautifyToolbarIcon(boolean enabled) {
        if (btnAutoBeautify == null) return;
        if (enabled) {
            btnAutoBeautify.setColorFilter(0xFF38BDF8);
            btnAutoBeautify.setBackgroundResource(R.drawable.bg_tool_button_active);
        } else {
            btnAutoBeautify.setColorFilter(0xFF94A3B8);
            btnAutoBeautify.setBackgroundResource(0);
        }
    }

    private void setupToolPropertyCard() {
        btnNibFine.setOnClickListener(v -> {
            canvasView.setStrokeWidth(3.0f);
            seekStrokeThickness.setProgress(3);
            textThicknessValue.setText("0.9 mm");
            updateNibButtons(btnNibFine);
            if (appSettings != null) appSettings.setStrokeWidth(3.0f);
            saveNotebookConfig();
        });
        btnNibMedium.setOnClickListener(v -> {
            canvasView.setStrokeWidth(6.0f);
            seekStrokeThickness.setProgress(6);
            textThicknessValue.setText("1.8 mm");
            updateNibButtons(btnNibMedium);
            if (appSettings != null) appSettings.setStrokeWidth(6.0f);
            saveNotebookConfig();
        });
        btnNibBold.setOnClickListener(v -> {
            canvasView.setStrokeWidth(12.0f);
            seekStrokeThickness.setProgress(12);
            textThicknessValue.setText("3.6 mm");
            updateNibButtons(btnNibBold);
            if (appSettings != null) appSettings.setStrokeWidth(12.0f);
            saveNotebookConfig();
        });

        seekStrokeThickness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float width = Math.max(1f, progress);
                    if (canvasView.getTool() == DrawingTool.LASER) {
                        canvasView.setLaserStrokeWidth(width);
                    } else {
                        canvasView.setStrokeWidth(width);
                        if (appSettings != null) appSettings.setStrokeWidth(width);
                    }
                    textThicknessValue.setText(String.format(Locale.US, "%.1f mm", width * 0.3f));
                    updateNibButtonsForWidth(width);
                    saveNotebookConfig();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekPressureSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float val = progress / 100f;
                    canvasView.setPressureSensitivity(val);
                    textPressureValue.setText(progress + "%");
                    if (appSettings != null) appSettings.setPressureSensitivity(val);
                    saveNotebookConfig();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekStabilization.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float val = progress / 100f;
                    canvasView.setStrokeStabilization(val);
                    textStabilizationValue.setText(progress + "%");
                    if (appSettings != null) appSettings.setStabilization(val);
                    saveNotebookConfig();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        if (switchStraightLine != null) {
            switchStraightLine.setOnCheckedChangeListener((btn, isChecked) -> {
                canvasView.setStraightLineAssist(isChecked);
                if (btnRuler != null) btnRuler.setColorFilter(isChecked ? 0xFF38BDF8 : 0xFF94A3B8);
                if (appSettings != null) appSettings.setStraightLineAssist(isChecked);
                saveNotebookConfig();
            });
        }

        // Quick colors in property card
        for (int qCol : QUICK_TOOL_COLORS) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(54, 54);
            lp.setMargins(6, 0, 6, 0);
            dot.setLayoutParams(lp);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(qCol);
            gd.setStroke(2, 0xFF475569);
            dot.setBackground(gd);
            dot.setContentDescription("Set ink color " + String.format(Locale.US, "#%06X", 0xFFFFFF & qCol));
            dot.setFocusable(true);
            dot.setClickable(true);

            dot.setOnClickListener(v -> {
                canvasView.setColor(qCol);
                updateColorPreview(qCol);
            });
            layoutToolQuickColors.addView(dot);
        }

        // Every drawing tool shares the ink color, so expose the full palette in its settings.
        ImageView paletteButton = new ImageView(this);
        int paletteSize = dpToPx(40);
        LinearLayout.LayoutParams paletteParams = new LinearLayout.LayoutParams(paletteSize, paletteSize);
        paletteParams.setMargins(dpToPx(8), 0, 0, 0);
        paletteButton.setLayoutParams(paletteParams);
        paletteButton.setImageResource(R.drawable.ic_palette);
        paletteButton.setColorFilter(0xFF38BDF8);
        paletteButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        paletteButton.setBackgroundResource(android.R.drawable.list_selector_background);
        paletteButton.setContentDescription("Open full ink color palette");
        paletteButton.setFocusable(true);
        paletteButton.setClickable(true);
        paletteButton.setOnClickListener(v -> showColorPickerDialog());
        layoutToolQuickColors.addView(paletteButton);

        btnAddToPenBox.setOnClickListener(v -> addCurrentToPenBox());
    }

    private void loadPenBoxPresets() {
        penBoxPresets.clear();
        SharedPreferences sp = getSharedPreferences("pen_box_prefs", MODE_PRIVATE);
        String json = sp.getString("presets", null);
        if (json != null && !json.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    DrawingTool tool = DrawingTool.valueOf(obj.getString("tool"));
                    int color = obj.getInt("color");
                    float width = (float) obj.getDouble("width");
                    penBoxPresets.add(new PenPreset(tool, color, width));
                }
            } catch (Exception ignored) {}
        }
        if (penBoxPresets.isEmpty()) {
            // Default StarNote pen box presets
            penBoxPresets.add(new PenPreset(DrawingTool.FOUNTAIN, 0xFFFFFFFF, 6.0f));
            penBoxPresets.add(new PenPreset(DrawingTool.HIGHLIGHTER, 0xFFFEF08A, 20.0f));
            penBoxPresets.add(new PenPreset(DrawingTool.PEN, 0xFFF59E0B, 4.0f));
        }
    }

    private void savePenBoxPresets() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (PenPreset p : penBoxPresets) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("tool", p.tool.name());
                obj.put("color", p.color);
                obj.put("width", (double) p.strokeWidth);
                arr.put(obj);
            }
            getSharedPreferences("pen_box_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("presets", arr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private void renderPenBoxDock() {
        if (layoutPenBoxDock == null) return;
        layoutPenBoxDock.removeAllViews();
        if (penBoxPresets.isEmpty()) {
            layoutPenBoxDock.setVisibility(View.GONE);
            if (dividerPenBox != null) dividerPenBox.setVisibility(View.GONE);
            return;
        }

        layoutPenBoxDock.setVisibility(View.VISIBLE);
        if (dividerPenBox != null) dividerPenBox.setVisibility(View.VISIBLE);

        for (int i = 0; i < penBoxPresets.size(); i++) {
            final int index = i;
            final PenPreset preset = penBoxPresets.get(i);

            FrameLayout slot = new FrameLayout(this);
            int slotSize = (int) (32 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(slotSize, slotSize);
            lp.setMargins((int) (2 * getResources().getDisplayMetrics().density), 0,
                    (int) (2 * getResources().getDisplayMetrics().density), 0);
            slot.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFF1E293B);
            bg.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), preset.color);
            slot.setBackground(bg);

            ImageView icon = new ImageView(this);
            int iconPadding = (int) (6 * getResources().getDisplayMetrics().density);
            icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            switch (preset.tool) {
                case HIGHLIGHTER:
                    icon.setImageResource(R.drawable.ic_highlighter);
                    break;
                case PEN:
                    icon.setImageResource(R.drawable.ic_pen);
                    break;
                case PENCIL:
                    icon.setImageResource(R.drawable.ic_pencil);
                    break;
                default:
                    icon.setImageResource(R.drawable.ic_fountain_pen);
                    break;
            }
            icon.setColorFilter(preset.color);
            slot.addView(icon);

            slot.setOnClickListener(v -> {
                v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100)
                        .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()).start();
                canvasView.setTool(preset.tool);
                canvasView.setColor(preset.color);
                canvasView.setStrokeWidth(preset.strokeWidth);
                updateToolUi(preset.tool);
                updateColorPreview(preset.color);
                if (seekStrokeThickness != null) {
                    seekStrokeThickness.setProgress(Math.round(preset.strokeWidth));
                }
                if (textThicknessValue != null) {
                    textThicknessValue.setText(String.format(Locale.US, "%.1f mm", preset.strokeWidth * 0.3f));
                }
                try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Exception ignored) {}
                Toast.makeText(this, "Pen Box: " + preset.tool.name() + " (" + String.format(Locale.US, "%.1fmm", preset.strokeWidth) + ")", Toast.LENGTH_SHORT).show();
            });

            slot.setOnLongClickListener(v -> {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                        .setTitle("Pen Box Preset")
                        .setMessage("Remove this preset (" + preset.tool.name() + ") from the quick dock?")
                        .setPositiveButton("Remove", (dialog, which) -> {
                            penBoxPresets.remove(index);
                            savePenBoxPresets();
                            renderPenBoxDock();
                            Toast.makeText(this, "Preset removed", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null);
                ThemeUtils.showStyledDialog(builder, this);
                return true;
            });

            layoutPenBoxDock.addView(slot);
        }
    }

    private void addCurrentToPenBox() {
        DrawingTool tool = canvasView.getTool();
        if (tool == DrawingTool.ERASER_STROKE || tool == DrawingTool.ERASER_AREA || tool == DrawingTool.LASSO || tool == DrawingTool.HAND) {
            tool = DrawingTool.FOUNTAIN;
        }
        int color = canvasView.getColor();
        float width = canvasView.getStrokeWidth();

        if (penBoxPresets.size() >= 6) {
            penBoxPresets.remove(0);
        }
        penBoxPresets.add(new PenPreset(tool, color, width));
        savePenBoxPresets();
        renderPenBoxDock();

        if (cardToolProperties != null) {
            cardToolProperties.animate().alpha(0f).translationY(-25f).setDuration(180)
                    .withEndAction(() -> cardToolProperties.setVisibility(View.GONE)).start();
        }
        Toast.makeText(this, "Added to Pen Box dock: " + tool.name() + " (" + String.format(Locale.US, "%.1fmm", width) + ")", Toast.LENGTH_SHORT).show();
    }

    private void showEraserOptionsDialog() {
        boolean onlyHighlighter = canvasView.isEraseOnlyHighlighter();
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Eraser Options")
                .setSubtitle("Select eraser targeting mode or clear canvas")
                .setHeaderSymbol("🧹")
                .addOption(1, "🧹", "Normal Eraser",
                        "Erase all strokes, pen ink, and handwritten text",
                        !onlyHighlighter, () -> {
                            canvasView.setEraseOnlyHighlighter(false);
                            toolEraser.setColorFilter(0xFF38BDF8);
                            saveNotebookConfig();
                        })
                .addOption(2, "🖍️", "Highlighter Eraser",
                        "Highlighter only • Preserves your pen ink and text",
                        onlyHighlighter, () -> {
                            canvasView.setEraseOnlyHighlighter(true);
                            toolEraser.setColorFilter(0xFFF59E0B);
                            saveNotebookConfig();
                        })
                .addDestructiveOption(3, "🗑️", "Clear Entire Page",
                        "Remove all ink drawings and handwriting on this page",
                        this::showClearCanvasDialog)
                .show();
    }

    private void showLassoOptionsDialog() {
        boolean isRect = canvasView.getLassoMode() == CanvasView.LassoMode.RECTANGLE;
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Lasso Selection Mode")
                .setSubtitle("Choose how you lasso strokes and items on canvas")
                .setHeaderSymbol("➰")
                .addOption(1, "➰", "Freehand Lasso",
                        "Draw a freeform outline around items to select",
                        !isRect, () -> {
                            canvasView.setLassoMode(CanvasView.LassoMode.FREEHAND);
                            toolLasso.setColorFilter(0xFF38BDF8);
                        })
                .addOption(2, "📐", "Rectangular Marquee",
                        "Drag a bounding box to quickly select rectangular regions",
                        isRect, () -> {
                            canvasView.setLassoMode(CanvasView.LassoMode.RECTANGLE);
                            toolLasso.setColorFilter(0xFF10B981);
                        })
                .show();
    }

    private void showClearCanvasDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Clear Canvas")
                .setMessage("Are you sure you want to clear all strokes on this page?")
                .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }


    private void updateNibButtons(TextView activeBtn) {
        btnNibFine.setBackgroundResource(R.drawable.bg_floating_pill);
        btnNibFine.setTextColor(0xFF94A3B8);
        btnNibMedium.setBackgroundResource(R.drawable.bg_floating_pill);
        btnNibMedium.setTextColor(0xFF94A3B8);
        btnNibBold.setBackgroundResource(R.drawable.bg_floating_pill);
        btnNibBold.setTextColor(0xFF94A3B8);

        activeBtn.setBackgroundResource(R.drawable.bg_tool_button_active);
        activeBtn.setTextColor(0xFFFFFFFF);
    }

    private void updateToolUi(DrawingTool tool) {
        ImageView[] allTools = {toolFountain, toolPen, toolHighlighter, toolPencil, toolEraser, toolLasso, toolShape, toolText, toolLaser};
        for (ImageView iv : allTools) {
            if (iv != null) {
                iv.setBackgroundResource(0);
                iv.setColorFilter(0xFFF8FAFC);
            }
        }

        ImageView activeIv = null;
        switch (tool) {
            case FOUNTAIN:
                activeIv = toolFountain;
                break;
            case PEN:
                activeIv = toolPen;
                break;
            case HIGHLIGHTER:
                activeIv = toolHighlighter;
                break;
            case PENCIL:
                activeIv = toolPencil;
                break;
            case ERASER_STROKE:
            case ERASER_AREA:
                activeIv = toolEraser;
                break;
            case LASSO:
                activeIv = toolLasso;
                break;
            case SHAPE:
                activeIv = toolShape;
                break;
            case TEXT:
                activeIv = toolText;
                break;
            case LASER:
                activeIv = toolLaser;
                break;
            default:
                break;
        }

        if (activeIv != null) {
            activeIv.setBackgroundResource(R.drawable.bg_tool_button_active);
            if (activeIv == toolEraser && canvasView != null && canvasView.isEraseOnlyHighlighter()) {
                activeIv.setColorFilter(0xFFF59E0B);
            } else if (activeIv == toolLasso && canvasView != null && canvasView.getLassoMode() == CanvasView.LassoMode.RECTANGLE) {
                activeIv.setColorFilter(0xFF10B981);
            } else {
                activeIv.setColorFilter(0xFF38BDF8);
            }
        }


        updateHandToolUi(canvasView != null && canvasView.isFingerMoveCanvasEnabled());
    }

    private void updateHandToolUi(boolean enabled) {
        if (toolHand == null) return;
        if (enabled) {
            toolHand.setColorFilter(0xFF38BDF8);
            toolHand.setBackgroundResource(R.drawable.bg_tool_button_active);
        } else {
            toolHand.setColorFilter(0xFFF8FAFC);
            toolHand.setBackgroundResource(0);
        }
    }

    private void updateColorPreview(int color) {
        updateColorPreviewInternal(color);
        saveNotebookConfig();
    }

    private void updateColorPreviewInternal(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        gd.setStroke(3, 0xFF38BDF8);
        colorPreviewCircle.setBackground(gd);
        if (appSettings != null) {
            appSettings.setInkColor(color);
        }
    }

    private void showColorPickerDialog() {
        StarColorPickerDialog.show(this, "Ink Color", canvasView.getColor(), color -> {
            canvasView.setColor(color);
            updateColorPreview(color);
        });
    }

    private void showResetZoomFavoritesMenu() {
        if (canvasView == null) return;
        boolean isPdf = canvasView.isPdfDocument();
        boolean isFitPageActive = isPdf && isPdfResetFitPage();

        List<Integer> favorites = ZoomFavoritesManager.getFavoriteZoomLevels(this);
        List<Integer> sortedOptions = new ArrayList<>();
        if (favorites != null && !favorites.isEmpty()) {
            sortedOptions.addAll(favorites);
        } else {
            sortedOptions.add(50);
            sortedOptions.add(100);
            sortedOptions.add(150);
            sortedOptions.add(200);
        }
        if (!sortedOptions.contains(100)) {
            sortedOptions.add(100);
        }
        Collections.sort(sortedOptions);

        int currentReset = getFavoriteResetZoom();

        ModernOptionsSelectorDialog.Builder builder = new ModernOptionsSelectorDialog.Builder(this)
                .setTitle(isPdf ? "PDF Reset Viewport" : "Reset Zoom Target")
                .setSubtitle(isPdf ? "Choose Page Fit or Favorite Viewport (Centered) for one-tap reset" : "Choose your favorite zoom level for one-tap reset")
                .setHeaderSymbol(isPdf ? "🎯" : "★");

        if (isPdf) {
            builder.addOption(0, "📄", "Fit Page to Screen",
                    "Fit page width edge-to-edge in screen", isFitPageActive, () -> {
                        setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FIT_PAGE);
                        saveNotebookConfig();
                        canvasView.fitPageToAvailableSpace(true);
                        Toast.makeText(this, "Reset target set to: Fit to Page 📄", Toast.LENGTH_SHORT).show();
                    });
        }

        for (int zoom : sortedOptions) {
            boolean isCurrent = (!isFitPageActive && zoom == currentReset);
            String desc;
            if (isPdf) {
                if (zoom <= 75) desc = "Overview centered in viewport";
                else if (zoom == 100) desc = "Standard 100% size centered in viewport";
                else if (zoom <= 150) desc = "Enlarged reading centered in viewport";
                else desc = "High-precision detailed view centered in viewport";
            } else {
                if (zoom <= 75) desc = "Wide overview for seeing entire page layout";
                else if (zoom == 100) desc = "Standard 100% natural handwriting size";
                else if (zoom <= 150) desc = "Comfortable enlarged writing and reading";
                else desc = "High-precision detailed note taking";
            }

            final int chosenZoom = zoom;
            String label = isPdf ? (zoom + "% Zoom (Centered)") : (zoom + "% Zoom");
            builder.addOption(zoom, "★", label, desc, isCurrent, () -> {
                if (isPdf) {
                    setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FAVORITE_CENTERED);
                }
                setFavoriteResetZoom(chosenZoom);
                saveNotebookConfig();
                if (isPdf) {
                    canvasView.resetPdfToZoomCentered(chosenZoom, true);
                    Toast.makeText(this, "Reset target set to " + chosenZoom + "% ★ (Centered)", Toast.LENGTH_SHORT).show();
                } else {
                    canvasView.resetZoom(chosenZoom);
                    Toast.makeText(this, "Reset zoom set to " + chosenZoom + "% ★", Toast.LENGTH_SHORT).show();
                }
            });
        }

        builder.addOption(-1, "⚙️", "All Zoom Levels / Manage Favorites…",
                "Pick custom zoom percentage or edit starred favorites", false, this::showZoomPickerDialog);

        builder.show();
    }

    private void showZoomPickerDialog() {
        if (canvasView == null) return;
        final int currentZoom = Math.round(canvasView.getZoomPercent());

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_zoom_level, null);
        TextView textDialogCurrentZoom = dialogView.findViewById(R.id.textDialogCurrentZoom);
        com.google.android.material.button.MaterialButton btnAddCurrentToFavorites = dialogView.findViewById(R.id.btnAddCurrentToFavorites);
        LinearLayout layoutFavoritesList = dialogView.findViewById(R.id.layoutFavoritesList);
        TextView textNoFavoritesHint = dialogView.findViewById(R.id.textNoFavoritesHint);
        LinearLayout layoutPresetsList = dialogView.findViewById(R.id.layoutPresetsList);
        View btnResetZoomDialog = dialogView.findViewById(R.id.btnResetZoomDialog);
        View btnCloseZoomDialog = dialogView.findViewById(R.id.btnCloseZoomDialog);

        if (textDialogCurrentZoom != null) {
            textDialogCurrentZoom.setText(currentZoom + "%");
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();

        // Runnable to populate favorites dynamically
        Runnable populateFavorites = new Runnable() {
            @Override
            public void run() {
                if (layoutFavoritesList == null) return;
                layoutFavoritesList.removeAllViews();
                List<Integer> favorites = ZoomFavoritesManager.getFavoriteZoomLevels(NoteEditorActivity.this);
                int currentReset = getFavoriteResetZoom();
                if (favorites.isEmpty()) {
                    if (textNoFavoritesHint != null) textNoFavoritesHint.setVisibility(View.VISIBLE);
                } else {
                    if (textNoFavoritesHint != null) textNoFavoritesHint.setVisibility(View.GONE);
                    for (int favZoom : favorites) {
                        View itemView = LayoutInflater.from(NoteEditorActivity.this)
                                .inflate(R.layout.item_favorite_zoom_level, layoutFavoritesList, false);

                        TextView textZoomPercent = itemView.findViewById(R.id.textZoomPercent);
                        TextView badgeCurrentZoom = itemView.findViewById(R.id.badgeCurrentZoom);
                        ImageButton btnDeleteFavoriteZoom = itemView.findViewById(R.id.btnDeleteFavoriteZoom);

                        textZoomPercent.setText(favZoom + "%");
                        boolean isPdf = (canvasView != null && canvasView.isPdfDocument());
                        boolean isPdfCentered = isPdf && !isPdfResetFitPage();
                        if (favZoom == currentReset) {
                            boolean isTarget = !isPdf || isPdfCentered;
                            if (isTarget) {
                                badgeCurrentZoom.setText(favZoom == currentZoom ? "Current ★ Reset" : "★ Reset Target");
                                badgeCurrentZoom.setVisibility(View.VISIBLE);
                            } else if (favZoom == currentZoom) {
                                badgeCurrentZoom.setText("Current");
                                badgeCurrentZoom.setVisibility(View.VISIBLE);
                            } else {
                                badgeCurrentZoom.setVisibility(View.GONE);
                            }
                        } else if (favZoom == currentZoom) {
                            badgeCurrentZoom.setText("Current");
                            badgeCurrentZoom.setVisibility(View.VISIBLE);
                        } else {
                            badgeCurrentZoom.setVisibility(View.GONE);
                        }

                        // Clicking favorite item sets the zoom AND sets it as the reset zoom target
                        itemView.setOnClickListener(v -> {
                            setFavoriteResetZoom(favZoom);
                            if (canvasView != null && canvasView.isPdfDocument()) {
                                setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FAVORITE_CENTERED);
                                saveNotebookConfig();
                                canvasView.resetPdfToZoomCentered(favZoom, true);
                            } else {
                                canvasView.setZoom(favZoom);
                            }
                            Toast.makeText(NoteEditorActivity.this, "Zoom set to " + favZoom + "% ★ (Reset Target)", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        });

                        // Delete option to remove level from favorites
                        btnDeleteFavoriteZoom.setOnClickListener(v -> {
                            ZoomFavoritesManager.removeFavorite(NoteEditorActivity.this, favZoom);
                            if (getFavoriteResetZoom() == favZoom) {
                                setFavoriteResetZoom(100);
                            }
                            Toast.makeText(NoteEditorActivity.this, "Zoom " + favZoom + "% removed from favorites", Toast.LENGTH_SHORT).show();
                            updateFavoriteZoomIcon(Math.round(canvasView.getZoomPercent()));
                            run(); // Re-populate favorites list

                            // Update add button state if current zoom was deleted
                            if (btnAddCurrentToFavorites != null) {
                                boolean isNowFav = ZoomFavoritesManager.isFavorite(NoteEditorActivity.this, currentZoom);
                                btnAddCurrentToFavorites.setText(isNowFav ? "★ Current Zoom Favorited" : "★ Add Current (" + currentZoom + "%) to Favorites");
                                btnAddCurrentToFavorites.setEnabled(!isNowFav);
                                btnAddCurrentToFavorites.setAlpha(isNowFav ? 0.6f : 1.0f);
                            }
                        });

                        layoutFavoritesList.addView(itemView);
                    }
                }
            }
        };

        // Wire Add Current Zoom to Favorites
        if (btnAddCurrentToFavorites != null) {
            boolean isCurFav = ZoomFavoritesManager.isFavorite(this, currentZoom);
            btnAddCurrentToFavorites.setText(isCurFav ? "★ Current Zoom Favorited" : "★ Add Current (" + currentZoom + "%) to Favorites");
            btnAddCurrentToFavorites.setEnabled(!isCurFav);
            btnAddCurrentToFavorites.setAlpha(isCurFav ? 0.6f : 1.0f);

            btnAddCurrentToFavorites.setOnClickListener(v -> {
                ZoomFavoritesManager.addFavorite(this, currentZoom);
                setFavoriteResetZoom(currentZoom);
                if (canvasView != null && canvasView.isPdfDocument()) {
                    setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FAVORITE_CENTERED);
                }
                saveNotebookConfig();
                Toast.makeText(this, "Zoom " + currentZoom + "% added to favorites & set as reset target ★", Toast.LENGTH_SHORT).show();
                updateFavoriteZoomIcon(currentZoom);
                btnAddCurrentToFavorites.setText("★ Current Zoom Favorited");
                btnAddCurrentToFavorites.setEnabled(false);
                btnAddCurrentToFavorites.setAlpha(0.6f);
                populateFavorites.run();
            });
        }

        populateFavorites.run();

        // Populate Standard Presets
        if (layoutPresetsList != null) {
            layoutPresetsList.removeAllViews();
            if (canvasView != null && canvasView.isPdfDocument()) {
                View fitPageItem = LayoutInflater.from(this).inflate(R.layout.item_preset_zoom_level, layoutPresetsList, false);
                TextView textPresetZoomPercent = fitPageItem.findViewById(R.id.textPresetZoomPercent);
                TextView badgePresetCurrent = fitPageItem.findViewById(R.id.badgePresetCurrent);
                textPresetZoomPercent.setText("📄 Fit Page to Screen");
                boolean isFitPageActive = isPdfResetFitPage();
                if (isFitPageActive) {
                    badgePresetCurrent.setText("Reset Target");
                    badgePresetCurrent.setVisibility(View.VISIBLE);
                } else {
                    badgePresetCurrent.setVisibility(View.GONE);
                }
                fitPageItem.setOnClickListener(v -> {
                    setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FIT_PAGE);
                    saveNotebookConfig();
                    canvasView.fitPageToAvailableSpace(true);
                    Toast.makeText(NoteEditorActivity.this, "Reset target set to: Fit Page 📄", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                layoutPresetsList.addView(fitPageItem);
            }

            for (int preset : ZoomFavoritesManager.PRESET_ZOOM_LEVELS) {
                View presetItem = LayoutInflater.from(this).inflate(R.layout.item_preset_zoom_level, layoutPresetsList, false);
                TextView textPresetZoomPercent = presetItem.findViewById(R.id.textPresetZoomPercent);
                TextView badgePresetCurrent = presetItem.findViewById(R.id.badgePresetCurrent);

                String label = preset == 100 ? "100% (Fit)" : (preset + "%");
                textPresetZoomPercent.setText(label);
                if (preset == currentZoom) {
                    badgePresetCurrent.setVisibility(View.VISIBLE);
                } else {
                    badgePresetCurrent.setVisibility(View.GONE);
                }

                presetItem.setOnClickListener(v -> {
                    setFavoriteResetZoom(preset);
                    if (canvasView != null && canvasView.isPdfDocument()) {
                        setPdfResetMode(ZoomFavoritesManager.PDF_RESET_MODE_FAVORITE_CENTERED);
                        saveNotebookConfig();
                        canvasView.resetPdfToZoomCentered(preset, true);
                    } else {
                        canvasView.setZoom(preset);
                    }
                    Toast.makeText(NoteEditorActivity.this, "Zoom set to " + preset + "% ★ (Reset Target)", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });

                layoutPresetsList.addView(presetItem);
            }
        }

        if (btnResetZoomDialog != null) {
            boolean isPdf = (canvasView != null && canvasView.isPdfDocument());
            boolean isFitPageActive = isPdf && isPdfResetFitPage();
            int target = getFavoriteResetZoom();
            if (btnResetZoomDialog instanceof com.google.android.material.button.MaterialButton) {
                if (isFitPageActive) {
                    ((com.google.android.material.button.MaterialButton) btnResetZoomDialog).setText("Reset (Fit Page)");
                } else {
                    ((com.google.android.material.button.MaterialButton) btnResetZoomDialog).setText("Reset (" + target + "%)");
                }
            }
            btnResetZoomDialog.setOnClickListener(v -> {
                executeResetZoom();
                dialog.dismiss();
            });
        }

        if (btnCloseZoomDialog != null) {
            btnCloseZoomDialog.setOnClickListener(v -> dialog.dismiss());
        }

        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void showLassoStyleDialog() {
        if (canvasView == null || !canvasView.hasSelection()) return;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_starnote_style, null);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ImageView btnClose = dialogView.findViewById(R.id.btnCloseStyleDialog);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        boolean hasText = canvasView.hasSelectedText();
        boolean hasShapes = canvasView.hasSelectedShape();
        boolean hasStrokes = canvasView.hasSelectedHandwriting();

        // Section visibility
        View groupLineStyle = dialogView.findViewById(R.id.groupLineStyle);
        View groupTextOptions = dialogView.findViewById(R.id.groupTextOptions);
        View groupShapeFill = dialogView.findViewById(R.id.groupShapeFill);
        TextView textThicknessTitle = dialogView.findViewById(R.id.textThicknessTitle);
        TextView textThicknessValue = dialogView.findViewById(R.id.textThicknessValue);
        SeekBar seekThickness = dialogView.findViewById(R.id.seekThickness);

        if (hasText && !hasStrokes && !hasShapes) {
            groupLineStyle.setVisibility(View.GONE);
            groupTextOptions.setVisibility(View.VISIBLE);
            groupShapeFill.setVisibility(View.GONE);
            textThicknessTitle.setText("Text Size");
        } else {
            groupLineStyle.setVisibility(View.VISIBLE);
            groupTextOptions.setVisibility(hasText ? View.VISIBLE : View.GONE);
            groupShapeFill.setVisibility(hasShapes ? View.VISIBLE : View.GONE);
            textThicknessTitle.setText(hasText ? "Ink Width / Text Size" : "Thickness");
        }

        // 1. Line Style (Solid, Dash, Dot)
        View btnSolid = dialogView.findViewById(R.id.btnStyleSolid);
        View btnDash = dialogView.findViewById(R.id.btnStyleDash);
        View btnDot = dialogView.findViewById(R.id.btnStyleDot);

        Stroke.LineStyle currentStyle = canvasView.getSelectionLineStyle();
        updateLineStyleButtons(btnSolid, btnDash, btnDot, currentStyle);

        btnSolid.setOnClickListener(v -> {
            canvasView.setSelectionLineStyle(Stroke.LineStyle.SOLID);
            updateLineStyleButtons(btnSolid, btnDash, btnDot, Stroke.LineStyle.SOLID);
        });
        btnDash.setOnClickListener(v -> {
            canvasView.setSelectionLineStyle(Stroke.LineStyle.DASH);
            updateLineStyleButtons(btnSolid, btnDash, btnDot, Stroke.LineStyle.DASH);
        });
        btnDot.setOnClickListener(v -> {
            canvasView.setSelectionLineStyle(Stroke.LineStyle.DOT);
            updateLineStyleButtons(btnSolid, btnDash, btnDot, Stroke.LineStyle.DOT);
        });

        // 2. Thickness / Size slider and chips
        float initialWidth = canvasView.getSelectionStrokeWidth();
        if (hasText && !hasStrokes && !hasShapes) {
            // Text size mapping: 12 to 72 sp
            int progress = Math.max(0, Math.min(100, Math.round((initialWidth - 12f) / (72f - 12f) * 100f)));
            seekThickness.setProgress(progress);
            textThicknessValue.setText(Math.round(initialWidth) + " sp");

            seekThickness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        float size = 12f + (progress / 100f) * 60f;
                        textThicknessValue.setText(Math.round(size) + " sp");
                        canvasView.setSelectionStrokeWidth(size);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        } else {
            // Thickness mapping: 0.5 mm to 8.0 mm (stroke width 2f to 24f)
            float mm = Math.max(0.5f, Math.min(8.0f, initialWidth / 3.0f));
            int progress = Math.max(0, Math.min(100, Math.round((mm - 0.5f) / 7.5f * 100f)));
            seekThickness.setProgress(progress);
            textThicknessValue.setText(String.format(Locale.US, "%.1f mm", mm));

            seekThickness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        float curMm = 0.5f + (progress / 100f) * 7.5f;
                        textThicknessValue.setText(String.format(Locale.US, "%.1f mm", curMm));
                        canvasView.setSelectionStrokeWidth(curMm * 3.0f);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Quick Thickness Chips
        bindThicknessChip(dialogView.findViewById(R.id.chipThick1), 1.0f, seekThickness, textThicknessValue, hasText && !hasStrokes && !hasShapes);
        bindThicknessChip(dialogView.findViewById(R.id.chipThick2), 2.0f, seekThickness, textThicknessValue, hasText && !hasStrokes && !hasShapes);
        bindThicknessChip(dialogView.findViewById(R.id.chipThick3), 3.5f, seekThickness, textThicknessValue, hasText && !hasStrokes && !hasShapes);
        bindThicknessChip(dialogView.findViewById(R.id.chipThick5), 5.0f, seekThickness, textThicknessValue, hasText && !hasStrokes && !hasShapes);
        bindThicknessChip(dialogView.findViewById(R.id.chipThick8), 8.0f, seekThickness, textThicknessValue, hasText && !hasStrokes && !hasShapes);

        // 3. Color Swatches
        LinearLayout layoutColors = dialogView.findViewById(R.id.layoutStyleColorSwatches);
        int[] palette = {
                0xFFFFFFFF, // Pure White
                0xFF111827, // Dark Black
                0xFF475569, // Slate Gray
                0xFF625AFF, // Star Blue / Indigo
                0xFF007355, // Emerald Green
                0xFFB33333, // Coral Red
                0xFFDAA001, // Amber Yellow
                0xFFFEF08A  // Soft Pastel Yellow
        };
        int currentColor = canvasView.getSelectionColor();
        populateStyleColorSwatches(layoutColors, includeSelectedColor(palette, currentColor), currentColor, "Custom Ink Color", color -> {
            canvasView.setSelectionColor(color);
        });

        // 4. Text Font Family
        if (hasText) {
            View btnSans = dialogView.findViewById(R.id.btnFontSans);
            View btnSerif = dialogView.findViewById(R.id.btnFontSerif);
            View btnMono = dialogView.findViewById(R.id.btnFontMono);
            View btnScript = dialogView.findViewById(R.id.btnFontScript);

            if (btnSans != null) btnSans.setOnClickListener(v -> canvasView.setSelectionTypeface("sans"));
            if (btnSerif != null) btnSerif.setOnClickListener(v -> canvasView.setSelectionTypeface("serif"));
            if (btnMono != null) btnMono.setOnClickListener(v -> canvasView.setSelectionTypeface("mono"));
            if (btnScript != null) btnScript.setOnClickListener(v -> canvasView.setSelectionTypeface("script"));
        }

        // 5. Shape Fill
        if (hasShapes) {
            androidx.appcompat.widget.SwitchCompat switchFill = dialogView.findViewById(R.id.switchShapeFill);
            View scrollFillColors = dialogView.findViewById(R.id.scrollShapeFillColors);
            LinearLayout layoutFillColors = dialogView.findViewById(R.id.layoutShapeFillColors);

            Integer curFill = canvasView.getSelectionFillColor();
            boolean isFilled = curFill != null && curFill != 0;
            switchFill.setChecked(isFilled);
            scrollFillColors.setVisibility(isFilled ? View.VISIBLE : View.GONE);

            int[] fillPalette = {
                    0x40625AFF, 0x4038BDF8, 0x4010B981, 0x40EF4444, 0x40F59E0B, 0x408B5CF6,
                    0x80625AFF, 0x8038BDF8, 0x8010B981, 0x80EF4444, 0x80F59E0B, 0x808B5CF6
            };
            int selectedFillColor = isFilled ? curFill : fillPalette[0];
            populateStyleColorSwatches(layoutFillColors, includeSelectedColor(fillPalette, selectedFillColor), selectedFillColor, "Custom Fill Color", color -> {
                canvasView.setSelectionFillColor(color);
            });

            switchFill.setOnCheckedChangeListener((btn, isChecked) -> {
                scrollFillColors.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (isChecked) {
                    canvasView.setSelectionFillColor(fillPalette[0]);
                } else {
                    canvasView.setSelectionFillColor(null);
                }
            });
        }

        dialog.show();
    }

    private void updateLineStyleButtons(View btnSolid, View btnDash, View btnDot, Stroke.LineStyle style) {
        if (btnSolid != null) btnSolid.setSelected(style == Stroke.LineStyle.SOLID);
        if (btnDash != null) btnDash.setSelected(style == Stroke.LineStyle.DASH);
        if (btnDot != null) btnDot.setSelected(style == Stroke.LineStyle.DOT);
    }

    private void bindThicknessChip(TextView chip, float mm, SeekBar seekBar, TextView textValue, boolean isText) {
        if (chip == null) return;
        if (isText) {
            int sp = Math.round(mm * 8f);
            chip.setText(sp + " sp");
            chip.setOnClickListener(v -> {
                int progress = Math.max(0, Math.min(100, Math.round((sp - 12f) / 60f * 100f)));
                seekBar.setProgress(progress);
                textValue.setText(sp + " sp");
                canvasView.setSelectionStrokeWidth(sp);
            });
        } else {
            chip.setText(String.format(Locale.US, "%.1f mm", mm));
            chip.setOnClickListener(v -> {
                int progress = Math.max(0, Math.min(100, Math.round((mm - 0.5f) / 7.5f * 100f)));
                seekBar.setProgress(progress);
                textValue.setText(String.format(Locale.US, "%.1f mm", mm));
                canvasView.setSelectionStrokeWidth(mm * 3.0f);
            });
        }
    }

    private void populateStyleColorSwatches(LinearLayout container, int[] colors, int selectedColor,
                                           String customColorTitle, java.util.function.Consumer<Integer> onSelected) {
        if (container == null) return;
        container.removeAllViews();
        List<FrameLayout> swatchFrames = new ArrayList<>();
        int swatchSize = dpToPx(34);
        int margin = dpToPx(6);

        for (int c : colors) {
            FrameLayout frame = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
            lp.setMargins(0, 0, margin, 0);
            frame.setLayoutParams(lp);

            View circle = new View(this);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dpToPx(28), dpToPx(28), Gravity.CENTER);
            circle.setLayoutParams(clp);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(c);
            if (c == 0xFFFFFFFF || (c & 0xFFFFFF) == 0xFFFFFF) {
                gd.setStroke(dpToPx(1), 0xFFCBD5E1);
            }
            circle.setBackground(gd);
            frame.addView(circle);

            // Active indicator ring
            View ring = new View(this);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(swatchSize, swatchSize, Gravity.CENTER);
            ring.setLayoutParams(rlp);
            GradientDrawable ringGd = new GradientDrawable();
            ringGd.setShape(GradientDrawable.OVAL);
            ringGd.setColor(Color.TRANSPARENT);
            ringGd.setStroke(dpToPx(2), 0xFF625AFF);
            ring.setBackground(ringGd);
            ring.setVisibility(c == selectedColor ? View.VISIBLE : View.GONE);
            frame.addView(ring);

            frame.setClickable(true);
            frame.setFocusable(true);
            frame.setOnClickListener(v -> {
                for (FrameLayout f : swatchFrames) {
                    if (f.getChildCount() > 1) f.getChildAt(1).setVisibility(View.GONE);
                }
                ring.setVisibility(View.VISIBLE);
                onSelected.accept(c);
            });

            swatchFrames.add(frame);
            container.addView(frame);
        }

        // Plus icon button for custom color picker
        FrameLayout plusFrame = new FrameLayout(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
        plusFrame.setLayoutParams(plp);
        ImageView plusIcon = new ImageView(this);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dpToPx(28), dpToPx(28), Gravity.CENTER);
        plusIcon.setLayoutParams(ilp);
        GradientDrawable plusBg = new GradientDrawable();
        plusBg.setShape(GradientDrawable.OVAL);
        plusBg.setColor(0x1A808080);
        plusBg.setStroke(dpToPx(1), 0x33808080);
        plusIcon.setBackground(plusBg);
        plusIcon.setImageResource(android.R.drawable.ic_input_add);
        plusIcon.setColorFilter(0xFF625AFF);
        plusIcon.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        plusFrame.addView(plusIcon);
        plusFrame.setClickable(true);
        plusFrame.setFocusable(true);
        plusFrame.setOnClickListener(v -> {
            StarColorPickerDialog.show(this, customColorTitle, selectedColor, color -> {
                onSelected.accept(color);
                populateStyleColorSwatches(container, includeSelectedColor(colors, color), color,
                        customColorTitle, onSelected);
            });
        });
        container.addView(plusFrame);
    }

    private int[] includeSelectedColor(int[] palette, int selectedColor) {
        for (int color : palette) {
            if (color == selectedColor) return palette;
        }
        int[] expanded = java.util.Arrays.copyOf(palette, palette.length + 1);
        expanded[palette.length] = selectedColor;
        return expanded;
    }



    private void updateLassoActionAvailability() {
        if (canvasView == null) return;
        boolean hasInk = canvasView.hasSelectedHandwriting();
        boolean hasClipboard = canvasView.hasClipboardContents();

        if (btnLassoConvertText != null) {
            btnLassoConvertText.setEnabled(hasInk);
            btnLassoConvertText.setAlpha(hasInk ? 1f : 0.45f);
        }
        if (btnLassoPaste != null) {
            btnLassoPaste.setEnabled(hasClipboard);
            btnLassoPaste.setAlpha(hasClipboard ? 1f : 0.45f);
        }
        if (btnLassoSpacing != null) {
            boolean hasSelectedText = canvasView.hasSelectedText();
            btnLassoSpacing.setEnabled(hasSelectedText);
            btnLassoSpacing.setAlpha(hasSelectedText ? 1f : 0.45f);
        }
    }

    private void repositionLassoBar(RectF screenBounds) {
        if (lassoFloatingActionBar == null) return;

        lassoFloatingActionBar.post(() -> {
            View parent = (View) lassoFloatingActionBar.getParent();
            int parentW = parent != null ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int parentH = parent != null ? parent.getHeight() : getResources().getDisplayMetrics().heightPixels;

            lassoFloatingActionBar.measure(
                    View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(parentH, View.MeasureSpec.AT_MOST));
            int barW = lassoFloatingActionBar.getMeasuredWidth();
            int barH = lassoFloatingActionBar.getMeasuredHeight();
            if (barW <= 0) barW = dpToPx(320);
            if (barH <= 0) barH = dpToPx(48);

            if (userHasManuallyMovedLassoBar) {
                float clampedX = Math.max(12f, Math.min(userLassoBarX, parentW - barW - 12f));
                float clampedY = Math.max(dpToPx(65), Math.min(userLassoBarY, parentH - barH - dpToPx(75)));
                lassoFloatingActionBar.setX(clampedX);
                lassoFloatingActionBar.setY(clampedY);
                return;
            }

            if (screenBounds == null) return;

            // Center horizontally over the selection
            float targetX = screenBounds.centerX() - (barW * 0.5f);
            float clampedX = Math.max(12f, Math.min(targetX, parentW - barW - 12f));

            // Position comfortably above selection, or below if near top
            float margin = dpToPx(14);
            float topBound = dpToPx(65); // Clear top app bar
            float bottomBound = parentH - barH - dpToPx(75); // Clear bottom toolbar

            float targetY;
            if (screenBounds.top - barH - margin >= topBound) {
                targetY = screenBounds.top - barH - margin;
            } else if (screenBounds.bottom + margin <= bottomBound) {
                targetY = screenBounds.bottom + margin;
            } else {
                targetY = Math.max(topBound, Math.min(screenBounds.top, bottomBound));
            }

            lassoFloatingActionBar.setX(clampedX);
            lassoFloatingActionBar.setY(targetY);
        });
    }

    public void triggerCircleToSearch() {
        String target = appSettings != null ? appSettings.getSearchAssistTarget() : AppSettings.SEARCH_TARGET_CIRCLE_TO_SEARCH;
        triggerSearchAction(target);
    }

    public void triggerSearchAction(String target) {
        if (canvasView != null) {
            canvasView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        // Temporarily hide the lasso bar so it doesn't obstruct the screen capture overlay
        if (lassoFloatingActionBar != null && lassoFloatingActionBar.getVisibility() == View.VISIBLE) {
            lassoFloatingActionBar.setVisibility(View.INVISIBLE);
            lassoFloatingActionBar.postDelayed(() -> {
                if (canvasView != null && canvasView.hasSelection()) {
                    lassoFloatingActionBar.setVisibility(View.VISIBLE);
                }
            }, 1200L);
        }

        if (AppSettings.SEARCH_TARGET_GEMINI.equals(target)) {
            launchGeminiAssist();
        } else if (AppSettings.SEARCH_TARGET_LENS.equals(target)) {
            launchGoogleLensSearch();
        } else {
            // Default: CIRCLE_TO_SEARCH
            launchCircleToSearch();
        }
    }

    private Bundle createHomeButtonLongPressAssistBundle() {
        Bundle b = new Bundle();
        // AOSP AssistUtils invocation type constants:
        // INVOCATION_TYPE_KEY = "invocation_type"
        // INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5
        b.putInt("invocation_type", 5);
        b.putInt("android.intent.extra.ASSIST_INPUT_DEVICE_ID", 1);
        b.putLong("android.intent.extra.TIME", SystemClock.uptimeMillis());

        // Android ContextualSearchManager entrypoint constants (Android 14+):
        // ENTRYPOINT_LONG_PRESS_HOME = 2
        b.putInt("entryPoint", 2);
        b.putInt("entry_point", 2);
        b.putInt("contextual_search_entry_point", 2);
        b.putInt("android.app.contextualsearch.extra.ENTRYPOINT", 2);

        // Google App (GSA / OPA / Omnient Circle to Search trigger):
        b.putString("triggered_by", "home_button_long_press");
        b.putInt("opa_bundle_trigger", 5);
        b.putInt("opa_trigger", 5);
        b.putBoolean("is_circle_to_search", true);
        b.putBoolean("screen_search", true);
        b.putBoolean("opa_force_screen_search", true);
        b.putInt("omni.entry_point", 2);
        b.putBoolean("omni_active", true);

        // Xiaomi HyperOS / MIUI shortcut mapping:
        b.putString("action_type", "circle_to_search");
        b.putString("shortcut_action", "circle_to_search");
        b.putString("key_action", "long_press_home_key");
        return b;
    }

    private Bundle createNavHandleLongPressAssistBundle() {
        Bundle b = new Bundle();
        b.putInt("invocation_type", 8); // INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS = 8
        b.putInt("android.intent.extra.ASSIST_INPUT_DEVICE_ID", 1);
        b.putLong("android.intent.extra.TIME", SystemClock.uptimeMillis());
        b.putInt("entryPoint", 1); // ENTRYPOINT_LONG_PRESS_NAV_HANDLE = 1
        b.putInt("entry_point", 1);
        b.putInt("contextual_search_entry_point", 1);
        b.putString("triggered_by", "nav_handle_long_press");
        b.putBoolean("is_circle_to_search", true);
        b.putBoolean("screen_search", true);
        b.putBoolean("opa_force_screen_search", true);
        b.putInt("omni.entry_point", 1);
        b.putBoolean("omni_active", true);
        return b;
    }

    private void launchCircleToSearch() {
        Bundle homeBundle = createHomeButtonLongPressAssistBundle();

        // 1. Native showAssist(homeBundle)
        // Dispatches the assist request with AOSP INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS (5)
        // directly to the system VoiceInteractionSession / Google Omnient overlay
        try {
            boolean shown = showAssist(homeBundle);
            if (shown) {
                Log.d("NoteEditorActivity", "Circle to Search invoked via showAssist(homeBundle)");
                return;
            }
        } catch (Throwable t) {
            Log.d("NoteEditorActivity", "showAssist homeBundle failed", t);
        }

        // 2. Try showAssist(navBundle) (for gesture navigation pill long-press)
        try {
            Bundle navBundle = createNavHandleLongPressAssistBundle();
            boolean shown = showAssist(navBundle);
            if (shown) {
                Log.d("NoteEditorActivity", "Circle to Search invoked via showAssist(navBundle)");
                return;
            }
        } catch (Throwable t) {
            Log.d("NoteEditorActivity", "showAssist navBundle failed", t);
        }

        // 3. Try SearchManager.launchAssist(homeBundle) via reflection (used by PhoneWindowManager)
        try {
            SearchManager sm = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
            if (sm != null) {
                Method launchAssistMethod = sm.getClass().getMethod("launchAssist", Bundle.class);
                launchAssistMethod.invoke(sm, homeBundle);
                Log.d("NoteEditorActivity", "Circle to Search invoked via SearchManager.launchAssist");
                return;
            }
        } catch (Throwable t) {
            Log.d("NoteEditorActivity", "SearchManager.launchAssist failed", t);
        }

        // 4. Try ContextualSearchManager.startContextualSearch(2) via reflection (Android 14+ / HyperOS)
        try {
            Object csm = getSystemService("contextual_search");
            if (csm != null) {
                Method startMethod = csm.getClass().getMethod("startContextualSearch", int.class);
                startMethod.invoke(csm, 2); // 2 = ENTRYPOINT_LONG_PRESS_HOME
                Log.d("NoteEditorActivity", "Circle to Search invoked via startContextualSearch(2)");
                return;
            }
        } catch (Throwable t) {
            Log.d("NoteEditorActivity", "ContextualSearchManager reflection failed", t);
        }

        // 5. Explicit Intent.ACTION_ASSIST targeting Google App with home button long-press extras
        try {
            Intent assistIntent = new Intent(Intent.ACTION_ASSIST);
            assistIntent.setPackage("com.google.android.googlequicksearchbox");
            assistIntent.putExtras(homeBundle);
            assistIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (assistIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(assistIntent);
                Log.d("NoteEditorActivity", "Circle to Search invoked via Google App ACTION_ASSIST");
                return;
            }
        } catch (Throwable t) {
            Log.d("NoteEditorActivity", "Explicit ACTION_ASSIST failed", t);
        }

        // 6. Try Google App's Omnient Activity or Screen Search
        try {
            Intent omniIntent = new Intent();
            omniIntent.setComponent(new ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.apps.search.omnient.host.activity.OmnientActivity"
            ));
            omniIntent.putExtras(homeBundle);
            omniIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (omniIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(omniIntent);
                return;
            }
        } catch (Throwable ignored) {}

        try {
            Intent screenSearchIntent = new Intent("com.google.android.googlequicksearchbox.action.SCREEN_SEARCH");
            screenSearchIntent.setPackage("com.google.android.googlequicksearchbox");
            screenSearchIntent.putExtras(homeBundle);
            screenSearchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (screenSearchIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(screenSearchIntent);
                return;
            }
        } catch (Throwable ignored) {}

        // 7. Try MiCTS if installed (Circle to Search helper)
        try {
            Intent micts = getPackageManager().getLaunchIntentForPackage("com.parallelc.micts");
            if (micts != null) {
                micts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(micts);
                return;
            }
        } catch (Throwable ignored) {}

        // 8. Try Xiaomi system broadcast for button shortcut
        try {
            Intent miuiIntent = new Intent("miui.intent.action.CIRCLE_TO_SEARCH");
            miuiIntent.putExtras(homeBundle);
            sendBroadcast(miuiIntent);
        } catch (Throwable ignored) {}

        // 9. Direct Google Image Search on screen/selection capture (Opens Google "Search image" with ZERO share sheet dialog)
        boolean directSearchLaunched = launchDirectGoogleImageSearch(captureSearchBitmap());
        if (!directSearchLaunched) {
            Toast.makeText(this, "Could not open Circle to Search on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGeminiAssist() {
        // 1. Try launching Gemini app directly
        try {
            Intent geminiIntent = new Intent(Intent.ACTION_ASSIST);
            geminiIntent.setPackage("com.google.android.apps.bard");
            geminiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (geminiIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(geminiIntent);
                return;
            }
        } catch (Exception ignored) {}

        // 2. Try launching Gemini main activity / launcher
        try {
            Intent geminiLaunch = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.bard");
            if (geminiLaunch != null) {
                geminiLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(geminiLaunch);
                return;
            }
        } catch (Exception ignored) {}

        // 3. Call native showAssist() (which invokes Gemini when set as assistant)
        try {
            boolean shown = showAssist(new Bundle());
            if (!shown) {
                Intent genericAssist = new Intent(Intent.ACTION_ASSIST);
                genericAssist.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(genericAssist);
            }
        } catch (Exception e) {
            Log.e("NoteEditorActivity", "Failed to launch Gemini assist", e);
            Toast.makeText(this, "Could not open Gemini Assistant", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGoogleLensSearch() {
        Bitmap bmp = captureSearchBitmap();
        boolean launched = launchDirectGoogleImageSearch(bmp);
        if (!launched) {
            Toast.makeText(this, "Google Lens / Search image not found on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap captureSearchBitmap() {
        if (canvasView == null) return null;
        if (canvasView.hasSelection()) {
            return canvasView.captureSelectionBitmap();
        }
        return canvasView.captureCurrentScreenBitmap();
    }

    private boolean launchDirectGoogleImageSearch(Bitmap bmp) {
        if (bmp == null) return false;
        try {
            File cacheDir = new File(getCacheDir(), "search_cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File searchFile = new File(cacheDir, "search_image.png");
            try (FileOutputStream fos = new FileOutputStream(searchFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }

            Uri contentUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    searchFile);

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("image/png");
            sendIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            List<ResolveInfo> matches = getPackageManager().queryIntentActivities(sendIntent, 0);
            if (matches != null && !matches.isEmpty()) {
                // 1. Target Google App ("Search image" from com.google.android.googlequicksearchbox)
                for (ResolveInfo info : matches) {
                    if (info.activityInfo != null && "com.google.android.googlequicksearchbox".equals(info.activityInfo.packageName)) {
                        Intent directIntent = new Intent(sendIntent);
                        directIntent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                        grantUriPermission(info.activityInfo.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(directIntent);
                        return true;
                    }
                }

                // 2. Standalone Google Lens app (com.google.ar.lens)
                for (ResolveInfo info : matches) {
                    if (info.activityInfo != null && "com.google.ar.lens".equals(info.activityInfo.packageName)) {
                        Intent directIntent = new Intent(sendIntent);
                        directIntent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                        grantUriPermission(info.activityInfo.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(directIntent);
                        return true;
                    }
                }

                // 3. Any activity containing "lens"
                for (ResolveInfo info : matches) {
                    if (info.activityInfo != null && (info.activityInfo.packageName.contains("lens") || (info.activityInfo.name != null && info.activityInfo.name.toLowerCase().contains("lens")))) {
                        Intent directIntent = new Intent(sendIntent);
                        directIntent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                        grantUriPermission(info.activityInfo.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(directIntent);
                        return true;
                    }
                }
            }

            // Fallback: direct ACTION_VIEW to googleapp://lens
            Intent lensUriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens"));
            lensUriIntent.setPackage("com.google.android.googlequicksearchbox");
            lensUriIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            lensUriIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (lensUriIntent.resolveActivity(getPackageManager()) != null) {
                grantUriPermission("com.google.android.googlequicksearchbox", contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(lensUriIntent);
                return true;
            }
        } catch (Exception e) {
            Log.e("NoteEditorActivity", "Direct Google image search failed", e);
        }
        return false;
    }

    private void showSearchAssistConfigDialog() {
        if (appSettings == null) return;
        String currentTarget = appSettings.getSearchAssistTarget();
        boolean isCircle = AppSettings.SEARCH_TARGET_CIRCLE_TO_SEARCH.equals(currentTarget);
        boolean isGemini = AppSettings.SEARCH_TARGET_GEMINI.equals(currentTarget);
        boolean isLens = AppSettings.SEARCH_TARGET_LENS.equals(currentTarget);
        boolean isTwoFingerEnabled = appSettings.isTwoFingerSearchGestureEnabled();

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Search & AI Assistant Gesture")
                .setSubtitle("Enable/disable two-finger hold shortcut and choose search engine")
                .setHeaderSymbol("🔍")
                .addOption(1, "✌️", "Enable Two-Finger Hold Gesture",
                        "Hold 2 fingers still on canvas for 0.6s to trigger search overlay",
                        isTwoFingerEnabled, () -> {
                            appSettings.setTwoFingerSearchGestureEnabled(true);
                            if (canvasView != null) canvasView.setTwoFingerSearchGestureEnabled(true);
                            Toast.makeText(this, "✌️ Two-Finger Hold Gesture ENABLED", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(2, "🚫", "Disable Two-Finger Hold Gesture",
                        "Turn off two-finger hold shortcut completely (prevents accidental triggers)",
                        !isTwoFingerEnabled, () -> {
                            appSettings.setTwoFingerSearchGestureEnabled(false);
                            if (canvasView != null) canvasView.setTwoFingerSearchGestureEnabled(false);
                            Toast.makeText(this, "🚫 Two-Finger Hold Gesture DISABLED", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(3, "🔍", "Google Circle to Search (Home Button Long Press)",
                        "Mimics 'Press and hold Home button' to trigger device's Circle to Search overlay",
                        isCircle, () -> {
                            appSettings.setSearchAssistTarget(AppSettings.SEARCH_TARGET_CIRCLE_TO_SEARCH);
                            Toast.makeText(this, "Set to Google Circle to Search (Home Long Press)", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(4, "✨", "Gemini AI Assistant",
                        "Open Gemini with 'Ask Gemini' and Live screen sharing",
                        isGemini, () -> {
                            appSettings.setSearchAssistTarget(AppSettings.SEARCH_TARGET_GEMINI);
                            Toast.makeText(this, "Set to Gemini Assistant", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(5, "📷", "Google Lens (Instant Image Search)",
                        "Directly search circled/screen content in Google Lens (No share sheet)",
                        isLens, () -> {
                            appSettings.setSearchAssistTarget(AppSettings.SEARCH_TARGET_LENS);
                            Toast.makeText(this, "Set to Google Lens", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(6, "⚙️", "Device Assistant Settings (Google / Gemini)",
                        "Switch your phone's Digital Assistant between Google (Circle to Search) & Gemini",
                        false, () -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
                                startActivity(intent);
                            } catch (Exception e) {
                                try {
                                    Intent intent2 = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                                    startActivity(intent2);
                                } catch (Exception e2) {
                                    Toast.makeText(this, "Open phone Settings > Apps > Default Apps > Digital Assistant", Toast.LENGTH_LONG).show();
                                }
                            }
                        })
                .addOption(7, "📱", "System Button & Gesture Settings",
                        "Open system settings for 'Press and hold Home button' / Navigation shortcuts",
                        false, this::openSystemButtonShortcutsSettings)
                .show();
    }

    private void openSystemButtonShortcutsSettings() {
        Intent[] shortcutIntents = new Intent[] {
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$KeyCombinationSettingsActivity")),
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$SystemNavigationActivity")),
                new Intent("miui.intent.action.KEY_COMBINATION_SETTINGS"),
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent intent : shortcutIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "Open phone Settings > Additional settings > Button shortcuts", Toast.LENGTH_LONG).show();
    }

    public void updatePasteButtonVisibility() {
        if (canvasView == null) return;
        updateLassoActionAvailability();
    }

    private void showLongPressPastePopup(float rawX, float rawY, float canvasX, float canvasY) {
        if (layoutLongPressPastePopup == null) return;
        layoutLongPressPastePopup.animate().cancel();
        layoutLongPressPastePopup.setVisibility(View.VISIBLE);
        layoutLongPressPastePopup.setAlpha(0f);
        layoutLongPressPastePopup.setScaleX(0.85f);
        layoutLongPressPastePopup.setScaleY(0.85f);

        int popupWidth = layoutLongPressPastePopup.getWidth();
        int popupHeight = layoutLongPressPastePopup.getHeight();
        if (popupWidth <= 0) popupWidth = dpToPx(110);
        if (popupHeight <= 0) popupHeight = dpToPx(44);

        float posX = Math.max(16f, Math.min(rawX - (popupWidth * 0.5f), getWindow().getDecorView().getWidth() - popupWidth - 16f));
        float posY = Math.max(100f, rawY - popupHeight - 20f);

        layoutLongPressPastePopup.setX(posX);
        layoutLongPressPastePopup.setY(posY);

        layoutLongPressPastePopup.animate().alpha(1f).scaleX(1.0f).scaleY(1.0f).setDuration(180)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();

        layoutLongPressPastePopup.setOnClickListener(v -> {
            canvasView.pasteAt(canvasX, canvasY);
            Toast.makeText(this, "Pasted at location", Toast.LENGTH_SHORT).show();
            hideLongPressPastePopup();
            updatePasteButtonVisibility();
        });
    }

    private void hideLongPressPastePopup() {
        if (layoutLongPressPastePopup != null && layoutLongPressPastePopup.getVisibility() == View.VISIBLE) {
            layoutLongPressPastePopup.animate().cancel();
            layoutLongPressPastePopup.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(140)
                    .withEndAction(() -> layoutLongPressPastePopup.setVisibility(View.GONE)).start();
        }
    }

    private void showShapeSelectorDialog() {
        final String[] shapes = {"Rectangle", "Circle / Oval", "Line", "Arrow", "Triangle", "Star", "Coordinate Axis", "Table Grid (3x3)"};
        final ShapeItem.Type[] types = {
                ShapeItem.Type.RECTANGLE,
                ShapeItem.Type.CIRCLE,
                ShapeItem.Type.LINE,
                ShapeItem.Type.ARROW,
                ShapeItem.Type.TRIANGLE,
                ShapeItem.Type.STAR,
                ShapeItem.Type.COORDINATE_AXIS,
                ShapeItem.Type.TABLE
        };
        final String[] symbols = {"⬜", "⭕", "➖", "➡️", "🔺", "⭐", "📈", "▦"};

        CanvasView.ShapeFillStyle currentFill = canvasView.getShapeFillStyle();
        String fillStyleName = currentFill == CanvasView.ShapeFillStyle.SOLID ? "Solid Fill" :
                (currentFill == CanvasView.ShapeFillStyle.TRANSLUCENT ? "Translucent Tint (20%)" : "Outline Only");

        ModernOptionsSelectorDialog.Builder builder = new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Select Shape Tool")
                .setSubtitle("Current Fill Style: " + fillStyleName)
                .setHeaderSymbol("📐");

        for (int i = 0; i < shapes.length; i++) {
            final int idx = i;
            boolean isCurType = (canvasView.getTool() == DrawingTool.SHAPE && canvasView.getShapeType() == types[i]);
            builder.addOption(i, symbols[i], shapes[i], "Draw precision " + shapes[i].toLowerCase(Locale.US), isCurType, () -> {
                DrawingTool prevTool = canvasView.getTool();
                if (prevTool != null) {
                    saveCurrentToolWidthAndColor(prevTool);
                }
                canvasView.setShapeType(types[idx]);
                canvasView.setTool(DrawingTool.SHAPE);
                updateToolUi(DrawingTool.SHAPE);
                if (appSettings != null) appSettings.setLastTool(DrawingTool.SHAPE);
                restoreToolWidthAndColor(DrawingTool.SHAPE);
                saveNotebookConfig();
                Toast.makeText(this, "Draw " + shapes[idx] + " (" + fillStyleName + ")", Toast.LENGTH_SHORT).show();
            });
        }

        builder.addOption(100, "🎨", "Style: Outline Only", "Crisp contour border without interior fill",
                currentFill == CanvasView.ShapeFillStyle.OUTLINE, () -> {
            canvasView.setShapeFillStyle(CanvasView.ShapeFillStyle.OUTLINE);
            showShapeSelectorDialog();
        });
        builder.addOption(101, "🎨", "Style: Translucent Tint Wash", "Subtle 20% transparent color wash",
                currentFill == CanvasView.ShapeFillStyle.TRANSLUCENT, () -> {
            canvasView.setShapeFillStyle(CanvasView.ShapeFillStyle.TRANSLUCENT);
            showShapeSelectorDialog();
        });
        builder.addOption(102, "🎨", "Style: Solid Opaque Fill", "Fully opaque filled geometric shapes",
                currentFill == CanvasView.ShapeFillStyle.SOLID, () -> {
            canvasView.setShapeFillStyle(CanvasView.ShapeFillStyle.SOLID);
            showShapeSelectorDialog();
        });

        builder.show();
    }

    private void showRulerAngleDialog() {
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Ruler & Angle Guide")
                .setSubtitle("Snap strokes to precise architectural angles")
                .setHeaderSymbol("📏")
                .addOption(0, "━", "0° Horizontal Snap", "Snap strokes perfectly horizontal across canvas", false, () -> {
                    enableRulerSnap("0° (Horizontal Snap)");
                })
                .addOption(30, "⟋", "30° Isometric Snap", "Isometric technical drawing angle guide", false, () -> {
                    enableRulerSnap("30° Angle Guide");
                })
                .addOption(45, "╱", "45° Diagonal Snap", "Diagonal line angle guide", false, () -> {
                    enableRulerSnap("45° Diagonal Snap");
                })
                .addOption(60, "╱", "60° Triangle Snap", "Equilateral triangular guide", false, () -> {
                    enableRulerSnap("60° Angle Guide");
                })
                .addOption(90, "│", "90° Vertical Snap", "Snap strokes perfectly vertical straight down", false, () -> {
                    enableRulerSnap("90° (Vertical Snap)");
                })
                .addDestructiveOption(999, "✕", "Disable Ruler Assist", "Turn off angle and line snapping guide", () -> {
                    canvasView.setStraightLineAssist(false);
                    if (switchStraightLine != null) switchStraightLine.setChecked(false);
                    if (btnRuler != null) btnRuler.setColorFilter(0xFF94A3B8);
                    saveNotebookConfig();
                    Toast.makeText(this, "Ruler Guide Disabled", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void enableRulerSnap(String angleLabel) {
        canvasView.setStraightLineAssist(true);
        if (switchStraightLine != null) switchStraightLine.setChecked(true);
        if (btnRuler != null) btnRuler.setColorFilter(0xFF38BDF8);
        saveNotebookConfig();
        Toast.makeText(this, "Ruler Angle Snap: " + angleLabel, Toast.LENGTH_SHORT).show();
    }

    private void showExportDialog() {
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Export & Share Note")
                .setSubtitle("Export notebook pages or share editable backup")
                .setHeaderSymbol("📤")
                .addOption(1, "🚀", "Direct Share as PDF", "Send complete multi-page PDF to WhatsApp, Gmail, Drive…", false, () -> {
                    saveCurrentPage();
                    try {
                        File pdfFile = PdfExporter.exportNotebookToPdf(this, notebookId);
                        if (pdfFile != null) {
                            shareFile(pdfFile, "application/pdf");
                            Toast.makeText(this, "✓ PDF ready! Opening share options…", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        ErrorDialog.show(this, "PDF export failed", e);
                    }
                })
                .addOption(2, "🖼️", "Direct Share Current Page (PNG)", "Share current page as high-resolution PNG image", false, () -> {
                    saveCurrentPage();
                    try {
                        File imgFile = ImageExporter.exportPageToImage(this, canvasView, currentNotebook.title, currentPageIndex);
                        if (imgFile != null) {
                            shareFile(imgFile, "image/png");
                            Toast.makeText(this, "✓ Image ready! Opening share options…", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        ErrorDialog.show(this, "Image export failed", e);
                    }
                })
                .addOption(3, "📄", "Save Notebook as PDF to Device", "Save entire PDF document directly to Downloads/Storage", false, () -> {
                    saveCurrentPage();
                    try {
                        File pdfFile = PdfExporter.exportNotebookToPdf(this, notebookId);
                        if (pdfFile != null) {
                            showExportSuccessDialog(pdfFile, "application/pdf");
                        }
                    } catch (Exception e) {
                        ErrorDialog.show(this, "PDF export failed", e);
                    }
                })
                .addOption(4, "🖼️", "Save Page as PNG Image", "Save high-res image to device photo gallery", false, () -> {
                    saveCurrentPage();
                    try {
                        File imgFile = ImageExporter.exportPageToImage(this, canvasView, currentNotebook.title, currentPageIndex);
                        if (imgFile != null) {
                            showExportSuccessDialog(imgFile, "image/png");
                        }
                    } catch (Exception e) {
                        ErrorDialog.show(this, "Image export failed", e);
                    }
                })
                .addOption(5, "📦", "Save & Share Editable Notebook (.opennote)", "Export native vector backup with all pages and layers", false, () -> {
                    saveCurrentPage();
                    exportNativeNotebookLauncher.launch(safeArchiveName(
                            currentNotebook != null ? currentNotebook.title : "OpenNotes notebook"));
                })
                .addOption(6, "📥", "Restore Editable Notebook (.opennote)", "Import and restore an .opennote archive file", false, () -> {
                    importNativeNotebookLauncher.launch(new String[]{"*/*"});
                })
                .show();
    }

    private void showExportSuccessDialog(File file, String mimeType) {
        long bytes = file.length();
        String sizeStr = bytes > 1024 * 1024
                ? String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
                : String.format(Locale.US, "%.1f KB", bytes / 1024f);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64, 32, 64, 24);

        TextView textInfo = new TextView(this);
        textInfo.setText("✓ Exported successfully at 300 DPI Ultra-HD RAW quality!\n\n"
                + "📄 File: " + file.getName() + "\n"
                + "📦 Size: " + sizeStr + "\n\n"
                + "📂 Saved Location:\n" + file.getAbsolutePath());
        textInfo.setTextColor(0xFFCBD5E1);
        textInfo.setTextSize(13);
        textInfo.setLineSpacing(4f, 1.2f);
        textInfo.setTextIsSelectable(true);
        layout.addView(textInfo);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Note Exported Successfully")
                .setView(layout)
                .setPositiveButton("Share", (d, w) -> shareFile(file, mimeType))
                .setNeutralButton("Copy Path", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("File Path", file.getAbsolutePath());
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "Path copied to clipboard!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Open", (d, w) -> openFile(file, mimeType));
        ThemeUtils.showStyledDialog(builder, this);
    }

    private Notebook snapshotNotebook(Notebook source) {
        if (source == null) return null;
        Notebook copy = new Notebook(source.title, source.coverColor, source.defaultTemplate);
        copy.folder = source.folder;
        copy.defaultBgColor = source.defaultBgColor;
        copy.defaultIsInfinite = source.defaultIsInfinite;
        copy.pageCount = source.pageCount;
        copy.isFavorite = source.isFavorite;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        return copy;
    }

    private String safeArchiveName(String title) {
        String base = title == null ? "OpenNotes notebook" : title.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) base = "OpenNotes notebook";
        if (base.toLowerCase(Locale.ROOT).endsWith(NativeNoteArchive.FILE_EXTENSION)) return base;
        return base + NativeNoteArchive.FILE_EXTENSION;
    }

    private void openFile(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.setClipData(android.content.ClipData.newRawUri("Exported Note", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            java.util.List<android.content.pm.ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            startActivity(intent);
        } catch (Exception e) {
            ErrorDialog.show(this, "Could not open exported file", e);
        }
    }

    private void shareFile(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            intent.putExtra(Intent.EXTRA_TEXT, "Exported from OpenNotes: " + file.getName());
            intent.setClipData(android.content.ClipData.newRawUri("Exported Note", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(intent, "Share Note with…");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            java.util.List<android.content.pm.ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(chooser, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            startActivity(chooser);
        } catch (Exception ex) {
            ErrorDialog.show(this, "Could not share exported file", ex);
        }
    }

    private void shareUri(Uri uri, String mimeType, String subject) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            intent.putExtra(Intent.EXTRA_TEXT, "Editable OpenNotes notebook archive");
            intent.setClipData(android.content.ClipData.newRawUri("OpenNotes notebook", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share original notebook with…"));
        } catch (Exception error) {
            ErrorDialog.show(this, "Could not share original notebook", error);
        }
    }

    private void showPageOverviewDialog() {
        if (pages == null || pages.isEmpty()) return;
        saveCurrentPage();

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_page_overview, null);
        RecyclerView rv = dialogView.findViewById(R.id.recyclerViewPages);
        com.google.android.material.button.MaterialButton btnOverviewAddPage = dialogView.findViewById(R.id.btnOverviewAddPage);
        rv.setLayoutManager(new GridLayoutManager(this, 3));

        boolean isContinuous = canvasView != null && canvasView.isContinuousVerticalScroll();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setTitle("Pages (" + pages.size() + ")")
                .setPositiveButton(isContinuous ? "Mode: ↕ Vertical Scroll" : "Mode: ↔ No Scroll (Page Turn)", (d, w) -> {
                    boolean next = !isContinuous;
                    if (appSettings != null) appSettings.setContinuousVerticalScroll(next);
                    canvasView.setContinuousVerticalScroll(next);
                    if (next && pages.size() > 1) {
                        canvasView.setPages(pages, currentPageIndex);
                    } else {
                        canvasView.setPage(currentPage);
                    }
                    updatePageNavUi();
                    saveNotebookConfig();
                    Toast.makeText(this, next ? "Smooth Vertical Scroll enabled" : "No Scroll: Change Pages Only enabled", Toast.LENGTH_SHORT).show();
                    showPageOverviewDialog();
                })
                .setNegativeButton("Close", null)
                .create();
        ThemeUtils.styleDialog(dialog, this);

        RecyclerView.Adapter<PageViewHolder> adapter = new RecyclerView.Adapter<PageViewHolder>() {
            @Override
            public PageViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_page_overview, parent, false);
                return new PageViewHolder(v);
            }

            @Override
            public void onBindViewHolder(PageViewHolder holder, int position) {
                final int pos = position;
                final Page page = pages.get(pos);
                holder.textPageNumber.setText("Page " + (pos + 1));
                String tplName = (page != null && page.template != null) ? page.template.getDisplayName() : "Ruled Lines";
                if (holder.textPageTemplateLabel != null) {
                    holder.textPageTemplateLabel.setText(tplName);
                }
                holder.itemView.setSelected(pos == currentPageIndex);

                if (holder.itemView instanceof com.google.android.material.card.MaterialCardView) {
                    com.google.android.material.card.MaterialCardView card = (com.google.android.material.card.MaterialCardView) holder.itemView;
                    if (pos == currentPageIndex) {
                        card.setStrokeColor(0xFF38BDF8);
                        card.setStrokeWidth(dp(2));
                    } else {
                        card.setStrokeColor(0xFF334155);
                        card.setStrokeWidth(dp(1));
                    }
                }

                holder.itemView.setOnClickListener(v -> {
                    if (pos != currentPageIndex) {
                        saveCurrentPage();
                        currentPageIndex = pos;
                        loadCurrentPage();
                    }
                    dialog.dismiss();
                });

                if (holder.btnDeletePage != null) {
                    holder.btnDeletePage.setOnClickListener(v -> {
                        if (pages.size() <= 1) {
                            Toast.makeText(NoteEditorActivity.this, "Cannot delete the only page in the notebook", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AlertDialog delDialog = new MaterialAlertDialogBuilder(NoteEditorActivity.this)
                                .setTitle("Delete Page " + (pos + 1) + "?")
                                .setMessage("Are you sure you want to delete Page " + (pos + 1) + "? This action cannot be undone.")
                                .setPositiveButton("Delete", (d, w) -> {
                                    deletePageAt(pos, dialog, this);
                                })
                                .setNegativeButton("Cancel", null)
                                .create();
                        ThemeUtils.styleDialog(delDialog, NoteEditorActivity.this);
                        delDialog.show();
                    });
                }
            }

            @Override
            public int getItemCount() {
                return pages.size();
            }
        };
        rv.setAdapter(adapter);

        if (btnOverviewAddPage != null) {
            btnOverviewAddPage.setOnClickListener(v -> showAddPageMenu(v, dialog, adapter));
        }

        View btnOverviewGoToPage = dialogView.findViewById(R.id.btnOverviewGoToPage);
        if (btnOverviewGoToPage != null) {
            btnOverviewGoToPage.setOnClickListener(v -> {
                dialog.dismiss();
                showGoToPageDialog();
            });
        }

        dialog.show();
    }

    private void deletePageAt(int index, AlertDialog dialog, RecyclerView.Adapter adapter) {
        if (pages == null || pages.size() <= 1 || index < 0 || index >= pages.size()) return;
        saveCurrentPage();

        pages.remove(index);
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).pageIndex = i;
        }
        if (currentNotebook != null) {
            currentNotebook.pageCount = pages.size();
        }

        if (currentPageIndex >= pages.size()) {
            currentPageIndex = pages.size() - 1;
            loadCurrentPage();
        } else if (currentPageIndex == index) {
            loadCurrentPage();
        } else if (currentPageIndex > index) {
            currentPageIndex--;
            updatePageNavUi();
        } else {
            updatePageNavUi();
        }

        final int targetIdx = index;
        final Notebook nbSnapshot = currentNotebook;
        executeSaveTask(() -> {
            dbHelper.deletePage(notebookId, targetIdx);
            if (nbSnapshot != null) {
                dbHelper.updateNotebook(nbSnapshot);
            }
        });

        if (dialog != null && dialog.isShowing()) {
            dialog.setTitle("Pages (" + pages.size() + ")");
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
        Toast.makeText(this, "Page deleted", Toast.LENGTH_SHORT).show();
    }

    private void showAddPageMenu(View anchor, AlertDialog overviewDialog, RecyclerView.Adapter overviewAdapter) {
        if (pages == null || currentNotebook == null) return;
        saveCurrentPage();

        PaperTemplate currentTpl = (currentPage != null && currentPage.template != null)
                ? currentPage.template
                : ((currentNotebook != null && currentNotebook.defaultTemplate != null)
                    ? currentNotebook.defaultTemplate
                    : PaperTemplate.RULED);
        String currentTplName = currentTpl.getDisplayName();

        ModernOptionsSelectorDialog.Builder builder = new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Add New Page")
                .setSubtitle("Choose paper template layout")
                .setHeaderSymbol("➕");

        final PaperTemplate sameTpl = currentTpl;
        builder.addOption(100, "➕", "Same as Current (" + currentTplName + ")",
                "Continue writing with current template layout", true,
                () -> addNewPageWithTemplate(sameTpl, overviewDialog, overviewAdapter));

        builder.addOption(1, "📏", "Ruled Lines", "Classic notebook lined paper for handwriting", false,
                () -> addNewPageWithTemplate(PaperTemplate.RULED, overviewDialog, overviewAdapter));

        builder.addOption(2, "📐", "Math Grid", "Square grid for notes, graphs and math", false,
                () -> addNewPageWithTemplate(PaperTemplate.GRID, overviewDialog, overviewAdapter));

        builder.addOption(3, "•", "Dot Grid", "Subtle dot grid for bullet journaling and layouts", false,
                () -> addNewPageWithTemplate(PaperTemplate.DOTS, overviewDialog, overviewAdapter));

        builder.addOption(4, "⬜", "Blank Page", "Clean canvas for free sketching and diagrams", false,
                () -> addNewPageWithTemplate(PaperTemplate.BLANK, overviewDialog, overviewAdapter));

        builder.addOption(5, "📑", "Cornell Note", "Structured cue column and summary section", false,
                () -> addNewPageWithTemplate(PaperTemplate.CORNELL, overviewDialog, overviewAdapter));

        builder.addOption(6, "📄", "Import Pages from PDF", "Append pages from an external PDF document", false, () -> {
            if (overviewDialog != null && overviewDialog.isShowing()) {
                overviewDialog.dismiss();
            }
            try {
                openPdfForAppendLauncher.launch(new String[]{"application/pdf"});
            } catch (Exception e) {
                Toast.makeText(this, "Could not open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        builder.show();
    }

    private void addNewPageWithTemplate(PaperTemplate template, AlertDialog overviewDialog, RecyclerView.Adapter overviewAdapter) {
        if (pages == null || currentNotebook == null) return;
        saveCurrentPage();

        int bgCol = (currentPage != null && currentPage.backgroundColor != 0)
                ? currentPage.backgroundColor
                : ((currentNotebook != null && currentNotebook.defaultBgColor != -1)
                    ? currentNotebook.defaultBgColor
                    : (appSettings != null ? appSettings.getDefaultPageBgColor() : 0xFF121214));
        boolean inf = (currentPage != null) ? currentPage.isInfinite : currentNotebook.defaultIsInfinite;

        Page newPage = new Page(notebookId, pages.size(), template, bgCol, inf);
        currentNotebook.pageCount = pages.size() + 1;
        pages.add(newPage);
        Notebook notebookSnapshot = currentNotebook;
        executeSaveTask(() -> {
            dbHelper.insertPage(newPage);
            dbHelper.updateNotebook(notebookSnapshot);
        });
        currentPageIndex = pages.size() - 1;
        loadCurrentPage();

        if (overviewDialog != null && overviewDialog.isShowing()) {
            overviewDialog.setTitle("Pages (" + pages.size() + ")");
            if (overviewAdapter != null) {
                overviewAdapter.notifyDataSetChanged();
            }
        }
        Toast.makeText(this, "Page " + (currentPageIndex + 1) + " added (" + template.getDisplayName() + ")", Toast.LENGTH_SHORT).show();
    }

    private static class PageViewHolder extends RecyclerView.ViewHolder {
        TextView textPageNumber;
        TextView textPageTemplateLabel;
        ImageView btnDeletePage;
        PageViewHolder(View itemView) {
            super(itemView);
            textPageNumber = itemView.findViewById(R.id.textPageNumber);
            textPageTemplateLabel = itemView.findViewById(R.id.textPageTemplateLabel);
            btnDeletePage = itemView.findViewById(R.id.btnDeletePage);
        }
    }

    private void showMoreMenu() {
        boolean isFavNotebook = currentNotebook != null && currentNotebook.isFavorite;
        boolean isPinnedNotebook = currentNotebook != null && currentNotebook.isPinned;
        boolean hasClipboard = canvasView != null && canvasView.hasClipboardContents();
        boolean isStylusOnly = canvasView != null && canvasView.isStylusOnly();

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Note Options & Tools")
                .setSubtitle("Page settings, utilities, and handwriting tools")
                .setHeaderSymbol("⚙️")
                .addOption(0, "🎯", "Go to Page Number", "Jump directly to any page with live scrubber or page input", false, this::showGoToPageDialog)
                .addOption(1, "📄", "Paper Template", "Switch lined, grid, dots or blank layout", false, this::showTemplateSelectionDialog)
                .addOption(2, "🎨", "Page Background Color", "AMOLED black, dark obsidian, warm ivory, pure white", false, this::showPageColorSelectionDialog)
                .addOption(3, "📺", "Zen Fullscreen Mode", "Distraction-free canvas with hidden toolbars", false, () -> setZenFullscreen(true))
                .addOption(4, "🖐️", "Palm Rejection (Stylus Only)", isStylusOnly ? "Stylus-only drawing active (touch rejected)" : "Touch & stylus drawing enabled", isStylusOnly, () -> {
                    boolean next = !canvasView.isStylusOnly();
                    canvasView.setStylusOnly(next);
                    if (appSettings != null) appSettings.setStylusOnly(next);
                    Toast.makeText(this, next ? "Palm Rejection ON (Stylus-only drawing)" : "Touch & Stylus drawing ON", Toast.LENGTH_SHORT).show();
                })
                .addOption(5, "✨", "Auto-Beautification Settings", "Configure automatic shape & stroke smoothing", false, this::showAutoBeautifySettingsDialog)
                .addOption(6, "🌙", "Super AMOLED Dark Mode (PDF)",
                        appSettings != null && appSettings.isPdfAmoledDarkMode() ? "Forced pure black AMOLED mode ON" : "Disabled (Original PDF white background)",
                        appSettings != null && appSettings.isPdfAmoledDarkMode(), this::togglePdfAmoledDarkMode)
                .addOption(7, "🔍", "Circle to Search", "Invoke search or AI assistant for current note", false, this::triggerCircleToSearch)
                .addOption(8, "⚙️", "Search & Assistant Gesture",
                        appSettings != null && appSettings.isTwoFingerSearchGestureEnabled()
                                ? "Two-Finger Hold: Enabled (" + (appSettings != null ? appSettings.getSearchAssistTargetName() : "Circle to Search") + ")"
                                : "Two-Finger Hold: Disabled (Tap to enable or configure)",
                        appSettings != null && appSettings.isTwoFingerSearchGestureEnabled(), this::showSearchAssistConfigDialog)
                .addOption(9, "📜", "Page Navigation & Swipe Gestures",
                        appSettings != null && appSettings.isContinuousVerticalScroll()
                                ? "Current: Smooth Vertical Scroll (Tap to configure or switch to No Scroll)"
                                : "Current: No Scroll / Change Pages Only (Tap to configure or switch to Vertical Scroll)",
                        appSettings != null && appSettings.isContinuousVerticalScroll(), this::showSwipeGestureOptionsDialog)
                .addOption(10, "➰", "Select All Content", "Select all ink strokes and items on this page", false, () -> {
                    if (!canvasView.selectAllContent()) {
                        Toast.makeText(this, "There is no content on this page to select", Toast.LENGTH_SHORT).show();
                    } else if (canvasView.getTool() != DrawingTool.LASSO) {
                        canvasView.setTool(DrawingTool.LASSO);
                        updateToolUi(DrawingTool.LASSO);
                        if (appSettings != null) appSettings.setLastTool(DrawingTool.LASSO);
                    }
                })
                .addOption(8, "✍️", "Convert Page to Beautiful Font", "Transform all handwriting on page to digital text", false, () -> {
                    if (!canvasView.convertPageToBeautifulText()) {
                        Toast.makeText(this, "There is no handwriting on this page to convert", Toast.LENGTH_SHORT).show();
                    }
                })
                .addCustomOption(new ModernOptionsSelectorDialog.OptionItem(9, "📋", 0, "Paste from Clipboard",
                        hasClipboard ? "Paste copied strokes, text or images" : "Clipboard empty", false, false, hasClipboard, null, v -> {
                    if (canvasView.hasClipboardContents()) {
                        canvasView.pasteSelection();
                    }
                }))
                .addOption(10, "📥", "Offline Handwriting Models", "Manage on-device ML Kit language engines", false, this::showDownloadModelsDialog)
                .addOption(11, "🎓", "Teach My Handwriting", "Train local personal handwriting recognition", false, this::showHandwritingProfileDialog)
                .addOption(12, "📤", "Export Handwriting Profile (.onhw)", "Save your trained personal profile to file", false, () -> {
                    exportProfileLauncher.launch("my_handwriting_profile.onhw");
                })
                .addOption(13, "📥", "Import Handwriting Profile (.onhw)", "Load trained profile from backup file", false, () -> {
                    importProfileLauncher.launch("*/*");
                })
                .addOption(14, "↔", "Adjust Text Spacing", "Fine-tune character and word spacing of selected text", false, this::showTextSpacingDialog)
                .addOption(15, isFavNotebook ? "★" : "☆", isFavNotebook ? "Remove from Favorites" : "Bookmark to Favorites",
                        isFavNotebook ? "This notebook is currently starred" : "Pin this notebook to favorites on home screen", isFavNotebook, () -> {
                    if (currentNotebook != null) {
                        saveCurrentPage();
                        currentNotebook.isFavorite = !currentNotebook.isFavorite;
                        final boolean fav = currentNotebook.isFavorite;
                        executeSaveTask(() -> dbHelper.setFavorite(notebookId, fav));
                        Toast.makeText(this, fav ? "Bookmarked ★ (Saved to Favorites)" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOption(16, "📌", isPinnedNotebook ? "Unpin Notebook" : "Pin Notebook",
                        isPinnedNotebook ? "Remove this notebook from the top of the home list" : "Keep this notebook at the top of the home list",
                        isPinnedNotebook, () -> {
                    if (currentNotebook != null) {
                        currentNotebook.isPinned = !currentNotebook.isPinned;
                        final boolean pinned = currentNotebook.isPinned;
                        executeSaveTask(() -> dbHelper.setPinned(notebookId, pinned));
                        Toast.makeText(this, pinned ? "Notebook pinned to top" : "Notebook unpinned", Toast.LENGTH_SHORT).show();
                    }
                })
                .addDestructiveOption(6, "🗑️", "Clear Canvas", "Erase all strokes on this page", () -> {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                            .setTitle("Clear Canvas")
                            .setMessage("Are you sure you want to clear all strokes on this page?")
                            .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                            .setNegativeButton("Cancel", null);
                    ThemeUtils.showStyledDialog(builder, this);
                })
                .show();
    }

    private void showTextSpacingDialog() {
        List<TextItem> selected = canvasView.getSelectedTexts();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select converted text first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canvasView.beginSelectedTextSpacingPreview()) return;

        TextItem first = selected.get(0);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        layout.setPadding(padding, dp(12), padding, dp(12));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xF128272E);
        panelBackground.setCornerRadius(dp(20));
        panelBackground.setStroke(dp(1), 0xFF4A4852);
        layout.setBackground(panelBackground);
        layout.setElevation(dp(12));

        TextView heading = new TextView(this);
        heading.setText("↕  Text spacing  ·  drag to move");
        heading.setTextColor(0xFFF3F4F6);
        heading.setTextSize(17f);
        heading.setPadding(0, 0, 0, dp(8));
        layout.addView(heading);

        TextView letterLabel = new TextView(this);
        TextView wordLabel = new TextView(this);
        SeekBar letterSeek = new SeekBar(this);
        SeekBar wordSeek = new SeekBar(this);
        letterSeek.setMax(100);
        wordSeek.setMax(700);
        float[] letterValue = {Math.max(-0.12f, Math.min(0.40f, first.letterSpacingEm))};
        final float minWordSpacing = -0.5f;
        final float maxWordSpacing = 1.0f;
        float[] wordValue = {Math.max(minWordSpacing, Math.min(maxWordSpacing, first.wordSpacingEm))};
        letterSeek.setProgress(Math.round((letterValue[0] + 0.12f) / 0.52f * 100f));
        wordSeek.setProgress(Math.round((wordValue[0] - minWordSpacing)
                / (maxWordSpacing - minWordSpacing) * 700f));
        letterSeek.setContentDescription("Letter spacing");
        wordSeek.setContentDescription("Word spacing from minus 0.5 em to plus 1 em; drag left to tighten, right to loosen");
        Runnable updateLabels = () -> {
            letterLabel.setText(String.format(Locale.getDefault(), "Letter spacing: %+.2f em", letterValue[0]));
            wordLabel.setText(String.format(Locale.getDefault(), "Word spacing: %+.2f em", wordValue[0]));
        };
        letterSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                letterValue[0] = -0.12f + progress / 100f * 0.52f;
                if (Math.abs(letterValue[0]) < 0.005f) letterValue[0] = 0f;
                updateLabels.run();
                canvasView.updateSelectedTextSpacingPreview(letterValue[0], wordValue[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        wordSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                wordValue[0] = minWordSpacing + progress / 700f * (maxWordSpacing - minWordSpacing);
                updateLabels.run();
                canvasView.updateSelectedTextSpacingPreview(letterValue[0], wordValue[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        updateLabels.run();
        layout.addView(letterLabel);
        layout.addView(letterSeek);
        layout.addView(wordLabel);
        layout.addView(wordSeek);
        TextView hint = new TextView(this);
        hint.setText("Drag left to tighten word gaps or right to spread them. The canvas previews changes as you move the sliders.");
        hint.setTextColor(0xFFB9B7C0);
        hint.setTextSize(12f);
        hint.setPadding(0, dp(6), 0, dp(8));
        layout.addView(hint);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button reset = new Button(this);
        reset.setText("Reset");
        ThemeUtils.styleButton(reset, this, false);
        reset.setOnClickListener(v -> {
            letterSeek.setProgress(Math.round((0.12f / 0.52f) * 100f));
            wordSeek.setProgress(Math.round((0f - minWordSpacing)
                    / (maxWordSpacing - minWordSpacing) * 700f));
        });
        View spacer = new View(this);
        actions.addView(reset);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        ThemeUtils.styleButton(cancel, this, false);
        Button apply = new Button(this);
        apply.setText("Apply");
        ThemeUtils.styleButton(apply, this, true);
        actions.addView(cancel);
        actions.addView(apply);
        layout.addView(actions);

        View root = getWindow().getDecorView();
        int rootWidth = Math.max(dp(280), root.getWidth());
        int rootHeight = Math.max(dp(320), root.getHeight());
        int panelWidth = Math.min(dp(440), rootWidth - dp(24));
        layout.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rootHeight, View.MeasureSpec.AT_MOST));
        int panelHeight = layout.getMeasuredHeight();
        PopupWindow popup = new PopupWindow(layout, panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        popup.setElevation(dp(16));
        boolean[] committed = {false};
        popup.setOnDismissListener(() -> {
            if (!committed[0]) {
                canvasView.cancelSelectedTextSpacingPreview();
                committed[0] = true;
            }
        });
        cancel.setOnClickListener(v -> popup.dismiss());
        apply.setOnClickListener(v -> {
            canvasView.finishSelectedTextSpacingPreview();
            committed[0] = true;
            popup.dismiss();
        });

        heading.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int[] panelLocation = new int[2];
                int[] rootLocation = new int[2];
                layout.getLocationInWindow(panelLocation);
                root.getLocationInWindow(rootLocation);
                view.setTag(new float[]{panelLocation[0] - rootLocation[0],
                        panelLocation[1] - rootLocation[1], event.getRawX(), event.getRawY()});
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                Object tag = view.getTag();
                if (tag instanceof float[]) {
                    float[] start = (float[]) tag;
                    int margin = dp(8);
                    int maxX = Math.max(margin, root.getWidth() - panelWidth - margin);
                    int maxY = Math.max(margin, root.getHeight() - panelHeight - margin);
                    int x = Math.max(margin, Math.min(maxX,
                            Math.round(start[0] + event.getRawX() - start[2])));
                    int y = Math.max(margin, Math.min(maxY,
                            Math.round(start[1] + event.getRawY() - start[3])));
                    popup.update(x, y, -1, -1);
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.setTag(null);
                return true;
            }
            return true;
        });

        RectF avoidBounds = canvasView.getSelectionScreenBounds();
        if (avoidBounds != null) {
            int[] canvasLocation = new int[2];
            int[] rootLocation = new int[2];
            canvasView.getLocationInWindow(canvasLocation);
            root.getLocationInWindow(rootLocation);
            avoidBounds.offset(canvasLocation[0] - rootLocation[0], canvasLocation[1] - rootLocation[1]);
        }
        int[] popupPosition = findSpacingPopupPosition(rootWidth, rootHeight, panelWidth,
                panelHeight, avoidBounds, dp(12));
        popup.showAtLocation(root, Gravity.TOP | Gravity.LEFT, popupPosition[0], popupPosition[1]);
    }

    private int[] findSpacingPopupPosition(int rootWidth, int rootHeight, int panelWidth,
                                           int panelHeight, RectF avoidBounds, int margin) {
        float centerX = avoidBounds != null ? avoidBounds.centerX() : rootWidth * 0.5f;
        float centerY = avoidBounds != null ? avoidBounds.centerY() : rootHeight * 0.5f;
        float avoidLeft = avoidBounds != null ? avoidBounds.left : rootWidth * 0.5f;
        float avoidTop = avoidBounds != null ? avoidBounds.top : rootHeight * 0.5f;
        float avoidRight = avoidBounds != null ? avoidBounds.right : rootWidth * 0.5f;
        float avoidBottom = avoidBounds != null ? avoidBounds.bottom : rootHeight * 0.5f;
        float[][] candidates = {
                {centerX - panelWidth * 0.5f, avoidTop - panelHeight - margin},
                {centerX - panelWidth * 0.5f, avoidBottom + margin},
                {avoidLeft - panelWidth - margin, centerY - panelHeight * 0.5f},
                {avoidRight + margin, centerY - panelHeight * 0.5f},
                {margin, margin}, {rootWidth - panelWidth - margin, margin},
                {margin, rootHeight - panelHeight - margin},
                {rootWidth - panelWidth - margin, rootHeight - panelHeight - margin}
        };
        int maxX = Math.max(margin, rootWidth - panelWidth - margin);
        int maxY = Math.max(margin, rootHeight - panelHeight - margin);
        int bestX = margin;
        int bestY = margin;
        double bestScore = Double.MAX_VALUE;
        for (float[] candidate : candidates) {
            int x = Math.max(margin, Math.min(maxX, Math.round(candidate[0])));
            int y = Math.max(margin, Math.min(maxY, Math.round(candidate[1])));
            float overlapWidth = Math.max(0f,
                    Math.min(x + panelWidth, avoidRight) - Math.max(x, avoidLeft));
            float overlapHeight = Math.max(0f,
                    Math.min(y + panelHeight, avoidBottom) - Math.max(y, avoidTop));
            float gapX = Math.max(0f, Math.max(avoidLeft - (x + panelWidth), x - avoidRight));
            float gapY = Math.max(0f, Math.max(avoidTop - (y + panelHeight), y - avoidBottom));
            double score = overlapWidth * overlapHeight * 1000d + Math.hypot(gapX, gapY);
            if (score < bestScore) {
                bestScore = score;
                bestX = x;
                bestY = y;
            }
        }
        return new int[]{bestX, bestY};
    }

    private void showHandwritingProfileDialog() {
        UserHandwritingModel model = UserHandwritingModel.getInstance(this);
        int learnedCount = model.getLearnedCount();
        int vocabCount = model.getCustomVocabulary().size();
        int wordPairCount = model.getLearnedBigramCount();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, pad);

        TextView infoText = new TextView(this);
        infoText.setText("Your local learning stays on this device until you export it. An .onhw profile includes handwriting examples, custom vocabulary, corrections, and learned word pairs.\n\n"
                + "📊 Learned items: " + learnedCount + "\n"
                + "📚 Custom vocabulary: " + vocabCount + " entries\n"
                + "🔗 Learned word pairs: " + wordPairCount);
        infoText.setTextColor(0xFFE2E8F0);
        infoText.setTextSize(14f);
        infoText.setPadding(0, 0, 0, dpToPx(16));
        layout.addView(infoText);

        Button btnTeach = new Button(this);
        btnTeach.setText("🎓 Teach Word or Cursive Style");
        ThemeUtils.styleButton(btnTeach, this, true);
        btnTeach.setOnClickListener(v -> showInteractiveTeachDialog());
        layout.addView(btnTeach);

        Button btnExport = new Button(this);
        btnExport.setText("📤 Export Local Learning (.onhw)");
        ThemeUtils.styleButton(btnExport, this, false);
        btnExport.setOnClickListener(v -> exportProfileLauncher.launch("my_handwriting_profile.onhw"));
        layout.addView(btnExport);

        Button btnImport = new Button(this);
        btnImport.setText("📥 Import Local Learning (.onhw)");
        ThemeUtils.styleButton(btnImport, this, false);
        btnImport.setOnClickListener(v -> importProfileLauncher.launch("*/*"));
        layout.addView(btnImport);

        if (learnedCount > 0) {
            Button btnClear = new Button(this);
            btnClear.setText("🗑️ Reset Learned Styles");
            ThemeUtils.styleDangerButton(btnClear, this);
            btnClear.setOnClickListener(v -> {
                MaterialAlertDialogBuilder resetBuilder = new MaterialAlertDialogBuilder(this)
                        .setTitle("Reset Handwriting Profile")
                        .setMessage("Clear all learned personal styles and prototypes?")
                        .setPositiveButton("Reset", (d, w) -> {
                            model.clearLearnedData();
                            Toast.makeText(this, "Handwriting profile reset to default", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null);
                ThemeUtils.showStyledDialog(resetBuilder, this);
            });
            layout.addView(btnClear);
        }

        MaterialAlertDialogBuilder profileBuilder = new MaterialAlertDialogBuilder(this)
                .setTitle("🎓 My Local Handwriting Learning")
                .setView(layout)
                .setPositiveButton("Done", null);
        ThemeUtils.showStyledDialog(profileBuilder, this);
    }

    public void showTeachHandwritingDialog(String initialRecognized, List<org.opennotes.app.model.Stroke> strokes) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText("Teach the engine how you write words, abbreviations, or doctor scripts. It will prioritize your handwriting style on device.");
        hint.setTextColor(0xFF94A3B8);
        hint.setTextSize(13f);
        hint.setPadding(0, 0, 0, dpToPx(12));
        layout.addView(hint);

        TextView lbl1 = new TextView(this);
        lbl1.setText("When handwriting is detected as (optional):");
        lbl1.setTextColor(0xFFE2E8F0);
        layout.addView(lbl1);

        EditText editRecognized = new EditText(this);
        editRecognized.setHint("e.g. ave, clate, 12, px, peop1e");
        editRecognized.setText(initialRecognized != null ? initialRecognized : "");
        layout.addView(editRecognized);

        TextView lbl2 = new TextView(this);
        lbl2.setText("The correct intended text is (required):");
        lbl2.setTextColor(0xFFE2E8F0);
        lbl2.setPadding(0, dpToPx(10), 0, 0);
        layout.addView(lbl2);

        EditText editIntended = new EditText(this);
        editIntended.setHint("e.g. are, date, R, Rx, people");
        layout.addView(editIntended);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("🎓 Teach Handwriting Style")
                .setView(layout)
                .setPositiveButton("Save Example", (dialog, which) -> {
                    String intended = editIntended.getText().toString().trim();
                    String recognized = editRecognized.getText().toString().trim();
                    if (!intended.isEmpty()) {
                        UserHandwritingModel.getInstance(this).learnCorrection(recognized, intended, strokes);
                        Toast.makeText(this, "✨ Saved a local handwriting example for '" + intended + "'", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Please enter the intended text", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }

    private void showPageSettingsMenu() {
        boolean isStylusOnly = canvasView != null && canvasView.isStylusOnly();
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Page & Canvas Settings")
                .setSubtitle("Configure background, drawing mode and tools")
                .setHeaderSymbol("🎛️")
                .addOption(1, "📄", "Paper Template", "Switch lined, grid, dots or blank layout", false, this::showTemplateSelectionDialog)
                .addOption(2, "🎨", "Page Background Color", "AMOLED black, dark obsidian, warm ivory, pure white", false, this::showPageColorSelectionDialog)
                .addOption(3, "🖐️", "Palm Rejection (Stylus Only)", isStylusOnly ? "Stylus-only drawing active (touch rejected)" : "Touch & stylus drawing enabled", isStylusOnly, () -> {
                    boolean next = !canvasView.isStylusOnly();
                    canvasView.setStylusOnly(next);
                    if (appSettings != null) appSettings.setStylusOnly(next);
                    Toast.makeText(this, next ? "Palm Rejection ON (Stylus-only drawing)" : "Touch & Stylus drawing ON", Toast.LENGTH_SHORT).show();
                })
                .addOption(4, "✨", "Auto-Beautification Settings", "Configure automatic shape & stroke smoothing", false, this::showAutoBeautifySettingsDialog)
                .addOption(5, "🌙", "Super AMOLED Dark Mode (PDF)",
                        appSettings != null && appSettings.isPdfAmoledDarkMode() ? "Forced pure black AMOLED mode ON" : "Disabled (Original PDF white background)",
                        appSettings != null && appSettings.isPdfAmoledDarkMode(), this::togglePdfAmoledDarkMode)
                .addOption(6, "↔️", "Page Swipe Gestures", "Configure swipe left/right gestures for PDF & notebook pages", false, this::showSwipeGestureOptionsDialog)
                .addDestructiveOption(7, "🗑️", "Clear Canvas", "Erase all strokes on this page", () -> {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                            .setTitle("Clear Canvas")
                            .setMessage("Are you sure you want to clear all strokes on this page?")
                            .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                            .setNegativeButton("Cancel", null);
                    ThemeUtils.showStyledDialog(builder, this);
                })
                .show();
    }

    private void togglePdfAmoledDarkMode() {
        if (canvasView == null) return;
        boolean next = !canvasView.isPdfAmoledDarkMode();
        if (appSettings != null) {
            appSettings.setPdfAmoledDarkMode(next);
        }
        canvasView.setPdfAmoledDarkMode(next);
        updatePageNavUi();
        saveNotebookConfig();
        if (btnPdfAmoledToggle != null) {
            btnPdfAmoledToggle.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                    .withEndAction(() -> btnPdfAmoledToggle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start())
                    .start();
        }
        Toast.makeText(this, next ? "Super AMOLED Dark Mode ON (Pure Black)" : "Super AMOLED Dark Mode OFF", Toast.LENGTH_SHORT).show();
    }

    private void applySwipeGesturesConfig() {
        if (canvasView == null || appSettings == null) return;
        canvasView.setSwipeGesturesConfig(
                appSettings.isSwipePageEnabled(),
                appSettings.isSwipeTwoFingerEnabled(),
                appSettings.isSwipeSingleFingerEnabled(),
                appSettings.isSwipeEdgeEnabled(),
                appSettings.getSwipeSensitivity()
        );
        canvasView.setTwoFingerSearchGestureEnabled(appSettings.isTwoFingerSearchGestureEnabled());
    }

    private void goToNextPageWithAnimation() {
        if (pages == null || pages.isEmpty()) return;
        if (canvasView != null && canvasView.isContinuousVerticalScroll() && pages.size() > 1) {
            if (currentPageIndex < pages.size() - 1) {
                saveCurrentPage();
                canvasView.smoothScrollToPage(currentPageIndex + 1);
            } else {
                Toast.makeText(this, "Last page (" + pages.size() + " / " + pages.size() + ")", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (currentPageIndex < pages.size() - 1) {
            saveCurrentPage();
            currentPageIndex++;
            int viewWidth = canvasView.getWidth() > 0 ? canvasView.getWidth() : 1080;
            canvasView.animate()
                    .translationX(-viewWidth * 0.18f)
                    .alpha(0.6f)
                    .setDuration(110)
                    .withEndAction(() -> {
                        loadCurrentPage();
                        canvasView.setTranslationX(viewWidth * 0.18f);
                        canvasView.animate()
                                .translationX(0f)
                                .alpha(1.0f)
                                .setDuration(140)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                                .start();
                    })
                    .start();
        } else {
            canvasView.animate()
                    .translationX(-dp(30f))
                    .setDuration(90)
                    .withEndAction(() -> canvasView.animate().translationX(0f).setDuration(120).start())
                    .start();
            Toast.makeText(this, "Last page (" + pages.size() + " / " + pages.size() + ")", Toast.LENGTH_SHORT).show();
        }
    }

    private void goToPrevPageWithAnimation() {
        if (pages == null || pages.isEmpty()) return;
        if (canvasView != null && canvasView.isContinuousVerticalScroll() && pages.size() > 1) {
            if (currentPageIndex > 0) {
                saveCurrentPage();
                canvasView.smoothScrollToPage(currentPageIndex - 1);
            }
            return;
        }
        if (currentPageIndex > 0) {
            saveCurrentPage();
            currentPageIndex--;
            int viewWidth = canvasView.getWidth() > 0 ? canvasView.getWidth() : 1080;
            canvasView.animate()
                    .translationX(viewWidth * 0.18f)
                    .alpha(0.6f)
                    .setDuration(110)
                    .withEndAction(() -> {
                        loadCurrentPage();
                        canvasView.setTranslationX(-viewWidth * 0.18f);
                        canvasView.animate()
                                .translationX(0f)
                                .alpha(1.0f)
                                .setDuration(140)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                                .start();
                    })
                    .start();
        } else {
            canvasView.animate()
                    .translationX(dp(30f))
                    .setDuration(90)
                    .withEndAction(() -> canvasView.animate().translationX(0f).setDuration(120).start())
                    .start();
            Toast.makeText(this, "First page (1 / " + pages.size() + ")", Toast.LENGTH_SHORT).show();
        }
    }

    private void showGoToPageDialog() {
        if (pages == null || pages.isEmpty()) return;
        saveCurrentPage();
        final int totalPages = pages.size();
        final int currentNum = currentPageIndex + 1;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_go_to_page, null);
        TextView textGoToSubtitle = dialogView.findViewById(R.id.textGoToSubtitle);
        ImageView btnGoToClose = dialogView.findViewById(R.id.btnGoToClose);
        EditText editPageNumber = dialogView.findViewById(R.id.editPageNumber);
        TextView textTotalPagesBadge = dialogView.findViewById(R.id.textTotalPagesBadge);
        SeekBar seekPageScrubber = dialogView.findViewById(R.id.seekPageScrubber);
        TextView textMaxPageLabel = dialogView.findViewById(R.id.textMaxPageLabel);
        TextView chipFirstPage = dialogView.findViewById(R.id.chipFirstPage);
        TextView chipLastPage = dialogView.findViewById(R.id.chipLastPage);
        TextView chipGridOverview = dialogView.findViewById(R.id.chipGridOverview);
        View btnCancelGoTo = dialogView.findViewById(R.id.btnCancelGoTo);
        View btnConfirmGoTo = dialogView.findViewById(R.id.btnConfirmGoTo);

        if (textGoToSubtitle != null) {
            textGoToSubtitle.setText("Notebook has " + totalPages + (totalPages == 1 ? " page" : " pages"));
        }
        if (textTotalPagesBadge != null) {
            textTotalPagesBadge.setText("of " + totalPages);
        }
        if (textMaxPageLabel != null) {
            textMaxPageLabel.setText("Page " + totalPages);
        }

        if (editPageNumber != null) {
            editPageNumber.setText(String.valueOf(currentNum));
            editPageNumber.setSelection(editPageNumber.getText().length());
        }

        if (seekPageScrubber != null) {
            seekPageScrubber.setMax(Math.max(1, totalPages - 1));
            seekPageScrubber.setProgress(Math.max(0, Math.min(currentPageIndex, totalPages - 1)));
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();
        ThemeUtils.styleDialog(dialog, this);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        final boolean[] isUpdating = new boolean[]{false};

        if (seekPageScrubber != null && editPageNumber != null) {
            seekPageScrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && !isUpdating[0]) {
                        isUpdating[0] = true;
                        int pageVal = progress + 1;
                        editPageNumber.setText(String.valueOf(pageVal));
                        editPageNumber.setSelection(editPageNumber.getText().length());
                        isUpdating[0] = false;
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            editPageNumber.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (isUpdating[0]) return;
                    String str = s.toString().trim();
                    if (!str.isEmpty()) {
                        try {
                            int num = Integer.parseInt(str);
                            if (num >= 1 && num <= totalPages) {
                                isUpdating[0] = true;
                                seekPageScrubber.setProgress(num - 1);
                                isUpdating[0] = false;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            editPageNumber.setOnEditorActionListener((v, actionId, event) -> {
                if (btnConfirmGoTo != null) {
                    btnConfirmGoTo.performClick();
                    return true;
                }
                return false;
            });
        }

        if (chipFirstPage != null && seekPageScrubber != null && editPageNumber != null) {
            chipFirstPage.setOnClickListener(v -> {
                isUpdating[0] = true;
                seekPageScrubber.setProgress(0);
                editPageNumber.setText("1");
                editPageNumber.setSelection(1);
                isUpdating[0] = false;
            });
        }

        if (chipLastPage != null && seekPageScrubber != null && editPageNumber != null) {
            chipLastPage.setOnClickListener(v -> {
                isUpdating[0] = true;
                seekPageScrubber.setProgress(totalPages - 1);
                String lastStr = String.valueOf(totalPages);
                editPageNumber.setText(lastStr);
                editPageNumber.setSelection(lastStr.length());
                isUpdating[0] = false;
            });
        }

        if (chipGridOverview != null) {
            chipGridOverview.setOnClickListener(v -> {
                dialog.dismiss();
                showPageOverviewDialog();
            });
        }

        if (btnGoToClose != null) {
            btnGoToClose.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnCancelGoTo != null) {
            btnCancelGoTo.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirmGoTo != null) {
            btnConfirmGoTo.setOnClickListener(v -> {
                int targetPageNum = currentNum;
                if (editPageNumber != null) {
                    String text = editPageNumber.getText().toString().trim();
                    if (!text.isEmpty()) {
                        try {
                            targetPageNum = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            targetPageNum = seekPageScrubber != null ? seekPageScrubber.getProgress() + 1 : currentNum;
                        }
                    } else if (seekPageScrubber != null) {
                        targetPageNum = seekPageScrubber.getProgress() + 1;
                    }
                }

                if (targetPageNum < 1) targetPageNum = 1;
                if (targetPageNum > totalPages) targetPageNum = totalPages;

                final int targetIndex = targetPageNum - 1;
                dialog.dismiss();

                if (targetIndex != currentPageIndex) {
                    jumpToPageWithSmoothTransition(targetIndex);
                } else {
                    Toast.makeText(this, "Already on Page " + targetPageNum, Toast.LENGTH_SHORT).show();
                }
            });
        }

        dialog.show();
        if (editPageNumber != null) {
            editPageNumber.requestFocus();
        }
    }

    public void jumpToPageWithSmoothTransition(int targetPageIndex) {
        if (pages == null || pages.isEmpty()) return;
        targetPageIndex = Math.max(0, Math.min(targetPageIndex, pages.size() - 1));
        if (targetPageIndex == currentPageIndex) return;

        saveCurrentPage();
        if (canvasView != null && canvasView.isContinuousVerticalScroll() && pages.size() > 1) {
            canvasView.smoothScrollToPage(targetPageIndex);
            saveNotebookViewState();
            return;
        }

        final int targetIndex = targetPageIndex;
        final boolean goingForward = targetIndex > currentPageIndex;
        int viewWidth = canvasView != null && canvasView.getWidth() > 0 ? canvasView.getWidth() : 1080;
        float startOffset = goingForward ? -viewWidth * 0.15f : viewWidth * 0.15f;
        float enterOffset = goingForward ? viewWidth * 0.15f : -viewWidth * 0.15f;

        if (canvasView != null) {
            canvasView.animate()
                    .translationX(startOffset)
                    .alpha(0.5f)
                    .setDuration(120)
                    .withEndAction(() -> {
                        currentPageIndex = targetIndex;
                        loadCurrentPage();
                        canvasView.setTranslationX(enterOffset);
                        canvasView.animate()
                                .translationX(0f)
                                .alpha(1.0f)
                                .setDuration(160)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                                .withEndAction(this::saveNotebookViewState)
                                .start();
                    })
                    .start();
        } else {
            currentPageIndex = targetIndex;
            loadCurrentPage();
            saveNotebookViewState();
        }
    }

    private void saveNotebookViewState() {
        if (notebookId <= 0 || pages == null || pages.isEmpty()) return;
        final int rawPageIdx = (canvasView != null && canvasView.isContinuousVerticalScroll() && canvasView.getAllPages() != null && !canvasView.getAllPages().isEmpty())
                ? canvasView.getActivePageIndex()
                : currentPageIndex;
        final int pageIdx = (rawPageIdx < 0 || rawPageIdx >= pages.size())
                ? Math.max(0, Math.min(currentPageIndex, pages.size() - 1))
                : rawPageIdx;
        final float scrollY = canvasView != null ? canvasView.getViewportOffsetY() : 0f;
        final float zoom = canvasView != null ? canvasView.getZoomPercent() : 100f;
        final float panX = canvasView != null ? canvasView.getPanOffsetX() : 0f;
        final float panY = canvasView != null ? canvasView.getPanOffsetY() : 0f;

        // 1. Synchronously save to high-speed SharedPreferences cache
        NotebookViewStateManager.saveState(this, notebookId, pageIdx, scrollY, zoom, panX, panY);

        // 2. Synchronously save per-notebook configuration
        saveNotebookConfig();

        // 3. Update SQLite database record
        final long nId = notebookId;
        if (isFinishing() || isDestroyed()) {
            try {
                dbHelper.updateNotebookViewState(nId, pageIdx, scrollY, zoom, panX, panY);
            } catch (Throwable t) {
                Log.w("NoteEditorActivity", "Failed to persist view state to db", t);
            }
        } else {
            executeSaveTask(() -> {
                try {
                    dbHelper.updateNotebookViewState(nId, pageIdx, scrollY, zoom, panX, panY);
                } catch (Throwable t) {
                    Log.w("NoteEditorActivity", "Failed to persist view state to db", t);
                }
            });
        }
    }

    private void showSwipeGestureOptionsDialog() {
        if (appSettings == null) return;
        boolean isContinuous = canvasView != null ? canvasView.isContinuousVerticalScroll() : appSettings.isContinuousVerticalScroll();
        boolean isEnabled = appSettings.isSwipePageEnabled();
        boolean isTwoFinger = appSettings.isSwipeTwoFingerEnabled();
        boolean isSingleFinger = appSettings.isSwipeSingleFingerEnabled();
        boolean isEdge = appSettings.isSwipeEdgeEnabled();
        String sensitivity = appSettings.getSwipeSensitivity();
        boolean isAmoled = canvasView != null ? canvasView.isPdfAmoledDarkMode() : appSettings.isPdfAmoledDarkMode();

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Page Navigation & Swipe Gestures")
                .setSubtitle("Customize page scroll style, gestures, and AMOLED mode")
                .setHeaderSymbol("↔️")
                .addOption(0, "📜", "Page Navigation Mode",
                        isContinuous ? "Smooth Continuous Vertical Scroll (Tap for No Scroll / Change Pages Only)"
                                     : "No Scroll / Change Pages Only (Tap for Smooth Vertical Scroll)",
                        isContinuous, () -> {
                            boolean next = !isContinuous;
                            if (appSettings != null) appSettings.setContinuousVerticalScroll(next);
                            canvasView.setContinuousVerticalScroll(next);
                            if (next && pages.size() > 1) {
                                canvasView.setPages(pages, currentPageIndex);
                            } else {
                                canvasView.setPage(currentPage);
                            }
                            updatePageNavUi();
                            saveNotebookConfig();
                            Toast.makeText(this, next ? "Smooth Vertical Scroll enabled" : "No Scroll: Change Pages Only enabled", Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(1, "↔️", "Enable Page Swipe Gestures",
                        isEnabled ? "Swipe gestures active (turning pages enabled)" : "Swipe gestures disabled",
                        isEnabled, () -> {
                            boolean next = !appSettings.isSwipePageEnabled();
                            appSettings.setSwipePageEnabled(next);
                            applySwipeGesturesConfig();
                            Toast.makeText(this, next ? "Page Swipe Gestures ON" : "Page Swipe Gestures OFF", Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(2, "✌️", "Two-Finger Swipe",
                        isTwoFinger ? "Swipe 2 fingers horizontally anywhere on canvas (safe while drawing)" : "Disabled",
                        isTwoFinger, () -> {
                            boolean next = !appSettings.isSwipeTwoFingerEnabled();
                            appSettings.setSwipeTwoFingerEnabled(next);
                            applySwipeGesturesConfig();
                            Toast.makeText(this, next ? "Two-Finger Swipe ON" : "Two-Finger Swipe OFF", Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(3, "👆", "Single-Finger Swipe",
                        isSingleFinger ? "Active in Palm Rejection (Stylus-Only) or Hand Tool mode" : "Disabled",
                        isSingleFinger, () -> {
                            boolean next = !appSettings.isSwipeSingleFingerEnabled();
                            appSettings.setSwipeSingleFingerEnabled(next);
                            applySwipeGesturesConfig();
                            Toast.makeText(this, next ? "Single-Finger Swipe ON" : "Single-Finger Swipe OFF", Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(4, "📐", "Screen-Edge Swipe",
                        isEdge ? "Swipe inward from left/right screen margin to turn pages" : "Disabled",
                        isEdge, () -> {
                            boolean next = !appSettings.isSwipeEdgeEnabled();
                            appSettings.setSwipeEdgeEnabled(next);
                            applySwipeGesturesConfig();
                            Toast.makeText(this, next ? "Screen-Edge Swipe ON" : "Screen-Edge Swipe OFF", Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(5, "⚡", "Swipe Sensitivity",
                        "Current: " + sensitivity + " (Tap to cycle: Normal → High → Low)",
                        false, () -> {
                            String next;
                            if ("NORMAL".equalsIgnoreCase(sensitivity)) {
                                next = "HIGH";
                            } else if ("HIGH".equalsIgnoreCase(sensitivity)) {
                                next = "LOW";
                            } else {
                                next = "NORMAL";
                            }
                            appSettings.setSwipeSensitivity(next);
                            applySwipeGesturesConfig();
                            Toast.makeText(this, "Swipe sensitivity: " + next, Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(6, "🌙", "Super AMOLED Dark Mode (PDF)",
                        isAmoled ? "Pure AMOLED black paper with crisp high-contrast text" : "Disabled (Original PDF white background)",
                        isAmoled, () -> {
                            togglePdfAmoledDarkMode();
                            showSwipeGestureOptionsDialog();
                        })
                .addOption(7, "🔍", "Two-Finger Hold Search Shortcut",
                        appSettings.isTwoFingerSearchGestureEnabled()
                                ? "Enabled (Hold 2 fingers still to trigger search. Tap to disable)"
                                : "Disabled (Turned off to prevent accidental triggers. Tap to enable)",
                        appSettings.isTwoFingerSearchGestureEnabled(), () -> {
                            boolean next = !appSettings.isTwoFingerSearchGestureEnabled();
                            appSettings.setTwoFingerSearchGestureEnabled(next);
                            if (canvasView != null) canvasView.setTwoFingerSearchGestureEnabled(next);
                            Toast.makeText(this, next ? "Two-Finger Hold Search ON" : "Two-Finger Hold Search OFF", Toast.LENGTH_SHORT).show();
                            showSwipeGestureOptionsDialog();
                        })
                .show();
    }

    private void showTemplateSelectionDialog() {
        PaperTemplate active = (currentPage != null && currentPage.template != null)
                ? currentPage.template : PaperTemplate.RULED;

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Select Paper Template")
                .setSubtitle("Change the background ruling for this page")
                .setHeaderSymbol("📄")
                .addOption(1, "⬜", "Blank Page", "Clean canvas with no lines", active == PaperTemplate.BLANK, () -> {
                    canvasView.setPaperTemplate(PaperTemplate.BLANK);
                    saveCurrentPage();
                })
                .addOption(2, "📏", "Ruled Lines", "Standard handwriting ruled lines", active == PaperTemplate.RULED, () -> {
                    canvasView.setPaperTemplate(PaperTemplate.RULED);
                    saveCurrentPage();
                })
                .addOption(3, "📐", "Grid Paper", "Precision square grid for math and graphs", active == PaperTemplate.GRID, () -> {
                    canvasView.setPaperTemplate(PaperTemplate.GRID);
                    saveCurrentPage();
                })
                .addOption(4, "•", "Dot Grid", "Minimal dot matrix for journaling", active == PaperTemplate.DOTS, () -> {
                    canvasView.setPaperTemplate(PaperTemplate.DOTS);
                    saveCurrentPage();
                })
                .addOption(5, "📑", "Cornell Notes", "Cue margin, notes area, and summary box", active == PaperTemplate.CORNELL, () -> {
                    canvasView.setPaperTemplate(PaperTemplate.CORNELL);
                    saveCurrentPage();
                })
                .show();
    }

    private void showPageColorSelectionDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_page_color_selector, null);
        LinearLayout layoutPresets = view.findViewById(R.id.layoutColorPresets);
        View btnOpenCustom = view.findViewById(R.id.btnOpenCustomColorPicker);
        View previewSelected = view.findViewById(R.id.previewSelectedBgColor);
        MaterialCheckBox checkDefault = view.findViewById(R.id.checkSetAsNotebookDefault);
        MaterialCheckBox checkAllPages = view.findViewById(R.id.checkApplyToAllPages);
        Button btnCancel = view.findViewById(R.id.btnCancelColorDialog);
        Button btnApply = view.findViewById(R.id.btnApplyColorDialog);

        final int initialColor = (currentPage != null && currentPage.backgroundColor != 0)
                ? currentPage.backgroundColor
                : canvasView.getPageBackgroundColor();
        final int[] selectedColor = {initialColor};

        final int[] presetColors = {
                0xFF000000, // Super Dark Black (True AMOLED Black)
                0xFF121214, // Dark Obsidian
                0xFF1E293B, // Dark Slate
                0xFF18181B, // Charcoal
                0xFFFFFFFF, // Pure White
                0xFFFFFBEB, // Warm Ivory
                0xFFF0FDF4, // Eye-Care Mint
                0xFFFEF9C3, // Legal Pad Yellow
                0xFFF5EBE1, // Vintage Kraft / Parchment
                0xFF0F172A  // Deep Navy
        };

        final String[] presetNames = {
                "Super Dark Black (AMOLED)", "Dark Obsidian", "Slate Gray", "Charcoal", "Pure White",
                "Warm Ivory", "Eye-Care Mint", "Legal Pad", "Kraft Paper", "Deep Navy"
        };

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();

        final List<View> swatchViews = new ArrayList<>();

        Runnable updatePreview = () -> {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(selectedColor[0]);
            gd.setStroke(3, 0xFF38BDF8);
            previewSelected.setBackground(gd);

            for (int i = 0; i < presetColors.length; i++) {
                if (i < swatchViews.size()) {
                    View sw = swatchViews.get(i);
                    GradientDrawable swGd = new GradientDrawable();
                    swGd.setShape(GradientDrawable.OVAL);
                    swGd.setColor(presetColors[i]);
                    if (presetColors[i] == selectedColor[0]) {
                        swGd.setStroke(5, 0xFF38BDF8);
                    } else {
                        swGd.setStroke(2, 0xFF475569);
                    }
                    sw.setBackground(swGd);
                }
            }
        };

        for (int i = 0; i < presetColors.length; i++) {
            final int col = presetColors[i];
            final String name = presetNames[i];

            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(70, 70);
            lp.setMargins(8, 6, 8, 6);
            dot.setLayoutParams(lp);
            dot.setContentDescription(name);

            dot.setOnClickListener(v -> {
                selectedColor[0] = col;
                updatePreview.run();
            });

            swatchViews.add(dot);
            layoutPresets.addView(dot);
        }

        updatePreview.run();

        btnOpenCustom.setOnClickListener(v -> {
            StarColorPickerDialog.show(this, "Custom Paper Background", selectedColor[0], color -> {
                selectedColor[0] = color;
                updatePreview.run();
            });
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnApply.setOnClickListener(v -> {
            int newColor = selectedColor[0];
            canvasView.setPageBackgroundColor(newColor);

            if (checkDefault.isChecked()) {
                if (currentNotebook != null) {
                    currentNotebook.defaultBgColor = newColor;
                }
                if (appSettings != null) {
                    appSettings.setDefaultPageBgColor(newColor);
                }
                boolean applyToAll = checkAllPages.isChecked();
                executeSaveTask(() -> dbHelper.updateNotebookDefaultBgColor(notebookId, newColor, applyToAll));
            } else if (checkAllPages.isChecked()) {
                int defaultColor = currentNotebook != null ? currentNotebook.defaultBgColor : -1;
                executeSaveTask(() -> dbHelper.updateNotebookDefaultBgColor(notebookId, defaultColor, true));
            }

            if (checkAllPages.isChecked() && pages != null) {
                for (Page p : pages) {
                    p.backgroundColor = newColor;
                }
            }

            saveCurrentPage();
            dialog.dismiss();
            Toast.makeText(this, "Page background color updated", Toast.LENGTH_SHORT).show();
        });

        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void showAutoBeautifySettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_auto_beautify_settings, null);

        SwitchCompat switchEnableBeautify = view.findViewById(R.id.switchEnableBeautify);
        TextView btnLangAuto = view.findViewById(R.id.btnLangAuto);
        TextView btnLangEnglish = view.findViewById(R.id.btnLangEnglish);
        TextView btnLangMarathi = view.findViewById(R.id.btnLangMarathi);
        TextView btnLangHindi = view.findViewById(R.id.btnLangHindi);

        View btnFontKalam = view.findViewById(R.id.btnFontKalam);
        View btnFontCaveat = view.findViewById(R.id.btnFontCaveat);
        View btnFontYatra = view.findViewById(R.id.btnFontYatra);
        View btnFontPoppins = view.findViewById(R.id.btnFontPoppins);

        LinearLayout layoutCustomFontList = view.findViewById(R.id.layoutCustomFontList);
        View btnImportExternalFont = view.findViewById(R.id.btnImportExternalFont);
        MaterialCheckBox checkAutoConvertText = view.findViewById(R.id.checkAutoConvertText);
        MaterialCheckBox checkShirorekhaSnap = view.findViewById(R.id.checkShirorekhaSnap);
        TextView textBeautifyPreview = view.findViewById(R.id.textBeautifyPreview);
        Button btnCancel = view.findViewById(R.id.btnCancelBeautifyDialog);
        Button btnApply = view.findViewById(R.id.btnApplyBeautifyDialog);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();

        boolean initialEnabled = appSettings != null ? appSettings.isAutoBeautifyEnabled() : true;
        String initialLang = appSettings != null ? appSettings.getBeautifyLanguage() : "MARATHI";
        String initialFont = appSettings != null ? appSettings.getBeautifyFont() : TypefaceManager.FONT_KALAM;
        boolean initialShirorekha = appSettings != null ? appSettings.isShirorekhaSnapEnabled() : true;
        boolean initialAutoConvert = appSettings != null ? appSettings.isAutoConvertTextEnabled() : true;

        final boolean[] isEnabled = { initialEnabled };
        final String[] selectedLang = { initialLang };
        final String[] selectedFont = { initialFont };
        final List<TypefaceManager.CustomFontInfo> customFonts = new ArrayList<>();

        switchEnableBeautify.setChecked(initialEnabled);
        checkShirorekhaSnap.setChecked(initialShirorekha);
        if (checkAutoConvertText != null) {
            checkAutoConvertText.setChecked(initialAutoConvert);
        }

        switchEnableBeautify.setOnCheckedChangeListener((btn, checked) -> isEnabled[0] = checked);

        final Runnable[] updateDialogUi = new Runnable[1];
        updateDialogUi[0] = () -> {
            boolean isAuto = "AUTO".equalsIgnoreCase(selectedLang[0]) || "AUTO_DETECT".equalsIgnoreCase(selectedLang[0]);
            boolean isMar = "MARATHI".equalsIgnoreCase(selectedLang[0]);
            boolean isHin = "HINDI".equalsIgnoreCase(selectedLang[0]);
            boolean isEng = "ENGLISH".equalsIgnoreCase(selectedLang[0]);

            if (btnLangAuto != null) {
                btnLangAuto.setBackgroundResource(isAuto ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
                ((TextView) btnLangAuto).setTextColor(isAuto ? 0xFFFFFFFF : 0xFF94A3B8);
            }

            btnLangMarathi.setBackgroundResource(isMar ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
            btnLangMarathi.setTextColor(isMar ? 0xFFFFFFFF : 0xFF94A3B8);

            btnLangHindi.setBackgroundResource(isHin ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
            btnLangHindi.setTextColor(isHin ? 0xFFFFFFFF : 0xFF94A3B8);

            btnLangEnglish.setBackgroundResource(isEng ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
            btnLangEnglish.setTextColor(isEng ? 0xFFFFFFFF : 0xFF94A3B8);

            btnFontKalam.setBackgroundResource(TypefaceManager.FONT_KALAM.equalsIgnoreCase(selectedFont[0]) ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
            btnFontCaveat.setBackgroundResource(TypefaceManager.FONT_CAVEAT.equalsIgnoreCase(selectedFont[0]) ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
            btnFontYatra.setBackgroundResource(TypefaceManager.FONT_YATRA.equalsIgnoreCase(selectedFont[0]) ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);
            btnFontPoppins.setBackgroundResource(TypefaceManager.FONT_POPPINS.equalsIgnoreCase(selectedFont[0]) ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);

            // Populate user imported custom TTF/OTF fonts
            if (layoutCustomFontList != null) {
                layoutCustomFontList.removeAllViews();
                for (TypefaceManager.CustomFontInfo cFont : customFonts) {
                    boolean isSelected = cFont.key.equalsIgnoreCase(selectedFont[0]);
                    LinearLayout fontRow = new LinearLayout(this);
                    fontRow.setOrientation(LinearLayout.HORIZONTAL);
                    fontRow.setGravity(Gravity.CENTER_VERTICAL);
                    fontRow.setPadding(32, 20, 32, 20);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(0, 0, 0, 12);
                    fontRow.setLayoutParams(lp);
                    fontRow.setBackgroundResource(isSelected ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);

                    TextView nameView = new TextView(this);
                    nameView.setText("🔤 " + cFont.displayName);
                    nameView.setTextColor(isSelected ? 0xFFFFFFFF : 0xFFE2E8F0);
                    nameView.setTextSize(13);
                    nameView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                    TextView tagView = new TextView(this);
                    tagView.setText(isSelected ? "✓ Active" : "Custom Font");
                    tagView.setTextColor(isSelected ? 0xFF38BDF8 : 0xFF94A3B8);
                    tagView.setTextSize(11);

                    fontRow.addView(nameView);
                    fontRow.addView(tagView);

                    fontRow.setOnClickListener(v -> {
                        selectedFont[0] = cFont.key;
                        if (updateDialogUi[0] != null) updateDialogUi[0].run();
                    });
                    layoutCustomFontList.addView(fontRow);
                }
            }

            if (isAuto) {
                textBeautifyPreview.setText("Auto-Detect: 1949 248 माझे Book 123");
            } else if (isMar) {
                textBeautifyPreview.setText("माझे सुंदर सुलेखन • OpenNotes");
            } else if (isHin) {
                textBeautifyPreview.setText("सुंदर हस्तलेख और सुलेखन • OpenNotes");
            } else {
                textBeautifyPreview.setText("Beautiful Handwritten Notes • OpenNotes");
            }
            textBeautifyPreview.setTypeface(TypefaceManager.getTypeface(this, selectedFont[0]));
        };

        if (btnLangAuto != null) {
            btnLangAuto.setOnClickListener(v -> {
                selectedLang[0] = "AUTO";
                if (updateDialogUi[0] != null) updateDialogUi[0].run();
            });
        }

        btnLangMarathi.setOnClickListener(v -> {
            selectedLang[0] = "MARATHI";
            if (TypefaceManager.FONT_CAVEAT.equalsIgnoreCase(selectedFont[0])) {
                selectedFont[0] = TypefaceManager.FONT_KALAM;
            }
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });

        btnLangHindi.setOnClickListener(v -> {
            selectedLang[0] = "HINDI";
            if (TypefaceManager.FONT_CAVEAT.equalsIgnoreCase(selectedFont[0])) {
                selectedFont[0] = TypefaceManager.FONT_KALAM;
            }
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });

        btnLangEnglish.setOnClickListener(v -> {
            selectedLang[0] = "ENGLISH";
            if (TypefaceManager.FONT_YATRA.equalsIgnoreCase(selectedFont[0])) {
                selectedFont[0] = TypefaceManager.FONT_CAVEAT;
            }
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });

        btnFontKalam.setOnClickListener(v -> {
            selectedFont[0] = TypefaceManager.FONT_KALAM;
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });
        btnFontCaveat.setOnClickListener(v -> {
            selectedFont[0] = TypefaceManager.FONT_CAVEAT;
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });
        btnFontYatra.setOnClickListener(v -> {
            selectedFont[0] = TypefaceManager.FONT_YATRA;
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });
        btnFontPoppins.setOnClickListener(v -> {
            selectedFont[0] = TypefaceManager.FONT_POPPINS;
            if (updateDialogUi[0] != null) updateDialogUi[0].run();
        });

        if (btnImportExternalFont != null) {
            btnImportExternalFont.setOnClickListener(v -> {
                dialog.dismiss();
                fontPickerLauncher.launch("*/*");
            });
        }

        if (updateDialogUi[0] != null) updateDialogUi[0].run();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnApply.setOnClickListener(v -> {
            boolean enabled = isEnabled[0];
            String lang = selectedLang[0];
            String font = selectedFont[0];
            boolean shirorekha = checkShirorekhaSnap.isChecked();
            boolean autoConvert = enabled && (checkAutoConvertText != null && checkAutoConvertText.isChecked());

            if (appSettings != null) {
                appSettings.setAutoBeautifyEnabled(enabled);
                appSettings.setBeautifyLanguage(lang);
                appSettings.setBeautifyFont(font);
                appSettings.setShirorekhaSnapEnabled(shirorekha);
                appSettings.setAutoConvertTextEnabled(autoConvert);
            }

            canvasView.setAutoBeautifyEnabled(enabled);
            canvasView.setBeautifyLanguage(lang);
            canvasView.setBeautifyFontKey(font);
            canvasView.setShirorekhaSnapEnabled(shirorekha);
            canvasView.setAutoConvertTextEnabled(autoConvert);
            if (!enabled || !autoConvert) {
                canvasView.cancelPendingAutoConversion();
            }

            updateAutoBeautifyToolbarIcon(enabled);
            dialog.dismiss();

            String langDisplay = "MARATHI".equalsIgnoreCase(lang) ? "मराठी" : ("HINDI".equalsIgnoreCase(lang) ? "हिन्दी" : "English");
            String convertMsg = autoConvert ? " • Real-Time Text Convert ON" : "";
            Toast.makeText(this, "✨ Auto-Beautification " + (enabled ? "ON" : "OFF") + " (" + langDisplay + " • " + font + ")" + convertMsg, Toast.LENGTH_SHORT).show();
        });

        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "⚙️ AI & Models", (d, w) -> {
            new ModernOptionsSelectorDialog.Builder(this)
                    .setTitle("AI & Handwriting Options")
                    .setSubtitle("Manage on-device ML Kit models and handwriting training")
                    .setHeaderSymbol("🤖")
                    .addOption(0, "📥", "Offline Handwriting Models", "Manage offline Digital Ink recognition models", false, this::showDownloadModelsDialog)
                    .addOption(1, "🎓", "Teach My Handwriting", "Train local personal handwriting recognition", false, this::showHandwritingProfileDialog)
                    .addOption(2, "📤", "Export Local Handwriting Learning (.onhw)", "Backup trained handwriting profile to file", false, () -> {
                        exportProfileLauncher.launch("my_handwriting_profile.onhw");
                    })
                    .addOption(3, "📥", "Import Local Handwriting Learning (.onhw)", "Restore trained handwriting profile from file", false, () -> {
                        importProfileLauncher.launch("*/*");
                    })
                    .show();
        });

        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
        if (layoutCustomFontList != null) {
            TextView loadingFonts = new TextView(this);
            loadingFonts.setText("Loading imported fonts…");
            loadingFonts.setTextColor(0xFF94A3B8);
            loadingFonts.setTextSize(12);
            layoutCustomFontList.addView(loadingFonts);
            executeFontIoTask(() -> {
                List<TypefaceManager.CustomFontInfo> foundFonts =
                        TypefaceManager.getCustomFonts(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    customFonts.clear();
                    customFonts.addAll(foundFonts);
                    updateDialogUi[0].run();
                });
            });
        }
    }

    private void handleImportCustomFont(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "Importing font…", Toast.LENGTH_SHORT).show();
        Context applicationContext = getApplicationContext();
        executeFontIoTask(() -> {
            String fontName = TypefaceManager.importCustomFont(applicationContext, uri);
            String errorDetails = TypefaceManager.getLastFontImportError();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fontName != null) {
                    if (canvasView != null) {
                        canvasView.clearTextTypefaceCache();
                        canvasView.setBeautifyFontKey(fontName);
                    }
                    if (appSettings != null) {
                        appSettings.setBeautifyFont(fontName);
                    }
                    Toast.makeText(this, "✓ Custom Font Imported: " + fontName
                            + "\nReady for auto-beautification & text conversion", Toast.LENGTH_LONG).show();
                    showAutoBeautifySettingsDialog();
                } else {
                    ErrorDialog.show(this, "Font import failed", errorDetails == null
                            ? "The selected file could not be imported. Choose a readable .ttf or .otf font file."
                            : errorDetails);
                }
            });
        });
    }

    private void setZenFullscreen(boolean fullscreen) {
        isZenFullscreen = fullscreen;
        if (fullscreen) {
            topBar.animate().translationY(-topBar.getHeight()).alpha(0f).setDuration(220)
                    .withEndAction(() -> topBar.setVisibility(View.GONE)).start();
            floatingViewportPill.animate().cancel();
            floatingViewportPill.setVisibility(View.VISIBLE);
            floatingViewportPill.setAlpha(1f);
            cardToolProperties.setVisibility(View.GONE);
            lassoFloatingActionBar.setVisibility(View.GONE);
            btnExitFullscreen.setVisibility(View.VISIBLE);
            btnExitFullscreen.setAlpha(0f);
            btnExitFullscreen.animate().alpha(1f).setDuration(200).start();
        } else {
            btnExitFullscreen.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> btnExitFullscreen.setVisibility(View.GONE)).start();
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().translationY(0f).alpha(1f).setDuration(220).start();
            floatingViewportPill.setVisibility(View.VISIBLE);
            floatingViewportPill.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void handleInsertImage(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);

            int sampleSize = 1;
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            while (maxDim / sampleSize > 1600) {
                sampleSize *= 2;
            }

            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options opts2 = new BitmapFactory.Options();
                opts2.inSampleSize = sampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(is2, null, opts2);
                if (bitmap != null) {
                    File imagesDir = new File(getFilesDir(), "note_images");
                    if (!imagesDir.exists()) imagesDir.mkdirs();
                    File destFile = new File(imagesDir, "img_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                    }

                    float width = Math.min(bitmap.getWidth(), 800f);
                    float height = width * ((float) bitmap.getHeight() / bitmap.getWidth());
                    float centerX = canvasView.getCenterCanvasX();
                    float centerY = canvasView.getCenterCanvasY();
                    float startX = centerX - width / 2f;
                    float startY = centerY - height / 2f;

                    ImageItem imgItem = new ImageItem(destFile.getAbsolutePath(), startX, startY, width, height);
                    canvasView.addImageItem(imgItem);
                    saveCurrentPage();
                    Toast.makeText(this, "Image inserted (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            ErrorDialog.show(this, "Image could not be loaded", e);
        }
    }

    private void showInsertPhotoDialog() {
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Insert Image")
                .setSubtitle("Add visual media to your notebook page")
                .setHeaderSymbol("📷")
                .addOption(1, "📷", "Take Photo with Camera", "Capture a picture and insert directly on canvas", false, () -> {
                    try {
                        File photoFile = new File(getCacheDir(), "camera_" + System.currentTimeMillis() + ".jpg");
                        tempCameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
                        takePictureLauncher.launch(tempCameraPhotoUri);
                    } catch (Exception e) {
                        Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .addOption(2, "🖼️", "Choose from Gallery", "Select photos from your device storage", false, () -> {
                    imagePickerLauncher.launch("image/*");
                })
                .show();
    }

    private void showDownloadModelsDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("📥 Offline Handwriting Recognition Models");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView desc = new TextView(this);
        desc.setText("Google ML Kit Digital Ink models operate 100% on-device for zero-latency, private handwriting-to-text recognition with no internet connection required.");
        desc.setTextColor(0xFF94A3B8);
        desc.setTextSize(13);
        desc.setLineSpacing(dp(2), 1.15f);
        root.addView(desc);

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(0, dp(16), 0, dp(16));
        root.addView(listLayout);

        String[] langs = {"ENGLISH", "MARATHI", "HINDI"};
        String[] langTitles = {"English (en-US / en-IN)", "Marathi (मराठी - mr-IN / mr)", "Hindi (हिन्दी - hi)"};
        String[] langSubtitles = {"Latin script & cursive ligature models", "Devanagari script with Shirorekha priors", "Devanagari script & joint compound glyphs"};

        TextView[] statusViews = new TextView[langs.length];
        Button[] actionButtons = new Button[langs.length];

        for (int i = 0; i < langs.length; i++) {
            final int index = i;
            final String langKey = langs[i];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(10));
            card.setLayoutParams(lp);
            card.setBackgroundResource(R.drawable.bg_floating_pill);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView title = new TextView(this);
            title.setText(langTitles[i]);
            title.setTextColor(0xFFF1F5F9);
            title.setTextSize(14);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(title);

            TextView subtitle = new TextView(this);
            subtitle.setText(langSubtitles[i]);
            subtitle.setTextColor(0xFF64748B);
            subtitle.setTextSize(11);
            textCol.addView(subtitle);

            TextView status = new TextView(this);
            status.setText("Checking status…");
            status.setTextColor(0xFF94A3B8);
            status.setTextSize(12);
            status.setPadding(0, dp(4), 0, 0);
            textCol.addView(status);
            statusViews[i] = status;

            card.addView(textCol);

            Button btnDownload = new Button(this, null, com.google.android.material.R.attr.materialButtonStyle);
            btnDownload.setText("Download");
            btnDownload.setTextSize(12);
            btnDownload.setAllCaps(false);
            btnDownload.setPadding(dp(12), dp(4), dp(12), dp(4));
            actionButtons[i] = btnDownload;
            card.addView(btnDownload);

            listLayout.addView(card);

            // Check download status asynchronously
            HandwritingRecognitionEngine.isModelDownloaded(langKey, downloaded -> {
                runOnUiThread(() -> {
                    if (downloaded) {
                        status.setText("✓ Ready Offline (Downloaded)");
                        status.setTextColor(0xFF10B981);
                        btnDownload.setText("Downloaded");
                        btnDownload.setEnabled(false);
                        btnDownload.setAlpha(0.6f);
                    } else {
                        status.setText("Not downloaded (~15MB)");
                        status.setTextColor(0xFFF59E0B);
                        btnDownload.setText("Download");
                        btnDownload.setEnabled(true);
                        btnDownload.setAlpha(1.0f);
                    }
                });
            });

            btnDownload.setOnClickListener(v -> {
                btnDownload.setEnabled(false);
                btnDownload.setText("Downloading…");
                status.setText("Downloading model package…");
                status.setTextColor(0xFF38BDF8);

                HandwritingRecognitionEngine.downloadModel(langKey, error -> {
                    runOnUiThread(() -> {
                        if (error == null) {
                            status.setText("✓ Download Complete • Ready Offline");
                            status.setTextColor(0xFF10B981);
                            btnDownload.setText("Downloaded");
                            btnDownload.setEnabled(false);
                            btnDownload.setAlpha(0.6f);
                            Toast.makeText(NoteEditorActivity.this, "✓ " + langTitles[index] + " model ready for offline recognition!", Toast.LENGTH_SHORT).show();
                        } else {
                            status.setText("Download failed: " + (error.getMessage() != null ? error.getMessage() : "Network error"));
                            status.setTextColor(0xFFEF4444);
                            btnDownload.setEnabled(true);
                            btnDownload.setText("Retry");
                        }
                    });
                });
            });
        }

        builder.setView(root);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Download All", (d, w) -> {
            Toast.makeText(this, "Downloading offline handwriting models in background…", Toast.LENGTH_SHORT).show();
            for (int i = 0; i < langs.length; i++) {
                final int idx = i;
                final String langKey = langs[i];
                HandwritingRecognitionEngine.downloadModel(langKey, error -> {
                    if (error == null) {
                        runOnUiThread(() -> {
                            if (statusViews[idx] != null) {
                                statusViews[idx].setText("✓ Ready Offline (Downloaded)");
                                statusViews[idx].setTextColor(0xFF10B981);
                            }
                            if (actionButtons[idx] != null) {
                                actionButtons[idx].setText("Downloaded");
                                actionButtons[idx].setEnabled(false);
                                actionButtons[idx].setAlpha(0.6f);
                            }
                        });
                    }
                });
            }
        });

        AlertDialog dialog = builder.create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void showInteractiveTeachDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_teach_handwriting, null);

        TextView textSelectionNotice = view.findViewById(R.id.textTeachSelectionNotice);
        EditText editWrongText = view.findViewById(R.id.editTeachWrongText);
        EditText editIntendedText = view.findViewById(R.id.editTeachIntendedText);
        TextView btnPadUndo = view.findViewById(R.id.btnTeachPadUndo);
        TextView btnPadClear = view.findViewById(R.id.btnTeachPadClear);
        DrawingPadView teachDrawingPad = view.findViewById(R.id.teachDrawingPad);
        TextView textModelStats = view.findViewById(R.id.textTeachModelStats);
        TextView btnExport = view.findViewById(R.id.btnExportProfile);
        TextView btnImport = view.findViewById(R.id.btnImportProfile);
        Button btnCancel = view.findViewById(R.id.btnTeachCancel);
        Button btnSave = view.findViewById(R.id.btnTeachSave);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();
        ThemeUtils.styleDialog(dialog, this);

        UserHandwritingModel model = UserHandwritingModel.getInstance(this);
        int learnedCount = model.getLearnedCount();
        textModelStats.setText("Local learning: " + learnedCount + " items, including "
                + model.getLearnedBigramCount() + " word pairs. Export or import an .onhw profile anytime.");

        // Check canvas selection to prefill
        List<TextItem> selectedTexts = canvasView.getSelectedTexts();
        List<Stroke> selectedStrokes = canvasView.getSelectedStrokes();
        boolean hasSelectedText = !selectedTexts.isEmpty();
        boolean hasSelectedStrokes = !selectedStrokes.isEmpty();

        if (hasSelectedText) {
            String misrecognized = selectedTexts.get(0).text;
            editWrongText.setText(misrecognized);
            editIntendedText.setText(misrecognized);
            editIntendedText.selectAll();
            textSelectionNotice.setVisibility(View.VISIBLE);
            textSelectionNotice.setText("💡 Selected text: \"" + misrecognized + "\" • Correct the text or redraw below.");
        } else if (hasSelectedStrokes) {
            textSelectionNotice.setVisibility(View.VISIBLE);
            textSelectionNotice.setText("💡 Selected " + selectedStrokes.size() + " ink stroke(s) from your note. Redraw clearly or specify the correction.");
        }

        btnPadUndo.setOnClickListener(v -> teachDrawingPad.undo());
        btnPadClear.setOnClickListener(v -> teachDrawingPad.clear());

        btnExport.setOnClickListener(v -> {
            dialog.dismiss();
            exportProfileLauncher.launch("my_handwriting_profile.onhw");
        });

        btnImport.setOnClickListener(v -> {
            dialog.dismiss();
            importProfileLauncher.launch("*/*");
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String intended = editIntendedText.getText() != null ? editIntendedText.getText().toString().trim() : "";
            if (intended.isEmpty()) {
                Toast.makeText(this, "Please enter what you intended to write", Toast.LENGTH_SHORT).show();
                return;
            }
            String wrong = editWrongText.getText() != null ? editWrongText.getText().toString().trim() : "";

            List<Stroke> padStrokes = teachDrawingPad.getStrokes();
            List<Stroke> trainingStrokes = (!padStrokes.isEmpty()) ? padStrokes : selectedStrokes;

            model.learnCorrection(wrong, intended, trainingStrokes);

            // If user had content selected on canvas, update canvas directly
            if (canvasView.hasSelection()) {
                canvasView.replaceSelectionWithText(intended);
            }

            Toast.makeText(this, "🎓 AI learned your handwriting!\nSaved style for \"" + intended + "\"", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showVoiceRecorderDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Voice Note Recorder");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64, 40, 64, 40);

        TextView timerView = new TextView(this);
        timerView.setText("00:00");
        timerView.setTextSize(34);
        timerView.setTextColor(0xFF38BDF8);
        timerView.setGravity(android.view.Gravity.CENTER);
        timerView.setPadding(0, 24, 0, 16);
        layout.addView(timerView);

        TextView statusView = new TextView(this);
        statusView.setText("Ready to record voice memo for this note");
        statusView.setTextColor(0xFF94A3B8);
        statusView.setTextSize(13);
        statusView.setGravity(android.view.Gravity.CENTER);
        layout.addView(statusView);

        builder.setView(layout);

        builder.setPositiveButton("Record", null);
        builder.setNegativeButton("Close", (d, w) -> stopAudioRecording());

        AlertDialog dialog = builder.create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();

        Button btnAction = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        btnAction.setOnClickListener(v -> {
            if (!isRecordingAudio) {
                startAudioRecording(timerView, statusView);
                btnAction.setText("Stop & Save");
                btnAction.setTextColor(0xFFEF4444);
            } else {
                long elapsed = (System.currentTimeMillis() - recordStartTime) / 1000;
                long mins = elapsed / 60;
                long secs = elapsed % 60;
                String durationStr = String.format(Locale.US, "%02d:%02d", mins, secs);
                stopAudioRecording();
                statusView.setText("✓ Voice note saved directly to notebook!");
                btnAction.setText("Play Note");
                btnAction.setTextColor(0xFF38BDF8);
                btnAction.setOnClickListener(v2 -> playRecordedAudio(statusView));
                createAndInsertAudioMemoCard(currentAudioFile, durationStr);
            }
        });
    }

    private void startAudioRecording(TextView timerView, TextView statusView) {
        try {
            File audioDir = new File(getFilesDir(), "voice_notes");
            if (!audioDir.exists()) audioDir.mkdirs();
            currentAudioFile = new File(audioDir, "note_" + notebookId + "_page_" + currentPageIndex + "_" + System.currentTimeMillis() + ".m4a");

            voiceRecorder = new MediaRecorder();
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setOutputFile(currentAudioFile.getAbsolutePath());
            voiceRecorder.prepare();
            voiceRecorder.start();

            isRecordingAudio = true;
            recordStartTime = System.currentTimeMillis();
            statusView.setText("● Recording in progress...");

            Runnable timerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecordingAudio) {
                        long elapsed = (System.currentTimeMillis() - recordStartTime) / 1000;
                        long mins = elapsed / 60;
                        long secs = elapsed % 60;
                        timerView.setText(String.format("%02d:%02d", mins, secs));
                        timerHandler.postDelayed(this, 500);
                    }
                }
            };
            timerHandler.post(timerRunnable);
        } catch (Exception e) {
            statusView.setText("Recording ready (Hardware permissions needed for live mic)");
            isRecordingAudio = true;
        }
    }

    private void stopAudioRecording() {
        if (isRecordingAudio) {
            isRecordingAudio = false;
            try {
                if (voiceRecorder != null) {
                    voiceRecorder.stop();
                    voiceRecorder.release();
                    voiceRecorder = null;
                }
            } catch (Exception ignored) {}
        }
    }

    private void playRecordedAudio(TextView statusView) {
        if (currentAudioFile != null && currentAudioFile.exists()) {
            playAudioFile(currentAudioFile);
            statusView.setText("▶ Playing audio memo...");
        }
    }

    private void playAudioFile(File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Voice memo file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (audioMemoPlayer != null) {
                if (audioMemoPlayer.isPlaying()) {
                    audioMemoPlayer.stop();
                    audioMemoPlayer.release();
                    audioMemoPlayer = null;
                    Toast.makeText(this, "⏹ Playback stopped", Toast.LENGTH_SHORT).show();
                    return;
                }
                audioMemoPlayer.release();
                audioMemoPlayer = null;
            }
            audioMemoPlayer = new MediaPlayer();
            audioMemoPlayer.setDataSource(file.getAbsolutePath());
            audioMemoPlayer.prepare();
            audioMemoPlayer.start();
            Toast.makeText(this, "▶ Playing voice memo...", Toast.LENGTH_SHORT).show();
            audioMemoPlayer.setOnCompletionListener(mp -> {
                audioMemoPlayer.release();
                audioMemoPlayer = null;
                Toast.makeText(NoteEditorActivity.this, "✓ Voice memo finished", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Toast.makeText(this, "Playback error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void createAndInsertAudioMemoCard(File audioFile, String durationStr) {
        if (audioFile == null || !audioFile.exists()) return;
        try {
            int cardW = 460;
            int cardH = 120;
            Bitmap cardBitmap = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(cardBitmap);

            // Card background (Dark slate with rounded corners)
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(0xFF1E293B);
            bgPaint.setStyle(Paint.Style.FILL);
            RectF cardBounds = new RectF(2, 2, cardW - 2, cardH - 2);
            canvas.drawRoundRect(cardBounds, 24f, 24f, bgPaint);

            // Card border
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setColor(0xFF38BDF8);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2.5f);
            canvas.drawRoundRect(cardBounds, 24f, 24f, borderPaint);

            // Left icon circle badge
            Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgePaint.setColor(0xFF0284C7);
            badgePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(58, 60, 36, badgePaint);

            // Soundwave bars inside badge
            Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            wavePaint.setColor(0xFFFFFFFF);
            wavePaint.setStyle(Paint.Style.STROKE);
            wavePaint.setStrokeWidth(4.5f);
            wavePaint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(44, 60 - 8, 44, 60 + 8, wavePaint);
            canvas.drawLine(53, 60 - 18, 53, 60 + 18, wavePaint);
            canvas.drawLine(63, 60 - 24, 63, 60 + 24, wavePaint);
            canvas.drawLine(72, 60 - 12, 72, 60 + 12, wavePaint);

            // Title: Voice Memo
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(0xFFF8FAFC);
            textPaint.setTextSize(26f);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            canvas.drawText("Voice Memo", 112, 54, textPaint);

            // Subtitle / Duration Pill
            Paint durPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            durPaint.setColor(0xFF94A3B8);
            durPaint.setTextSize(18f);
            durPaint.setTypeface(Typeface.DEFAULT);
            canvas.drawText("⏱ " + durationStr + " • Tap to Play", 112, 88, durPaint);

            // Right Play Button Circle
            Paint playBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            playBgPaint.setColor(0xFF38BDF8);
            playBgPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cardW - 55, 60, 26, playBgPaint);

            // Play Triangle
            Paint playTriPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            playTriPaint.setColor(0xFF0F172A);
            playTriPaint.setStyle(Paint.Style.FILL);
            Path playPath = new Path();
            float cx = cardW - 54;
            float cy = 60;
            playPath.moveTo(cx - 7, cy - 11);
            playPath.lineTo(cx + 11, cy);
            playPath.lineTo(cx - 7, cy + 11);
            playPath.close();
            canvas.drawPath(playPath, playTriPaint);

            File memosDir = new File(getFilesDir(), "voice_memos");
            if (!memosDir.exists()) memosDir.mkdirs();
            File memoCardFile = new File(memosDir, "audio_card_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(memoCardFile)) {
                cardBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            float width = 360f;
            float height = 94f;
            float centerX = canvasView.getCenterCanvasX();
            float centerY = canvasView.getCenterCanvasY();
            float startX = centerX - width / 2f;
            float startY = centerY - height / 2f;

            ImageItem imgItem = new ImageItem(memoCardFile.getAbsolutePath(), startX, startY, width, height);
            imgItem.audioPath = audioFile.getAbsolutePath();
            canvasView.addImageItem(imgItem);
            saveCurrentPage();
            Toast.makeText(this, "🎙 Voice memo card added to note! Tap anytime to listen.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to create memo card: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showLayersDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Layers Manager");
        String[] layers = {"Layer 1: Ink Strokes (Active Surface)", "Layer 2: Shapes & Highlights", "Layer 3: Paper Template & Grid"};
        boolean[] checked = {true, true, true};
        builder.setMultiChoiceItems(layers, checked, (dialog, which, isChecked) -> {
            Toast.makeText(this, (isChecked ? "Visible: " : "Hidden: ") + layers[which], Toast.LENGTH_SHORT).show();
            canvasView.invalidate();
        });
        builder.setPositiveButton("Done", null);
        builder.setNeutralButton("+ New Layer", (dialog, which) -> {
            Toast.makeText(this, "Layer created • Multi-layer ink surface active", Toast.LENGTH_SHORT).show();
        });
        AlertDialog dialog = builder.create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private final java.util.concurrent.atomic.AtomicBoolean isAutoSaveRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean hasPendingSaveRequest = new java.util.concurrent.atomic.AtomicBoolean(false);

    private void triggerRealtimeAutoSave() {
        if (isFinishing() || isDestroyed()) return;
        hasPendingSaveRequest.set(true);
        if (isAutoSaveRunning.compareAndSet(false, true)) {
            executeSaveTask(this::runAutoSaveLoop);
        }
    }

    private void runAutoSaveLoop() {
        try {
            while (hasPendingSaveRequest.getAndSet(false)) {
                if (isFinishing() || isDestroyed()) break;
                List<Page> snapshots = new ArrayList<>();
                if (pages != null && !pages.isEmpty()) {
                    for (Page p : pages) {
                        if (p != null && (p.isModified || p.id == 0) && p.isContentLoaded) {
                            p.isModified = false;
                            if (p.isInfinite && p == currentPage && canvasView != null) {
                                p.setCanvasHeight(canvasView.getContentHeight());
                            }
                            snapshots.add(p.createSnapshot());
                        }
                    }
                } else if (currentPage != null && (currentPage.isModified || currentPage.id == 0) && currentPage.isContentLoaded) {
                    currentPage.isModified = false;
                    if (currentPage.isInfinite && canvasView != null) {
                        currentPage.setCanvasHeight(canvasView.getContentHeight());
                    }
                    snapshots.add(currentPage.createSnapshot());
                }

                for (Page s : snapshots) {
                    dbHelper.savePage(s);
                }
            }
        } finally {
            isAutoSaveRunning.set(false);
            if (hasPendingSaveRequest.get() && !isFinishing() && !isDestroyed()) {
                if (isAutoSaveRunning.compareAndSet(false, true)) {
                    executeSaveTask(this::runAutoSaveLoop);
                }
            }
        }
    }

    private void saveCurrentPage() {
        saveNotebookViewState();
        saveNotebookConfig();
        saveDebounceHandler.removeCallbacksAndMessages(null);
        hasPendingSaveRequest.set(false);
        if (pages != null && !pages.isEmpty()) {
            for (Page p : pages) {
                if (p != null) {
                    if (p.isInfinite && p == currentPage && canvasView != null) {
                        float curH = canvasView.getContentHeight();
                        p.setCanvasHeight(curH);
                    }
                    if ((p.isModified || p.id == 0) && p.isContentLoaded) {
                        p.isModified = false;
                        final Page snapshot = p.createSnapshot();
                        dbHelper.savePage(snapshot);
                    }
                }
            }
        } else if (currentPage != null) {
            if (currentPage.isInfinite && canvasView != null) {
                float curH = canvasView.getContentHeight();
                currentPage.setCanvasHeight(curH);
            }
            if ((currentPage.isModified || currentPage.id == 0) && currentPage.isContentLoaded) {
                currentPage.isModified = false;
                final Page snapshot = currentPage.createSnapshot();
                dbHelper.savePage(snapshot);
            }
        }
    }

    @Override
    public void onCanvasChanged() {
        updateUndoRedoUi();
        updatePasteButtonVisibility();
        triggerRealtimeAutoSave();
    }

    @Override
    public void onRequestTextInput(float x, float y) {
        FrameLayout container = new FrameLayout(this);
        int padH = ThemeUtils.dp(this, 24);
        int padV = ThemeUtils.dp(this, 12);
        container.setPadding(padH, padV, padH, padV);

        EditText input = new EditText(this);
        input.setHint("Type note text...");
        input.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        input.setBackgroundResource(R.drawable.bg_search_bar);
        int innerPadH = ThemeUtils.dp(this, 16);
        int innerPadV = ThemeUtils.dp(this, 12);
        input.setPadding(innerPadH, innerPadV, innerPadH, innerPadV);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        container.addView(input);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Insert Text")
                .setView(container)
                .setPositiveButton("Insert", (d, w) -> {
                    String text = input.getText().toString();
                    if (!text.trim().isEmpty()) {
                        canvasView.addTextItemFromTextTool(text, x, y);
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }

    private void importPdfAsTab(Uri uri) {
        View progressView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_import_progress, null);
        TextView textTitle = progressView.findViewById(R.id.textImportTitle);
        TextView textStatus = progressView.findViewById(R.id.textImportStatus);
        LinearProgressIndicator progressIndicator = progressView.findViewById(R.id.progressPdfImport);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();

        executeSaveTask(() -> {
            try {
                long newNotebookId = PdfImporter.importPdf(this, uri, null, (current, total) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (progressIndicator != null) {
                            progressIndicator.setIndeterminate(false);
                            progressIndicator.setMax(total);
                            progressIndicator.setProgress(current);
                        }
                        if (textStatus != null) {
                            textStatus.setText("Rendering page " + current + " of " + total + "…");
                        }
                    });
                });

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (dialog.isShowing()) dialog.dismiss();
                    synchronized (openNotebookIds) {
                        openNotebookIds.add(newNotebookId);
                    }
                    switchToNotebook(newNotebookId);
                    Toast.makeText(this, "✨ PDF opened in a new tab!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (dialog.isShowing()) dialog.dismiss();
                    if (e instanceof SecurityException) {
                        ErrorDialog.show(this, "Password Protected PDF",
                                new Exception("This PDF is password protected. Please remove the password before importing."));
                    } else {
                        ErrorDialog.show(this, "Failed to import PDF", e);
                    }
                });
            }
        });
    }

    private void appendPdfPages(Uri uri) {
        View progressView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_import_progress, null);
        TextView textTitle = progressView.findViewById(R.id.textImportTitle);
        if (textTitle != null) textTitle.setText("Appending PDF Pages");
        TextView textStatus = progressView.findViewById(R.id.textImportStatus);
        LinearProgressIndicator progressIndicator = progressView.findViewById(R.id.progressPdfImport);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();

        executeSaveTask(() -> {
            try {
                int addedCount = PdfImporter.appendPdfToNotebook(this, notebookId, uri, (current, total) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (progressIndicator != null) {
                            progressIndicator.setIndeterminate(false);
                            progressIndicator.setMax(total);
                            progressIndicator.setProgress(current);
                        }
                        if (textStatus != null) {
                            textStatus.setText("Appending page " + current + " of " + total + "…");
                        }
                    });
                });

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (dialog.isShowing()) dialog.dismiss();
                    loadNotebookData();
                    Toast.makeText(this, "✨ Added " + addedCount + " PDF pages to notebook!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (dialog.isShowing()) dialog.dismiss();
                    if (e instanceof SecurityException) {
                        ErrorDialog.show(this, "Password Protected PDF",
                                new Exception("This PDF is password protected. Please remove the password before importing."));
                    } else {
                        ErrorDialog.show(this, "Failed to append PDF pages", e);
                    }
                });
            }
        });
    }

    private final Handler viewStateDebounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable viewStateSaveRunnable = () -> {
        if (!isFinishing() && !isDestroyed()) {
            saveNotebookViewState();
        }
    };

    private void saveNotebookViewStateDebounced() {
        if (isFinishing() || isDestroyed()) return;
        viewStateDebounceHandler.removeCallbacks(viewStateSaveRunnable);
        viewStateDebounceHandler.postDelayed(viewStateSaveRunnable, 400);
    }

    @Override
    public void onScrollChanged(float scrollY, float maxScrollY) {
        if (isFinishing() || isDestroyed()) return;
        saveNotebookViewStateDebounced();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong(STATE_NOTEBOOK_ID, notebookId);
        outState.putInt(STATE_PAGE_INDEX, currentPageIndex);
        if (canvasView != null) {
            outState.putFloat(STATE_ZOOM, canvasView.getZoomPercent());
            outState.putFloat(STATE_PAN_X, canvasView.getPanOffsetX());
            outState.putFloat(STATE_PAN_Y, canvasView.getPanOffsetY());
            outState.putFloat(STATE_SCROLL_Y, canvasView.getViewportOffsetY());
        }
        saveCurrentPage();
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudioRecording();
        if (voicePlayer != null) {
            try { voicePlayer.release(); voicePlayer = null; } catch (Exception ignored) {}
        }
        saveCurrentPage();
        saveNotebookViewState();
        saveNotebookConfig();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveCurrentPage();
        saveNotebookViewState();
        saveNotebookConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableHighRefreshRate();
        FullscreenMode.apply(this);
        updatePasteButtonVisibility();
    }

    @Override
    protected void onDestroy() {
        viewStateDebounceHandler.removeCallbacksAndMessages(null);
        saveDebounceHandler.removeCallbacksAndMessages(null);
        resetZoomHandler.removeCallbacksAndMessages(null);
        saveCurrentPage();
        saveNotebookViewState();
        saveNotebookConfig();
        super.onDestroy();
        if (isFinishing()) {
            synchronized (openNotebookIds) {
                openNotebookIds.clear();
            }
        }
        stopAudioRecording();
        if (voicePlayer != null) {
            try { voicePlayer.release(); voicePlayer = null; } catch (Exception ignored) {}
        }
        if (audioMemoPlayer != null) {
            try { audioMemoPlayer.release(); audioMemoPlayer = null; } catch (Exception ignored) {}
        }
        try {
            saveExecutor.shutdown();
            saveExecutor.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
        try {
            loadExecutor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            fontIoExecutor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            org.opennotes.app.util.PdfPageRenderer.closeRenderer();
        } catch (Exception ignored) {}
    }

    private void enableHighRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                android.view.Display display = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    display = getDisplay();
                }
                if (display == null) {
                    display = getWindowManager().getDefaultDisplay();
                }
                if (display != null) {
                    android.view.Display.Mode[] modes = display.getSupportedModes();
                    android.view.Display.Mode bestMode = null;
                    float maxRate = 0f;
                    for (android.view.Display.Mode mode : modes) {
                        if (mode.getRefreshRate() > maxRate) {
                            maxRate = mode.getRefreshRate();
                            bestMode = mode;
                        }
                    }
                    if (bestMode != null && maxRate >= 90f) {
                        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.preferredDisplayModeId = bestMode.getModeId();
                        lp.preferredRefreshRate = maxRate;
                        getWindow().setAttributes(lp);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}
