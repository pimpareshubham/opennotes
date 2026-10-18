package org.opennotes.app.util;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;

import org.opennotes.app.ai.GeometricDigitDisambiguator;
import org.opennotes.app.ai.InkNormalizer;
import org.opennotes.app.ai.InkPreprocessor;
import org.opennotes.app.ai.SemanticStrokeClusterer;
import org.opennotes.app.db.AppSettings;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device handwriting recognition using Google ML Kit's Digital Ink Recognition models.
 * Operates directly and accurately on handwritten strokes with natural coordinates,
 * without artificial bounding area limits, median thresholds, or false cross-script fallbacks.
 */
public final class HandwritingRecognitionEngine {
    private static final String TAG = "HandwritingRecognition";
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

    public static final class WordItem {
        public final String text;
        public final RectF bounds;
        public final String fontKey;

        public WordItem(String text, RectF bounds, String fontKey) {
            this.text = text;
            this.bounds = bounds;
            this.fontKey = fontKey;
        }
    }

    public static final class RecognitionResult {
        public final String text;
        public final String language;
        public final RectF bounds;
        public final float fontSize;
        public final List<String> alternatives;
        public final float angleDegrees;
        public final float startX;
        public final float startY;
        public final List<WordItem> wordItems;

        public RecognitionResult(String text, String language, RectF bounds, float fontSize,
                                 List<String> alternatives, float angleDegrees, float startX, float startY,
                                 List<WordItem> wordItems) {
            this.text = text;
            this.language = language;
            this.bounds = bounds;
            this.fontSize = fontSize;
            this.alternatives = Collections.unmodifiableList(new ArrayList<>(alternatives));
            this.angleDegrees = angleDegrees;
            this.startX = startX;
            this.startY = startY;
            this.wordItems = (wordItems != null && !wordItems.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(wordItems))
                    : Collections.emptyList();
        }

        public RecognitionResult(String text, String language, RectF bounds, float fontSize,
                                 List<String> alternatives, float angleDegrees, float startX, float startY) {
            this(text, language, bounds, fontSize, alternatives, angleDegrees, startX, startY, null);
        }

        public RecognitionResult(String text, String language, RectF bounds, float fontSize,
                                 List<String> alternatives, float angleDegrees) {
            this(text, language, bounds, fontSize, alternatives, angleDegrees,
                    bounds != null ? bounds.left : 0f,
                    bounds != null ? bounds.bottom - 4f : 0f, null);
        }

        public RecognitionResult(String text, String language, RectF bounds, float fontSize,
                                 List<String> alternatives) {
            this(text, language, bounds, fontSize, alternatives, 0f);
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

    public interface SingleModelStatusCallback {
        void onStatus(boolean downloaded);
    }

    public interface ModelResolutionCallback {
        void onResolved(DigitalInkRecognitionModel model, String tag);
        void onNoneReady(String preferredTag);
    }

    public static String[] getCandidateTags(String language) {
        if ("ENGLISH".equalsIgnoreCase(language)) {
            return new String[]{"en-US", "en-IN", "en", "en-GB"};
        } else if ("HINDI".equalsIgnoreCase(language)) {
            return new String[]{"hi", "hi-IN", "mr-IN", "mr"};
        } else if ("AUTO".equalsIgnoreCase(language) || "AUTO_DETECT".equalsIgnoreCase(language)) {
            return new String[]{"mr-IN", "mr", "hi", "hi-IN", "en-US", "en-IN"};
        } else {
            // Marathi default
            return new String[]{"mr-IN", "mr", "hi", "hi-IN"};
        }
    }

    /**
     * Determines whether handwriting strokes contain a Devanagari Shirorekha headline.
     */
    public static boolean hasShirorekha(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return false;
        RectF totalBounds = boundsOf(strokes);
        if (totalBounds == null || totalBounds.width() < 12f || totalBounds.height() < 12f) return false;

        float totalW = totalBounds.width();
        float totalH = totalBounds.height();

        for (Stroke s : strokes) {
            if (s == null) continue;
            RectF sb = s.getBounds();
            if (sb == null) continue;

            if (sb.width() >= 18f && sb.width() >= totalW * 0.30f) {
                float aspect = sb.width() / Math.max(1f, sb.height());
                if (aspect >= 2.2f && sb.top <= totalBounds.top + totalH * 0.48f) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Converts stray Devanagari numerals to standard universal Arabic digits (0-9).
     */
    public static String convertDevanagariDigitsToArabic(String text) {
        if (text == null) return "";
        char[] devDigits = {'०', '१', '२', '३', '४', '५', '६', '७', '८', '९'};
        char[] arDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean replaced = false;
            for (int d = 0; d < 10; d++) {
                if (c == devDigits[d]) {
                    sb.append(arDigits[d]);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Resolve a ready, downloaded model for the language across candidate tags.
     */
    public static void resolveReadyModel(String language, ModelResolutionCallback callback) {
        if (callback == null) return;
        WORKER.execute(() -> {
            String[] tags = getCandidateTags(language);
            RemoteModelManager manager = RemoteModelManager.getInstance();
            checkCandidateAt(tags, 0, manager, callback);
        });
    }

    private static void checkCandidateAt(String[] tags, int index, RemoteModelManager manager, ModelResolutionCallback callback) {
        if (index >= tags.length) {
            String fallbackTag = tags.length > 0 ? tags[0] : "mr-IN";
            MAIN.post(() -> callback.onNoneReady(fallbackTag));
            return;
        }
        String tag = tags[index];
        try {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
            if (id == null) {
                checkCandidateAt(tags, index + 1, manager, callback);
                return;
            }
            DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
            manager.isModelDownloaded(model).addOnCompleteListener(t -> {
                if (t.isSuccessful() && Boolean.TRUE.equals(t.getResult())) {
                    MAIN.post(() -> callback.onResolved(model, tag));
                } else {
                    WORKER.execute(() -> checkCandidateAt(tags, index + 1, manager, callback));
                }
            });
        } catch (Exception e) {
            checkCandidateAt(tags, index + 1, manager, callback);
        }
    }

    /** Check if offline models are installed on the device across candidate tags. */
    public static void checkModelsDownloaded(Context context, ModelsStatusCallback callback) {
        if (callback == null) return;
        isModelDownloaded("ENGLISH", enReady -> {
            isModelDownloaded("MARATHI", mrReady -> {
                callback.onStatus(enReady, mrReady);
            });
        });
    }

    /** Check offline download status of all supported language models (English, Marathi, Hindi). */
    public static void checkAllModelsDownloaded(Context context, AllModelsStatusCallback callback) {
        if (callback == null) return;
        isModelDownloaded("ENGLISH", enReady -> {
            isModelDownloaded("MARATHI", mrReady -> {
                isModelDownloaded("HINDI", hiReady -> {
                    callback.onStatus(enReady, mrReady, hiReady);
                });
            });
        });
    }

    public static void isModelDownloaded(String language, SingleModelStatusCallback callback) {
        if (callback == null) return;
        resolveReadyModel(language, new ModelResolutionCallback() {
            @Override
            public void onResolved(DigitalInkRecognitionModel model, String tag) {
                MAIN.post(() -> callback.onStatus(true));
            }

            @Override
            public void onNoneReady(String preferredTag) {
                MAIN.post(() -> callback.onStatus(false));
            }
        });
    }

    public static void downloadModel(String language, ModelDownloadCallback callback) {
        WORKER.execute(() -> {
            RemoteModelManager manager = RemoteModelManager.getInstance();
            String[] tags = getCandidateTags(language);
            downloadCandidateWithFallback(manager, tags, 0, language, callback);
        });
    }

    public static void downloadLanguageModel(Context context, String preferredLanguage,
                                             ModelDownloadCallback callback) {
        downloadModel(preferredLanguage, callback);
    }

    private static void downloadCandidateWithFallback(RemoteModelManager manager,
                                                     String[] tags, int index,
                                                     String language,
                                                     ModelDownloadCallback callback) {
        if (index >= tags.length) {
            postModelDownloadResult(callback, new IllegalArgumentException(
                    "No compatible handwriting model found for " + language));
            return;
        }

        String tag = tags[index];
        final DigitalInkRecognitionModelIdentifier id;
        try {
            id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
        } catch (Exception e) {
            downloadCandidateWithFallback(manager, tags, index + 1, language, callback);
            return;
        }
        if (id == null) {
            downloadCandidateWithFallback(manager, tags, index + 1, language, callback);
            return;
        }

        DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
        manager.download(model, new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> postModelDownloadResult(callback, null))
                .addOnFailureListener(error -> {
                    WORKER.execute(() -> downloadCandidateWithFallback(manager, tags, index + 1, language, callback));
                });
    }

    private static void postModelDownloadResult(ModelDownloadCallback callback, Exception error) {
        if (callback != null) {
            MAIN.post(() -> callback.onComplete(error));
        }
    }

    public static void recognizeStrokes(Context context, List<Stroke> strokes,
                                        String preferredLanguage, Callback callback) {
        recognizeStrokes(context, strokes, preferredLanguage, "", callback);
    }

    public static void recognizeStrokes(Context context, List<Stroke> strokes,
                                        String preferredLanguage, String preContext,
                                        Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        recognizeMultiHypothesis(context, strokes, preferredLanguage, preContext, false, callback);
    }

    public static void recognizeStrokesWithFilteredSuggestions(Context context,
                                                               List<Stroke> strokes,
                                                               String preferredLanguage,
                                                               String preContext,
                                                               Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        recognizeMultiHypothesis(context, strokes, preferredLanguage, preContext, true, callback);
    }

    public static void recognizeStrokeLine(Context context,
                                           SemanticStrokeClusterer.StrokeLine line,
                                           String preferredLanguage,
                                           String preContext,
                                           Callback callback) {
        if (context == null || line == null || line.strokes == null || line.strokes.isEmpty() || callback == null) return;
        recognizeStrokeLineInternal(context, line, preferredLanguage, preContext, false, callback);
    }

    public static void recognizeStrokeLineWithFilteredSuggestions(Context context,
                                                                 SemanticStrokeClusterer.StrokeLine line,
                                                                 String preferredLanguage,
                                                                 String preContext,
                                                                 Callback callback) {
        if (context == null || line == null || line.strokes == null || line.strokes.isEmpty() || callback == null) return;
        recognizeStrokeLineInternal(context, line, preferredLanguage, preContext, true, callback);
    }

    private static void recognizeStrokeLineInternal(Context context,
                                                    SemanticStrokeClusterer.StrokeLine line,
                                                    String preferredLanguage,
                                                    String preContext,
                                                    boolean includeReviewSuggestions,
                                                    Callback callback) {
        recognizeMultiHypothesis(context, line.strokes, preferredLanguage, preContext, includeReviewSuggestions, callback);
    }

    private static void recognizeMultiHypothesis(Context context,
                                                 List<Stroke> strokes,
                                                 String preferredLanguage,
                                                 String preContext,
                                                 boolean includeReviewSuggestions,
                                                 Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        final String contextText = preContext != null ? preContext.trim() : "";

        final float writingAngle = InkNormalizer.detectWritingAngleDegrees(strokes);

        // Language routing: respect the user's selected language
        final String detectedLang;
        if ("ENGLISH".equalsIgnoreCase(preferredLanguage)) {
            detectedLang = "ENGLISH";
        } else if ("HINDI".equalsIgnoreCase(preferredLanguage)) {
            detectedLang = "HINDI";
        } else if ("MARATHI".equalsIgnoreCase(preferredLanguage)) {
            detectedLang = "MARATHI";
        } else {
            // Default to Marathi which natively recognizes Devanagari, digits, and punctuation
            detectedLang = "MARATHI";
        }

        // Dedicated geometric arrow recognizer for standalone arrows
        String standaloneArrow = recognizeStandaloneArrow(strokes);
        if (standaloneArrow != null) {
            RectF arrowBounds = boundsOf(strokes);
            if (arrowBounds != null) {
                float arrowSize = Math.max(26f, Math.min(65f,
                        Math.max(arrowBounds.width(), arrowBounds.height()) * 0.62f));
                MAIN.post(() -> callback.onSuccess(new RecognitionResult(
                        standaloneArrow, detectedLang, new RectF(arrowBounds), arrowSize,
                        Collections.singletonList(standaloneArrow), 0f,
                        arrowBounds.left, arrowBounds.bottom - 4f)));
                return;
            }
        }

        // Deskew if baseline is tilted
        final float primaryPassAngle = (Math.abs(writingAngle) >= 3.5f) ? writingAngle : 0f;
        List<Stroke> primaryStrokes = (primaryPassAngle != 0f)
                ? transformedStrokeCopies(strokes, primaryPassAngle, false, false)
                : strokes;

        executeSingleRecognitionPass(context, primaryStrokes, strokes, primaryPassAngle, detectedLang, contextText, new Callback() {
            @Override
            public void onSuccess(RecognitionResult primaryResult) {
                if (primaryResult != null && primaryResult.text != null && !primaryResult.text.trim().isEmpty()) {
                    callback.onSuccess(primaryResult);
                } else {
                    callback.onFailure(new IllegalArgumentException("Could not decipher handwriting. Your ink was kept."));
                }
            }

            @Override
            public void onFailure(Exception error) {
                callback.onFailure(error);
            }
        });
    }

    private static void executeSingleRecognitionPass(Context context,
                                                     List<Stroke> strokesToRecognize,
                                                     List<Stroke> originalStrokes,
                                                     float angleDegrees,
                                                     String preferredLanguage,
                                                     String preContext,
                                                     Callback callback) {
        final List<Stroke> preparedStrokes = InkPreprocessor.prepare(strokesToRecognize);
        if (preparedStrokes.isEmpty()) {
            postFailure(callback, new IllegalArgumentException(
                    "No usable handwriting points were found. Your ink was kept."));
            return;
        }
        final RectF bounds = boundsOf(preparedStrokes);
        if (bounds == null) {
            postFailure(callback, new IllegalArgumentException(
                    "No usable handwriting points were found. Your ink was kept."));
            return;
        }

        resolveReadyModel(preferredLanguage, new ModelResolutionCallback() {
            @Override
            public void onResolved(DigitalInkRecognitionModel model, String tag) {
                runRecognition(context, model, preparedStrokes, originalStrokes, angleDegrees,
                        preferredLanguage, preContext, bounds, callback);
            }

            @Override
            public void onNoneReady(String preferredTag) {
                DigitalInkRecognitionModelIdentifier identifier = getModelIdentifier(preferredLanguage);
                if (identifier == null) {
                    postFailure(callback, new IllegalArgumentException(
                            "Handwriting recognition is not available for " + preferredLanguage));
                    return;
                }
                DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(identifier).build();
                RemoteModelManager manager = RemoteModelManager.getInstance();
                String tag = preferredTag != null ? preferredTag : identifier.getLanguageSubtag();

                MAIN.post(() -> {
                    if (context != null) {
                        android.widget.Toast.makeText(context,
                                "Downloading " + languageName(preferredLanguage) + " handwriting model (~18 MB). Please wait a moment...",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });

                downloadThenRecognize(context, manager, model, tag,
                        preparedStrokes, originalStrokes, angleDegrees,
                        preferredLanguage, preContext, bounds, callback);
            }
        });
    }

    public static boolean isFatalError(Throwable error) {
        if (error == null) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelDownloadCancellationException) return true;
            String msg = cause.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("download")
                        || lower.contains("network")
                        || lower.contains("connection")
                        || lower.contains("offline")
                        || lower.contains("internet")
                        || lower.contains("not installed")
                        || lower.contains("model not found")
                        || lower.contains("not downloaded")
                        || lower.contains("failed to schedule")
                        || lower.contains("play services")
                        || lower.contains("google play")
                        || lower.contains("api exception")
                        || lower.contains("cancelled")
                        || lower.contains("canceled")
                        || lower.contains("storage")
                        || lower.contains("space")) {
                    return true;
                }
            }
            String className = cause.getClass().getName();
            if (className.contains("MlKitException")
                    || className.contains("ApiException")
                    || className.contains("UnavailableException")) {
                if (!isEmptyRecognitionFailure(cause)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isEmptyRecognitionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                String msg = cause.getMessage().toLowerCase();
                if (msg.contains("no handwriting text was recognized")
                        || msg.contains("no usable handwriting points")
                        || msg.contains("returned no readable text")
                        || msg.contains("could not decipher handwriting")
                        || msg.contains("empty line")
                        || msg.contains("returned an empty line")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static RecognitionResult disambiguateDigits(Context context, RecognitionResult result, List<Stroke> strokes) {
        if (result == null) return null;
        List<String> alternatives = new ArrayList<>();
        if (result.text != null && !result.text.trim().isEmpty()) alternatives.add(result.text);
        String geometricDigits = GeometricDigitDisambiguator.disambiguateDigits(result.text, strokes);
        if (geometricDigits != null && !geometricDigits.equals(result.text)
                && !alternatives.contains(geometricDigits)) {
            alternatives.add(geometricDigits);
        }
        for (String candidate : result.alternatives) {
            if (candidate != null && !candidate.trim().isEmpty() && !alternatives.contains(candidate)) {
                alternatives.add(candidate);
            }
        }
        return new RecognitionResult(result.text, result.language, result.bounds, result.fontSize,
                alternatives, result.angleDegrees, result.startX, result.startY, result.wordItems);
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

    public static float estimateWritingAngleDegrees(List<Stroke> strokes) {
        return InkNormalizer.detectWritingAngleDegrees(strokes);
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
                                              String tag, List<Stroke> strokesToRecognize,
                                              List<Stroke> originalStrokes, float angleDegrees,
                                              String language, String preContext,
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

        downloadTask
                .addOnSuccessListener(unused -> {
                    removeCompletedDownload(tag, downloadTask);
                    WORKER.execute(() -> runRecognition(
                            context, model, strokesToRecognize, originalStrokes, angleDegrees, language, preContext, bounds, callback));
                })
                .addOnFailureListener(error -> {
                    removeCompletedDownload(tag, downloadTask);
                    postFailure(callback, error);
                });
    }

    private static void removeCompletedDownload(String tag, Task<Void> downloadTask) {
        synchronized (MODEL_DOWNLOADS) {
            if (MODEL_DOWNLOADS.get(tag) == downloadTask) {
                MODEL_DOWNLOADS.remove(tag);
            }
        }
    }

    private static void runRecognition(Context context, DigitalInkRecognitionModel model,
                                       List<Stroke> strokesToRecognize, List<Stroke> originalStrokes,
                                       float angleDegrees, String language,
                                       String preContext, RectF bounds, Callback callback) {
        WORKER.execute(() -> buildInkAndRecognize(
                context, model, strokesToRecognize, originalStrokes, angleDegrees, language, preContext, bounds, callback));
    }

    private static void buildInkAndRecognize(Context context, DigitalInkRecognitionModel model,
                                             List<Stroke> strokesToRecognize, List<Stroke> originalStrokes,
                                             float angleDegrees, String language,
                                             String preContext, RectF bounds,
                                             Callback callback) {
        try {
            long firstInkTime = Long.MAX_VALUE;
            for (Stroke source : strokesToRecognize) {
                if (source == null) continue;
                List<DrawingPoint> points = source.getRecognitionPoints();
                if (points == null || points.isEmpty()) points = source.points;
                if (points == null) continue;
                for (DrawingPoint point : points) {
                    if (point != null && point.timeMillis >= 0L) {
                        firstInkTime = Math.min(firstInkTime, point.timeMillis);
                    }
                }
            }
            boolean hasCapturedTimes = firstInkTime != Long.MAX_VALUE;

            Ink.Builder inkBuilder = Ink.builder();
            long currentTime = 0L;
            int addedStrokes = 0;

            for (int sIdx = 0; sIdx < strokesToRecognize.size(); sIdx++) {
                Stroke source = strokesToRecognize.get(sIdx);
                if (source == null) continue;
                List<DrawingPoint> inputPoints = source.getRecognitionPoints();
                if (inputPoints == null || inputPoints.isEmpty()) inputPoints = source.points;
                if (inputPoints == null || inputPoints.isEmpty()) continue;

                if (addedStrokes > 0 && !hasCapturedTimes) {
                    currentTime += 130L;
                }

                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                int addedPoints = 0;
                for (DrawingPoint point : inputPoints) {
                    if (point == null || !isFinite(point.x) || !isFinite(point.y)) continue;
                    if (hasCapturedTimes && point.timeMillis >= firstInkTime) {
                        long capturedRelativeTime = point.timeMillis - firstInkTime;
                        currentTime = capturedRelativeTime > currentTime || addedPoints == 0 && addedStrokes == 0
                                ? capturedRelativeTime : currentTime + 1L;
                    } else if (addedPoints > 0 || addedStrokes > 0) {
                        currentTime += 12L;
                    }

                    // Natural coordinates directly into ML Kit's digital ink model
                    strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, currentTime));
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

            // Unconstrained, direct recognition pass without artificial bounding area limits or fallback chaining
            recognizer.recognize(ink)
                    .addOnSuccessListener(result -> {
                        String text = firstCandidate(result);
                        if (text.trim().isEmpty()) {
                            recognizer.close();
                            postFailure(callback, new IllegalArgumentException(
                                    "Could not decipher handwriting. Your ink was kept."));
                        } else {
                            completeRecognition(context, strokesToRecognize, originalStrokes, angleDegrees,
                                    recognizer, result, language, preContext, bounds, callback);
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

    private static String firstCandidate(com.google.mlkit.vision.digitalink.common.RecognitionResult result) {
        if (result == null || result.getCandidates().isEmpty()) return "";
        String text = result.getCandidates().get(0).getText();
        return text == null ? "" : text;
    }

    private static void completeRecognition(Context context,
                                            List<Stroke> strokesToRecognize,
                                            List<Stroke> originalStrokes,
                                            float angleDegrees,
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
            if (alternatives.isEmpty()) {
                postFailure(callback, new IllegalArgumentException("The model returned no readable text."));
                return;
            }

            // Candidate 0 is ML Kit's highest confidence prediction. Preserve it untouched!
            String text = alternatives.get(0);

            // Script/Language classification:
            String lang = isLatin(language) ? "ENGLISH"
                    : ("HINDI".equalsIgnoreCase(language) ? "HINDI" : "MARATHI");
            if (containsDevanagari(text)) {
                lang = "HINDI".equalsIgnoreCase(language) ? "HINDI" : "MARATHI";
            } else if (text.matches("^[0-9\\s.,\\-+]+$") || text.matches("^[a-zA-Z0-9\\s.,\\-+!?'\"()]+$")) {
                lang = "ENGLISH";
            }

            List<Stroke> srcStrokes = (originalStrokes != null && !originalStrokes.isEmpty())
                    ? originalStrokes : strokesToRecognize;
            RectF origBounds = boundsOf(srcStrokes);
            if (origBounds == null) origBounds = bounds;

            float fontSize = Math.max(26f, Math.min(65f, origBounds.height() * 0.85f));

            float startX = origBounds.left;
            float startY = origBounds.bottom - 4f;

            if (angleDegrees <= -50f && angleDegrees >= -130f) {
                // Vertical upward writing (e.g. -90°)
                startX = origBounds.right - Math.max(4f, fontSize * 0.15f);
                startY = origBounds.bottom - 4f;
            } else if (angleDegrees >= 50f && angleDegrees <= 130f) {
                // Vertical downward writing (e.g. +90°)
                startX = origBounds.left + Math.max(4f, fontSize * 0.15f);
                startY = origBounds.top + 4f;
            }

            final float finalFontSize = fontSize;
            final String postText = text;
            final String finalLang = lang;
            final RectF finalBounds = new RectF(origBounds);
            final float finalStartX = startX;
            final float finalStartY = startY;

            RecognitionResult initialResult = new RecognitionResult(postText, finalLang, finalBounds, finalFontSize,
                    alternatives, 0f, finalStartX, finalStartY);

            RecognitionResult reconciled = disambiguateDigits(context, initialResult, srcStrokes);

            MAIN.post(() -> callback.onSuccess(reconciled != null ? reconciled : initialResult));
        } finally {
            recognizer.close();
        }
    }

    private static RectF rawBounds(List<DrawingPoint> points) {
        if (points == null || points.isEmpty()) return null;
        RectF bounds = null;
        for (DrawingPoint point : points) {
            if (point == null || !isFinite(point.x) || !isFinite(point.y)) continue;
            if (bounds == null) bounds = new RectF(point.x, point.y, point.x, point.y);
            else bounds.union(point.x, point.y);
        }
        return bounds;
    }

    private static boolean containsDevanagari(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.DEVANAGARI) return true;
        }
        return false;
    }

    private static String recognizeStandaloneArrow(List<Stroke> input) {
        if (input == null) return null;
        List<List<DrawingPoint>> parts = new ArrayList<>(input.size());
        RectF bounds = null;
        for (Stroke stroke : input) {
            if (stroke == null) continue;
            List<DrawingPoint> points = stroke.getRecognitionPoints();
            if (points == null || points.size() < 2) return null;
            RectF partBounds = rawBounds(points);
            if (partBounds == null) return null;
            if (bounds == null) bounds = new RectF(partBounds);
            else bounds.union(partBounds);
            parts.add(points);
        }
        if (parts.size() < 2 || parts.size() > 3 || bounds == null) return null;

        float majorAxis = Math.max(bounds.width(), bounds.height());
        float minorAxis = Math.min(bounds.width(), bounds.height());
        if (majorAxis < 22f || majorAxis / Math.max(1f, minorAxis) < 1.35f) return null;

        int shaftIndex = -1;
        float shaftLength = 0f;
        for (int i = 0; i < parts.size(); i++) {
            List<DrawingPoint> points = parts.get(i);
            DrawingPoint first = points.get(0);
            DrawingPoint last = points.get(points.size() - 1);
            float chord = pointDistance(first, last);
            float arc = strokeArcLength(points);
            if (chord >= majorAxis * 0.56f && arc > 0f
                    && chord / arc >= 0.68f && chord > shaftLength) {
                shaftIndex = i;
                shaftLength = chord;
            }
        }
        if (shaftIndex < 0) return null;

        List<DrawingPoint> shaft = parts.get(shaftIndex);
        DrawingPoint shaftStart = shaft.get(0);
        DrawingPoint shaftEnd = shaft.get(shaft.size() - 1);
        float tolerance = Math.max(5f, majorAxis * 0.16f);
        for (int tipAtEnd = 0; tipAtEnd < 2; tipAtEnd++) {
            DrawingPoint tip = (tipAtEnd == 1) ? shaftEnd : shaftStart;
            DrawingPoint base = (tipAtEnd == 1) ? shaftStart : shaftEnd;
            if (hasArrowhead(parts, shaftIndex, tip, tolerance, majorAxis)) {
                return arrowGlyph(tip.x - base.x, tip.y - base.y);
            }
        }
        return null;
    }

    private static boolean hasArrowhead(List<List<DrawingPoint>> parts, int shaftIndex,
                                        DrawingPoint tip, float tolerance, float majorAxis) {
        List<float[]> arms = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i == shaftIndex) continue;
            List<DrawingPoint> points = parts.get(i);
            DrawingPoint first = points.get(0);
            DrawingPoint last = points.get(points.size() - 1);
            float dFirst = pointDistance(first, tip);
            float dLast = pointDistance(last, tip);
            if (dFirst <= tolerance) {
                float[] arm = getArmFromTip(points, first, tolerance, majorAxis);
                if (arm != null) arms.add(arm);
            } else if (dLast <= tolerance) {
                float[] arm = getArmFromTip(points, last, tolerance, majorAxis);
                if (arm != null) arms.add(arm);
            } else {
                for (int pIdx = 1; pIdx < points.size() - 1; pIdx++) {
                    DrawingPoint mid = points.get(pIdx);
                    if (pointDistance(mid, tip) <= tolerance) {
                        float[] armA = armVector(mid, first, majorAxis);
                        float[] armB = armVector(mid, last, majorAxis);
                        if (armA != null && armB != null && areOppositeArrowArms(armA, armB)) return true;
                    }
                }
            }
        }
        return arms.size() >= 2 && areOppositeArrowArms(arms.get(0), arms.get(1));
    }

    private static float[] getArmFromTip(List<DrawingPoint> points, DrawingPoint tip,
                                         float tolerance, float majorAxis) {
        DrawingPoint far = null;
        float maxDist = 0f;
        for (DrawingPoint p : points) {
            float d = pointDistance(p, tip);
            if (d > maxDist) {
                maxDist = d;
                far = p;
            }
        }
        if (far == null || maxDist < majorAxis * 0.14f || maxDist > majorAxis * 0.72f) return null;
        return armVector(tip, far, majorAxis);
    }

    private static float[] armVector(DrawingPoint tip, DrawingPoint far,
                                     float majorAxis) {
        float dx = far.x - tip.x;
        float dy = far.y - tip.y;
        float len = (float) Math.hypot(dx, dy);
        if (len < majorAxis * 0.12f) return null;
        return new float[]{dx / len, dy / len};
    }

    private static boolean areOppositeArrowArms(float[] first, float[] second) {
        float dot = first[0] * second[0] + first[1] * second[1];
        return dot >= -0.2f && dot <= 0.92f;
    }

    private static float strokeArcLength(List<DrawingPoint> points) {
        float total = 0f;
        for (int i = 0; i < points.size() - 1; i++) {
            total += pointDistance(points.get(i), points.get(i + 1));
        }
        return total;
    }

    private static float pointDistance(DrawingPoint first, DrawingPoint second) {
        return (float) Math.hypot(first.x - second.x, first.y - second.y);
    }

    private static String arrowGlyph(float dx, float dy) {
        int sector = Math.floorMod(Math.round((float) Math.atan2(dy, dx) / ((float) Math.PI / 4f)), 8);
        switch (sector) {
            case 0: return "\u2192";
            case 1: return "\u2198";
            case 2: return "\u2193";
            case 3: return "\u2199";
            case 4: return "\u2190";
            case 5: return "\u2196";
            case 6: return "\u2191";
            default: return "\u2197";
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

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static DigitalInkRecognitionModelIdentifier getModelIdentifier(String language) {
        String[] tags = getCandidateTags(language);
        for (String tag : tags) {
            try {
                DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
                if (id != null) return id;
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static String languageTag(String language) {
        if ("ENGLISH".equalsIgnoreCase(language)) return "en-US";
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
