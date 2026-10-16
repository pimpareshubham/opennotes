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

public class NotebookAdapter extends RecyclerView.Adapter<NotebookAdapter.NotebookViewHolder> {

    public interface OnNotebookClickListener {
        void onNotebookClick(Notebook notebook);
        void onNotebookFavoriteClick(Notebook notebook);
        void onNotebookMoreClick(Notebook notebook, View anchorView);
    }

    private final Context context;
    private List<Notebook> notebooks;
    private final OnNotebookClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    public NotebookAdapter(Context context, List<Notebook> notebooks, OnNotebookClickListener listener) {
        this.context = context;
        this.notebooks = notebooks;
        this.listener = listener;
    }

    public void updateData(List<Notebook> newNotebooks) {
        this.notebooks = newNotebooks;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public NotebookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_notebook_grid, parent, false);
        return new NotebookViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NotebookViewHolder holder, int position) {
        Notebook nb = notebooks.get(position);

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
        return notebooks.size();
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
