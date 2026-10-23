package org.opennotes.app.util;

import org.junit.Test;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FastStrokeParserTest {

    @Test
    public void testEmptyAndNull() throws Exception {
        assertTrue(FastStrokeParser.parseStrokes(null).isEmpty());
        assertTrue(FastStrokeParser.parseStrokes("").isEmpty());
        assertTrue(FastStrokeParser.parseStrokes("   ").isEmpty());
        assertTrue(FastStrokeParser.parseStrokes("[]").isEmpty());
        assertTrue(FastStrokeParser.parseStrokes(" [  ] ").isEmpty());
    }

    @Test
    public void testRoundTripSingleStroke() throws Exception {
        Stroke stroke = new Stroke(0xFFFF0000, 4.5f, DrawingTool.FOUNTAIN);
        stroke.lineStyle = Stroke.LineStyle.DASH;
        stroke.points.add(new DrawingPoint(10.5f, 20.25f, 0.6f, 1000L));
        stroke.points.add(new DrawingPoint(15.0f, 25.5f, 0.75f, 1010L));
        stroke.addRecognitionPoint(10.5f, 20.25f, 0.6f, 1000L);
        stroke.addRecognitionPoint(15.0f, 25.5f, 0.75f, 1010L);

        List<Stroke> input = new ArrayList<>();
        input.add(stroke);

        StringBuilder sb = new StringBuilder();
        FastStrokeParser.writeStrokes(input, sb);
        String json = sb.toString();

        List<Stroke> parsed = FastStrokeParser.parseStrokes(json);
        assertEquals(1, parsed.size());

        Stroke res = parsed.get(0);
        assertEquals(stroke.color, res.color);
        assertEquals(stroke.width, res.width, 0.001f);
        assertEquals(stroke.tool, res.tool);
        assertEquals(stroke.lineStyle, res.lineStyle);

        assertEquals(2, res.points.size());
        assertEquals(10.5f, res.points.get(0).x, 0.001f);
        assertEquals(20.25f, res.points.get(0).y, 0.001f);
        assertEquals(0.6f, res.points.get(0).pressure, 0.001f);
        assertEquals(1000L, res.points.get(0).timeMillis);

        assertEquals(15.0f, res.points.get(1).x, 0.001f);
        assertEquals(25.5f, res.points.get(1).y, 0.001f);
        assertEquals(0.75f, res.points.get(1).pressure, 0.001f);
        assertEquals(1010L, res.points.get(1).timeMillis);

        assertNotNull(res.recognitionPoints);
        assertEquals(2, res.recognitionPoints.size());
    }

    @Test
    public void testHighVolumeParsingSpeed() throws Exception {
        // Generate 100 strokes with 100 points each = 10,000 points
        List<Stroke> strokes = new ArrayList<>();
        for (int s = 0; s < 100; s++) {
            Stroke stroke = new Stroke(0xFF123456 + s, 3.0f, DrawingTool.PEN);
            for (int p = 0; p < 100; p++) {
                stroke.points.add(new DrawingPoint(s * 10f + p, s * 20f + p, 0.5f, 100000L + p * 16));
            }
            strokes.add(stroke);
        }

        StringBuilder sb = new StringBuilder();
        FastStrokeParser.writeStrokes(strokes, sb);
        String json = sb.toString();

        // Warm-up pass to let JVM load classes and JIT compile
        FastStrokeParser.parseStrokes(json);

        long start = System.nanoTime();
        List<Stroke> parsed = FastStrokeParser.parseStrokes(json);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(100, parsed.size());
        assertEquals(100, parsed.get(0).points.size());
        assertTrue("Parsing 10,000 points should take under 100ms, took: " + elapsedMs + "ms", elapsedMs < 100);
    }
}
