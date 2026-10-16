package org.opennotes.app.util;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext;
import com.google.mlkit.vision.digitalink.recognition.WritingArea;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-device handwriting recognition using ML Kit's digital ink models. */
public final class HandwritingRecognitionEngine {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final class RecognitionResult {
        public final String text;
        public final String language;
        public final RectF bounds;
        public final float fontSize;
        public final float confidence;

        RecognitionResult(String text, String language, RectF bounds, float fontSize) {
            this.text = text;
            this.language = language;
            this.bounds = bounds;
            this.fontSize = fontSize;
            this.confidence = 1f; // ML Kit does not expose text confidence scores.
        }
    }

    public interface Callback {
        void onSuccess(RecognitionResult result);
        void onFailure(Exception error);
    }

    private HandwritingRecognitionEngine() { }

    /**
     * Recognize completed strokes asynchronously. If the language model is not installed,
     * it is downloaded first; until then the caller should keep the original ink intact.
     */
    public static void recognizeStrokes(Context context, List<Stroke> strokes,
                                        String preferredLanguage, Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        WORKER.execute(() -> {
            try {
                final RectF bounds = boundsOf(strokes);
                if (bounds == null || bounds.width() < 2f || bounds.height() < 2f) {
                    postFailure(callback, new IllegalArgumentException("Write a little more before converting."));
                    return;
                }
                String tag = languageTag(preferredLanguage);
                DigitalInkRecognitionModelIdentifier identifier =
                        DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
                if (identifier == null) {
                    postFailure(callback, new IllegalArgumentException("Handwriting recognition is not available for " + tag));
                    return;
                }
                DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(identifier).build();
                RemoteModelManager manager = RemoteModelManager.getInstance();
                manager.isModelDownloaded(model).addOnSuccessListener(isDownloaded -> {
                    if (isDownloaded) runRecognition(model, strokes, preferredLanguage, bounds, callback);
                    else manager.download(model, new DownloadConditions.Builder().build())
                            .addOnSuccessListener(ignored -> runRecognition(model, strokes, preferredLanguage, bounds, callback))
                            .addOnFailureListener(error -> postFailure(callback, error));
                }).addOnFailureListener(error -> postFailure(callback, error));
            } catch (Exception error) {
                postFailure(callback, error);
            }
        });
    }

    private static void runRecognition(DigitalInkRecognitionModel model, List<Stroke> strokes,
                                       String language, RectF bounds, Callback callback) {
        try {
            Ink.Builder inkBuilder = Ink.builder();
            long time = 0L;
            for (Stroke source : strokes) {
                if (source == null || source.points == null || source.points.isEmpty()) continue;
                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                for (DrawingPoint point : source.points) {
                    strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, time));
                    time += 8L;
                }
                inkBuilder.addStroke(strokeBuilder.build());
                time += 120L; // Preserve a realistic pause between pen lifts.
            }
            Ink ink = inkBuilder.build();
            DigitalInkRecognizer recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build());
            RecognitionContext recognitionContext = RecognitionContext.builder()
                    .setWritingArea(new WritingArea(Math.max(1f, bounds.width()), Math.max(1f, bounds.height())))
                    .build();
            recognizer.recognize(ink, recognitionContext)
                    .addOnSuccessListener(result -> {
                        try {
                            String text = result.getCandidates().isEmpty()
                                    ? "" : result.getCandidates().get(0).getText();
                            if (text.trim().isEmpty()) {
                                postFailure(callback, new IllegalArgumentException("Could not confidently read these strokes. The handwriting was kept."));
                            } else {
                                String lang = isLatin(language) ? "ENGLISH"
                                        : ("HINDI".equalsIgnoreCase(language) ? "HINDI" : "MARATHI");
                                float fontSize = Math.max(26f, Math.min(92f, bounds.height() * 0.85f));
                                MAIN.post(() -> callback.onSuccess(
                                        new RecognitionResult(text, lang, new RectF(bounds), fontSize)));
                            }
                        } finally {
                            recognizer.close();
                        }
                    })
                    .addOnFailureListener(error -> {
                        recognizer.close();
                        postFailure(callback, error);
                    });
        } catch (Exception error) {
            postFailure(callback, error);
        }
    }

    private static RectF boundsOf(List<Stroke> strokes) {
        RectF bounds = new RectF();
        boolean found = false;
        for (Stroke stroke : strokes) {
            if (stroke == null || stroke.points == null) continue;
            for (DrawingPoint point : stroke.points) {
                if (!found) {
                    bounds.set(point.x, point.y, point.x, point.y);
                    found = true;
                } else bounds.union(point.x, point.y);
            }
        }
        return found ? bounds : null;
    }

    private static String languageTag(String language) {
        if ("ENGLISH".equalsIgnoreCase(language)) return "en-US";
        if ("HINDI".equalsIgnoreCase(language)) return "hi-IN";
        return "mr-IN";
    }

    private static boolean isLatin(String language) {
        return "ENGLISH".equalsIgnoreCase(language);
    }

    private static void postFailure(Callback callback, Exception error) {
        MAIN.post(() -> callback.onFailure(error));
    }
}
