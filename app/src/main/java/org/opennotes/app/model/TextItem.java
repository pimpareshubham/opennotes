package org.opennotes.app.model;

import org.opennotes.app.util.TextSpacingCompat;
import org.json.JSONException;
import org.json.JSONObject;

public class TextItem {
    public String text;
    public float x;
    public float y;
    public int color;
    public float fontSize;
    public String fontKey;
    public float rotationAngle;
    /** Letter spacing as an em fraction and word spacing as an em addition. */
    public float letterSpacingEm;
    public float wordSpacingEm;
    /** Generated words share an id so spacing adjustments can preserve phrase layout. */
    public String spacingGroupId;
    public int spacingOrder;

    public TextItem(String text, float x, float y, int color, float fontSize) {
        this(text, x, y, color, fontSize, "KALAM", 0f);
    }

    public TextItem(String text, float x, float y, int color, float fontSize, String fontKey) {
        this(text, x, y, color, fontSize, fontKey, 0f);
    }

    public TextItem(String text, float x, float y, int color, float fontSize, String fontKey, float rotationAngle) {
        this(text, x, y, color, fontSize, fontKey, rotationAngle, 0f, 0f, null, 0);
    }

    public TextItem(String text, float x, float y, int color, float fontSize, String fontKey,
                    float rotationAngle, float letterSpacingEm, float wordSpacingEm,
                    String spacingGroupId, int spacingOrder) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.color = color;
        this.fontSize = fontSize;
        this.fontKey = (fontKey != null && !fontKey.isEmpty()) ? fontKey : "KALAM";
        this.rotationAngle = rotationAngle;
        this.letterSpacingEm = letterSpacingEm;
        this.wordSpacingEm = wordSpacingEm;
        this.spacingGroupId = spacingGroupId;
        this.spacingOrder = spacingOrder;
    }

    private transient android.graphics.RectF cachedBounds;
    private static final android.graphics.Paint MEASURE_PAINT = new android.graphics.Paint();

    public void markDirty() {
        cachedBounds = null;
    }

    public android.graphics.RectF getBounds() {
        if (cachedBounds == null) {
            float width;
            synchronized (MEASURE_PAINT) {
                MEASURE_PAINT.setTextSize(fontSize);
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    MEASURE_PAINT.setLetterSpacing(letterSpacingEm);
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        MEASURE_PAINT.setWordSpacing(wordSpacingEm * fontSize);
                    }
                }
                width = TextSpacingCompat.measureText(MEASURE_PAINT, text,
                        wordSpacingEm * fontSize);
            }
            android.graphics.RectF unrotated = new android.graphics.RectF(x - 4f, y - fontSize - 4f, x + width + 4f, y + 6f);
            if (Math.abs(rotationAngle) < 0.5f) {
                cachedBounds = unrotated;
            } else {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotationAngle, x, y);
                m.mapRect(unrotated);
                cachedBounds = unrotated;
            }
        }
        return cachedBounds;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("text", text);
        obj.put("x", (double) x);
        obj.put("y", (double) y);
        obj.put("color", color);
        obj.put("fontSize", (double) fontSize);
        obj.put("fontKey", fontKey);
        obj.put("rotationAngle", (double) rotationAngle);
        obj.put("letterSpacingEm", (double) letterSpacingEm);
        obj.put("wordSpacingEm", (double) wordSpacingEm);
        if (spacingGroupId != null && !spacingGroupId.isEmpty()) obj.put("spacingGroupId", spacingGroupId);
        obj.put("spacingOrder", spacingOrder);
        return obj;
    }

    public static TextItem fromJson(JSONObject obj) {
        String text = obj.optString("text", "");
        float x = (float) obj.optDouble("x", 50.0);
        float y = (float) obj.optDouble("y", 100.0);
        int color = obj.optInt("color", 0xFF000000);
        float size = (float) obj.optDouble("fontSize", 18.0);
        String font = obj.optString("fontKey", "KALAM");
        float rotation = (float) obj.optDouble("rotationAngle", 0.0);
        float letterSpacing = (float) obj.optDouble("letterSpacingEm", 0.0);
        float wordSpacing = (float) obj.optDouble("wordSpacingEm", 0.0);
        String group = obj.optString("spacingGroupId", null);
        int order = obj.optInt("spacingOrder", 0);
        return new TextItem(text, x, y, color, size, font, rotation,
                letterSpacing, wordSpacing, group, order);
    }
}
