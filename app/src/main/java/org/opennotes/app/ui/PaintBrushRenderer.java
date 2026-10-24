package org.opennotes.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import org.opennotes.app.model.Stroke;

import java.util.Random;

/** Shared pressure-shaped paint brush with a subtle, repeatable bristle grain. */
public final class PaintBrushRenderer {
    private static final BitmapShader GRAIN_SHADER = new BitmapShader(
            createGrainTile(false), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    private static final BitmapShader HIGHLIGHT_SHADER = new BitmapShader(
            createGrainTile(true), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    private static final LruCache<Integer, PorterDuffColorFilter> COLOR_FILTERS =
            new LruCache<Integer, PorterDuffColorFilter>(24);

    private PaintBrushRenderer() {}

    public static void draw(Canvas canvas, Stroke stroke, float pressureSensitivity,
                            Paint fillPaint, Paint grainPaint) {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty()) return;
        Path shape = stroke.getPressurePath(pressureSensitivity);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(stroke.color);
        fillPaint.setAlpha(246);
        canvas.drawPath(shape, fillPaint);

        RectF bounds = stroke.getBounds();
        if (bounds == null || bounds.isEmpty()) return;
        int darkerColor = darken(stroke.color, 0.70f);
        canvas.save();
        canvas.clipPath(shape);
        grainPaint.setStyle(Paint.Style.FILL);
        grainPaint.setShader(GRAIN_SHADER);
        grainPaint.setColorFilter(colorFilter(darkerColor));
        grainPaint.setAlpha(72);
        canvas.drawRect(bounds, grainPaint);
        grainPaint.setShader(HIGHLIGHT_SHADER);
        grainPaint.setColorFilter(colorFilter(lighten(stroke.color, 1.16f)));
        grainPaint.setAlpha(42);
        canvas.drawRect(bounds, grainPaint);
        grainPaint.setShader(null);
        grainPaint.setColorFilter(null);
        grainPaint.setAlpha(255);
        canvas.restore();
    }

    private static Bitmap createGrainTile(boolean highlight) {
        final int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Random random = new Random(highlight ? 0x51A7B00CL : 0x51A7B00BL);
        Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
        grain.setColor(0xFFFFFFFF);
        int strandCount = highlight ? 38 : 58;
        for (int i = 0; i < strandCount; i++) {
            int alpha = highlight ? 12 + random.nextInt(34) : 14 + random.nextInt(42);
            grain.setAlpha(alpha);
            grain.setStrokeWidth(0.2f + random.nextFloat() * 0.48f);
            float x = random.nextFloat() * size;
            float y = random.nextFloat() * size;
            float length = 7f + random.nextFloat() * (highlight ? 24f : 32f);
            float drift = (random.nextFloat() - 0.5f) * 1.4f;
            canvas.drawLine(x, y, x + length, y + drift, grain);
        }
        return bitmap;
    }

    private static int darken(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.round(((color >>> 16) & 0xFF) * amount);
        int g = Math.round(((color >>> 8) & 0xFF) * amount);
        int b = Math.round((color & 0xFF) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((color >>> 16) & 0xFF) * amount));
        int g = Math.min(255, Math.round(((color >>> 8) & 0xFF) * amount));
        int b = Math.min(255, Math.round((color & 0xFF) * amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static PorterDuffColorFilter colorFilter(int color) {
        synchronized (COLOR_FILTERS) {
            PorterDuffColorFilter filter = COLOR_FILTERS.get(color);
            if (filter == null) {
                filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
                COLOR_FILTERS.put(color, filter);
            }
            return filter;
        }
    }
}
