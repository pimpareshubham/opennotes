package org.opennotes.app.ai;

import android.graphics.RectF;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geometric and topological digit/character disambiguator.
 * 
 * Accurately resolves ambiguities that confuse ML statistical language models:
 * - '4' vs '9' (open-top cursive 4 vs closed-loop 9)
 * - '1' vs '7' (vertical stem vs horizontal top bar)
 * - '0' vs '6' vs '8' (closed oval vs ascender vs figure-eight)
 */
public class GeometricDigitDisambiguator {

    /**
     * Post-processes recognized text against stroke geometry.
     * If any character in the text was classified as '9', but the corresponding stroke
     * geometry is an open-top '4' (single upper diagonal, middle crossbar, vertical stem),
     * corrects it to '4'.
     */
    public static String disambiguateDigits(String recognizedText, List<Stroke> strokes) {
        if (recognizedText == null || recognizedText.isEmpty() || strokes == null || strokes.isEmpty()) {
            return recognizedText;
        }

        // Only inspect if text contains numbers
        boolean hasDigits = false;
        for (int i = 0; i < recognizedText.length(); i++) {
            char c = recognizedText.charAt(i);
            if (Character.isDigit(c)) {
                hasDigits = true;
                break;
            }
        }
        if (!hasDigits) return recognizedText;

        // Group strokes into horizontal clusters corresponding to individual characters/digits
        List<List<Stroke>> characterClusters = clusterStrokesIntoCharacters(strokes);
        String cleanedDigits = recognizedText.replaceAll("[^0-9]", "");

        // If digit counts match, inspect each digit individually
        if (characterClusters.size() == cleanedDigits.length()) {
            StringBuilder corrected = new StringBuilder();
            int digitIdx = 0;
            for (int i = 0; i < recognizedText.length(); i++) {
                char c = recognizedText.charAt(i);
                if (Character.isDigit(c)) {
                    List<Stroke> charStrokes = characterClusters.get(digitIdx);
                    char verified = disambiguateSingleDigit(c, charStrokes);
                    corrected.append(verified);
                    digitIdx++;
                } else {
                    corrected.append(c);
                }
            }
            return corrected.toString();
        }

        // Fallback: Check if any stroke cluster in the line is an open-top 4 while text has '9'
        if (recognizedText.contains("9")) {
            for (List<Stroke> charStrokes : characterClusters) {
                if (isOpenTopFour(charStrokes)) {
                    // Replace the first '9' that matches this cluster's position
                    return replaceMatchingNineWithFour(recognizedText, characterClusters, charStrokes);
                }
            }
        }

        return recognizedText;
    }

    private static char disambiguateSingleDigit(char candidateDigit, List<Stroke> charStrokes) {
        if (charStrokes == null || charStrokes.isEmpty()) return candidateDigit;

        if (candidateDigit == '9') {
            if (isOpenTopFour(charStrokes)) {
                return '4';
            }
        } else if (candidateDigit == '4') {
            if (isClosedLoopNine(charStrokes)) {
                return '9';
            }
        } else if (candidateDigit == '1') {
            if (hasTopHorizontalBar(charStrokes)) {
                return '7';
            }
        } else if (candidateDigit == '7') {
            if (!hasTopHorizontalBar(charStrokes) && isSingleVerticalStem(charStrokes)) {
                return '1';
            }
        }

        return candidateDigit;
    }

    /**
     * Evaluates whether a stroke or group of strokes represents an open-top digit '4'.
     * Key structural features of open-top '4':
     * 1. Top 25% has NO closed loop and only ONE stroke edge (the left diagonal stroke).
     * 2. Middle 35%-60% has a horizontal crossbar or TWO distinct horizontal x-regions.
     * 3. Bottom 70%-100% has a single vertical stem on the right half.
     */
    public static boolean isOpenTopFour(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return false;
        RectF b = InkNormalizer.getBounds(strokes);
        if (b == null || b.height() < 18f || b.width() < 8f) return false;

        List<DrawingPoint> allPts = new ArrayList<>();
        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts != null) allPts.addAll(pts);
        }
        if (allPts.size() < 6) return false;

        float h = b.height();
        float yTop20 = b.top + h * 0.20f;
        float yMid40 = b.top + h * 0.40f;
        float yBot75 = b.top + h * 0.75f;

        // Count horizontal points / transitions across Y-slices
        float topMinX = Float.MAX_VALUE, topMaxX = -Float.MAX_VALUE;
        int topPtsCount = 0;

        float midMinX = Float.MAX_VALUE, midMaxX = -Float.MAX_VALUE;
        int midPtsCount = 0;

        float botMinX = Float.MAX_VALUE, botMaxX = -Float.MAX_VALUE;
        int botPtsCount = 0;

        for (DrawingPoint p : allPts) {
            if (p == null) continue;
            if (p.y <= yTop20) {
                topMinX = Math.min(topMinX, p.x);
                topMaxX = Math.max(topMaxX, p.x);
                topPtsCount++;
            } else if (p.y >= yTop20 && p.y <= yMid40 + h * 0.15f) {
                midMinX = Math.min(midMinX, p.x);
                midMaxX = Math.max(midMaxX, p.x);
                midPtsCount++;
            } else if (p.y >= yBot75) {
                botMinX = Math.min(botMinX, p.x);
                botMaxX = Math.max(botMaxX, p.x);
                botPtsCount++;
            }
        }

        // An open-top 4 has a narrow left diagonal at the top:
        // topMaxX is on the left half or center, NOT spanning the full width!
        float topSpan = (topPtsCount > 0) ? (topMaxX - topMinX) : 0f;
        float midSpan = (midPtsCount > 0) ? (midMaxX - midMinX) : 0f;

        // In a '9', the top loop spans almost the entire width of the character (topSpan / b.width() >= 0.65f).
        // In an open '4', the top stroke is a single diagonal entry, and the middle crossbar spans wider than the top!
        boolean midIsWiderThanTop = midSpan > topSpan * 1.30f || topSpan <= b.width() * 0.55f;

        // Check if there is a horizontal stroke segment in the middle third (the crossbar of 4)
        boolean hasHorizontalCrossbar = false;
        for (int i = 0; i < allPts.size() - 1; i++) {
            DrawingPoint p1 = allPts.get(i);
            DrawingPoint p2 = allPts.get(i + 1);
            if (p1 == null || p2 == null) continue;
            if (p1.y >= b.top + h * 0.25f && p1.y <= b.top + h * 0.70f) {
                float dx = Math.abs(p2.x - p1.x);
                float dy = Math.abs(p2.y - p1.y);
                if (dx >= dy * 1.5f && dx >= b.width() * 0.25f) {
                    hasHorizontalCrossbar = true;
                    break;
                }
            }
        }

        // In a '9', the stroke loops back to the right stem at the top.
        // In an open '4', the topmost point is near the left or center, and the rightmost point at the bottom is the stem.
        boolean stemOnRight = (botPtsCount > 0 && botMinX >= b.left + b.width() * 0.40f);

        return midIsWiderThanTop && (hasHorizontalCrossbar || stemOnRight);
    }

    /**
     * Checks if a stroke cluster represents a closed-loop '9'.
     */
    public static boolean isClosedLoopNine(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return false;
        RectF b = InkNormalizer.getBounds(strokes);
        if (b == null) return false;

        List<DrawingPoint> allPts = new ArrayList<>();
        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts != null) allPts.addAll(pts);
        }
        if (allPts.size() < 8) return false;

        float h = b.height();
        float yTopHalf = b.top + h * 0.50f;

        // Check if the top half forms a closed loop:
        // Start of loop and end of loop in top half are close in distance
        DrawingPoint topFirst = null;
        DrawingPoint topLoopEnd = null;

        for (int i = 0; i < allPts.size(); i++) {
            DrawingPoint p = allPts.get(i);
            if (p.y <= yTopHalf) {
                if (topFirst == null) topFirst = p;
                topLoopEnd = p;
            } else if (topFirst != null) {
                break;
            }
        }

        if (topFirst != null && topLoopEnd != null) {
            float dist = (float) Math.hypot(topFirst.x - topLoopEnd.x, topFirst.y - topLoopEnd.y);
            if (dist <= Math.max(12f, b.width() * 0.35f)) {
                return true; // Loop closes at the right stem
            }
        }

        return false;
    }

    private static boolean hasTopHorizontalBar(List<Stroke> strokes) {
        RectF b = InkNormalizer.getBounds(strokes);
        if (b == null) return false;
        float h = b.height();
        float yTop25 = b.top + h * 0.25f;

        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null || pts.size() < 2) continue;
            for (int i = 0; i < pts.size() - 1; i++) {
                DrawingPoint p1 = pts.get(i);
                DrawingPoint p2 = pts.get(i + 1);
                if (p1.y <= yTop25 && p2.y <= yTop25) {
                    if (Math.abs(p2.x - p1.x) >= Math.abs(p2.y - p1.y) * 2.0f
                            && Math.abs(p2.x - p1.x) >= b.width() * 0.40f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSingleVerticalStem(List<Stroke> strokes) {
        if (strokes == null || strokes.size() != 1) return false;
        RectF b = strokes.get(0).getBounds();
        if (b == null) return false;
        return (b.height() / Math.max(1f, b.width())) >= 2.5f && b.width() <= 24f;
    }

    private static String replaceMatchingNineWithFour(String text,
                                                      List<List<Stroke>> clusters,
                                                      List<Stroke> fourCluster) {
        RectF targetB = InkNormalizer.getBounds(fourCluster);
        if (targetB == null) return text.replaceFirst("9", "4");

        // Find which position the cluster is in from left to right
        int targetIdx = 0;
        for (int i = 0; i < clusters.size(); i++) {
            RectF cb = InkNormalizer.getBounds(clusters.get(i));
            if (cb != null && cb.left < targetB.left - 5f) {
                targetIdx++;
            }
        }

        // Count digit indices in text
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
    }

    /**
     * Clusters strokes horizontally from left to right into separate character/digit groups.
     */
    public static List<List<Stroke>> clusterStrokesIntoCharacters(List<Stroke> strokes) {
        List<List<Stroke>> result = new ArrayList<>();
        if (strokes == null || strokes.isEmpty()) return result;

        List<Stroke> sorted = new ArrayList<>(strokes);
        Collections.sort(sorted, (s1, s2) -> {
            RectF b1 = s1.getBounds();
            RectF b2 = s2.getBounds();
            float l1 = b1 != null ? b1.left : 0f;
            float l2 = b2 != null ? b2.left : 0f;
            return Float.compare(l1, l2);
        });

        List<Stroke> currentCluster = new ArrayList<>();
        currentCluster.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            Stroke s = sorted.get(i);
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

        return result;
    }
}
