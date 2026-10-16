package org.opennotes.app.ui;

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
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import org.opennotes.app.model.Notebook;
import org.opennotes.app.util.ErrorDialog;
import org.opennotes.app.model.PaperTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullscreenMode.apply(this);
        setContentView(R.layout.activity_main);

        dbHelper = NotesDbHelper.getInstance(this);
        isGridView = AppSettings.getInstance(this).isGridView();

        recyclerView = findViewById(R.id.recyclerViewNotebooks);
        emptyStateView = findViewById(R.id.emptyStateView);
        searchEditText = findViewById(R.id.searchEditText);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        btnToggleViewMode = findViewById(R.id.btnToggleViewMode);
        filterChipGroup = findViewById(R.id.filterChipGroup);

        btnToggleViewMode.setImageResource(isGridView ? R.drawable.ic_view_grid : R.drawable.ic_view_list);

        ExtendedFloatingActionButton fabNew = findViewById(R.id.fabNewNotebook);
        View btnEmptyCreate = findViewById(R.id.btnEmptyCreate);

        setupRecyclerView();
        setupFiltersAndSearch();

        fabNew.setOnClickListener(v -> showCreateNotebookDialog());
        btnEmptyCreate.setOnClickListener(v -> showCreateNotebookDialog());

        btnToggleViewMode.setOnClickListener(v -> {
            isGridView = !isGridView;
            AppSettings.getInstance(this).setGridView(isGridView);
            setupLayoutManager();
            btnToggleViewMode.setImageResource(isGridView ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenMode.apply(this);
        loadNotebooks();
    }

    private void setupRecyclerView() {
        adapter = new NotebookAdapter(this, new ArrayList<>(), this);
        setupLayoutManager();
        recyclerView.setAdapter(adapter);
    }

    private void setupLayoutManager() {
        if (isGridView) {
            int spanCount = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
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
        notebookIoExecutor.execute(() -> {
            List<Notebook> result = dbHelper.getAllNotebooks(folder, favoritesOnly, searchQuery);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != notebookLoadGeneration) return;
                adapter.updateData(result);
                boolean isEmpty = result.isEmpty();
                emptyStateView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
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
        final PaperTemplate[] selectedTemplate = {PaperTemplate.RULED};
        final boolean[] isInfinite = {true};

        Runnable updatePreview = () -> {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(16f);
            gd.setColor(selectedCoverColor[0]);
            gd.setStroke(2, 0xFF38BDF8);
            previewCoverBox.setBackground(gd);
        };
        updatePreview.run();

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
            btn.setTextColor(i == 1 ? 0xFFFFFFFF : 0xFF94A3B8);
            btn.setBackgroundResource(i == 1 ? R.drawable.bg_tool_button_active : R.drawable.bg_floating_pill);

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

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
            if (title.isEmpty()) title = "Unnamed note";

            Notebook newNb = new Notebook(title, selectedCoverColor[0], selectedTemplate[0]);
            newNb.defaultIsInfinite = isInfinite[0];
            newNb.defaultBgColor = selectedPageBgColor[0];
            AppSettings.getInstance(this).setDefaultPageBgColor(selectedPageBgColor[0]);
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
        startActivity(intent);
    }

    @Override
    public void onNotebookClick(Notebook notebook) {
        openNotebook(notebook.id);
    }

    @Override
    public void onNotebookFavoriteClick(Notebook notebook) {
        notebook.isFavorite = !notebook.isFavorite;
        notebookIoExecutor.execute(() -> dbHelper.updateNotebook(notebook));
        loadNotebooks();
    }

    @Override
    public void onNotebookMoreClick(Notebook notebook, View anchorView) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchorView);
        popup.getMenu().add(0, 1, 0, "Open");
        popup.getMenu().add(0, 2, 1, R.string.rename);
        popup.getMenu().add(0, 3, 2, R.string.duplicate);
        popup.getMenu().add(0, 4, 3, R.string.export_pdf);
        popup.getMenu().add(0, 5, 4, R.string.delete);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    openNotebook(notebook.id);
                    return true;
                case 2:
                    showRenameDialog(notebook);
                    return true;
                case 3:
                    duplicateNotebook(notebook);
                    return true;
                case 4:
                    exportNotebookPdf(notebook);
                    return true;
                case 5:
                    showDeleteConfirmDialog(notebook);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void showRenameDialog(Notebook notebook) {
        EditText input = new EditText(this);
        input.setText(notebook.title);
        input.setSelectAllOnFocus(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename)
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newTitle = input.getText().toString().trim();
                    if (!newTitle.isEmpty()) {
                        notebook.title = newTitle;
                        notebookIoExecutor.execute(() -> dbHelper.updateNotebook(notebook));
                        loadNotebooks();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Share Notebook PDF"));
            }
        } catch (Exception e) {
            ErrorDialog.show(this, "Notebook export failed", e);
        }
    }

    private void showDeleteConfirmDialog(Notebook notebook) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete)
                .setMessage("Delete \"" + notebook.title + "\"? All pages will be permanently deleted.")
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    notebookIoExecutor.execute(() -> dbHelper.deleteNotebook(notebook.id));
                    loadNotebooks();
                    Toast.makeText(this, "Notebook deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        searchDebounceHandler.removeCallbacksAndMessages(null);
        notebookIoExecutor.shutdown();
        super.onDestroy();
    }
}
