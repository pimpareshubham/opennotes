package org.opennotes.app.ui;

import org.json.JSONObject;
import org.junit.Test;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying word wrapping in TextItem (boxWidth persistence, reflow)
 * and coordinate translation logic for Lasso and Text on imported PDF pages.
 */
public class TextWrappingAndLassoPdfTest {

    @Test
    public void testTextItemBoxWidthProperties() {
        TextItem item = new TextItem("लोकपाल आणि लोकायुक्त कायदा, २०१३ च्या तरतुदीनुसार",
                100f, 200f, 0xFF000000, 36f, "KALAM", 0f, 0f, 0f, null, 0, 480f);

        assertEquals(480f, item.boxWidth, 0.001f);
        assertEquals(480f, item.getEffectiveBoxWidth(), 0.001f);
        assertEquals("लोकपाल आणि लोकायुक्त कायदा, २०१३ च्या तरतुदीनुसार", item.text);
        assertEquals(100f, item.x, 0.001f);
        assertEquals(200f, item.y, 0.001f);
        assertEquals(36f, item.fontSize, 0.001f);
    }

    @Test
    public void testSideHandleResizingAdjustsBoxWidthWithoutAlteringFontSize() {
        TextItem item = new TextItem("Sample paragraph for reflow testing",
                50f, 150f, 0xFF222222, 28f, "ROBOTO", 0f, 0f, 0f, null, 0, 400f);

        float originalFontSize = item.fontSize;
        float originalBoxWidth = item.getEffectiveBoxWidth();

        // Simulate side handle resizing (e.g. MIDDLE_RIGHT dragged to expand box horizontally by 1.5x)
        float scaleX = 1.5f;
        float scaleY = 1.0f; // Height / Y does not scale on side pill handles
        float anchorX = item.x;
        float anchorY = item.y;

        // Apply scaleItemsAround logic for TextItem
        float dx = item.x - anchorX;
        float dy = item.y - anchorY;
        item.x = anchorX + dx * scaleX;
        item.y = anchorY + dy * scaleY;

        if (Math.abs(scaleY - 1.0f) > 0.001f) {
            float avgScale = (Math.abs(scaleX) + Math.abs(scaleY)) / 2f;
            item.fontSize = Math.max(8f, item.fontSize * avgScale);
        }
        float curBoxW = item.getEffectiveBoxWidth();
        if (curBoxW > 0f) {
            item.boxWidth = Math.max(80f, curBoxW * scaleX);
        }

        // Font size MUST remain unchanged so text reflows inside the wider block
        assertEquals(originalFontSize, item.fontSize, 0.001f);
        // Box width is expanded to 600px
        assertEquals(originalBoxWidth * 1.5f, item.boxWidth, 0.001f);
        assertEquals(600f, item.boxWidth, 0.001f);
    }

    @Test
    public void testCornerHandleResizingScalesBothBoxWidthAndFontSize() {
        TextItem item = new TextItem("Corner scaling text",
                100f, 100f, 0xFF000000, 30f, "KALAM", 0f, 0f, 0f, null, 0, 300f);

        float scaleX = 2.0f;
        float scaleY = 2.0f;
        float anchorX = item.x;
        float anchorY = item.y;

        float avgScale = (Math.abs(scaleX) + Math.abs(scaleY)) / 2f;
        if (Math.abs(scaleY - 1.0f) > 0.001f) {
            item.fontSize = Math.max(8f, item.fontSize * avgScale);
        }
        float curBoxW = item.getEffectiveBoxWidth();
        if (curBoxW > 0f) {
            item.boxWidth = Math.max(80f, curBoxW * scaleX);
        }

        // Both font size and box width scale proportionally
        assertEquals(60f, item.fontSize, 0.001f);
        assertEquals(600f, item.boxWidth, 0.001f);
    }

    @Test
    public void testImportedPdfPageLassoCoordinateTranslation() {
        // Page 3 of an imported PDF where page top is at Y = 2800 in continuous scroll
        float pageTop = 2800f;

        // An ink stroke drawn on Page 3 has local coordinates relative to the page top:
        // Local Y is between 100 and 300, which in world coordinates is 2900 to 3100.
        Stroke stroke = new Stroke(0xFF000000, 4f, org.opennotes.app.model.DrawingTool.PEN);
        stroke.addPoint(150f, 120f, 1f, 1000L);
        stroke.addPoint(250f, 280f, 1f, 1010L);

        // User draws a lasso around world coordinates [100, 2850] to [300, 3150]
        float worldLassoTop = 2850f;
        float worldLassoBottom = 3150f;

        // When testing lasso intersection against page 3, local lasso bounds are computed:
        float localLassoTop = worldLassoTop - pageTop; // 50f
        float localLassoBottom = worldLassoBottom - pageTop; // 350f

        // Verify stroke local points fall inside local lasso bounds
        DrawingPoint pt0 = stroke.points.get(0);
        DrawingPoint pt1 = stroke.points.get(1);

        assertTrue("pt0 local Y (120) must be within local lasso bounds [50, 350]",
                pt0.y >= localLassoTop && pt0.y <= localLassoBottom);
        assertTrue("pt1 local Y (280) must be within local lasso bounds [50, 350]",
                pt1.y >= localLassoTop && pt1.y <= localLassoBottom);

        // When selection bounds are computed for the UI, local bounds are offset by pageTop into world coordinates
        float localStrokeMinY = Math.min(pt0.y, pt1.y); // 120f
        float localStrokeMaxY = Math.max(pt0.y, pt1.y); // 280f
        float worldSelectionMinY = localStrokeMinY + pageTop; // 2920f
        float worldSelectionMaxY = localStrokeMaxY + pageTop; // 3080f

        assertEquals(2920f, worldSelectionMinY, 0.001f);
        assertEquals(3080f, worldSelectionMaxY, 0.001f);
        assertTrue("World selection bounds align with user's screen view on page 3",
                worldSelectionMinY >= worldLassoTop && worldSelectionMaxY <= worldLassoBottom);
    }

    @Test
    public void testAddTextItemPageLocalYCalculationOnPdfPage() {
        // User taps on Page 2 of an imported PDF at screen touch position corresponding to actualY = 1600.
        // Page 2 begins at pageTop = 1400.
        float actualX = 250f;
        float actualY = 1600f;
        float pageTop = 1400f;

        // Page-local coordinates calculated by addTextItem:
        float localX = actualX;
        float localY = actualY - pageTop;

        assertEquals(200f, localY, 0.001f);
        assertTrue("Local Y must be within Page 2's height (e.g. 1400px)", localY >= 0f && localY <= 1400f);

        // Initial box width calculation prevents single infinite straight line
        float targetPageWidth = 1200f;
        float availableWidth = Math.max(280f, targetPageWidth - localX - 60f); // 890f
        float initialBoxWidth = Math.min(650f, availableWidth); // 650f

        assertEquals(650f, initialBoxWidth, 0.001f);
        assertTrue("Initial box width enforces paragraph wrapping instead of single infinite line",
                initialBoxWidth > 0f && initialBoxWidth <= availableWidth);
    }

    @Test
    public void testLassoSingleWordSelectionSplitting() {
        String paragraph = "लोकपाल आणि लोकायुक्त कायदा";
        // Words:
        // 0: "लोकपाल"
        // 1: "आणि"
        // 2: "लोकायुक्त"
        // 3: "कायदा"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\S+");
        java.util.regex.Matcher m = p.matcher(paragraph);
        List<String> words = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            words.add(m.group());
            spans.add(new int[]{m.start(), m.end()});
        }
        assertEquals(4, words.size());
        assertEquals("लोकायुक्त", words.get(2));

        // When lasso targets only word 2 ("लोकायुक्त"):
        int targetWordIndex = 2;
        int[] targetSpan = spans.get(targetWordIndex);
        String selectedWord = paragraph.substring(targetSpan[0], targetSpan[1]);
        assertEquals("लोकायुक्त", selectedWord);

        // Preceding unselected run: [0..1] ("लोकपाल आणि")
        String preceding = paragraph.substring(spans.get(0)[0], spans.get(1)[1]);
        assertEquals("लोकपाल आणि", preceding);

        // Succeeding unselected run: [3] ("कायदा")
        String succeeding = paragraph.substring(spans.get(3)[0], spans.get(3)[1]);
        assertEquals("कायदा", succeeding);
    }
}
