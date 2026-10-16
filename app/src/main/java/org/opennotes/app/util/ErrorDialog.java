package org.opennotes.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Presents complete error details in a scrollable, selectable, copyable dialog. */
public final class ErrorDialog {
    private ErrorDialog() {}

    public static void show(Context context, String title, Throwable error) {
        String details = error == null ? "No additional error details were provided."
                : Log.getStackTraceString(error);
        if (TextUtils.isEmpty(details)) details = error == null ? "Unknown error." : error.toString();
        show(context, title, details);
    }

    public static void show(Context context, String title, String details) {
        if (context == null) return;
        String completeDetails = TextUtils.isEmpty(details) ? "No additional error details were provided." : details;
        int horizontalPadding = dp(context, 20);
        int verticalPadding = dp(context, 12);

        TextView text = new TextView(context);
        text.setText(completeDetails);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        text.setLinksClickable(true);
        text.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(text, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(scroll, dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
                .setPositiveButton("Copy details", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(title, completeDetails));
                        Toast.makeText(context, "Full error details copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
