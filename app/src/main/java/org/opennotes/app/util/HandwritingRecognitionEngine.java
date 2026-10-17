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
        } else {
            // Marathi default
            return new String[]{"mr-IN", "mr", "hi", "hi-IN"};
        }
    }

    /**
     * Resolve a ready, downloaded model for the language across candidate tags.
     * If any candidate tag is downloaded, invokes onResolved immediately.
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
            String fallbackTag = tags.length > 0 ? tags[0] : "en-US";
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

    /** Check if offline models are already installed on the device across candidate tags. */
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
            String[] tags = getCandidateTags(language);
            RemoteModelManager manager = RemoteModelManager.getInstance();
            downloadCandidateWithFallback(manager, tags, 0, language, callback);
        });
    }

    /** Pre-download a language model from an explicit setup flow before recognition is used. */
    public static void downloadLanguageModel(Context context, String preferredLanguage,
                                            ModelDownloadCallback callback) {
        downloadModel(preferredLanguage, callback);
    }

    private static void downloadCandidateWithFallback(RemoteModelManager manager,
                                                      String[] tags,
                                                      int index,
                                                      String language,
                                                      ModelDownloadCallback callback) {
        if (index >= tags.length) {
            String langName = languageName(language);
            if (callback != null) {
                MAIN.post(() -> callback.onComplete(new IllegalStateException(
                        "Unable to download " + langName + " handwriting model. Please check internet connection.")));
            }
            return;
        }
        String tag = tags[index];
        try {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
            if (id == null) {
                downloadCandidateWithFallback(manager, tags, index + 1, language, callback);
                return;
            }
            DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
            downloadModelForSetup(manager, model, tag, error -> {
                if (error == null) {
                    if (callback != null) callback.onComplete(null);
                } else {
                    Log.w(TAG, "Download failed for candidate tag " + tag + ": " + (error != null ? error.getMessage() : "") + ", trying next candidate...");
                    WORKER.execute(() -> downloadCandidateWithFallback(manager, tags, index + 1, language, callback));
                }
            });
        } catch (Exception e) {
            downloadCandidateWithFallback(manager, tags, index + 1, language, callback);
        }
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
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
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
        List<SemanticStrokeClusterer.StrokeWord> words = line.words;
        if (words == null || words.size() <= 1) {
            recognizeMultiHypothesis(context, line.strokes, preferredLanguage, preContext, includeReviewSuggestions, callback);
            return;
        }

        boolean hasSymbolOrMixedScript = false;
        int shirorekhaCount = 0;
        int nonShirorekhaCount = 0;

        for (SemanticStrokeClusterer.StrokeWord w : words) {
            if (isRightArrow(w.strokes) || isLeftArrow(w.strokes) || isDigitOne(w.strokes) || isDash(w.strokes) || isBullet(w.strokes)) {
                hasSymbolOrMixedScript = true;
                break;
            }
            if (hasShirorekha(w.strokes)) {
                shirorekhaCount++;
            } else {
                nonShirorekhaCount++;
            }
        }
        if (!hasSymbolOrMixedScript && shirorekhaCount > 0 && nonShirorekhaCount > 0) {
            hasSymbolOrMixedScript = true;
        }

        if (hasSymbolOrMixedScript) {
            recognizeWordsSequentially(context, words, 0, new ArrayList<>(), preferredLanguage, preContext,
                    line.bounds, line.angleDegrees, callback);
        } else {
            recognizeMultiHypothesis(context, line.strokes, preferredLanguage, preContext, includeReviewSuggestions, callback);
        }
    }

    private static void recognizeWordsSequentially(Context context,
                                                   List<SemanticStrokeClusterer.StrokeWord> words,
                                                   int index,
                                                   List<WordItem> accumulatedItems,
                                                   String preferredLanguage,
                                                   String preContext,
                                                   RectF lineBounds,
                                                   float angleDegrees,
                                                   Callback callback) {
        if (index >= words.size()) {
            if (accumulatedItems.isEmpty()) {
                postFailure(callback, new IllegalArgumentException("No text recognized on line."));
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < accumulatedItems.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(accumulatedItems.get(i).text);
            }
            String fullText = sb.toString();
            float fontSize = Math.max(26f, Math.min(65f, lineBounds.height() * 0.85f));
            String lineLang = isLatin(preferredLanguage) ? "ENGLISH"
                    : ("HINDI".equalsIgnoreCase(preferredLanguage) ? "HINDI" : "MARATHI");
            if (fullText.matches("^[0-9\\s.,\\-+]+$") || fullText.matches("^[a-zA-Z0-9\\s.,\\-+!?'\"()]+$")) {
                lineLang = "ENGLISH";
            }
            RecognitionResult result = new RecognitionResult(fullText, lineLang, lineBounds, fontSize,
                    Collections.singletonList(fullText), angleDegrees, lineBounds.left, lineBounds.bottom - 4f, accumulatedItems);
            MAIN.post(() -> callback.onSuccess(result));
            return;
        }

        SemanticStrokeClusterer.StrokeWord word = words.get(index);
        final List<Stroke> wordStrokes = word.strokes;

        if (isRightArrow(wordStrokes)) {
            accumulatedItems.add(new WordItem("->", word.bounds, TypefaceManager.FONT_CAVEAT));
            recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                    preContext, lineBounds, angleDegrees, callback);
            return;
        }
        if (isLeftArrow(wordStrokes)) {
            accumulatedItems.add(new WordItem("<-", word.bounds, TypefaceManager.FONT_CAVEAT));
            recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                    preContext, lineBounds, angleDegrees, callback);
            return;
        }
        if (isDigitOne(wordStrokes)) {
            accumulatedItems.add(new WordItem("1", word.bounds, TypefaceManager.FONT_CAVEAT));
            recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                    preContext, lineBounds, angleDegrees, callback);
            return;
        }
        if (isDash(wordStrokes)) {
            accumulatedItems.add(new WordItem("-", word.bounds, TypefaceManager.FONT_CAVEAT));
            recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                    preContext, lineBounds, angleDegrees, callback);
            return;
        }
        if (isBullet(wordStrokes)) {
            accumulatedItems.add(new WordItem("•", word.bounds, TypefaceManager.FONT_CAVEAT));
            recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                    preContext, lineBounds, angleDegrees, callback);
            return;
        }

        final boolean hasSh = hasShirorekha(wordStrokes);
        String wordLang = preferredLanguage;
        String defaultFont = TypefaceManager.FONT_KALAM;
        if (!hasSh && InkNormalizer.isIndicLanguage(preferredLanguage)) {
            defaultFont = TypefaceManager.FONT_CAVEAT;
        }
        final String expectedFont = defaultFont;

        recognizeStrokes(context, wordStrokes, wordLang, preContext, new Callback() {
            @Override
            public void onSuccess(RecognitionResult wordRes) {
                if (wordRes != null && wordRes.text != null && !wordRes.text.trim().isEmpty()) {
                    String wText = wordRes.text.trim();
                    String fKey = (!hasSh || "ENGLISH".equalsIgnoreCase(wordRes.language))
                            ? TypefaceManager.FONT_CAVEAT : expectedFont;
                    accumulatedItems.add(new WordItem(wText, word.bounds, fKey));
                }
                recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                        preContext, lineBounds, angleDegrees, callback);
            }

            @Override
            public void onFailure(Exception error) {
                recognizeWordsSequentially(context, words, index + 1, accumulatedItems, preferredLanguage,
                        preContext, lineBounds, angleDegrees, callback);
            }
        });
    }

    public static boolean isRightArrow(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty() || strokes.size() > 3) return false;
        RectF b = boundsOf(strokes);
        if (b == null || b.width() < 22f || b.height() < 8f) return false;
        if (b.width() < b.height() * 0.70f) return false;

        for (Stroke s : strokes) {
            if (s == null) continue;
            RectF sb = s.getBounds();
            if (sb != null && sb.width() > b.width() * 0.70f && sb.top <= b.top + b.height() * 0.20f && sb.width() >= sb.height() * 2.5f) {
                return false;
            }
        }

        if (strokes.size() >= 2) {
            Stroke shaft = null;
            for (Stroke s : strokes) {
                if (s == null) continue;
                RectF sb = s.getBounds();
                if (sb != null && sb.width() >= sb.height() * 1.8f && sb.width() >= b.width() * 0.45f) {
                    float relY = (sb.centerY() - b.top) / b.height();
                    if (relY >= 0.20f && relY <= 0.80f) {
                        shaft = s;
                        break;
                    }
                }
            }
            if (shaft != null) {
                RectF shaftBounds = shaft.getBounds();
                boolean hasHeadAtRight = false;
                for (Stroke s : strokes) {
                    if (s == shaft || s == null) continue;
                    RectF hb = s.getBounds();
                    if (hb != null && hb.right >= shaftBounds.right - shaftBounds.width() * 0.40f
                            && hb.centerX() > shaftBounds.centerX()
                            && hb.height() >= b.height() * 0.45f) {
                        hasHeadAtRight = true;
                        break;
                    }
                }
                if (hasHeadAtRight) return true;
            }
        }

        if (strokes.size() == 1) {
            Stroke s = strokes.get(0);
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts != null && pts.size() >= 4) {
                float left50X = b.left + b.width() * 0.50f;
                float right60X = b.left + b.width() * 0.60f;
                float leftMinY = Float.MAX_VALUE, leftMaxY = -Float.MAX_VALUE;
                float rightMinY = Float.MAX_VALUE, rightMaxY = -Float.MAX_VALUE;
                int leftCount = 0, rightCount = 0;
                float sumLeftY = 0f;

                for (DrawingPoint p : pts) {
                    if (p == null) continue;
                    if (p.x <= left50X) {
                        leftMinY = Math.min(leftMinY, p.y);
                        leftMaxY = Math.max(leftMaxY, p.y);
                        sumLeftY += p.y;
                        leftCount++;
                    }
                    if (p.x >= right60X) {
                        rightMinY = Math.min(rightMinY, p.y);
                        rightMaxY = Math.max(rightMaxY, p.y);
                        rightCount++;
                    }
                }

                if (leftCount >= 2 && rightCount >= 2) {
                    float leftH = leftMaxY - leftMinY;
                    float rightH = rightMaxY - rightMinY;
                    float leftCenterY = sumLeftY / leftCount;
                    float relLeftY = (leftCenterY - b.top) / b.height();

                    if (relLeftY >= 0.20f && relLeftY <= 0.80f
                            && leftH <= b.height() * 0.42f
                            && rightH >= b.height() * 0.55f
                            && rightH >= leftH * 1.8f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isLeftArrow(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty() || strokes.size() > 3) return false;
        RectF b = boundsOf(strokes);
        if (b == null || b.width() < 22f || b.height() < 8f) return false;
        if (b.width() < b.height() * 0.70f) return false;

        if (strokes.size() >= 2) {
            Stroke shaft = null;
            for (Stroke s : strokes) {
                if (s == null) continue;
                RectF sb = s.getBounds();
                if (sb != null && sb.width() >= sb.height() * 1.8f && sb.width() >= b.width() * 0.45f) {
                    float relY = (sb.centerY() - b.top) / b.height();
                    if (relY >= 0.20f && relY <= 0.80f) {
                        shaft = s;
                        break;
                    }
                }
            }
            if (shaft != null) {
                RectF shaftBounds = shaft.getBounds();
                for (Stroke s : strokes) {
                    if (s == shaft || s == null) continue;
                    RectF hb = s.getBounds();
                    if (hb != null && hb.left <= shaftBounds.left + shaftBounds.width() * 0.40f
                            && hb.centerX() < shaftBounds.centerX()
                            && hb.height() >= b.height() * 0.45f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isDigitOne(List<Stroke> strokes) {
        if (strokes == null || strokes.size() != 1) return false;
        Stroke s = strokes.get(0);
        if (s == null) return false;
        RectF b = s.getBounds();
        if (b == null) return false;
        return (b.height() >= 25f && b.width() <= 24f && (b.height() / Math.max(1f, b.width())) >= 2.5f);
    }

    public static boolean isDash(List<Stroke> strokes) {
        if (strokes == null || strokes.size() != 1) return false;
        Stroke s = strokes.get(0);
        if (s == null) return false;
        RectF b = s.getBounds();
        if (b == null) return false;
        return (b.width() >= 18f && b.width() <= 95f && b.height() <= 18f && (b.width() / Math.max(1f, b.height())) >= 2.6f);
    }

    public static boolean isBullet(List<Stroke> strokes) {
        if (strokes == null || strokes.size() != 1) return false;
        Stroke s = strokes.get(0);
        if (s == null) return false;
        RectF b = s.getBounds();
        if (b == null) return false;
        return (b.width() <= 18f && b.height() <= 18f && s.points != null && s.points.size() <= 8);
    }

    public static boolean hasShirorekha(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return false;
        RectF b = boundsOf(strokes);
        if (b == null || b.width() < 10f) return false;
        float totalW = b.width();
        for (Stroke s : strokes) {
            if (s == null) continue;
            RectF sb = s.getBounds();
            if (sb != null && (sb.width() > totalW * 0.35f && (sb.width() / Math.max(1f, sb.height())) >= 2.0f)) {
                return true;
            }
        }
        return false;
    }

    private static void recognizeMultiHypothesis(Context context,
                                                 List<Stroke> strokes,
                                                 String preferredLanguage,
                                                 String preContext,
                                                 boolean includeReviewSuggestions,
                                                 Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        final String contextText = preContext == null ? "" : preContext;

        final float writingAngle = InkNormalizer.detectWritingAngleDegrees(strokes);

        // PRIMARY PASS: Always evaluate raw, unrotated handwriting first!
        // Google's Digital Ink ML models are trained on natural human handwriting with natural slant.
        executeSingleRecognitionPass(context, strokes, strokes, 0f, preferredLanguage, contextText, new Callback() {
            @Override
            public void onSuccess(RecognitionResult primaryResult) {
                if (primaryResult != null && !primaryResult.text.trim().isEmpty()) {
                    if (includeReviewSuggestions && primaryResult.alternatives.size() <= 1) {
                        tryAlternativeHypothesis(context, strokes, preferredLanguage, contextText,
                                primaryResult, false, writingAngle, callback);
                    } else {
                        callback.onSuccess(primaryResult);
                    }
                } else {
                    tryAlternativeHypothesis(context, strokes, preferredLanguage, contextText,
                            null, false, writingAngle, callback);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (isFatalError(error)) {
                    callback.onFailure(error);
                    return;
                }
                tryAlternativeHypothesis(context, strokes, preferredLanguage, contextText,
                        null, false, writingAngle, callback);
            }
        });
    }

    private static class AngleHypothesis {
        final List<Stroke> strokes;
        final float angleDegrees;

        AngleHypothesis(List<Stroke> strokes, float angleDegrees) {
            this.strokes = strokes;
            this.angleDegrees = angleDegrees;
        }
    }

    private static void tryAlternativeHypothesis(Context context,
                                                 List<Stroke> originalStrokes,
                                                 String language,
                                                 String preContext,
                                                 RecognitionResult primaryResult,
                                                 boolean primaryWasDeskewed,
                                                 float writingAngle,
                                                 Callback callback) {
        List<AngleHypothesis> hypotheses = new ArrayList<>();

        if (Math.abs(writingAngle) >= 4.0f) {
            hypotheses.add(new AngleHypothesis(
                    transformedStrokeCopies(originalStrokes, writingAngle, false, false), writingAngle));
        }
        // Test common tilt variations: +15°, -15°, +30°, -30°, +45°, -45°, +90°, -90°
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, 15f, false, false), 15f));
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, -15f, false, false), -15f));
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, 30f, false, false), 30f));
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, -30f, false, false), -30f));
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, 45f, false, false), 45f));
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, -45f, false, false), -45f));
        RectF bounds = boundsOf(originalStrokes);
        if (bounds != null && bounds.height() > bounds.width() * 1.5f) {
            hypotheses.add(new AngleHypothesis(
                    transformedStrokeCopies(originalStrokes, 90f, false, false), 90f));
            hypotheses.add(new AngleHypothesis(
                    transformedStrokeCopies(originalStrokes, -90f, false, false), -90f));
        }

        runWaterfall(context, hypotheses, originalStrokes, 0, language, preContext, primaryResult, callback);
    }

    private static void runWaterfall(Context context,
                                     List<AngleHypothesis> queue,
                                     List<Stroke> originalStrokes,
                                     int index,
                                     String language,
                                     String preContext,
                                     RecognitionResult accumulatedResult,
                                     Callback callback) {
        if (index >= queue.size()) {
            if (accumulatedResult != null) {
                callback.onSuccess(accumulatedResult);
            } else if ((preContext == null || preContext.isEmpty()) && !queue.isEmpty()) {
                // Cross-language fallback (Devanagari <-> English) if primary language produced no candidates
                String fallbackLang = "ENGLISH".equalsIgnoreCase(language) ? "MARATHI" : "ENGLISH";
                resolveReadyModel(fallbackLang, new ModelResolutionCallback() {
                    @Override
                    public void onResolved(DigitalInkRecognitionModel model, String tag) {
                        executeSingleRecognitionPass(context, originalStrokes, originalStrokes, 0f, fallbackLang, "", callback);
                    }

                    @Override
                    public void onNoneReady(String preferredTag) {
                        callback.onFailure(new IllegalArgumentException(
                                "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
                    }
                });
                return;
            } else {
                callback.onFailure(new IllegalArgumentException(
                        "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
            }
            return;
        }

        AngleHypothesis hypothesis = queue.get(index);
        if (hypothesis == null || hypothesis.strokes == null || hypothesis.strokes.isEmpty()) {
            runWaterfall(context, queue, originalStrokes, index + 1, language, preContext, accumulatedResult, callback);
            return;
        }

        executeSingleRecognitionPass(context, hypothesis.strokes, originalStrokes, hypothesis.angleDegrees,
                language, preContext, new Callback() {
            @Override
            public void onSuccess(RecognitionResult candidateResult) {
                if (candidateResult != null && !candidateResult.text.trim().isEmpty()) {
                    RecognitionResult merged = mergeCandidates(accumulatedResult, candidateResult);
                    callback.onSuccess(merged);
                } else {
                    runWaterfall(context, queue, originalStrokes, index + 1, language, preContext, accumulatedResult, callback);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (isFatalError(error)) {
                    callback.onFailure(error);
                    return;
                }
                runWaterfall(context, queue, originalStrokes, index + 1, language, preContext, accumulatedResult, callback);
            }
        });
    }

    private static void executeSingleRecognitionPass(Context context,
                                                     List<Stroke> strokes,
                                                     String preferredLanguage,
                                                     String preContext,
                                                     Callback callback) {
        executeSingleRecognitionPass(context, strokes, strokes, 0f, preferredLanguage, preContext, callback);
    }

    private static void executeSingleRecognitionPass(Context context,
                                                     List<Stroke> strokesToRecognize,
                                                     List<Stroke> originalStrokes,
                                                     float angleDegrees,
                                                     String preferredLanguage,
                                                     String preContext,
                                                     Callback callback) {
        final RectF bounds = boundsOf(strokesToRecognize);
        if (bounds == null) {
            postFailure(callback, new IllegalArgumentException(
                    "No usable handwriting points were found. Your ink was kept."));
            return;
        }

        resolveReadyModel(preferredLanguage, new ModelResolutionCallback() {
            @Override
            public void onResolved(DigitalInkRecognitionModel model, String tag) {
                runRecognition(context, model, strokesToRecognize, originalStrokes, angleDegrees,
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
                        strokesToRecognize, originalStrokes, angleDegrees,
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
                primary.fontSize, merged, primary.angleDegrees, primary.startX, primary.startY);
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
     * Estimate the dominant writing baseline angle (in degrees, from -180 to +180) across all points.
     * Uses Minimum Area Bounding Box aspect ratio search and temporal kinematic progression.
     */
    public static float estimateWritingAngleDegrees(List<Stroke> strokes) {
        return InkNormalizer.detectWritingAngleDegrees(strokes);
    }

    private static void requestModelDownloadApproval(Context context, RemoteModelManager manager,
                                                     DigitalInkRecognitionModel model, String tag,
                                                     List<Stroke> strokesToRecognize, List<Stroke> originalStrokes,
                                                     float angleDegrees, String language,
                                                     String preContext,
                                                     RectF bounds, Callback callback) {
        AppSettings settings = AppSettings.getInstance(context);
        settings.setAcceptedHandwritingModelDownload(tag, true);
        downloadThenRecognize(context, manager, model, tag, strokesToRecognize, originalStrokes, angleDegrees,
                language, preContext, bounds, callback);
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
        downloadTask.addOnSuccessListener(ignored -> {
            manager.isModelDownloaded(model)
                    .addOnSuccessListener(isDownloaded -> {
                        removeCompletedDownload(tag, downloadTask);
                        if (isDownloaded) {
                            runRecognition(context, model, strokesToRecognize, originalStrokes, angleDegrees,
                                    language, preContext, bounds, callback);
                        } else {
                            postFailure(callback, new IllegalStateException(
                                     "The " + languageName(language) + " handwriting model download finished but the model is not installed yet. Please try again."));
                        }
                    })
                    .addOnFailureListener(error -> {
                        removeCompletedDownload(tag, downloadTask);
                        postFailure(callback, error);
                    });
        }).addOnFailureListener(error -> {
            removeCompletedDownload(tag, downloadTask);
            String langName = languageName(language);
            String detail = (error != null && error.getMessage() != null) ? error.getMessage() : "Network error";
            postFailure(callback, new IllegalStateException(
                    "Failed to download " + langName + " handwriting model: " + detail + ". Please connect to internet to download the offline model."));
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
        runRecognition(context, model, strokes, strokes, 0f, language, preContext, bounds, callback);
    }

    private static void runRecognition(Context context, DigitalInkRecognitionModel model,
                                       List<Stroke> strokesToRecognize, List<Stroke> originalStrokes,
                                       float angleDegrees, String language, String preContext,
                                       RectF bounds, Callback callback) {
        WORKER.execute(() -> buildInkAndRecognize(
                context, model, strokesToRecognize, originalStrokes, angleDegrees, language, preContext, bounds, callback));
    }

    private static void buildInkAndRecognize(Context context, DigitalInkRecognitionModel model,
                                             List<Stroke> strokesToRecognize, List<Stroke> originalStrokes,
                                             float angleDegrees, String language,
                                             String preContext, RectF bounds,
                                             Callback callback) {
        try {
            float naturalHeight = bounds.height();
            float naturalWidth = bounds.width();

            float charHeight = 0f;
            if (strokesToRecognize != null && !strokesToRecognize.isEmpty()) {
                List<Float> heights = new ArrayList<>();
                for (Stroke s : strokesToRecognize) {
                    if (s != null && s.getBounds() != null && s.getBounds().height() > 4f) {
                        heights.add(s.getBounds().height());
                    }
                }
                if (!heights.isEmpty()) {
                    Collections.sort(heights);
                    int idx = (int) (heights.size() * 0.75f);
                    if (idx >= heights.size()) idx = heights.size() - 1;
                    charHeight = heights.get(idx);
                }
            }
            if (charHeight <= 0f) {
                charHeight = bounds.height();
            }

            float scale = 1.0f;
            if (charHeight > 0f && charHeight < 140f) {
                scale = Math.min(4.5f, 160f / charHeight);
            }

            float scaledW = naturalWidth * scale;
            float scaledH = naturalHeight * scale;
            float padX = Math.max(24f, scaledW * 0.10f);
            float padY = Math.max(24f, scaledH * 0.10f);

            Ink.Builder inkBuilder = Ink.builder();
            long currentTime = 0L;
            int addedStrokes = 0;

            for (int sIdx = 0; sIdx < strokesToRecognize.size(); sIdx++) {
                Stroke source = strokesToRecognize.get(sIdx);
                if (source == null) continue;
                List<DrawingPoint> inputPoints = source.getRecognitionPoints();
                if (inputPoints == null || inputPoints.isEmpty()) inputPoints = source.points;
                if (inputPoints == null || inputPoints.isEmpty()) continue;

                // Natural inter-stroke pen-lift delay between separate strokes (120ms to 200ms)
                if (addedStrokes > 0) {
                    currentTime += 130L;
                }

                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                int addedPoints = 0;
                float lastAddedX = 0f;
                float lastAddedY = 0f;
                long lastPointRawTime = -1L;

                for (DrawingPoint point : inputPoints) {
                    if (point == null || !isFinite(point.x) || !isFinite(point.y)) continue;

                    long stepDelta = 12L; // Default ~80Hz sampling
                    if (point.timeMillis > 0L && lastPointRawTime > 0L) {
                        long rawDelta = point.timeMillis - lastPointRawTime;
                        if (rawDelta > 0L && rawDelta <= 150L) {
                            stepDelta = Math.max(6L, Math.min(40L, rawDelta));
                        }
                    }
                    if (addedPoints > 0) {
                        currentTime += stepDelta;
                    }
                    lastPointRawTime = point.timeMillis;

                    float normX = (point.x - bounds.left) * scale + padX;
                    float normY = (point.y - bounds.top) * scale + padY;

                    strokeBuilder.addPoint(Ink.Point.create(normX, normY, currentTime));
                    lastAddedX = normX;
                    lastAddedY = normY;
                    addedPoints++;
                }

                if (addedPoints == 1) {
                    // For single-tap dots (like anusvar or i-dot), create a micro-offset second point
                    currentTime += 15L;
                    strokeBuilder.addPoint(Ink.Point.create(
                            lastAddedX + 2.0f, lastAddedY + 2.0f, currentTime));
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

            // Primary pass: RecognitionContext with WritingArea and preContext
            RecognitionContext recognitionContext = null;
            try {
                float writingAreaW = Math.max(200f, scaledW + padX * 2.0f);
                float writingAreaH = Math.max(120f, scaledH + padY * 2.0f);
                recognitionContext = RecognitionContext.builder()
                        .setPreContext(preContext != null ? preContext : "")
                        .setWritingArea(new WritingArea(writingAreaW, writingAreaH))
                        .build();
            } catch (Exception e) {
                Log.w(TAG, "Failed to build RecognitionContext, proceeding unconstrained", e);
            }

            Task<com.google.mlkit.vision.digitalink.common.RecognitionResult> primaryTask =
                    recognitionContext != null ? recognizer.recognize(ink, recognitionContext) : recognizer.recognize(ink);

            primaryTask.addOnSuccessListener(result -> {
                        String text = firstCandidate(result);
                        if (text.trim().isEmpty()) {
                            // Secondary fallback pass: unconstrained recognition without WritingArea
                            recognizer.recognize(ink)
                                    .addOnSuccessListener(fallbackResult -> {
                                        String fallbackText = firstCandidate(fallbackResult);
                                        if (fallbackText.trim().isEmpty()) {
                                            recognizer.close();
                                            postFailure(callback, new IllegalArgumentException(
                                                    "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
                                        } else {
                                            completeRecognition(context, strokesToRecognize, originalStrokes, angleDegrees,
                                                    recognizer, fallbackResult, language, preContext, bounds, callback);
                                        }
                                    })
                                    .addOnFailureListener(fallbackError -> {
                                        recognizer.close();
                                        postFailure(callback, fallbackError);
                                    });
                        } else {
                            completeRecognition(context, strokesToRecognize, originalStrokes, angleDegrees,
                                    recognizer, result, language, preContext, bounds, callback);
                        }
                    })
                    .addOnFailureListener(error -> {
                        // Unconstrained fallback if contextual pass encountered error
                        recognizer.recognize(ink)
                                .addOnSuccessListener(fallbackResult -> {
                                    String fallbackText = firstCandidate(fallbackResult);
                                    if (!fallbackText.trim().isEmpty()) {
                                        completeRecognition(context, strokesToRecognize, originalStrokes, angleDegrees,
                                                recognizer, fallbackResult, language, preContext, bounds, callback);
                                    } else {
                                        recognizer.close();
                                        postFailure(callback, error);
                                    }
                                })
                                .addOnFailureListener(unconstrainedErr -> {
                                    recognizer.close();
                                    postFailure(callback, error);
                                });
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
            List<String> rerankedAlternatives = enhanceAndRerankCandidates(context, strokesToRecognize, alternatives, language, preContext);
            String text = rerankedAlternatives.isEmpty() ? "" : rerankedAlternatives.get(0);
            if (text.isEmpty()) {
                postFailure(callback, new IllegalArgumentException("The model returned no readable text."));
                return;
            }

            String lang = isLatin(language) ? "ENGLISH"
                    : ("HINDI".equalsIgnoreCase(language) ? "HINDI" : "MARATHI");
            if (text.matches("^[0-9\\s.,\\-+]+$") || text.matches("^[a-zA-Z0-9\\s.,\\-+!?'\"()]+$")) {
                lang = "ENGLISH";
            }

            List<Stroke> srcStrokes = (originalStrokes != null && !originalStrokes.isEmpty())
                    ? originalStrokes : strokesToRecognize;
            RectF origBounds = boundsOf(srcStrokes);
            if (origBounds == null) origBounds = bounds;

            // Character font size estimation that works naturally at any angle or incline
            float estimatedLineHeight = bounds.height();
            if (srcStrokes != null && !srcStrokes.isEmpty()) {
                List<Float> strokeHeights = new ArrayList<>();
                for (Stroke s : srcStrokes) {
                    if (s != null && s.getBounds() != null && s.getBounds().height() > 4f) {
                        strokeHeights.add(s.getBounds().height());
                    }
                }
                if (!strokeHeights.isEmpty()) {
                    Collections.sort(strokeHeights);
                    float medianStrokeH = strokeHeights.get(strokeHeights.size() / 2);
                    if (bounds.height() > medianStrokeH * 2.0f) {
                        estimatedLineHeight = Math.max(medianStrokeH * 1.35f, 32f);
                    }
                }
            }
            float fontSize = Math.max(26f, Math.min(65f, estimatedLineHeight * 0.85f));

            float startX = origBounds.left;
            float startY = origBounds.bottom - 4f;

            final float finalFontSize = fontSize;
            final String finalText = text;
            final String finalLang = lang;
            final RectF finalBounds = new RectF(origBounds);
            final float finalStartX = origBounds.left;
            final float finalStartY = origBounds.bottom - 4f;

            MAIN.post(() -> callback.onSuccess(
                    new RecognitionResult(finalText, finalLang, finalBounds, finalFontSize,
                            rerankedAlternatives, angleDegrees, finalStartX, finalStartY)));
        } finally {
            recognizer.close();
        }
    }

    private static List<String> rerankDevanagariCandidates(List<Stroke> strokes, List<String> candidates) {
        if (strokes == null || strokes.isEmpty() || candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        // Level strokes horizontally so diacritic geometric analysis aligns with thresholds
        float angle = InkNormalizer.detectWritingAngleDegrees(strokes);
        List<Stroke> leveledStrokes = (Math.abs(angle) >= 6.0f)
                ? transformedStrokeCopies(strokes, angle, false, false)
                : strokes;

        RectF bounds = boundsOf(leveledStrokes);
        if (bounds == null || bounds.height() < 5f) return candidates;

        float totalHeight = bounds.height();
        float totalWidth = bounds.width();
        float upperThreshold = bounds.top + totalHeight * 0.32f;
        float lowerThreshold = bounds.bottom - totalHeight * 0.25f;
        float leftThreshold = bounds.left + totalWidth * 0.38f;
        float rightThreshold = bounds.left + totalWidth * 0.48f;

        boolean hasUpperMatraStroke = false;
        boolean hasKanaStroke = false;
        boolean hasPahiliVelantiStroke = false;
        boolean hasDusriVelantiStroke = false;
        boolean hasLowerUkarStroke = false;
        boolean hasAnusvarDot = false;

        for (Stroke s : leveledStrokes) {
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

            // 1. Shirorekha (Horizontal headline across characters):
            // Wide horizontal bar (width > 38% totalWidth and width/height >= 2.5).
            // A shirorekha is a natural structural feature of Devanagari, NOT an upper matra!
            boolean isShirorekha = (sb.width() > totalWidth * 0.38f && (sb.width() / Math.max(1f, sb.height())) >= 2.5f);
            if (isShirorekha) {
                continue;
            }

            // 2. Anusvar Dot (अनुस्वार - ं): Small dot or short tap in top region
            boolean isDotSize = (sb.width() < totalWidth * 0.25f && sb.height() < totalHeight * 0.25f)
                    && (ptCount <= 8 || arcLen < totalHeight * 0.30f);
            if (isDotSize && sb.bottom <= upperThreshold + totalHeight * 0.10f) {
                hasAnusvarDot = true;
                continue;
            }

            // 3. Upper Matra (मात्रा - े, ै, ो, ौ): Slanted/diagonal line in upper region (NOT the horizontal shirorekha!)
            if (sb.top < upperThreshold && sb.height() >= totalHeight * 0.12f) {
                hasUpperMatraStroke = true;
            }

            // 4. Pahili Velanti (पहिली वेलांटी - ि): Vertical stem on the left of the consonant
            if (sb.right <= leftThreshold && sb.height() >= totalHeight * 0.38f
                    && (sb.height() / Math.max(1f, sb.width())) >= 1.4f) {
                hasPahiliVelantiStroke = true;
            }

            // 5. Kana (काणा - ा) vs Dusri Velanti (दुसरी वेलांटी - ी):
            // Both have a vertical downstroke on the right side of the character.
            // A Kana is straight vertical (no backward arch over the consonant).
            // A Dusri Velanti has an arch at the top curving to the LEFT over the consonant!
            if (sb.left >= rightThreshold && sb.height() >= totalHeight * 0.38f
                    && (sb.height() / Math.max(1f, sb.width())) >= 1.4f) {
                boolean hasLeftwardArch = false;
                if (pts != null && ptCount >= 3) {
                    float stemX = sb.right;
                    float minTopX = stemX;
                    for (DrawingPoint p : pts) {
                        if (p.y <= upperThreshold) {
                            minTopX = Math.min(minTopX, p.x);
                        }
                    }
                    if (minTopX < stemX - totalWidth * 0.22f) {
                        hasLeftwardArch = true;
                    }
                }

                if (hasLeftwardArch) {
                    hasDusriVelantiStroke = true;
                } else {
                    hasKanaStroke = true;
                }
            }

            // 6. Lower Ukar (उकार - ु, ू): Dips below lower baseline
            if (sb.bottom >= lowerThreshold && sb.top > bounds.top + totalHeight * 0.45f) {
                hasLowerUkarStroke = true;
            }
        }

        // Only re-rank if there is a distinct detected feature to avoid perturbing high-confidence base output
        if (!hasUpperMatraStroke && !hasKanaStroke && !hasPahiliVelantiStroke
                && !hasDusriVelantiStroke && !hasLowerUkarStroke && !hasAnusvarDot) {
            return candidates;
        }

        final boolean fUpper = hasUpperMatraStroke;
        final boolean fKana = hasKanaStroke;
        final boolean fPahili = hasPahiliVelantiStroke;
        final boolean fDusri = hasDusriVelantiStroke;
        final boolean fLower = hasLowerUkarStroke;
        final boolean fAnusvar = hasAnusvarDot;

        // Re-score ONLY the legitimate candidates returned by ML Kit! Never inject synthetic strings!
        List<String> pool = new ArrayList<>(candidates);
        pool.sort((c1, c2) -> {
            int score1 = scoreDevanagariMatch(c1, pool.indexOf(c1), fUpper, fKana, fPahili, fDusri, fLower, fAnusvar);
            int score2 = scoreDevanagariMatch(c2, pool.indexOf(c2), fUpper, fKana, fPahili, fDusri, fLower, fAnusvar);
            return Integer.compare(score2, score1);
        });

        return pool;
    }

    private static int scoreDevanagariMatch(String text, int rankIndex, boolean hasUpper, boolean hasKana,
                                            boolean hasPahiliVelanti, boolean hasDusriVelanti,
                                            boolean hasLower, boolean hasAnusvar) {
        if (text == null) return 0;
        // Base confidence from ML Kit's own model: candidate 0 gets 100, candidate 1 gets 85, etc.
        int score = Math.max(0, 100 - rankIndex * 15);

        boolean textHasPureKana = text.contains("\u093e"); // का, बा
        boolean textHasOorAu = containsAny(text, "\u094b", "\u094c"); // को, कौ
        boolean textHasPahiliVelanti = text.contains("\u093f"); // कि, बि
        boolean textHasDusriVelanti = text.contains("\u0940"); // की, बी
        boolean textHasPureUpper = containsAny(text, "\u0947", "\u0948", "\u0945"); // के, कै
        boolean textHasAnusvar = containsAny(text, "\u0902", "\u0901", "\u0903"); // कं, बं
        boolean textHasLowerUkar = containsAny(text, "\u0941", "\u0942", "\u0943"); // कु, कू

        // 1. Pure Kana vs Dusri Velanti (resolves: "sometimes it's adding velanti even though i tried to add a kana")
        if (hasKana && !hasDusriVelanti && !hasPahiliVelanti && !hasUpper) {
            if (textHasPureKana) score += 45;
            if (textHasDusriVelanti || textHasPahiliVelanti) score -= 50;
        } else if (hasDusriVelanti) {
            if (textHasDusriVelanti) score += 45;
            if (textHasPureKana) score -= 40;
        }

        // 2. Pahili Velanti vs Dusri Velanti
        if (hasPahiliVelanti) {
            if (textHasPahiliVelanti) score += 45;
            if (textHasDusriVelanti) score -= 30;
        }

        // 3. Anusvar Dot (कं vs क)
        if (hasAnusvar) {
            if (textHasAnusvar) score += 50;
        } else {
            if (textHasAnusvar) score -= 35;
        }

        // 4. Upper Matra (के, कै vs क)
        if (hasUpper && !hasKana) {
            if (textHasPureUpper) score += 45;
        } else if (!hasUpper && !hasKana) {
            if (textHasPureUpper || textHasOorAu) score -= 45;
        }

        // 5. Kana + Upper Matra (को, कौ)
        if (hasKana && hasUpper) {
            if (textHasOorAu) score += 50;
            if (textHasPureKana) score -= 25;
        }

        // 6. Lower Ukar (कु, कू)
        if (hasLower) {
            if (textHasLowerUkar) score += 45;
        } else {
            if (textHasLowerUkar) score -= 25;
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

            // Shirorekha check: in Marathi/Hindi handwriting, words have a shirorekha (top headline).
            // Numbers (0-9) and Latin words do not have a shirorekha.
            // If the user wrote digits without a shirorekha, promote numeric candidate over accidental Devanagari dictionary words!
            boolean hasShirorekha = false;
            if (strokes != null && !strokes.isEmpty()) {
                RectF b = boundsOf(strokes);
                if (b != null && b.width() > 10f) {
                    float totalW = b.width();
                    for (Stroke s : strokes) {
                        if (s == null) continue;
                        RectF sb = s.getBounds();
                        if (sb != null && (sb.width() > totalW * 0.35f && (sb.width() / Math.max(1f, sb.height())) >= 2.2f)) {
                            hasShirorekha = true;
                            break;
                        }
                    }
                }
            }

            if (!hasShirorekha && !pool.isEmpty()) {
                for (int i = 0; i < pool.size(); i++) {
                    String c = pool.get(i).trim();
                    if (!c.isEmpty() && c.matches("^[0-9\\s.,\\-+]+$") && pool.get(0).matches(".*[\\u0900-\\u097F].*")) {
                        String numCand = pool.remove(i);
                        pool.add(0, numCand);
                        break;
                    }
                }
            }

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

        // English / Latin:
        // Google's Digital Ink ML model for English features an extensive built-in language
        // model and dictionary. Respect ML Kit's top-ranked candidate while prioritizing
        // user-trained personalized corrections if available.
        UserHandwritingModel userModel = (context != null) ? UserHandwritingModel.getInstance(context) : null;
        if (userModel != null) {
            for (String cand : alternatives) {
                String learned = userModel.predictLearnedCorrection(cand, strokes);
                if (learned != null && !learned.trim().isEmpty() && !pool.contains(learned)) {
                    pool.add(0, learned); // Prioritize user-trained style at top
                }
            }
        }

        return pool;
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
        String[] tags = getCandidateTags(language);
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
