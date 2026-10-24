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
import org.opennotes.app.model.DrawingTool;

import java.util.Random;

/** Shared pressure-shaped paint brush with cached, repeatable texture finishes. */
public final class PaintBrushRenderer {
    public static final String[] TEXTURE_NAMES = {
            "Silky Bristle", "Linen Canvas", "Chalk Dust", "Soft Pastel", "Watercolor Wash",
            "Metallic Foil", "Satin Ribbon", "Frosted Glass", "Neon Glass", "Prism Shimmer", "Velvet Marker",
            "Diamond Glitter"
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

        // 1. Neon glow backdrop if neon style is selected
        if (style == 8) drawNeonGlass(canvas, stroke, shape, fillPaint, grainPaint);

        // 2. Solid, rich, viscous paint base coat
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(stroke.color);
        fillPaint.setAlpha(style == 10 ? 235 : style == 4 ? 200 : style == 7 ? 230 : 255);
        canvas.drawPath(shape, fillPaint);

        // 3. Volumetric 3D Impasto lighting & longitudinal bristle striations (clipped to shape)
        RectF bounds = stroke.getBounds();
        if (bounds != null && !bounds.isEmpty()) {
            canvas.save();
            canvas.clipPath(shape);

            // A. Draw 3D Volumetric Lighting (Upper crest highlight & specular core)
            drawVolumetricLighting(canvas, stroke, grainPaint);

            // B. Draw Longitudinal Bristle Grooves (shadow valleys between bristles)
            Path shades = stroke.getBrushShadeStrands();
            if (shades != null && !shades.isEmpty()) {
                grainPaint.setShader(null);
                grainPaint.setColorFilter(null);
                grainPaint.setStyle(Paint.Style.STROKE);
                grainPaint.setStrokeCap(Paint.Cap.ROUND);
                grainPaint.setStrokeJoin(Paint.Join.ROUND);
                grainPaint.setColor(darken(stroke.color, 0.64f));
                grainPaint.setAlpha(125);
                grainPaint.setStrokeWidth(Math.max(0.65f, Math.min(1.85f, stroke.width * 0.045f)));
                canvas.drawPath(shades, grainPaint);
            }

            // C. Draw Longitudinal Bristle Ridges (glistening highlights along bristles)
            Path highlights = stroke.getBrushHighlightStrands();
            if (highlights != null && !highlights.isEmpty()) {
                grainPaint.setShader(null);
                grainPaint.setColorFilter(null);
                grainPaint.setStyle(Paint.Style.STROKE);
                grainPaint.setStrokeCap(Paint.Cap.ROUND);
                grainPaint.setStrokeJoin(Paint.Join.ROUND);
                grainPaint.setColor(lighten(stroke.color, 1.42f));
                grainPaint.setAlpha(175);
                grainPaint.setStrokeWidth(Math.max(0.75f, Math.min(2.10f, stroke.width * 0.052f)));
                canvas.drawPath(highlights, grainPaint);
            }

            // D. Apply texture finishes for non-default styles (Linen, Foil, Glitter, etc.)
            if (style > 0) {
                applyTextureFinish(canvas, stroke, bounds, grainPaint, style);
            }

            canvas.restore();
        }

        // 4. Draw Staggered Bristle Head Marks (natural touch-down contacts at stroke start)
        Path headFibers = stroke.getBrushEdgeFibers();
        if (headFibers != null && !headFibers.isEmpty()) {
            grainPaint.setShader(null);
            grainPaint.setColorFilter(null);
            grainPaint.setStyle(Paint.Style.STROKE);
            grainPaint.setStrokeCap(Paint.Cap.ROUND);
            grainPaint.setColor(darken(stroke.color, 0.78f));
            grainPaint.setAlpha(160);
            grainPaint.setStrokeWidth(Math.max(0.55f, Math.min(1.4f, stroke.width * 0.035f)));
            canvas.drawPath(headFibers, grainPaint);

            grainPaint.setColor(lighten(stroke.color, 1.25f));
            grainPaint.setAlpha(140);
            grainPaint.setStrokeWidth(Math.max(0.45f, Math.min(1.1f, stroke.width * 0.028f)));
            canvas.drawPath(headFibers, grainPaint);
        }

        // 5. Draw Sweeping, Feathered, Wispy Tail Swash (Trailing bristle fibers at stroke release)
        drawFeatheredTailSwash(canvas, stroke, grainPaint, style);

        // Restore paints
        grainPaint.setStyle(Paint.Style.FILL);
        grainPaint.setAlpha(255);
        grainPaint.setShader(null);
        grainPaint.setColorFilter(null);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setAlpha(255);
    }

    private static void drawVolumetricLighting(Canvas canvas, Stroke stroke, Paint paint) {
        Path mainFibers = stroke.getBrushMainFibers();
        if (mainFibers == null || mainFibers.isEmpty()) return;
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // Upper crest highlight (soft, rich volumetric reflection along the top ridge)
        paint.setColor(lighten(stroke.color, 1.36f));
        paint.setAlpha(95);
        paint.setStrokeWidth(Math.max(2.2f, stroke.width * 0.32f));
        canvas.drawPath(mainFibers, paint);

        // Specular glistening core (sharp wet-paint reflection)
        paint.setColor(lighten(stroke.color, 1.75f));
        paint.setAlpha(140);
        paint.setStrokeWidth(Math.max(0.9f, stroke.width * 0.11f));
        canvas.drawPath(mainFibers, paint);
    }

    private static void drawFeatheredTailSwash(Canvas canvas, Stroke stroke, Paint paint, int style) {
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // 1. Shadow / depth fibers
        Path tailShades = stroke.getBrushTailShades();
        if (tailShades != null && !tailShades.isEmpty()) {
            paint.setColor(darken(stroke.color, 0.68f));
            paint.setAlpha(145);
            paint.setStrokeWidth(Math.max(0.55f, Math.min(1.35f, stroke.width * 0.032f)));
            canvas.drawPath(tailShades, paint);
        }

        // 2. Main saturated body color fibers
        Path tailMain = stroke.getBrushTailFibers();
        if (tailMain != null && !tailMain.isEmpty()) {
            paint.setColor(stroke.color);
            paint.setAlpha(220);
            paint.setStrokeWidth(Math.max(0.70f, Math.min(1.75f, stroke.width * 0.042f)));
            canvas.drawPath(tailMain, paint);
        }

        // 3. Luminous highlight fibers
        Path tailHighlights = stroke.getBrushTailHighlights();
        if (tailHighlights != null && !tailHighlights.isEmpty()) {
            paint.setColor(lighten(stroke.color, style == 8 ? 2.0f : 1.38f));
            paint.setAlpha(style == 8 ? 230 : 185);
            paint.setStrokeWidth(Math.max(0.50f, Math.min(1.25f, stroke.width * 0.030f)));
            canvas.drawPath(tailHighlights, paint);
        }
    }

    private static void applyTextureFinish(Canvas canvas, Stroke stroke, RectF bounds,
                                          Paint grainPaint, int style) {
        int darkerColor = darken(stroke.color, style == 5 ? 0.46f : style == 6 ? 0.58f : style == 11 ? 0.54f : 0.70f);
        grainPaint.setStyle(Paint.Style.FILL);
        grainPaint.setShader(textureShader(style));
        grainPaint.setColorFilter(colorFilter(darkerColor));
        grainPaint.setAlpha(textureAlpha(style));
        canvas.drawRect(bounds, grainPaint);
        if (style == 5 || style == 6 || style == 8 || style == 9 || style == 11) {
            grainPaint.setShader(style == 11 ? textureShader(11) : textureShader((style + 3) % TEXTURE_COUNT));
            grainPaint.setColorFilter(colorFilter(lighten(stroke.color, style == 8 ? 1.75f : style == 11 ? 2.2f : 1.28f)));
            grainPaint.setAlpha(style == 8 ? 52 : style == 11 ? 188 : 36);
            canvas.drawRect(bounds, grainPaint);
        }
        grainPaint.setShader(null);
        grainPaint.setColorFilter(null);
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
            case 11: return 118;
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
        if (style == 11) {
            grain.setStyle(Paint.Style.STROKE);
            grain.setStrokeWidth(0.9f);
            for (int y = -12; y < size + 12; y += 16) {
                for (int x = -12; x < size + 12; x += 16) {
                    int alpha = 35 + random.nextInt(72);
                    grain.setAlpha(alpha);
                    Path diamond = new Path();
                    diamond.moveTo(x + 8f, y + 1f);
                    diamond.lineTo(x + 15f, y + 8f);
                    diamond.lineTo(x + 8f, y + 15f);
                    diamond.lineTo(x + 1f, y + 8f);
                    diamond.close();
                    canvas.drawPath(diamond, grain);
                }
            }
            grain.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 22; i++) {
                float x = random.nextFloat() * size;
                float y = random.nextFloat() * size;
                float radius = 0.8f + random.nextFloat() * 1.6f;
                grain.setAlpha(120 + random.nextInt(120));
                canvas.drawCircle(x, y, radius, grain);
                grain.setAlpha(175);
                grain.setStrokeWidth(0.7f);
                canvas.drawLine(x - radius * 2.2f, y, x + radius * 2.2f, y, grain);
                canvas.drawLine(x, y - radius * 2.2f, x, y + radius * 2.2f, grain);
            }
            return bitmap;
        }
        if (style == 1) {
            grain.setAlpha(42);
            grain.setStrokeWidth(0.42f);
            for (int p = 0; p < size; p += 9) {
                canvas.drawLine(p, 0, p, size, grain);
                canvas.drawLine(0, p, size, p, grain);
            }
            return bitmap;
        }
        int strandCount = new int[]{58, 0, 190, 125, 36, 45, 32, 74, 24, 52, 22, 0}[style];
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
        int r = Math.max(0, Math.min(255, Math.round(((color >>> 16) & 0xFF) * amount)));
        int g = Math.max(0, Math.min(255, Math.round(((color >>> 8) & 0xFF) * amount)));
        int b = Math.max(0, Math.min(255, Math.round((color & 0xFF) * amount)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        if (amount > 1.0f) {
            float t = amount - 1.0f;
            r = Math.min(255, Math.round(r + (255 - r) * Math.min(1.0f, t * 0.70f)));
            g = Math.min(255, Math.round(g + (255 - g) * Math.min(1.0f, t * 0.70f)));
            b = Math.min(255, Math.round(b + (255 - b) * Math.min(1.0f, t * 0.70f)));
        } else {
            r = Math.max(0, Math.round(r * amount));
            g = Math.max(0, Math.round(g * amount));
            b = Math.max(0, Math.round(b * amount));
        }
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
