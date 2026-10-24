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

/** Shared pressure-shaped paint brush with cached, repeatable texture finishes. */
public final class PaintBrushRenderer {
    public static final String[] TEXTURE_NAMES = {
            "Silky Bristle", "Linen Canvas", "Chalk Dust", "Soft Pastel", "Watercolor Wash",
            "Metallic Foil", "Satin Ribbon", "Frosted Glass", "Neon Glass", "Prism Shimmer", "Velvet Marker"
    };
    public static final int TEXTURE_COUNT = TEXTURE_NAMES.length;
    private static final BitmapShader[] TEXTURE_SHADERS = new BitmapShader[TEXTURE_COUNT];
    private static final LruCache<Integer, PorterDuffColorFilter> COLOR_FILTERS =
            new LruCache<Integer, PorterDuffColorFilter>(24);

    private PaintBrushRenderer() {}

    public static void draw(Canvas canvas, Stroke stroke, float pressureSensitivity,
                            Paint fillPaint, Paint grainPaint) {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty()) return;
        Path shape = stroke.getPressurePath(pressureSensitivity);
        int style = Math.max(0, Math.min(TEXTURE_COUNT - 1, stroke.brushTextureStyle));
        if (style == 8) drawNeonGlass(canvas, stroke, shape, fillPaint, grainPaint);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(stroke.color);
        fillPaint.setAlpha(style == 10 ? 222 : style == 4 ? 188 : style == 7 ? 226 : 246);
        canvas.drawPath(shape, fillPaint);

        RectF bounds = stroke.getBounds();
        if (bounds == null || bounds.isEmpty()) return;
        int darkerColor = darken(stroke.color, style == 5 ? 0.46f : style == 6 ? 0.58f : 0.70f);
        canvas.save();
        canvas.clipPath(shape);
        grainPaint.setStyle(Paint.Style.FILL);
        grainPaint.setShader(textureShader(style));
        grainPaint.setColorFilter(colorFilter(darkerColor));
        grainPaint.setAlpha(textureAlpha(style));
        canvas.drawRect(bounds, grainPaint);
        if (style == 5 || style == 6 || style == 8 || style == 9) {
            grainPaint.setShader(textureShader((style + 3) % TEXTURE_COUNT));
            grainPaint.setColorFilter(colorFilter(lighten(stroke.color, style == 8 ? 1.75f : 1.28f)));
            grainPaint.setAlpha(style == 8 ? 52 : 36);
            canvas.drawRect(bounds, grainPaint);
        }
        grainPaint.setShader(null);
        grainPaint.setColorFilter(null);
        grainPaint.setAlpha(255);
        canvas.restore();
    }

    private static int textureAlpha(int style) {
        switch (style) {
            case 0: return 72;
            case 1: return 48;
            case 2: return 116;
            case 3: return 74;
            case 4: return 42;
            case 5: return 68;
            case 6: return 58;
            case 7: return 34;
            case 8: return 26;
            case 9: return 52;
            case 10: return 20;
            default: return 72;
        }
    }

    private static void drawNeonGlass(Canvas canvas, Stroke stroke, Path shape, Paint fillPaint, Paint detailPaint) {
        int glowColor = lighten(stroke.color, 1.45f);
        detailPaint.setStyle(Paint.Style.STROKE);
        detailPaint.setStrokeJoin(Paint.Join.ROUND);
        detailPaint.setStrokeCap(Paint.Cap.ROUND);
        detailPaint.setShader(null);
        detailPaint.setColorFilter(null);
        detailPaint.setColor(glowColor);
        detailPaint.setAlpha(62);
        detailPaint.setStrokeWidth(Math.max(2.5f, stroke.width * 0.48f));
        canvas.drawPath(shape, detailPaint);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(glowColor);
        fillPaint.setAlpha(64);
        canvas.drawPath(shape, fillPaint);
        fillPaint.setColor(stroke.color);
        fillPaint.setAlpha(236);
        canvas.drawPath(shape, fillPaint);
        detailPaint.setColor(lighten(stroke.color, 2.1f));
        detailPaint.setAlpha(170);
        detailPaint.setStrokeWidth(Math.max(0.75f, stroke.width * 0.12f));
        canvas.drawPath(stroke.getPath(), detailPaint);
        detailPaint.setStyle(Paint.Style.FILL);
        detailPaint.setAlpha(255);
    }

    private static BitmapShader textureShader(int style) {
        synchronized (TEXTURE_SHADERS) {
            BitmapShader shader = TEXTURE_SHADERS[style];
            if (shader == null) {
                shader = new BitmapShader(createTextureTile(style), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                TEXTURE_SHADERS[style] = shader;
            }
            return shader;
        }
    }

    private static Bitmap createTextureTile(int style) {
        final int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Random random = new Random(0x51A7B00BL + style * 977L);
        Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
        grain.setColor(0xFFFFFFFF);
        if (style == 1) {
            grain.setAlpha(42);
            grain.setStrokeWidth(0.42f);
            for (int p = 0; p < size; p += 9) {
                canvas.drawLine(p, 0, p, size, grain);
                canvas.drawLine(0, p, size, p, grain);
            }
            return bitmap;
        }
        int strandCount = new int[]{58, 0, 190, 125, 36, 45, 32, 74, 24, 52, 22}[style];
        for (int i = 0; i < strandCount; i++) {
            int alpha = style == 2 ? 34 + random.nextInt(90)
                    : style == 4 ? 10 + random.nextInt(20)
                    : style == 5 || style == 9 ? 15 + random.nextInt(65)
                    : 14 + random.nextInt(42);
            grain.setAlpha(alpha);
            grain.setStrokeWidth(style == 2 ? 0.35f + random.nextFloat() * 0.85f : 0.2f + random.nextFloat() * 0.48f);
            float x = random.nextFloat() * size;
            float y = random.nextFloat() * size;
            float length = style == 6 ? 42f + random.nextFloat() * 46f
                    : style == 2 || style == 3 ? 2f + random.nextFloat() * 9f
                    : 7f + random.nextFloat() * 32f;
            float drift = style == 5 ? (random.nextFloat() - 0.5f) * 18f
                    : style == 9 ? (random.nextFloat() - 0.5f) * 28f
                    : (random.nextFloat() - 0.5f) * (style == 6 ? 0.5f : 1.4f);
            canvas.drawLine(x, y, x + length, y + drift, grain);
        }
        if (style == 4 || style == 7) {
            for (int i = 0; i < 32; i++) {
                grain.setAlpha(5 + random.nextInt(style == 4 ? 18 : 12));
                canvas.drawCircle(random.nextFloat() * size, random.nextFloat() * size,
                        2f + random.nextFloat() * 8f, grain);
            }
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
