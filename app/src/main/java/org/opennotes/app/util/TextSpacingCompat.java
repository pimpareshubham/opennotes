package org.opennotes.app.util;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Paint word-spacing compatibility for the app's API 26 minimum. */
public final class TextSpacingCompat {
    private static final Pattern TEXT_OR_WHITESPACE = Pattern.compile("\\S+|\\s+");

    private TextSpacingCompat() { }

    public static void configure(Paint paint, float letterSpacingEm, float wordSpacingPx) {
        if (paint == null) return;
        if (Build.VERSION.SDK_INT >= 21) paint.setLetterSpacing(letterSpacingEm);
        if (Build.VERSION.SDK_INT >= 29) paint.setWordSpacing(clampWordSpacing(paint, wordSpacingPx));
    }

    public static float measureText(Paint paint, String text, float wordSpacingPx) {
        if (paint == null || text == null || text.isEmpty()) return 0f;
        if (Build.VERSION.SDK_INT >= 29) {
            paint.setWordSpacing(clampWordSpacing(paint, wordSpacingPx));
            return paint.measureText(text);
        }
        return paint.measureText(text) + countSpaces(text) * clampWordSpacing(paint, wordSpacingPx);
    }

    public static void drawText(Canvas canvas, String text, float x, float y,
                                Paint paint, float wordSpacingPx) {
        if (canvas == null || paint == null || text == null || text.isEmpty()) return;
        float safeWordSpacing = clampWordSpacing(paint, wordSpacingPx);
        if (Build.VERSION.SDK_INT >= 29 || safeWordSpacing == 0f || text.indexOf(' ') < 0) {
            if (Build.VERSION.SDK_INT >= 29) paint.setWordSpacing(safeWordSpacing);
            canvas.drawText(text, x, y, paint);
            return;
        }

        // Paint.setWordSpacing is unavailable before API 29. Draw preserved runs and
        // add the requested pixel adjustment after each ordinary word-space instead.
        Matcher matcher = TEXT_OR_WHITESPACE.matcher(text);
        float cursorX = x;
        while (matcher.find()) {
            String run = matcher.group();
            canvas.drawText(run, cursorX, y, paint);
            cursorX += paint.measureText(run);
            cursorX += countSpaces(run) * safeWordSpacing;
        }
    }

    public static void drawTextItem(Canvas canvas, org.opennotes.app.model.TextItem item, Paint paint) {
        if (canvas == null || item == null || item.text == null || item.text.isEmpty() || paint == null) return;
        boolean hasNewlines = item.text.indexOf('\n') >= 0;
        boolean hasBoxWidth = item.boxWidth > 0f;
        if (!hasNewlines && !hasBoxWidth) {
            drawText(canvas, item.text, item.x, item.y, paint, item.wordSpacingEm * item.fontSize);
            return;
        }

        android.text.TextPaint tp = (paint instanceof android.text.TextPaint)
                ? (android.text.TextPaint) paint : new android.text.TextPaint(paint);
        int targetW = hasBoxWidth ? Math.max(20, (int) Math.ceil(item.boxWidth)) : 20000;
        android.text.StaticLayout layout;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            layout = android.text.StaticLayout.Builder.obtain(item.text, 0, item.text.length(), tp, targetW)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build();
        } else {
            layout = new android.text.StaticLayout(item.text, tp, targetW,
                    android.text.Layout.Alignment.ALIGN_NORMAL, 1.2f, 0f, false);
        }
        int baseline0 = layout.getLineCount() > 0 ? layout.getLineBaseline(0) : Math.round(item.fontSize);
        canvas.save();
        canvas.translate(item.x, item.y - baseline0);
        layout.draw(canvas);
        canvas.restore();
    }

    private static int countSpaces(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ' ') count++;
        return count;
    }

    private static float clampWordSpacing(Paint paint, float wordSpacingPx) {
        float em = Math.max(1f, paint.getTextSize());
        return Math.max(-0.5f * em, Math.min(em, wordSpacingPx));
    }
}
