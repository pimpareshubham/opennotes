package org.opennotes.app.util;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-device handwriting recognition using ML Kit's digital ink models. */
public final class HandwritingRecognitionEngine {
    private static final String TAG = "HandwritingRecognition";
    private static final float MIN_WRITING_AREA_AXIS = 2f;
    private static final float DEGENERATE_AXIS_RATIO = 0.08f;
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
        public final List<String> alternatives;

        RecognitionResult(String text, String language, RectF bounds, float fontSize,
                          List<String> alternatives) {
            this.text = text;
            this.language = language;
            this.bounds = bounds;
            this.fontSize = fontSize;
            this.alternatives = Collections.unmodifiableList(new ArrayList<>(alternatives));
        }
    }

    public static class ModelDownloadCancellationException extends Exception {
        public ModelDownloadCancellationException(String message) {
            super(message);
        }
    }

    public interface Callback {
        void onSuccess(RecognitionResult result);
        void onFailure(Exception error);
    }

    public interface ModelDownloadCallback {
        void onComplete(Exception error);
    }

    public interface ModelsStatusCallback {
        void onStatus(boolean englishReady, boolean marathiReady);
    }

    private HandwritingRecognitionEngine() { }

    /** Check if offline models are already installed on the device. */
    public static void checkModelsDownloaded(Context context, ModelsStatusCallback callback) {
        if (callback == null) return;
        WORKER.execute(() -> {
            try {
                RemoteModelManager manager = RemoteModelManager.getInstance();
                String enTag = languageTag("ENGLISH");
                String mrTag = languageTag("MARATHI");
                DigitalInkRecognitionModelIdentifier idEn =
                        DigitalInkRecognitionModelIdentifier.fromLanguageTag(enTag);
                DigitalInkRecognitionModelIdentifier idMr =
                        DigitalInkRecognitionModelIdentifier.fromLanguageTag(mrTag);
                if (idEn == null || idMr == null) {
                    MAIN.post(() -> callback.onStatus(false, false));
                    return;
                }
                DigitalInkRecognitionModel modelEn = DigitalInkRecognitionModel.builder(idEn).build();
                DigitalInkRecognitionModel modelMr = DigitalInkRecognitionModel.builder(idMr).build();

                manager.isModelDownloaded(modelEn).addOnCompleteListener(taskEn -> {
                    boolean en = taskEn.isSuccessful() && Boolean.TRUE.equals(taskEn.getResult());
                    manager.isModelDownloaded(modelMr).addOnCompleteListener(taskMr -> {
                        boolean mr = taskMr.isSuccessful() && Boolean.TRUE.equals(taskMr.getResult());
                        MAIN.post(() -> callback.onStatus(en, mr));
                    });
                });
            } catch (Exception e) {
                MAIN.post(() -> callback.onStatus(false, false));
            }
        });
    }

    /** Pre-download a language model from an explicit setup flow before recognition is used. */
    public static void downloadLanguageModel(Context context, String preferredLanguage,
                                            ModelDownloadCallback callback) {
        if (context == null || callback == null) return;
        WORKER.execute(() -> {
            try {
                String tag = languageTag(preferredLanguage);
                DigitalInkRecognitionModelIdentifier identifier =
                        DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
                if (identifier == null) {
                    postModelDownloadResult(callback, new IllegalArgumentException(
                            "Handwriting recognition is not available for " + tag));
                    return;
                }
                DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(identifier).build();
                RemoteModelManager manager = RemoteModelManager.getInstance();
                manager.isModelDownloaded(model)
                        .addOnSuccessListener(isDownloaded -> {
                            if (isDownloaded) postModelDownloadResult(callback, null);
                            else downloadModelForSetup(manager, model, tag, callback);
                        })
                        .addOnFailureListener(error -> postModelDownloadResult(callback, error));
            } catch (Exception error) {
                postModelDownloadResult(callback, error);
            }
        });
    }

    private static void downloadModelForSetup(RemoteModelManager manager,
                                              DigitalInkRecognitionModel model,
                                              String tag, ModelDownloadCallback callback) {
        try {
            final Task<Void> task;
            synchronized (MODEL_DOWNLOADS) {
                Task<Void> existing = MODEL_DOWNLOADS.get(tag);
                if (existing != null) task = existing;
                else {
                    task = manager.download(model, new DownloadConditions.Builder().build());
                    MODEL_DOWNLOADS.put(tag, task);
                }
            }
            task.addOnSuccessListener(ignored -> manager.isModelDownloaded(model)
                    .addOnSuccessListener(isDownloaded -> {
                        removeCompletedDownload(tag, task);
                        postModelDownloadResult(callback, isDownloaded ? null : new IllegalStateException(
                                "The " + languageName(tag) + " handwriting model download finished, but the model is not installed."));
                    })
                    .addOnFailureListener(error -> {
                        removeCompletedDownload(tag, task);
                        postModelDownloadResult(callback, error);
                    }))
                    .addOnFailureListener(error -> {
                        removeCompletedDownload(tag, task);
                        postModelDownloadResult(callback, error);
                    });
        } catch (Exception error) {
            postModelDownloadResult(callback, error);
        }
    }

    private static void postModelDownloadResult(ModelDownloadCallback callback, Exception error) {
        MAIN.post(() -> callback.onComplete(error));
    }

    /**
     * Recognize completed strokes asynchronously with automatic multi-angle deskewing,
     * lazy-handwriting cleanup (hook trimming & arc-length resampling), and multi-pass fallbacks.
     */
    public static void recognizeStrokes(Context context, List<Stroke> strokes,
                                        String preferredLanguage, Callback callback) {
        recognizeStrokes(context, strokes, preferredLanguage, "", callback);
    }

    public static void recognizeStrokes(Context context, List<Stroke> strokes,
                                        String preferredLanguage, String preContext,
                                        Callback callback) {
        recognizeMultiHypothesis(context, strokes, preferredLanguage, preContext, false, callback);
    }

    /**
     * Recognize ink for a review flow and include candidates from multi-orientation and stabilized
     * stroke variations.
     */
    public static void recognizeStrokesWithFilteredSuggestions(Context context,
                                                               List<Stroke> strokes,
                                                               String preferredLanguage,
                                                               String preContext,
                                                               Callback callback) {
        recognizeMultiHypothesis(context, strokes, preferredLanguage, preContext, true, callback);
    }

    private static void recognizeMultiHypothesis(Context context,
                                                 List<Stroke> strokes,
                                                 String preferredLanguage,
                                                 String preContext,
                                                 boolean includeReviewSuggestions,
                                                 Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        final String contextText = preContext == null ? "" : preContext;
        final float writingAngle = estimateWritingAngleDegrees(strokes);
        final boolean isSlanted = Math.abs(writingAngle) >= 4.0f;

        // Pass 1: Primary recognition.
        // If the handwriting is distinctly slanted (e.g. tilted uphill, downhill, or vertical margin),
        // deskewing to horizontal baseline dramatically boosts recognition accuracy.
        List<Stroke> primaryStrokes = isSlanted
                ? transformedStrokeCopies(strokes, writingAngle, true, false)
                : strokes;
        if (primaryStrokes.isEmpty()) primaryStrokes = strokes;

        final List<Stroke> effectivePrimary = primaryStrokes;
        executeSingleRecognitionPass(context, effectivePrimary, preferredLanguage, contextText, new Callback() {
            @Override
            public void onSuccess(RecognitionResult primaryResult) {
                if (includeReviewSuggestions || primaryResult.alternatives.size() <= 1) {
                    tryAlternativeHypothesis(context, strokes, preferredLanguage, contextText,
                            primaryResult, isSlanted, writingAngle, callback);
                } else {
                    callback.onSuccess(primaryResult);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (isModelDownloadCancellation(error)) {
                    callback.onFailure(error);
                    return;
                }
                tryAlternativeHypothesis(context, strokes, preferredLanguage, contextText,
                        null, isSlanted, writingAngle, callback);
            }
        });
    }

    private static void tryAlternativeHypothesis(Context context,
                                                 List<Stroke> originalStrokes,
                                                 String language,
                                                 String preContext,
                                                 RecognitionResult primaryResult,
                                                 boolean primaryWasDeskewed,
                                                 float writingAngle,
                                                 Callback callback) {
        List<List<Stroke>> candidatesToTry = new ArrayList<>();

        if (primaryWasDeskewed) {
            candidatesToTry.add(originalStrokes);
        }

        // Test cleaned + resampled (ideal for lazy/rushed handwriting with hooks or uneven sampling)
        candidatesToTry.add(transformedStrokeCopies(originalStrokes,
                primaryWasDeskewed ? writingAngle : 0f, true, true));

        // Test common tilt variations: +15°, -15°, +30°, -30°, 90°
        if (!primaryWasDeskewed) {
            candidatesToTry.add(transformedStrokeCopies(originalStrokes, 15f, true, false));
            candidatesToTry.add(transformedStrokeCopies(originalStrokes, -15f, true, false));
            RectF bounds = boundsOf(originalStrokes);
            if (bounds != null && bounds.height() > bounds.width() * 1.5f) {
                candidatesToTry.add(transformedStrokeCopies(originalStrokes, 90f, true, false));
            }
        }

        runWaterfall(context, candidatesToTry, 0, language, preContext, primaryResult, callback);
    }

    private static void runWaterfall(Context context,
                                     List<List<Stroke>> queue,
                                     int index,
                                     String language,
                                     String preContext,
                                     RecognitionResult accumulatedResult,
                                     Callback callback) {
        if (index >= queue.size()) {
            if (accumulatedResult != null) {
                callback.onSuccess(accumulatedResult);
            } else {
                callback.onFailure(new IllegalArgumentException(
                        "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
            }
            return;
        }

        List<Stroke> candidateStrokes = queue.get(index);
        if (candidateStrokes == null || candidateStrokes.isEmpty()) {
            runWaterfall(context, queue, index + 1, language, preContext, accumulatedResult, callback);
            return;
        }

        executeSingleRecognitionPass(context, candidateStrokes, language, preContext, new Callback() {
            @Override
            public void onSuccess(RecognitionResult candidateResult) {
                RecognitionResult merged = mergeCandidates(accumulatedResult, candidateResult);
                if (merged.alternatives.size() >= 3) {
                    callback.onSuccess(merged);
                } else {
                    runWaterfall(context, queue, index + 1, language, preContext, merged, callback);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (isModelDownloadCancellation(error)) {
                    callback.onFailure(error);
                    return;
                }
                runWaterfall(context, queue, index + 1, language, preContext, accumulatedResult, callback);
            }
        });
    }

    private static void executeSingleRecognitionPass(Context context,
                                                     List<Stroke> strokes,
                                                     String preferredLanguage,
                                                     String preContext,
                                                     Callback callback) {
        final RectF bounds = boundsOf(strokes);
        if (bounds == null) {
            postFailure(callback, new IllegalArgumentException(
                    "No usable handwriting points were found. Your ink was kept."));
            return;
        }
        try {
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
                if (isDownloaded) {
                    runRecognition(model, strokes, preferredLanguage, preContext, bounds, callback);
                } else {
                    requestModelDownloadApproval(context, manager, model, tag,
                            strokes, preferredLanguage, preContext, bounds, callback);
                }
            }).addOnFailureListener(error -> postFailure(callback, error));
        } catch (Exception error) {
            postFailure(callback, error);
        }
    }

    public static boolean isEmptyRecognitionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                String msg = cause.getMessage().toLowerCase();
                if (msg.contains("no handwriting text was recognized")
                        || msg.contains("no usable handwriting points")
                        || msg.contains("returned no readable text")
                        || msg.contains("empty line")
                        || msg.contains("returned an empty line")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static RecognitionResult mergeCandidates(RecognitionResult primary,
                                                      RecognitionResult alternate) {
        if (primary == null) return alternate;
        if (alternate == null) return primary;
        List<String> merged = new ArrayList<>(primary.alternatives);
        for (String candidate : alternate.alternatives) {
            if (candidate != null && !candidate.trim().isEmpty() && !merged.contains(candidate)) {
                merged.add(candidate);
            }
        }
        return new RecognitionResult(primary.text, primary.language, primary.bounds,
                primary.fontSize, merged);
    }

    private static List<Stroke> transformedStrokeCopies(List<Stroke> strokes,
                                                        float rotationAngleDegrees,
                                                        boolean trimHooks,
                                                        boolean resamplePoints) {
        List<Stroke> result = new ArrayList<>(strokes.size());
        RectF bounds = boundsOf(strokes);
        if (bounds == null) return result;
        double rad = Math.toRadians(rotationAngleDegrees);
        float cosine = (float) Math.cos(rad);
        float sine = (float) Math.sin(rad);
        float cx = bounds.centerX();
        float cy = bounds.centerY();

        for (Stroke source : strokes) {
            if (source == null) continue;
            List<DrawingPoint> raw = source.getRecognitionPoints();
            if (raw == null || raw.isEmpty()) raw = source.points;
            if (raw == null || raw.isEmpty()) continue;

            List<DrawingPoint> cleaned = new ArrayList<>(raw.size());
            for (DrawingPoint p : raw) {
                if (p != null && isFinite(p.x) && isFinite(p.y)) {
                    cleaned.add(new DrawingPoint(p.x, p.y, p.pressure, p.timeMillis));
                }
            }
            if (trimHooks && cleaned.size() > 4) {
                DrawingPoint p0 = cleaned.get(0);
                DrawingPoint p1 = cleaned.get(1);
                DrawingPoint p2 = cleaned.get(2);
                float d01x = p1.x - p0.x, d01y = p1.y - p0.y;
                float d12x = p2.x - p1.x, d12y = p2.y - p1.y;
                float dotStart = d01x * d12x + d01y * d12y;
                if (dotStart < -0.4f * (Math.hypot(d01x, d01y) * Math.hypot(d12x, d12y))) {
                    cleaned.remove(0);
                }
                int last = cleaned.size() - 1;
                DrawingPoint q2 = cleaned.get(last);
                DrawingPoint q1 = cleaned.get(last - 1);
                DrawingPoint q0 = cleaned.get(last - 2);
                float e01x = q1.x - q0.x, e01y = q1.y - q0.y;
                float e12x = q2.x - q1.x, e12y = q2.y - q1.y;
                float dotEnd = e01x * e12x + e01y * e12y;
                if (dotEnd < -0.4f * (Math.hypot(e01x, e01y) * Math.hypot(e12x, e12y))) {
                    cleaned.remove(last);
                }
            }

            List<DrawingPoint> processed = cleaned;
            if (resamplePoints && cleaned.size() >= 2) {
                processed = resampleStroke(cleaned, 3.0f);
            }

            Stroke copy = new Stroke(source.color, source.width, source.tool);
            for (DrawingPoint pt : processed) {
                float dx = pt.x - cx;
                float dy = pt.y - cy;
                float rotX = dx * cosine + dy * sine + cx;
                float rotY = -dx * sine + dy * cosine + cy;
                copy.addPoint(rotX, rotY, pt.pressure, pt.timeMillis);
                copy.addRecognitionPoint(rotX, rotY, pt.pressure, pt.timeMillis);
            }
            if (!copy.getRecognitionPoints().isEmpty()) {
                result.add(copy);
            }
        }
        return result;
    }

    private static List<DrawingPoint> resampleStroke(List<DrawingPoint> points, float step) {
        List<DrawingPoint> resampled = new ArrayList<>();
        if (points.isEmpty()) return resampled;
        resampled.add(points.get(0));
        float accumulated = 0f;
        for (int i = 0; i < points.size() - 1; i++) {
            DrawingPoint p1 = points.get(i);
            DrawingPoint p2 = points.get(i + 1);
            float dist = (float) Math.hypot(p2.x - p1.x, p2.y - p1.y);
            if (dist <= 0.001f) continue;
            float consumed = 0f;
            while (consumed + (step - accumulated) <= dist) {
                float t = (consumed + (step - accumulated)) / dist;
                float x = p1.x + t * (p2.x - p1.x);
                float y = p1.y + t * (p2.y - p1.y);
                float p = p1.pressure + t * (p2.pressure - p1.pressure);
                long time = (long) (p1.timeMillis + t * (p2.timeMillis - p1.timeMillis));
                resampled.add(new DrawingPoint(x, y, p, time));
                consumed += (step - accumulated);
                accumulated = 0f;
            }
            accumulated += (dist - consumed);
        }
        DrawingPoint last = points.get(points.size() - 1);
        if (resampled.size() < 2 || Math.hypot(last.x - resampled.get(resampled.size() - 1).x,
                last.y - resampled.get(resampled.size() - 1).y) > step * 0.5f) {
            resampled.add(last);
        }
        return resampled;
    }

    /**
     * Estimate the dominant writing baseline angle (in degrees, from -180 to +180) across all points
     * using Principal Component Analysis and temporal writing progression.
     */
    public static float estimateWritingAngleDegrees(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return 0f;
        List<DrawingPoint> allPoints = new ArrayList<>();
        DrawingPoint firstPoint = null;
        DrawingPoint lastPoint = null;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null) continue;
            for (DrawingPoint p : pts) {
                if (p == null || !isFinite(p.x) || !isFinite(p.y)) continue;
                allPoints.add(p);
                if (p.timeMillis > 0) {
                    if (p.timeMillis < minTime) {
                        minTime = p.timeMillis;
                        firstPoint = p;
                    }
                    if (p.timeMillis > maxTime) {
                        maxTime = p.timeMillis;
                        lastPoint = p;
                    }
                }
            }
        }
        if (allPoints.size() < 2) return 0f;
        if (firstPoint == null) firstPoint = allPoints.get(0);
        if (lastPoint == null) lastPoint = allPoints.get(allPoints.size() - 1);

        double meanX = 0d;
        double meanY = 0d;
        for (DrawingPoint p : allPoints) {
            meanX += p.x;
            meanY += p.y;
        }
        meanX /= allPoints.size();
        meanY /= allPoints.size();

        double varX = 0d;
        double varY = 0d;
        double covXY = 0d;
        for (DrawingPoint p : allPoints) {
            double dx = p.x - meanX;
            double dy = p.y - meanY;
            varX += dx * dx;
            varY += dy * dy;
            covXY += dx * dy;
        }
        if (varX + varY < 4.0) return 0f;

        double angleRad = 0.5d * Math.atan2(2d * covXY, varX - varY);
        float degrees = (float) Math.toDegrees(angleRad);

        // Check if temporal writing progression went forward or backward along this axis
        double dirX = Math.cos(angleRad);
        double dirY = Math.sin(angleRad);
        double progX = lastPoint.x - firstPoint.x;
        double progY = lastPoint.y - firstPoint.y;
        double dot = progX * dirX + progY * dirY;
        if (dot < 0) {
            degrees = (degrees > 0) ? degrees - 180f : degrees + 180f;
        }

        RectF bounds = boundsOf(strokes);
        if (bounds != null && bounds.height() > bounds.width() * 2.2f && Math.abs(degrees) < 30f) {
            degrees = 90f;
        }

        if (Math.abs(degrees) < 3.5f) return 0f;
        return degrees;
    }

    private static void requestModelDownloadApproval(Context context, RemoteModelManager manager,
                                                     DigitalInkRecognitionModel model, String tag,
                                                     List<Stroke> strokes, String language,
                                                     String preContext,
                                                     RectF bounds, Callback callback) {
        AppSettings settings = AppSettings.getInstance(context);
        settings.setAcceptedHandwritingModelDownload(tag, true);
        downloadThenRecognize(manager, model, tag, strokes, language, preContext, bounds, callback);
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
            else postFailure(request.callback, new ModelDownloadCancellationException(reason));
        }
    }

    private static String languageName(String language) {
        if ("ENGLISH".equalsIgnoreCase(language) || "en-IN".equalsIgnoreCase(language)) return "English";
        if ("HINDI".equalsIgnoreCase(language) || "hi".equalsIgnoreCase(language)) return "Hindi";
        return "Marathi";
    }

    public static boolean isModelDownloadCancellation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelDownloadCancellationException) return true;
            if (cause.getMessage() != null) {
                String msg = cause.getMessage().toLowerCase();
                if (msg.contains("download was cancelled") || msg.contains("download was canceled")
                        || msg.contains("download was declined") || msg.contains("download cancelled")
                        || msg.contains("download canceled")) {
                    return true;
                }
            }
        }
        return false;
    }


    private static void downloadThenRecognize(RemoteModelManager manager,
                                             DigitalInkRecognitionModel model,
                                             String tag, List<Stroke> strokes, String language,
                                             String preContext,
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
            // ML Kit persists the downloaded model in app-private storage. Confirm
            // that it is available there before constructing a recognizer, so a
            // completed network task is never mistaken for an installed model.
            manager.isModelDownloaded(model)
                    .addOnSuccessListener(isDownloaded -> {
                        removeCompletedDownload(tag, downloadTask);
                        if (isDownloaded) {
                            runRecognition(model, strokes, language, preContext, bounds, callback);
                        } else {
                            postFailure(callback, new IllegalStateException(
                                    "The language model download finished but the model is not installed yet. Please try again."));
                        }
                    })
                    .addOnFailureListener(error -> {
                        removeCompletedDownload(tag, downloadTask);
                        postFailure(callback, error);
                    });
        }).addOnFailureListener(error -> {
            removeCompletedDownload(tag, downloadTask);
            postFailure(callback, error);
        });
    }

    private static void removeCompletedDownload(String tag, Task<Void> downloadTask) {
        synchronized (MODEL_DOWNLOADS) {
            if (MODEL_DOWNLOADS.get(tag) == downloadTask) MODEL_DOWNLOADS.remove(tag);
        }
    }

    private static void runRecognition(DigitalInkRecognitionModel model, List<Stroke> strokes,
                                       String language, String preContext,
                                       RectF bounds, Callback callback) {
        // ML Kit Task listeners normally run on the main thread. Building Ink
        // walks every captured point and can take long enough to hitch the
        // editor for page-wide conversion, so always prepare and submit it on
        // the dedicated recognition worker.
        WORKER.execute(() -> buildInkAndRecognize(
                model, strokes, language, preContext, bounds, callback));
    }

    private static void buildInkAndRecognize(DigitalInkRecognitionModel model,
                                             List<Stroke> strokes, String language,
                                             String preContext, RectF bounds,
                                             Callback callback) {
        try {
            // A narrow but valid glyph can have a zero-width or zero-height
            // geometric bound (for example, a single vertical or horizontal
            // stroke). ML Kit still needs a non-degenerate writing area. Expand
            // only those axes and center the original points within that area.
            RectF writingArea = usableWritingArea(bounds);
            Ink.Builder inkBuilder = Ink.builder();
            long lastInkTime = -1L;
            int addedStrokes = 0;
            for (Stroke source : strokes) {
                if (source == null) continue;
                List<DrawingPoint> inputPoints = source.getRecognitionPoints();
                if (inputPoints == null || inputPoints.isEmpty()) continue;
                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                int addedPoints = 0;
                for (DrawingPoint point : inputPoints) {
                    if (point == null || !isFinite(point.x) || !isFinite(point.y)) continue;
                    // ML Kit's writing area starts at (0, 0). Canvas strokes use page
                    // coordinates, so normalize them to the selected ink bounds first.
                    // Retain actual event timing when captured. Legacy notes have
                    // no timestamps, so give those points a monotonic fallback.
                    long pointTime = point.timeMillis >= 0L
                            ? point.timeMillis
                            : (lastInkTime < 0L ? 0L : lastInkTime + 8L);
                    if (lastInkTime >= 0L && pointTime <= lastInkTime) {
                        pointTime = lastInkTime + 1L;
                    }
                    strokeBuilder.addPoint(Ink.Point.create(
                            point.x - writingArea.left, point.y - writingArea.top, pointTime));
                    lastInkTime = pointTime;
                    addedPoints++;
                }
                if (addedPoints > 0) {
                    inkBuilder.addStroke(strokeBuilder.build());
                    addedStrokes++;
                }
            }
            if (addedStrokes == 0) {
                postFailure(callback, new IllegalArgumentException(
                        "No usable handwriting points were found. Your ink was kept."));
                return;
            }
            Ink ink = inkBuilder.build();
            DigitalInkRecognizer recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).setMaxResultCount(5).build());
            RecognitionContext.Builder contextBuilder = RecognitionContext.builder()
                    .setWritingArea(new WritingArea(writingArea.width(), writingArea.height()))
                    // ML Kit requires preContext even when there is no preceding text.
                    .setPreContext(preContext);
            RecognitionContext recognitionContext = contextBuilder.build();
            recognizer.recognize(ink, recognitionContext)
                    .addOnSuccessListener(result -> {
                        String text = firstCandidate(result);
                        if (text.trim().isEmpty()) {
                            recognizeWithoutWritingArea(recognizer, ink, language,
                                    preContext, bounds, callback, null);
                        } else {
                            completeRecognition(recognizer, result, language, bounds, callback);
                        }
                    })
                    .addOnFailureListener(error -> recognizeWithoutWritingArea(
                            recognizer, ink, language, preContext, bounds, callback, error));
        } catch (Exception error) {
            postFailure(callback, error);
        }
    }

    /** Retry without the optional writing-area hint, which may be a poor fit for selected ink. */
    private static void recognizeWithoutWritingArea(DigitalInkRecognizer recognizer, Ink ink,
                                                    String language, String preContext, RectF bounds,
                                                    Callback callback, Exception firstError) {
        try {
            Task<com.google.mlkit.vision.digitalink.common.RecognitionResult> retryTask;
            if (preContext.isEmpty()) {
                retryTask = recognizer.recognize(ink);
            } else {
                RecognitionContext context = RecognitionContext.builder()
                        .setPreContext(preContext).build();
                retryTask = recognizer.recognize(ink, context);
            }
            retryTask
                    .addOnSuccessListener(result -> {
                        String text = firstCandidate(result);
                        if (text.trim().isEmpty()) {
                            recognizer.close();
                            IllegalArgumentException error = new IllegalArgumentException(
                                    "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink.");
                            if (firstError != null) error.addSuppressed(firstError);
                            postFailure(callback, error);
                        } else {
                            completeRecognition(recognizer, result, language, bounds, callback);
                        }
                    })
                    .addOnFailureListener(error -> {
                        recognizer.close();
                        if (firstError != null) error.addSuppressed(firstError);
                        postFailure(callback, error);
                    });
        } catch (Exception error) {
            recognizer.close();
            if (firstError != null) error.addSuppressed(firstError);
            postFailure(callback, error);
        }
    }

    private static String firstCandidate(com.google.mlkit.vision.digitalink.common.RecognitionResult result) {
        if (result == null || result.getCandidates().isEmpty()) return "";
        String text = result.getCandidates().get(0).getText();
        return text == null ? "" : text;
    }

    private static void completeRecognition(DigitalInkRecognizer recognizer,
                                            com.google.mlkit.vision.digitalink.common.RecognitionResult result,
                                            String language, RectF bounds, Callback callback) {
        try {
            List<String> alternatives = new ArrayList<>();
            for (com.google.mlkit.vision.digitalink.common.RecognitionCandidate candidate
                    : result.getCandidates()) {
                String candidateText = candidate.getText();
                if (candidateText != null && !candidateText.trim().isEmpty()
                        && !alternatives.contains(candidateText)) {
                    alternatives.add(candidateText);
                }
            }
            String text = alternatives.isEmpty() ? "" : alternatives.get(0);
            if (text.isEmpty()) {
                postFailure(callback, new IllegalArgumentException("The model returned no readable text."));
                return;
            }
            String lang = isLatin(language) ? "ENGLISH"
                    : ("HINDI".equalsIgnoreCase(language) ? "HINDI" : "MARATHI");
            float fontSize = Math.max(26f, Math.min(92f, bounds.height() * 0.85f));
            MAIN.post(() -> callback.onSuccess(
                    new RecognitionResult(text, lang, new RectF(bounds), fontSize, alternatives)));
        } finally {
            recognizer.close();
        }
    }

    private static RectF boundsOf(List<Stroke> strokes) {
        RectF bounds = new RectF();
        boolean found = false;
        for (Stroke stroke : strokes) {
            if (stroke == null) continue;
            List<DrawingPoint> inputPoints = stroke.getRecognitionPoints();
            if (inputPoints == null) continue;
            for (DrawingPoint point : inputPoints) {
                if (point == null || !isFinite(point.x) || !isFinite(point.y)) continue;
                if (!found) {
                    bounds.set(point.x, point.y, point.x, point.y);
                    found = true;
                } else bounds.union(point.x, point.y);
            }
        }
        return found ? bounds : null;
    }

    private static RectF usableWritingArea(RectF inkBounds) {
        float width = Math.max(0f, inkBounds.width());
        float height = Math.max(0f, inkBounds.height());
        float targetWidth = width;
        float targetHeight = height;
        if (targetWidth < MIN_WRITING_AREA_AXIS) {
            targetWidth = Math.max(MIN_WRITING_AREA_AXIS,
                    Math.max(height, MIN_WRITING_AREA_AXIS) * DEGENERATE_AXIS_RATIO);
        }
        if (targetHeight < MIN_WRITING_AREA_AXIS) {
            targetHeight = Math.max(MIN_WRITING_AREA_AXIS,
                    Math.max(width, MIN_WRITING_AREA_AXIS) * DEGENERATE_AXIS_RATIO);
        }
        float centerX = (inkBounds.left + inkBounds.right) * 0.5f;
        float centerY = (inkBounds.top + inkBounds.bottom) * 0.5f;
        return new RectF(centerX - targetWidth * 0.5f, centerY - targetHeight * 0.5f,
                centerX + targetWidth * 0.5f, centerY + targetHeight * 0.5f);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static String languageTag(String language) {
        // The app and its target devices are used in India; ML Kit has a
        // dedicated English (India) handwriting model as well as Marathi.
        if ("ENGLISH".equalsIgnoreCase(language)) return "en-IN";
        // ML Kit's base-model catalog lists Hindi as "hi" (unlike Marathi's
        // regional model "mr-IN"). Requesting hi-IN can yield no identifier.
        if ("HINDI".equalsIgnoreCase(language)) return "hi";
        return "mr-IN";
    }

    private static boolean isLatin(String language) {
        return "ENGLISH".equalsIgnoreCase(language);
    }

    private static void postFailure(Callback callback, Exception error) {
        Log.e(TAG, "Handwriting recognition failed", error);
        MAIN.post(() -> callback.onFailure(error));
    }
}
