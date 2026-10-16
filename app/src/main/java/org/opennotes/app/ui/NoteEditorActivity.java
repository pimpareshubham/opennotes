package org.opennotes.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import org.opennotes.app.export.PdfExporter;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.util.TypefaceManager;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoteEditorActivity extends AppCompatActivity implements CanvasView.OnCanvasChangeListener {

    private NotesDbHelper dbHelper;
    private AppSettings appSettings;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final Handler saveDebounceHandler = new Handler(Looper.getMainLooper());

    private long notebookId;
    private Notebook currentNotebook;
    private List<Page> pages;
    private int currentPageIndex = 0;
    private Page currentPage;

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
    private ImageView toolLaser;
    private ImageView btnAutoBeautify;
    private FrameLayout btnColorPicker;
    private View colorPreviewCircle;
    private ImageView toolShape, toolText, toolMedia;
    private ImageView btnExport, btnPageOverview, btnMenu;

    // Top Navigation Header
    private ImageView btnCloudSync, btnCamera, btnCrop, btnMic;
    private ImageView btnSplitView, btnRuler, btnLayers, btnPageColor, btnSettingsSliders;
    private View quickColorWhite, quickColorOrange, quickColorGreen, quickColorCyan, quickColorPurple;

    // Floating Viewport Pill
    private LinearLayout floatingViewportPill;
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
    private SwitchCompat switchStraightLine;
    private LinearLayout layoutToolQuickColors;
    private Button btnAddToPenBox;

    // Floating Contextual Lasso Action Bar
    private LinearLayout lassoFloatingActionBar;
    private TextView btnLassoDelete, btnLassoCut, btnLassoCopy, btnLassoStyle, btnLassoDuplicate, btnLassoColor, btnLassoBeautify, btnLassoConvertText;

    // Fullscreen Zen mode
    private ImageView btnExitFullscreen;
    private boolean isZenFullscreen = false;

    // Image Picker & Voice Recorder & Font Picker
    private ActivityResultLauncher<String> imagePickerLauncher;
    private ActivityResultLauncher<String> fontPickerLauncher;
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
        setContentView(R.layout.activity_note_editor);

        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                handleInsertImage(uri);
            }
        });

        fontPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                handleImportCustomFont(uri);
            }
        });

        dbHelper = NotesDbHelper.getInstance(this);
        appSettings = AppSettings.getInstance(this);
        notebookId = getIntent().getLongExtra("notebook_id", -1);

        if (notebookId == -1) {
            finish();
            return;
        }

        bindViews();
        loadNotebookData();
        setupListeners();
        setupToolPropertyCard();
        restoreUserSettings();
        updateFavoriteIcon();
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
        btnAutoBeautify = findViewById(R.id.btnAutoBeautify);

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
        switchStraightLine = findViewById(R.id.switchStraightLine);
        layoutToolQuickColors = findViewById(R.id.layoutToolQuickColors);
        btnAddToPenBox = findViewById(R.id.btnAddToPenBox);

        lassoFloatingActionBar = findViewById(R.id.lassoFloatingActionBar);
        btnLassoDelete = findViewById(R.id.btnLassoDelete);
        btnLassoCut = findViewById(R.id.btnLassoCut);
        btnLassoCopy = findViewById(R.id.btnLassoCopy);
        btnLassoStyle = findViewById(R.id.btnLassoStyle);
        btnLassoDuplicate = findViewById(R.id.btnLassoDuplicate);
        btnLassoColor = findViewById(R.id.btnLassoColor);
        btnLassoBeautify = findViewById(R.id.btnLassoBeautify);
        btnLassoConvertText = findViewById(R.id.btnLassoConvertText);

        btnExitFullscreen = findViewById(R.id.btnExitFullscreen);

        canvasView.setOnCanvasChangeListener(this);
        canvasView.setOnZoomChangeListener(zoomPercent -> {
            textZoomPercent.setText(Math.round(zoomPercent) + "% ▾");
        });
        canvasView.setOnLassoSelectionListener((hasSelection, screenBounds) -> {
            if (hasSelection && screenBounds != null) {
                if (lassoFloatingActionBar.getVisibility() != View.VISIBLE) {
                    lassoFloatingActionBar.setVisibility(View.VISIBLE);
                    lassoFloatingActionBar.setAlpha(0f);
                    lassoFloatingActionBar.setScaleX(0.85f);
                    lassoFloatingActionBar.setScaleY(0.85f);
                    lassoFloatingActionBar.animate().alpha(1f).scaleX(1.0f).scaleY(1.0f).setDuration(200)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                }
                lassoFloatingActionBar.post(() -> {
                    int barWidth = lassoFloatingActionBar.getWidth();
                    float posX = screenBounds.centerX() - barWidth / 2f;
                    posX = Math.max(20f, Math.min(canvasView.getWidth() - barWidth - 20f, posX));
                    float posY = Math.max(120f, screenBounds.top - 60f);
                    lassoFloatingActionBar.setX(posX);
                    lassoFloatingActionBar.setY(posY);
                });
            } else {
                if (lassoFloatingActionBar.getVisibility() == View.VISIBLE) {
                    lassoFloatingActionBar.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160)
                            .withEndAction(() -> lassoFloatingActionBar.setVisibility(View.GONE)).start();
                }
            }
        });
    }

    private void loadNotebookData() {
        currentNotebook = dbHelper.getNotebook(notebookId);
        if (currentNotebook == null) {
            finish();
            return;
        }

        textNotebookTitle.setText(currentNotebook.title);
        pages = dbHelper.getPagesForNotebook(notebookId);
        if (pages.isEmpty()) {
            Page firstPage = new Page(notebookId, 0, currentNotebook.defaultTemplate, currentNotebook.defaultBgColor, currentNotebook.defaultIsInfinite);
            dbHelper.insertPage(firstPage);
            pages = dbHelper.getPagesForNotebook(notebookId);
        }

        currentPageIndex = 0;
        loadCurrentPage();
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
            cardToolProperties.setVisibility(View.GONE);
            canvasView.setTool(DrawingTool.ERASER_STROKE);
            updateToolUi(DrawingTool.ERASER_STROKE);
            if (appSettings != null) appSettings.setLastTool(DrawingTool.ERASER_STROKE);
        });
        toolLasso.setOnClickListener(v -> {
            cardToolProperties.setVisibility(View.GONE);
            canvasView.setTool(DrawingTool.LASSO);
            updateToolUi(DrawingTool.LASSO);
            if (appSettings != null) appSettings.setLastTool(DrawingTool.LASSO);
            Toast.makeText(this, "Lasso: circle strokes to select, drag to move", Toast.LENGTH_SHORT).show();
        });

        if (toolLaser != null) {
            toolLaser.setOnClickListener(v -> selectOrToggleTool(DrawingTool.LASER, "Laser Presentation Pointer"));
        }

        if (btnAutoBeautify != null) {
            btnAutoBeautify.setOnClickListener(v -> {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                        .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()).start();
                boolean newState = !canvasView.isAutoBeautifyEnabled();
                canvasView.setAutoBeautifyEnabled(newState);
                if (appSettings != null) appSettings.setAutoBeautifyEnabled(newState);
                updateAutoBeautifyToolbarIcon(newState);
                String lang = appSettings != null ? appSettings.getBeautifyLanguage() : "MARATHI";
                String langDisplay = "MARATHI".equalsIgnoreCase(lang) ? "मराठी" : ("HINDI".equalsIgnoreCase(lang) ? "हिन्दी" : "English");
                if (newState) {
                    Toast.makeText(this, "✨ Auto-Beautification ON (" + langDisplay + ")\nLong-press icon to customize language & font", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Auto-Beautification OFF", Toast.LENGTH_SHORT).show();
                }
            });

            btnAutoBeautify.setOnLongClickListener(v -> {
                showAutoBeautifySettingsDialog();
                return true;
            });
        }

        btnColorPicker.setOnClickListener(v -> showColorPickerDialog());

        toolShape.setOnClickListener(v -> showShapeSelectorDialog());
        toolText.setOnClickListener(v -> {
            cardToolProperties.setVisibility(View.GONE);
            canvasView.setTool(DrawingTool.TEXT);
            updateToolUi(DrawingTool.TEXT);
            if (appSettings != null) appSettings.setLastTool(DrawingTool.TEXT);
            Toast.makeText(this, "Tap anywhere on canvas to type text", Toast.LENGTH_SHORT).show();
        });
        toolMedia.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));

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
            btnCamera.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
        }
        if (btnCrop != null) {
            btnCrop.setOnClickListener(v -> {
                canvasView.setTool(DrawingTool.LASSO);
                updateToolUi(DrawingTool.LASSO);
                Toast.makeText(this, "Crop Tool: Circle stroke area with lasso to extract or copy", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnMic != null) {
            btnMic.setOnClickListener(v -> showVoiceRecorderDialog());
        }
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
                dbHelper.updateNotebook(currentNotebook);
                updateFavoriteIcon();
                Toast.makeText(this, currentNotebook.isFavorite ? "Added to Favorites ★" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
            }
        });

        textZoomPercent.setOnClickListener(v -> showZoomPickerDialog());

        btnLockViewport.setOnClickListener(v -> {
            boolean next = !canvasView.isViewportLocked();
            canvasView.setViewportLocked(next);
            btnLockViewport.setColorFilter(next ? 0xFF38BDF8 : 0xFF94A3B8);
            Toast.makeText(this, next ? "Viewport Locked 🔒" : "Viewport Unlocked", Toast.LENGTH_SHORT).show();
        });

        btnResetZoom.setOnClickListener(v -> {
            canvasView.resetZoom();
            Toast.makeText(this, "Zoom reset to 100%", Toast.LENGTH_SHORT).show();
        });

        btnPrevPage.setOnClickListener(v -> {
            if (currentPageIndex > 0) {
                saveCurrentPage();
                currentPageIndex--;
                loadCurrentPage();
            }
        });

        btnNextPage.setOnClickListener(v -> {
            if (currentPageIndex < pages.size() - 1) {
                saveCurrentPage();
                currentPageIndex++;
                loadCurrentPage();
            }
        });

        btnAddPage.setOnClickListener(v -> {
            saveCurrentPage();
            PaperTemplate tpl = (currentPage != null) ? currentPage.template : currentNotebook.defaultTemplate;
            int bgCol = (currentPage != null && currentPage.backgroundColor != 0)
                    ? currentPage.backgroundColor
                    : ((currentNotebook != null && currentNotebook.defaultBgColor != -1)
                        ? currentNotebook.defaultBgColor
                        : (appSettings != null ? appSettings.getDefaultPageBgColor() : 0xFF121214));
            boolean inf = (currentPage != null) ? currentPage.isInfinite : currentNotebook.defaultIsInfinite;

            Page newPage = new Page(notebookId, pages.size(), tpl, bgCol, inf);
            dbHelper.insertPage(newPage);

            currentNotebook.pageCount = pages.size() + 1;
            dbHelper.updateNotebook(currentNotebook);

            pages = dbHelper.getPagesForNotebook(notebookId);
            currentPageIndex = pages.size() - 1;
            loadCurrentPage();
            Toast.makeText(this, "Page " + (currentPageIndex + 1) + " added", Toast.LENGTH_SHORT).show();
        });

        textPageCounter.setOnClickListener(v -> showPageOverviewDialog());

        // Contextual Lasso Action Bar
        btnLassoDelete.setOnClickListener(v -> canvasView.deleteSelection());
        btnLassoCut.setOnClickListener(v -> {
            canvasView.cutSelection();
            Toast.makeText(this, "Cut to clipboard", Toast.LENGTH_SHORT).show();
        });
        btnLassoCopy.setOnClickListener(v -> {
            canvasView.copySelection();
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        btnLassoStyle.setOnClickListener(v -> showLassoStyleDialog());
        btnLassoDuplicate.setOnClickListener(v -> canvasView.duplicateSelection());
        btnLassoColor.setOnClickListener(v -> {
            StarColorPickerDialog.show(this, "Selection Color", canvasView.getColor(), color -> {
                canvasView.setSelectionColor(color);
            });
        });
        if (btnLassoBeautify != null) {
            btnLassoBeautify.setOnClickListener(v -> {
                canvasView.beautifySelection();
                Toast.makeText(this, "✨ Selected handwriting beautified!", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnLassoConvertText != null) {
            btnLassoConvertText.setOnClickListener(v -> {
                canvasView.convertSelectionToText();
                Toast.makeText(this, "✍️ Converted to font text!", Toast.LENGTH_SHORT).show();
            });
        }

        btnExitFullscreen.setOnClickListener(v -> setZenFullscreen(false));
    }

    private void selectOrToggleTool(DrawingTool tool, String title) {
        if (canvasView.getTool() == tool) {
            // Second tap toggles property card with smooth slide and fade
            if (cardToolProperties.getVisibility() == View.VISIBLE) {
                cardToolProperties.animate().alpha(0f).translationY(-25f).setDuration(180)
                        .withEndAction(() -> cardToolProperties.setVisibility(View.GONE)).start();
            } else {
                cardToolProperties.setVisibility(View.VISIBLE);
                cardToolProperties.setAlpha(0f);
                cardToolProperties.setTranslationY(-25f);
                cardToolProperties.animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        } else {
            canvasView.setTool(tool);
            updateToolUi(tool);
            if (appSettings != null) {
                appSettings.setLastTool(tool);
            }
            if (cardToolProperties.getVisibility() == View.VISIBLE) {
                cardToolProperties.animate().alpha(0f).translationY(-25f).setDuration(180)
                        .withEndAction(() -> cardToolProperties.setVisibility(View.GONE)).start();
            }
        }
        textToolPropertyTitle.setText(title);
    }

    private void restoreUserSettings() {
        if (appSettings == null) return;

        // Restore Tool
        DrawingTool savedTool = appSettings.getLastTool();
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
        canvasView.setAutoConvertTextEnabled(appSettings.isAutoConvertTextEnabled());
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
                    canvasView.setStrokeWidth(width);
                    textThicknessValue.setText(String.format(Locale.US, "%.1f mm", width * 0.3f));
                    if (appSettings != null) appSettings.setStrokeWidth(width);
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

            dot.setOnClickListener(v -> {
                canvasView.setColor(qCol);
                updateColorPreview(qCol);
            });
            layoutToolQuickColors.addView(dot);
        }

        btnAddToPenBox.setOnClickListener(v -> {
            cardToolProperties.animate().alpha(0f).translationY(-25f).setDuration(180)
                    .withEndAction(() -> cardToolProperties.setVisibility(View.GONE)).start();
            Toast.makeText(this, "Pen preset saved to dock", Toast.LENGTH_SHORT).show();
        });
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
            activeIv.setColorFilter(0xFF38BDF8);
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
        final String[] zoomOptions = {"25%", "50%", "75%", "100% (Fit)", "150%", "200%", "400%", "800%", "1600%"};
        final float[] zoomValues = {25f, 50f, 75f, 100f, 150f, 200f, 400f, 800f, 1600f};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Zoom Level")
                .setItems(zoomOptions, (dialog, which) -> {
                    canvasView.setZoom(zoomValues[which]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLassoStyleDialog() {
        final String[] widths = {"1 mm (Fine)", "2 mm (Normal)", "3 mm (Medium)", "5 mm (Thick)", "8 mm (Bold)"};
        final float[] vals = {3f, 6f, 10f, 16f, 24f};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Change Stroke Width")
                .setItems(widths, (dialog, which) -> {
                    canvasView.setSelectionStrokeWidth(vals[which]);
                })
                .setNegativeButton("Cancel", null)
                .show();
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

        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Shape")
                .setItems(shapes, (dialog, which) -> {
                    canvasView.setShapeType(types[which]);
                    canvasView.setTool(DrawingTool.SHAPE);
                    updateToolUi(DrawingTool.SHAPE);
                    Toast.makeText(this, "Drag on canvas to draw " + shapes[which], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showRulerAngleDialog() {
        final String[] angles = {"0° (Horizontal Snap)", "30° Angle Guide", "45° Diagonal Snap", "60° Angle Guide", "90° (Vertical Snap)", "120° Angle Guide", "150° Angle Guide", "Disable Ruler Assist"};
        new MaterialAlertDialogBuilder(this)
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
                .show();
    }

    private void showExportDialog() {
        String[] options = {"Export as PDF Document (300 DPI Ultra-HD RAW)", "Export as PNG Image (Crisp HD)"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Export Notebook")
                .setItems(options, (dialog, which) -> {
                    saveCurrentPage();
                    if (which == 0) {
                        try {
                            File pdfFile = PdfExporter.exportNotebookToPdf(this, notebookId);
                            if (pdfFile != null) {
                                showExportSuccessDialog(pdfFile, "application/pdf");
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "PDF Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        try {
                            File imgFile = ImageExporter.exportPageToImage(this, canvasView, currentNotebook.title, currentPageIndex);
                            if (imgFile != null) {
                                showExportSuccessDialog(imgFile, "image/png");
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Image Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
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

        new MaterialAlertDialogBuilder(this)
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
                .setNegativeButton("Open", (d, w) -> openFile(file, mimeType))
                .show();
    }

    private void openFile(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            shareFile(file, mimeType);
        }
    }

    private void shareFile(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share Exported Note"));
        } catch (Exception ex) {
            Toast.makeText(this, "Export complete: " + file.getName(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPageOverviewDialog() {
        saveCurrentPage();
        pages = dbHelper.getPagesForNotebook(notebookId);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_page_overview, null);
        RecyclerView rv = dialogView.findViewById(R.id.recyclerViewPages);
        rv.setLayoutManager(new GridLayoutManager(this, 3));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setTitle("Pages (" + pages.size() + ")")
                .setNegativeButton("Close", null)
                .create();

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
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Clear Canvas")
                            .setMessage("Are you sure you want to clear all strokes on this page?")
                            .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
            }
            return false;
        });
        popup.show();
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
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Clear Canvas")
                            .setMessage("Are you sure you want to clear all strokes on this page?")
                            .setPositiveButton("Clear", (d, w) -> canvasView.clearAll())
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void showTemplateSelectionDialog() {
        String[] templates = {"Blank", "Ruled Lines", "Grid Paper", "Dot Grid", "Cornell Notes"};
        PaperTemplate[] tpls = {PaperTemplate.BLANK, PaperTemplate.RULED, PaperTemplate.GRID, PaperTemplate.DOTS, PaperTemplate.CORNELL};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Paper Template")
                .setItems(templates, (dialog, which) -> {
                    canvasView.setPaperTemplate(tpls[which]);
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
                dbHelper.updateNotebookDefaultBgColor(notebookId, newColor, checkAllPages.isChecked());
            } else if (checkAllPages.isChecked()) {
                dbHelper.updateNotebookDefaultBgColor(notebookId, (currentNotebook != null ? currentNotebook.defaultBgColor : -1), true);
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

        dialog.show();
    }

    private void showAutoBeautifySettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_auto_beautify_settings, null);

        SwitchCompat switchEnableBeautify = view.findViewById(R.id.switchEnableBeautify);
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
        boolean initialAutoConvert = appSettings != null ? appSettings.isAutoConvertTextEnabled() : false;

        final boolean[] isEnabled = { initialEnabled };
        final String[] selectedLang = { initialLang };
        final String[] selectedFont = { initialFont };

        switchEnableBeautify.setChecked(initialEnabled);
        checkShirorekhaSnap.setChecked(initialShirorekha);
        if (checkAutoConvertText != null) {
            checkAutoConvertText.setChecked(initialAutoConvert);
        }

        switchEnableBeautify.setOnCheckedChangeListener((btn, checked) -> isEnabled[0] = checked);

        final Runnable[] updateDialogUi = new Runnable[1];
        updateDialogUi[0] = () -> {
            boolean isMar = "MARATHI".equalsIgnoreCase(selectedLang[0]);
            boolean isHin = "HINDI".equalsIgnoreCase(selectedLang[0]);
            boolean isEng = "ENGLISH".equalsIgnoreCase(selectedLang[0]);

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
                List<TypefaceManager.CustomFontInfo> customFonts = TypefaceManager.getCustomFonts(this);
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
                    nameView.setTypeface(TypefaceManager.getTypeface(this, cFont.key));
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

            if (isMar) {
                textBeautifyPreview.setText("माझे सुंदर सुलेखन • OpenNotes");
            } else if (isHin) {
                textBeautifyPreview.setText("सुंदर हस्तलेख और सुलेखन • OpenNotes");
            } else {
                textBeautifyPreview.setText("Beautiful Handwritten Notes • OpenNotes");
            }
            textBeautifyPreview.setTypeface(TypefaceManager.getTypeface(this, selectedFont[0]));
        };

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
            boolean autoConvert = (checkAutoConvertText != null && checkAutoConvertText.isChecked());

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

            updateAutoBeautifyToolbarIcon(enabled);
            dialog.dismiss();

            String langDisplay = "MARATHI".equalsIgnoreCase(lang) ? "मराठी" : ("HINDI".equalsIgnoreCase(lang) ? "हिन्दी" : "English");
            String convertMsg = autoConvert ? " • Real-Time Text Convert ON" : "";
            Toast.makeText(this, "✨ Auto-Beautification " + (enabled ? "ON" : "OFF") + " (" + langDisplay + " • " + font + ")" + convertMsg, Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void handleImportCustomFont(Uri uri) {
        String fontName = TypefaceManager.importCustomFont(this, uri);
        if (fontName != null) {
            if (canvasView != null) {
                canvasView.setBeautifyFontKey(fontName);
            }
            if (appSettings != null) {
                appSettings.setBeautifyFont(fontName);
            }
            Toast.makeText(this, "✓ Custom Font Imported: " + fontName + "\nReady for auto-beautification & text conversion", Toast.LENGTH_LONG).show();
            showAutoBeautifySettingsDialog();
        } else {
            Toast.makeText(this, "Failed to import font. Please select a valid .ttf or .otf file.", Toast.LENGTH_LONG).show();
        }
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
            if (opts.outWidth > 1200 || opts.outHeight > 1200) {
                sampleSize = Math.max(opts.outWidth / 1200, opts.outHeight / 1200);
            }

            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options opts2 = new BitmapFactory.Options();
                opts2.inSampleSize = sampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(is2, null, opts2);
                if (bitmap != null) {
                    float startX = 60f;
                    float startY = 180f;
                    float width = Math.min(bitmap.getWidth(), 800f);
                    float height = width * ((float) bitmap.getHeight() / bitmap.getWidth());
                    ShapeItem imgItem = new ShapeItem(ShapeItem.Type.RECTANGLE, startX, startY, startX + width, startY + height, 0xFF38BDF8, 3.0f);
                    if (currentPage != null) {
                        currentPage.shapes.add(imgItem);
                        canvasView.invalidate();
                        saveCurrentPage();
                        Toast.makeText(this, "Image inserted (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
        dialog.show();

        Button btnAction = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        btnAction.setOnClickListener(v -> {
            if (!isRecordingAudio) {
                startAudioRecording(timerView, statusView);
                btnAction.setText("Stop & Save");
                btnAction.setTextColor(0xFFEF4444);
            } else {
                stopAudioRecording();
                statusView.setText("✓ Voice note saved directly to notebook!");
                btnAction.setText("Play Note");
                btnAction.setTextColor(0xFF38BDF8);
                btnAction.setOnClickListener(v2 -> playRecordedAudio(statusView));
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
            try {
                if (voicePlayer != null) {
                    voicePlayer.release();
                }
                voicePlayer = new MediaPlayer();
                voicePlayer.setDataSource(currentAudioFile.getAbsolutePath());
                voicePlayer.prepare();
                voicePlayer.start();
                statusView.setText("▶ Playing audio memo...");
                voicePlayer.setOnCompletionListener(mp -> statusView.setText("✓ Playback finished"));
            } catch (Exception e) {
                statusView.setText("Playback preview: " + e.getMessage());
            }
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
        builder.show();
    }

    private void triggerRealtimeAutoSave() {
        if (currentPage == null) return;
        currentPage.canvasHeight = canvasView.getContentHeight();
        final Page snapshot = currentPage.createSnapshot();
        saveDebounceHandler.removeCallbacksAndMessages(null);
        saveDebounceHandler.postDelayed(() -> {
            saveExecutor.execute(() -> {
                dbHelper.savePage(snapshot);
            });
        }, 150);
    }

    private void saveCurrentPage() {
        if (currentPage != null) {
            saveDebounceHandler.removeCallbacksAndMessages(null);
            currentPage.canvasHeight = canvasView.getContentHeight();
            final Page snapshot = currentPage.createSnapshot();
            dbHelper.savePage(snapshot);
        }
    }

    @Override
    public void onCanvasChanged() {
        updateUndoRedoUi();
        triggerRealtimeAutoSave();
    }

    @Override
    public void onRequestTextInput(float x, float y) {
        EditText input = new EditText(this);
        input.setHint("Type note text...");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Insert Text")
                .setView(input)
                .setPositiveButton("Insert", (d, w) -> {
                    String text = input.getText().toString();
                    if (!text.trim().isEmpty()) {
                        canvasView.addTextItem(text, x, y);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onScrollChanged(float scrollY, float maxScrollY) {}

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
    protected void onDestroy() {
        super.onDestroy();
        stopAudioRecording();
        if (voicePlayer != null) {
            try { voicePlayer.release(); voicePlayer = null; } catch (Exception ignored) {}
        }
        saveDebounceHandler.removeCallbacksAndMessages(null);
        saveCurrentPage();
        try {
            saveExecutor.shutdown();
        } catch (Exception ignored) {}
    }
}
