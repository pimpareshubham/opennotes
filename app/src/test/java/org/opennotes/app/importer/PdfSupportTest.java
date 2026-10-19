package org.opennotes.app.importer;

import org.junit.Test;
import org.opennotes.app.model.ImageItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PdfSupportTest {

    @Test
    public void testImageItemBackgroundFlag() {
        // Standard sticker image
        ImageItem sticker = new ImageItem("/path/to/sticker.png", 50f, 100f, 200f, 300f);
        assertFalse("Standard image should not be background by default", sticker.isBackground);
        assertEquals("/path/to/sticker.png", sticker.imagePath);
        assertEquals(50f, sticker.x, 0.001f);
        assertEquals(100f, sticker.y, 0.001f);
        assertEquals(200f, sticker.width, 0.001f);
        assertEquals(300f, sticker.height, 0.001f);

        // PDF background page image
        ImageItem pdfBg = new ImageItem("/path/to/page_0.jpg", 0f, 0f, 1080f, 1528f, null, true);
        assertTrue("PDF background page should have isBackground = true", pdfBg.isBackground);
        assertEquals("/path/to/page_0.jpg", pdfBg.imagePath);
        assertEquals(0f, pdfBg.x, 0.001f);
        assertEquals(0f, pdfBg.y, 0.001f);
        assertEquals(1080f, pdfBg.width, 0.001f);
        assertEquals(1528f, pdfBg.height, 0.001f);
    }
}
