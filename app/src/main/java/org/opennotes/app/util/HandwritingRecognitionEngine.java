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
import org.opennotes.app.ai.InkPreprocessor;
import org.opennotes.app.ai.SemanticStrokeClusterer;
import org.opennotes.app.ai.UserHandwritingModel;
import org.opennotes.app.ai.LinguisticDictionary;
import org.opennotes.app.ai.HandwritingImageOcr;
import org.opennotes.app.ai.GeometricDigitDisambiguator;

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
    private static final int MAX_REVIEW_CANDIDATES = 32;
    private static final Set<String> COMMON_ENGLISH_WORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("a", "about", "all", "am", "an", "and", "are", "as", "at",
                    "be", "because", "been", "but", "by", "can", "could", "did", "do", "for",
                    "from", "get", "give", "go", "had", "has", "have", "he", "her", "here",
                    "him", "his", "how", "i", "if", "in", "into", "is", "it", "its", "just",
                    "know", "like", "me", "more", "my", "no", "not", "of", "on", "one", "or",
                    "our", "out", "please", "see", "she", "so", "some", "take", "tell", "than",
                    "that", "the", "their", "them", "then", "there", "these", "they", "think",
                    "this", "to", "up", "us", "was", "we", "were", "what", "when", "where",
                    "which", "who", "will", "with", "would", "yes", "you", "your")));
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
            return new String[]{"mr-IN", "mr", "en-US", "en-IN", "hi", "hi-IN"};
        } else {
            // Marathi default
            return new String[]{"mr-IN", "mr", "hi", "hi-IN"};
        }
    }

    /**
     * Determines whether handwriting strokes contain a Devanagari Shirorekha headline.
     * Note: Genuine Devanagari words can be written either with a connected headline (formal)
     * or without a headline (informal / fast handwriting). A detected Shirorekha confirms
     * Devanagari script, but its absence does NOT mean the text is English.
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
        recognizeMultiHypothesis(context, strokes, preferredLanguage, preContext, false,
                withImageOcr(context, strokes, preferredLanguage, callback));
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
        recognizeMultiHypothesis(context, strokes, preferredLanguage, preContext, true,
                withImageOcr(context, strokes, preferredLanguage, callback));
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
        recognizeMultiHypothesis(context, line.strokes, preferredLanguage, preContext, includeReviewSuggestions,
                withImageOcr(context, line.strokes, preferredLanguage, callback));
    }

    /** Add independent image-OCR candidates while preserving Digital Ink's primary result. */
    private static Callback withImageOcr(Context context, List<Stroke> strokes,
                                         String language, Callback downstream) {
        return new Callback() {
            @Override public void onSuccess(RecognitionResult result) {
                String selectedLanguage = result.language != null ? result.language : language;
                HandwritingImageOcr.recognize(context, strokes, selectedLanguage, candidate -> {
                    String clean = candidate == null ? "" : candidate.trim().replaceAll("\\s+", " ");
                    if (clean.isEmpty() || clean.equalsIgnoreCase(result.text.trim())) {
                        downstream.onSuccess(result);
                        return;
                    }
                    List<String> alternatives = new ArrayList<>(result.alternatives);
                    String selectedText = result.text;
                    String resultLanguage = result.language != null ? result.language : language;
                    LinguisticDictionary dictionary = context != null
                            ? LinguisticDictionary.getInstance(context) : null;
                    boolean promoteOcr = dictionary != null && isCandidateInScript(clean, resultLanguage)
                            && dictionary.scoreLine(clean, resultLanguage)
                            >= dictionary.scoreLine(result.text, resultLanguage) + 12;
                    if (promoteOcr) {
                        selectedText = clean;
                        alternatives.remove(clean);
                        if (alternatives.size() >= MAX_REVIEW_CANDIDATES) {
                            alternatives.remove(alternatives.size() - 1);
                        }
                        alternatives.add(0, clean);
                        if (!alternatives.contains(result.text)
                                && alternatives.size() < MAX_REVIEW_CANDIDATES) alternatives.add(result.text);
                    } else if (!alternatives.contains(clean)
                            && alternatives.size() < MAX_REVIEW_CANDIDATES) {
                        alternatives.add(clean);
                    }
                    downstream.onSuccess(new RecognitionResult(selectedText, resultLanguage,
                            result.bounds, result.fontSize, alternatives, result.angleDegrees,
                            result.startX, result.startY, result.wordItems));
                });
            }
            @Override public void onFailure(Exception error) {
                String ocrLanguage = language;
                if (isAutoLanguage(ocrLanguage)) ocrLanguage = "MARATHI";
                final String fallbackLanguage = ocrLanguage;
                HandwritingImageOcr.recognize(context, strokes, fallbackLanguage, candidate -> {
                    String clean = candidate == null ? "" : candidate.trim().replaceAll("\\s+", " ");
                    if (clean.isEmpty()) {
                        downstream.onFailure(error);
                        return;
                    }
                    RectF bounds = boundsOf(strokes);
                    if (bounds == null) {
                        downstream.onFailure(error);
                        return;
                    }
                    float fontSize = Math.max(26f, Math.min(65f, bounds.height() * 0.85f));
                    String resultLanguage = containsDevanagari(clean) ? fallbackLanguage : "ENGLISH";
                    MAIN.post(() -> downstream.onSuccess(new RecognitionResult(clean,
                            resultLanguage, bounds, fontSize, Collections.singletonList(clean),
                            0f, bounds.left, bounds.bottom - 4f)));
                });
            }
        };
    }

    private static boolean isCandidateInScript(String candidate, String language) {
        if (candidate == null || candidate.isEmpty()) return false;
        if ("MARATHI".equalsIgnoreCase(language) || "HINDI".equalsIgnoreCase(language)) {
            return containsDevanagari(candidate) || !containsLatinLetters(candidate);
        }
        return containsLatinLetters(candidate) || !containsDevanagari(candidate);
    }

    private static String recentPreContext(String preContext, int maxCodePoints) {
        if (preContext == null || preContext.isEmpty() || maxCodePoints <= 0) return "";
        int count = preContext.codePointCount(0, preContext.length());
        int start = count > maxCodePoints
                ? preContext.offsetByCodePoints(0, count - maxCodePoints)
                : 0;
        return preContext.substring(start);
    }



    private static void recognizeMultiHypothesis(Context context,
                                                 List<Stroke> strokes,
                                                 String preferredLanguage,
                                                 String preContext,
                                                 boolean includeReviewSuggestions,
                                                 Callback callback) {
        if (context == null || strokes == null || strokes.isEmpty() || callback == null) return;
        // ML Kit only uses the characters immediately before this line (roughly
        // the last 20). Bound the context here so distant page text cannot bias a
        // short word and oversized context is not needlessly passed through.
        final String contextText = recentPreContext(preContext, 20);

        final float writingAngle = InkNormalizer.detectWritingAngleDegrees(strokes);

        // Script selection:
        // Respect the user's explicit language preference.
        // If the user selected MARATHI, HINDI, or ENGLISH, ALWAYS use that language!
        // Never override MARATHI or HINDI to ENGLISH based on absence of Shirorekha,
        // because native speakers frequently write Devanagari without a top headline!
        final String detectedLang;
        if ("ENGLISH".equalsIgnoreCase(preferredLanguage)) {
            detectedLang = "ENGLISH";
        } else if ("HINDI".equalsIgnoreCase(preferredLanguage)) {
            detectedLang = "HINDI";
        } else if ("MARATHI".equalsIgnoreCase(preferredLanguage)) {
            detectedLang = "MARATHI";
        } else {
            // For AUTO or unspecified:
            // Default to MARATHI which natively recognizes Devanagari (both with and without
            // Shirorekha), universal Arabic digits (0-9), and Latin punctuation.
            detectedLang = "MARATHI";
        }

        // Digital Ink language models are optimized for words and frequently turn a
        // hand-drawn arrow into punctuation or unrelated letters. For an isolated,
        // unmistakable two/three-stroke arrow, use its geometry directly. This is
        // intentionally restricted to a complete arrow selection so ordinary writing
        // and arrows embedded in text continue through ML Kit unchanged.
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

        // Deskew moderate as well as steep baselines. The detector suppresses small
        // noisy estimates, so a 3.5° threshold avoids rotating ordinary level writing.
        final float primaryPassAngle = (Math.abs(writingAngle) >= 3.5f) ? writingAngle : 0f;
        List<Stroke> primaryStrokes = (primaryPassAngle != 0f)
                ? transformedStrokeCopies(strokes, primaryPassAngle, false, false)
                : strokes;

        executeSingleRecognitionPass(context, primaryStrokes, strokes, primaryPassAngle, detectedLang, contextText, new Callback() {
            @Override
            public void onSuccess(RecognitionResult primaryResult) {
                RecognitionResult reconciled = disambiguateDigits(context, primaryResult, strokes);
                if (reconciled != null && !reconciled.text.trim().isEmpty()) {
                    if (isAutoLanguage(preferredLanguage)
                            && containsLatinLetters(reconciled.text)
                            && !containsDevanagari(reconciled.text)) {
                        tryEnglishAutoPass(context, primaryStrokes, strokes, primaryPassAngle,
                                writingAngle, contextText, reconciled, includeReviewSuggestions, callback);
                        return;
                    }
                    if (isAutoLanguage(preferredLanguage) && containsDevanagari(reconciled.text)) {
                        tryEnglishCrossScriptAutoPass(context, primaryStrokes, strokes,
                                primaryPassAngle, writingAngle, contextText, reconciled,
                                includeReviewSuggestions, callback);
                        return;
                    }
                    // Review mode should gather alternative views of the ink even when ML Kit
                    // returns several candidates for its first view. Those alternatives may
                    // all come from the same orientation and miss a much better deskewed read.
                    if (includeReviewSuggestions) {
                        tryReviewHypotheses(context, strokes, detectedLang, contextText,
                                reconciled, primaryPassAngle != 0f, writingAngle, callback);
                    } else {
                        callback.onSuccess(reconciled);
                    }
                } else {
                    tryAlternativeHypothesis(context, strokes, detectedLang, contextText,
                            null, primaryPassAngle != 0f, writingAngle, callback);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (isFatalError(error)) {
                    callback.onFailure(error);
                    return;
                }
                tryAlternativeHypothesis(context, strokes, detectedLang, contextText,
                        null, primaryPassAngle != 0f, writingAngle, callback);
            }
        });
    }

    /** In Auto mode, a Latin-script answer from the Marathi fallback is a cue to consult
     * the English model before returning. Explicit language selections are never changed. */
    private static void tryEnglishAutoPass(Context context, List<Stroke> primaryStrokes,
            List<Stroke> originalStrokes, float primaryPassAngle, float writingAngle,
            String preContext, RecognitionResult primaryResult, boolean includeReviewSuggestions,
            Callback callback) {
        executeSingleRecognitionPass(context, primaryStrokes, originalStrokes, primaryPassAngle,
                "ENGLISH", preContext, new Callback() {
                    @Override
                    public void onSuccess(RecognitionResult englishResult) {
                        if (englishResult == null || !containsLatinLetters(englishResult.text)
                                || containsDevanagari(englishResult.text)) {
                            deliverAutoFallback(primaryResult, originalStrokes, writingAngle,
                                    includeReviewSuggestions, callback, context, preContext);
                            return;
                        }
                        RecognitionResult combined = mergeCandidates(englishResult, primaryResult);
                        if (includeReviewSuggestions) {
                            tryReviewHypotheses(context, originalStrokes, "ENGLISH", preContext,
                                    combined, primaryPassAngle != 0f, writingAngle, callback);
                        } else {
                            callback.onSuccess(combined);
                        }
                    }

                    @Override
                    public void onFailure(Exception error) {
                        deliverAutoFallback(primaryResult, originalStrokes, writingAngle,
                                includeReviewSuggestions, callback, context, preContext);
                    }
                });
    }

    private static void deliverAutoFallback(RecognitionResult primaryResult, List<Stroke> strokes,
            float writingAngle, boolean includeReviewSuggestions, Callback callback,
            Context context, String preContext) {
        if (includeReviewSuggestions) {
            tryReviewHypotheses(context, strokes, primaryResult.language, preContext,
                    primaryResult, false, writingAngle, callback);
        } else {
            callback.onSuccess(primaryResult);
        }
    }

    private static boolean isAutoLanguage(String language) {
        return language == null || "AUTO".equalsIgnoreCase(language)
                || "AUTO_DETECT".equalsIgnoreCase(language);
    }

    private static boolean containsLatinLetters(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)
                    && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) return true;
            i += Character.charCount(codePoint);
        }
        return false;
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

        if (primaryWasDeskewed) {
            hypotheses.add(new AngleHypothesis(originalStrokes, 0f));
        }

        // The geometric estimate is usually close, but handwriting baselines can be
        // distorted by ascenders, dots, diagrams, and short selections. Try nearby
        // deskew angles in review/failure recovery so those cases can still surface
        // useful candidates. Keep the search deterministic and merge all successful
        // passes into the review suggestions while preserving the primary result.
        float[] offsets = {-12f, 12f, -25f, 25f, -15f, 15f, -30f, 30f, -45f, 45f};
        for (float offset : offsets) {
            float angle = writingAngle + offset;
            if (angle <= -85f || angle >= 85f) continue;
            hypotheses.add(new AngleHypothesis(
                    transformedStrokeCopies(originalStrokes, angle, false, false), angle));
        }
        hypotheses.add(new AngleHypothesis(
                transformedStrokeCopies(originalStrokes, writingAngle, false, false), writingAngle));

        RectF bounds = boundsOf(originalStrokes);
        if (bounds != null && bounds.height() > bounds.width() * 1.3f) {
            // Priority vertical hypotheses for tall handwriting
            hypotheses.add(new AngleHypothesis(
                    transformedStrokeCopies(originalStrokes, -90f, false, false), -90f));
            hypotheses.add(new AngleHypothesis(
                    transformedStrokeCopies(originalStrokes, 90f, false, false), 90f));
        }

        // Each retry is an offset around the angle measured from this ink rather
        // than an absolute page rotation, keeping hypotheses tied to the sample.

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
        Map<String, Integer> candidateVotes = new HashMap<>();
        if (accumulatedResult != null) {
            for (String candidate : accumulatedResult.alternatives) {
                if (candidate != null && !candidate.trim().isEmpty()) {
                    candidateVotes.put(candidate, 1);
                }
            }
        }
        runWaterfall(context, queue, originalStrokes, index, language, preContext,
                accumulatedResult, candidateVotes, callback);
    }

    private static void runWaterfall(Context context,
                                     List<AngleHypothesis> queue,
                                     List<Stroke> originalStrokes,
                                     int index,
                                     String language,
                                     String preContext,
                                     RecognitionResult accumulatedResult,
                                     Map<String, Integer> candidateVotes,
                                     Callback callback) {
        if (index >= queue.size()) {
            if (accumulatedResult != null) {
                callback.onSuccess(disambiguateDigits(context,
                        rankCandidatesByAgreement(accumulatedResult, candidateVotes), originalStrokes));
            } else if (!queue.isEmpty()) {
                // Try the other supported scripts in a stable order. This also
                // covers Hindi <-> Marathi for Devanagari code-switching.
                tryCrossLanguageFallback(context, originalStrokes, language, preContext, callback);
                return;
            } else {
                callback.onFailure(new IllegalArgumentException(
                        "No handwriting text was recognized on this line. Check the selected language or select a clearer line of ink."));
            }
            return;
        }

        AngleHypothesis hypothesis = queue.get(index);
        if (hypothesis == null || hypothesis.strokes == null || hypothesis.strokes.isEmpty()) {
            runWaterfall(context, queue, originalStrokes, index + 1, language, preContext,
                    accumulatedResult, candidateVotes, callback);
            return;
        }

        executeSingleRecognitionPass(context, hypothesis.strokes, originalStrokes, hypothesis.angleDegrees,
                language, preContext, new Callback() {
            @Override
            public void onSuccess(RecognitionResult candidateResult) {
                if (candidateResult != null && !candidateResult.text.trim().isEmpty()) {
                    Set<String> seenInPass = new HashSet<>();
                    for (String candidate : candidateResult.alternatives) {
                        if (candidate != null && !candidate.trim().isEmpty() && seenInPass.add(candidate)) {
                            candidateVotes.put(candidate, candidateVotes.getOrDefault(candidate, 0) + 1);
                        }
                    }
                    RecognitionResult merged = mergeCandidates(accumulatedResult, candidateResult);
                    runWaterfall(context, queue, originalStrokes, index + 1, language,
                            preContext, merged, candidateVotes, callback);
                } else {
                    runWaterfall(context, queue, originalStrokes, index + 1, language, preContext,
                            accumulatedResult, candidateVotes, callback);
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (isFatalError(error)) {
                    callback.onFailure(error);
                    return;
                }
                runWaterfall(context, queue, originalStrokes, index + 1, language, preContext,
                        accumulatedResult, candidateVotes, callback);
            }
        });
    }

    /** When Auto mistakes Latin handwriting for Devanagari, consult English only if
     * the first Marathi read has little lexical evidence; retain Marathi when it reads well. */
    private static void tryEnglishCrossScriptAutoPass(Context context, List<Stroke> primaryStrokes,
            List<Stroke> originalStrokes, float primaryPassAngle, float writingAngle,
            String preContext, RecognitionResult marathiResult, boolean includeReviewSuggestions,
            Callback callback) {
        int marathiScore = scoreLanguageCandidate(marathiResult.text, "MARATHI", context);
        if (marathiScore >= 4) {
            tryHindiAutoPass(context, primaryStrokes, originalStrokes, primaryPassAngle,
                    writingAngle, preContext, marathiResult, includeReviewSuggestions, callback);
            return;
        }
        executeSingleRecognitionPass(context, primaryStrokes, originalStrokes, primaryPassAngle,
                "ENGLISH", preContext, new Callback() {
                    @Override public void onSuccess(RecognitionResult englishResult) {
                        String bestEnglish = bestLanguageCandidate(englishResult.alternatives,
                                "ENGLISH", context);
                        int englishScore = scoreLanguageCandidate(bestEnglish, "ENGLISH", context);
                        int englishEvidence = countEnglishEvidence(bestEnglish, context);
                        boolean oneKnownWord = bestEnglish.trim().split("\\s+").length == 1
                                && englishEvidence == 1 && englishScore >= 2 && marathiScore == 0;
                        boolean strongPhrase = englishEvidence >= 2
                                && englishScore >= marathiScore + 8;
                        if (!containsLatinLetters(bestEnglish) || containsDevanagari(bestEnglish)
                                || (!strongPhrase && !oneKnownWord)) {
                            tryHindiAutoPass(context, primaryStrokes, originalStrokes, primaryPassAngle,
                                    writingAngle, preContext, marathiResult, includeReviewSuggestions, callback);
                            return;
                        }
                        RecognitionResult combined = mergeLanguageResults(englishResult,
                                bestEnglish, marathiResult);
                        if (includeReviewSuggestions) {
                            tryReviewHypotheses(context, originalStrokes, "ENGLISH", preContext,
                                    combined, primaryPassAngle != 0f, writingAngle, callback);
                        } else {
                            callback.onSuccess(combined);
                        }
                    }

                    @Override public void onFailure(Exception error) {
                        tryHindiAutoPass(context, primaryStrokes, originalStrokes, primaryPassAngle,
                                writingAngle, preContext, marathiResult, includeReviewSuggestions, callback);
                    }
                });
    }

    private static void tryCrossLanguageFallback(Context context, List<Stroke> strokes,
            String originalLanguage, String preContext, Callback callback) {
        List<String> fallbackLanguages = new ArrayList<>();
        if ("HINDI".equalsIgnoreCase(originalLanguage)) {
            fallbackLanguages.add("MARATHI");
            fallbackLanguages.add("ENGLISH");
        } else if ("MARATHI".equalsIgnoreCase(originalLanguage)) {
            fallbackLanguages.add("HINDI");
            fallbackLanguages.add("ENGLISH");
        } else {
            fallbackLanguages.add("MARATHI");
            fallbackLanguages.add("HINDI");
        }
        tryCrossLanguageFallback(context, strokes, fallbackLanguages, 0, preContext,
                new IllegalArgumentException("No handwriting text was recognized on this line. "
                        + "Check the selected language or select a clearer line of ink."), callback);
    }

    private static void tryCrossLanguageFallback(Context context, List<Stroke> strokes,
            List<String> languages, int index, String preContext, Exception lastError,
            Callback callback) {
        if (index >= languages.size()) {
            tryWordClusterFallback(context, strokes, "ENGLISH", preContext, lastError, callback);
            return;
        }
        String language = languages.get(index);
        executeSingleRecognitionPass(context, strokes, strokes, 0f, language, preContext,
                new Callback() {
                    @Override
                    public void onSuccess(RecognitionResult result) {
                        if (result != null && result.text != null && !result.text.trim().isEmpty()) {
                            callback.onSuccess(result);
                        } else {
                            tryCrossLanguageFallback(context, strokes, languages, index + 1,
                                    preContext, lastError, callback);
                        }
                    }

                    @Override
                    public void onFailure(Exception error) {
                        if (isFatalError(error)) {
                            callback.onFailure(error);
                            return;
                        }
                        tryCrossLanguageFallback(context, strokes, languages, index + 1,
                                preContext, error != null ? error : lastError, callback);
                    }
                });
    }

    /** Auto mode compares Hindi and Marathi hypotheses when both recognize Devanagari.
     * Marathi remains the default unless Hindi has clear language-specific evidence. */
    private static void tryHindiAutoPass(Context context, List<Stroke> primaryStrokes,
            List<Stroke> originalStrokes, float primaryPassAngle, float writingAngle,
            String preContext, RecognitionResult marathiResult, boolean includeReviewSuggestions,
            Callback callback) {
        executeSingleRecognitionPass(context, primaryStrokes, originalStrokes, primaryPassAngle,
                "HINDI", preContext, new Callback() {
                    @Override
                    public void onSuccess(RecognitionResult hindiResult) {
                        if (hindiResult == null || !containsDevanagari(hindiResult.text)) {
                            deliverAutoFallback(marathiResult, originalStrokes, writingAngle,
                                    includeReviewSuggestions, callback, context, preContext);
                            return;
                        }
                        String bestMarathi = bestLanguageCandidate(marathiResult.alternatives,
                                "MARATHI", context);
                        String bestHindi = bestLanguageCandidate(hindiResult.alternatives,
                                "HINDI", context);
                        int marathiScore = scoreLanguageCandidate(bestMarathi, "MARATHI", context);
                        int hindiScore = scoreLanguageCandidate(bestHindi, "HINDI", context);
                        boolean preferHindi = hindiScore >= marathiScore + 8;
                        RecognitionResult selected = preferHindi ? hindiResult : marathiResult;
                        String selectedText = preferHindi ? bestHindi : bestMarathi;
                        RecognitionResult combined = mergeLanguageResults(selected, selectedText,
                                preferHindi ? marathiResult : hindiResult);
                        if (includeReviewSuggestions) {
                            tryReviewHypotheses(context, originalStrokes, combined.language,
                                    preContext, combined, primaryPassAngle != 0f, writingAngle, callback);
                        } else {
                            callback.onSuccess(combined);
                        }
                    }

                    @Override
                    public void onFailure(Exception error) {
                        deliverAutoFallback(marathiResult, originalStrokes, writingAngle,
                                includeReviewSuggestions, callback, context, preContext);
                    }
                });
    }

    private static String bestLanguageCandidate(List<String> candidates, String language, Context context) {
        if (candidates == null || candidates.isEmpty()) return "";
        String best = candidates.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            int score = scoreLanguageCandidate(candidate, language, context);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int scoreLanguageCandidate(String text, String language, Context context) {
        if (text == null || text.trim().isEmpty()) return Integer.MIN_VALUE / 2;
        String[] tokens = text.trim().split("\\s+");
        Set<String> markers = "HINDI".equalsIgnoreCase(language)
                ? new HashSet<>(Arrays.asList("है", "हैं", "था", "थे", "थी", "मैं", "में", "को", "से", "और", "किया", "होगा"))
                : new HashSet<>(Arrays.asList("आहे", "आहेत", "आणि", "माझे", "माझा", "माझी", "तुम्ही", "करतो", "करते", "करून", "साठी", "म्हणून", "झाले"));
        LinguisticDictionary dictionary = context != null
                ? LinguisticDictionary.getInstance(context) : null;
        int score = 0;
        for (String token : tokens) {
            String clean = token.replaceAll("[^\\p{L}\\p{M}\\p{N}]", "");
            if (clean.isEmpty()) continue;
            if ("ENGLISH".equalsIgnoreCase(language) && COMMON_ENGLISH_WORDS.contains(clean)) score += 5;
            if (markers.contains(clean)) score += 7;
            if (dictionary != null && dictionary.isValidWord(clean, language)) score += 2;
        }
        return score;
    }

    private static int countEnglishEvidence(String text, Context context) {
        if (text == null || text.trim().isEmpty()) return 0;
        LinguisticDictionary dictionary = context != null
                ? LinguisticDictionary.getInstance(context) : null;
        int count = 0;
        for (String token : text.trim().split("\\s+")) {
            String clean = token.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
            if (!clean.isEmpty() && (COMMON_ENGLISH_WORDS.contains(clean)
                    || (dictionary != null && dictionary.isValidWord(clean, "ENGLISH")))) count++;
        }
        return count;
    }

    private static RecognitionResult mergeLanguageResults(RecognitionResult selected,
            String selectedText, RecognitionResult other) {
        List<String> alternatives = new ArrayList<>();
        if (selectedText != null && !selectedText.trim().isEmpty()) alternatives.add(selectedText);
        for (String candidate : selected.alternatives) {
            if (candidate != null && !candidate.trim().isEmpty() && !alternatives.contains(candidate)) {
                alternatives.add(candidate);
                if (alternatives.size() >= MAX_REVIEW_CANDIDATES) break;
            }
        }
        if (other != null && alternatives.size() < MAX_REVIEW_CANDIDATES) {
            if (other.text != null && !other.text.trim().isEmpty() && !alternatives.contains(other.text)) {
                alternatives.add(other.text);
            }
            for (String candidate : other.alternatives) {
                if (candidate != null && !candidate.trim().isEmpty() && !alternatives.contains(candidate)) {
                    alternatives.add(candidate);
                    if (alternatives.size() >= MAX_REVIEW_CANDIDATES) break;
                }
            }
        }
        return new RecognitionResult(selectedText, selected.language, selected.bounds, selected.fontSize,
                alternatives, selected.angleDegrees, selected.startX, selected.startY, selected.wordItems);
    }

    /** Stable candidates from several orientations rise in the review list; ML Kit's
     * primary result remains first so agreement never silently changes the transcript. */
    private static RecognitionResult rankCandidatesByAgreement(RecognitionResult result,
                                                                Map<String, Integer> votes) {
        if (result == null || result.alternatives.size() < 2 || votes == null || votes.isEmpty()) return result;
        List<String> ranked = new ArrayList<>(result.alternatives);
        Map<String, Integer> originalRanks = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) originalRanks.putIfAbsent(ranked.get(i), i);
        String primary = result.text;
        ranked.remove(primary);
        ranked.sort((left, right) -> {
            int voteOrder = Integer.compare(votes.getOrDefault(right, 0), votes.getOrDefault(left, 0));
            if (voteOrder != 0) return voteOrder;
            return Integer.compare(originalRanks.getOrDefault(left, Integer.MAX_VALUE),
                    originalRanks.getOrDefault(right, Integer.MAX_VALUE));
        });
        ranked.add(0, primary);
        return new RecognitionResult(result.text, result.language, result.bounds, result.fontSize,
                ranked, result.angleDegrees, result.startX, result.startY, result.wordItems);
    }

    /**
     * Last-resort recovery for a line that the recognizer could not read as a whole.
     * Separate spatial word groups can be easier to recognize when a tight word gap,
     * ligature, or unusual spacing makes the full line ambiguous.
     */
    private static void tryWordClusterFallback(Context context, List<Stroke> strokes,
                                              String language, String preContext,
                                              Exception originalError, Callback callback) {
        if (strokes == null || strokes.size() < 2) {
            callback.onFailure(originalError);
            return;
        }
        List<Float> heights = new ArrayList<>();
        for (Stroke stroke : strokes) {
            RectF bounds = InkNormalizer.getExactBounds(stroke);
            if (bounds != null && bounds.height() > 6f) heights.add(bounds.height());
        }
        if (heights.isEmpty()) {
            callback.onFailure(originalError);
            return;
        }
        Collections.sort(heights);
        float medianHeight = Math.max(15f, heights.get(heights.size() / 2));
        float angle = InkNormalizer.detectWritingAngleDegrees(strokes);
        List<SemanticStrokeClusterer.StrokeWord> words =
                SemanticStrokeClusterer.segmentLineIntoWords(strokes, medianHeight, angle);
        if (words == null || words.size() < 2) {
            callback.onFailure(originalError);
            return;
        }
        recognizeWordClustersSequentially(context, words, 0, language, preContext,
                new ArrayList<>(), new ArrayList<>(), originalError, callback);
    }

    /** Adds a spatially segmented read to review suggestions even when whole-line OCR worked. */
    private static void trySpatialReviewHypothesis(Context context, List<Stroke> strokes,
            String language, String preContext, RecognitionResult primary, Callback callback) {
        if (primary == null || strokes == null || strokes.size() < 3) {
            callback.onSuccess(primary);
            return;
        }
        List<Float> heights = new ArrayList<>();
        for (Stroke stroke : strokes) {
            RectF bounds = InkNormalizer.getExactBounds(stroke);
            if (bounds != null && bounds.height() > 6f) heights.add(bounds.height());
        }
        if (heights.isEmpty()) {
            callback.onSuccess(primary);
            return;
        }
        Collections.sort(heights);
        float medianHeight = Math.max(15f, heights.get(heights.size() / 2));
        float angle = InkNormalizer.detectWritingAngleDegrees(strokes);
        List<SemanticStrokeClusterer.StrokeWord> words =
                SemanticStrokeClusterer.segmentLineIntoWords(strokes, medianHeight, angle);
        if (words == null || words.size() < 2 || words.size() > 8) {
            callback.onSuccess(primary);
            return;
        }
        recognizeWordClustersSequentially(context, words, 0, language, preContext,
                new ArrayList<>(), new ArrayList<>(),
                new IllegalArgumentException("Spatial word recognition returned no text."), new Callback() {
                    @Override
                    public void onSuccess(RecognitionResult segmented) {
                        callback.onSuccess(appendReviewCandidate(primary,
                                segmented != null ? segmented.text : null));
                    }

                    @Override
                    public void onFailure(Exception error) {
                        callback.onSuccess(primary);
                    }
                });
    }

    private static void tryReviewHypotheses(Context context, List<Stroke> strokes,
            String language, String preContext, RecognitionResult primary,
            boolean primaryWasDeskewed, float writingAngle, Callback callback) {
        tryAlternativeHypothesis(context, strokes, language, preContext, primary,
                primaryWasDeskewed, writingAngle, new Callback() {
                    @Override
                    public void onSuccess(RecognitionResult angleResult) {
                        callback.onSuccess(angleResult != null ? angleResult : primary);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        if (primary != null) callback.onSuccess(primary);
                        else callback.onFailure(error);
                    }
                });
    }

    private static RecognitionResult appendReviewCandidate(RecognitionResult primary, String candidate) {
        if (primary == null || candidate == null || candidate.trim().isEmpty()
                || primary.alternatives.contains(candidate)) return primary;
        List<String> alternatives = new ArrayList<>(primary.alternatives);
        if (alternatives.size() >= MAX_REVIEW_CANDIDATES) {
            alternatives.remove(alternatives.size() - 1);
        }
        alternatives.add(candidate);
        return new RecognitionResult(primary.text, primary.language, primary.bounds,
                primary.fontSize, alternatives, primary.angleDegrees, primary.startX,
                primary.startY, primary.wordItems);
    }

    private static void recognizeWordClustersSequentially(Context context,
            List<SemanticStrokeClusterer.StrokeWord> words, int index, String language,
            String preContext, List<String> recognizedWords, List<WordItem> wordItems,
            Exception originalError, Callback callback) {
        if (index >= words.size()) {
            String text = String.join(" ", recognizedWords);
            RectF bounds = new RectF();
            boolean hasBounds = false;
            float fontSize = 32f;
            for (SemanticStrokeClusterer.StrokeWord word : words) {
                if (word.bounds == null) continue;
                if (!hasBounds) {
                    bounds.set(word.bounds);
                    hasBounds = true;
                } else {
                    bounds.union(word.bounds);
                }
            }
            if (!hasBounds) {
                callback.onFailure(originalError);
                return;
            }
            for (WordItem item : wordItems) {
                if (item.bounds != null) fontSize = Math.max(fontSize, item.bounds.height() * 0.8f);
            }
            callback.onSuccess(new RecognitionResult(text, language, bounds, fontSize,
                    Collections.singletonList(text), 0f, bounds.left, bounds.bottom - 4f, wordItems));
            return;
        }

        SemanticStrokeClusterer.StrokeWord word = words.get(index);
        if (word == null || word.strokes == null || word.strokes.isEmpty()) {
            callback.onFailure(originalError);
            return;
        }
        executeSingleRecognitionPass(context, word.strokes, word.strokes, 0f, language, preContext,
                new Callback() {
                    @Override
                    public void onSuccess(RecognitionResult result) {
                        if (result == null || result.text == null || result.text.trim().isEmpty()) {
                            callback.onFailure(originalError);
                            return;
                        }
                        String recognizedWord = chooseSegmentCandidate(context, result,
                                language, preContext);
                        recognizedWords.add(recognizedWord);
                        RectF wordBounds = word.bounds != null ? new RectF(word.bounds) : boundsOf(word.strokes);
                        wordItems.add(new WordItem(recognizedWord, wordBounds, ""));
                        String nextContext = (preContext == null || preContext.isEmpty())
                                ? recognizedWord : preContext + " " + recognizedWord;
                        recognizeWordClustersSequentially(context, words, index + 1, language,
                                recentPreContext(nextContext, 20), recognizedWords, wordItems,
                                originalError, callback);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        callback.onFailure(originalError != null ? originalError : error);
                    }
                });
    }

    /** Ranks isolated word reads using model order, known vocabulary and learned context. */
    private static String chooseSegmentCandidate(Context context, RecognitionResult result,
            String language, String preContext) {
        if (result == null || result.alternatives == null || result.alternatives.isEmpty()) {
            return result != null && result.text != null ? result.text.trim() : "";
        }
        LinguisticDictionary dictionary = context != null
                ? LinguisticDictionary.getInstance(context) : null;
        UserHandwritingModel personalModel = context != null
                ? UserHandwritingModel.getInstance(context) : null;
        String primary = result.text == null ? "" : result.text.trim();
        String best = primary;
        int primaryScore = scoreSegmentCandidate(primary, 0, language, preContext,
                dictionary, personalModel);
        int bestScore = primaryScore;
        for (int i = 0; i < result.alternatives.size(); i++) {
            String candidate = result.alternatives.get(i);
            if (candidate == null || candidate.trim().isEmpty()) continue;
            String trimmed = candidate.trim();
            int score = scoreSegmentCandidate(trimmed, i, language, preContext,
                    dictionary, personalModel);
            // Keep the model's first choice unless another candidate has clear
            // vocabulary/context evidence; avoid flipping on weak word-list matches.
            if (score > bestScore && (trimmed.equals(primary) || score >= primaryScore + 2)) {
                best = trimmed;
                bestScore = score;
            }
        }
        return best;
    }

    private static int scoreSegmentCandidate(String candidate, int modelRank, String language,
            String preContext, LinguisticDictionary dictionary, UserHandwritingModel personalModel) {
        String clean = candidate.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
        int score = -2 * modelRank;
        if (!clean.isEmpty()) {
            if ("ENGLISH".equalsIgnoreCase(language) && COMMON_ENGLISH_WORDS.contains(clean)) score += 5;
            if (dictionary != null && dictionary.isValidWord(clean, language)) score += 2;
        }
        if (personalModel != null) {
            score += personalModel.getPersonalizedContextScore(candidate, preContext);
        }
        return score;
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
        // Work on cleaned, uniformly sampled copies. Original strokes are retained
        // separately for placement, learning, and undo; preprocessing never mutates them.
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
                if (merged.size() >= MAX_REVIEW_CANDIDATES) break;
            }
        }
        return new RecognitionResult(primary.text, primary.language, primary.bounds,
                primary.fontSize, merged, primary.angleDegrees, primary.startX, primary.startY);
    }

    private static RecognitionResult disambiguateDigits(Context context, RecognitionResult result, List<Stroke> strokes) {
        if (result == null) return null;
        // Keep the ML Kit transcript first, but surface a geometry-checked numeric
        // alternative for review when isolated digit strokes support a correction.
        List<String> alternatives = new ArrayList<>();
        if (result.text != null && !result.text.trim().isEmpty()) alternatives.add(result.text);
        String geometricDigits = GeometricDigitDisambiguator.disambiguateDigits(result.text, strokes);
        if (geometricDigits != null && !geometricDigits.equals(result.text)
                && !alternatives.contains(geometricDigits)) alternatives.add(geometricDigits);
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
            // Send the captured trajectory at its original scale. Translation only
            // removes the page offset; no median character-height scale is inferred.
            float naturalWidth = Math.max(1f, bounds.width());
            float naturalHeight = Math.max(1f, bounds.height());
            float scaledW = naturalWidth;
            float scaledH = naturalHeight;
            float padX = Math.max(24f, naturalWidth * 0.05f);
            float padY = Math.max(24f, naturalHeight * 0.05f);

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

                // Use captured pen-up intervals when available; legacy notes without
                // timestamps receive only a small synthetic inter-stroke gap.
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

                    float inkX = point.x - bounds.left + padX;
                    float inkY = point.y - bounds.top + padY;

                    strokeBuilder.addPoint(Ink.Point.create(inkX, inkY, currentTime));
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

            // This affects inserted-text appearance only. Recognition itself receives
            // the original ordered points and timestamps without per-character sizing.
            float fontSize = Math.max(26f, Math.min(65f, bounds.height() * 0.85f));

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

            // ML Kit returns its candidates in descending likelihood order. Preserve
            // the transcript and order verbatim; speculative lexical, spacing, and
            // geometric rules were silently changing valid handwriting.
            String finalText = text;
            List<String> verifiedAlternatives = new ArrayList<>(rerankedAlternatives);
            if (!verifiedAlternatives.contains(finalText)) verifiedAlternatives.add(0, finalText);

            final float finalFontSize = fontSize;
            final String postText = finalText;
            String computedLang = lang;
            if (postText.matches("^[0-9\\s.,\\-+]+$") || postText.matches("^[a-zA-Z0-9\\s.,\\-+!?'\"()]+$")) {
                computedLang = "ENGLISH";
            }
            final String finalLang = computedLang;
            final RectF finalBounds = new RectF(origBounds);
            final float finalStartX = startX;
            final float finalStartY = startY;

            MAIN.post(() -> callback.onSuccess(
                    new RecognitionResult(postText, finalLang, finalBounds, finalFontSize,
                            verifiedAlternatives, 0f, finalStartX, finalStartY)));
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
        LinguisticDictionary dictionary = context != null
                ? LinguisticDictionary.getInstance(context) : null;
        if (dictionary != null && "MARATHI".equalsIgnoreCase(language)) {
            for (String candidate : alternatives) {
                for (String orthographic : dictionary.suggestMarathiOrthographicVariants(candidate, 4)) {
                    if (!pool.contains(orthographic) && pool.size() < MAX_REVIEW_CANDIDATES) {
                        pool.add(orthographic);
                    }
                }
            }
        }
        // User-taught corrections remain available as candidates. Grammar can reorder
        // a model alternative only when lexical and structural evidence is substantial.
        UserHandwritingModel userModel = (context != null) ? UserHandwritingModel.getInstance(context) : null;
        if (userModel != null) {
            for (String cand : alternatives) {
                String learned = userModel.predictLearnedCorrection(cand, strokes);
                if (learned != null && !learned.trim().isEmpty() && !pool.contains(learned)) {
                    pool.add(learned);
                }
            }
            pool = rerankPersonalContext(pool, userModel, preContext);
        }
        pool = rerankWithGrammar(context, pool, language);
        return rerankClearAnusvarAlternatives(strokes, pool, language);
    }

    /** Promote a non-primary hypothesis only when lexical plus grammar evidence is substantial. */
    private static List<String> rerankWithGrammar(Context context, List<String> candidates, String language) {
        if (candidates == null || candidates.size() < 2
                || !("ENGLISH".equalsIgnoreCase(language) || "MARATHI".equalsIgnoreCase(language))) return candidates;
        LinguisticDictionary dictionary = context != null ? LinguisticDictionary.getInstance(context) : null;
        if (dictionary == null) return candidates;
        String primary = candidates.get(0);
        int primaryScore = dictionary.scoreLine(primary, language);
        String best = primary;
        int bestScore = primaryScore;
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            if ("ENGLISH".equalsIgnoreCase(language) && !containsLatinLetters(candidate)) continue;
            if ("MARATHI".equalsIgnoreCase(language) && !containsDevanagari(candidate)) continue;
            int score = dictionary.scoreLine(candidate, language);
            if (score > bestScore) { best = candidate; bestScore = score; }
        }
        // Require more than a single token's weak morphology score before changing the head.
        if (best.equals(primary) || bestScore < primaryScore + 12) return candidates;
        List<String> ranked = new ArrayList<>(candidates);
        ranked.remove(best);
        ranked.add(0, best);
        return ranked;
    }

    /** Keeps ML Kit's first suggestion fixed; learned phrase context only orders alternatives. */
    private static List<String> rerankPersonalContext(List<String> candidates,
            UserHandwritingModel userModel, String preContext) {
        if (candidates == null || candidates.size() < 2 || userModel == null) return candidates;
        List<String> ranked = new ArrayList<>(candidates);
        String primary = ranked.remove(0);
        Map<String, Integer> originalRanks = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) originalRanks.putIfAbsent(ranked.get(i), i);
        ranked.sort((left, right) -> {
            int leftScore = userModel.getPersonalizedContextScore(left, preContext);
            int rightScore = userModel.getPersonalizedContextScore(right, preContext);
            if (leftScore != rightScore) return Integer.compare(rightScore, leftScore);
            return Integer.compare(originalRanks.getOrDefault(left, Integer.MAX_VALUE),
                    originalRanks.getOrDefault(right, Integer.MAX_VALUE));
        });
        ranked.add(0, primary);
        return ranked;
    }

    /**
     * A tiny detached dot above Devanagari handwriting is strong evidence for anusvara.
     * Reorder only model-provided alternatives in that narrow case; never invent text or
     * let broad line geometry change unrelated matras or consonants.
     */
    private static List<String> rerankClearAnusvarAlternatives(List<Stroke> strokes,
                                                               List<String> candidates,
                                                               String language) {
        if (strokes == null || candidates == null || candidates.size() < 2
                || !("MARATHI".equalsIgnoreCase(language) || "HINDI".equalsIgnoreCase(language))
                || !hasClearDetachedAnusvarDot(strokes)) {
            return candidates;
        }

        boolean hasDevanagariCandidate = false;
        for (String candidate : candidates) {
            if (containsDevanagari(candidate)) {
                hasDevanagariCandidate = true;
                break;
            }
        }
        if (!hasDevanagariCandidate) return candidates;

        Map<String, Integer> originalRanks = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            originalRanks.putIfAbsent(candidates.get(i), i);
        }
        List<String> ranked = new ArrayList<>(candidates);
        ranked.sort((left, right) -> {
            boolean leftHasAnusvar = left != null && left.indexOf('\u0902') >= 0;
            boolean rightHasAnusvar = right != null && right.indexOf('\u0902') >= 0;
            if (leftHasAnusvar != rightHasAnusvar) return leftHasAnusvar ? -1 : 1;
            return Integer.compare(originalRanks.getOrDefault(left, Integer.MAX_VALUE),
                    originalRanks.getOrDefault(right, Integer.MAX_VALUE));
        });
        return ranked;
    }

    private static boolean hasClearDetachedAnusvarDot(List<Stroke> strokes) {
        RectF allInk = boundsOf(strokes);
        if (allInk == null || allInk.width() < 8f || allInk.height() < 12f) return false;
        float textHeight = allInk.height();
        float maxDotExtent = Math.max(4f, textHeight * 0.18f);

        for (Stroke candidate : strokes) {
            if (candidate == null) continue;
            List<DrawingPoint> points = candidate.getRecognitionPoints();
            if (points == null || points.isEmpty()) continue;

            RectF dotBounds = rawBounds(points);
            if (dotBounds == null || dotBounds.width() > maxDotExtent
                    || dotBounds.height() > maxDotExtent) continue;

            float arcLength = 0f;
            for (int i = 1; i < points.size(); i++) {
                DrawingPoint before = points.get(i - 1);
                DrawingPoint after = points.get(i);
                arcLength += (float) Math.hypot(after.x - before.x, after.y - before.y);
            }
            if (points.size() > 8 && arcLength > textHeight * 0.20f) continue;
            float dotX = dotBounds.centerX();
            float dotY = dotBounds.centerY();
            if (dotY > allInk.top + textHeight * 0.46f) continue;

            RectF bodyBounds = new RectF();
            boolean hasBody = false;
            for (Stroke other : strokes) {
                if (other == null || other == candidate) continue;
                List<DrawingPoint> bodyPoints = other.getRecognitionPoints();
                if (bodyPoints == null) continue;
                RectF part = rawBounds(bodyPoints);
                if (part == null || part.height() < textHeight * 0.30f) continue;
                if (!hasBody) {
                    bodyBounds.set(part);
                    hasBody = true;
                } else {
                    bodyBounds.union(part);
                }
            }
            if (!hasBody || bodyBounds.height() < textHeight * 0.40f) continue;

            float horizontalAllowance = Math.max(3f, bodyBounds.height() * 0.18f);
            boolean aboveBody = dotY < bodyBounds.top + bodyBounds.height() * 0.42f;
            boolean alignedWithText = dotX >= bodyBounds.left - horizontalAllowance
                    && dotX <= bodyBounds.right + horizontalAllowance;
            if (aboveBody && alignedWithText) return true;
        }
        return false;
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

    /**
     * Recognize a standalone arrow made from a shaft plus a two-arm arrowhead.
     * A three-stroke arrow has one stroke per component; a two-stroke arrow has
     * a separate shaft and a V-shaped arrowhead. Geometry is measured from the
     * original input samples so pen smoothing and zoom do not alter recognition.
     */
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
            DrawingPoint tip = tipAtEnd == 1 ? shaftEnd : shaftStart;
            DrawingPoint tail = tipAtEnd == 1 ? shaftStart : shaftEnd;
            float dirX = (tip.x - tail.x) / shaftLength;
            float dirY = (tip.y - tail.y) / shaftLength;
            if (hasArrowhead(parts, shaftIndex, tip, dirX, dirY, majorAxis, tolerance)) {
                return arrowGlyph(dirX, dirY);
            }
        }
        return null;
    }

    private static boolean hasArrowhead(List<List<DrawingPoint>> parts, int shaftIndex,
                                        DrawingPoint tip, float dirX, float dirY,
                                        float majorAxis, float tolerance) {
        if (parts.size() == 3) {
            List<float[]> arms = new ArrayList<>(2);
            for (int i = 0; i < parts.size(); i++) {
                if (i == shaftIndex) continue;
                float[] arm = getArmFromTip(parts.get(i), tip, dirX, dirY,
                        majorAxis, tolerance);
                if (arm == null) return false;
                arms.add(arm);
            }
            return areOppositeArrowArms(arms.get(0), arms.get(1));
        }

        // A V can be drawn as a single polyline. Its nearest point to the shaft
        // tip must be internal, with both outer ends behind the tip on opposite
        // sides of the shaft.
        List<DrawingPoint> head = parts.get(shaftIndex == 0 ? 1 : 0);
        int nearestIndex = -1;
        float nearestDistance = Float.MAX_VALUE;
        for (int i = 0; i < head.size(); i++) {
            float distance = pointDistance(head.get(i), tip);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        if (nearestIndex <= 0 || nearestIndex >= head.size() - 1
                || nearestDistance > tolerance) return false;
        float[] firstArm = armVector(tip, head.get(0), dirX, dirY, majorAxis);
        float[] secondArm = armVector(tip, head.get(head.size() - 1), dirX, dirY, majorAxis);
        return firstArm != null && secondArm != null
                && areOppositeArrowArms(firstArm, secondArm);
    }

    /** Returns [direction dot, signed cross] for an arm that connects to the tip. */
    private static float[] getArmFromTip(List<DrawingPoint> points, DrawingPoint tip,
                                         float dirX, float dirY, float majorAxis,
                                         float tolerance) {
        if (points == null || points.size() < 2) return null;
        DrawingPoint first = points.get(0);
        DrawingPoint last = points.get(points.size() - 1);
        float firstDistance = pointDistance(first, tip);
        float lastDistance = pointDistance(last, tip);
        DrawingPoint far;
        if (Math.min(firstDistance, lastDistance) > tolerance) return null;
        far = firstDistance <= lastDistance ? last : first;
        return armVector(tip, far, dirX, dirY, majorAxis);
    }

    private static float[] armVector(DrawingPoint tip, DrawingPoint far,
                                     float dirX, float dirY, float majorAxis) {
        float x = far.x - tip.x;
        float y = far.y - tip.y;
        float length = (float) Math.hypot(x, y);
        if (length < majorAxis * 0.08f || length > majorAxis * 0.70f) return null;
        float dot = (x * dirX + y * dirY) / length;
        float cross = (dirX * y - dirY * x) / length;
        if (dot > -0.08f || Math.abs(cross) < 0.18f) return null;
        return new float[]{dot, cross};
    }

    private static boolean areOppositeArrowArms(float[] first, float[] second) {
        return first != null && second != null && first[1] * second[1] < 0f;
    }

    private static float strokeArcLength(List<DrawingPoint> points) {
        float length = 0f;
        for (int i = 1; i < points.size(); i++) {
            length += pointDistance(points.get(i - 1), points.get(i));
        }
        return length;
    }

    private static float pointDistance(DrawingPoint first, DrawingPoint second) {
        return (float) Math.hypot(second.x - first.x, second.y - first.y);
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
