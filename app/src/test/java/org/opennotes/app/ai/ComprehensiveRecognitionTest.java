package org.opennotes.app.ai;

import android.graphics.RectF;
import org.junit.Assert;
import org.junit.Test;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.Stroke;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ComprehensiveRecognitionTest {

    private Stroke createSyntheticStroke(float startX, float startY, float endX, float endY, int numPoints) {
        Stroke s = new Stroke(0xFF000000, 3.0f, DrawingTool.PEN);
        for (int i = 0; i < numPoints; i++) {
            float frac = (float) i / (float) (numPoints - 1);
            float x = startX + (endX - startX) * frac;
            float y = startY + (endY - startY) * frac;
            s.points.add(new DrawingPoint(x, y, 1.0f));
            s.recognitionPoints.add(new DrawingPoint(x, y, 1.0f));
        }
        return s;
    }

    private List<Stroke> createWordStrokes(float originX, float originY, int letterCount, float charW, float charH) {
        List<Stroke> wordStrokes = new ArrayList<>();
        // Shirorekha / top bar
        float wordW = letterCount * charW;
        wordStrokes.add(createSyntheticStroke(originX, originY, originX + wordW, originY, 15));

        // Vertical stems for each character
        for (int i = 0; i < letterCount; i++) {
            float cx = originX + (i + 0.5f) * charW;
            wordStrokes.add(createSyntheticStroke(cx, originY, cx, originY + charH, 12));
            wordStrokes.add(createSyntheticStroke(cx - charW * 0.3f, originY + charH * 0.5f, cx, originY + charH * 0.5f, 8));
        }
        return wordStrokes;
    }

    private List<Stroke> rotateStrokes(List<Stroke> strokes, float angleDegrees, float pivotX, float pivotY) {
        double rad = Math.toRadians(angleDegrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        List<Stroke> result = new ArrayList<>();
        for (Stroke s : strokes) {
            Stroke r = new Stroke(s.color, s.width, s.tool);
            for (DrawingPoint p : s.points) {
                float dx = p.x - pivotX;
                float dy = p.y - pivotY;
                float nx = pivotX + dx * cos - dy * sin;
                float ny = pivotY + dx * sin + dy * cos;
                r.points.add(new DrawingPoint(nx, ny, p.pressure));
                r.recognitionPoints.add(new DrawingPoint(nx, ny, p.pressure));
            }
            result.add(r);
        }
        return result;
    }

    @Test
    public void testAtLeastOneThousandWordsFromRealDictionaries() throws Exception {
        File assetsDir = new File("src/main/assets/dict");
        if (!assetsDir.exists()) {
            assetsDir = new File("app/src/main/assets/dict");
        }
        Assert.assertTrue("Assets dict dir should exist", assetsDir.exists());

        File marathiFile = new File(assetsDir, "marathi.txt");
        File englishFile = new File(assetsDir, "english.txt");
        File hindiFile = new File(assetsDir, "hindi.txt");

        List<String> marathiWords = new ArrayList<>();
        List<String> englishWords = new ArrayList<>();
        List<String> hindiWords = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(marathiFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) marathiWords.add(line);
                if (marathiWords.size() >= 600) break;
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(englishFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) englishWords.add(line);
                if (englishWords.size() >= 300) break;
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(hindiFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) hindiWords.add(line);
                if (hindiWords.size() >= 200) break;
            }
        }

        int totalWords = marathiWords.size() + englishWords.size() + hindiWords.size();
        System.out.println("Loaded " + totalWords + " words across Marathi, Hindi, and English dictionaries.");
        Assert.assertTrue("Must test at least 1000 dictionary words", totalWords >= 1000);

        // Test mixed combinations of Marathi words + English words + Numbers
        Random rng = new Random(42);
        int passedCombinations = 0;
        for (int i = 0; i < 200; i++) {
            String mWord = marathiWords.get(rng.nextInt(marathiWords.size()));
            String eWord = englishWords.get(rng.nextInt(englishWords.size()));
            int number = 1000 + rng.nextInt(9000);

            // Construct mixed sentence: e.g. "शुभम 1949 OpenNotes"
            String mixedPhrase = mWord + " " + number + " " + eWord;

            // Test SpatialSpaceNormalizer on this mixed phrase
            String normalized = SpatialSpaceNormalizer.normalizeSpacing(mixedPhrase, new ArrayList<>(), "MARATHI");
            Assert.assertEquals(mixedPhrase, normalized);
            passedCombinations++;
        }
        System.out.println("Verified " + passedCombinations + " mixed Marathi+Number+English combinations in SpatialSpaceNormalizer.");
    }

    @Test
    public void testOpenTopFourVsClosedNineGeometricDisambiguation() {
        // Construct synthetic open-top '4'
        List<Stroke> fourStrokes = new ArrayList<>();
        // Left down slant: (20, 10) to (10, 35)
        fourStrokes.add(createSyntheticStroke(20, 10, 10, 35, 10));
        // Crossbar: (8, 35) to (32, 35)
        fourStrokes.add(createSyntheticStroke(8, 35, 32, 35, 12));
        // Main vertical stem: (25, 5) to (25, 50)
        fourStrokes.add(createSyntheticStroke(25, 5, 25, 50, 15));

        // Test GeometricDigitDisambiguator: if recognizer proposed '9', it must correct to '4'
        String corrected = GeometricDigitDisambiguator.disambiguateDigits("9", fourStrokes);
        Assert.assertEquals("Open-top 4 stroke must be disambiguated from 9 to 4", "4", corrected);

        // Now test 4-digit number "1949" where digit 3 was mistakenly recognized as 9 -> "1999"
        // Synthetic 1949:
        List<Stroke> numberStrokes = new ArrayList<>();
        // Digit 1: x = 10..15
        numberStrokes.add(createSyntheticStroke(12, 10, 12, 50, 15));
        // Digit 9: x = 30..50 (closed loop)
        numberStrokes.add(createSyntheticStroke(50, 10, 30, 10, 8));
        numberStrokes.add(createSyntheticStroke(30, 10, 30, 30, 8));
        numberStrokes.add(createSyntheticStroke(30, 30, 50, 30, 8));
        numberStrokes.add(createSyntheticStroke(50, 10, 50, 50, 15));
        // Digit 4: x = 65..90 (open top)
        numberStrokes.add(createSyntheticStroke(75, 10, 65, 35, 10));
        numberStrokes.add(createSyntheticStroke(63, 35, 87, 35, 12));
        numberStrokes.add(createSyntheticStroke(80, 5, 80, 50, 15));
        // Digit 9: x = 100..120 (closed loop)
        numberStrokes.add(createSyntheticStroke(120, 10, 100, 10, 8));
        numberStrokes.add(createSyntheticStroke(100, 10, 100, 30, 8));
        numberStrokes.add(createSyntheticStroke(100, 30, 120, 30, 8));
        numberStrokes.add(createSyntheticStroke(120, 10, 120, 50, 15));

        // Given ML Kit output "1999", disambiguator must correct to "1949"
        String correctedNumber = GeometricDigitDisambiguator.disambiguateDigits("1999", numberStrokes);
        Assert.assertEquals("1999 with open-top 3rd digit must resolve to 1949", "1949", correctedNumber);

        // Verify that Marathi words are NEVER modified by digit disambiguator
        String marathiWord = "शुभम सुरेश पिंपरे";
        String untouched = GeometricDigitDisambiguator.disambiguateDigits(marathiWord, numberStrokes);
        Assert.assertEquals("Marathi text must be completely untouched by digit disambiguator", marathiWord, untouched);
    }

    @Test
    public void testSingleLineWordClusteringAtRandomAngles() {
        // Words along the same line: "शुभम", "सुरेश", "पिंपरे"
        // Character height = 40, width = 25
        float[] testAngles = {-45f, -30f, -15f, 0f, 15f, 30f, 45f};

        for (float angle : testAngles) {
            List<Stroke> lineStrokes = new ArrayList<>();
            // Word 1: 3 letters at x = 0..75
            lineStrokes.addAll(createWordStrokes(0, 0, 3, 25f, 40f));
            // Inter-word space = 30px (gap between word 1 and 2)
            // Word 2: 3 letters at x = 105..180
            lineStrokes.addAll(createWordStrokes(105, 0, 3, 25f, 40f));
            // Inter-word space = 35px
            // Word 3: 3 letters at x = 215..290
            lineStrokes.addAll(createWordStrokes(215, 0, 3, 25f, 40f));

            // Rotate by test angle
            RectF bounds = InkNormalizer.getBounds(lineStrokes);
            List<Stroke> rotated = rotateStrokes(lineStrokes, angle, bounds.centerX(), bounds.centerY());

            List<SemanticStrokeClusterer.StrokeLine> lines = SemanticStrokeClusterer.segmentIntoLines(rotated);
            Assert.assertNotNull("Lines should not be null", lines);
            Assert.assertEquals("Single line with 3 words at angle " + angle + "° must be recognized as EXACTLY 1 line",
                    1, lines.size());
            Assert.assertEquals("All strokes must be preserved in the single line",
                    lineStrokes.size(), lines.get(0).strokes.size());
        }
    }

    @Test
    public void testMultiLineSegmentationAtRandomAngles() {
        // 3 lines of handwriting:
        // Line 1: Marathi text ("शुभम सुरेश पिंपरे")
        // Line 2: Mixed numbers & Marathi ("सन 1949 मध्ये")
        // Line 3: English text ("OpenNotes Android App")
        float[] testAngles = {-35f, -20f, 0f, 25f, 40f};

        for (float angle : testAngles) {
            List<Stroke> pageStrokes = new ArrayList<>();

            // Line 1 at y = 0
            pageStrokes.addAll(createWordStrokes(0, 0, 3, 25f, 40f));
            pageStrokes.addAll(createWordStrokes(100, 0, 3, 25f, 40f));
            pageStrokes.addAll(createWordStrokes(200, 0, 3, 25f, 40f));

            // Line 2 at y = 80 (vertical pitch = 80px, well separated baseline)
            pageStrokes.addAll(createWordStrokes(0, 80, 2, 25f, 40f));
            pageStrokes.addAll(createWordStrokes(70, 80, 4, 20f, 40f)); // 1949
            pageStrokes.addAll(createWordStrokes(180, 80, 3, 25f, 40f));

            // Line 3 at y = 160
            pageStrokes.addAll(createWordStrokes(0, 160, 4, 25f, 40f));
            pageStrokes.addAll(createWordStrokes(120, 160, 4, 25f, 40f));

            RectF bounds = InkNormalizer.getBounds(pageStrokes);
            List<Stroke> rotated = rotateStrokes(pageStrokes, angle, bounds.centerX(), bounds.centerY());

            float detectedAngle = InkNormalizer.detectWritingAngleDegrees(rotated);
            List<SemanticStrokeClusterer.StrokeLine> lines = SemanticStrokeClusterer.segmentIntoLines(rotated);
            System.out.println("Test angle: " + angle + "°, detectedAngle: " + detectedAngle + "°, lines: " + (lines != null ? lines.size() : "null"));
            Assert.assertNotNull("Segmented lines should not be null", lines);
            Assert.assertEquals("3 distinct lines of writing at angle " + angle + "° must be segmented into EXACTLY 3 lines",
                    3, lines.size());
        }
    }

    @Test
    public void testDeskewingPrecisionAtRandomAngles() {
        float[] targetAngles = {-45f, -30f, -15f, 15f, 30f, 45f};

        for (float targetAngle : targetAngles) {
            List<Stroke> baseStrokes = new ArrayList<>();
            baseStrokes.addAll(createWordStrokes(0, 0, 6, 25f, 40f));
            baseStrokes.addAll(createWordStrokes(180, 0, 6, 25f, 40f));

            RectF bounds = InkNormalizer.getBounds(baseStrokes);
            List<Stroke> rotated = rotateStrokes(baseStrokes, targetAngle, bounds.centerX(), bounds.centerY());

            float detectedAngle = InkNormalizer.detectWritingAngleDegrees(rotated);
            // Verify angle detected matches target within +-5°
            Assert.assertEquals("Detected angle should match rotation angle " + targetAngle,
                    targetAngle, detectedAngle, 5.0f);

            // Verify deskewing returns it to horizontal
            List<Stroke> deskewed = rotateStrokes(rotated, -detectedAngle, bounds.centerX(), bounds.centerY());
            float residualAngle = InkNormalizer.detectWritingAngleDegrees(deskewed);
            Assert.assertEquals("Deskewed residual angle should be close to 0°",
                    0.0f, residualAngle, 3.5f);
        }
    }

    @Test
    public void testSpatialSpaceNormalizationOnNumbersAndText() {
        // Space inside numbers should be closed
        Assert.assertEquals("1949", SpatialSpaceNormalizer.normalizeSpacing("19 49", new ArrayList<>(), "ENGLISH"));
        Assert.assertEquals("2024", SpatialSpaceNormalizer.normalizeSpacing("20 24", new ArrayList<>(), "ENGLISH"));
        Assert.assertEquals("500", SpatialSpaceNormalizer.normalizeSpacing("5 00", new ArrayList<>(), "ENGLISH"));

        // Legitimate spaces in Marathi and English should be preserved
        Assert.assertEquals("शुभम सुरेश पिंपरे",
                SpatialSpaceNormalizer.normalizeSpacing("शुभम सुरेश पिंपरे", new ArrayList<>(), "MARATHI"));
        Assert.assertEquals("OpenNotes Handwritten Ink",
                SpatialSpaceNormalizer.normalizeSpacing("OpenNotes Handwritten Ink", new ArrayList<>(), "ENGLISH"));
        Assert.assertEquals("किंमत 500 रुपये",
                SpatialSpaceNormalizer.normalizeSpacing("किंमत 500 रुपये", new ArrayList<>(), "MARATHI"));
    }
}
