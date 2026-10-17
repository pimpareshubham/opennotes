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

import org.opennotes.app.R;
import org.opennotes.app.model.Notebook;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotebookAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_NEW_NOTEBOOK = 0;
    public static final int VIEW_TYPE_NOTEBOOK = 1;

    public interface OnNotebookClickListener {
        void onNotebookClick(Notebook notebook);
        void onNotebookFavoriteClick(Notebook notebook);
        void onNotebookMoreClick(Notebook notebook, View anchorView);
        void onCreateNewNotebookClick();
    }

    private final Context context;
    private List<Notebook> notebooks;
    private final OnNotebookClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
    private boolean isSearching = false;

    public NotebookAdapter(Context context, List<Notebook> notebooks, OnNotebookClickListener listener) {
        this.context = context;
        this.notebooks = notebooks;
        this.listener = listener;
    }

    public void updateData(List<Notebook> newNotebooks) {
        updateData(newNotebooks, false);
    }

    public void updateData(List<Notebook> newNotebooks, boolean isSearching) {
        this.notebooks = newNotebooks;
        this.isSearching = isSearching;
        notifyDataSetChanged();
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
            holder.itemView.setOnClickListener(v -> {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                        .withEndAction(() -> {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
                            if (listener != null) listener.onCreateNewNotebookClick();
                        }).start();
            });
            return;
        }

        NotebookViewHolder holder = (NotebookViewHolder) baseHolder;
        int notebookIndex = isSearching ? position : position - 1;
        if (notebookIndex < 0 || notebookIndex >= notebooks.size()) return;

        Notebook nb = notebooks.get(notebookIndex);

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

        holder.itemView.setOnClickListener(v -> {
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                    .withEndAction(() -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
                        if (listener != null) listener.onNotebookClick(nb);
                    }).start();
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

    @Override
    public int getItemCount() {
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
        View coverView;
        TextView titleView;
        TextView pagesBadge;
        TextView dateView;
        ImageView btnFavorite;
        ImageView btnMore;

        public NotebookViewHolder(@NonNull View itemView) {
            super(itemView);
            coverView = itemView.findViewById(R.id.coverContainer);
            titleView = itemView.findViewById(R.id.textNotebookTitle);
            pagesBadge = itemView.findViewById(R.id.textPagesBadge);
            dateView = itemView.findViewById(R.id.textNotebookDate);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            btnMore = itemView.findViewById(R.id.btnMore);
        }
    }
}
