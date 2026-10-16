package org.opennotes.app.model;

import android.graphics.RectF;
import org.json.JSONException;
import org.json.JSONObject;

public class ImageItem {
    public String imagePath;
    public float x;
    public float y;
    public float width;
    public float height;

    public ImageItem(String imagePath, float x, float y, float width, float height) {
        this.imagePath = imagePath;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    private transient RectF cachedBounds;
    public void markDirty() {
        cachedBounds = null;
    }
    public RectF getBounds() {
        if (cachedBounds == null) {
            cachedBounds = new RectF(x, y, x + width, y + height);
        }
        return cachedBounds;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("imagePath", imagePath != null ? imagePath : "");
        obj.put("x", (double) x);
        obj.put("y", (double) y);
        obj.put("width", (double) width);
        obj.put("height", (double) height);
        return obj;
    }

    public static ImageItem fromJson(JSONObject obj) {
        String path = obj.optString("imagePath", "");
        float x = (float) obj.optDouble("x", 0.0);
        float y = (float) obj.optDouble("y", 0.0);
        float width = (float) obj.optDouble("width", 200.0);
        float height = (float) obj.optDouble("height", 200.0);
        return new ImageItem(path, x, y, width, height);
    }
}
