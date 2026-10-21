package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import org.opennotes.app.R;
import org.opennotes.app.model.Notebook;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NotebookAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_NEW_NOTEBOOK = 0;
    public static final int VIEW_TYPE_NOTEBOOK = 1;

    public interface OnNotebookClickListener {
        void onNotebookClick(Notebook notebook);
        void onNotebookFavoriteClick(Notebook notebook);
        void onNotebookMoreClick(Notebook notebook, View anchorView);
        void onCreateNewNotebookClick();
        void onNotebookLongClick(Notebook notebook);
        void onSelectionModeChanged(boolean isSelectionMode, int selectedCount);
    }

    private final Context context;
    private List<Notebook> notebooks;
    private final OnNotebookClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
    private boolean isSearching = false;
    private boolean isSelectionMode = false;
    private final Set<Long> selectedNotebookIds = new HashSet<>();

    public NotebookAdapter(Context context, List<Notebook> notebooks, OnNotebookClickListener listener) {
        this.context = context;
        this.notebooks = notebooks;
        this.listener = listener;
    }

    public List<Notebook> getNotebooks() {
        return notebooks != null ? new ArrayList<>(notebooks) : new ArrayList<>();
    }

    public void updateData(List<Notebook> newNotebooks) {
        updateData(newNotebooks, false);
    }

    public void updateData(List<Notebook> newNotebooks, boolean isSearching) {
        if (this.notebooks != null && newNotebooks != null && !isSelectionMode
                && this.isSearching == isSearching && isDataIdentical(this.notebooks, newNotebooks)) {
            return;
        }
        this.notebooks = newNotebooks;
        this.isSearching = isSearching;
        // Clean up selected IDs that no longer exist
        if (isSelectionMode && notebooks != null) {
            Set<Long> validIds = new HashSet<>();
            for (Notebook nb : notebooks) {
                validIds.add(nb.id);
            }
            selectedNotebookIds.retainAll(validIds);
            if (listener != null) {
                listener.onSelectionModeChanged(true, selectedNotebookIds.size());
            }
        }
        notifyDataSetChanged();
    }

    private boolean isDataIdentical(List<Notebook> a, List<Notebook> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Notebook n1 = a.get(i);
            Notebook n2 = b.get(i);
            if (n1 == null || n2 == null) return false;
            if (n1.id != n2.id
                    || n1.updatedAt != n2.updatedAt
                    || n1.pageCount != n2.pageCount
                    || n1.isFavorite != n2.isFavorite
                    || n1.coverColor != n2.coverColor
                    || !java.util.Objects.equals(n1.title, n2.title)
                    || !java.util.Objects.equals(n1.folder, n2.folder)) {
                return false;
            }
        }
        return true;
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void startSelection(long initialId) {
        isSelectionMode = true;
        selectedNotebookIds.clear();
        if (initialId != -1) {
            selectedNotebookIds.add(initialId);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionModeChanged(true, selectedNotebookIds.size());
        }
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        selectedNotebookIds.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionModeChanged(false, 0);
        }
    }

    public void toggleSelection(long id) {
        if (selectedNotebookIds.contains(id)) {
            selectedNotebookIds.remove(id);
        } else {
            selectedNotebookIds.add(id);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionModeChanged(isSelectionMode, selectedNotebookIds.size());
        }
    }

    public void selectAll() {
        if (notebooks != null) {
            for (Notebook nb : notebooks) {
                selectedNotebookIds.add(nb.id);
            }
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionModeChanged(isSelectionMode, selectedNotebookIds.size());
        }
    }

    public void deselectAll() {
        selectedNotebookIds.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionModeChanged(isSelectionMode, 0);
        }
    }

    public boolean areAllSelected() {
        if (notebooks == null || notebooks.isEmpty()) return false;
        return selectedNotebookIds.size() >= notebooks.size();
    }

    public Set<Long> getSelectedNotebookIds() {
        return new HashSet<>(selectedNotebookIds);
    }

    public int getSelectedCount() {
        return selectedNotebookIds.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (!isSearching && position == 0) {
            return VIEW_TYPE_NEW_NOTEBOOK;
        }
        return VIEW_TYPE_NOTEBOOK;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_NEW_NOTEBOOK) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_notebook_new, parent, false);
            return new NewNotebookViewHolder(view);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_notebook_grid, parent, false);
        return new NotebookViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder baseHolder, int position) {
        if (getItemViewType(position) == VIEW_TYPE_NEW_NOTEBOOK) {
            NewNotebookViewHolder holder = (NewNotebookViewHolder) baseHolder;
            if (isSelectionMode) {
                holder.itemView.setAlpha(0.6f);
                holder.itemView.setOnClickListener(v -> {
                    exitSelectionMode();
                    if (listener != null) listener.onCreateNewNotebookClick();
                });
            } else {
                holder.itemView.setAlpha(1.0f);
                holder.itemView.setOnClickListener(v -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                            .withEndAction(() -> {
                                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
                                if (listener != null) listener.onCreateNewNotebookClick();
                            }).start();
                });
            }
            return;
        }

        NotebookViewHolder holder = (NotebookViewHolder) baseHolder;
        int notebookIndex = isSearching ? position : position - 1;
        if (notebookIndex < 0 || notebooks == null || notebookIndex >= notebooks.size()) return;

        Notebook nb = notebooks.get(notebookIndex);
        boolean isSelected = selectedNotebookIds.contains(nb.id);

        holder.titleView.setText(nb.title);
        holder.pagesBadge.setText(nb.pageCount + (nb.pageCount == 1 ? " page" : " pages"));
        holder.dateView.setText(dateFormat.format(new Date(nb.updatedAt)));

        // Cover styling with spine
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setShape(GradientDrawable.RECTANGLE);
        coverBg.setCornerRadius(16f);
        coverBg.setColor(nb.coverColor != 0 ? nb.coverColor : 0xFF1E1E24);
        coverBg.setStroke(1, 0xFF2E2E38);
        holder.coverView.setBackground(coverBg);

        // Favorite icon state
        holder.btnFavorite.setImageResource(nb.isFavorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        if (nb.isFavorite) {
            holder.btnFavorite.setColorFilter(0xFFF59E0B);
        } else {
            holder.btnFavorite.setColorFilter(0xFF94A3B8);
        }

        float density = context.getResources().getDisplayMetrics().density;

        if (isSelectionMode) {
            holder.checkSelect.setVisibility(View.VISIBLE);
            holder.checkSelect.setImageResource(isSelected ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_outline);
            holder.checkSelect.setColorFilter(isSelected ? 0xFF38BDF8 : 0xFF94A3B8);
            holder.cardView.setStrokeColor(isSelected ? 0xFF38BDF8 : 0xFF2E2E38);
            holder.cardView.setStrokeWidth(Math.round((isSelected ? 2.5f : 1f) * density));
            holder.btnFavorite.setVisibility(View.GONE);
            holder.btnMore.setVisibility(View.GONE);

            holder.itemView.setOnClickListener(v -> toggleSelection(nb.id));
            holder.itemView.setOnLongClickListener(v -> {
                toggleSelection(nb.id);
                return true;
            });
        } else {
            holder.checkSelect.setVisibility(View.GONE);
            holder.cardView.setStrokeColor(0xFF2E2E38);
            holder.cardView.setStrokeWidth(Math.round(1f * density));
            holder.btnFavorite.setVisibility(View.VISIBLE);
            holder.btnMore.setVisibility(View.VISIBLE);

            holder.itemView.setOnClickListener(v -> {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                        .withEndAction(() -> {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
                            if (listener != null) listener.onNotebookClick(nb);
                        }).start();
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onNotebookLongClick(nb);
                }
                return true;
            });

            holder.btnFavorite.setOnClickListener(v -> {
                v.animate().scaleX(1.4f).scaleY(1.4f).rotation(30f).setDuration(120)
                        .withEndAction(() -> v.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).setDuration(120).start()).start();
                if (listener != null) listener.onNotebookFavoriteClick(nb);
            });

            holder.btnMore.setOnClickListener(v -> {
                if (listener != null) listener.onNotebookMoreClick(nb, holder.btnMore);
            });
        }
    }

    @Override
    public int getItemCount() {
        if (notebooks == null) return 0;
        if (isSearching) {
            return notebooks.size();
        }
        return notebooks.size() + 1;
    }

    public static class NewNotebookViewHolder extends RecyclerView.ViewHolder {
        public NewNotebookViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    public static class NotebookViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        View coverView;
        TextView titleView;
        TextView pagesBadge;
        TextView dateView;
        ImageView btnFavorite;
        ImageView btnMore;
        ImageView checkSelect;

        public NotebookViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.notebookCard);
            coverView = itemView.findViewById(R.id.coverContainer);
            titleView = itemView.findViewById(R.id.textNotebookTitle);
            pagesBadge = itemView.findViewById(R.id.textPagesBadge);
            dateView = itemView.findViewById(R.id.textNotebookDate);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            btnMore = itemView.findViewById(R.id.btnMore);
            checkSelect = itemView.findViewById(R.id.checkSelect);
        }
    }
}
