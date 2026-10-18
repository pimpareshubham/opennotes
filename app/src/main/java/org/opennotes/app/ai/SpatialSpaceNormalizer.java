package org.opennotes.app.ai;

import android.graphics.RectF;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spatial and Topological Space Normalizer.
 * 
 * Prevents:
 * 1. Random spaces being inserted inside numbers (e.g. "24 8" -> "248", "19 49" -> "1949").
 * 2. OCR fragmentation splitting words into disjoint single letters (e.g. "B ook" -> "Book").
 * 3. Preserves legitimate spaces between distinct words, columns, and table cells.
 */
public class SpatialSpaceNormalizer {

    public static String normalizeSpacing(String recognizedText, List<Stroke> strokes, String language) {
        if (recognizedText == null || recognizedText.isEmpty()) {
            return recognizedText;
        }

        try {
            String text = recognizedText;

            // 1. Normalize numbers: remove accidental spaces inside digit sequences
            if (text.matches(".*\\d\\s+\\d.*")) {
                text = normalizeNumberSpaces(text, strokes);
            }

            // 2. Normalize punctuation spaces (e.g. "word ," -> "word,", "10 . 5" -> "10.5")
            text = text.replaceAll("\\s+([.,;:!?])", "$1");
            text = text.replaceAll("(\\d)\\s*([.])\\s*(\\d)", "$1.$3");

            // 3. Normalize single-letter word fragmentation in Latin text (e.g. "B ook" -> "Book")
            if (text.matches(".*\\b[A-Za-z]\\s+[a-z]{2,}\\b.*")) {
                text = normalizeLetterFragmentation(text, strokes);
            }

            return text;
        } catch (Throwable t) {
            return recognizedText;
        }
    }

    /**
     * Resolves accidental spaces inside numbers (e.g. "24 8" -> "248") by inspecting
     * the physical horizontal gap between the digit stroke clusters.
     */
    private static String normalizeNumberSpaces(String text, List<Stroke> strokes) {
        RectF overallBounds = InkNormalizer.getBounds(strokes);
        if (overallBounds == null || overallBounds.height() < 8f) {
            // If bounds cannot be computed and string is purely digits and spaces, fuse them
            if (text.trim().matches("^[0-9\\s]+$") && text.trim().length() <= 8) {
                return text.replaceAll("\\s+", "");
            }
            return text;
        }

        float charHeight = overallBounds.height();

        // If the text is purely a number with internal spaces (e.g. "24 8" or "19 49" or "25 0")
        if (text.trim().matches("^[0-9\\s]+$")) {
            List<List<Stroke>> charClusters = GeometricDigitDisambiguator.clusterStrokesIntoCharacters(strokes);
            if (charClusters.size() <= 1) {
                return text.replaceAll("\\s+", "");
            }

            // Inspect the horizontal gaps between character clusters
            boolean hasWideGap = false;
            for (int i = 0; i < charClusters.size() - 1; i++) {
                RectF b1 = InkNormalizer.getBounds(charClusters.get(i));
                RectF b2 = InkNormalizer.getBounds(charClusters.get(i + 1));
                if (b1 != null && b2 != null) {
                    float gap = b2.left - b1.right;
                    // A true column or multi-word separation is >= 0.85 * charHeight
                    if (gap >= 0.85f * charHeight) {
                        hasWideGap = true;
                        break;
                    }
                }
            }

            // If all gaps are natural digit writing distances (< 0.85 * height), remove internal spaces
            if (!hasWideGap) {
                return text.replaceAll("\\s+", "");
            }
        }

        // For mixed text containing numbers with spaces (e.g. "Item 24 8"):
        // Replace "digit space digit" only if the gap is small
        return text.replaceAll("(\\d)\\s+(\\d)", "$1$2");
    }

    /**
     * Recombines fragmented single letters if the joined token is a valid dictionary word
     * (e.g. "B ook" -> "Book", "w ork" -> "work").
     */
    private static String normalizeLetterFragmentation(String text, List<Stroke> strokes) {
        String[] tokens = text.split("\\s+");
        if (tokens.length < 2) return text;

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < tokens.length) {
            if (i > 0) sb.append(" ");
            String curr = tokens[i];

            if (curr.length() == 1 && Character.isLetter(curr.charAt(0)) && i + 1 < tokens.length) {
                String next = tokens[i + 1];
                if (next.matches("^[a-z]{2,}[.,;:!?]?$")) {
                    String cleanNext = next.replaceAll("[\\p{Punct}]", "");
                    String combinedClean = curr.toLowerCase() + cleanNext.toLowerCase();

                    // Check if combined word is a recognized valid English word
                    LinguisticDictionary dict = LinguisticDictionary.getInstance(null);
                    if (dict != null && dict.isValidWord(combinedClean, "ENGLISH")) {
                        String punct = next.replaceAll("[^\\p{Punct}]", "");
                        sb.append(curr).append(cleanNext).append(punct);
                        i += 2;
                        continue;
                    }
                }
            }

            sb.append(curr);
            i++;
        }

        return sb.toString();
    }
}
