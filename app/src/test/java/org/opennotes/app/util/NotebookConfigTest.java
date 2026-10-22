package org.opennotes.app.util;

import org.junit.Test;
import org.opennotes.app.model.DrawingTool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NotebookConfigTest {

    @Test
    public void testDefaultConfigValues() {
        NotebookConfigManager.NotebookConfig config = new NotebookConfigManager.NotebookConfig();
        assertNull(config.tool);
        assertEquals(-1f, config.strokeWidth, 0.001f);
        assertEquals(Integer.MIN_VALUE, config.inkColor);
        assertEquals(-1f, config.pressureSensitivity, 0.001f);
        assertEquals(-1f, config.stabilization, 0.001f);
        assertNull(config.straightLineAssist);
        assertNull(config.straightHighlighterLock);
        assertNull(config.eraseOnlyHighlighter);
        assertNull(config.stylusOnly);
        assertNull(config.fingerMoveCanvas);
        assertNull(config.pdfAmoledDarkMode);
        assertNull(config.continuousVerticalScroll);
        assertNull(config.viewportLocked);
        assertNull(config.laserDurationMs);
        assertNull(config.autoBeautifyEnabled);
        assertNull(config.lassoMode);
        assertNull(config.laserMode);
        assertNull(config.defaultResetZoom);
        assertTrue(config.toolWidths.isEmpty());
        assertTrue(config.toolColors.isEmpty());
    }

    @Test
    public void testPerToolWidthAndColor() {
        NotebookConfigManager.NotebookConfig config = new NotebookConfigManager.NotebookConfig();
        config.strokeWidth = 3.0f;
        config.inkColor = 0xFFFFFFFF;

        // Fallback to general width and color when tool-specific not set
        assertEquals(3.0f, config.getToolWidth(DrawingTool.PEN), 0.001f);
        assertEquals(0xFFFFFFFF, config.getToolColor(DrawingTool.PEN));

        // Set tool-specific width and color
        config.setToolWidth(DrawingTool.HIGHLIGHTER, 18.0f);
        config.setToolColor(DrawingTool.HIGHLIGHTER, 0xFFFBBF24);

        assertEquals(18.0f, config.getToolWidth(DrawingTool.HIGHLIGHTER), 0.001f);
        assertEquals(0xFFFBBF24, config.getToolColor(DrawingTool.HIGHLIGHTER));

        // Other tools still fall back to general
        assertEquals(3.0f, config.getToolWidth(DrawingTool.FOUNTAIN), 0.001f);
        assertEquals(0xFFFFFFFF, config.getToolColor(DrawingTool.FOUNTAIN));
    }

    @Test
    public void testFullConfigState() {
        NotebookConfigManager.NotebookConfig config = new NotebookConfigManager.NotebookConfig();
        config.tool = DrawingTool.HIGHLIGHTER;
        config.strokeWidth = 15.0f;
        config.inkColor = 0xFF38BDF8;
        config.pressureSensitivity = 0.85f;
        config.stabilization = 0.6f;
        config.straightLineAssist = true;
        config.straightHighlighterLock = true;
        config.eraseOnlyHighlighter = true;
        config.stylusOnly = true;
        config.fingerMoveCanvas = true;
        config.pdfAmoledDarkMode = true;
        config.continuousVerticalScroll = true;
        config.viewportLocked = true;
        config.laserDurationMs = 2500L;
        config.autoBeautifyEnabled = false;
        config.lassoMode = org.opennotes.app.ui.CanvasView.LassoMode.RECTANGLE;
        config.laserMode = org.opennotes.app.ui.CanvasView.LaserMode.DOT;
        config.defaultResetZoom = 150;

        assertEquals(DrawingTool.HIGHLIGHTER, config.tool);
        assertEquals(15.0f, config.strokeWidth, 0.001f);
        assertEquals(0xFF38BDF8, config.inkColor);
        assertEquals(0.85f, config.pressureSensitivity, 0.001f);
        assertEquals(0.6f, config.stabilization, 0.001f);
        assertTrue(config.straightLineAssist);
        assertTrue(config.straightHighlighterLock);
        assertTrue(config.eraseOnlyHighlighter);
        assertTrue(config.stylusOnly);
        assertTrue(config.fingerMoveCanvas);
        assertTrue(config.pdfAmoledDarkMode);
        assertTrue(config.continuousVerticalScroll);
        assertTrue(config.viewportLocked);
        assertEquals(Long.valueOf(2500L), config.laserDurationMs);
        assertFalse(config.autoBeautifyEnabled);
        assertEquals(org.opennotes.app.ui.CanvasView.LassoMode.RECTANGLE, config.lassoMode);
        assertEquals(org.opennotes.app.ui.CanvasView.LaserMode.DOT, config.laserMode);
        assertEquals(Integer.valueOf(150), config.defaultResetZoom);
    }
}
