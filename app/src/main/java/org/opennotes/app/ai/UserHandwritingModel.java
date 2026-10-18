package org.opennotes.app.ai;

import android.content.Context;
import android.graphics.RectF;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-Device Real-Time Self-Learning & Adaptive Handwriting Model.
 * Learns the user's personal handwriting quirks, cursive ligatures, and doctor's handwriting.
 * Enables exporting and importing .onhw training profile files so users can share their learned styles.
 */
public class UserHandwritingModel {
    private static final String TAG = "UserHandwritingModel";
    private static final String PROFILE_FILENAME = "user_handwriting_profile.onhw";
    private static final int MAX_PROFILE_CHARS = 32 * 1024 * 1024;
    private static final int MAX_IMPORTED_ENTRIES = 50_000;
    private static volatile UserHandwritingModel sInstance;

    public static class LearnedPrototype {
        public String intendedText;
        public String recognizedAs;
        public int strokeCount;
        public float aspectRatio;
        public boolean hasLoop;
        public float[] normalizedFeatures; // 32-dim resampled normalized vector
        // Stroke-separated trajectories avoid inventing a line across pen lifts.
        // Older profiles omit this field and continue to use normalizedFeatures.
        public float[][] strokeFeatures;
        public long timestamp;

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("intendedText", intendedText != null ? intendedText : "");
            obj.put("recognizedAs", recognizedAs != null ? recognizedAs : "");
            obj.put("strokeCount", strokeCount);
            obj.put("aspectRatio", (double) aspectRatio);
            obj.put("hasLoop", hasLoop);
            obj.put("timestamp", timestamp);
            if (normalizedFeatures != null) {
                JSONArray arr = new JSONArray();
                for (float f : normalizedFeatures) arr.put((double) f);
                obj.put("features", arr);
            }
            if (strokeFeatures != null) {
                JSONArray strokes = new JSONArray();
                for (float[] stroke : strokeFeatures) {
                    JSONArray points = new JSONArray();
                    if (stroke != null) {
                        for (float value : stroke) points.put((double) value);
                    }
                    strokes.put(points);
                }
                obj.put("strokeFeatures", strokes);
            }
            return obj;
        }

        public static LearnedPrototype fromJson(JSONObject obj) {
            LearnedPrototype p = new LearnedPrototype();
            p.intendedText = obj.optString("intendedText", "");
            p.recognizedAs = obj.optString("recognizedAs", "");
            p.strokeCount = obj.optInt("strokeCount", 1);
            p.aspectRatio = (float) obj.optDouble("aspectRatio", 1.0);
            p.hasLoop = obj.optBoolean("hasLoop", false);
            p.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
            JSONArray arr = obj.optJSONArray("features");
            if (arr != null) {
                p.normalizedFeatures = new float[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    p.normalizedFeatures[i] = (float) arr.optDouble(i, 0.0);
                }
            }
            JSONArray strokeArray = obj.optJSONArray("strokeFeatures");
            if (strokeArray != null) {
                p.strokeFeatures = new float[strokeArray.length()][];
                for (int i = 0; i < strokeArray.length(); i++) {
                    JSONArray points = strokeArray.optJSONArray(i);
                    if (points == null) continue;
                    p.strokeFeatures[i] = new float[points.length()];
                    for (int j = 0; j < points.length(); j++) {
                        p.strokeFeatures[i][j] = (float) points.optDouble(j, 0.0);
                    }
                }
            }
            return p;
        }
    }

    private final Context context;
    // Normalized lowercase token mapping: "ave" -> "are", "peop1e" -> "people", "clate" -> "date"
    private final Map<String, String> personalizedReplacements = new HashMap<>();
    // Exemplar stroke prototypes
    private final List<LearnedPrototype> learnedPrototypes = new ArrayList<>();
    // User-specific custom vocabulary additions
    private final List<String> customVocabulary = new ArrayList<>();
    // Adjacent tokens collected from text the user explicitly corrected or taught.
    private final Map<String, Integer> learnedBigrams = new HashMap<>();

    private UserHandwritingModel(Context context) {
        this.context = context.getApplicationContext();
        loadFromDisk();
    }

    public static UserHandwritingModel getInstance(Context context) {
        if (sInstance == null) {
            synchronized (UserHandwritingModel.class) {
                if (sInstance == null) {
                    sInstance = new UserHandwritingModel(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * Real-time training: User corrects a recognized word/symbol or teaches their personal style.
     */
    public synchronized void learnCorrection(String recognizedAs, String intendedText, List<Stroke> strokes) {
        if (intendedText == null || intendedText.trim().isEmpty()) return;
        String trimmedIntended = intendedText.trim();
        String trimmedRecognized = recognizedAs != null ? recognizedAs.trim() : "";

        // 1. Learn lexical substitution
        if (!trimmedRecognized.isEmpty() && !trimmedRecognized.equalsIgnoreCase(trimmedIntended)) {
            personalizedReplacements.put(trimmedRecognized.toLowerCase(Locale.US), trimmedIntended);
        }

        // 2. Add to user vocabulary
        if (!customVocabulary.contains(trimmedIntended)) {
            customVocabulary.add(trimmedIntended);
        }
        learnBigrams(trimmedIntended);

        // 3. Extract geometric features from the ink strokes
        if (strokes != null && !strokes.isEmpty()) {
            LearnedPrototype proto = extractPrototype(trimmedIntended, trimmedRecognized, strokes);
            if (proto != null) {
                // Keep max 250 most relevant prototypes to keep matching fast
                if (learnedPrototypes.size() >= 250) {
                    learnedPrototypes.remove(0);
                }
                learnedPrototypes.add(proto);
            }
        }

        // Persist to disk immediately
        saveToDisk();
    }

    /**
     * Check if the user has taught a replacement or prototype for this recognized string or ink.
     */
    public synchronized String predictLearnedCorrection(String candidate, List<Stroke> strokes) {
        if (candidate == null) return null;
        String clean = candidate.trim().toLowerCase(Locale.US);

        // 1. Direct substitution lookup
        if (personalizedReplacements.containsKey(clean)) {
            return matchCasing(personalizedReplacements.get(clean), candidate);
        }

        // 2. Shape similarity match if strokes are provided
        if (strokes != null && !strokes.isEmpty() && !learnedPrototypes.isEmpty()) {
            LearnedPrototype inputProto = extractPrototype("", candidate, strokes);
            if (inputProto != null && inputProto.normalizedFeatures != null) {
                Map<String, Float> labelWeights = new HashMap<>();
                Map<String, Integer> labelSupport = new HashMap<>();
                Map<String, Float> labelBestDistance = new HashMap<>();
                for (LearnedPrototype stored : learnedPrototypes) {
                    if (stored.normalizedFeatures == null || stored.intendedText == null
                            || stored.intendedText.isEmpty()) continue;
                    float dist = computeFeatureDistance(inputProto, stored);
                    if (!Float.isFinite(dist) || dist >= 0.32f) continue;
                    String label = stored.intendedText;
                    float weight = 1f / (0.05f + dist);
                    labelWeights.put(label, labelWeights.getOrDefault(label, 0f) + weight);
                    labelSupport.put(label, labelSupport.getOrDefault(label, 0) + 1);
                    labelBestDistance.put(label, Math.min(labelBestDistance.getOrDefault(label,
                            Float.MAX_VALUE), dist));
                }

                String bestLabel = null;
                float bestWeight = 0f;
                float runnerUpWeight = 0f;
                for (Map.Entry<String, Float> entry : labelWeights.entrySet()) {
                    if (entry.getValue() > bestWeight) {
                        runnerUpWeight = bestWeight;
                        bestWeight = entry.getValue();
                        bestLabel = entry.getKey();
                    } else if (entry.getValue() > runnerUpWeight) {
                        runnerUpWeight = entry.getValue();
                    }
                }
                if (bestLabel != null) {
                    int support = labelSupport.getOrDefault(bestLabel, 0);
                    float closest = labelBestDistance.getOrDefault(bestLabel, Float.MAX_VALUE);
                    boolean repeatedEvidence = support >= 2 && bestWeight >= runnerUpWeight * 1.15f;
                    boolean nearExactEvidence = closest < 0.13f
                            && bestWeight >= runnerUpWeight * 1.35f;
                    if (repeatedEvidence || nearExactEvidence) {
                        return matchCasing(bestLabel, candidate);
                    }
                }
            }
        }

        return null;
    }

    public synchronized Map<String, String> getPersonalizedReplacements() {
        return new HashMap<>(personalizedReplacements);
    }

    public synchronized List<String> getCustomVocabulary() {
        return new ArrayList<>(customVocabulary);
    }

    public synchronized int getLearnedBigramCount() {
        return learnedBigrams.size();
    }

    /** Scores phrase alternatives from locally learned word-pair context. */
    public synchronized int getPersonalizedContextScore(String candidate, String preContext) {
        List<String> candidateTokens = tokenize(candidate);
        if (candidateTokens.isEmpty()) return 0;
        List<String> contextTokens = tokenize(preContext);
        String previous = contextTokens.isEmpty() ? null : contextTokens.get(contextTokens.size() - 1);
        int score = 0;
        for (String token : candidateTokens) {
            if (previous != null) {
                int count = learnedBigrams.getOrDefault(previous + " " + token, 0);
                score += Math.min(3, count) * 10;
            }
            previous = token;
        }
        return score;
    }

    public synchronized int getLearnedCount() {
        return personalizedReplacements.size() + learnedPrototypes.size()
                + customVocabulary.size() + learnedBigrams.size();
    }

    public synchronized void clearLearnedData() {
        personalizedReplacements.clear();
        learnedPrototypes.clear();
        customVocabulary.clear();
        learnedBigrams.clear();
        saveToDisk();
    }

    private void learnBigrams(String text) {
        List<String> tokens = tokenize(text);
        for (int i = 1; i < tokens.size(); i++) {
            String key = tokens.get(i - 1) + " " + tokens.get(i);
            learnedBigrams.put(key, Math.min(100, learnedBigrams.getOrDefault(key, 0) + 1));
        }
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return tokens;
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (String part : parts) if (!part.isEmpty()) tokens.add(part);
        return tokens;
    }

    private static String matchCasing(String target, String source) {
        if (target == null || source == null || source.isEmpty()) return target;
        if (Character.isUpperCase(source.charAt(0)) && target.length() > 0) {
            return Character.toUpperCase(target.charAt(0)) + (target.length() > 1 ? target.substring(1) : "");
        }
        return target;
    }

    private LearnedPrototype extractPrototype(String intended, String recognized, List<Stroke> strokes) {
        try {
            InkNormalizer.NormalizedInk norm = InkNormalizer.normalizeInk(strokes);
            List<Stroke> effectiveStrokes = (norm != null && norm.strokes != null && !norm.strokes.isEmpty())
                    ? norm.strokes : strokes;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            List<DrawingPoint> allPoints = new ArrayList<>();
            List<List<DrawingPoint>> pointStrokes = new ArrayList<>();
            for (Stroke s : effectiveStrokes) {
                if (s == null) continue;
                List<DrawingPoint> pts = s.getRecognitionPoints();
                if (pts == null || pts.isEmpty()) pts = s.points;
                if (pts != null) {
                    List<DrawingPoint> validStroke = new ArrayList<>();
                    for (DrawingPoint p : pts) {
                        if (p == null || !Float.isFinite(p.x) || !Float.isFinite(p.y)) continue;
                        validStroke.add(p);
                        allPoints.add(p);
                        minX = Math.min(minX, p.x);
                        minY = Math.min(minY, p.y);
                        maxX = Math.max(maxX, p.x);
                        maxY = Math.max(maxY, p.y);
                    }
                    if (!validStroke.isEmpty()) pointStrokes.add(validStroke);
                }
            }
            if (allPoints.size() < 2 || minX == Float.MAX_VALUE) return null;

            float w = Math.max(1f, maxX - minX);
            float h = Math.max(1f, maxY - minY);

            LearnedPrototype proto = new LearnedPrototype();
            proto.intendedText = intended;
            proto.recognizedAs = recognized;
            proto.strokeCount = strokes.size();
            proto.aspectRatio = w / h;
            proto.timestamp = System.currentTimeMillis();

            // Detect loops in ink
            proto.hasLoop = detectLoop(allPoints);

            // Resample ink trajectory into canonical 16-point (32 floats: x, y) normalized feature vector
            proto.normalizedFeatures = resampleAndNormalize(allPoints, minX, minY, w, h, 16);
            proto.strokeFeatures = new float[pointStrokes.size()][];
            for (int i = 0; i < pointStrokes.size(); i++) {
                proto.strokeFeatures[i] = resampleAndNormalize(pointStrokes.get(i), minX, minY, w, h, 8);
            }

            return proto;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract prototype", e);
            return null;
        }
    }

    private boolean detectLoop(List<DrawingPoint> points) {
        if (points == null || points.size() < 8) return false;
        int step = Math.max(1, points.size() / 20);
        for (int i = 0; i < points.size() - 6; i += step) {
            DrawingPoint p1 = points.get(i);
            for (int j = i + 5; j < points.size(); j += step) {
                DrawingPoint p2 = points.get(j);
                float distSq = (p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y);
                if (distSq < 100f) { // Points came close together after traveling
                    return true;
                }
            }
        }
        return false;
    }

    private float[] resampleAndNormalize(List<DrawingPoint> points, float minX, float minY, float w, float h, int sampleCount) {
        float[] features = new float[sampleCount * 2];
        if (points.isEmpty()) return features;

        for (int i = 0; i < sampleCount; i++) {
            float t = (float) i / (float) (sampleCount - 1);
            int idx = Math.min(points.size() - 1, Math.round(t * (points.size() - 1)));
            DrawingPoint p = points.get(idx);
            features[i * 2] = (p.x - minX) / w;
            features[i * 2 + 1] = (p.y - minY) / h;
        }
        return features;
    }

    private float computeFeatureDistance(LearnedPrototype a, LearnedPrototype b) {
        float strokeAwareDistance = computeStrokeSequenceDistance(a.strokeFeatures, b.strokeFeatures);
        if (strokeAwareDistance >= 0f) {
            float aspectDist = Math.abs(a.aspectRatio - b.aspectRatio)
                    / Math.max(0.1f, a.aspectRatio + b.aspectRatio);
            float loopDist = (a.hasLoop == b.hasLoop) ? 0f : 0.2f;
            float strokeCountDist = Math.min(0.2f, Math.abs(a.strokeCount - b.strokeCount) * 0.08f);
            return strokeAwareDistance * 0.6f + aspectDist * 0.2f + loopDist + strokeCountDist;
        }

        float[] f1 = a.normalizedFeatures;
        float[] f2 = b.normalizedFeatures;
        if (f1 == null || f2 == null || f1.length != f2.length) return 1.0f;

        float sumDiff = 0f;
        for (int i = 0; i < f1.length; i++) {
            float d = f1[i] - f2[i];
            sumDiff += d * d;
        }
        float shapeDist = (float) Math.sqrt(sumDiff / f1.length);
        float aspectDist = Math.abs(a.aspectRatio - b.aspectRatio) / Math.max(0.1f, a.aspectRatio + b.aspectRatio);
        float loopDist = (a.hasLoop == b.hasLoop) ? 0f : 0.2f;
        float strokeCountDist = Math.min(0.2f, Math.abs(a.strokeCount - b.strokeCount) * 0.08f);

        return shapeDist * 0.6f + aspectDist * 0.2f + loopDist + strokeCountDist;
    }

    /**
     * Aligns stroke sequences with dynamic programming, so a missed or extra pen lift
     * does not shift every later point. Returns -1 when either prototype is legacy.
     */
    private float computeStrokeSequenceDistance(float[][] first, float[][] second) {
        if (first == null || second == null || first.length == 0 || second.length == 0) return -1f;
        int n = first.length;
        int m = second.length;
        float[][] cost = new float[n + 1][m + 1];
        final float gapPenalty = 0.24f;
        for (int i = 1; i <= n; i++) cost[i][0] = i * gapPenalty;
        for (int j = 1; j <= m; j++) cost[0][j] = j * gapPenalty;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                float match = strokeShapeDistance(first[i - 1], second[j - 1]);
                cost[i][j] = Math.min(cost[i - 1][j - 1] + match,
                        Math.min(cost[i - 1][j] + gapPenalty, cost[i][j - 1] + gapPenalty));
            }
        }
        return cost[n][m] / Math.max(n, m);
    }

    private float strokeShapeDistance(float[] first, float[] second) {
        if (first == null || second == null || first.length < 2 || second.length < 2) return 1f;
        int samples = Math.min(first.length, second.length) / 2;
        if (samples == 0) return 1f;
        float sum = 0f;
        for (int i = 0; i < samples; i++) {
            float dx = first[i * 2] - second[i * 2];
            float dy = first[i * 2 + 1] - second[i * 2 + 1];
            sum += (float) Math.hypot(dx, dy);
        }
        return sum / samples;
    }

    private synchronized void saveToDisk() {
        try {
            File file = new File(context.getFilesDir(), PROFILE_FILENAME);
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("lastModified", System.currentTimeMillis());

            JSONObject repl = new JSONObject();
            for (Map.Entry<String, String> e : personalizedReplacements.entrySet()) {
                repl.put(e.getKey(), e.getValue());
            }
            root.put("replacements", repl);

            JSONArray protoArr = new JSONArray();
            for (LearnedPrototype p : learnedPrototypes) {
                protoArr.put(p.toJson());
            }
            root.put("prototypes", protoArr);

            JSONArray vocabArr = new JSONArray();
            for (String v : customVocabulary) {
                vocabArr.put(v);
            }
            root.put("vocabulary", vocabArr);

            JSONObject bigrams = new JSONObject();
            for (Map.Entry<String, Integer> entry : learnedBigrams.entrySet()) {
                bigrams.put(entry.getKey(), entry.getValue());
            }
            root.put("bigrams", bigrams);

            AtomicFile atomicFile = new AtomicFile(file);
            FileOutputStream fos = null;
            try {
                fos = atomicFile.startWrite();
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                atomicFile.finishWrite(fos);
            } catch (Exception writeError) {
                if (fos != null) atomicFile.failWrite(fos);
                throw writeError;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving handwriting profile", e);
        }
    }

    private synchronized void loadFromDisk() {
        try {
            File file = new File(context.getFilesDir(), PROFILE_FILENAME);
            if (!file.exists()) return;

            StringBuilder sb = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            importFromJson(root, false);
        } catch (Exception e) {
            Log.e(TAG, "Error loading handwriting profile", e);
        }
    }

    /**
     * Export the learned handwriting profile to any output stream (.onhw file).
     */
    public synchronized void exportProfile(OutputStream outputStream) throws Exception {
        if (outputStream == null) throw new IllegalArgumentException("No destination was selected.");
        JSONObject root = new JSONObject();
        root.put("format", "OPENNOTES_HANDWRITING_PROFILE");
        root.put("version", 2);
        root.put("exportedAt", System.currentTimeMillis());

        JSONObject repl = new JSONObject();
        for (Map.Entry<String, String> e : personalizedReplacements.entrySet()) {
            repl.put(e.getKey(), e.getValue());
        }
        root.put("replacements", repl);

        JSONArray protoArr = new JSONArray();
        for (LearnedPrototype p : learnedPrototypes) {
            protoArr.put(p.toJson());
        }
        root.put("prototypes", protoArr);

        JSONArray vocabArr = new JSONArray();
        for (String v : customVocabulary) {
            vocabArr.put(v);
        }
        root.put("vocabulary", vocabArr);

        JSONObject bigrams = new JSONObject();
        for (Map.Entry<String, Integer> entry : learnedBigrams.entrySet()) {
            bigrams.put(entry.getKey(), entry.getValue());
        }
        root.put("bigrams", bigrams);

        OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
        writer.write(root.toString(2));
        writer.flush();
    }

    /**
     * Import a shared .onhw profile and merge into current user model.
     */
    public synchronized void importProfile(InputStream inputStream) throws Exception {
        if (inputStream == null) throw new IllegalArgumentException("No handwriting profile was selected.");
        StringBuilder sb = new StringBuilder();
        InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            if (sb.length() + read > MAX_PROFILE_CHARS) {
                throw new IllegalArgumentException("This handwriting profile is too large to import.");
            }
            sb.append(buffer, 0, read);
        }

        JSONObject root = new JSONObject(sb.toString());
        String format = root.optString("format", "");
        if (!format.isEmpty() && !"OPENNOTES_HANDWRITING_PROFILE".equals(format)) {
            throw new IllegalArgumentException("This file is not an OpenNotes handwriting profile.");
        }
        int version = root.optInt("version", 1);
        if (version < 1 || version > 2) {
            throw new IllegalArgumentException("This handwriting profile version is not supported.");
        }
        validateProfileSize(root);
        importFromJson(root, true);
        saveToDisk();
    }

    private static void validateProfileSize(JSONObject root) {
        JSONArray prototypes = root.optJSONArray("prototypes");
        JSONArray vocabulary = root.optJSONArray("vocabulary");
        JSONObject replacements = root.optJSONObject("replacements");
        JSONObject bigrams = root.optJSONObject("bigrams");
        if ((prototypes != null && prototypes.length() > MAX_IMPORTED_ENTRIES)
                || (vocabulary != null && vocabulary.length() > MAX_IMPORTED_ENTRIES)
                || (replacements != null && replacements.length() > MAX_IMPORTED_ENTRIES)
                || (bigrams != null && bigrams.length() > MAX_IMPORTED_ENTRIES)) {
            throw new IllegalArgumentException("This handwriting profile contains too many learned entries.");
        }
    }

    private void importFromJson(JSONObject root, boolean merge) {
        if (!merge) {
            personalizedReplacements.clear();
            learnedPrototypes.clear();
            customVocabulary.clear();
            learnedBigrams.clear();
        }

        JSONObject repl = root.optJSONObject("replacements");
        if (repl != null) {
            JSONArray keys = repl.names();
            if (keys != null) {
                int limit = Math.min(keys.length(), Math.max(0,
                        MAX_IMPORTED_ENTRIES - personalizedReplacements.size()));
                for (int i = 0; i < limit; i++) {
                    String k = keys.optString(i);
                    String v = repl.optString(k);
                    if (k != null && v != null) {
                        personalizedReplacements.put(k.toLowerCase(Locale.US), v);
                    }
                }
            }
        }

        JSONArray protoArr = root.optJSONArray("prototypes");
        if (protoArr != null) {
            int limit = Math.min(protoArr.length(),
                    Math.max(0, MAX_IMPORTED_ENTRIES - learnedPrototypes.size()));
            for (int i = 0; i < limit; i++) {
                JSONObject obj = protoArr.optJSONObject(i);
                if (obj != null) {
                    learnedPrototypes.add(LearnedPrototype.fromJson(obj));
                }
            }
        }

        JSONArray vocabArr = root.optJSONArray("vocabulary");
        if (vocabArr != null) {
            int limit = Math.min(vocabArr.length(),
                    Math.max(0, MAX_IMPORTED_ENTRIES - customVocabulary.size()));
            for (int i = 0; i < limit; i++) {
                String v = vocabArr.optString(i);
                if (v != null && !v.trim().isEmpty() && !customVocabulary.contains(v)) {
                    customVocabulary.add(v.trim());
                }
            }
        }

        JSONObject bigrams = root.optJSONObject("bigrams");
        if (bigrams != null) {
            JSONArray keys = bigrams.names();
            if (keys != null) {
                int limit = Math.min(keys.length(), Math.max(0,
                        MAX_IMPORTED_ENTRIES - learnedBigrams.size()));
                for (int i = 0; i < limit; i++) {
                    String key = keys.optString(i);
                    int count = bigrams.optInt(key, 0);
                    if (key != null && !key.trim().isEmpty() && count > 0) {
                        learnedBigrams.put(key, Math.min(100,
                                learnedBigrams.getOrDefault(key, 0) + count));
                    }
                }
            }
        }
    }
}
