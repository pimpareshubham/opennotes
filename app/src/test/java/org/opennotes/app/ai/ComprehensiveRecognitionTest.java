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
            Assert.assertEquals("Three spatial word groups should remain distinct at angle " + angle + "°",
                    3, lines.get(0).words.size());
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

    @Test
    public void testEnglishAndMarathiGrammarEvidence() {
        LinguisticDictionary dictionary = LinguisticDictionary.getInstance(null);
        Assert.assertTrue(dictionary.scoreGrammarLine("How are you", "ENGLISH")
                > dictionary.scoreGrammarLine("How is you", "ENGLISH"));
        Assert.assertTrue(dictionary.scoreGrammarLine("मी आहे", "MARATHI")
                > dictionary.scoreGrammarLine("मी आहात", "MARATHI"));
        Assert.assertTrue(dictionary.scoreLine("How are you", "ENGLISH")
                > dictionary.scoreLine("How is you", "ENGLISH"));
    }

    @Test
    public void testDevanagariVowelsMatrasAndConjunctOrthography() {
        String[] validForms = {"अ", "आ", "इ", "ई", "उ", "ऊ", "ऋ", "ए", "ऐ", "ओ", "औ", "अं", "अः",
                "का", "कि", "की", "कु", "कू", "कृ", "के", "कै", "को", "कौ", "कं", "क्ष", "ज्ञ", "स्त्री"};
        for (String form : validForms) {
            Assert.assertEquals("Valid Devanagari orthography should not be penalized: " + form,
                    0, LinguisticDictionary.scoreDevanagariOrthography(form));
        }
        Assert.assertTrue("A dependent vowel sign cannot begin a word",
                LinguisticDictionary.scoreDevanagariOrthography("िक") < 0);
        Assert.assertTrue("Anusvara correction should be surfaced as a candidate",
                LinguisticDictionary.getInstance(null).suggestMarathiOrthographicVariants("किवा", 4)
                        .contains("किंवा"));
    }

    @Test
    public void testSixLineMixedLanguageNoteWithTallMatrasAndUkars() {
        // Simulates the exact layout of the user's 6-line handwritten note:
        // Line 1: "हे पाहा नीट काम नाही" (Devanagari with top matras: हे, नाही)
        // Line 2: "करत जरा चांगल काहीतरी" (Devanagari with anusvara and matras)
        // Line 3: "implement कर साधा" (Mixed English + Marathi)
        // Line 4: "लहानसा issue तुला" (Mixed Marathi + English + ukar below baseline: तुला)
        // Line 5: "solve कराय नाही समजत" (Mixed English + Marathi)
        // Line 6: "लाज वाटली पाहिजे तुला" (Marathi with top matra and lower ukar: पाहिजे, तुला)
        List<Stroke> pageStrokes = new ArrayList<>();

        float lineSpacing = 120f;
        float charW = 25f;
        float charH = 45f;

        // Line 1 at y = 0
        float y1 = 0f;
        pageStrokes.addAll(createWordStrokes(0, y1, 2, charW, charH));   // हे
        pageStrokes.add(createSyntheticStroke(10f, y1 - 25f, 25f, y1, 8)); // upper matra
        pageStrokes.addAll(createWordStrokes(75, y1, 4, charW, charH));  // पाहा
        pageStrokes.addAll(createWordStrokes(200, y1, 3, charW, charH)); // नीट
        pageStrokes.addAll(createWordStrokes(300, y1, 3, charW, charH)); // काम
        pageStrokes.addAll(createWordStrokes(400, y1, 4, charW, charH)); // नाही

        // Line 2 at y = 120
        float y2 = y1 + lineSpacing;
        pageStrokes.addAll(createWordStrokes(0, y2, 3, charW, charH));   // करत
        pageStrokes.addAll(createWordStrokes(100, y2, 3, charW, charH)); // जरा
        pageStrokes.addAll(createWordStrokes(200, y2, 4, charW, charH)); // चांगल
        pageStrokes.add(createSyntheticStroke(240f, y2 - 15f, 242f, y2 - 15f, 3)); // anusvara dot
        pageStrokes.addAll(createWordStrokes(330, y2, 6, charW, charH)); // काहीतरी

        // Line 3 at y = 240
        float y3 = y2 + lineSpacing;
        pageStrokes.addAll(createWordStrokes(0, y3, 9, 20f, charH));     // implement (English)
        pageStrokes.addAll(createWordStrokes(220, y3, 2, charW, charH)); // कर
        pageStrokes.addAll(createWordStrokes(300, y3, 4, charW, charH)); // साधा

        // Line 4 at y = 360
        float y4 = y3 + lineSpacing;
        pageStrokes.addAll(createWordStrokes(0, y4, 5, charW, charH));   // लहानसा
        pageStrokes.addAll(createWordStrokes(160, y4, 5, 20f, charH));   // issue (English)
        pageStrokes.addAll(createWordStrokes(300, y4, 4, charW, charH)); // तुला
        pageStrokes.add(createSyntheticStroke(320f, y4 + charH, 325f, y4 + charH + 25f, 8)); // lower ukar

        // Line 5 at y = 480
        float y5 = y4 + lineSpacing;
        pageStrokes.addAll(createWordStrokes(0, y5, 5, 20f, charH));     // solve (English)
        pageStrokes.addAll(createWordStrokes(140, y5, 4, charW, charH)); // कराय
        pageStrokes.addAll(createWordStrokes(270, y5, 4, charW, charH)); // नाही
        pageStrokes.addAll(createWordStrokes(400, y5, 4, charW, charH)); // समजत

        // Line 6 at y = 600
        float y6 = y5 + lineSpacing;
        pageStrokes.addAll(createWordStrokes(0, y6, 3, charW, charH));   // लाज
        pageStrokes.addAll(createWordStrokes(100, y6, 5, charW, charH)); // वाटली
        pageStrokes.addAll(createWordStrokes(250, y6, 5, charW, charH)); // पाहिजे
        pageStrokes.add(createSyntheticStroke(310f, y6 - 25f, 325f, y6, 8)); // upper matra
        pageStrokes.addAll(createWordStrokes(400, y6, 4, charW, charH)); // तुला
        pageStrokes.add(createSyntheticStroke(420f, y6 + charH, 425f, y6 + charH + 25f, 8)); // lower ukar

        List<SemanticStrokeClusterer.StrokeLine> lines = SemanticStrokeClusterer.segmentIntoLines(pageStrokes);
        Assert.assertNotNull("Segmented lines should not be null", lines);
        Assert.assertEquals("The 6-line handwritten note must be segmented into EXACTLY 6 distinct lines",
                6, lines.size());

        int totalStrokesPreserved = 0;
        for (SemanticStrokeClusterer.StrokeLine line : lines) {
            totalStrokesPreserved += line.strokes.size();
        }
        Assert.assertEquals("Every single stroke across all 6 lines must be preserved without trimming",
                pageStrokes.size(), totalStrokesPreserved);

        // Verify top-to-bottom vertical line progression
        for (int i = 0; i < lines.size() - 1; i++) {
            Assert.assertTrue("Line " + i + " must be positioned strictly above line " + (i + 1),
                    lines.get(i).bounds.top < lines.get(i + 1).bounds.top);
        }
    }

    @Test
    public void testSingleLineMarathiWithoutShirorekha() {
        // Reproduces the exact user handwriting of "शुभम सुरेश पिंपरे" from Image 1:
        // 3 words written on a single line WITHOUT continuous shirorekha:
        // Word 1: "शुभम" at x = 0..150, y = 30..80 (ukar at y=80..110)
        // Word 2: "सुरेश" at x = 210..360, y = 30..80 (matra at y=0..30, ukar at y=80..110)
        // Word 3: "पिंपरे" at x = 430..600, y = 30..80 (velanti stem, anusvara dot at y=10..12, tall matra at y=-25..30)
        List<Stroke> strokes = new ArrayList<>();

        // Word 1: शुभम
        strokes.add(createSyntheticStroke(25f, 30f, 25f, 80f, 10));  // श
        strokes.add(createSyntheticStroke(20f, 80f, 25f, 110f, 8));  // ु (ukar below baseline)
        strokes.add(createSyntheticStroke(75f, 30f, 75f, 80f, 10));  // भ
        strokes.add(createSyntheticStroke(125f, 30f, 125f, 80f, 10)); // म

        // Word 2: सुरेश
        strokes.add(createSyntheticStroke(235f, 30f, 235f, 80f, 10)); // स
        strokes.add(createSyntheticStroke(230f, 80f, 235f, 110f, 8)); // ु (ukar)
        strokes.add(createSyntheticStroke(285f, 30f, 285f, 80f, 10)); // र
        strokes.add(createSyntheticStroke(285f, 30f, 305f, 0f, 8));   // े (matra)
        strokes.add(createSyntheticStroke(335f, 30f, 335f, 80f, 10)); // श

        // Word 3: पिंपरे
        strokes.add(createSyntheticStroke(430f, 15f, 430f, 80f, 10)); // ि (velanti)
        strokes.add(createSyntheticStroke(470f, 30f, 470f, 80f, 10)); // प
        strokes.add(createSyntheticStroke(472f, 10f, 474f, 12f, 3));  // ं (anusvara dot)
        strokes.add(createSyntheticStroke(520f, 30f, 520f, 80f, 10)); // प
        strokes.add(createSyntheticStroke(565f, 30f, 565f, 80f, 10)); // र
        strokes.add(createSyntheticStroke(565f, 30f, 600f, -25f, 8)); // े (tall matra)

        List<SemanticStrokeClusterer.StrokeLine> lines = SemanticStrokeClusterer.segmentIntoLines(strokes);
        Assert.assertNotNull("Lines should not be null", lines);
        Assert.assertEquals("Single line 'शुभम सुरेश पिंपरे' must be segmented into EXACTLY 1 line, NOT fragmented into isolated letters",
                1, lines.size());
        Assert.assertEquals("All strokes must be preserved in the single line",
                strokes.size(), lines.get(0).strokes.size());
    }
}
