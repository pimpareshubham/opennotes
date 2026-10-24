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
            createGrainTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    private static final LruCache<Integer, PorterDuffColorFilter> COLOR_FILTERS =
            new LruCache<Integer, PorterDuffColorFilter>(24);

    private PaintBrushRenderer() {}

    public static void draw(Canvas canvas, Stroke stroke, float pressureSensitivity,
                            Paint fillPaint, Paint grainPaint) {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty()) return;
        Path shape = stroke.getPressurePath(pressureSensitivity);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(stroke.color);
        fillPaint.setAlpha(238);
        canvas.drawPath(shape, fillPaint);

        RectF bounds = stroke.getBounds();
        if (bounds == null || bounds.isEmpty()) return;
        int darkerColor = darken(stroke.color, 0.43f);
        canvas.save();
        canvas.clipPath(shape);
        grainPaint.setStyle(Paint.Style.FILL);
        grainPaint.setShader(GRAIN_SHADER);
        grainPaint.setColorFilter(colorFilter(darkerColor));
        grainPaint.setAlpha(155);
        canvas.drawRect(bounds, grainPaint);
        grainPaint.setShader(null);
        grainPaint.setColorFilter(null);
        grainPaint.setAlpha(255);
        canvas.restore();
    }

    private static Bitmap createGrainTile() {
        final int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Random random = new Random(0x51A7B00BL);
        Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
        grain.setColor(0xFFFFFFFF);
        for (int i = 0; i < 180; i++) {
            int alpha = 36 + random.nextInt(150);
            grain.setAlpha(alpha);
            grain.setStrokeWidth(0.35f + random.nextFloat() * 1.05f);
            float x = random.nextFloat() * size;
            float y = random.nextFloat() * size;
            float length = 2.5f + random.nextFloat() * 17f;
            float drift = (random.nextFloat() - 0.5f) * 3f;
            canvas.drawLine(x, y, x + length, y + drift, grain);
        }
        for (int i = 0; i < 110; i++) {
            grain.setAlpha(30 + random.nextInt(90));
            float radius = 0.35f + random.nextFloat() * 0.8f;
            canvas.drawCircle(random.nextFloat() * size, random.nextFloat() * size, radius, grain);
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
