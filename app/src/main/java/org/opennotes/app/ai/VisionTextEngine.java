package org.opennotes.app.ai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device Vision OCR engine utilizing Google ML Kit's Vision Text Recognition.
 * 
 * Runs Latin and Devanagari OCR models directly from rasterized ink bitmaps to provide
 * ground-truth visual shape validation alongside Digital Ink trajectory recognition.
 */
public class VisionTextEngine {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);

    private static TextRecognizer latinRecognizer;
    private static TextRecognizer devanagariRecognizer;

    public interface VisionCallback {
        void onResult(String recognizedText, float confidence);
        void onError(Exception e);
    }

    private static synchronized TextRecognizer getLatinRecognizer() {
        if (latinRecognizer == null) {
            latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        return latinRecognizer;
    }

    private static synchronized TextRecognizer getDevanagariRecognizer() {
        if (devanagariRecognizer == null) {
            try {
                devanagariRecognizer = TextRecognition.getClient(
                        new DevanagariTextRecognizerOptions.Builder().build());
            } catch (Exception e) {
                // Fallback to Latin if Devanagari options cannot instantiate
                devanagariRecognizer = getLatinRecognizer();
            }
        }
        return devanagariRecognizer;
    }

    /**
     * Renders vector handwriting strokes into a high-contrast bitmap suitable for OCR.
     */
    public static Bitmap renderStrokesToBitmap(List<Stroke> strokes, int padding) {
        if (strokes == null || strokes.isEmpty()) return null;

        RectF bounds = InkNormalizer.getBounds(strokes);
        if (bounds == null || bounds.width() <= 1f || bounds.height() <= 1f) return null;

        int pad = Math.max(24, padding);
        float width = bounds.width() + pad * 2f;
        float height = bounds.height() + pad * 2f;

        // Upscale small handwriting to ensure OCR filters have sufficient pixel resolution
        float minDimension = 140f;
        float scale = 1.0f;
        if (bounds.height() < minDimension || bounds.width() < minDimension) {
            scale = Math.min(3.5f, minDimension / Math.min(bounds.width(), bounds.height()));
        }

        int bmpW = Math.max(64, Math.round(width * scale));
        int bmpH = Math.max(64, Math.round(height * scale));

        // Limit maximum size to prevent memory pressure
        if (bmpW > 2048 || bmpH > 2048) {
            float maxDim = Math.max(bmpW, bmpH);
            float downscale = 2048f / maxDim;
            bmpW = Math.round(bmpW * downscale);
            bmpH = Math.round(bmpH * downscale);
            scale *= downscale;
        }

        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            return null;
        }

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE); // High-contrast clean white background

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        float strokeWidth = Math.max(3.5f, Math.min(8f, 5.0f * scale));
        paint.setStrokeWidth(strokeWidth);

        float offsetX = bounds.left - (pad * scale);
        float offsetY = bounds.top - (pad * scale);

        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null || pts.isEmpty()) continue;

            if (pts.size() == 1) {
                DrawingPoint p = pts.get(0);
                float x = (p.x - bounds.left) * scale + (pad * scale);
                float y = (p.y - bounds.top) * scale + (pad * scale);
                canvas.drawCircle(x, y, strokeWidth / 2f, paint);
                continue;
            }

            android.graphics.Path path = new android.graphics.Path();
            DrawingPoint first = pts.get(0);
            path.moveTo((first.x - bounds.left) * scale + (pad * scale),
                        (first.y - bounds.top) * scale + (pad * scale));

            for (int i = 1; i < pts.size(); i++) {
                DrawingPoint p = pts.get(i);
                path.lineTo((p.x - bounds.left) * scale + (pad * scale),
                            (p.y - bounds.top) * scale + (pad * scale));
            }
            canvas.drawPath(path, paint);
        }

        return bitmap;
    }

    /**
     * Recognizes text from strokes using ML Kit Vision OCR with Latin & Devanagari dual-engine evaluation.
     */
    public static void recognizeStrokesVision(List<Stroke> strokes, String preferredLanguage, VisionCallback callback) {
        if (callback == null) return;
        if (strokes == null || strokes.isEmpty()) {
            MAIN.post(() -> callback.onResult("", 0f));
            return;
        }

        WORKER.execute(() -> {
            Bitmap bitmap = renderStrokesToBitmap(strokes, 28);
            if (bitmap == null) {
                MAIN.post(() -> callback.onResult("", 0f));
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            boolean isIndic = "MARATHI".equalsIgnoreCase(preferredLanguage) || "HINDI".equalsIgnoreCase(preferredLanguage);

            TextRecognizer primaryOcr = isIndic ? getDevanagariRecognizer() : getLatinRecognizer();
            TextRecognizer secondaryOcr = isIndic ? getLatinRecognizer() : null;

            primaryOcr.process(image)
                    .addOnSuccessListener(visionText -> {
                        String primaryText = visionText != null ? visionText.getText().trim() : "";
                        if (secondaryOcr != null) {
                            secondaryOcr.process(image)
                                    .addOnSuccessListener(secVisionText -> {
                                        String secText = secVisionText != null ? secVisionText.getText().trim() : "";
                                        String best = selectBestOcrCandidate(primaryText, secText, isIndic);
                                        bitmap.recycle();
                                        MAIN.post(() -> callback.onResult(best, 0.90f));
                                    })
                                    .addOnFailureListener(e -> {
                                        bitmap.recycle();
                                        MAIN.post(() -> callback.onResult(primaryText, 0.85f));
                                    });
                        } else {
                            bitmap.recycle();
                            MAIN.post(() -> callback.onResult(primaryText, 0.90f));
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (secondaryOcr != null) {
                            secondaryOcr.process(image)
                                    .addOnSuccessListener(secVisionText -> {
                                        String secText = secVisionText != null ? secVisionText.getText().trim() : "";
                                        bitmap.recycle();
                                        MAIN.post(() -> callback.onResult(secText, 0.80f));
                                    })
                                    .addOnFailureListener(err -> {
                                        bitmap.recycle();
                                        MAIN.post(() -> callback.onError(err));
                                    });
                        } else {
                            bitmap.recycle();
                            MAIN.post(() -> callback.onError(e));
                        }
                    });
        });
    }

    /**
     * Reconciles primary (Indic) and secondary (Latin/Digits) OCR candidates.
     * When handwriting contains digits (like 1949) or Latin letters, the Latin model output is preferred.
     */
    private static String selectBestOcrCandidate(String indicCandidate, String latinCandidate, boolean preferIndic) {
        if (latinCandidate == null || latinCandidate.isEmpty()) return indicCandidate != null ? indicCandidate : "";
        if (indicCandidate == null || indicCandidate.isEmpty()) return latinCandidate;

        // If Latin candidate is purely digits/symbols (e.g. "1949", "315", "2024")
        if (latinCandidate.matches("^[0-9\\s.,\\-+:/#]+$")) {
            return latinCandidate;
        }

        // If Indic candidate has Devanagari characters, prefer Indic
        boolean indicHasDevanagari = false;
        for (int i = 0; i < indicCandidate.length(); i++) {
            char c = indicCandidate.charAt(i);
            if (c >= '\u0900' && c <= '\u097F') {
                indicHasDevanagari = true;
                break;
            }
        }

        if (indicHasDevanagari) {
            return indicCandidate;
        }

        return latinCandidate.length() >= indicCandidate.length() ? latinCandidate : indicCandidate;
    }
}
