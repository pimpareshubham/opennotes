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
import org.opennotes.app.ai.InkNormalizer;
import org.opennotes.app.ai.SemanticStrokeClusterer;
import org.opennotes.app.ai.UserHandwritingModel;

import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
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

    public interface AllModelsStatusCallback {
        void onStatus(boolean englishReady, boolean marathiReady, boolean hindiReady);
    }

    private HandwritingRecognitionEngine() { }

    /** Check if offline models are already installed on the device. */
    public static void checkModelsDownloaded(Context context, ModelsStatusCallback callback) {
        if (callback == null) return;
        WORKER.execute(() -> {
            try {
                RemoteModelManager manager = RemoteModelManager.getInstance();
                DigitalInkRecognitionModelIdentifier idEn = getModelIdentifier("ENGLISH");
                DigitalInkRecognitionModelIdentifier idMr = getModelIdentifier("MARATHI");
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

    /** Check offline download status of all supported language models (English, Marathi, Hindi). */
    public static void checkAllModelsDownloaded(Context context, AllModelsStatusCallback callback) {
        if (callback == null) return;
        WORKER.execute(() -> {
            try {
                RemoteModelManager manager = RemoteModelManager.getInstance();
                DigitalInkRecognitionModelIdentifier idEn = getModelIdentifier("ENGLISH");
                DigitalInkRecognitionModelIdentifier idMr = getModelIdentifier("MARATHI");
                DigitalInkRecognitionModelIdentifier idHi = getModelIdentifier("HINDI");

                DigitalInkRecognitionModel modelEn = idEn != null ? DigitalInkRecognitionModel.builder(idEn).build() : null;
                DigitalInkRecognitionModel modelMr = idMr != null ? DigitalInkRecognitionModel.builder(idMr).build() : null;
                DigitalInkRecognitionModel modelHi = idHi != null ? DigitalInkRecognitionModel.builder(idHi).build() : null;

                final boolean[] status = new boolean[3]; // [en, mr, hi]
                final int[] pending = {3};

                Runnable checkDone = () -> {
                    pending[0]--;
                    if (pending[0] <= 0) {
                        MAIN.post(() -> callback.onStatus(status[0], status[1], status[2]));
                    }
                };

                if (modelEn != null) {
                    manager.isModelDownloaded(modelEn).addOnCompleteListener(t -> {
                        status[0] = t.isSuccessful() && Boolean.TRUE.equals(t.getResult());
                        checkDone.run();
                    });
                } else checkDone.run();

                if (modelMr != null) {
                    manager.isModelDownloaded(modelMr).addOnCompleteListener(t -> {
                        status[1] = t.isSuccessful() && Boolean.TRUE.equals(t.getResult());
                        checkDone.run();
                    });
                } else checkDone.run();

                if (modelHi != null) {
                    manager.isModelDownloaded(modelHi).addOnCompleteListener(t -> {
                        status[2] = t.isSuccessful() && Boolean.TRUE.equals(t.getResult());
                        checkDone.run();
                    });
                } else checkDone.run();

            } catch (Exception e) {
                MAIN.post(() -> callback.onStatus(false, false, false));
            }
        });
    }

    public interface SingleModelStatusCallback {
        void onStatus(boolean downloaded);
    }

    public static void isModelDownloaded(String language, SingleModelStatusCallback callback) {
        if (callback == null) return;
        WORKER.execute(() -> {
            try {
                DigitalInkRecognitionModelIdentifier id = getModelIdentifier(language);
                if (id == null) {
                    MAIN.post(() -> callback.onStatus(false));
                    return;
                }
                DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
                RemoteModelManager.getInstance().isModelDownloaded(model)
                        .addOnCompleteListener(t -> {
                            boolean ready = t.isSuccessful() && Boolean.TRUE.equals(t.getResult());
                            MAIN.post(() -> callback.onStatus(ready));
                        });
            } catch (Exception e) {
                MAIN.post(() -> callback.onStatus(false));
            }
        });
    }

    public static void downloadModel(String language, ModelDownloadCallback callback) {
        WORKER.execute(() -> {
            try {
                String tag = languageTag(language);
                DigitalInkRecognitionModelIdentifier id = getModelIdentifier(language);
                if (id == null) {
                    if (callback != null) MAIN.post(() -> callback.onComplete(new IllegalArgumentException("Model not found: " + language)));
                    return;
                }
                DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
                downloadModelForSetup(RemoteModelManager.getInstance(), model, tag, callback != null ? callback : error -> {});
            } catch (Exception e) {
                if (callback != null) MAIN.post(() -> callback.onComplete(e));
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
                DigitalInkRecognitionModelIdentifier identifier = getModelIdentifier(preferredLanguage);
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

        // Multi-line and any-angle segmentation:
        // If the handwriting spans multiple lines (written one below another at any angle),
        // cleanly segmenting into lines prevents ML Kit from confusing overlapping bounding boxes.
        if (SemanticStrokeClusterer.isMultiLine(strokes)) {
            List<SemanticStrokeClusterer.StrokeLine> lines = SemanticStrokeClusterer.segmentIntoLines(strokes);
            if (lines != null && lines.size() > 1) {
                recognizeMultiLineStatements(context, lines, 0, preferredLanguage, contextText,
                        new StringBuilder(), new ArrayList<>(), new RectF(), callback);
                return;
            }
        }

        // Pass 1: Raw untouched strokes for Devanagari (Marathi/Hindi) so Kana and Matras
        // remain exactly anchored to consonants without slant distortion or artificial rotation.
        boolean isIndic = InkNormalizer.isIndicLanguage(preferredLanguage);
        InkNormalizer.NormalizedInk normalized = InkNormalizer.normalizeInk(strokes, preferredLanguage);
        List<Stroke> primaryStrokes = isIndic ? strokes
                : ((normalized != null && normalized.strokes != null && !normalized.strokes.isEmpty())
                        ? normalized.strokes : strokes);
        final float writingAngle = (normalized != null && normalized.transform != null)
                ? normalized.transform.angleDegrees
                : estimateWritingAngleDegrees(strokes);
        final boolean isSlanted = !isIndic && Math.abs(writingAngle) >= 3.5f;

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

    private static void recognizeMultiLineStatements(Context context,
                                                     List<SemanticStrokeClusterer.StrokeLine> lines,
                                                     int index,
                                                     String preferredLanguage,
                                                     String preContext,
                                                     StringBuilder combinedText,
                                                     List<String> combinedAlternatives,
                                                     RectF combinedBounds,
                                                     Callback callback) {
        if (index >= lines.size()) {
            String text = combinedText.toString().trim();
            if (text.isEmpty()) {
                callback.onFailure(new IllegalArgumentException("No text could be deciphered from handwriting lines."));
                return;
            }
            float lineCount = Math.max(1f, (float) lines.size());
            float fontSize = Math.max(26f, Math.min(92f, (combinedBounds.height() / lineCount) * 0.85f));
            String lang = isLatin(preferredLanguage) ? "ENGLISH"
                    : ("HINDI".equalsIgnoreCase(preferredLanguage) ? "HINDI" : "MARATHI");
            final String finalText = text;
            final String finalLang = lang;
            final float finalFontSize = fontSize;
            MAIN.post(() -> callback.onSuccess(
                    new RecognitionResult(finalText, finalLang, new RectF(combinedBounds), finalFontSize, combinedAlternatives)));
            return;
        }

        SemanticStrokeClusterer.StrokeLine line = lines.get(index);
        recognizeMultiHypothesis(context, line.strokes, preferredLanguage, preContext, false, new Callback() {
            @Override
            public void onSuccess(RecognitionResult result) {
                if (combinedText.length() > 0) combinedText.append("\n");
                combinedText.append(result.text);

                if (combinedBounds.isEmpty()) {
                    combinedBounds.set(result.bounds);
                } else {
                    combinedBounds.union(result.bounds);
                }

                for (String alt : result.alternatives) {
                    if (!combinedAlternatives.contains(alt)) combinedAlternatives.add(alt);
                }

                recognizeMultiLineStatements(context, lines, index + 1, preferredLanguage,
                        result.text, combinedText, combinedAlternatives, combinedBounds, callback);
            }

            @Override
            public void onFailure(Exception error) {
                // If multi-line decomposition fails on any subline, fall back to single-pass block recognition
                List<Stroke> allStrokes = new ArrayList<>();
                for (SemanticStrokeClusterer.StrokeLine l : lines) {
                    if (l != null && l.strokes != null) allStrokes.addAll(l.strokes);
                }
                executeSingleRecognitionPass(context, allStrokes, preferredLanguage, preContext, callback);
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

        // Test cleaned variations without destructive hook trimming
        candidatesToTry.add(transformedStrokeCopies(originalStrokes,
                primaryWasDeskewed ? writingAngle : 0f, false, false));

        // Test common tilt variations: +15°, -15°, +30°, -30°, 90°
        if (!primaryWasDeskewed) {
            candidatesToTry.add(transformedStrokeCopies(originalStrokes, 15f, false, false));
            candidatesToTry.add(transformedStrokeCopies(originalStrokes, -15f, false, false));
            RectF bounds = boundsOf(originalStrokes);
            if (bounds != null && bounds.height() > bounds.width() * 1.5f) {
                candidatesToTry.add(transformedStrokeCopies(originalStrokes, 90f, false, false));
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
            } else if (preContext == null && !queue.isEmpty()) {
                // Cross-language fallback (Devanagari <-> English) if primary language produced no candidates
                String fallbackLang = "ENGLISH".equalsIgnoreCase(language) ? "MARATHI" : "ENGLISH";
                DigitalInkRecognitionModelIdentifier fbId = getModelIdentifier(fallbackLang);
                if (fbId != null) {
                    DigitalInkRecognitionModel fbModel = DigitalInkRecognitionModel.builder(fbId).build();
                    RemoteModelManager.getInstance().isModelDownloaded(fbModel).addOnCompleteListener(t -> {
                        if (t.isSuccessful() && Boolean.TRUE.equals(t.getResult())) {
                            executeSingleRecognitionPass(context, queue.get(0), fallbackLang, "", callback);
                        } else {
                            callback.onFailure(new IllegalArgumentException(
                                    "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
                        }
                    });
                    return;
                }
                callback.onFailure(new IllegalArgumentException(
                        "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
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
            DigitalInkRecognitionModelIdentifier identifier = getModelIdentifier(preferredLanguage);
            if (identifier == null) {
                postFailure(callback, new IllegalArgumentException("Handwriting recognition is not available for " + preferredLanguage));
                return;
            }
            String tag = identifier.getLanguageSubtag();
            DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(identifier).build();
            RemoteModelManager manager = RemoteModelManager.getInstance();
            manager.isModelDownloaded(model).addOnSuccessListener(isDownloaded -> {
                if (isDownloaded) {
                    runRecognition(context, model, strokes, preferredLanguage, preContext, bounds, callback);
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
        downloadThenRecognize(context, manager, model, tag, strokes, language, preContext, bounds, callback);
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


    private static void downloadThenRecognize(Context context, RemoteModelManager manager,
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
                            runRecognition(context, model, strokes, language, preContext, bounds, callback);
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

    private static void runRecognition(Context context, DigitalInkRecognitionModel model, List<Stroke> strokes,
                                       String language, String preContext,
                                       RectF bounds, Callback callback) {
        // ML Kit Task listeners normally run on the main thread. Building Ink
        // walks every captured point and can take long enough to hitch the
        // editor for page-wide conversion, so always prepare and submit it on
        // the dedicated recognition worker.
        WORKER.execute(() -> buildInkAndRecognize(
                context, model, strokes, language, preContext, bounds, callback));
    }

    private static void buildInkAndRecognize(Context context, DigitalInkRecognitionModel model,
                                             List<Stroke> strokes, String language,
                                             String preContext, RectF bounds,
                                             Callback callback) {
        try {
            float naturalHeight = bounds.height();
            float naturalWidth = bounds.width();

            // ML Kit Digital Ink CNN/RNN models were trained on nominal line heights of 200-280px.
            // Small handwriting (e.g. 40-90px tall) shrinks upper matras (मात्रा) and anuswar (अनुस्वार)
            // to 2-4px, causing convolutional downsampling pooling to lose them.
            // Scale ink coordinates up to ~240px nominal height.
            float scale = 1.0f;
            if (naturalHeight > 0f && naturalHeight < 180f) {
                scale = Math.min(4.5f, 240f / naturalHeight);
            }

            float scaledW = naturalWidth * scale;
            float scaledH = naturalHeight * scale;
            float padX = Math.max(24f, scaledW * 0.10f);
            float padY = Math.max(24f, scaledH * 0.10f);
            float areaW = scaledW + padX * 2f;
            float areaH = scaledH + padY * 2f;

            Ink.Builder inkBuilder = Ink.builder();
            long lastInkTime = -1L;
            int addedStrokes = 0;
            for (Stroke source : strokes) {
                if (source == null) continue;
                List<DrawingPoint> inputPoints = source.getRecognitionPoints();
                if (inputPoints == null || inputPoints.isEmpty()) continue;
                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                int addedPoints = 0;
                float lastAddedX = 0f;
                float lastAddedY = 0f;
                for (DrawingPoint point : inputPoints) {
                    if (point == null || !isFinite(point.x) || !isFinite(point.y)) continue;
                    long pointTime = point.timeMillis >= 0L
                            ? point.timeMillis
                            : (lastInkTime < 0L ? 0L : lastInkTime + 8L);
                    if (lastInkTime >= 0L && pointTime <= lastInkTime) {
                        pointTime = lastInkTime + 1L;
                    }
                    float normX = (point.x - bounds.left) * scale + padX;
                    float normY = (point.y - bounds.top) * scale + padY;
                    strokeBuilder.addPoint(Ink.Point.create(normX, normY, pointTime));
                    lastAddedX = normX;
                    lastAddedY = normY;
                    lastInkTime = pointTime;
                    addedPoints++;
                }
                if (addedPoints == 1) {
                    // Single-point strokes (dots, anuswar, short matra taps):
                    // ML Kit feature extraction works on directional vectors (dx, dy).
                    // Single points have zero vectors and are SILENTLY DISCARDED by ML Kit!
                    // Synthesize a 2.5px micro-segment so the dot is preserved and recognized.
                    strokeBuilder.addPoint(Ink.Point.create(
                            lastAddedX + 2.5f, lastAddedY + 2.5f, lastInkTime + 15L));
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
                    DigitalInkRecognizerOptions.builder(model).setMaxResultCount(16).build());

            boolean isBigLine = bounds.width() > 400f || bounds.width() > bounds.height() * 2.8f;
            if (isBigLine) {
                recognizeWithoutWritingArea(context, strokes, recognizer, ink, language, preContext, bounds, callback, null);
                return;
            }

            RecognitionContext.Builder contextBuilder = RecognitionContext.builder()
                    .setWritingArea(new WritingArea(areaW, areaH))
                    .setPreContext(preContext);
            RecognitionContext recognitionContext = contextBuilder.build();
            recognizer.recognize(ink, recognitionContext)
                    .addOnSuccessListener(result -> {
                        String text = firstCandidate(result);
                        if (text.trim().isEmpty()) {
                            recognizeWithoutWritingArea(context, strokes, recognizer, ink, language,
                                    preContext, bounds, callback, null);
                        } else {
                            completeRecognition(context, strokes, recognizer, result, language, preContext, bounds, callback);
                        }
                    })
                    .addOnFailureListener(error -> recognizeWithoutWritingArea(
                            context, strokes, recognizer, ink, language, preContext, bounds, callback, error));
        } catch (Exception error) {
            postFailure(callback, error);
        }
    }

    /** Retry without the optional writing-area hint, which may be a poor fit for selected ink. */
    private static void recognizeWithoutWritingArea(Context context, List<Stroke> strokes,
                                                    DigitalInkRecognizer recognizer, Ink ink,
                                                    String language, String preContext, RectF bounds,
                                                    Callback callback, Exception firstError) {
        try {
            Task<com.google.mlkit.vision.digitalink.common.RecognitionResult> retryTask;
            if (preContext.isEmpty()) {
                retryTask = recognizer.recognize(ink);
            } else {
                RecognitionContext recContext = RecognitionContext.builder()
                        .setPreContext(preContext).build();
                retryTask = recognizer.recognize(ink, recContext);
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
                            completeRecognition(context, strokes, recognizer, result, language, preContext, bounds, callback);
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

    private static void completeRecognition(Context context, List<Stroke> strokes,
                                            DigitalInkRecognizer recognizer,
                                            com.google.mlkit.vision.digitalink.common.RecognitionResult result,
                                            String language, String preContext, RectF bounds, Callback callback) {
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
            List<String> rerankedAlternatives = enhanceAndRerankCandidates(context, strokes, alternatives, language, preContext);
            String text = rerankedAlternatives.isEmpty() ? "" : rerankedAlternatives.get(0);
            if (text.isEmpty()) {
                postFailure(callback, new IllegalArgumentException("The model returned no readable text."));
                return;
            }
            String lang = isLatin(language) ? "ENGLISH"
                    : ("HINDI".equalsIgnoreCase(language) ? "HINDI" : "MARATHI");
            float fontSize = Math.max(26f, Math.min(92f, bounds.height() * 0.85f));
            final float finalFontSize = fontSize;
            final String finalText = text;
            final String finalLang = lang;
            MAIN.post(() -> callback.onSuccess(
                    new RecognitionResult(finalText, finalLang, new RectF(bounds), finalFontSize, rerankedAlternatives)));
        } finally {
            recognizer.close();
        }
    }

    private static List<String> rerankDevanagariCandidates(List<Stroke> strokes, List<String> candidates) {
        if (strokes == null || strokes.isEmpty() || candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        RectF bounds = boundsOf(strokes);
        if (bounds == null || bounds.height() < 5f) return candidates;

        float totalHeight = bounds.height();
        float totalWidth = bounds.width();
        float upperThreshold = bounds.top + totalHeight * 0.30f;
        float lowerThreshold = bounds.bottom - totalHeight * 0.25f;
        float leftThreshold = bounds.left + totalWidth * 0.40f;
        float rightThreshold = bounds.left + totalWidth * 0.45f;

        boolean hasUpperMatraStroke = false;
        boolean hasKanaStroke = false;
        boolean hasPahiliVelantiStroke = false;
        boolean hasDusriVelantiStroke = false;
        boolean hasLowerUkarStroke = false;
        boolean hasAnusvarDot = false;

        for (Stroke s : strokes) {
            if (s == null) continue;
            RectF sb = s.getBounds();
            if (sb == null || sb.isEmpty()) continue;

            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            int ptCount = (pts != null) ? pts.size() : 0;

            float arcLen = 0f;
            if (pts != null && ptCount > 1) {
                for (int i = 0; i < ptCount - 1; i++) {
                    DrawingPoint p1 = pts.get(i);
                    DrawingPoint p2 = pts.get(i + 1);
                    arcLen += (float) Math.hypot(p2.x - p1.x, p2.y - p1.y);
                }
            }

            // 1. Anusvar Dot (अनुस्वार - ं): Small dot or short tap in top region
            boolean isDotSize = (sb.width() < totalWidth * 0.26f && sb.height() < totalHeight * 0.26f)
                    && (ptCount <= 6 || arcLen < totalHeight * 0.35f);
            if (isDotSize && sb.bottom <= upperThreshold + totalHeight * 0.12f) {
                hasAnusvarDot = true;
                hasUpperMatraStroke = true;
                continue;
            }

            // 2. Upper Matra (मात्रा - े, ै, ो, ौ): Slanted line sitting predominantly above or at upperThreshold
            if (sb.top < upperThreshold) {
                if (sb.bottom <= upperThreshold + totalHeight * 0.22f) {
                    hasUpperMatraStroke = true;
                } else if (pts != null) {
                    for (DrawingPoint p : pts) {
                        if (p.y < bounds.top + totalHeight * 0.20f) {
                            hasUpperMatraStroke = true;
                            break;
                        }
                    }
                }
            }

            // 3. Pahili Velanti (पहिली वेलांटी - ि): Vertical stem on the left of the consonant
            if (sb.right <= leftThreshold && sb.height() >= totalHeight * 0.35f) {
                hasPahiliVelantiStroke = true;
            }

            // 4. Kana (काणा - ा) vs Dusri Velanti (दुसरी वेलांटी - ी):
            // Both have a vertical downstroke on the right side of the character.
            // A Kana is straight vertical (no backward arch over the consonant).
            // A Dusri Velanti has an arch at the top curving to the LEFT over the consonant!
            if (sb.left >= rightThreshold && sb.height() >= totalHeight * 0.35f) {
                boolean hasLeftwardArch = false;
                if (pts != null && ptCount >= 3) {
                    float stemX = sb.right;
                    float minTopX = stemX;
                    for (DrawingPoint p : pts) {
                        if (p.y < upperThreshold) {
                            minTopX = Math.min(minTopX, p.x);
                        }
                    }
                    if (minTopX < stemX - totalWidth * 0.25f) {
                        hasLeftwardArch = true;
                    }
                }

                if (hasLeftwardArch) {
                    hasDusriVelantiStroke = true;
                } else {
                    hasKanaStroke = true;
                }
            }

            // 5. Lower Ukar (उकार - ु, ू): Dips below lower baseline
            if (sb.bottom >= lowerThreshold && sb.top > bounds.top + totalHeight * 0.45f) {
                hasLowerUkarStroke = true;
            }
        }

        // Global check across all points for upper matra (e.g. if drawn in a compound stroke)
        if (!hasUpperMatraStroke) {
            for (Stroke s : strokes) {
                if (s == null) continue;
                List<DrawingPoint> pts = s.getRecognitionPoints();
                if (pts == null || pts.isEmpty()) pts = s.points;
                if (pts == null) continue;
                for (DrawingPoint p : pts) {
                    if (p.y < bounds.top + totalHeight * 0.18f) {
                        hasUpperMatraStroke = true;
                        break;
                    }
                }
                if (hasUpperMatraStroke) break;
            }
        }

        // Candidate Synthesis:
        // Guarantee that if the user wrote a diacritic combination, the matching candidate exists in the pool!
        List<String> pool = new ArrayList<>(candidates);
        String baseChar = extractPrimaryDevanagariConsonant(candidates);
        if (baseChar != null && !baseChar.isEmpty()) {
            if (hasKanaStroke && hasUpperMatraStroke) {
                // Kana + Upper Matra -> को / कौ
                addIfMissing(pool, baseChar + "\u094b"); // \u094b = ो (e.g. को)
                addIfMissing(pool, baseChar + "\u094c"); // \u094c = ौ (e.g. कौ)
            } else if (hasKanaStroke && !hasUpperMatraStroke && !hasDusriVelantiStroke && !hasPahiliVelantiStroke) {
                // Pure Kana -> का
                addIfMissing(pool, baseChar + "\u093e"); // \u093e = ा (e.g. का)
            } else if (hasPahiliVelantiStroke) {
                // Pahili Velanti -> कि
                addIfMissing(pool, baseChar + "\u093f"); // \u093f = ि (e.g. कि)
            } else if (hasDusriVelantiStroke) {
                // Dusri Velanti -> की
                addIfMissing(pool, baseChar + "\u0940"); // \u0940 = ी (e.g. की)
            } else if (hasAnusvarDot) {
                // Anusvar -> कं, बं
                addIfMissing(pool, baseChar + "\u0902"); // \u0902 = ं (e.g. बं, कं)
            } else if (hasUpperMatraStroke && !hasKanaStroke) {
                // Upper Matra -> के, बे, कै
                addIfMissing(pool, baseChar + "\u0947"); // \u0947 = े (e.g. बे, के)
                addIfMissing(pool, baseChar + "\u0948"); // \u0948 = ै (e.g. कै, बै)
            } else if (hasLowerUkarStroke) {
                // Ukar -> कु, कू
                addIfMissing(pool, baseChar + "\u0941"); // \u0941 = ु
                addIfMissing(pool, baseChar + "\u0942"); // \u0942 = ू
            }
        }

        final boolean fUpper = hasUpperMatraStroke;
        final boolean fKana = hasKanaStroke;
        final boolean fPahili = hasPahiliVelantiStroke;
        final boolean fDusri = hasDusriVelantiStroke;
        final boolean fLower = hasLowerUkarStroke;
        final boolean fAnusvar = hasAnusvarDot;

        pool.sort((c1, c2) -> {
            int score1 = scoreDevanagariMatch(c1, fUpper, fKana, fPahili, fDusri, fLower, fAnusvar);
            int score2 = scoreDevanagariMatch(c2, fUpper, fKana, fPahili, fDusri, fLower, fAnusvar);
            return Integer.compare(score2, score1);
        });

        return pool;
    }

    private static void addIfMissing(List<String> list, String item) {
        if (item != null && !item.isEmpty() && !list.contains(item)) {
            list.add(0, item);
        }
    }

    private static String extractPrimaryDevanagariConsonant(List<String> candidates) {
        if (candidates == null) return null;
        for (String c : candidates) {
            if (c == null || c.trim().isEmpty()) continue;
            String trimmed = c.trim();
            for (int i = 0; i < trimmed.length(); i++) {
                char ch = trimmed.charAt(i);
                if ((ch >= '\u0915' && ch <= '\u0939') || (ch >= '\u0905' && ch <= '\u0914')) {
                    return String.valueOf(ch);
                }
            }
        }
        return null;
    }

    private static int scoreDevanagariMatch(String text, boolean hasUpper, boolean hasKana,
                                            boolean hasPahiliVelanti, boolean hasDusriVelanti,
                                            boolean hasLower, boolean hasAnusvar) {
        if (text == null) return 0;
        int score = 0;

        boolean textHasPureKana = text.contains("\u093e"); // का, बा
        boolean textHasOorAu = containsAny(text, "\u094b", "\u094c"); // को, कौ
        boolean textHasPahiliVelanti = text.contains("\u093f"); // कि, बि
        boolean textHasDusriVelanti = text.contains("\u0940"); // की, बी
        boolean textHasAnyVelanti = textHasPahiliVelanti || textHasDusriVelanti;
        boolean textHasPureUpper = containsAny(text, "\u0947", "\u0948", "\u0945"); // के, कै
        boolean textHasAnusvar = containsAny(text, "\u0902", "\u0901", "\u0903"); // कं, बं
        boolean textHasLowerUkar = containsAny(text, "\u0941", "\u0942", "\u0943"); // कु, कू
        boolean isBareConsonant = !textHasPureKana && !textHasOorAu && !textHasAnyVelanti
                && !textHasPureUpper && !textHasAnusvar && !textHasLowerUkar;

        // 1. Kana + Upper Matra (को, कौ)
        if (hasKana && hasUpper) {
            if (textHasOorAu) score += 95;
            else if (textHasPureKana) score -= 45;
            else if (textHasPureUpper) score -= 45;
            else if (textHasAnyVelanti) score -= 85;
            else if (isBareConsonant) score -= 110;
        }
        // 2. Pure Kana (का, बा) - NO upper matra, NO velanti arch, NO left stem
        else if (hasKana && !hasUpper && !hasDusriVelanti && !hasPahiliVelanti) {
            if (textHasPureKana) score += 95;
            if (textHasAnyVelanti) score -= 90; // User explicitly said: "sometimes it's adding velanti even though i tried to add a kana"
            if (textHasOorAu) score -= 50;
            if (textHasPureUpper) score -= 60;
            if (isBareConsonant) score -= 95;
        }
        // 3. Pahili Velanti (कि, बि) - left vertical stem with arch
        else if (hasPahiliVelanti) {
            if (textHasPahiliVelanti) score += 95;
            if (textHasDusriVelanti) score += 30;
            if (textHasPureKana) score -= 85;
            if (isBareConsonant) score -= 95;
        }
        // 4. Dusri Velanti (की, बी) - right vertical stem with leftward arch
        else if (hasDusriVelanti) {
            if (textHasDusriVelanti) score += 95;
            if (textHasPahiliVelanti) score += 30;
            if (textHasPureKana) score -= 85;
            if (isBareConsonant) score -= 95;
        }
        // 5. Anusvar Dot (कं, बं)
        else if (hasAnusvar && !hasKana) {
            if (textHasAnusvar) score += 95;
            if (textHasPureUpper) score += 40;
            if (textHasPureKana) score -= 65;
            if (textHasAnyVelanti) score -= 85;
            if (isBareConsonant) score -= 95;
        }
        // 6. Upper Matra Only (के, बे, कै)
        else if (hasUpper && !hasKana) {
            if (textHasPureUpper) score += 90;
            if (textHasAnusvar) score += 40;
            if (textHasPureKana) score -= 65;
            if (textHasAnyVelanti) score -= 85;
            if (textHasOorAu) score -= 40;
            if (isBareConsonant) score -= 95;
        }

        // 7. Lower Ukar (कु, कू)
        if (hasLower) {
            if (textHasLowerUkar) score += 85;
            else if (isBareConsonant) score -= 75;
        } else {
            if (textHasLowerUkar) score -= 40;
        }

        return score;
    }

    private static boolean containsAny(String text, String... patterns) {
        if (text == null) return false;
        for (String p : patterns) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    private static List<String> enhanceAndRerankCandidates(Context context, List<Stroke> strokes,
                                                           List<String> alternatives, String language, String preContext) {
        if (alternatives == null || alternatives.isEmpty()) return alternatives;
        List<String> pool = new ArrayList<>(alternatives);

        // Devanagari (Marathi/Hindi) linguistic and geometric feature matching
        if (InkNormalizer.isIndicLanguage(language)) {
            pool = rerankDevanagariCandidates(strokes, pool);
            UserHandwritingModel userModel = (context != null) ? UserHandwritingModel.getInstance(context) : null;
            if (userModel != null) {
                for (String cand : alternatives) {
                    String learned = userModel.predictLearnedCorrection(cand, strokes);
                    if (learned != null && !learned.trim().isEmpty() && !pool.contains(learned)) {
                        pool.add(0, learned);
                    }
                }
            }
            return pool;
        }

        UserHandwritingModel userModel = (context != null) ? UserHandwritingModel.getInstance(context) : null;

        // 1. Personalized learned handwriting style lookup (trained prototypes & substitutions)
        if (userModel != null) {
            for (String cand : alternatives) {
                String learned = userModel.predictLearnedCorrection(cand, strokes);
                if (learned != null && !learned.trim().isEmpty() && !pool.contains(learned)) {
                    pool.add(0, learned); // Prioritize user-trained style at top
                }
            }
        }

        // 2. Doctor handwriting & careless handwriting replacements
        Map<String, String> wordReplacements = getCarelessHandwritingReplacements();
        int initialSize = pool.size();
        for (int i = 0; i < initialSize; i++) {
            String candidate = pool.get(i);
            String corrected = applyWordReplacements(candidate, wordReplacements);
            if (!corrected.equals(candidate) && !pool.contains(corrected)) {
                pool.add(corrected);
            }
        }

        // 3. Score each candidate using language probability, user vocabulary, doctor terminology, bigrams, and penalties
        String lastPreContextWord = getLastToken(preContext);

        class ScoredCandidate implements Comparable<ScoredCandidate> {
            final String text;
            final float score;
            final int originalRank;

            ScoredCandidate(String text, float score, int originalRank) {
                this.text = text;
                this.score = score;
                this.originalRank = originalRank;
            }

            @Override
            public int compareTo(ScoredCandidate other) {
                int cmp = Float.compare(other.score, this.score);
                if (cmp != 0) return cmp;
                return Integer.compare(this.originalRank, other.originalRank);
            }
        }

        List<ScoredCandidate> scoredList = new ArrayList<>();
        for (int rank = 0; rank < pool.size(); rank++) {
            String cand = pool.get(rank);
            float score = calculateProbabilisticScore(context, cand, rank, lastPreContextWord);
            scoredList.add(new ScoredCandidate(cand, score, rank));
        }

        Collections.sort(scoredList);

        List<String> reranked = new ArrayList<>();
        for (ScoredCandidate sc : scoredList) {
            if (!reranked.contains(sc.text)) {
                reranked.add(sc.text);
            }
        }

        return reranked;
    }

    private static String getLastToken(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 0) return null;
        String last = parts[parts.length - 1].replaceAll("[^a-zA-Z0-9]", "");
        return last.isEmpty() ? null : last.toLowerCase();
    }

    private static String applyWordReplacements(String text, Map<String, String> wordReplacements) {
        if (text == null || text.trim().isEmpty()) return text;
        String[] words = text.split(" ");
        boolean changed = false;
        StringBuilder fixedLine = new StringBuilder();
        for (int w = 0; w < words.length; w++) {
            if (w > 0) fixedLine.append(" ");
            String word = words[w];
            String clean = word.replaceAll("[^a-zA-Z0-9]", "");
            String punctuation = word.substring(clean.length());
            String lower = clean.toLowerCase();
            if (wordReplacements.containsKey(lower)) {
                String replacement = wordReplacements.get(lower);
                if (clean.length() > 0 && Character.isUpperCase(clean.charAt(0)) && replacement.length() > 0) {
                    replacement = Character.toUpperCase(replacement.charAt(0))
                            + (replacement.length() > 1 ? replacement.substring(1) : "");
                }
                fixedLine.append(replacement).append(punctuation);
                changed = true;
            } else if (clean.equalsIgnoreCase("12") || clean.equalsIgnoreCase("l2") || clean.equalsIgnoreCase("1z") || clean.equalsIgnoreCase("lz")) {
                fixedLine.append("R").append(punctuation);
                changed = true;
            } else {
                fixedLine.append(word);
            }
        }
        return changed ? fixedLine.toString() : text;
    }

    private static float calculateProbabilisticScore(Context context, String text, int originalRank, String lastPreContextWord) {
        float score = Math.max(0f, 32f - (originalRank * 2.5f));
        String[] tokens = text.trim().split("\\s+");
        String prev = lastPreContextWord;

        UserHandwritingModel userModel = (context != null) ? UserHandwritingModel.getInstance(context) : null;
        List<String> customVocab = (userModel != null) ? userModel.getCustomVocabulary() : Collections.emptyList();

        for (String token : tokens) {
            String clean = token.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (clean.isEmpty()) continue;

            if (customVocab.contains(token) || customVocab.contains(clean)) {
                score += 50f; // Boost user-taught vocabulary words
            } else if (clean.equals("r")) {
                score += 26f;
            } else if (clean.equals("12") || clean.equals("l2") || clean.equals("1z") || clean.equals("lz")) {
                score -= 15f; // penalize mistaken digit for R
            } else if (isDoctorTerm(clean)) {
                score += 30f; // Boost medical/doctor terminology and abbreviations
            } else if (isFrequentWord(clean)) {
                score += 22f;
            } else if (isInLexicon(clean)) {
                score += 14f;
            } else if (isNumeric(clean)) {
                score += 10f;
            } else if (isProperNoun(token)) {
                score += 12f;
            } else if (hasDigitMixedWithLetters(clean)) {
                score -= 30f; // definitely garbage (e.g. peop1e, w0rk)
            } else if (clean.length() >= 3) {
                score -= 8f; // unrecognized non-dictionary word
            }

            if (prev != null) {
                String bigram = prev + " " + clean;
                if (isFrequentBigram(bigram)) {
                    score += 40f; // decisive boost for natural phrases
                }
            }
            prev = clean;
        }

        return score;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isProperNoun(String word) {
        if (word == null || word.length() < 2) return false;
        return Character.isUpperCase(word.charAt(0)) && Character.isLowerCase(word.charAt(1));
    }

    private static boolean hasDigitMixedWithLetters(String s) {
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) hasDigit = true;
            else if (Character.isLetter(c)) hasLetter = true;
        }
        return hasDigit && hasLetter;
    }

    private static final Set<String> FREQUENT_WORDS_SET = new HashSet<>(Arrays.asList(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he",
            "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
            "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about",
            "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know",
            "take", "people", "into", "year", "your", "good", "some", "could", "them", "see", "other", "than", "then",
            "now", "look", "only", "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our",
            "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            "are", "r", "write", "wrote", "written", "writing", "note", "notes", "starnote", "opennotes", "today", "tomorrow",
            "yesterday", "morning", "evening", "night", "week", "month", "sunday", "monday", "tuesday", "wednesday",
            "thursday", "friday", "saturday", "meeting", "project", "plan", "task", "tasks", "office", "home", "call",
            "message", "email", "phone", "contact", "name", "suresh", "shubham", "rahul", "rohit", "amit", "priya",
            "sneha", "anjali", "pooja", "great", "sure", "pure", "more", "here", "where", "every", "never", "under",
            "water", "paper", "letter", "print", "create", "share", "stare", "help", "please", "thanks", "thank",
            "welcome", "ok", "okay", "yes", "done", "important", "remember", "check", "point", "summary", "question", "answer"
    ));

    private static boolean isFrequentWord(String word) {
        return FREQUENT_WORDS_SET.contains(word);
    }

    private static final Set<String> LEXICON_SET = new HashSet<>(Arrays.asList(
            "able", "about", "above", "accept", "according", "account", "across", "act", "action", "activity",
            "actually", "add", "address", "admit", "adult", "affect", "after", "again", "against", "age",
            "agency", "agent", "ago", "agree", "agreement", "ahead", "air", "all", "allow", "almost",
            "alone", "along", "already", "also", "although", "always", "american", "among", "amount", "analysis",
            "and", "animal", "another", "answer", "any", "anyone", "anything", "appear", "apply", "approach",
            "area", "argue", "arm", "around", "arrive", "art", "article", "artist", "as", "ask",
            "assume", "at", "attack", "attention", "attorney", "audience", "author", "authority", "available", "avoid",
            "away", "baby", "back", "bad", "bag", "ball", "bank", "bar", "base", "be",
            "beat", "beautiful", "because", "become", "bed", "before", "begin", "behavior", "behind", "believe",
            "benefit", "best", "better", "between", "beyond", "big", "bill", "billion", "bit", "black",
            "blood", "blue", "board", "body", "book", "born", "both", "box", "boy", "break",
            "bring", "brother", "budget", "build", "building", "business", "but", "buy", "by", "call",
            "camera", "campaign", "can", "cancer", "candidate", "capital", "car", "card", "care", "career",
            "carry", "case", "catch", "cause", "cell", "center", "central", "century", "certain", "certainly",
            "chair", "challenge", "chance", "change", "character", "charge", "check", "child", "choice", "choose",
            "church", "citizen", "city", "civil", "claim", "class", "clear", "clearly", "close", "coach",
            "cold", "collection", "college", "color", "come", "commercial", "common", "community", "company", "compare",
            "computer", "concern", "condition", "conference", "congress", "consider", "consumer", "contain", "continue", "control",
            "cost", "could", "country", "couple", "course", "court", "cover", "create", "crime", "cultural",
            "culture", "cup", "current", "customer", "cut", "dark", "data", "daughter", "day", "dead",
            "deal", "death", "debate", "decade", "decide", "decision", "deep", "defense", "degree", "democrat",
            "democratic", "describe", "design", "despite", "detail", "determine", "develop", "development", "device", "differ",
            "difference", "different", "difficult", "dinner", "direction", "director", "discover", "discuss", "discussion", "disease",
            "do", "doctor", "dog", "door", "down", "draw", "dream", "drive", "drop", "drug",
            "during", "each", "early", "east", "easy", "eat", "economic", "economy", "edge", "education",
            "effect", "effort", "eight", "either", "election", "else", "employee", "end", "energy", "enjoy",
            "enough", "enter", "entire", "environment", "environmental", "especially", "establish", "even", "evening", "event",
            "ever", "every", "everybody", "everyone", "everything", "evidence", "exactly", "example", "executive", "exist",
            "expect", "experience", "expert", "explain", "eye", "face", "fact", "factor", "fail", "fall",
            "family", "far", "fast", "father", "fear", "federal", "feel", "feeling", "few", "field",
            "fight", "figure", "fill", "film", "final", "finally", "financial", "find", "fine", "finger",
            "finish", "fire", "firm", "first", "fish", "five", "floor", "fly", "focus", "follow",
            "food", "foot", "for", "force", "foreign", "forget", "form", "former", "forward", "four",
            "free", "friend", "from", "front", "full", "fund", "future", "game", "garden", "gas",
            "general", "generation", "get", "girl", "give", "glass", "go", "goal", "good", "government",
            "great", "green", "ground", "group", "grow", "growth", "guess", "gun", "guy", "hair",
            "half", "hand", "hang", "happen", "happy", "hard", "have", "he", "head", "health",
            "hear", "heart", "heat", "heavy", "help", "her", "here", "herself", "high", "him",
            "himself", "his", "history", "hit", "hold", "home", "hope", "hospital", "hot", "hotel",
            "hour", "house", "how", "however", "huge", "human", "hundred", "husband", "i", "idea",
            "identify", "if", "image", "imagine", "impact", "important", "improve", "in", "include", "including",
            "increase", "indeed", "indicate", "individual", "industry", "information", "inside", "instead", "institution", "interest",
            "interesting", "international", "interview", "into", "investment", "involve", "issue", "it", "item", "its",
            "itself", "job", "join", "just", "keep", "key", "kid", "kill", "kind", "kitchen",
            "know", "knowledge", "land", "language", "large", "last", "late", "later", "laugh", "law",
            "lawyer", "lay", "lead", "leader", "learn", "least", "leave", "left", "leg", "legal",
            "less", "let", "letter", "level", "lie", "life", "light", "like", "likely", "line",
            "list", "listen", "little", "live", "local", "long", "look", "lose", "loss", "lot",
            "love", "low", "machine", "magazine", "main", "maintain", "major", "majority", "make", "man",
            "manage", "management", "manager", "many", "market", "marriage", "material", "matter", "may", "maybe",
            "me", "mean", "measure", "media", "medical", "meet", "meeting", "member", "memory", "mention",
            "message", "method", "middle", "might", "military", "million", "mind", "minute", "miss", "mission",
            "model", "modern", "moment", "money", "month", "more", "morning", "most", "mother", "mouth",
            "move", "movement", "movie", "mr", "mrs", "much", "music", "must", "my", "myself",
            "name", "nation", "national", "natural", "nature", "near", "nearly", "necessary", "need", "network",
            "never", "new", "news", "newspaper", "next", "nice", "night", "no", "none", "nor",
            "north", "not", "note", "nothing", "notice", "now", "number", "occur", "of", "off",
            "offer", "office", "officer", "official", "often", "oh", "oil", "ok", "okay", "old",
            "on", "once", "one", "only", "onto", "open", "operation", "opportunity", "option", "or",
            "order", "organization", "other", "others", "our", "out", "outside", "over", "own", "owner",
            "page", "pain", "painting", "paper", "parent", "part", "participant", "particular", "particularly", "partner",
            "party", "pass", "past", "patient", "pattern", "pay", "peace", "people", "per", "perform",
            "performance", "perhaps", "period", "person", "personal", "phone", "physical", "pick", "picture", "piece",
            "place", "plan", "plant", "play", "player", "point", "police", "policy", "political", "politics",
            "poor", "popular", "population", "position", "positive", "possible", "power", "practice", "prepare", "present",
            "president", "pressure", "pretty", "prevent", "price", "private", "probably", "problem", "process", "produce",
            "product", "production", "professional", "professor", "program", "project", "property", "protect", "prove", "provide",
            "public", "pull", "purpose", "push", "put", "quality", "question", "quickly", "quite", "race",
            "radio", "raise", "range", "rate", "rather", "reach", "read", "ready", "real", "reality",
            "realize", "really", "reason", "receive", "recent", "recently", "recognize", "record", "red", "reduce",
            "reflect", "region", "relate", "relationship", "religious", "remain", "remember", "remove", "report", "represent",
            "republican", "require", "research", "resource", "respond", "response", "responsibility", "rest", "result", "return",
            "reveal", "rich", "right", "rise", "risk", "road", "rock", "role", "room", "rule",
            "run", "safe", "same", "save", "say", "scene", "school", "science", "scientist", "score",
            "sea", "season", "seat", "second", "section", "security", "see", "seek", "seem", "sell",
            "send", "senior", "sense", "series", "serious", "serve", "service", "set", "seven", "several",
            "sex", "sexual", "shake", "share", "she", "shoot", "short", "shot", "should", "shoulder",
            "show", "side", "sign", "significant", "similar", "simple", "simply", "since", "sing", "single",
            "sister", "sit", "site", "situation", "six", "size", "skill", "skin", "small", "smile",
            "so", "social", "society", "soldier", "some", "somebody", "someone", "something", "sometimes", "son",
            "song", "soon", "sort", "sound", "source", "south", "southern", "space", "speak", "special",
            "specific", "speech", "spend", "sport", "spring", "staff", "stage", "stand", "standard", "star",
            "start", "state", "statement", "station", "stay", "step", "still", "stock", "stop", "store",
            "story", "strategy", "street", "strong", "structure", "student", "study", "stuff", "style", "subject",
            "success", "successful", "such", "suddenly", "suffer", "suggest", "summer", "support", "sure", "surface",
            "system", "table", "take", "talk", "task", "tax", "teach", "teacher", "team", "technology",
            "television", "tell", "ten", "tend", "term", "test", "than", "thank", "thanks", "that",
            "the", "their", "them", "themselves", "then", "theory", "there", "these", "they", "thing",
            "think", "third", "this", "those", "though", "thought", "thousand", "threat", "three", "through",
            "throughout", "throw", "thus", "time", "to", "today", "together", "tonight", "too", "top",
            "total", "tough", "toward", "town", "trade", "traditional", "training", "travel", "treat", "treatment",
            "tree", "trial", "trip", "trouble", "true", "truth", "try", "turn", "tv", "two",
            "type", "under", "understand", "unit", "until", "up", "upon", "us", "use", "usually",
            "value", "various", "very", "victim", "view", "violence", "visit", "voice", "vote", "wait",
            "walk", "wall", "want", "war", "watch", "water", "way", "we", "weapon", "wear",
            "week", "weight", "well", "west", "western", "what", "whatever", "when", "where", "whether",
            "which", "while", "white", "who", "whole", "whom", "whose", "why", "wide", "wife",
            "will", "win", "wind", "window", "wish", "with", "within", "without", "woman", "wonder",
            "word", "work", "worker", "world", "worry", "would", "write", "writer", "wrong", "yard",
            "yeah", "year", "yes", "yet", "you", "young", "your", "yourself"
    ));

    private static boolean isInLexicon(String word) {
        return LEXICON_SET.contains(word);
    }

    private static final Set<String> FREQUENT_BIGRAMS = new HashSet<>(Arrays.asList(
            "how are", "you are", "we are", "they are", "there are", "what are", "where are", "who are",
            "thank you", "thanks for", "good morning", "good evening", "good night", "good day", "have a",
            "to be", "will be", "can be", "could be", "should be", "would be", "have been", "has been", "had been",
            "in the", "on the", "at the", "to the", "for the", "with the", "of the", "from the", "about the",
            "into the", "over the", "under the", "by the", "through the", "as well", "as soon", "as possible",
            "as per", "look at", "look for", "based on", "part of", "one of", "out of", "due to", "according to",
            "in order", "so that", "because of", "kind regards", "please find", "attached here", "let me", "let us",
            "want to", "need to", "like to", "have to", "going to", "able to", "used to", "try to", "right now",
            "each other", "every day", "next week", "last week", "next month", "last year", "don't know",
            "see you", "take care", "well done", "best regards", "best wishes", "no problem", "not only", "but also",
            "star note", "starnote", "open notes", "opennotes", "written on", "write a", "wrote a", "for your",
            "is a", "this is", "that is", "there is", "it is", "what is", "where is", "who is", "which is"
    ));

    private static boolean isFrequentBigram(String bigram) {
        return FREQUENT_BIGRAMS.contains(bigram);
    }

    private static final Set<String> DOCTOR_TERMS = new HashSet<>(Arrays.asList(
            "rx", "tab", "cap", "syp", "inj", "mg", "ml", "mcg", "gm", "kg",
            "od", "bd", "bid", "tid", "qid", "po", "prn", "sos", "stat", "hs", "ac", "pc",
            "bp", "hr", "pulse", "temp", "spo2", "ecg", "cxr", "lab", "cbc", "lft", "kft",
            "dx", "hx", "sx", "tx", "dr", "doc", "doctor", "patient", "clinic", "hospital",
            "fever", "cough", "cold", "pain", "headache", "throat", "chest", "vomiting", "diarrhea",
            "paracetamol", "amoxicillin", "ibuprofen", "cetirizine", "azithromycin", "pantoprazole",
            "metformin", "atorvastatin", "omeprazole", "ciprofloxacin", "doxycycline", "aspirin",
            "pcm", "ibugesic", "pan", "augmentin", "zithromax", "allegra", "crocin", "dolo",
            "calpol", "combiflam", "saridon", "vicks", "benadryl", "ascoril", "gargle",
            "syrup", "drops", "tablet", "capsule", "injection", "ointment", "cream", "lotion",
            "dose", "daily", "days", "weeks", "months", "review", "consult", "referral",
            "diagnosis", "treatment", "prescription", "symptoms", "history", "examination"
    ));

    private static boolean isDoctorTerm(String word) {
        return DOCTOR_TERMS.contains(word);
    }

    private static Map<String, String> getCarelessHandwritingReplacements() {
        Map<String, String> m = new HashMap<>();
        m.put("ave", "are");
        m.put("youv", "your");
        m.put("youn", "your");
        m.put("yous", "your");
        m.put("fov", "for");
        m.put("fon", "for");
        m.put("fnom", "from");
        m.put("fvom", "from");
        m.put("fcom", "from");
        m.put("theiv", "their");
        m.put("thein", "their");
        m.put("theve", "there");
        m.put("thene", "there");
        m.put("heve", "here");
        m.put("wheve", "where");
        m.put("ovev", "over");
        m.put("evevy", "every");
        m.put("nevev", "never");
        m.put("wvite", "write");
        m.put("wnite", "write");
        m.put("woste", "wrote");
        m.put("wnote", "wrote");
        m.put("gveat", "great");
        m.put("gceat", "great");
        m.put("suve", "sure");
        m.put("sune", "sure");
        m.put("puve", "pure");
        m.put("wosk", "work");
        m.put("wonk", "work");
        m.put("wosld", "world");
        m.put("wonld", "world");
        m.put("wosd", "word");
        m.put("wond", "word");
        m.put("pvint", "print");
        m.put("cveate", "create");
        m.put("stave", "stare");
        m.put("shave", "share");
        m.put("fist", "first");
        m.put("undev", "under");
        m.put("unden", "under");
        m.put("aftev", "after");
        m.put("aften", "after");
        m.put("watev", "water");
        m.put("waten", "water");
        m.put("papev", "paper");
        m.put("papen", "paper");
        m.put("lettev", "letter");
        m.put("letten", "letter");
        m.put("ouy", "our");
        m.put("ouv", "our");
        m.put("weve", "were");
        m.put("mose", "more");
        m.put("cly", "cry");
        m.put("tly", "try");
        m.put("dly", "dry");
        m.put("hureesh", "Suresh");
        m.put("sunesh", "Suresh");
        m.put("suvesh", "Suresh");
        m.put("sures", "Suresh");
        m.put("ubham", "Shubham");
        m.put("12", "R");
        m.put("l2", "R");
        m.put("i2", "R");
        m.put("1z", "R");
        m.put("lz", "R");
        m.put("peop1e", "people");
        m.put("tornorrow", "tomorrow");
        m.put("inportant", "important");
        m.put("neeting", "meeting");
        m.put("problen", "problem");
        m.put("clate", "date");
        m.put("olraw", "draw");
        m.put("olown", "down");
        m.put("cloor", "door");
        m.put("clrive", "drive");
        m.put("vvork", "work");
        m.put("vvould", "would");
        m.put("vvith", "with");
        m.put("vvhat", "what");
        m.put("vvhere", "where");
        m.put("vvhen", "when");
        m.put("cornplete", "complete");
        m.put("pleasc", "please");
        m.put("px", "Rx");
        m.put("bx", "Rx");
        m.put("para", "Paracetamol");
        m.put("paracet", "Paracetamol");
        m.put("pcm", "PCM");
        m.put("amox", "Amox");
        m.put("dolo", "Dolo");
        m.put("crocin", "Crocin");
        m.put("calpol", "Calpol");
        m.put("cetriz", "Cetirizine");
        m.put("panto", "Pantoprazole");
        return m;
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
        float width = Math.max(MIN_WRITING_AREA_AXIS, inkBounds.width());
        float height = Math.max(MIN_WRITING_AREA_AXIS, inkBounds.height());
        // For writing lines, provide ample natural line height so ML Kit does not compress
        // coordinate space or clip letters on large continuous lines.
        float targetHeight = Math.max(160f, height * 1.5f);
        float targetWidth = Math.max(width + 60f, targetHeight * 1.5f);
        float centerX = (inkBounds.left + inkBounds.right) * 0.5f;
        float centerY = (inkBounds.top + inkBounds.bottom) * 0.5f;
        return new RectF(centerX - targetWidth * 0.5f, centerY - targetHeight * 0.5f,
                centerX + targetWidth * 0.5f, centerY + targetHeight * 0.5f);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static DigitalInkRecognitionModelIdentifier getModelIdentifier(String language) {
        String[] tags;
        if ("ENGLISH".equalsIgnoreCase(language)) {
            tags = new String[]{"en-IN", "en", "en-US", "en-GB"};
        } else if ("HINDI".equalsIgnoreCase(language)) {
            tags = new String[]{"hi", "hi-IN", "mr-IN", "mr"};
        } else {
            // Marathi default
            tags = new String[]{"mr-IN", "mr", "hi", "hi-IN"};
        }
        for (String tag : tags) {
            try {
                DigitalInkRecognitionModelIdentifier id =
                        DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
                if (id != null) return id;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static String languageTag(String language) {
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
