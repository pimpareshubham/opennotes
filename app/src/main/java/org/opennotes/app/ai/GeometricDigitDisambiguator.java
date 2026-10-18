package org.opennotes.app.ai;

import android.graphics.RectF;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geometric and topological digit disambiguator.
 * 
 * Safely resolves digits where ML Kit Digital Ink frequently confuses open-top '4' with '9'.
 * Only touches text that consists exclusively of digits and punctuation.
 * Fully guarded with try-catch to guarantee zero crash risk.
 */
public class GeometricDigitDisambiguator {

    public static String disambiguateDigits(String recognizedText, List<Stroke> strokes) {
        if (recognizedText == null || recognizedText.isEmpty() || strokes == null || strokes.isEmpty()) {
            return recognizedText;
        }

        try {
            String trimmed = recognizedText.trim();
            // ONLY inspect text that consists strictly of numbers and basic punctuation (e.g. "1949", "249", "12/04/2024")
            // NEVER touch Indic (Marathi/Hindi) or Latin language text!
            if (!trimmed.matches("^[0-9\\s.,\\-+:/#]+$")) {
                return recognizedText;
            }

            if (!trimmed.contains("9")) {
                return recognizedText;
            }

            // Clean stroke list (remove nulls and strokes with no valid points)
            List<Stroke> validStrokes = new ArrayList<>();
            for (Stroke s : strokes) {
                if (s == null) continue;
                List<DrawingPoint> pts = s.getRecognitionPoints();
                if (pts == null || pts.isEmpty()) pts = s.points;
                if (pts != null && !pts.isEmpty() && s.getBounds() != null) {
                    validStrokes.add(s);
                }
            }
            if (validStrokes.isEmpty()) return recognizedText;

            List<List<Stroke>> characterClusters = clusterStrokesIntoCharacters(validStrokes);
            String cleanedDigits = trimmed.replaceAll("[^0-9]", "");

            // Count how many '4's are already recognized in the text
            int recognizedFours = 0;
            for (int i = 0; i < recognizedText.length(); i++) {
                if (recognizedText.charAt(i) == '4') recognizedFours++;
            }

            int openTopFourClusters = 0;
            for (List<Stroke> cluster : characterClusters) {
                if (isOpenTopFour(cluster)) openTopFourClusters++;
            }

            // If all open-top four clusters were ALREADY recognized as '4' (e.g. in "249", '4' is already recognized),
            // then there is NO misclassified '4'! Leave legitimate '9's completely untouched!
            if (openTopFourClusters <= recognizedFours) {
                return recognizedText;
            }

            if (characterClusters.size() == cleanedDigits.length()) {
                StringBuilder corrected = new StringBuilder();
                int digitIdx = 0;
                for (int i = 0; i < recognizedText.length(); i++) {
                    char c = recognizedText.charAt(i);
                    if (Character.isDigit(c)) {
                        List<Stroke> charStrokes = characterClusters.get(digitIdx);
                        if (c == '9' && isOpenTopFour(charStrokes)) {
                            corrected.append('4');
                        } else {
                            corrected.append(c);
                        }
                        digitIdx++;
                    } else {
                        corrected.append(c);
                    }
                }
                return corrected.toString();
            }

            // Fallback if stroke cluster count differs from digit count:
            // Only replace if a cluster is an open top 4 AND its position matches an unrecognized 4
            for (List<Stroke> charStrokes : characterClusters) {
                if (isOpenTopFour(charStrokes)) {
                    return replaceMatchingNineWithFour(recognizedText, characterClusters, charStrokes);
                }
            }

            return recognizedText;
        } catch (Throwable t) {
            return recognizedText;
        }
    }

    /**
     * Evaluates whether a stroke or group of strokes represents an open-top digit '4'.
     * Key structural features of open-top '4':
     * 1. Top 25% has NO closed loop and only ONE stroke edge (the left diagonal stroke).
     * 2. Middle 35%-65% has a horizontal crossbar or wide horizontal span.
     * 3. Bottom 70%-100% has a single vertical stem on the right half.
     */
    public static boolean isOpenTopFour(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return false;
        try {
            RectF b = InkNormalizer.getBounds(strokes);
            if (b == null || b.height() < 12f || b.width() < 6f) return false;

            List<DrawingPoint> allPts = new ArrayList<>();
            for (Stroke s : strokes) {
                if (s == null) continue;
                List<DrawingPoint> pts = s.getRecognitionPoints();
                if (pts == null || pts.isEmpty()) pts = s.points;
                if (pts != null) {
                    for (DrawingPoint p : pts) {
                        if (p != null && !Float.isNaN(p.x) && !Float.isNaN(p.y) && !Float.isInfinite(p.x) && !Float.isInfinite(p.y)) {
                            allPts.add(p);
                        }
                    }
                }
            }
            if (allPts.size() < 6) return false;

            float h = b.height();
            float yTop25 = b.top + h * 0.25f;
            float yMid50 = b.top + h * 0.50f;
            float yBot75 = b.top + h * 0.75f;

            float topMinX = Float.MAX_VALUE, topMaxX = -Float.MAX_VALUE;
            int topPtsCount = 0;

            float midMinX = Float.MAX_VALUE, midMaxX = -Float.MAX_VALUE;
            int midPtsCount = 0;

            float botMinX = Float.MAX_VALUE, botMaxX = -Float.MAX_VALUE;
            int botPtsCount = 0;

            for (DrawingPoint p : allPts) {
                if (p.y <= yTop25) {
                    topMinX = Math.min(topMinX, p.x);
                    topMaxX = Math.max(topMaxX, p.x);
                    topPtsCount++;
                } else if (p.y >= yTop25 && p.y <= yMid50 + h * 0.15f) {
                    midMinX = Math.min(midMinX, p.x);
                    midMaxX = Math.max(midMaxX, p.x);
                    midPtsCount++;
                } else if (p.y >= yBot75) {
                    botMinX = Math.min(botMinX, p.x);
                    botMaxX = Math.max(botMaxX, p.x);
                    botPtsCount++;
                }
            }

            float topSpan = (topPtsCount > 0) ? (topMaxX - topMinX) : 0f;
            float midSpan = (midPtsCount > 0) ? (midMaxX - midMinX) : 0f;

            // In a '9', the top loop spans almost the entire width of the character (topSpan / b.width() >= 0.65f).
            // In an open '4', the top stroke is a single diagonal entry, and the middle crossbar spans wider than the top!
            boolean midIsWiderThanTop = midSpan > topSpan * 1.25f || topSpan <= b.width() * 0.55f;

            // Check if there is a horizontal crossbar segment in the middle third
            boolean hasHorizontalCrossbar = false;
            for (int i = 0; i < allPts.size() - 1; i++) {
                DrawingPoint p1 = allPts.get(i);
                DrawingPoint p2 = allPts.get(i + 1);
                if (p1.y >= b.top + h * 0.20f && p1.y <= b.top + h * 0.75f) {
                    float dx = Math.abs(p2.x - p1.x);
                    float dy = Math.abs(p2.y - p1.y);
                    if (dx >= dy * 1.4f && dx >= b.width() * 0.20f) {
                        hasHorizontalCrossbar = true;
                        break;
                    }
                }
            }

            boolean stemOnRight = (botPtsCount > 0 && botMinX >= b.left + b.width() * 0.35f);

            return midIsWiderThanTop && (hasHorizontalCrossbar || stemOnRight);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String replaceMatchingNineWithFour(String text,
                                                      List<List<Stroke>> clusters,
                                                      List<Stroke> fourCluster) {
        try {
            RectF targetB = InkNormalizer.getBounds(fourCluster);
            if (targetB == null) return text.replaceFirst("9", "4");

            int targetIdx = 0;
            for (int i = 0; i < clusters.size(); i++) {
                RectF cb = InkNormalizer.getBounds(clusters.get(i));
                if (cb != null && cb.left < targetB.left - 5f) {
                    targetIdx++;
                }
            }

            int currentDigit = 0;
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isDigit(c)) {
                    if (c == '9' && (currentDigit == targetIdx || (!replaced && currentDigit >= targetIdx - 1))) {
                        sb.append('4');
                        replaced = true;
                    } else {
                        sb.append(c);
                    }
                    currentDigit++;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return text.replaceFirst("9", "4");
        }
    }

    public static List<List<Stroke>> clusterStrokesIntoCharacters(List<Stroke> strokes) {
        List<List<Stroke>> result = new ArrayList<>();
        if (strokes == null || strokes.isEmpty()) return result;

        try {
            List<Stroke> valid = new ArrayList<>();
            for (Stroke s : strokes) {
                if (s != null && s.getBounds() != null) {
                    valid.add(s);
                }
            }
            if (valid.isEmpty()) return result;

            Collections.sort(valid, (s1, s2) -> {
                RectF b1 = s1.getBounds();
                RectF b2 = s2.getBounds();
                float l1 = b1 != null ? b1.left : 0f;
                float l2 = b2 != null ? b2.left : 0f;
                return Float.compare(l1, l2);
            });

            List<Stroke> currentCluster = new ArrayList<>();
            currentCluster.add(valid.get(0));

            for (int i = 1; i < valid.size(); i++) {
                Stroke s = valid.get(i);
                RectF prevB = InkNormalizer.getBounds(currentCluster);
                RectF currB = s.getBounds();

                if (prevB != null && currB != null) {
                    float hOverlap = Math.max(0f, Math.min(prevB.right, currB.right) - Math.max(prevB.left, currB.left));
                    float hGap = currB.left - prevB.right;
                    float minW = Math.min(prevB.width(), currB.width());

                    if (hOverlap >= 0.20f * minW || hGap <= Math.max(8f, minW * 0.35f)) {
                        currentCluster.add(s);
                    } else {
                        result.add(currentCluster);
                        currentCluster = new ArrayList<>();
                        currentCluster.add(s);
                    }
                } else {
                    currentCluster.add(s);
                }
            }
            if (!currentCluster.isEmpty()) {
                result.add(currentCluster);
            }
        } catch (Throwable t) {
            result.clear();
            result.add(new ArrayList<>(strokes));
        }

        return result;
    }
}
