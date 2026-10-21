package org.opennotes.app.ui;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import org.opennotes.app.R;
import org.opennotes.app.db.AppSettings;
import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.export.PdfExporter;
import org.opennotes.app.importer.PdfImporter;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.util.ErrorDialog;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.util.ThemeUtils;
import org.opennotes.app.export.BackupManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NotebookAdapter.OnNotebookClickListener {

    private NotesDbHelper dbHelper;
    private RecyclerView recyclerView;
    private NotebookAdapter adapter;
    private View emptyStateView;
    private EditText searchEditText;
    private ImageView btnClearSearch;
    private ImageView btnToggleViewMode;
    private ChipGroup filterChipGroup;

    private View layoutNormalTopBar;
    private View layoutSelectionBar;
    private ImageView btnCloseSelection;
    private TextView textSelectionCount;
    private com.google.android.material.button.MaterialButton btnSelectAll;
    private ImageView btnBatchShare;
    private ImageView btnBatchDelete;
    private ExtendedFloatingActionButton fabNew;

    private boolean isGridView = true;
    private String currentFolder = "All";
    private boolean isFavoritesOnly = false;
    private String currentSearchQuery = "";

    private final int[] PRESET_COVER_COLORS = {
            0xFF1E1E24, // Dark Obsidian (Default StarNote style)
            0xFF1E3A8A, // Deep Navy
            0xFF065F46, // Emerald
            0xFF334155, // Slate
            0xFF581C87, // Plum
            0xFF9F1239, // Crimson
            0xFF92400E, // Amber
            0xFF18181B  // Pitch Charcoal
    };

    private final ExecutorService notebookIoExecutor = Executors.newSingleThreadExecutor();
    private final Handler searchDebounceHandler = new Handler(Looper.getMainLooper());
    private int notebookLoadGeneration = 0;

    private final ActivityResultLauncher<String[]> openPdfLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) {}
                    importPdfWithProgress(uri, null);
                }
            });

    private final ActivityResultLauncher<String> createBackupLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE), uri -> {
                if (uri != null) {
                    handleCreateBackup(uri);
                }
            });

    private final ActivityResultLauncher<String[]> restoreBackupLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    confirmRestoreBackup(uri);
                }
            });

    private AlertDialog backupProgressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullscreenMode.apply(this);
        enableHighRefreshRate();
        setContentView(R.layout.activity_main);

        dbHelper = NotesDbHelper.getInstance(this);
        isGridView = AppSettings.getInstance(this).isGridView();

        recyclerView = findViewById(R.id.recyclerViewNotebooks);
        emptyStateView = findViewById(R.id.emptyStateView);
        searchEditText = findViewById(R.id.searchEditText);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        btnToggleViewMode = findViewById(R.id.btnToggleViewMode);
        filterChipGroup = findViewById(R.id.filterChipGroup);

        View homeSearchBar = findViewById(R.id.homeSearchBar);
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        if (screenWidthDp >= 600) {
            LinearLayout.LayoutParams searchParams = (LinearLayout.LayoutParams) homeSearchBar.getLayoutParams();
            float density = getResources().getDisplayMetrics().density;
            int windowWidthPx = Math.round(screenWidthDp * density);
            int maxSearchWidthPx = Math.round(900f * density);
            int horizontalMarginsPx = Math.round(40f * density);
            searchParams.width = Math.min(windowWidthPx - horizontalMarginsPx, maxSearchWidthPx);
            searchParams.gravity = Gravity.CENTER_HORIZONTAL;
            homeSearchBar.setLayoutParams(searchParams);
        }

        btnToggleViewMode.setImageResource(isGridView ? R.drawable.ic_view_grid : R.drawable.ic_view_list);

        layoutNormalTopBar = findViewById(R.id.layoutNormalTopBar);
        layoutSelectionBar = findViewById(R.id.layoutSelectionBar);
        btnCloseSelection = findViewById(R.id.btnCloseSelection);
        textSelectionCount = findViewById(R.id.textSelectionCount);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnBatchShare = findViewById(R.id.btnBatchShare);
        btnBatchDelete = findViewById(R.id.btnBatchDelete);
        fabNew = findViewById(R.id.fabNewNotebook);
        View btnEmptyCreate = findViewById(R.id.btnEmptyCreate);

        setupRecyclerView();
        setupFiltersAndSearch();
        setupSelectionBar();

        fabNew.setOnClickListener(v -> showCreateNotebookDialog());
        btnEmptyCreate.setOnClickListener(v -> showCreateNotebookDialog());

        btnToggleViewMode.setOnClickListener(v -> {
            isGridView = !isGridView;
            AppSettings.getInstance(this).setGridView(isGridView);
            setupLayoutManager();
            btnToggleViewMode.setImageResource(isGridView ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        });

        View btnImportPdf = findViewById(R.id.btnImportPdf);
        if (btnImportPdf != null) {
            btnImportPdf.setOnClickListener(v -> openPdfPicker());
        }

        ImageView btnMenuMore = findViewById(R.id.btnMenuMore);
        if (btnMenuMore != null) {
            btnMenuMore.setOnClickListener(v -> showSettingsMenu());
        }

        recyclerView.post(this::offerHandwritingModelSetup);
        handlePdfIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePdfIntent(intent);
    }

    private void offerHandwritingModelSetup() {
        AppSettings settings = AppSettings.getInstance(this);
        settings.setAcceptedHandwritingModelDownload("en-US", true);
        settings.setAcceptedHandwritingModelDownload("en-IN", true);
        settings.setAcceptedHandwritingModelDownload("en", true);
        settings.setAcceptedHandwritingModelDownload("mr-IN", true);
        settings.setAcceptedHandwritingModelDownload("mr", true);
        settings.setAcceptedHandwritingModelDownload("hi", true);
        settings.setAcceptedHandwritingModelDownload("hi-IN", true);

        org.opennotes.app.util.HandwritingRecognitionEngine.checkModelsDownloaded(
                getApplicationContext(), (englishReady, marathiReady) -> {
                    if (englishReady && marathiReady) return;
                    // Auto-download in background on first start without bothering the user for permission
                    org.opennotes.app.util.HandwritingRecognitionEngine.downloadLanguageModel(
                            getApplicationContext(), "ENGLISH", englishErr -> {
                                org.opennotes.app.util.HandwritingRecognitionEngine.downloadLanguageModel(
                                        getApplicationContext(), "MARATHI", marathiErr -> {
                                            if (englishErr == null || marathiErr == null) {
                                                if (!isFinishing() && !isDestroyed()) {
                                                    Toast.makeText(MainActivity.this,
                                                            "Offline handwriting recognition engine ready.",
                                                            Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        });
                            });
                });
    }

    private void startHandwritingModelDownloads() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20f * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        TextView status = new TextView(this);
        status.setText("Preparing English model…");
        status.setPadding(0, pad, 0, 0);
        content.addView(spinner);
        content.addView(status);
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Downloading handwriting models")
                        .setView(content)
                        .setNegativeButton("Run in background", null);
        androidx.appcompat.app.AlertDialog progressDialog = builder.create();
        org.opennotes.app.util.ThemeUtils.styleDialog(progressDialog, this);
        progressDialog.show();

        Exception[] failures = new Exception[2];
        org.opennotes.app.util.HandwritingRecognitionEngine.downloadLanguageModel(
                getApplicationContext(), "ENGLISH", englishError -> {
                    failures[0] = englishError;
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(englishError == null
                                ? "English ready. Preparing Marathi model…"
                                : "English model failed. Trying Marathi…");
                    }
                    org.opennotes.app.util.HandwritingRecognitionEngine.downloadLanguageModel(
                            getApplicationContext(), "MARATHI", marathiError -> {
                                failures[1] = marathiError;
                                if (isFinishing() || isDestroyed()) return;
                                progressDialog.dismiss();
                                if (failures[0] == null && failures[1] == null) {
                                    Toast.makeText(this, "English and Marathi recognition are ready offline", Toast.LENGTH_LONG).show();
                                } else {
                                    StringBuilder details = new StringBuilder();
                                    if (failures[0] != null) details.append("English: ").append(failures[0]);
                                    if (failures[1] != null) {
                                        if (details.length() > 0) details.append("\n\n");
                                        details.append("Marathi: ").append(failures[1]);
                                    }
                                    ErrorDialog.show(this, "Handwriting model setup incomplete", details.toString());
                                }
                            });
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenMode.apply(this);
        loadNotebooks();
    }

    private void setupRecyclerView() {
        List<Notebook> cached = dbHelper.getCachedNotebooks();
        adapter = new NotebookAdapter(this, cached != null ? cached : new ArrayList<>(), this);
        setupLayoutManager();
        recyclerView.setAdapter(adapter);
    }

    private void setupLayoutManager() {
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int verticalPadding = Math.round(12f * getResources().getDisplayMetrics().density);
        int bottomPadding = Math.round(92f * getResources().getDisplayMetrics().density);
        int horizontalPaddingDp = isGridView
                ? 12 : Math.max(12, (screenWidthDp - 900) / 2 + 12);
        int horizontalPadding = Math.round(horizontalPaddingDp * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, bottomPadding);
        if (isGridView) {
            int availableWidthDp = Math.max(1,
                    screenWidthDp - 24);
            int spanCount = Math.max(1, Math.min(6, availableWidthDp / 256));
            recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
        }
    }

    private void setupFiltersAndSearch() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString();
                btnClearSearch.setVisibility(currentSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                searchDebounceHandler.removeCallbacksAndMessages(null);
                searchDebounceHandler.postDelayed(MainActivity.this::loadNotebooks, 120);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnClearSearch.setOnClickListener(v -> searchEditText.setText(""));

        filterChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipAll) {
                currentFolder = "All";
                isFavoritesOnly = false;
            } else if (id == R.id.chipFavorites) {
                currentFolder = "All";
                isFavoritesOnly = true;
            } else if (id == R.id.chipWork) {
                currentFolder = "Work";
                isFavoritesOnly = false;
            } else if (id == R.id.chipIdeas) {
                currentFolder = "Ideas";
                isFavoritesOnly = false;
            } else if (id == R.id.chipPersonal) {
                currentFolder = "Personal";
                isFavoritesOnly = false;
            }
            loadNotebooks();
        });
    }

    private void loadNotebooks() {
        final int generation = ++notebookLoadGeneration;
        final String folder = currentFolder;
        final boolean favoritesOnly = isFavoritesOnly;
        final String searchQuery = currentSearchQuery;
        final boolean isSearching = searchQuery != null && !searchQuery.trim().isEmpty();
        notebookIoExecutor.execute(() -> {
            List<Notebook> result = dbHelper.getAllNotebooks(folder, favoritesOnly, searchQuery);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != notebookLoadGeneration) return;
                adapter.updateData(result, isSearching);
                boolean showEmpty = isSearching && result.isEmpty();
                emptyStateView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            });
        });
    }

    private void showCreateNotebookDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_notebook, null);

        TextView btnCancel = dialogView.findViewById(R.id.btnCancelCreate);
        TextView btnConfirm = dialogView.findViewById(R.id.btnConfirmCreate);
        FrameLayout previewCoverBox = dialogView.findViewById(R.id.previewCoverBox);
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

        final int[] selectedCoverColor = {PRESET_COVER_COLORS[0]};
        final int[] selectedPageBgColor = {AppSettings.getInstance(this).getDefaultPageBgColor()};
        PaperTemplate initialTpl = AppSettings.getInstance(this).getDefaultPaperTemplate();
        final PaperTemplate[] selectedTemplate = {initialTpl != null ? initialTpl : PaperTemplate.BLANK};
        final boolean[] isInfinite = {true};

        Runnable updatePreview = () -> {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(16f);
            gd.setColor(selectedCoverColor[0]);
            gd.setStroke(2, 0xFF38BDF8);
            previewCoverBox.setBackground(gd);
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
        List<TextView> templateViews = new ArrayList<>();

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

        // Page Background Presets (Super Dark Black AMOLED at #0, Obsidian, Slate, White, etc.)
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
        List<View> pageBgDots = new ArrayList<>();
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
                Toast.makeText(this, "Page Background: " + bgName, Toast.LENGTH_SHORT).show();
            });
            pageBgDots.add(dot);
            layoutPageBgButtons.addView(dot);
        }
        updatePageBgUi.run();

        // Color choices
        for (int col : PRESET_COVER_COLORS) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(60, 60);
            lp.setMargins(6, 0, 6, 0);
            dot.setLayoutParams(lp);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(col);
            gd.setStroke(2, 0xFF475569);
            dot.setBackground(gd);

            dot.setOnClickListener(v -> {
                selectedCoverColor[0] = col;
                updatePreview.run();
            });
            layoutColorButtons.addView(dot);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, this);

        View btnImportPdfInDialog = dialogView.findViewById(R.id.btnImportPdfInDialog);
        if (btnImportPdfInDialog != null) {
            btnImportPdfInDialog.setOnClickListener(v -> {
                dialog.dismiss();
                openPdfPicker();
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
            if (title.isEmpty()) title = "Unnamed note";

            Notebook newNb = new Notebook(title, selectedCoverColor[0], selectedTemplate[0]);
            newNb.defaultIsInfinite = isInfinite[0];
            newNb.defaultBgColor = selectedPageBgColor[0];
            AppSettings.getInstance(this).setDefaultPageBgColor(selectedPageBgColor[0]);
            AppSettings.getInstance(this).setDefaultPaperTemplate(selectedTemplate[0]);
            if (!"All".equalsIgnoreCase(currentFolder) && !isFavoritesOnly) {
                newNb.folder = currentFolder;
            }
            dialog.dismiss();
            notebookIoExecutor.execute(() -> {
                long id = dbHelper.insertNotebook(newNb);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loadNotebooks();
                    openNotebook(id);
                });
            });
        });

        dialog.show();
    }


    private void openNotebook(long notebookId) {
        Intent intent = new Intent(this, NoteEditorActivity.class);
        intent.putExtra("notebook_id", notebookId);
        if (adapter != null) {
            for (Notebook nb : adapter.getNotebooks()) {
                if (nb != null && nb.id == notebookId) {
                    if (nb.defaultTemplate != null) {
                        intent.putExtra("template", nb.defaultTemplate.name());
                    }
                    break;
                }
            }
        }
        startActivity(intent);
    }

    private void openPdfPicker() {
        try {
            openPdfLauncher.launch(new String[]{"application/pdf"});
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePdfIntent(Intent intent) {
        if (intent == null) return;
        Uri pdfUri = null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            pdfUri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            pdfUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (pdfUri != null) {
            importPdfWithProgress(pdfUri, null);
        }
    }

    private void importPdfWithProgress(@NonNull Uri pdfUri, @Nullable String customTitle) {
        View progressView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_import_progress, null);
        TextView textTitle = progressView.findViewById(R.id.textImportTitle);
        TextView textStatus = progressView.findViewById(R.id.textImportStatus);
        LinearProgressIndicator progressIndicator = progressView.findViewById(R.id.progressPdfImport);

        if (textTitle != null) {
            textTitle.setText("Importing PDF Document");
        }
        if (textStatus != null) {
            textStatus.setText("Importing PDF directly without conversion…");
        }
        if (progressIndicator != null) {
            progressIndicator.setIndeterminate(true);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();

        notebookIoExecutor.execute(() -> {
            try {
                long notebookId = PdfImporter.importPdf(this, pdfUri, customTitle, (current, total) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (progressIndicator != null) {
                            progressIndicator.setIndeterminate(false);
                            progressIndicator.setMax(total);
                            progressIndicator.setProgress(current);
                        }
                        if (textStatus != null) {
                            textStatus.setText("Importing PDF page " + current + " of " + total + "…");
                        }
                    });
                });

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (dialog.isShowing()) dialog.dismiss();
                    loadNotebooks();
                    openNotebook(notebookId);
                    Toast.makeText(this, "✨ PDF imported directly as notebook!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (dialog.isShowing()) dialog.dismiss();
                    if (e instanceof SecurityException) {
                        ErrorDialog.show(this, "Password Protected PDF",
                                new Exception("This PDF is password protected. Please remove the password before importing into OpenNotes."));
                    } else {
                        ErrorDialog.show(this, "Failed to import PDF", e);
                    }
                });
            }
        });
    }

    @Override
    public void onNotebookClick(Notebook notebook) {
        openNotebook(notebook.id);
    }

    @Override
    public void onNotebookFavoriteClick(Notebook notebook) {
        notebook.isFavorite = !notebook.isFavorite;
        final long id = notebook.id;
        final boolean fav = notebook.isFavorite;
        notebookIoExecutor.execute(() -> dbHelper.setFavorite(id, fav));
        loadNotebooks();
    }

    @Override
    public void onCreateNewNotebookClick() {
        showCreateNotebookDialog();
    }

    @Override
    public void onNotebookMoreClick(Notebook notebook, View anchorView) {
        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle(notebook.title)
                .setSubtitle(notebook.pageCount + (notebook.pageCount == 1 ? " page" : " pages") + " • Notebook Options")
                .setHeaderSymbol("📓")
                .addOption(1, "📖", "Open Notebook", "Read or continue writing in this notebook", false, () -> {
                    openNotebook(notebook.id);
                })
                .addOption(2, "✏️", getString(R.string.rename), "Change notebook title", false, () -> {
                    showRenameDialog(notebook);
                })
                .addOption(3, "📑", getString(R.string.duplicate), "Create a full copy with all pages", false, () -> {
                    duplicateNotebook(notebook);
                })
                .addOption(4, "📤", getString(R.string.export_pdf), "Export and share as PDF document", false, () -> {
                    exportNotebookPdf(notebook);
                })
                .addDestructiveOption(5, "🗑️", getString(R.string.delete), "Permanently remove notebook and its pages", () -> {
                    showDeleteConfirmDialog(notebook);
                })
                .show();
    }

    private void showRenameDialog(Notebook notebook) {
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        int padH = org.opennotes.app.util.ThemeUtils.dp(this, 24);
        int padV = org.opennotes.app.util.ThemeUtils.dp(this, 12);
        container.setPadding(padH, padV, padH, padV);

        EditText input = new EditText(this);
        input.setText(notebook.title);
        input.setSelectAllOnFocus(true);
        input.setTextColor(0xFFF8FAFC);
        input.setHintTextColor(0xFF64748B);
        input.setTextSize(16);
        input.setBackgroundResource(R.drawable.bg_search_bar);
        input.setPadding(org.opennotes.app.util.ThemeUtils.dp(this, 14), org.opennotes.app.util.ThemeUtils.dp(this, 12),
                org.opennotes.app.util.ThemeUtils.dp(this, 14), org.opennotes.app.util.ThemeUtils.dp(this, 12));
        container.addView(input);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename)
                .setView(container)
                .setPositiveButton("Save", (d, which) -> {
                    String newTitle = input.getText().toString().trim();
                    if (!newTitle.isEmpty()) {
                        notebook.title = newTitle;
                        notebookIoExecutor.execute(() -> dbHelper.updateNotebook(notebook));
                        loadNotebooks();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void duplicateNotebook(Notebook notebook) {
        notebookIoExecutor.execute(() -> {
            dbHelper.duplicateNotebook(notebook.id);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadNotebooks();
                Toast.makeText(this, "Notebook duplicated", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void exportNotebookPdf(Notebook notebook) {
        try {
            File pdf = PdfExporter.exportNotebookToPdf(this, notebook.id);
            if (pdf != null) {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdf);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/pdf");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, notebook.title);
                shareIntent.putExtra(Intent.EXTRA_TEXT, "Exported from OpenNotes: " + notebook.title);
                shareIntent.setClipData(android.content.ClipData.newRawUri("Notebook PDF", uri));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "Share Notebook PDF with…");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
            }
        } catch (Exception e) {
            ErrorDialog.show(this, "Notebook export failed", e);
        }
    }

    private void showDeleteConfirmDialog(Notebook notebook) {
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete)
                .setMessage("Delete \"" + notebook.title + "\"? All pages will be permanently deleted.")
                .setPositiveButton(R.string.delete, (d, which) -> {
                    notebookIoExecutor.execute(() -> dbHelper.deleteNotebook(notebook.id));
                    loadNotebooks();
                    Toast.makeText(this, "Notebook deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    @Override
    public void onNotebookLongClick(Notebook notebook) {
        if (adapter != null && !adapter.isSelectionMode()) {
            adapter.startSelection(notebook.id);
        }
    }

    @Override
    public void onSelectionModeChanged(boolean isSelectionMode, int selectedCount) {
        if (layoutNormalTopBar != null) {
            layoutNormalTopBar.setVisibility(View.VISIBLE);
        }
        if (layoutSelectionBar != null) {
            if (isSelectionMode) {
                if (layoutSelectionBar.getVisibility() != View.VISIBLE) {
                    layoutSelectionBar.setVisibility(View.VISIBLE);
                    layoutSelectionBar.setAlpha(0f);
                    layoutSelectionBar.setTranslationY(40f);
                    layoutSelectionBar.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(180)
                            .start();
                }
            } else {
                if (layoutSelectionBar.getVisibility() == View.VISIBLE) {
                    layoutSelectionBar.animate()
                            .alpha(0f)
                            .translationY(40f)
                            .setDuration(150)
                            .withEndAction(() -> layoutSelectionBar.setVisibility(View.GONE))
                            .start();
                }
            }
        }
        if (fabNew != null) {
            if (isSelectionMode) fabNew.hide(); else fabNew.show();
        }
        if (textSelectionCount != null) {
            textSelectionCount.setText(selectedCount + (selectedCount == 1 ? " note selected" : " notes selected"));
        }
        if (btnSelectAll != null && adapter != null) {
            btnSelectAll.setText(adapter.areAllSelected() ? "Deselect all" : "Select all");
        }
        boolean hasSelection = selectedCount > 0;
        if (btnBatchShare != null) {
            btnBatchShare.setEnabled(hasSelection);
            btnBatchShare.setAlpha(hasSelection ? 1.0f : 0.35f);
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setEnabled(hasSelection);
            btnBatchDelete.setAlpha(hasSelection ? 1.0f : 0.35f);
        }
    }

    private void setupSelectionBar() {
        if (btnCloseSelection != null) {
            btnCloseSelection.setOnClickListener(v -> {
                if (adapter != null) adapter.exitSelectionMode();
            });
        }
        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                if (adapter != null) {
                    if (adapter.areAllSelected()) {
                        adapter.deselectAll();
                    } else {
                        adapter.selectAll();
                    }
                }
            });
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setOnClickListener(v -> showBatchDeleteConfirmDialog());
        }
        if (btnBatchShare != null) {
            btnBatchShare.setOnClickListener(v -> exportSelectedNotebooks());
        }
    }

    private void showBatchDeleteConfirmDialog() {
        if (adapter == null || adapter.getSelectedCount() == 0) return;
        final int count = adapter.getSelectedCount();
        final Set<Long> selectedIds = adapter.getSelectedNotebookIds();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete)
                .setMessage("Delete " + count + (count == 1 ? " notebook?" : " notebooks?")
                        + " All pages will be permanently deleted.")
                .setPositiveButton(R.string.delete, (d, which) -> {
                    notebookIoExecutor.execute(() -> {
                        for (Long id : selectedIds) {
                            dbHelper.deleteNotebook(id);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (adapter != null) adapter.exitSelectionMode();
                            loadNotebooks();
                            Toast.makeText(this, count + (count == 1 ? " notebook deleted" : " notebooks deleted"), Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        org.opennotes.app.util.ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void exportSelectedNotebooks() {
        if (adapter == null || adapter.getSelectedCount() == 0) return;
        final int count = adapter.getSelectedCount();
        final Set<Long> selectedIds = adapter.getSelectedNotebookIds();

        Toast.makeText(this, "Exporting " + count + (count == 1 ? " notebook" : " notebooks") + " to PDF…", Toast.LENGTH_SHORT).show();

        notebookIoExecutor.execute(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            for (Long id : selectedIds) {
                try {
                    File pdf = PdfExporter.exportNotebookToPdf(MainActivity.this, id);
                    if (pdf != null) {
                        Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", pdf);
                        uris.add(uri);
                    }
                } catch (Exception e) {
                    android.util.Log.e("MainActivity", "Failed to export notebook " + id, e);
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (uris.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Failed to export notebooks", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent shareIntent;
                if (uris.size() == 1) {
                    shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/pdf");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    shareIntent.setClipData(android.content.ClipData.newRawUri("Notebook PDF", uris.get(0)));
                } else {
                    shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    shareIntent.setType("application/pdf");
                    shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    android.content.ClipData clipData = android.content.ClipData.newRawUri("Notebooks PDF", uris.get(0));
                    for (int i = 1; i < uris.size(); i++) {
                        clipData.addItem(new android.content.ClipData.Item(uris.get(i)));
                    }
                    shareIntent.setClipData(clipData);
                }
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Exported " + uris.size() + " notebooks from OpenNotes");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "Exported " + uris.size() + " notebooks from OpenNotes");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "Share " + uris.size() + " notebooks with…");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
                if (adapter != null) adapter.exitSelectionMode();
            });
        });
    }

    private void showSettingsMenu() {
        AppSettings settings = AppSettings.getInstance(this);
        boolean isVerticalScroll = settings.isContinuousVerticalScroll();
        boolean isHoldSearch = settings.isTwoFingerSearchGestureEnabled();

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Settings & Options")
                .setSubtitle("Configure gestures, navigation, and backups")
                .setHeaderSymbol("⚙️")
                .addOption(1, "📜", "Page Navigation Mode",
                        !isVerticalScroll ? "No Scroll / Change Pages Only (Original discrete pages)"
                                          : "Smooth Continuous Vertical Scroll (All pages in one flow)",
                        isVerticalScroll, this::showPageViewModeDialog)
                .addOption(2, "🔍", "Search & AI Assistant Gesture",
                        isHoldSearch
                                ? "Two-finger hold gesture: ENABLED (" + settings.getSearchAssistTargetName() + ")"
                                : "Two-finger hold gesture: DISABLED (Tap to configure or enable)",
                        isHoldSearch, this::showSearchAssistConfigDialog)
                .addOption(3, "💾", "Local Backup & Restore",
                        "Backup all notes, pages, drawings & media locally or restore",
                        false, this::showBackupRestoreDialog)
                .show();
    }

    private void showSearchAssistConfigDialog() {
        AppSettings settings = AppSettings.getInstance(this);
        String currentTarget = settings.getSearchAssistTarget();
        boolean isCircle = AppSettings.SEARCH_TARGET_CIRCLE_TO_SEARCH.equals(currentTarget);
        boolean isGemini = AppSettings.SEARCH_TARGET_GEMINI.equals(currentTarget);
        boolean isLens = AppSettings.SEARCH_TARGET_LENS.equals(currentTarget);
        boolean isTwoFingerEnabled = settings.isTwoFingerSearchGestureEnabled();

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Search & AI Assistant Gesture")
                .setSubtitle("Enable/disable two-finger hold shortcut and choose search engine")
                .setHeaderSymbol("🔍")
                .addOption(1, "✌️", "Enable Two-Finger Hold Gesture",
                        "Hold 2 fingers still on canvas for 0.6s to trigger search overlay",
                        isTwoFingerEnabled, () -> {
                            settings.setTwoFingerSearchGestureEnabled(true);
                            Toast.makeText(this, "✌️ Two-Finger Hold Gesture ENABLED", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(2, "🚫", "Disable Two-Finger Hold Gesture",
                        "Turn off two-finger hold shortcut completely (prevents accidental triggers)",
                        !isTwoFingerEnabled, () -> {
                            settings.setTwoFingerSearchGestureEnabled(false);
                            Toast.makeText(this, "🚫 Two-Finger Hold Gesture DISABLED", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(3, "🔍", "Google Circle to Search (Home Button Long Press)",
                        "Mimics 'Press and hold Home button' to trigger device's Circle to Search overlay",
                        isCircle, () -> {
                            settings.setSearchAssistTarget(AppSettings.SEARCH_TARGET_CIRCLE_TO_SEARCH);
                            Toast.makeText(this, "Set to Google Circle to Search (Home Long Press)", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(4, "✨", "Gemini AI Assistant",
                        "Open Gemini with 'Ask Gemini' and Live screen sharing",
                        isGemini, () -> {
                            settings.setSearchAssistTarget(AppSettings.SEARCH_TARGET_GEMINI);
                            Toast.makeText(this, "Set to Gemini Assistant", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .addOption(5, "📷", "Google Lens (Instant Image Search)",
                        "Directly search circled/screen content in Google Lens (No share sheet)",
                        isLens, () -> {
                            settings.setSearchAssistTarget(AppSettings.SEARCH_TARGET_LENS);
                            Toast.makeText(this, "Set to Google Lens", Toast.LENGTH_SHORT).show();
                            showSearchAssistConfigDialog();
                        })
                .show();
    }

    private void showPageViewModeDialog() {
        AppSettings settings = AppSettings.getInstance(this);
        boolean isVertical = settings.isContinuousVerticalScroll();

        new ModernOptionsSelectorDialog.Builder(this)
                .setTitle("Page Navigation Mode")
                .setSubtitle("Select how multi-page documents are navigated in the editor")
                .setHeaderSymbol("📜")
                .addOption(1, "↔️", "No Scroll / Change Pages Only (Original Way)",
                        "Original discrete single-page mode. Zero vertical scroll between pages. Change pages with swipe or previous/next buttons.",
                        !isVertical, () -> {
                            settings.setContinuousVerticalScroll(false);
                            Toast.makeText(this, "Page View: No Scroll (Change Pages Only)", Toast.LENGTH_SHORT).show();
                            showSettingsMenu();
                        })
                .addOption(2, "↕️", "Smooth Continuous Vertical Scroll",
                        "Scroll smoothly through all document pages vertically with fluid momentum fling.",
                        isVertical, () -> {
                            settings.setContinuousVerticalScroll(true);
                            Toast.makeText(this, "Page View: Smooth Continuous Vertical Scroll", Toast.LENGTH_SHORT).show();
                            showSettingsMenu();
                        })
                .show();
    }

    private void showBackupRestoreDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("💾 Local Backup & Restore")
                .setMessage("Create a local compressed archive containing all notebooks, pages, handwritten strokes, drawings, and media, or restore your library from a previous backup.")
                .setPositiveButton("📦 Create Backup", (d, w) -> {
                    String timeStamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                    String fileName = "OpenNotes_Backup_" + timeStamp + ".zip";
                    try {
                        createBackupLauncher.launch(fileName);
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("📥 Restore Backup", (d, w) -> {
                    try {
                        restoreBackupLauncher.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void showBackupProgress(String message) {
        if (isFinishing() || isDestroyed()) return;
        backupProgressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Please wait")
                .setMessage(message)
                .setCancelable(false)
                .create();
        ThemeUtils.styleDialog(backupProgressDialog, this);
        backupProgressDialog.show();
    }

    private void dismissBackupProgress() {
        if (backupProgressDialog != null && backupProgressDialog.isShowing()) {
            backupProgressDialog.dismiss();
            backupProgressDialog = null;
        }
    }

    private void handleCreateBackup(Uri uri) {
        showBackupProgress("Exporting complete library backup to ZIP...");
        BackupManager.createBackup(this, uri, new BackupManager.BackupCallback() {
            @Override
            public void onSuccess(int notebookCount, long totalBytes) {
                runOnUiThread(() -> {
                    dismissBackupProgress();
                    String formattedSize = android.text.format.Formatter.formatShortFileSize(MainActivity.this, totalBytes);
                    Toast.makeText(MainActivity.this,
                            "✅ Backup saved successfully! (" + notebookCount + " notebooks, " + formattedSize + ")",
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    dismissBackupProgress();
                    new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle("Backup Failed")
                            .setMessage("Error creating backup: " + error.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void confirmRestoreBackup(Uri uri) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Restore Library from Backup?")
                .setMessage("⚠️ Restoring will merge and replace notebooks with the contents of this backup archive.\n\nDo you want to proceed?")
                .setPositiveButton("Restore Now", (d, w) -> executeRestore(uri))
                .setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void executeRestore(Uri uri) {
        showBackupProgress("Restoring library from backup archive...");
        BackupManager.restoreBackup(this, uri, new BackupManager.RestoreCallback() {
            @Override
            public void onSuccess(int notebookCount) {
                runOnUiThread(() -> {
                    dismissBackupProgress();
                    loadNotebooks();
                    Toast.makeText(MainActivity.this,
                            "✅ Restored successfully! " + notebookCount + " notebooks loaded.",
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    dismissBackupProgress();
                    new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle("Restore Failed")
                            .setMessage("Could not restore backup:\n" + error.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (adapter != null && adapter.isSelectionMode()) {
            adapter.exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        searchDebounceHandler.removeCallbacksAndMessages(null);
        notebookIoExecutor.shutdown();
        super.onDestroy();
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
