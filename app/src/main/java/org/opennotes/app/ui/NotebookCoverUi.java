package org.opennotes.app.ui;

import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Small reusable cover previews and image loading for notebook cover editors. */
public final class NotebookCoverUi {
    private NotebookCoverUi() {}

    public static void populateStyles(Context context, LinearLayout container, int selected,
                                      java.util.function.IntConsumer onSelected) {
        float d = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < NotebookCoverStyles.count(); i++) {
            final int style = i;
            FrameLayout card = new FrameLayout(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(76*d), Math.round(72*d));
            lp.setMargins(Math.round(4*d), Math.round(3*d), Math.round(4*d), Math.round(3*d));
            card.setLayoutParams(lp);
            card.setBackground(NotebookCoverStyles.drawable(i));
            TextView label = new TextView(context);
            label.setText(NotebookCoverStyles.NAMES[i]);
            label.setTextColor(0xFFF8FAFC);
            label.setTextSize(9);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setShadowLayer(3, 0, 1, 0xCC000000);
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            labelLp.setMargins(Math.round(3*d), 0, Math.round(3*d), Math.round(5*d));
            card.addView(label, labelLp);
            GradientDrawable stroke = NotebookCoverStyles.drawable(i);
            if (i == selected) stroke.setStroke(Math.round(3*d), 0xFF38BDF8);
            else stroke.setStroke(Math.round(1*d), 0x66FFFFFF);
            card.setBackground(stroke);
            card.setContentDescription(NotebookCoverStyles.NAMES[i] + " cover");
            card.setOnClickListener(v -> onSelected.accept(style));
            container.addView(card);
        }
    }

    public static Bitmap decodePreview(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = Math.max(1, Math.max(options.outWidth / 500, options.outHeight / 700));
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, options);
    }

    public static String importImage(Context context, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("Invalid image");
        int sample = 1;
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 2000) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new java.io.IOException("Invalid image");
        File directory = new File(context.getFilesDir(), "notebook_covers");
        if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("Cannot create cover directory");
        File target = new File(directory, "cover_" + java.util.UUID.randomUUID() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
        } finally {
            bitmap.recycle();
        }
        return target.getAbsolutePath();
    }
}
