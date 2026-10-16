package org.opennotes.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public class DrawingPoint {
    public float x;
    public float y;
    public float pressure;
    /** Android uptime in milliseconds, or -1 for points loaded from legacy notes. */
    public long timeMillis;

    public DrawingPoint(float x, float y, float pressure) {
        this(x, y, pressure, -1L);
    }

    public DrawingPoint(float x, float y, float pressure, long timeMillis) {
        this.x = x;
        this.y = y;
        this.pressure = pressure;
        this.timeMillis = timeMillis;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("x", (double) x);
        obj.put("y", (double) y);
        obj.put("p", (double) pressure);
        if (timeMillis >= 0L) obj.put("t", timeMillis);
        return obj;
    }

    public static DrawingPoint fromJson(JSONObject obj) {
        float x = (float) obj.optDouble("x", 0.0);
        float y = (float) obj.optDouble("y", 0.0);
        float p = (float) obj.optDouble("p", 1.0);
        return new DrawingPoint(x, y, p, obj.optLong("t", -1L));
    }
}
