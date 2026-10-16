package org.opennotes.app.util;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import org.opennotes.app.db.AppSettings;

import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-device handwriting recognition using ML Kit's digital ink models. */
public final class HandwritingRecognitionEngine {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Task<Void>> MODEL_DOWNLOADS = new HashMap<>();
    private static final Map<String, List<PendingApproval>> WAITING_APPROVALS = new HashMap<>();

    private static final class PendingApproval {
        final Runnable approved;
        final Callback callback;

        PendingApproval(Runnable approved, Callback callback) {
            this.approved = approved;
            this.callback = callback;
        }
    }

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
                    else requestModelDownloadApproval(context, manager, model, tag,
                            strokes, preferredLanguage, bounds, callback);
                }).addOnFailureListener(error -> postFailure(callback, error));
            } catch (Exception error) {
                postFailure(callback, error);
            }
        });
    }

    private static void requestModelDownloadApproval(Context context, RemoteModelManager manager,
                                                     DigitalInkRecognitionModel model, String tag,
                                                     List<Stroke> strokes, String language,
                                                     RectF bounds, Callback callback) {
        AppSettings settings = AppSettings.getInstance(context);
        Runnable beginDownload = () -> downloadThenRecognize(
                manager, model, tag, strokes, language, bounds, callback);
        if (settings.hasAcceptedHandwritingModelDownload(tag)) {
            beginDownload.run();
            return;
        }

        synchronized (WAITING_APPROVALS) {
            List<PendingApproval> waiting = WAITING_APPROVALS.get(tag);
            if (waiting != null) {
                waiting.add(new PendingApproval(beginDownload, callback));
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(new PendingApproval(beginDownload, callback));
            WAITING_APPROVALS.put(tag, waiting);
        }

        MAIN.post(() -> {
            try {
                new MaterialAlertDialogBuilder(context)
                        .setTitle("Download handwriting model?")
                        .setMessage("OpenNotes needs to download the " + languageName(language)
                                + " handwriting model (about 20 MB) for recognition. Android stores it in the app's private storage; your handwriting is not uploaded. Allow this model download over the internet?")
                        .setNegativeButton("Not now", (dialog, which) -> finishApproval(
                                tag, false, settings, "Model download was declined."))
                        .setPositiveButton("Allow download", (dialog, which) -> finishApproval(
                                tag, true, settings, null))
                        .setOnCancelListener(dialog -> finishApproval(
                                tag, false, settings, "Model download was cancelled."))
                        .show();
            } catch (Exception error) {
                finishApproval(tag, false, settings, error.getMessage());
            }
        });
    }

    private static void finishApproval(String tag, boolean approved, AppSettings settings, String reason) {
        final List<PendingApproval> waiting;
        synchronized (WAITING_APPROVALS) {
            waiting = WAITING_APPROVALS.remove(tag);
        }
        if (waiting == null) return;
        if (approved) settings.setAcceptedHandwritingModelDownload(tag, true);
        for (PendingApproval request : waiting) {
            if (approved) request.approved.run();
            else postFailure(request.callback, new IllegalStateException(reason));
        }
    }

    private static String languageName(String language) {
        if ("ENGLISH".equalsIgnoreCase(language)) return "English";
        if ("HINDI".equalsIgnoreCase(language)) return "Hindi";
        return "Marathi";
    }

    private static void downloadThenRecognize(RemoteModelManager manager,
                                             DigitalInkRecognitionModel model,
                                             String tag, List<Stroke> strokes, String language,
                                             RectF bounds, Callback callback) {
        final Task<Void> downloadTask;
        synchronized (MODEL_DOWNLOADS) {
            Task<Void> existing = MODEL_DOWNLOADS.get(tag);
            if (existing != null) {
                downloadTask = existing;
            } else {
                downloadTask = manager.download(model, new DownloadConditions.Builder().build());
                MODEL_DOWNLOADS.put(tag, downloadTask);
            }
        }
        downloadTask.addOnSuccessListener(ignored -> {
            synchronized (MODEL_DOWNLOADS) {
                if (MODEL_DOWNLOADS.get(tag) == downloadTask) MODEL_DOWNLOADS.remove(tag);
            }
            runRecognition(model, strokes, language, bounds, callback);
        }).addOnFailureListener(error -> {
            synchronized (MODEL_DOWNLOADS) {
                if (MODEL_DOWNLOADS.get(tag) == downloadTask) MODEL_DOWNLOADS.remove(tag);
            }
            postFailure(callback, error);
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
                    // ML Kit's writing area starts at (0, 0). Canvas strokes use page
                    // coordinates, so normalize them to the selected ink bounds first.
                    strokeBuilder.addPoint(Ink.Point.create(
                            point.x - bounds.left, point.y - bounds.top, time));
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
