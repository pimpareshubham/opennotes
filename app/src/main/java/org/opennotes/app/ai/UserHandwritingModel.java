package org.opennotes.app.ai;

import android.content.Context;
import android.graphics.RectF;
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
    private static volatile UserHandwritingModel sInstance;

    public static class LearnedPrototype {
        public String intendedText;
        public String recognizedAs;
        public int strokeCount;
        public float aspectRatio;
        public boolean hasLoop;
        public float[] normalizedFeatures; // 32-dim resampled normalized vector
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
                float bestDistance = Float.MAX_VALUE;
                LearnedPrototype bestMatch = null;
                for (LearnedPrototype stored : learnedPrototypes) {
                    if (stored.normalizedFeatures == null) continue;
                    float dist = computeFeatureDistance(inputProto, stored);
                    if (dist < bestDistance) {
                        bestDistance = dist;
                        bestMatch = stored;
                    }
                }
                // If shape is very close to a taught prototype, promote it!
                if (bestMatch != null && bestDistance < 0.28f && bestMatch.intendedText != null && !bestMatch.intendedText.isEmpty()) {
                    return matchCasing(bestMatch.intendedText, candidate);
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

    public synchronized int getLearnedCount() {
        return personalizedReplacements.size() + learnedPrototypes.size() + customVocabulary.size();
    }

    public synchronized void clearLearnedData() {
        personalizedReplacements.clear();
        learnedPrototypes.clear();
        customVocabulary.clear();
        saveToDisk();
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
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            List<DrawingPoint> allPoints = new ArrayList<>();
            for (Stroke s : strokes) {
                if (s == null) continue;
                List<DrawingPoint> pts = s.getRecognitionPoints();
                if (pts == null || pts.isEmpty()) pts = s.points;
                if (pts != null) {
                    allPoints.addAll(pts);
                    for (DrawingPoint p : pts) {
                        minX = Math.min(minX, p.x);
                        minY = Math.min(minY, p.y);
                        maxX = Math.max(maxX, p.x);
                        maxY = Math.max(maxY, p.y);
                    }
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

    private synchronized void saveToDisk() {
        try {
            File file = new File(context.getFilesDir(), PROFILE_FILENAME);
            JSONObject root = new JSONObject();
            root.put("version", 1);
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

            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
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
        JSONObject root = new JSONObject();
        root.put("format", "OPENNOTES_HANDWRITING_PROFILE");
        root.put("version", 1);
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

        OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
        writer.write(root.toString(2));
        writer.flush();
    }

    /**
     * Import a shared .onhw profile and merge into current user model.
     */
    public synchronized void importProfile(InputStream inputStream) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        JSONObject root = new JSONObject(sb.toString());
        importFromJson(root, true);
        saveToDisk();
    }

    private void importFromJson(JSONObject root, boolean merge) {
        if (!merge) {
            personalizedReplacements.clear();
            learnedPrototypes.clear();
            customVocabulary.clear();
        }

        JSONObject repl = root.optJSONObject("replacements");
        if (repl != null) {
            JSONArray keys = repl.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
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
            for (int i = 0; i < protoArr.length(); i++) {
                JSONObject obj = protoArr.optJSONObject(i);
                if (obj != null) {
                    learnedPrototypes.add(LearnedPrototype.fromJson(obj));
                }
            }
        }

        JSONArray vocabArr = root.optJSONArray("vocabulary");
        if (vocabArr != null) {
            for (int i = 0; i < vocabArr.length(); i++) {
                String v = vocabArr.optString(i);
                if (v != null && !v.trim().isEmpty() && !customVocabulary.contains(v)) {
                    customVocabulary.add(v.trim());
                }
            }
        }
    }
}
