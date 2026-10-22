package org.opennotes.app.util;

import org.junit.Test;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotebookFastLoadTest {

    @Test
    public void testPageInitialContentLoadedState() {
        Page page = new Page(100L, 0, PaperTemplate.BLANK, 0xFFFFFFFF, false);
        assertTrue("Newly instantiated page should be marked as content loaded", page.isContentLoaded);
        assertTrue(page.strokes.isEmpty());
        assertTrue(page.shapes.isEmpty());
        assertTrue(page.texts.isEmpty());
        assertTrue(page.images.isEmpty());
    }

    @Test
    public void testPageShellLazyLoadingContract() {
        // Simulates lightweight page shell created by getPagesForNotebookFast
        Page shell = new Page(100L, 5, PaperTemplate.RULED, 0xFF121214, true);
        shell.id = 555L;
        shell.canvasHeight = 2400f;
        shell.isContentLoaded = false;

        assertFalse("Shell should indicate content is not yet loaded from disk", shell.isContentLoaded);
        assertEquals(555L, shell.id);
        assertEquals(5, shell.pageIndex);
        assertEquals(PaperTemplate.RULED, shell.template);
        assertEquals(0xFF121214, shell.backgroundColor);
        assertTrue(shell.isInfinite);
        assertEquals(2400f, shell.canvasHeight, 0.001f);

        // Snapshot preserves isContentLoaded state
        Page snapshot = shell.createSnapshot();
        assertFalse(snapshot.isContentLoaded);
        assertEquals(555L, snapshot.id);

        // Once loaded, content is flagged as loaded
        shell.isContentLoaded = true;
        assertTrue(shell.isContentLoaded);
        assertTrue(shell.createSnapshot().isContentLoaded);
    }

    @Test
    public void testPageStrokesPreservation() {
        Page page = new Page(100L, 0, PaperTemplate.BLANK, 0xFFFFFFFF, false);
        org.opennotes.app.model.Stroke s1 = new org.opennotes.app.model.Stroke(0xFF000000, 3.0f, org.opennotes.app.model.DrawingTool.FOUNTAIN);
        s1.addPoint(10f, 20f, 1.0f, 100L);
        page.strokes.add(s1);
        page.markContentChanged();

        Page snapshot = page.createSnapshot();
        assertEquals(1, snapshot.strokes.size());
        assertEquals(1, page.strokes.size());

        // Live page receives second stroke
        org.opennotes.app.model.Stroke s2 = new org.opennotes.app.model.Stroke(0xFFFF0000, 4.0f, org.opennotes.app.model.DrawingTool.PEN);
        s2.addPoint(50f, 60f, 1.0f, 200L);
        page.strokes.add(s2);
        page.markContentChanged();

        assertEquals(2, page.strokes.size());
        assertEquals(1, snapshot.strokes.size());
    }

    @Test
    public void testGuardedPageContentDetection() {
        Page page = new Page(100L, 1, PaperTemplate.RULED, 0xFF121214, false);
        page.isContentLoaded = false;
        // User wrote on the page before background loading completed
        org.opennotes.app.model.Stroke s = new org.opennotes.app.model.Stroke(0xFFFFFFFF, 2.0f, org.opennotes.app.model.DrawingTool.FOUNTAIN);
        s.addPoint(100f, 100f, 1.0f, 100L);
        page.strokes.add(s);

        boolean hasInMemoryContent = (page.strokes != null && !page.strokes.isEmpty())
                || (page.shapes != null && !page.shapes.isEmpty())
                || (page.texts != null && !page.texts.isEmpty());
        assertTrue("Page with active user handwriting must be flagged as having in-memory content", hasInMemoryContent);
    }
}
