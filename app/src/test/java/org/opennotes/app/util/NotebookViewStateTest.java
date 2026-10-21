package org.opennotes.app.util;

import org.junit.Test;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.PaperTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NotebookViewStateTest {

    @Test
    public void testNotebookReadingPositionModel() {
        Notebook nb = new Notebook("Calculus II", 0xFF2A2D34, PaperTemplate.GRID);
        assertEquals(0, nb.lastPageIndex);
        assertEquals(0f, nb.lastScrollY, 0.001f);
        assertEquals(100f, nb.lastZoomPercent, 0.001f);
        assertEquals(0f, nb.lastPanX, 0.001f);
        assertEquals(0f, nb.lastPanY, 0.001f);

        // Update position
        nb.lastPageIndex = 7;
        nb.lastScrollY = 1420.5f;
        nb.lastZoomPercent = 135f;
        nb.lastPanX = 12.0f;
        nb.lastPanY = -45.0f;

        assertEquals(7, nb.lastPageIndex);
        assertEquals(1420.5f, nb.lastScrollY, 0.001f);
        assertEquals(135f, nb.lastZoomPercent, 0.001f);
        assertEquals(12.0f, nb.lastPanX, 0.001f);
        assertEquals(-45.0f, nb.lastPanY, 0.001f);
    }

    @Test
    public void testViewStateObject() {
        NotebookViewStateManager.ViewState state = new NotebookViewStateManager.ViewState(
                5, 850.25f, 125.0f, -10f, 25f, 1700000000000L);
        assertNotNull(state);
        assertEquals(5, state.pageIndex);
        assertEquals(850.25f, state.scrollY, 0.001f);
        assertEquals(125.0f, state.zoomPercent, 0.001f);
        assertEquals(-10f, state.panX, 0.001f);
        assertEquals(25f, state.panY, 0.001f);
        assertEquals(1700000000000L, state.timestamp);
    }
}
