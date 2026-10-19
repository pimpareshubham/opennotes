package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.opennotes.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern, eye-pleasing pill options selector dialog that replaces outdated
 * context menus and plain list dialogs with sleek pill cards, vibrant symbols,
 * tactile touch micro-animations, and buttery-smooth transitions.
 */
public class ModernOptionsSelectorDialog {

    public interface OnOptionSelectedListener {
        void onSelected(OptionItem item);
    }

    public static class OptionItem {
        public final int id;
        public final String symbol;
        public final int iconRes;
        public final String title;
        public final String description;
        public final boolean isSelected;
        public final boolean isDestructive;
        public final boolean isEnabled;
        public final String customBadge;
        public final View.OnClickListener clickListener;

        public OptionItem(int id, @Nullable String symbol, int iconRes, @NonNull String title,
                          @Nullable String description, boolean isSelected, boolean isDestructive,
                          boolean isEnabled, @Nullable String customBadge,
                          @Nullable View.OnClickListener clickListener) {
            this.id = id;
            this.symbol = symbol;
            this.iconRes = iconRes;
            this.title = title;
            this.description = description;
            this.isSelected = isSelected;
            this.isDestructive = isDestructive;
            this.isEnabled = isEnabled;
            this.customBadge = customBadge;
            this.clickListener = clickListener;
        }
    }

    public static class Builder {
        private final Context context;
        private String title;
        private String subtitle;
        private String headerSymbol;
        private int headerIconRes = 0;
        private final List<OptionItem> options = new ArrayList<>();
        private boolean cancelable = true;

        public Builder(@NonNull Context context) {
            this.context = context;
        }

        public Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        public Builder setSubtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder setHeaderSymbol(String symbol) {
            this.headerSymbol = symbol;
            return this;
        }

        public Builder setHeaderIcon(int iconRes) {
            this.headerIconRes = iconRes;
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        public Builder addOption(int id, @Nullable String symbol, @NonNull String title,
                                 @Nullable String description, boolean isSelected,
                                 @NonNull Runnable action) {
            options.add(new OptionItem(id, symbol, 0, title, description, isSelected, false, true,
                    isSelected ? "✓ Active" : null, v -> action.run()));
            return this;
        }

        public Builder addOption(int id, int iconRes, @NonNull String title,
                                 @Nullable String description, boolean isSelected,
                                 @NonNull Runnable action) {
            options.add(new OptionItem(id, null, iconRes, title, description, isSelected, false, true,
                    isSelected ? "✓ Active" : null, v -> action.run()));
            return this;
        }

        public Builder addDestructiveOption(int id, @Nullable String symbol, @NonNull String title,
                                            @Nullable String description, @NonNull Runnable action) {
            options.add(new OptionItem(id, symbol, 0, title, description, false, true, true,
                    null, v -> action.run()));
            return this;
        }

        public Builder addCustomOption(OptionItem item) {
            options.add(item);
            return this;
        }

        public AlertDialog show() {
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_modern_options_selector, null);
            MaterialCardView cardContainer = dialogView.findViewById(R.id.cardContainer);
            TextView textDialogTitle = dialogView.findViewById(R.id.textDialogTitle);
            TextView textDialogSubtitle = dialogView.findViewById(R.id.textDialogSubtitle);
            TextView textHeaderSymbol = dialogView.findViewById(R.id.textHeaderSymbol);
            ImageView imageHeaderIcon = dialogView.findViewById(R.id.imageHeaderIcon);
            View btnCloseDialog = dialogView.findViewById(R.id.btnCloseDialog);
            View btnDialogCancel = dialogView.findViewById(R.id.btnDialogCancel);
            LinearLayout layoutOptionsContainer = dialogView.findViewById(R.id.layoutOptionsContainer);

            if (title != null) {
                textDialogTitle.setText(title);
            }
            if (subtitle != null && !subtitle.isEmpty()) {
                textDialogSubtitle.setText(subtitle);
                textDialogSubtitle.setVisibility(View.VISIBLE);
            } else {
                textDialogSubtitle.setVisibility(View.GONE);
            }

            if (headerSymbol != null && !headerSymbol.isEmpty()) {
                textHeaderSymbol.setText(headerSymbol);
                textHeaderSymbol.setVisibility(View.VISIBLE);
                imageHeaderIcon.setVisibility(View.GONE);
            } else if (headerIconRes != 0) {
                imageHeaderIcon.setImageResource(headerIconRes);
                imageHeaderIcon.setVisibility(View.VISIBLE);
                textHeaderSymbol.setVisibility(View.GONE);
            } else {
                textHeaderSymbol.setText("✨");
                textHeaderSymbol.setVisibility(View.VISIBLE);
                imageHeaderIcon.setVisibility(View.GONE);
            }

            AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                    .setView(dialogView)
                    .setCancelable(cancelable)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setDimAmount(0.65f);
            }

            Runnable dismissWithAnimation = () -> {
                if (!dialog.isShowing()) return;
                cardContainer.animate()
                        .alpha(0f)
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(120)
                        .withEndAction(dialog::dismiss)
                        .start();
            };

            btnCloseDialog.setOnClickListener(v -> dismissWithAnimation.run());
            btnDialogCancel.setOnClickListener(v -> dismissWithAnimation.run());

            LayoutInflater inflater = LayoutInflater.from(context);
            for (OptionItem opt : options) {
                View pillView = inflater.inflate(R.layout.item_modern_option_pill, layoutOptionsContainer, false);
                MaterialCardView cardOptionPill = pillView.findViewById(R.id.cardOptionPill);
                FrameLayout containerOptionIcon = pillView.findViewById(R.id.containerOptionIcon);
                TextView textOptionSymbol = pillView.findViewById(R.id.textOptionSymbol);
                ImageView imageOptionIcon = pillView.findViewById(R.id.imageOptionIcon);
                TextView textOptionTitle = pillView.findViewById(R.id.textOptionTitle);
                TextView textOptionDesc = pillView.findViewById(R.id.textOptionDesc);
                TextView textOptionBadge = pillView.findViewById(R.id.textOptionBadge);
                ImageView imageOptionArrow = pillView.findViewById(R.id.imageOptionArrow);

                textOptionTitle.setText(opt.title);

                if (opt.description != null && !opt.description.isEmpty()) {
                    textOptionDesc.setText(opt.description);
                    textOptionDesc.setVisibility(View.VISIBLE);
                } else {
                    textOptionDesc.setVisibility(View.GONE);
                }

                if (opt.symbol != null && !opt.symbol.isEmpty()) {
                    textOptionSymbol.setText(opt.symbol);
                    textOptionSymbol.setVisibility(View.VISIBLE);
                    imageOptionIcon.setVisibility(View.GONE);
                } else if (opt.iconRes != 0) {
                    imageOptionIcon.setImageResource(opt.iconRes);
                    imageOptionIcon.setVisibility(View.VISIBLE);
                    textOptionSymbol.setVisibility(View.GONE);
                } else {
                    textOptionSymbol.setText("•");
                    textOptionSymbol.setVisibility(View.VISIBLE);
                    imageOptionIcon.setVisibility(View.GONE);
                }

                if (opt.isDestructive) {
                    // Modern Destructive Styling (Crimson/Red)
                    cardOptionPill.setCardBackgroundColor(0x1AEF4444);
                    cardOptionPill.setStrokeColor(0x66EF4444);
                    textOptionTitle.setTextColor(0xFFEF4444);
                    if (opt.iconRes != 0) imageOptionIcon.setColorFilter(0xFFEF4444);

                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setShape(GradientDrawable.RECTANGLE);
                    badgeBg.setCornerRadius(18f);
                    badgeBg.setColor(0x33EF4444);
                    containerOptionIcon.setBackground(badgeBg);
                    imageOptionArrow.setVisibility(View.GONE);
                } else if (opt.isSelected) {
                    // Modern Selected/Active Styling (StarNote Glowing Sky-Blue #38BDF8)
                    cardOptionPill.setCardBackgroundColor(0x2238BDF8);
                    cardOptionPill.setStrokeColor(0xFF38BDF8);
                    cardOptionPill.setStrokeWidth(Math.round(1.5f * context.getResources().getDisplayMetrics().density));
                    textOptionTitle.setTextColor(0xFF38BDF8);
                    if (opt.iconRes != 0) imageOptionIcon.setColorFilter(0xFF38BDF8);

                    GradientDrawable iconBg = new GradientDrawable();
                    iconBg.setShape(GradientDrawable.RECTANGLE);
                    iconBg.setCornerRadius(18f);
                    iconBg.setColor(0x4038BDF8);
                    iconBg.setStroke(1, 0x8038BDF8);
                    containerOptionIcon.setBackground(iconBg);

                    // Active badge
                    textOptionBadge.setVisibility(View.VISIBLE);
                    textOptionBadge.setText(opt.customBadge != null ? opt.customBadge : "✓ Active");
                    textOptionBadge.setTextColor(0xFF38BDF8);
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setShape(GradientDrawable.RECTANGLE);
                    badgeBg.setCornerRadius(14f);
                    badgeBg.setColor(0x2E38BDF8);
                    badgeBg.setStroke(1, 0x6638BDF8);
                    textOptionBadge.setBackground(badgeBg);

                    imageOptionArrow.setVisibility(View.GONE);
                } else {
                    // Modern Neutral Pill Styling
                    cardOptionPill.setCardBackgroundColor(0xFF1E1E24);
                    cardOptionPill.setStrokeColor(0xFF33333E);
                    textOptionTitle.setTextColor(0xFFF8FAFC);
                    if (opt.iconRes != 0) imageOptionIcon.setColorFilter(0xFF94A3B8);

                    GradientDrawable iconBg = new GradientDrawable();
                    iconBg.setShape(GradientDrawable.RECTANGLE);
                    iconBg.setCornerRadius(18f);
                    iconBg.setColor(0xFF282832);
                    iconBg.setStroke(1, 0xFF383848);
                    containerOptionIcon.setBackground(iconBg);

                    if (opt.customBadge != null && !opt.customBadge.isEmpty()) {
                        textOptionBadge.setVisibility(View.VISIBLE);
                        textOptionBadge.setText(opt.customBadge);
                        textOptionBadge.setTextColor(0xFF94A3B8);
                        GradientDrawable badgeBg = new GradientDrawable();
                        badgeBg.setShape(GradientDrawable.RECTANGLE);
                        badgeBg.setCornerRadius(14f);
                        badgeBg.setColor(0xFF282832);
                        badgeBg.setStroke(1, 0xFF383848);
                        textOptionBadge.setBackground(badgeBg);
                        imageOptionArrow.setVisibility(View.GONE);
                    } else {
                        textOptionBadge.setVisibility(View.GONE);
                        imageOptionArrow.setVisibility(View.VISIBLE);
                    }
                }

                if (!opt.isEnabled) {
                    pillView.setAlpha(0.4f);
                    pillView.setEnabled(false);
                } else {
                    pillView.setOnClickListener(v -> {
                        // Tactile haptic click and micro-bounce
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        v.animate()
                                .scaleX(0.96f)
                                .scaleY(0.96f)
                                .setDuration(70)
                                .withEndAction(() -> {
                                    v.animate()
                                            .scaleX(1.0f)
                                            .scaleY(1.0f)
                                            .setDuration(70)
                                            .withEndAction(() -> {
                                                dismissWithAnimation.run();
                                                if (opt.clickListener != null) {
                                                    opt.clickListener.onClick(v);
                                                }
                                            })
                                            .start();
                                })
                                .start();
                    });
                }

                layoutOptionsContainer.addView(pillView);
            }

            dialog.setOnShowListener(d -> {
                cardContainer.setAlpha(0f);
                cardContainer.setScaleX(0.92f);
                cardContainer.setScaleY(0.92f);
                cardContainer.animate()
                        .alpha(1f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(2.0f))
                        .start();
            });

            dialog.show();
            return dialog;
        }
    }
}
