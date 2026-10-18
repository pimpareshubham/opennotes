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
