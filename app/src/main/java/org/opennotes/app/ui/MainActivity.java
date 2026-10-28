package org.opennotes.app.ui;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
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
import androidx.appcompat.widget.SwitchCompat;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.Task;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import org.opennotes.app.sync.GoogleAuthManager;
import org.opennotes.app.sync.DriveSyncManager;

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

    private java.util.function.Consumer<String> pendingCoverImageCallback;

    private final ActivityResultLauncher<String[]> openCoverImageLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || pendingCoverImageCallback == null) return;
                try {
                    String savedPath = NotebookCoverUi.importImage(this, uri);
                    if (savedPath != null) pendingCoverImageCallback.accept(savedPath);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not load cover image", Toast.LENGTH_SHORT).show();
                }
            });

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
    private LinearProgressIndicator backupProgressIndicator;
    private TextView backupProgressStatus;
    private TextView backupProgressPercent;

    private AlertDialog googleDriveSyncDialog;

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getData() != null) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        GoogleSignInAccount account = task.getResult(ApiException.class);
                        if (account != null) {
                            GoogleAuthManager.getInstance().onSignInSuccess(this, account);
                            Toast.makeText(this, "Signed in as " + account.getEmail(), Toast.LENGTH_SHORT).show();
                            updateCloudSyncIcon();
                            DriveSyncManager.getInstance().syncNow(this, true, null);
                            if (googleDriveSyncDialog != null && googleDriveSyncDialog.isShowing()) {
                                googleDriveSyncDialog.dismiss();
                                showGoogleDriveSyncDialog();
                            }
                        }
                    } catch (ApiException e) {
                        handleGoogleSignInError(e);
                    }
                }
            });

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

        ImageView btnCloudSync = findViewById(R.id.btnCloudSync);
        if (btnCloudSync != null) {
            btnCloudSync.setOnClickListener(v -> showGoogleDriveSyncDialog());
        }

        ImageView btnMenuMore = findViewById(R.id.btnMenuMore);
        if (btnMenuMore != null) {
            btnMenuMore.setOnClickListener(v -> showSettingsMenu());
        }

        DriveSyncManager.getInstance().addListener(new DriveSyncManager.SyncListener() {
            @Override
            public void onSyncStarted() {
                runOnUiThread(() -> {
                    updateCloudSyncIcon();
                    com.google.android.material.progressindicator.LinearProgressIndicator topBar = findViewById(R.id.topSyncProgressBar);
                    if (topBar != null) {
                        topBar.setVisibility(View.VISIBLE);
                        topBar.setIndeterminate(true);
                    }
                });
            }

            @Override
            public void onSyncProgress(String stage, int current, int total) {
                runOnUiThread(() -> {
                    com.google.android.material.progressindicator.LinearProgressIndicator topBar = findViewById(R.id.topSyncProgressBar);
                    if (topBar != null) {
                        topBar.setVisibility(View.VISIBLE);
                        if (total > 0) {
                            int pct = Math.min(100, Math.max(0, (current * 100) / total));
                            topBar.setIndeterminate(false);
                            topBar.setProgressCompat(pct, true);
                        } else {
                            topBar.setIndeterminate(true);
                        }
                    }
                });
            }

            @Override
            public void onSyncCompleted(int uploaded, int downloaded, int deleted) {
                runOnUiThread(() -> {
                    updateCloudSyncIcon();
                    com.google.android.material.progressindicator.LinearProgressIndicator topBar = findViewById(R.id.topSyncProgressBar);
                    if (topBar != null) {
                        topBar.setIndeterminate(false);
                        topBar.setProgressCompat(100, true);
                        topBar.postDelayed(() -> topBar.setVisibility(View.GONE), 1200);
                    }
                    loadNotebooks();
                });
            }

            @Override
            public void onSyncError(String errorMessage) {
                runOnUiThread(() -> {
                    updateCloudSyncIcon();
                    com.google.android.material.progressindicator.LinearProgressIndicator topBar = findViewById(R.id.topSyncProgressBar);
                    if (topBar != null) {
                        topBar.setVisibility(View.GONE);
                    }
                });
            }
        });

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
        enableHighRefreshRate();
        FullscreenMode.apply(this);
        updateCloudSyncIcon();
        DriveSyncManager.getInstance().scheduleAutoSync(this);
        List<Notebook> cached = dbHelper.getCachedNotebooks();
        if (cached != null && !cached.isEmpty() && adapter != null) {
            adapter.updateData(cached, false);
            if (emptyStateView != null) emptyStateView.setVisibility(View.GONE);
            if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
        }
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
        final int[] selectedPageBgColor = {AppSettings.getInstance(this).getDefaultPageBgColor()};
        PaperTemplate initialTpl = AppSettings.getInstance(this).getDefaultPaperTemplate();
        final PaperTemplate[] selectedTemplate = {initialTpl != null ? initialTpl : PaperTemplate.BLANK};
        final boolean[] isInfinite = {true};

        Runnable updatePreview = () -> {
            previewCoverBox.setBackground(selectedCoverStyle[0] >= 0
                    ? NotebookCoverStyles.drawable(selectedCoverStyle[0])
                    : NotebookCoverStyles.legacyDrawable(selectedCoverColor[0]));
            previewCoverBox.setClipToOutline(true);
            Bitmap bitmap = NotebookCoverUi.decodePreview(selectedCoverImage[0]);
            previewCoverImage.setImageBitmap(bitmap);
            previewCoverImage.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
            String caption = editCoverCaption.getText() == null ? "" : editCoverCaption.getText().toString().trim();
            textPreviewCoverCaption.setText(caption);
            textPreviewCoverCaption.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
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

        NotebookCoverUi.populateStyles(this, layoutColorButtons, 0, style -> {
            selectedCoverStyle[0] = style;
            selectedCoverColor[0] = NotebookCoverStyles.startColor(style);
            selectedCoverImage[0] = null;
            for (int i = 0; i < layoutColorButtons.getChildCount(); i++) {
                ((GradientDrawable) layoutColorButtons.getChildAt(i).getBackground()).setStroke(
                        Math.round((i == style ? 3 : 1) * getResources().getDisplayMetrics().density),
                        i == style ? 0xFF38BDF8 : 0x66FFFFFF);
            }
            updatePreview.run();
        });
        btnChooseCustomCover.setOnClickListener(v -> {
            pendingCoverImageCallback = path -> {
                selectedCoverImage[0] = path;
                selectedCoverStyle[0] = -1;
                for (int i = 0; i < layoutColorButtons.getChildCount(); i++) {
                    ((GradientDrawable) layoutColorButtons.getChildAt(i).getBackground()).setStroke(
                            Math.round(getResources().getDisplayMetrics().density), 0x66FFFFFF);
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
            newNb.coverStyle = selectedCoverStyle[0];
            newNb.coverImagePath = selectedCoverImage[0];
            newNb.coverText = editCoverCaption.getText() != null ? editCoverCaption.getText().toString().trim() : "";
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
                    if (nb.title != null) {
                        intent.putExtra("notebook_title", nb.title);
                    }
                    if (nb.defaultTemplate != null) {
                        intent.putExtra("template", nb.defaultTemplate.name());
                    }
                    intent.putExtra("bg_color", nb.defaultBgColor);
                    intent.putExtra("is_infinite", nb.defaultIsInfinite);
                    break;
                }
            }
        }
        startActivity(intent);
        overridePendingTransition(R.anim.activity_open_enter, R.anim.activity_open_exit);
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
                .addOption(6, notebook.isPinned ? "📌" : "📍",
                        notebook.isPinned ? "Unpin Notebook" : "Pin Notebook",
                        notebook.isPinned ? "Remove this notebook from the top of the list" : "Keep this notebook at the top of the list",
                        false, () -> {
                            notebook.isPinned = !notebook.isPinned;
                            final boolean pinned = notebook.isPinned;
                            notebookIoExecutor.execute(() -> dbHelper.setPinned(notebook.id, pinned));
                            loadNotebooks();
                            Toast.makeText(this, pinned ? "Notebook pinned to top" : "Notebook unpinned", Toast.LENGTH_SHORT).show();
                        })
                .addOption(2, "✏️", getString(R.string.rename), "Change notebook title", false, () -> {
                    showRenameDialog(notebook);
                })
                .addOption(7, "🎨", "Edit Notebook Cover", "Choose a design, photo, and cover text", false, () -> {
                    showNotebookCoverEditor(notebook);
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

    private void showNotebookCoverEditor(Notebook notebook) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        root.setPadding(pad, pad, pad, pad);
        TextView heading = new TextView(this);
        heading.setText("Make this notebook yours");
        heading.setTextColor(0xFFF8FAFC);
        heading.setTextSize(18);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(heading);
        FrameLayout preview = new FrameLayout(this);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(Math.round(120*d), Math.round(150*d));
        previewLp.gravity = Gravity.CENTER_HORIZONTAL;
        previewLp.topMargin = Math.round(12*d);
        preview.setLayoutParams(previewLp);
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(this);
        title.setText(notebook.title);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(Math.round(8*d), Math.round(8*d), Math.round(8*d), Math.round(8*d));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        preview.addView(title, new FrameLayout.LayoutParams(-1, -1));
        TextView captionPreview = new TextView(this);
        captionPreview.setTextColor(0xEFFFFFFF);
        captionPreview.setGravity(Gravity.CENTER);
        captionPreview.setTextSize(10);
        FrameLayout.LayoutParams captionLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        captionLp.setMargins(Math.round(6*d), 0, Math.round(6*d), Math.round(7*d));
        preview.addView(captionPreview, captionLp);
        root.addView(preview);

        EditText caption = new EditText(this);
        caption.setSingleLine(true);
        caption.setText(notebook.coverText == null ? "" : notebook.coverText);
        caption.setHint("Add a title or custom cover text");
        caption.setTextColor(0xFFF8FAFC);
        caption.setHintTextColor(0xFF94A3B8);
        caption.setBackgroundResource(R.drawable.bg_search_bar);
        caption.setPadding(Math.round(12*d), 0, Math.round(12*d), 0);
        LinearLayout.LayoutParams captionLp2 = new LinearLayout.LayoutParams(-1, Math.round(44*d));
        captionLp2.topMargin = Math.round(12*d);
        root.addView(caption, captionLp2);
        TextView choosePhoto = new TextView(this);
        choosePhoto.setText("🖼  Choose a photo cover");
        choosePhoto.setTextColor(0xFF38BDF8);
        choosePhoto.setGravity(Gravity.CENTER);
        choosePhoto.setBackgroundResource(R.drawable.bg_floating_pill);
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(-1, Math.round(40*d));
        chooseLp.topMargin = Math.round(8*d);
        root.addView(choosePhoto, chooseLp);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        int[] selected = {notebook.coverStyle >= 0 ? notebook.coverStyle : -1};
        int[] coverColor = {notebook.coverColor};
        String[] imagePath = {notebook.coverImagePath};
        for (int i = 0; i < NotebookCoverStyles.count(); i++) {
            final int style = i;
            FrameLayout card = new FrameLayout(this);
            android.widget.GridLayout.LayoutParams glp = new android.widget.GridLayout.LayoutParams();
            glp.width = 0;
            glp.height = Math.round(66*d);
            glp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
            glp.setMargins(Math.round(4*d), Math.round(4*d), Math.round(4*d), Math.round(4*d));
            card.setLayoutParams(glp);
            card.setBackground(NotebookCoverStyles.drawable(i));
            TextView label = new TextView(this);
            label.setText(NotebookCoverStyles.NAMES[i]);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(10);
            card.addView(label, new FrameLayout.LayoutParams(-1, -1));
            if (selected[0] == i) ((GradientDrawable) card.getBackground()).setStroke(Math.round(3*d), 0xFF38BDF8);
            card.setOnClickListener(v -> {
                selected[0] = style;
                coverColor[0] = NotebookCoverStyles.startColor(style);
                imagePath[0] = null;
                for (int j = 0; j < grid.getChildCount(); j++) {
                    View child = grid.getChildAt(j);
                    ((GradientDrawable) child.getBackground()).setStroke(Math.round(j == style ? 3*d : 1*d),
                            j == style ? 0xFF38BDF8 : 0x66FFFFFF);
                }
                updateCoverEditorPreview(preview, photo, captionPreview, selected[0], coverColor[0], null,
                        caption.getText().toString());
            });
            grid.addView(card);
        }
        scroll.addView(grid);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, Math.round(190*d));
        scrollLp.topMargin = Math.round(8*d);
        root.addView(scroll, scrollLp);
        Runnable refresh = () -> updateCoverEditorPreview(preview, photo, captionPreview,
                selected[0], coverColor[0], imagePath[0], caption.getText().toString());
        caption.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        choosePhoto.setOnClickListener(v -> {
            pendingCoverImageCallback = path -> {
                imagePath[0] = path;
                selected[0] = -1;
                refresh.run();
            };
            openCoverImageLauncher.launch(new String[]{"image/*"});
        });
        refresh.run();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(root)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", (dlg, which) -> {
                    notebook.coverStyle = selected[0];
                    notebook.coverColor = coverColor[0];
                    notebook.coverImagePath = imagePath[0];
                    notebook.coverText = caption.getText().toString().trim();
                    notebookIoExecutor.execute(() -> dbHelper.updateNotebook(notebook));
                    loadNotebooks();
                }).create();
        ThemeUtils.styleDialog(dialog, this);
        dialog.show();
    }

    private void updateCoverEditorPreview(FrameLayout preview, ImageView photo, TextView caption,
                                          int style, int color, String imagePath, String text) {
        preview.setBackground(style >= 0 ? NotebookCoverStyles.drawable(style) : NotebookCoverStyles.legacyDrawable(color));
        preview.setClipToOutline(true);
        Bitmap bitmap = NotebookCoverUi.decodePreview(imagePath);
        photo.setImageBitmap(bitmap);
        photo.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        caption.setText(text == null ? "" : text.trim());
        caption.setVisibility(text == null || text.trim().isEmpty() ? View.GONE : View.VISIBLE);
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

        boolean isGoogleConnected = GoogleAuthManager.getInstance().isSignedIn(this);
        String googleSubtitle = isGoogleConnected
                ? "Connected (" + settings.getGoogleAccountEmail() + ") • " + getSyncTimeAgo(settings.getGoogleLastSyncTime())
                : "Sign in with Google to automatically sync all notes with Google Drive";

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
                .addOption(3, "☁️", "Google Drive Cloud Sync",
                        googleSubtitle,
                        isGoogleConnected, this::showGoogleDriveSyncDialog)
                .addOption(4, "💾", "Local Backup & Restore",
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

    private void showBackupProgress(String title, String subtitle) {
        if (isFinishing() || isDestroyed()) return;
        View progressView = LayoutInflater.from(this).inflate(R.layout.dialog_backup_progress, null);
        ((TextView) progressView.findViewById(R.id.textBackupTitle)).setText(title);
        ((TextView) progressView.findViewById(R.id.textBackupSubtitle)).setText(subtitle);
        backupProgressIndicator = progressView.findViewById(R.id.progressBackup);
        backupProgressStatus = progressView.findViewById(R.id.textBackupStatus);
        backupProgressPercent = progressView.findViewById(R.id.textBackupPercent);
        backupProgressIndicator.setIndeterminate(true);
        backupProgressStatus.setText("Getting things ready…");
        backupProgressPercent.setText("");
        backupProgressDialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
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
        backupProgressIndicator = null;
        backupProgressStatus = null;
        backupProgressPercent = null;
    }

    private void updateBackupProgress(String stage, long current, long total) {
        runOnUiThread(() -> {
            if (backupProgressDialog == null || !backupProgressDialog.isShowing()
                    || backupProgressIndicator == null) return;
            backupProgressStatus.setText(stage);
            if (total <= 0) {
                backupProgressIndicator.setIndeterminate(true);
                backupProgressPercent.setText("");
            } else {
                int percent = (int) Math.min(100, Math.max(0, (current * 100L) / total));
                backupProgressIndicator.setIndeterminate(false);
                backupProgressIndicator.setMax(1000);
                backupProgressIndicator.setProgressCompat((int) Math.min(1000, (current * 1000L) / total), true);
                backupProgressPercent.setText(percent + "%");
            }
        });
    }

    private void handleCreateBackup(Uri uri) {
        showBackupProgress("Creating local backup", "Packing notebooks, pages, and media into a ZIP archive.");
        BackupManager.createBackup(this, uri, this::updateBackupProgress, new BackupManager.BackupCallback() {
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
        showBackupProgress("Restoring your library", "Your notebooks and files will appear when everything is ready.");
        BackupManager.restoreBackup(this, uri, this::updateBackupProgress, new BackupManager.RestoreCallback() {
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

    private void handleGoogleSignInError(ApiException e) {
        int statusCode = e.getStatusCode();
        if (statusCode == 12501 || statusCode == CommonStatusCodes.CANCELED) {
            return;
        }

        if (statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Google Sign-In Options")
                    .setMessage("Android OAuth client is not registered yet in project " + GoogleAuthManager.PROJECT_ID + ".\n\n"
                            + "Would you like to sign in immediately using your Client ID in the browser, or setup the Android Client ID?")
                    .setPositiveButton("Sign in via Browser", (d, w) -> {
                        Toast.makeText(this, "Opening Google sign-in in browser…", Toast.LENGTH_SHORT).show();
                        GoogleAuthManager.getInstance().startBrowserOAuth(this, new GoogleAuthManager.BrowserAuthCallback() {
                            @Override
                            public void onSuccess(String email, String displayName) {
                                Toast.makeText(MainActivity.this, "Signed in as " + email, Toast.LENGTH_SHORT).show();
                                updateCloudSyncIcon();
                                DriveSyncManager.getInstance().syncNow(MainActivity.this, true, null);
                                if (googleDriveSyncDialog != null && googleDriveSyncDialog.isShowing()) {
                                    googleDriveSyncDialog.dismiss();
                                    showGoogleDriveSyncDialog();
                                }
                            }

                            @Override
                            public void onError(String errorMessage) {
                                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                            }
                        });
                    })
                    .setNeutralButton("Setup Android Client", (d, w) -> {
                        showGoogleCloudSetupDialog(
                                "Google Cloud Project: " + GoogleAuthManager.PROJECT_ID + "\n\n"
                                + "Installed Client ID: " + GoogleAuthManager.DEFAULT_CLIENT_ID + "\n\n"
                                + "To enable native Google Play Services 1-tap sign-in:\n"
                                + "1. Go to console.cloud.google.com/apis/credentials?project=" + GoogleAuthManager.PROJECT_ID + "\n"
                                + "2. Create an OAuth 2.0 Client ID for Android:\n"
                                + "   • Package name: " + getPackageName() + "\n"
                                + "   • SHA-1: " + GoogleAuthManager.DEBUG_SHA1_FINGERPRINT + "\n"
                                + "3. Enable 'Google Drive API' in Enabled APIs & Services."
                        );
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            Toast.makeText(this, "Sign-in error (Code " + statusCode + "): " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showGoogleCloudSetupDialog(String details) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Google Cloud Setup Info")
                .setMessage(details)
                .setPositiveButton("Copy SHA-1", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("SHA-1 Fingerprint", GoogleAuthManager.DEBUG_SHA1_FINGERPRINT));
                        Toast.makeText(this, "SHA-1 copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Copy Package", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Package Name", getPackageName()));
                        Toast.makeText(this, "Package name copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("OK", null)
                .show();
    }

    private void showGoogleDriveSyncDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (googleDriveSyncDialog != null) {
            try {
                if (googleDriveSyncDialog.isShowing()) {
                    googleDriveSyncDialog.dismiss();
                }
            } catch (Throwable ignored) {}
            googleDriveSyncDialog = null;
        }

        AppSettings settings = AppSettings.getInstance(this);
        boolean isConnected = GoogleAuthManager.getInstance().isSignedIn(this);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_google_drive_sync, null);
        View layoutSignedOut = dialogView.findViewById(R.id.layoutSignedOut);
        View layoutSignedIn = dialogView.findViewById(R.id.layoutSignedIn);
        View btnSignInGoogle = dialogView.findViewById(R.id.btnSignInGoogle);
        View btnSignInBrowser = dialogView.findViewById(R.id.btnSignInBrowser);
        View btnSetupGuide = dialogView.findViewById(R.id.btnSetupGuide);

        TextView textUserAvatar = dialogView.findViewById(R.id.textUserAvatar);
        TextView textUserName = dialogView.findViewById(R.id.textUserName);
        TextView textUserEmail = dialogView.findViewById(R.id.textUserEmail);
        TextView textLastSyncTime = dialogView.findViewById(R.id.textLastSyncTime);
        SwitchCompat switchAutoSync = dialogView.findViewById(R.id.switchAutoSync);
        View layoutSyncProgress = dialogView.findViewById(R.id.layoutSyncProgress);
        TextView textSyncStatusStage = dialogView.findViewById(R.id.textSyncStatusStage);
        TextView textSyncProgressPercent = dialogView.findViewById(R.id.textSyncProgressPercent);
        com.google.android.material.progressindicator.LinearProgressIndicator progressSync = dialogView.findViewById(R.id.progressSync);
        TextView textSyncStatusPill = dialogView.findViewById(R.id.textSyncStatusPill);
        com.google.android.material.button.MaterialButton btnSyncNow = dialogView.findViewById(R.id.btnSyncNow);
        View btnSignOut = dialogView.findViewById(R.id.btnSignOut);

        if (isConnected) {
            layoutSignedOut.setVisibility(View.GONE);
            layoutSignedIn.setVisibility(View.VISIBLE);

            String name = settings.getGoogleAccountDisplayName();
            String email = settings.getGoogleAccountEmail();
            textUserName.setText(!name.isEmpty() ? name : "Google User");
            textUserEmail.setText(email);
            textUserAvatar.setText(!name.isEmpty() ? name.substring(0, 1).toUpperCase(java.util.Locale.US) : "G");

            long lastSync = settings.getGoogleLastSyncTime();
            textLastSyncTime.setText(getSyncTimeAgo(lastSync));

            switchAutoSync.setChecked(settings.isGoogleAutoSyncEnabled());
            switchAutoSync.setOnCheckedChangeListener((v, isChecked) -> {
                settings.setGoogleAutoSyncEnabled(isChecked);
                Toast.makeText(this, isChecked ? "Auto-sync enabled" : "Auto-sync disabled", Toast.LENGTH_SHORT).show();
            });

            btnSignOut.setOnClickListener(v -> {
                GoogleAuthManager.getInstance().signOut(this, () -> {
                    Toast.makeText(this, "Signed out of Google", Toast.LENGTH_SHORT).show();
                    updateCloudSyncIcon();
                    if (googleDriveSyncDialog != null && googleDriveSyncDialog.isShowing()) {
                        googleDriveSyncDialog.dismiss();
                        showGoogleDriveSyncDialog();
                    }
                });
            });

            boolean currentlySyncing = DriveSyncManager.getInstance().isSyncing();
            if (currentlySyncing) {
                layoutSyncProgress.setVisibility(View.VISIBLE);
                btnSyncNow.setEnabled(false);
                if (textSyncStatusStage != null) textSyncStatusStage.setText("Synchronizing with Google Drive…");
                if (progressSync != null) progressSync.setIndeterminate(true);
                if (textSyncStatusPill != null) {
                    textSyncStatusPill.setText("Syncing…");
                    textSyncStatusPill.setTextColor(0xFF38BDF8);
                }
            } else {
                layoutSyncProgress.setVisibility(View.GONE);
                btnSyncNow.setEnabled(true);
            }

            btnSyncNow.setOnClickListener(v -> {
                DriveSyncManager.getInstance().syncNow(this, true, new DriveSyncManager.SyncListener() {
                    @Override
                    public void onSyncStarted() {
                        runOnUiThread(() -> {
                            layoutSyncProgress.setVisibility(View.VISIBLE);
                            if (textSyncStatusStage != null) textSyncStatusStage.setText("Starting synchronization…");
                            if (textSyncProgressPercent != null) textSyncProgressPercent.setText("0%");
                            if (progressSync != null) progressSync.setIndeterminate(true);
                            if (textSyncStatusPill != null) {
                                textSyncStatusPill.setText("Syncing…");
                                textSyncStatusPill.setTextColor(0xFF38BDF8);
                            }
                            btnSyncNow.setEnabled(false);
                            updateCloudSyncIcon();
                        });
                    }

                    @Override
                    public void onSyncProgress(String stage, int current, int total) {
                        runOnUiThread(() -> {
                            if (textSyncStatusStage != null) textSyncStatusStage.setText(stage);
                            if (progressSync != null) {
                                if (total > 0) {
                                    int pct = Math.min(100, Math.max(0, (current * 100) / total));
                                    progressSync.setIndeterminate(false);
                                    progressSync.setProgressCompat(pct, true);
                                    if (textSyncProgressPercent != null) {
                                        textSyncProgressPercent.setText(pct + "% (" + current + "/" + total + ")");
                                    }
                                } else {
                                    progressSync.setIndeterminate(true);
                                    if (textSyncProgressPercent != null) textSyncProgressPercent.setText("…");
                                }
                            }
                        });
                    }

                    @Override
                    public void onSyncCompleted(int uploadedCount, int downloadedCount, int deletedCount) {
                        runOnUiThread(() -> {
                            if (progressSync != null) {
                                progressSync.setIndeterminate(false);
                                progressSync.setProgressCompat(100, true);
                            }
                            if (textSyncProgressPercent != null) textSyncProgressPercent.setText("100%");
                            if (textSyncStatusStage != null) textSyncStatusStage.setText("All notes up to date!");
                            if (textSyncStatusPill != null) {
                                textSyncStatusPill.setText("● Complete");
                                textSyncStatusPill.setTextColor(0xFF34D399);
                            }
                            textLastSyncTime.setText("Just now");
                            updateCloudSyncIcon();
                            layoutSyncProgress.postDelayed(() -> {
                                if (layoutSyncProgress != null) layoutSyncProgress.setVisibility(View.GONE);
                                if (btnSyncNow != null) btnSyncNow.setEnabled(true);
                            }, 1200);
                            if (uploadedCount > 0 || downloadedCount > 0 || deletedCount > 0) {
                                Toast.makeText(MainActivity.this, "Sync complete! (" + uploadedCount + " uploaded, " + downloadedCount + " downloaded)", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "All notes up to date", Toast.LENGTH_SHORT).show();
                            }
                            loadNotebooks();
                        });
                    }

                    @Override
                    public void onSyncError(String errorMessage) {
                        runOnUiThread(() -> {
                            if (textSyncStatusStage != null) textSyncStatusStage.setText("Sync failed: " + errorMessage);
                            if (textSyncStatusPill != null) {
                                textSyncStatusPill.setText("● Error");
                                textSyncStatusPill.setTextColor(0xFFF87171);
                            }
                            btnSyncNow.setEnabled(true);
                            updateCloudSyncIcon();
                            layoutSyncProgress.postDelayed(() -> {
                                if (layoutSyncProgress != null) layoutSyncProgress.setVisibility(View.GONE);
                            }, 2500);
                            Toast.makeText(MainActivity.this, "Sync error: " + errorMessage, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            });

        } else {
            layoutSignedOut.setVisibility(View.VISIBLE);
            layoutSignedIn.setVisibility(View.GONE);

            btnSignInGoogle.setOnClickListener(v -> {
                try {
                    Intent signInIntent = GoogleAuthManager.getInstance().getSignInIntent(this);
                    googleSignInLauncher.launch(signInIntent);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not open Google Sign-In: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

            if (btnSignInBrowser != null) {
                btnSignInBrowser.setOnClickListener(v -> {
                    Toast.makeText(this, "Opening Google sign-in in browser…", Toast.LENGTH_SHORT).show();
                    GoogleAuthManager.getInstance().startBrowserOAuth(this, new GoogleAuthManager.BrowserAuthCallback() {
                        @Override
                        public void onSuccess(String email, String displayName) {
                            Toast.makeText(MainActivity.this, "Signed in as " + email, Toast.LENGTH_SHORT).show();
                            updateCloudSyncIcon();
                            DriveSyncManager.getInstance().syncNow(MainActivity.this, true, null);
                            if (googleDriveSyncDialog != null && googleDriveSyncDialog.isShowing()) {
                                googleDriveSyncDialog.dismiss();
                                showGoogleDriveSyncDialog();
                            }
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                        }
                    });
                });
            }

            btnSetupGuide.setOnClickListener(v -> {
                showGoogleCloudSetupDialog(
                        "Google Cloud Configuration Details:\n\n"
                        + "• Project ID: " + GoogleAuthManager.PROJECT_ID + "\n"
                        + "• Package Name: " + getPackageName() + "\n"
                        + "• Debug SHA-1: " + GoogleAuthManager.DEBUG_SHA1_FINGERPRINT + "\n"
                        + "• Client ID: " + GoogleAuthManager.DEFAULT_CLIENT_ID + "\n\n"
                        + "To connect Google Drive with 1-tap Google Play Services:\n"
                        + "1. Go to console.cloud.google.com/apis/credentials?project=" + GoogleAuthManager.PROJECT_ID + "\n"
                        + "2. Create an OAuth 2.0 Client ID for Android with the package name and SHA-1 above.\n"
                        + "3. Enable 'Google Drive API' in Enabled APIs & Services."
                );
            });
        }

        googleDriveSyncDialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .create();
        ThemeUtils.styleDialog(googleDriveSyncDialog, this);
        googleDriveSyncDialog.setOnDismissListener(d -> googleDriveSyncDialog = null);
        googleDriveSyncDialog.show();
    }

    private String getSyncTimeAgo(long timestamp) {
        if (timestamp <= 0L) return "Never";
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 60_000L) return "Just now";
        if (diff < 3600_000L) return (diff / 60_000L) + "m ago";
        if (diff < 86400_000L) return (diff / 3600_000L) + "h ago";
        return new java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(timestamp));
    }

    private void updateCloudSyncIcon() {
        ImageView btnCloudSync = findViewById(R.id.btnCloudSync);
        if (btnCloudSync == null) return;
        boolean signedIn = GoogleAuthManager.getInstance().isSignedIn(this);
        boolean isSyncing = DriveSyncManager.getInstance().isSyncing();

        if (isSyncing) {
            btnCloudSync.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.accent));
            if (btnCloudSync.getAnimation() == null) {
                android.view.animation.AnimationSet animSet = new android.view.animation.AnimationSet(true);
                animSet.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());

                android.view.animation.AlphaAnimation fade = new android.view.animation.AlphaAnimation(1.0f, 0.35f);
                fade.setDuration(900);
                fade.setRepeatCount(android.view.animation.Animation.INFINITE);
                fade.setRepeatMode(android.view.animation.Animation.REVERSE);
                animSet.addAnimation(fade);

                android.view.animation.ScaleAnimation scale = new android.view.animation.ScaleAnimation(
                        1.0f, 1.08f, 1.0f, 1.08f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
                scale.setDuration(900);
                scale.setRepeatCount(android.view.animation.Animation.INFINITE);
                scale.setRepeatMode(android.view.animation.Animation.REVERSE);
                animSet.addAnimation(scale);

                btnCloudSync.startAnimation(animSet);
            }
        } else {
            btnCloudSync.clearAnimation();
            btnCloudSync.setAlpha(1.0f);
            btnCloudSync.setScaleX(1.0f);
            btnCloudSync.setScaleY(1.0f);
            if (signedIn) {
                btnCloudSync.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.accent));
            } else {
                btnCloudSync.clearColorFilter();
            }
        }
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
