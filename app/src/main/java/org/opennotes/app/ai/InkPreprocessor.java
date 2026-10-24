package org.opennotes.app.ai;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/** Copies captured recognition samples without smoothing, resampling, or changing their times. */
public final class InkPreprocessor {
    private InkPreprocessor() { }

    public static List<Stroke> prepare(List<Stroke> source) {
        List<Stroke> output = new ArrayList<>();
        if (source == null) return output;
        for (Stroke stroke : source) {
            if (stroke == null) continue;
            List<DrawingPoint> raw = stroke.getRecognitionPoints();
            if (raw == null || raw.isEmpty()) raw = stroke.points;
            if (raw == null || raw.isEmpty()) continue;
            List<DrawingPoint> clean = cleanPoints(raw);
            if (clean.isEmpty()) continue;
            Stroke copy = new Stroke(stroke.color, stroke.width, stroke.tool);
            copy.lineStyle = stroke.lineStyle;
            copy.brushTextureStyle = stroke.brushTextureStyle;
            copy.brushFiberDensity = stroke.brushFiberDensity;
            for (DrawingPoint point : clean) {
                copy.addPoint(point.x, point.y, point.pressure, point.timeMillis);
                copy.addRecognitionPoint(point.x, point.y, point.pressure, point.timeMillis);
            }
            output.add(copy);
        }
        return output;
    }

    private static List<DrawingPoint> cleanPoints(List<DrawingPoint> raw) {
        List<DrawingPoint> valid = new ArrayList<>(raw.size());
        for (DrawingPoint point : raw) {
            if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y)) continue;
            valid.add(new DrawingPoint(point.x, point.y,
                    point.pressure,
                    point.timeMillis));
        }
        return valid;
    }
}
