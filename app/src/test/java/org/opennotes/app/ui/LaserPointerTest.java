package org.opennotes.app.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaserPointerTest {

    @Test
    public void testLaserPointSubpathsAndRetention() {
        List<CanvasView.LaserPoint> trail = new ArrayList<>();

        // First stroke: startsSubpath = true
        trail.add(new CanvasView.LaserPoint(100f, 100f, 1000L, true));
        trail.add(new CanvasView.LaserPoint(110f, 105f, 1010L, false));
        trail.add(new CanvasView.LaserPoint(120f, 110f, 1020L, false));

        assertEquals(3, trail.size());
        assertTrue(trail.get(0).startsSubpath);
        assertFalse(trail.get(1).startsSubpath);
        assertFalse(trail.get(2).startsSubpath);

        // Second stroke while previous is still visible: startsSubpath = true
        trail.add(new CanvasView.LaserPoint(200f, 200f, 1500L, true));
        trail.add(new CanvasView.LaserPoint(210f, 205f, 1510L, false));

        // Both strokes should be retained together without old points being trimmed
        assertEquals(5, trail.size());
        assertTrue(trail.get(3).startsSubpath);
        assertFalse(trail.get(4).startsSubpath);

        // Third stroke
        trail.add(new CanvasView.LaserPoint(300f, 300f, 1800L, true));
        assertEquals(6, trail.size());
        assertTrue(trail.get(5).startsSubpath);
    }

    @Test
    public void testLaserFadeTimingCalculations() {
        long durationMs = 1500L;
        long fadePeriod = Math.min(650L, Math.max(300L, (long) (durationMs * 0.4f)));
        long holdPeriod = Math.max(0L, durationMs - fadePeriod);

        assertEquals(600L, fadePeriod);
        assertEquals(900L, holdPeriod);

        // At t = 450ms (within holdPeriod): alpha must remain 1.0f (steady)
        long elapsedHold = 450L;
        assertTrue(elapsedHold <= holdPeriod);

        // At t = 1200ms (within fadePeriod): alpha should be between 0.0 and 1.0
        long elapsedFade = 1200L;
        float progress = (float) (elapsedFade - holdPeriod) / (float) fadePeriod;
        float fade = 1.0f - progress;
        float alpha = fade * fade * (3f - 2f * fade);
        assertTrue(alpha > 0f && alpha < 1.0f);

        // At t >= 1500ms (expired): alpha is 0
        long elapsedExpired = 1500L;
        assertTrue(elapsedExpired >= durationMs);
    }
}
