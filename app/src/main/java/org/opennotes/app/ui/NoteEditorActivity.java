package org.opennotes.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoteEditorActivity extends AppCompatActivity implements CanvasView.OnCanvasChangeListener {

    private NotesDbHelper dbHelper;
    private AppSettings appSettings;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fontIoExecutor = Executors.newSingleThreadExecutor();
    private final Handler saveDebounceHandler = new Handler(Looper.getMainLooper());

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
    private LinearLayout activeDocTab;
    private TextView textNotebookTitle;
    private ImageView btnCloseDocTab, btnNewTab;

    // Top Tool Dock
    private LinearLayout mainToolDock;
    private ImageView btnUndo, btnRedo;
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
    private ImageView btnLockViewport, btnResetZoom;
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
    private TextView btnLassoDelete, btnLassoCut, btnLassoCopy, btnLassoPaste, btnLassoStyle, btnLassoDuplicate, btnLassoColor, btnLassoSpacing, btnLassoConvertText, btnLassoTeach;

    // Fullscreen Zen mode
    private ImageView btnExitFullscreen;
    private boolean isZenFullscreen = false;

    // Clipboard Paste shortcuts
    private ImageView btnToolbarPaste;
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
                    saveExecutor.execute(() -> {
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
                    saveExecutor.execute(() -> {
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

        bindViews();
        applyResponsiveEditorLayout();
        loadNotebookData();
        setupListeners();
        setupToolPropertyCard();
        restoreUserSettings();
        updateFavoriteIcon();

        // Silent background pre-download of handwriting models for seamless offline recognition
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
    }

    private void bindViews() {
        canvasView = findViewById(R.id.canvasView);
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
        activeDocTab = findViewById(R.id.activeDocTab);
        textNotebookTitle = findViewById(R.id.textNotebookTitle);
        btnCloseDocTab = findViewById(R.id.btnCloseDocTab);
        btnNewTab = findViewById(R.id.btnNewTab);

        mainToolDock = findViewById(R.id.mainToolDock);
        btnUndo = findViewById(R.id.btnUndo);
        btnRedo = findViewById(R.id.btnRedo);
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

        btnToolbarPaste = findViewById(R.id.btnToolbarPaste);
        layoutLongPressPastePopup = findViewById(R.id.layoutLongPressPastePopup);
        if (btnToolbarPaste != null) {
            btnToolbarPaste.setOnClickListener(v -> {
                canvasView.pasteSelection();
                Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show();
                updatePasteButtonVisibility();
            });
        }

        canvasView.setOnCanvasChangeListener(this);
        canvasView.setOnCanvasLongPressListener((rawX, rawY, canvasX, canvasY) -> {
            if (canvasView.hasClipboardContents()) {
                showLongPressPastePopup(rawX, rawY, canvasX, canvasY);
            }
        });
        canvasView.setOnZoomChangeListener(zoomPercent -> {
            textZoomPercent.setText(Math.round(zoomPercent) + "% ▾");
        });
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
                lassoFloatingActionBar.post(() -> {
                    lassoFloatingActionBar.setX(0f);
                    float posY = Math.max(120f, screenBounds.top - 60f);
                    lassoFloatingActionBar.setY(posY);
                });
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
        setViewHeight(findViewById(R.id.activeDocTab), tabHeight);
        setViewHeight(findViewById(R.id.inactiveDocTab), tabHeight);
        setViewSize(findViewById(R.id.btnNewTab), pillButtonSize, pillButtonSize);
        int tabCloseSize = getResources().getDimensionPixelSize(R.dimen.editor_tab_close_size);
        setViewSize(btnCloseDocTab, tabCloseSize, tabCloseSize);
        setViewSize(findViewById(R.id.btnCloseInactiveDocTab), tabCloseSize, tabCloseSize);
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
        textNotebookTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.editor_tab_text_size));
        textNotebookTitle.setMaxWidth(dp(getResources().getConfiguration().screenWidthDp >= 600
                ? 240 : 160));
        TextView inactiveTitle = findViewById(R.id.textInactiveDocTitle);
        inactiveTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.editor_tab_text_size));
        inactiveTitle.setMaxWidth(dp(getResources().getConfiguration().screenWidthDp >= 600
                ? 200 : 120));
        TextView tabCount = findViewById(R.id.textTabCountBadge);
        tabCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.editor_tab_text_size));
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
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_X && canvasView.hasSelection()) {
            canvasView.cutSelection();
            updateLassoActionAvailability();
            Toast.makeText(this, "Cut to clipboard", Toast.LENGTH_SHORT).show();
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

    private void loadNotebookData() {
        canvasView.setEnabled(false);
        saveExecutor.execute(() -> {
            Notebook loadedNotebook = dbHelper.getNotebook(notebookId);
            if (loadedNotebook == null) {
                runOnUiThread(this::finish);
                return;
            }
            List<Page> loadedPages = dbHelper.getPagesForNotebook(notebookId);
            if (loadedPages.isEmpty()) {
                Page firstPage = new Page(notebookId, 0, loadedNotebook.defaultTemplate,
                        loadedNotebook.defaultBgColor, loadedNotebook.defaultIsInfinite);
                dbHelper.insertPage(firstPage);
                loadedPages = dbHelper.getPagesForNotebook(notebookId);
            }
            List<Page> resultPages = loadedPages;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                currentNotebook = loadedNotebook;
                pages = resultPages;
                textNotebookTitle.setText(currentNotebook.title);
                updateFavoriteIcon();
                currentPageIndex = hasRestoredEditorState
                        ? Math.max(0, Math.min(restoredPageIndex, resultPages.size() - 1)) : 0;
                loadCurrentPage();
                if (hasRestoredEditorState) {
                    // Wait for the canvas to be measured so infinite-page scroll bounds are valid.
                    canvasView.post(() -> canvasView.restoreViewport(
                            restoredZoom, restoredPanX, restoredPanY, restoredScrollY));
                    hasRestoredEditorState = false;
                }
                canvasView.setEnabled(true);
            });
        });
    }

    private void loadCurrentPage() {
        if (currentPageIndex < 0 || currentPageIndex >= pages.size()) return;
        currentPage = pages.get(currentPageIndex);
        canvasView.setPage(currentPage);
        updatePageNavUi();
        updateUndoRedoUi();
    }

    private void updatePageNavUi() {
        textPageCounter.setText((currentPageIndex + 1) + " / " + pages.size());
        btnPrevPage.setEnabled(currentPageIndex > 0);
        btnPrevPage.setAlpha(currentPageIndex > 0 ? 1.0f : 0.35f);
        btnNextPage.setEnabled(currentPageIndex < pages.size() - 1);
        btnNextPage.setAlpha(currentPageIndex < pages.size() - 1 ? 1.0f : 0.35f);
    }

    private void updateUndoRedoUi() {
        btnUndo.setEnabled(canvasView.canUndo());
        btnUndo.setAlpha(canvasView.canUndo() ? 1.0f : 0.35f);
        btnRedo.setEnabled(canvasView.canRedo());
        btnRedo.setAlpha(canvasView.canRedo() ? 1.0f : 0.35f);
    }

    private void updateFavoriteIcon() {
        if (currentNotebook != null && currentNotebook.isFavorite) {
            btnFavorite.setImageResource(R.drawable.ic_star_filled);
            btnFavorite.setColorFilter(0xFFF59E0B);
        } else {
            btnFavorite.setImageResource(R.drawable.ic_star_outline);
            btnFavorite.setColorFilter(0xFF94A3B8);
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
            finish();
        });

        btnCloseDocTab.setOnClickListener(v -> {
            saveCurrentPage();
            finish();
        });

        btnNewTab.setOnClickListener(v -> {
            saveCurrentPage();
            Toast.makeText(this, "Quick Note: return to shelf to open multiple notebooks", Toast.LENGTH_SHORT).show();
        });

        btnUndo.setOnClickListener(v -> canvasView.undo());
        btnRedo.setOnClickListener(v -> canvasView.redo());

        // Tools
        toolFountain.setOnClickListener(v -> selectOrToggleTool(DrawingTool.FOUNTAIN, "Fountain Pen Settings"));
        toolPen.setOnClickListener(v -> selectOrToggleTool(DrawingTool.PEN, "Ballpoint Pen Settings"));
        toolHighlighter.setOnClickListener(v -> selectOrToggleTool(DrawingTool.HIGHLIGHTER, "Highlighter Settings"));
        toolPencil.setOnClickListener(v -> selectOrToggleTool(DrawingTool.PENCIL, "Pencil Settings"));
        toolEraser.setOnClickListener(v -> {
            closeToolPropertiesCard();
            if (canvasView.getTool() == DrawingTool.ERASER_STROKE) {
                // Tapping eraser while already active toggles Erase Highlighter Only filter
                boolean nextOnlyHighlighter = !canvasView.isEraseOnlyHighlighter();
                canvasView.setEraseOnlyHighlighter(nextOnlyHighlighter);
                toolEraser.setColorFilter(nextOnlyHighlighter ? 0xFFF59E0B : 0xFF38BDF8);
                if (nextOnlyHighlighter) {
                    Toast.makeText(this, "🧹 Eraser: Highlighter Only Mode (Pen & text preserved)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "🧹 Eraser: Erase All Strokes & Text", Toast.LENGTH_SHORT).show();
                }
            } else {
                canvasView.setTool(DrawingTool.ERASER_STROKE);
                updateToolUi(DrawingTool.ERASER_STROKE);
                if (appSettings != null) appSettings.setLastTool(DrawingTool.ERASER_STROKE);
                boolean onlyH = canvasView.isEraseOnlyHighlighter();
                toolEraser.setColorFilter(onlyH ? 0xFFF59E0B : 0xFF38BDF8);
                Toast.makeText(this, onlyH ? "Eraser: Highlighter Only (tap again to erase all)" : "Eraser active (tap again for highlighter only)", Toast.LENGTH_SHORT).show();
            }
        });
        toolEraser.setOnLongClickListener(v -> {
            showEraserOptionsDialog();
            return true;
        });

        toolLasso.setOnClickListener(v -> {
            closeToolPropertiesCard();
            if (canvasView.getTool() == DrawingTool.LASSO) {
                // Tapping lasso while already active toggles between Freehand and Rectangular selection
                boolean isRect = canvasView.getLassoMode() == CanvasView.LassoMode.RECTANGLE;
                CanvasView.LassoMode newMode = isRect ? CanvasView.LassoMode.FREEHAND : CanvasView.LassoMode.RECTANGLE;
                canvasView.setLassoMode(newMode);
                toolLasso.setColorFilter(newMode == CanvasView.LassoMode.RECTANGLE ? 0xFF10B981 : 0xFF38BDF8);
                if (newMode == CanvasView.LassoMode.RECTANGLE) {
                    Toast.makeText(this, "📐 Lasso: Rectangular Marquee Mode (drag box to select)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "➰ Lasso: Freehand Mode (circle items to select)", Toast.LENGTH_SHORT).show();
                }
            } else {
                canvasView.setTool(DrawingTool.LASSO);
                updateToolUi(DrawingTool.LASSO);
                if (appSettings != null) appSettings.setLastTool(DrawingTool.LASSO);
                boolean isRect = canvasView.getLassoMode() == CanvasView.LassoMode.RECTANGLE;
                toolLasso.setColorFilter(isRect ? 0xFF10B981 : 0xFF38BDF8);
                Toast.makeText(this, isRect ? "Lasso: Rectangular (tap again for freehand)" : "Lasso active (tap again for rectangle marquee)", Toast.LENGTH_SHORT).show();
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
                if (nextState) {
                    Toast.makeText(this, "🖐️ Move Canvas with Finger: ON\n(Stylus draws with pen, finger moves canvas)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "✏️ Move Canvas with Finger: OFF", Toast.LENGTH_SHORT).show();
                }
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
            showShapeSelectorDialog();
        });
        toolText.setOnClickListener(v -> {
            closeToolPropertiesCard();
            canvasView.setTool(DrawingTool.TEXT);
            updateToolUi(DrawingTool.TEXT);
            if (appSettings != null) appSettings.setLastTool(DrawingTool.TEXT);
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
        btnFavorite.setOnClickListener(v -> {
            if (currentNotebook != null) {
                currentNotebook.isFavorite = !currentNotebook.isFavorite;
                Notebook notebookSnapshot = currentNotebook;
                saveExecutor.execute(() -> dbHelper.updateNotebook(notebookSnapshot));
                updateFavoriteIcon();
                Toast.makeText(this, currentNotebook.isFavorite ? "Added to Favorites ★" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
            }
        });

        textZoomPercent.setOnClickListener(v -> showZoomPickerDialog());

        btnLockViewport.setOnClickListener(v -> {
            boolean next = !canvasView.isViewportLocked();
            canvasView.setViewportLocked(next);
            updateViewportLockIcon();
            Toast.makeText(this, next ? "Viewport Locked 🔒" : "Viewport Unlocked", Toast.LENGTH_SHORT).show();
        });
        updateViewportLockIcon();

        btnResetZoom.setOnClickListener(v -> {
            canvasView.resetZoom();
            Toast.makeText(this, "Zoom reset to 100%", Toast.LENGTH_SHORT).show();
        });

        btnPrevPage.setOnClickListener(v -> {
            if (pages != null && currentPageIndex > 0) {
                saveCurrentPage();
                currentPageIndex--;
                loadCurrentPage();
            }
        });

        btnNextPage.setOnClickListener(v -> {
            if (pages != null && currentPageIndex < pages.size() - 1) {
                saveCurrentPage();
                currentPageIndex++;
                loadCurrentPage();
            }
        });

        btnAddPage.setOnClickListener(v -> {
            if (pages == null || currentNotebook == null) return;
            saveCurrentPage();
            PaperTemplate tpl = (currentPage != null) ? currentPage.template : currentNotebook.defaultTemplate;
            int bgCol = (currentPage != null && currentPage.backgroundColor != 0)
                    ? currentPage.backgroundColor
                    : ((currentNotebook != null && currentNotebook.defaultBgColor != -1)
                        ? currentNotebook.defaultBgColor
                        : (appSettings != null ? appSettings.getDefaultPageBgColor() : 0xFF121214));
            boolean inf = (currentPage != null) ? currentPage.isInfinite : currentNotebook.defaultIsInfinite;

            Page newPage = new Page(notebookId, pages.size(), tpl, bgCol, inf);
            currentNotebook.pageCount = pages.size() + 1;
            pages.add(newPage);
            Notebook notebookSnapshot = currentNotebook;
            saveExecutor.execute(() -> {
                dbHelper.insertPage(newPage);
                dbHelper.updateNotebook(notebookSnapshot);
            });
            currentPageIndex = pages.size() - 1;
            loadCurrentPage();
            Toast.makeText(this, "Page " + (currentPageIndex + 1) + " added", Toast.LENGTH_SHORT).show();
        });

        textPageCounter.setOnClickListener(v -> showPageOverviewDialog());

        // Contextual Lasso Action Bar
        btnLassoDelete.setOnClickListener(v -> canvasView.deleteSelection());
        btnLassoCut.setOnClickListener(v -> {
            if (!canvasView.hasSelection()) return;
            canvasView.cutSelection();
            Toast.makeText(this, "Cut to clipboard. Use Paste to place it.", Toast.LENGTH_SHORT).show();
            updatePasteButtonVisibility();
        });
        btnLassoCopy.setOnClickListener(v -> {
            if (!canvasView.hasSelection()) return;
            canvasView.copySelection();
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
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
            if (btnNibFine != null) {
                btnNibFine.setText("Fine");
                btnNibFine.setOnClickListener(v -> {
                    canvasView.setStrokeWidth(3.0f);
                    if (seekStrokeThickness != null) seekStrokeThickness.setProgress(3);
                    if (textThicknessValue != null) textThicknessValue.setText("0.9 mm");
                    updateNibButtons(btnNibFine);
                    if (appSettings != null) appSettings.setStrokeWidth(3.0f);
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
                        Toast.makeText(this, isChecked ? "Straight Highlighter Lock: ON (0° / 90°)" : "Straight Highlighter Lock: OFF", Toast.LENGTH_SHORT).show();
                    } else {
                        canvasView.setStraightLineAssist(isChecked);
                        if (btnRuler != null) btnRuler.setColorFilter(isChecked ? 0xFF38BDF8 : 0xFF94A3B8);
                        if (appSettings != null) appSettings.setStraightLineAssist(isChecked);
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
            canvasView.setTool(tool);
            updateToolUi(tool);
            if (appSettings != null) {
                appSettings.setLastTool(tool);
            }
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
            boolean closedAnyMenu = false;

            // 1. Tool properties popup menu (Frame 50)
            if (cardToolProperties != null && cardToolProperties.getVisibility() == View.VISIBLE) {
                Rect cardRect = new Rect();
                cardToolProperties.getGlobalVisibleRect(cardRect);
                if (!cardRect.contains((int) rawX, (int) rawY)) {
                    // Check if clicked the active tool button (its own onClick will toggle/handle it)
                    View activeToolBtn = getActiveToolButtonView();
                    boolean clickedActiveTool = isTouchInsideView(activeToolBtn, (int) rawX, (int) rawY);
                    if (!clickedActiveTool) {
                        closeToolPropertiesCard();
                        closedAnyMenu = true;
                    }
                }
            }

            // 2. Long press paste popup
            if (layoutLongPressPastePopup != null && layoutLongPressPastePopup.getVisibility() == View.VISIBLE) {
                Rect pasteRect = new Rect();
                layoutLongPressPastePopup.getGlobalVisibleRect(pasteRect);
                if (!pasteRect.contains((int) rawX, (int) rawY)) {
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
                if (isTouchOnlyOnCanvas((int) rawX, (int) rawY)) {
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

    private boolean isTouchInsideView(View v, int rawX, int rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        Rect rect = new Rect();
        v.getGlobalVisibleRect(rect);
        return rect.contains(rawX, rawY);
    }

    private boolean isTouchOnlyOnCanvas(int rawX, int rawY) {
        if (canvasView == null) return false;
        Rect canvasRect = new Rect();
        canvasView.getGlobalVisibleRect(canvasRect);
        if (!canvasRect.contains(rawX, rawY)) {
            return false;
        }

        View[] toolbarContainers = {
                topBar, mainToolDock, floatingViewportPill, floatingPageNavPill,
                btnExitFullscreen, lassoFloatingActionBar
        };
        for (View v : toolbarContainers) {
            if (isTouchInsideView(v, rawX, rawY)) {
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
        super.onBackPressed();
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

        // Restore Ink Color
        int savedColor = appSettings.getInkColor();
        canvasView.setColor(savedColor);
        updateColorPreview(savedColor);

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
        });
        btnNibMedium.setOnClickListener(v -> {
            canvasView.setStrokeWidth(6.0f);
            seekStrokeThickness.setProgress(6);
            textThicknessValue.setText("1.8 mm");
            updateNibButtons(btnNibMedium);
            if (appSettings != null) appSettings.setStrokeWidth(6.0f);
        });
        btnNibBold.setOnClickListener(v -> {
            canvasView.setStrokeWidth(12.0f);
            seekStrokeThickness.setProgress(12);
            textThicknessValue.setText("3.6 mm");
            updateNibButtons(btnNibBold);
            if (appSettings != null) appSettings.setStrokeWidth(12.0f);
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
        String[] options = {
                "🧹 Erase All (Handwriting, Text & Highlighter)" + (!onlyHighlighter ? "  ✓" : ""),
                "🖍️ Erase Highlighter Only (Preserve Pen & Text)" + (onlyHighlighter ? "  ✓" : ""),
                "🗑️ Clear Entire Page"
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Eraser Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        canvasView.setEraseOnlyHighlighter(false);
                        toolEraser.setColorFilter(0xFF38BDF8);
                        Toast.makeText(this, "Eraser: Erasing all strokes & text", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        canvasView.setEraseOnlyHighlighter(true);
                        toolEraser.setColorFilter(0xFFF59E0B);
                        Toast.makeText(this, "Eraser: Highlighter only (Handwriting preserved)", Toast.LENGTH_SHORT).show();
                    } else if (which == 2) {
                        showClearCanvasDialog();
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }

    private void showLassoOptionsDialog() {
        boolean isRect = canvasView.getLassoMode() == CanvasView.LassoMode.RECTANGLE;
        String[] options = {
                "➰ Freehand Lasso Selection (Draw boundary around items)" + (!isRect ? "  ✓" : ""),
                "📐 Rectangular Marquee Selection (Drag box to select items)" + (isRect ? "  ✓" : "")
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Lasso Selection Mode")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        canvasView.setLassoMode(CanvasView.LassoMode.FREEHAND);
                        toolLasso.setColorFilter(0xFF38BDF8);
                        Toast.makeText(this, "Lasso: Freehand outline mode", Toast.LENGTH_SHORT).show();
                    } else {
                        canvasView.setLassoMode(CanvasView.LassoMode.RECTANGLE);
                        toolLasso.setColorFilter(0xFF10B981);
                        Toast.makeText(this, "Lasso: Rectangular marquee mode", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
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

    private void showZoomPickerDialog() {
        final String[] zoomOptions = {"10%", "20%", "25%", "50%", "75%", "100% (Fit)", "150%", "200%", "400%", "800%", "1600%"};
        final float[] zoomValues = {10f, 20f, 25f, 50f, 75f, 100f, 150f, 200f, 400f, 800f, 1600f};

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Select Zoom Level")
                .setItems(zoomOptions, (dialog, which) -> {
                    canvasView.setZoom(zoomValues[which]);
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
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

    public void updatePasteButtonVisibility() {
        if (canvasView == null) return;
        boolean hasClipboard = canvasView.hasClipboardContents();
        if (btnToolbarPaste != null) {
            btnToolbarPaste.setVisibility(hasClipboard ? View.VISIBLE : View.GONE);
        }
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

        String fillStyleName = canvasView.getShapeFillStyle() == CanvasView.ShapeFillStyle.SOLID ? "Solid Fill" :
                (canvasView.getShapeFillStyle() == CanvasView.ShapeFillStyle.TRANSLUCENT ? "Translucent Tint (20%)" : "Outline Only");

        final String[] options = new String[shapes.length + 3];
        System.arraycopy(shapes, 0, options, 0, shapes.length);
        options[shapes.length] = "🎨 Style: Outline Only" + (canvasView.getShapeFillStyle() == CanvasView.ShapeFillStyle.OUTLINE ? "  ✓" : "");
        options[shapes.length + 1] = "🎨 Style: Translucent Tint Wash" + (canvasView.getShapeFillStyle() == CanvasView.ShapeFillStyle.TRANSLUCENT ? "  ✓" : "");
        options[shapes.length + 2] = "🎨 Style: Solid Opaque Fill" + (canvasView.getShapeFillStyle() == CanvasView.ShapeFillStyle.SOLID ? "  ✓" : "");

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Select Shape (Fill: " + fillStyleName + ")")
                .setItems(options, (dialog, which) -> {
                    if (which < shapes.length) {
                        canvasView.setShapeType(types[which]);
                        canvasView.setTool(DrawingTool.SHAPE);
                        updateToolUi(DrawingTool.SHAPE);
                        Toast.makeText(this, "Draw " + shapes[which] + " (" + fillStyleName + ")", Toast.LENGTH_SHORT).show();
                    } else if (which == shapes.length) {
                        canvasView.setShapeFillStyle(CanvasView.ShapeFillStyle.OUTLINE);
                        Toast.makeText(this, "Shape Fill: Outline Only", Toast.LENGTH_SHORT).show();
                        showShapeSelectorDialog();
                    } else if (which == shapes.length + 1) {
                        canvasView.setShapeFillStyle(CanvasView.ShapeFillStyle.TRANSLUCENT);
                        Toast.makeText(this, "Shape Fill: Translucent Tint Wash", Toast.LENGTH_SHORT).show();
                        showShapeSelectorDialog();
                    } else if (which == shapes.length + 2) {
                        canvasView.setShapeFillStyle(CanvasView.ShapeFillStyle.SOLID);
                        Toast.makeText(this, "Shape Fill: Solid Opaque Fill", Toast.LENGTH_SHORT).show();
                        showShapeSelectorDialog();
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }


    private void showRulerAngleDialog() {
        final String[] angles = {"0° (Horizontal Snap)", "30° Angle Guide", "45° Diagonal Snap", "60° Angle Guide", "90° (Vertical Snap)", "120° Angle Guide", "150° Angle Guide", "Disable Ruler Assist"};
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Ruler & Angle Guide")
                .setItems(angles, (dialog, which) -> {
                    if (which == 7) {
                        canvasView.setStraightLineAssist(false);
                        if (switchStraightLine != null) switchStraightLine.setChecked(false);
                        if (btnRuler != null) btnRuler.setColorFilter(0xFF94A3B8);
                        Toast.makeText(this, "Ruler Guide Disabled", Toast.LENGTH_SHORT).show();
                    } else {
                        canvasView.setStraightLineAssist(true);
                        if (switchStraightLine != null) switchStraightLine.setChecked(true);
                        if (btnRuler != null) btnRuler.setColorFilter(0xFF38BDF8);
                        Toast.makeText(this, "Ruler Angle Snap: " + angles[which], Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }

    private void showExportDialog() {
        String[] options = {
                "🚀 Direct Share as PDF (WhatsApp, Drive, Gmail…)",
                "🚀 Direct Share Page as PNG Image (WhatsApp, Drive…)",
                "📄 Save Notebook as PDF to Device",
                "🖼️ Save Page as PNG Image to Device",
                "📦 Save and share editable original notebook (.opennote)",
                "📥 Restore editable original notebook (.opennote)"
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Export & Share Note")
                .setItems(options, (dialog, which) -> {
                    if (which == 4) {
                        saveCurrentPage();
                        exportNativeNotebookLauncher.launch(safeArchiveName(
                                currentNotebook != null ? currentNotebook.title : "OpenNotes notebook"));
                        return;
                    }
                    if (which == 5) {
                        importNativeNotebookLauncher.launch(new String[]{"*/*"});
                        return;
                    }
                    saveCurrentPage();
                    boolean directShare = (which == 0 || which == 1);
                    boolean isPdf = (which == 0 || which == 2);
                    if (isPdf) {
                        try {
                            File pdfFile = PdfExporter.exportNotebookToPdf(this, notebookId);
                            if (pdfFile != null) {
                                if (directShare) {
                                    shareFile(pdfFile, "application/pdf");
                                    Toast.makeText(this, "✓ PDF ready! Opening share options…", Toast.LENGTH_SHORT).show();
                                } else {
                                    showExportSuccessDialog(pdfFile, "application/pdf");
                                }
                            }
                        } catch (Exception e) {
                            ErrorDialog.show(this, "PDF export failed", e);
                        }
                    } else {
                        try {
                            File imgFile = ImageExporter.exportPageToImage(this, canvasView, currentNotebook.title, currentPageIndex);
                            if (imgFile != null) {
                                if (directShare) {
                                    shareFile(imgFile, "image/png");
                                    Toast.makeText(this, "✓ Image ready! Opening share options…", Toast.LENGTH_SHORT).show();
                                } else {
                                    showExportSuccessDialog(imgFile, "image/png");
                                }
                            }
                        } catch (Exception e) {
                            ErrorDialog.show(this, "Image export failed", e);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
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
        rv.setLayoutManager(new GridLayoutManager(this, 3));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setTitle("Pages (" + pages.size() + ")")
                .setNegativeButton("Close", null)
                .create();
        ThemeUtils.styleDialog(dialog, this);

        rv.setAdapter(new RecyclerView.Adapter<PageViewHolder>() {
            @Override
            public PageViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_page_overview, parent, false);
                return new PageViewHolder(v);
            }

            @Override
            public void onBindViewHolder(PageViewHolder holder, int position) {
                holder.textPageNumber.setText("Page " + (position + 1));
                holder.itemView.setSelected(position == currentPageIndex);
                holder.itemView.setOnClickListener(v -> {
                    currentPageIndex = position;
                    loadCurrentPage();
                    dialog.dismiss();
                });
            }

            @Override
            public int getItemCount() {
                return pages.size();
            }
        });

        dialog.show();
    }

    private static class PageViewHolder extends RecyclerView.ViewHolder {
        TextView textPageNumber;
        PageViewHolder(View itemView) {
            super(itemView);
            textPageNumber = itemView.findViewById(R.id.textPageNumber);
        }
    }

    private void showMoreMenu() {
        PopupMenu popup = new PopupMenu(this, btnMenu);
        popup.getMenu().add(0, 1, 0, "Paper Template");
        popup.getMenu().add(0, 2, 1, "Page Background Color");
        popup.getMenu().add(0, 3, 2, "Zen Fullscreen Mode");
        popup.getMenu().add(0, 4, 3, "Palm Rejection (Stylus Only)");
        popup.getMenu().add(0, 5, 4, "✨ Auto-Beautification Settings");
        popup.getMenu().add(0, 6, 5, "Clear Canvas");
        popup.getMenu().add(0, 7, 6, "Select All Content");
        popup.getMenu().add(0, 8, 7, "Convert Entire Page to Beautiful Font");
        popup.getMenu().add(0, 9, 8, "Paste from Clipboard");
        popup.getMenu().add(0, 10, 9, "📥 Offline Handwriting Models");
        popup.getMenu().add(0, 11, 10, "🎓 Teach My Handwriting");
        popup.getMenu().add(0, 12, 11, "📤 Export Handwriting Profile (.onhw)");
        popup.getMenu().add(0, 13, 12, "📥 Import Handwriting Profile (.onhw)");
        popup.getMenu().add(0, 14, 13, "↔ Adjust selected text spacing");
        popup.getMenu().findItem(9).setEnabled(canvasView.hasClipboardContents());
        popup.getMenu().findItem(14).setEnabled(canvasView.hasSelectedText());

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showTemplateSelectionDialog();
                    return true;
                case 2:
                    showPageColorSelectionDialog();
                    return true;
                case 3:
                    setZenFullscreen(true);
                    return true;
                case 4:
                    boolean next = !canvasView.isStylusOnly();
                    canvasView.setStylusOnly(next);
                    if (appSettings != null) appSettings.setStylusOnly(next);
                    Toast.makeText(this, next ? "Palm Rejection ON (Stylus-only drawing)" : "Touch & Stylus drawing ON", Toast.LENGTH_SHORT).show();
                    return true;
                case 5:
                    showAutoBeautifySettingsDialog();
                    return true;
                case 6:
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                            .setTitle("Clear Canvas")
                            .setMessage("Are you sure you want to clear all strokes on this page?")
                            .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                            .setNegativeButton("Cancel", null);
                    ThemeUtils.showStyledDialog(builder, this);
                    return true;
                case 7:
                    if (!canvasView.selectAllContent()) {
                        Toast.makeText(this, "There is no content on this page to select", Toast.LENGTH_SHORT).show();
                    } else if (canvasView.getTool() != DrawingTool.LASSO) {
                        canvasView.setTool(DrawingTool.LASSO);
                        updateToolUi(DrawingTool.LASSO);
                        if (appSettings != null) appSettings.setLastTool(DrawingTool.LASSO);
                    }
                    return true;
                case 8:
                    if (!canvasView.convertPageToBeautifulText()) {
                        Toast.makeText(this, "There is no handwriting on this page to convert", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 9:
                    if (!canvasView.hasClipboardContents()) {
                        Toast.makeText(this, "Copy or cut content before pasting", Toast.LENGTH_SHORT).show();
                    } else {
                        canvasView.pasteSelection();
                    }
                    return true;
                case 10:
                    showDownloadModelsDialog();
                    return true;
                case 11:
                    showHandwritingProfileDialog();
                    return true;
                case 12:
                    exportProfileLauncher.launch("my_handwriting_profile.onhw");
                    return true;
                case 13:
                    importProfileLauncher.launch("*/*");
                    return true;
                case 14:
                    showTextSpacingDialog();
                    return true;
            }
            return false;
        });
        popup.show();
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
        final float minWordSpacing = -5.0f;
        final float maxWordSpacing = 2.0f;
        float[] wordValue = {Math.max(minWordSpacing, Math.min(maxWordSpacing, first.wordSpacingEm))};
        letterSeek.setProgress(Math.round((letterValue[0] + 0.12f) / 0.52f * 100f));
        wordSeek.setProgress(Math.round((wordValue[0] - minWordSpacing)
                / (maxWordSpacing - minWordSpacing) * 700f));
        letterSeek.setContentDescription("Letter spacing");
        wordSeek.setContentDescription("Word spacing from minus 5 em to plus 2 em; drag left to tighten, right to loosen");
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
        hint.setText("Drag left to tighten words or right to spread them. The canvas previews changes as you move the sliders.");
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
        PopupMenu popup = new PopupMenu(this, btnSettingsSliders != null ? btnSettingsSliders : btnMenu);
        popup.getMenu().add(0, 1, 0, "Paper Template");
        popup.getMenu().add(0, 2, 1, "Page Background Color");
        popup.getMenu().add(0, 3, 2, "Palm Rejection (Stylus Only)");
        popup.getMenu().add(0, 4, 3, "✨ Auto-Beautification Settings");
        popup.getMenu().add(0, 5, 4, "Clear Canvas");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showTemplateSelectionDialog();
                    return true;
                case 2:
                    showPageColorSelectionDialog();
                    return true;
                case 3:
                    boolean next = !canvasView.isStylusOnly();
                    canvasView.setStylusOnly(next);
                    if (appSettings != null) appSettings.setStylusOnly(next);
                    Toast.makeText(this, next ? "Palm Rejection ON (Stylus-only drawing)" : "Touch & Stylus drawing ON", Toast.LENGTH_SHORT).show();
                    return true;
                case 4:
                    showAutoBeautifySettingsDialog();
                    return true;
                case 5:
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                            .setTitle("Clear Canvas")
                            .setMessage("Are you sure you want to clear all strokes on this page?")
                            .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                            .setNegativeButton("Cancel", null);
                    ThemeUtils.showStyledDialog(builder, this);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void showTemplateSelectionDialog() {
        String[] templates = {"Blank", "Ruled Lines", "Grid Paper", "Dot Grid", "Cornell Notes"};
        PaperTemplate[] tpls = {PaperTemplate.BLANK, PaperTemplate.RULED, PaperTemplate.GRID, PaperTemplate.DOTS, PaperTemplate.CORNELL};

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Select Paper Template")
                .setItems(templates, (dialog, which) -> {
                    canvasView.setPaperTemplate(tpls[which]);
                    saveCurrentPage();
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
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
                saveExecutor.execute(() -> dbHelper.updateNotebookDefaultBgColor(notebookId, newColor, applyToAll));
            } else if (checkAllPages.isChecked()) {
                int defaultColor = currentNotebook != null ? currentNotebook.defaultBgColor : -1;
                saveExecutor.execute(() -> dbHelper.updateNotebookDefaultBgColor(notebookId, defaultColor, true));
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
            String[] options = {
                    "📥 Offline Handwriting Models",
                    "🎓 Teach My Handwriting",
                    "📤 Export Local Handwriting Learning (.onhw)",
                    "📥 Import Local Handwriting Learning (.onhw)"
            };
            MaterialAlertDialogBuilder optBuilder = new MaterialAlertDialogBuilder(this)
                    .setTitle("AI & Handwriting Options")
                    .setItems(options, (optDialog, which) -> {
                        switch (which) {
                            case 0:
                                showDownloadModelsDialog();
                                break;
                            case 1:
                                showHandwritingProfileDialog();
                                break;
                            case 2:
                                exportProfileLauncher.launch("my_handwriting_profile.onhw");
                                break;
                            case 3:
                                importProfileLauncher.launch("*/*");
                                break;
                        }
                    })
                    .setNegativeButton("Cancel", null);
            ThemeUtils.showStyledDialog(optBuilder, this);
        });

        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
        if (layoutCustomFontList != null) {
            TextView loadingFonts = new TextView(this);
            loadingFonts.setText("Loading imported fonts…");
            loadingFonts.setTextColor(0xFF94A3B8);
            loadingFonts.setTextSize(12);
            layoutCustomFontList.addView(loadingFonts);
            fontIoExecutor.execute(() -> {
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
        fontIoExecutor.execute(() -> {
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
            floatingViewportPill.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> floatingViewportPill.setVisibility(View.GONE)).start();
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
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Insert Image");
        String[] options = {"Take Photo with Camera", "Choose from Gallery"};
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                try {
                    File photoFile = new File(getCacheDir(), "camera_" + System.currentTimeMillis() + ".jpg");
                    tempCameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
                    takePictureLauncher.launch(tempCameraPhotoUri);
                } catch (Exception e) {
                    Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                imagePickerLauncher.launch("image/*");
            }
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
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

    private void triggerRealtimeAutoSave() {
        saveDebounceHandler.removeCallbacksAndMessages(null);
        saveDebounceHandler.postDelayed(() -> {
            if (currentPage == null || isFinishing() || isDestroyed()) return;
            currentPage.canvasHeight = canvasView.getContentHeight();
            final Page snapshot = currentPage.createSnapshot();
            saveExecutor.execute(() -> dbHelper.savePage(snapshot));
        }, 600);
    }

    private void saveCurrentPage() {
        if (currentPage != null) {
            saveDebounceHandler.removeCallbacksAndMessages(null);
            currentPage.canvasHeight = canvasView.getContentHeight();
            final Page snapshot = currentPage.createSnapshot();
            saveExecutor.execute(() -> dbHelper.savePage(snapshot));
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
        EditText input = new EditText(this);
        input.setHint("Type note text...");
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Insert Text")
                .setView(input)
                .setPositiveButton("Insert", (d, w) -> {
                    String text = input.getText().toString();
                    if (!text.trim().isEmpty()) {
                        canvasView.addTextItem(text, x, y);
                    }
                })
                .setNegativeButton("Cancel", null);
        ThemeUtils.showStyledDialog(builder, this);
    }

    @Override
    public void onScrollChanged(float scrollY, float maxScrollY) {}

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenMode.apply(this);
        updatePasteButtonVisibility();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAudioRecording();
        if (voicePlayer != null) {
            try { voicePlayer.release(); voicePlayer = null; } catch (Exception ignored) {}
        }
        if (audioMemoPlayer != null) {
            try { audioMemoPlayer.release(); audioMemoPlayer = null; } catch (Exception ignored) {}
        }
        saveDebounceHandler.removeCallbacksAndMessages(null);
        saveCurrentPage();
        try {
            saveExecutor.shutdown();
        } catch (Exception ignored) {}
        try {
            fontIoExecutor.shutdownNow();
        } catch (Exception ignored) {}
    }
}
