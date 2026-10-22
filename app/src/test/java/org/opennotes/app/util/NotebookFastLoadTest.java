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
}
