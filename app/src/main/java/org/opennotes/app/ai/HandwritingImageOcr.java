package org.opennotes.app.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.List;

/** Image-OCR candidate source for ink. Digital Ink remains the primary handwriting recognizer. */
public final class HandwritingImageOcr {
    private static final float MAX_SIDE = 2048f;
    private static final float PADDING = 24f;

    public interface Callback { void onCandidate(String text); }

    private HandwritingImageOcr() { }

    public static void recognize(Context context, List<Stroke> strokes, String language, Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) {
            if (callback != null) callback.onCandidate("");
            return;
        }
        Bitmap bitmap = render(strokes);
        if (bitmap == null) {
            callback.onCandidate("");
            return;
        }
        recognizeOwnedBitmap(context, bitmap, language, callback);
    }

    /** OCR an existing handwriting sample; used by the screenshot regression benchmark. */
    public static void recognizeBitmap(Context context, Bitmap source, String language, Callback callback) {
        if (context == null || source == null || callback == null) {
            if (callback != null) callback.onCandidate("");
            return;
        }
        Bitmap bitmap = source.copy(Bitmap.Config.ARGB_8888, true);
        if (bitmap == null) { callback.onCandidate(""); return; }
        int corner = bitmap.getPixel(0, 0);
        if ((Color.red(corner) + Color.green(corner) + Color.blue(corner)) / 3 < 128) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    int color = bitmap.getPixel(x, y);
                    bitmap.setPixel(x, y, Color.rgb(255 - Color.red(color),
                            255 - Color.green(color), 255 - Color.blue(color)));
                }
            }
        }
        recognizeOwnedBitmap(context, bitmap, language, callback);
    }

    private static void recognizeOwnedBitmap(Context context, Bitmap bitmap, String language,
                                             Callback callback) {
        final TextRecognizer recognizer;
        boolean devanagari = "HINDI".equalsIgnoreCase(language)
                || "MARATHI".equalsIgnoreCase(language);
        try {
            recognizer = devanagari
                    ? TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build())
                    : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(result -> callback.onCandidate(result.getText()))
                    .addOnFailureListener(error -> callback.onCandidate(""))
                    .addOnCompleteListener(task -> {
                        recognizer.close();
                        bitmap.recycle();
                    });
        } catch (RuntimeException error) {
            bitmap.recycle();
            callback.onCandidate("");
        }
    }

    static Bitmap render(List<Stroke> strokes) {
        RectF bounds = InkNormalizer.getExactBounds(strokes);
        if (bounds == null || bounds.isEmpty()) return null;
        float scale = Math.min(3f, MAX_SIDE / Math.max(bounds.width(), bounds.height()));
        scale = Math.max(0.2f, scale);
        int width = Math.max(48, Math.min((int) MAX_SIDE, (int) (bounds.width() * scale + 2 * PADDING)));
        int height = Math.max(48, Math.min((int) MAX_SIDE, (int) (bounds.height() * scale + 2 * PADDING)));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        for (Stroke stroke : strokes) {
            if (stroke == null) continue;
            List<DrawingPoint> points = stroke.getRecognitionPoints();
            if (points == null || points.isEmpty()) points = stroke.points;
            if (points == null || points.isEmpty()) continue;
            Path path = new Path();
            boolean started = false;
            for (DrawingPoint point : points) {
                if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y)) continue;
                float x = (point.x - bounds.left) * scale + PADDING;
                float y = (point.y - bounds.top) * scale + PADDING;
                if (!started) { path.moveTo(x, y); started = true; }
                else path.lineTo(x, y);
            }
            if (!started) continue;
            paint.setStrokeWidth(Math.max(2.2f, Math.max(1f, stroke.width) * scale));
            if (points.size() == 1) {
                DrawingPoint point = points.get(0);
                canvas.drawCircle((point.x - bounds.left) * scale + PADDING,
                        (point.y - bounds.top) * scale + PADDING,
                        paint.getStrokeWidth() * 0.5f, paint);
            } else {
                canvas.drawPath(path, paint);
            }
        }
        return bitmap;
    }
}
