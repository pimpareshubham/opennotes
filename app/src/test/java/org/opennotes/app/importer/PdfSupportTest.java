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

    @Test
    public void testUltraHdPngBackgroundSupport() {
        ImageItem ultraHdBg = new ImageItem("/path/to/page_0.png", 0f, 0f, 1440f, 2038f, null, true);
        assertTrue(ultraHdBg.isBackground);
        assertEquals("/path/to/page_0.png", ultraHdBg.imagePath);
        assertEquals(1440f, ultraHdBg.width, 0.001f);
        assertEquals(2038f, ultraHdBg.height, 0.001f);
    }

    @Test
    public void testAmoledInversionCalculations() {
        float contrast = -1.378f;
        float offset = 324f;

        // White paper (255) -> Pure Black (<= 0)
        float whiteToBlack = contrast * 255f + offset;
        assertTrue("White paper must map to <= 0 for Super AMOLED pure black", whiteToBlack <= 0f);

        // Off-white paper (240) -> Pure Black (<= 0)
        float offWhiteToBlack = contrast * 240f + offset;
        assertTrue("Off-white paper must map to <= 0 for Super AMOLED pure black", offWhiteToBlack <= 0f);

        // Black text (0) -> Crisp Pure White (>= 255)
        float blackToWhite = contrast * 0f + offset;
        assertTrue("Black text must map to >= 255 for razor-sharp legibility", blackToWhite >= 255f);

        // Dark gray text (30) -> Crisp White (>= 255)
        float darkGrayToWhite = contrast * 30f + offset;
        assertTrue("Dark gray text must map to >= 255", darkGrayToWhite >= 255f);
    }

    @Test
    public void testPdfPageRendererReferenceParsing() {
        String pdfRef = "/storage/emulated/0/documents/report.pdf#page=4";
        assertTrue("Should detect PDF page reference", org.opennotes.app.util.PdfPageRenderer.isPdfPageReference(pdfRef));
        assertEquals(4, org.opennotes.app.util.PdfPageRenderer.parsePageNumber(pdfRef));
        assertEquals("/storage/emulated/0/documents/report.pdf", org.opennotes.app.util.PdfPageRenderer.cleanPdfFilePath(pdfRef));

        String pngRef = "/storage/emulated/0/documents/page_0.png";
        assertFalse("Standard PNG is not a PDF reference", org.opennotes.app.util.PdfPageRenderer.isPdfPageReference(pngRef));
        assertEquals(0, org.opennotes.app.util.PdfPageRenderer.parsePageNumber(pngRef));
        assertEquals(pngRef, org.opennotes.app.util.PdfPageRenderer.cleanPdfFilePath(pngRef));
    }

    @Test
    public void testPdfImportAndPageViewSettings() {
        assertEquals("CONVERT_PNG", org.opennotes.app.db.AppSettings.PDF_IMPORT_MODE_CONVERT_PNG);
        assertEquals("NATIVE_PDF", org.opennotes.app.db.AppSettings.PDF_IMPORT_MODE_NATIVE_PDF);
        assertEquals("VERTICAL_SCROLL", org.opennotes.app.db.AppSettings.PAGE_VIEW_MODE_VERTICAL_SCROLL);
        assertEquals("HORIZONTAL_SWITCH", org.opennotes.app.db.AppSettings.PAGE_VIEW_MODE_HORIZONTAL_SWITCH);
    }
}

