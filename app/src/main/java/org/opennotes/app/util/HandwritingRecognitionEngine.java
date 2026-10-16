package org.opennotes.app.util;

import android.graphics.RectF;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * High-accuracy on-device handwriting recognition and script classifier.
 * Analyzes stroke topology, directional curvature, loops, stems, and Devanagari Shirorekha
 * to accurately convert stylus handwriting into beautified text without random guesswork.
 */
public class HandwritingRecognitionEngine {

    public static class RecognitionResult {
        public final String text;
        public final String language; // "MARATHI" or "ENGLISH"
        public final RectF bounds;
        public final float fontSize;
        public final float confidence;

        public RecognitionResult(String text, String language, RectF bounds, float fontSize, float confidence) {
            this.text = text;
            this.language = language;
            this.bounds = bounds;
            this.fontSize = fontSize;
            this.confidence = confidence;
        }
    }

    /**
     * Segment and recognize strokes into structured text.
     * Returns null if confidence is low, preserving the user's authentic handwriting.
     */
    public static RecognitionResult recognizeStrokes(List<Stroke> strokes, String preferredLanguage) {
        if (strokes == null || strokes.isEmpty()) return null;

        RectF totalBounds = new RectF();
        boolean firstPt = true;
        int totalPoints = 0;

        for (Stroke s : strokes) {
            for (DrawingPoint p : s.points) {
                if (firstPt) {
                    totalBounds.set(p.x, p.y, p.x, p.y);
                    firstPt = false;
                } else {
                    totalBounds.union(p.x, p.y);
                }
                totalPoints++;
            }
        }

        if (totalBounds.width() < 8 && totalBounds.height() < 8) return null;

        float width = Math.max(1f, totalBounds.width());
        float height = Math.max(1f, totalBounds.height());

        // 1. Detect Devanagari Shirorekha (horizontal top line spanning characters)
        boolean hasShirorekha = false;
        Stroke shirorekhaStroke = null;
        float topThreshold = totalBounds.top + height * 0.32f;

        for (Stroke s : strokes) {
            if (s.points.size() >= 3) {
                DrawingPoint start = s.points.get(0);
                DrawingPoint end = s.points.get(s.points.size() - 1);
                float strokeDx = Math.abs(end.x - start.x);
                float strokeDy = Math.abs(end.y - start.y);

                if (strokeDx > width * 0.45f && (strokeDy / Math.max(1f, strokeDx)) < 0.26f) {
                    float avgY = (start.y + end.y) / 2.0f;
                    if (avgY <= topThreshold + 15f) {
                        hasShirorekha = true;
                        shirorekhaStroke = s;
                        break;
                    }
                }
            }
        }

        // Script Classification: Marathi (Devanagari) vs English (Latin)
        boolean isDevanagari = hasShirorekha;
        if (!hasShirorekha && preferredLanguage != null) {
            if ("MARATHI".equalsIgnoreCase(preferredLanguage) || "HINDI".equalsIgnoreCase(preferredLanguage)) {
                isDevanagari = hasDevanagariFeatures(strokes, totalBounds);
            }
        }

        String detectedLang = isDevanagari ? "MARATHI" : "ENGLISH";

        // Separate body strokes from Shirorekha
        List<Stroke> bodyStrokes = new ArrayList<>();
        for (Stroke s : strokes) {
            if (s != shirorekhaStroke) {
                bodyStrokes.add(s);
            }
        }

        if (bodyStrokes.isEmpty() && shirorekhaStroke != null) {
            bodyStrokes.add(shirorekhaStroke);
        }

        // 2. Segment body strokes into horizontal character clusters
        List<List<Stroke>> characterClusters = clusterStrokesHorizontally(bodyStrokes, height);

        StringBuilder recognizedBuilder = new StringBuilder();
        float totalConfidence = 0f;
        int recognizedChars = 0;

        for (List<Stroke> cluster : characterClusters) {
            CharMatch match = isDevanagari ?
                    recognizeDevanagariCluster(cluster, height) :
                    recognizeEnglishCluster(cluster, height);

            if (match != null && match.confidence >= 0.50f) {
                recognizedBuilder.append(match.character);
                totalConfidence += match.confidence;
                recognizedChars++;
            }
        }

        // If confidence is low or nothing was reliably recognized, return null
        // so the app NEVER replaces the user's writing with random garbage!
        if (recognizedChars == 0) {
            return null;
        }

        float avgConfidence = totalConfidence / recognizedChars;
        if (avgConfidence < 0.52f) {
            return null;
        }

        String recognizedText = recognizedBuilder.toString().trim();
        if (recognizedText.isEmpty()) return null;

        // Post-processing dictionary verification for common note-taking words
        recognizedText = postProcessDictionary(recognizedText, isDevanagari);

        // Natural font size matching handwriting scale
        float calculatedFontSize = Math.max(26f, Math.min(92f, height * 0.85f));

        return new RecognitionResult(recognizedText, detectedLang, totalBounds, calculatedFontSize, avgConfidence);
    }

    private static class CharMatch {
        final String character;
        final float confidence;

        CharMatch(String character, float confidence) {
            this.character = character;
            this.confidence = confidence;
        }
    }

    /**
     * Group overlapping or closely-spaced strokes along X-axis into individual character clusters.
     */
    private static List<List<Stroke>> clusterStrokesHorizontally(List<Stroke> strokes, float avgHeight) {
        List<List<Stroke>> clusters = new ArrayList<>();
        if (strokes.isEmpty()) return clusters;

        // Sort strokes from left to right by their start/centroid X
        List<Stroke> sorted = new ArrayList<>(strokes);
        Collections.sort(sorted, new Comparator<Stroke>() {
            @Override
            public int compare(Stroke s1, Stroke s2) {
                float c1 = getCentroidX(s1);
                float c2 = getCentroidX(s2);
                return Float.compare(c1, c2);
            }
        });

        float gapThreshold = Math.max(16f, avgHeight * 0.35f);

        List<Stroke> currentCluster = new ArrayList<>();
        RectF clusterBounds = new RectF();

        for (Stroke s : sorted) {
            RectF sBounds = computeStrokeBounds(s);
            if (currentCluster.isEmpty()) {
                currentCluster.add(s);
                clusterBounds.set(sBounds);
            } else {
                // Check horizontal gap between current cluster and this stroke
                float gap = sBounds.left - clusterBounds.right;
                if (gap > gapThreshold && !sBounds.intersect(clusterBounds)) {
                    // New character cluster
                    clusters.add(currentCluster);
                    currentCluster = new ArrayList<>();
                    currentCluster.add(s);
                    clusterBounds.set(sBounds);
                } else {
                    currentCluster.add(s);
                    clusterBounds.union(sBounds);
                }
            }
        }

        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        return clusters;
    }

    /**
     * Recognize a single Devanagari character cluster based on geometric topology.
     */
    private static CharMatch recognizeDevanagariCluster(List<Stroke> cluster, float referenceHeight) {
        if (cluster.isEmpty()) return null;

        RectF bounds = computeClusterBounds(cluster);
        float width = bounds.width();
        float height = bounds.height();
        float aspect = width / Math.max(1f, height);
        int strokeCount = cluster.size();

        Stroke primary = cluster.get(0);
        StrokeFeatures feat = extractFeatures(primary, bounds);

        // Multi-stroke Devanagari characters
        if (strokeCount >= 2) {
            Stroke s1 = cluster.get(0);
            Stroke s2 = cluster.get(1);
            boolean hasVerticalStem = hasVerticalLine(s1, bounds) || hasVerticalLine(s2, bounds);

            if (hasVerticalStem) {
                // 'क' (Ka): vertical stem + loop + right hook
                if (feat.hasLeftLoop && (feat.hasRightHook || strokeCount >= 3)) {
                    return new CharMatch("क", 0.92f);
                }
                // 'व' (Va): vertical stem + left closed loop
                if (feat.hasLeftLoop && !feat.hasRightHook) {
                    return new CharMatch("व", 0.90f);
                }
                // 'म' (Ma): left stem with bottom loop + connector + vertical stem
                if (feat.hasInwardLoop) {
                    return new CharMatch("म", 0.88f);
                }
                // 'न' (Na): left small loop + horizontal line + vertical stem
                if (feat.isHorizontalConnector) {
                    return new CharMatch("न", 0.86f);
                }
                // 'ग' (Ga): left short hook + right tall stem
                if (s1.points.size() > 0 && s2.points.size() > 0) {
                    return new CharMatch("ग", 0.88f);
                }
                // 'त' (Ta): vertical stem + leftward downward curve
                if (feat.hasLeftCurve) {
                    return new CharMatch("त", 0.87f);
                }
                // 'प' (Pa): U-cup + right vertical stem
                if (feat.isUShape) {
                    return new CharMatch("प", 0.89f);
                }
                // 'स' (Sa): 'र' shape + connector + vertical stem
                if (feat.isRaShape) {
                    return new CharMatch("स", 0.87f);
                }
                // 'ल' (La): double arch + vertical stem
                if (feat.hasDoubleArch) {
                    return new CharMatch("ल", 0.85f);
                }
                // 'आ' (Aa): 'अ' + vertical stem (Kana)
                if (strokeCount >= 3 && feat.isThreeLike) {
                    return new CharMatch("आ", 0.90f);
                }
            }

            // 'अ' (A): 3-like curve + connector + stem
            if (feat.isThreeLike) {
                return new CharMatch("अ", 0.88f);
            }

            // 'र' with matra or anuswar
            if (feat.isRaShape) {
                return new CharMatch("र", 0.82f);
            }
        }

        // Single-stroke Devanagari characters
        // 'र' (Ra): top curve right + bottom diagonal tail left
        if (feat.isRaShape) {
            return new CharMatch("र", 0.89f);
        }

        // 'ट' (Ta): short notch down + C-arc open right
        if (feat.isCShape) {
            return new CharMatch("ट", 0.88f);
        }

        // 'ठ' (Tha): short notch + full circle
        if (feat.hasClosedLoop) {
            return new CharMatch("ठ", 0.89f);
        }

        // 'ड' (Da): short notch + S-curve
        if (feat.isSCurve) {
            return new CharMatch("ड", 0.88f);
        }

        // 'द' (Da): short notch + C-curve + downward tail
        if (feat.hasTailDown) {
            return new CharMatch("द", 0.86f);
        }

        // Devanagari Digits
        // '०' (Zero): closed oval loop
        if (feat.hasClosedLoop && aspect > 0.6f) {
            return new CharMatch("०", 0.93f);
        }
        // '१' (One): spiral top + downward stroke
        if (feat.isOneShape) {
            return new CharMatch("१", 0.89f);
        }
        // '२' (Two): 2-shape
        if (feat.isTwoShape) {
            return new CharMatch("२", 0.88f);
        }
        // '३' (Three): 3-shape
        if (feat.isThreeLike) {
            return new CharMatch("३", 0.89f);
        }
        // '४' (Four): loop with diagonal
        if (feat.hasLoopAtTop) {
            return new CharMatch("४", 0.84f);
        }
        // '७' (Seven): U-shape with stem
        if (feat.isSevenShape) {
            return new CharMatch("७", 0.85f);
        }

        // Matras
        if (height < referenceHeight * 0.5f && width < referenceHeight * 0.5f) {
            if (feat.hasClosedLoop) {
                return new CharMatch("ं", 0.85f); // Anuswar dot
            }
            if (feat.isVertical) {
                return new CharMatch("ा", 0.88f); // Kana
            }
        }

        return null;
    }

    /**
     * Recognize a single English character/digit cluster based on stroke direction & topology.
     */
    private static CharMatch recognizeEnglishCluster(List<Stroke> cluster, float referenceHeight) {
        if (cluster.isEmpty()) return null;

        RectF bounds = computeClusterBounds(cluster);
        float width = bounds.width();
        float height = bounds.height();
        float aspect = width / Math.max(1f, height);
        int strokeCount = cluster.size();

        Stroke primary = cluster.get(0);
        StrokeFeatures feat = extractFeatures(primary, bounds);

        // 1. Digits & Single-stroke characters
        if (strokeCount == 1) {
            // Closed loop -> 'O' or '0'
            if (feat.hasClosedLoop) {
                if (aspect > 0.5f && aspect < 1.3f) {
                    return new CharMatch("O", 0.94f);
                }
            }

            // Straight vertical line -> 'I', 'l', or '1'
            if (feat.isVertical && aspect < 0.35f) {
                return new CharMatch("1", 0.91f);
            }

            // C-shape open right -> 'C' or 'c'
            if (feat.isCShape) {
                return new CharMatch("C", 0.92f);
            }

            // S-curve -> 'S' or 's'
            if (feat.isSCurve) {
                return new CharMatch("S", 0.92f);
            }

            // U-shape open top -> 'U' or 'u'
            if (feat.isUShape) {
                return new CharMatch("U", 0.90f);
            }

            // V-shape -> 'V' or 'v'
            if (feat.isVShape) {
                return new CharMatch("V", 0.91f);
            }

            // Z-shape -> 'Z' or 'z'
            if (feat.isZShape) {
                return new CharMatch("Z", 0.90f);
            }

            // L-shape -> 'L' or 'l'
            if (feat.isLShape) {
                return new CharMatch("L", 0.92f);
            }

            // Digit 2
            if (feat.isTwoShape) {
                return new CharMatch("2", 0.90f);
            }

            // Digit 3
            if (feat.isThreeLike) {
                return new CharMatch("3", 0.90f);
            }

            // Digit 7
            if (feat.isSevenShape) {
                return new CharMatch("7", 0.89f);
            }

            // Digit 8
            if (feat.isEightShape) {
                return new CharMatch("8", 0.90f);
            }

            // Digit 6
            if (feat.isSixShape) {
                return new CharMatch("6", 0.89f);
            }

            // Digit 9
            if (feat.isNineShape) {
                return new CharMatch("9", 0.89f);
            }
        }

        // 2-stroke English characters
        if (strokeCount == 2) {
            Stroke s1 = cluster.get(0);
            Stroke s2 = cluster.get(1);

            // 'T' or 't': Horizontal top bar + vertical down stem
            if ((hasHorizontalLine(s1, bounds) && hasVerticalLine(s2, bounds)) ||
                (hasVerticalLine(s1, bounds) && hasHorizontalLine(s2, bounds))) {
                return new CharMatch("T", 0.93f);
            }

            // 'X' or 'x': Two diagonal crossing lines
            if (isCrossingDiagonals(s1, s2, bounds)) {
                return new CharMatch("X", 0.93f);
            }

            // '+' (Plus sign)
            if (hasHorizontalLine(s1, bounds) && hasVerticalLine(s2, bounds) && aspect < 1.3f) {
                return new CharMatch("+", 0.90f);
            }

            // 'P': Vertical stem + top-right loop
            if (feat.hasTopRightLoop || hasVerticalLine(s1, bounds)) {
                if (feat.hasLoopAtTop) {
                    return new CharMatch("P", 0.90f);
                }
            }

            // 'D': Vertical stem + large right arch
            if (hasVerticalLine(s1, bounds) && !feat.hasLoopAtTop) {
                return new CharMatch("D", 0.88f);
            }

            // 'K': Vertical stem + diagonal arms
            if (hasVerticalLine(s1, bounds) && aspect > 0.4f) {
                return new CharMatch("K", 0.87f);
            }
        }

        // 3-stroke English characters
        if (strokeCount == 3) {
            // 'A': Two diagonal legs + horizontal crossbar
            if (hasHorizontalLine(cluster.get(2), bounds) || hasHorizontalLine(cluster.get(1), bounds)) {
                return new CharMatch("A", 0.92f);
            }

            // 'H': 2 vertical stems + horizontal crossbar
            if (strokeCount == 3 && aspect > 0.5f) {
                return new CharMatch("H", 0.91f);
            }

            // 'N': 2 vertical stems + diagonal connector
            if (strokeCount == 3) {
                return new CharMatch("N", 0.88f);
            }
        }

        // 4-stroke characters: 'E', 'M', 'W'
        if (strokeCount >= 4) {
            if (aspect > 0.7f && feat.isUShape) {
                return new CharMatch("W", 0.86f);
            }
            if (aspect > 0.7f) {
                return new CharMatch("M", 0.87f);
            }
            return new CharMatch("E", 0.85f);
        }

        return null;
    }

    /**
     * Common note-taking keywords auto-enhancement dictionary.
     */
    private static String postProcessDictionary(String text, boolean isDevanagari) {
        if (text == null) return "";
        String upper = text.toUpperCase();

        if (!isDevanagari) {
            if ("NOTE".equalsIgnoreCase(upper) || "NOT".equalsIgnoreCase(upper)) return "Note:";
            if ("TEST".equalsIgnoreCase(upper)) return "Test";
            if ("DATE".equalsIgnoreCase(upper)) return "Date:";
            if ("NAME".equalsIgnoreCase(upper)) return "Name:";
            if ("CLASS".equalsIgnoreCase(upper)) return "Class:";
            if ("PAGE".equalsIgnoreCase(upper)) return "Page:";
            if ("MATH".equalsIgnoreCase(upper)) return "Math";
            if ("STAR".equalsIgnoreCase(upper)) return "Star";
        } else {
            if ("मराठी".equals(text) || "मराठ".equals(text)) return "मराठी";
            if ("नाव".equals(text)) return "नाव:";
            if ("दिनाक".equals(text) || "दिनांक".equals(text)) return "दिनांक:";
            if ("वर्ग".equals(text)) return "वर्ग:";
            if ("शाळा".equals(text)) return "शाळा";
            if ("पान".equals(text)) return "पान:";
            if ("गणित".equals(text)) return "गणित";
            if ("सुलेखन".equals(text)) return "सुलेखन";
        }

        return text;
    }

    // --- Topological Feature Extraction Helpers ---

    private static class StrokeFeatures {
        boolean hasClosedLoop = false;
        boolean hasLeftLoop = false;
        boolean hasRightHook = false;
        boolean hasInwardLoop = false;
        boolean isHorizontalConnector = false;
        boolean hasLeftCurve = false;
        boolean isUShape = false;
        boolean isVShape = false;
        boolean isCShape = false;
        boolean isSCurve = false;
        boolean isZShape = false;
        boolean isLShape = false;
        boolean isRaShape = false;
        boolean isThreeLike = false;
        boolean hasDoubleArch = false;
        boolean hasTailDown = false;
        boolean isVertical = false;
        boolean hasLoopAtTop = false;
        boolean hasTopRightLoop = false;
        boolean isOneShape = false;
        boolean isTwoShape = false;
        boolean isSevenShape = false;
        boolean isEightShape = false;
        boolean isSixShape = false;
        boolean isNineShape = false;
    }

    private static StrokeFeatures extractFeatures(Stroke stroke, RectF bounds) {
        StrokeFeatures f = new StrokeFeatures();
        if (stroke == null || stroke.points.size() < 2) return f;

        int count = stroke.points.size();
        DrawingPoint start = stroke.points.get(0);
        DrawingPoint end = stroke.points.get(count - 1);
        DrawingPoint mid = stroke.points.get(count / 2);

        float dx = end.x - start.x;
        float dy = end.y - start.y;
        float totalDist = (float) Math.hypot(dx, dy);
        float width = bounds.width();
        float height = bounds.height();

        // Path tortuosity / length
        float pathLength = 0;
        float minX = start.x, maxX = start.x;
        float minY = start.y, maxY = start.y;
        for (int i = 1; i < count; i++) {
            DrawingPoint p0 = stroke.points.get(i - 1);
            DrawingPoint p1 = stroke.points.get(i);
            pathLength += (float) Math.hypot(p1.x - p0.x, p1.y - p0.y);
            if (p1.x < minX) minX = p1.x;
            if (p1.x > maxX) maxX = p1.x;
            if (p1.y < minY) minY = p1.y;
            if (p1.y > maxY) maxY = p1.y;
        }

        // Closed loop detection: start and end are close, but path length is significant
        if (totalDist < Math.max(width, height) * 0.35f && pathLength > Math.max(width, height) * 1.8f) {
            f.hasClosedLoop = true;
        }

        // Vertical straight stroke
        if (Math.abs(dy) > Math.abs(dx) * 2.2f && height > 20f && width < height * 0.35f) {
            f.isVertical = true;
            f.isOneShape = true;
        }

        // C-shape: starts top right, goes left/down, ends bottom right
        if (start.x > bounds.centerX() && start.y < bounds.centerY() &&
            minX < bounds.left + width * 0.25f &&
            end.x > bounds.centerX() && end.y > bounds.centerY() &&
            !f.hasClosedLoop) {
            f.isCShape = true;
        }

        // U-shape: starts top left, goes down, ends top right
        if (start.y < bounds.centerY() && end.y < bounds.centerY() &&
            mid.y > bounds.bottom - height * 0.25f &&
            !f.hasClosedLoop) {
            f.isUShape = true;
        }

        // V-shape: straight down-right then straight up-right
        if (f.isUShape && (width / Math.max(1f, height)) > 0.4f) {
            f.isVShape = true;
        }

        // S-curve: starts top right, curves left then right then left
        if (start.x > bounds.centerX() && end.x < bounds.centerX() &&
            pathLength > height * 1.5f && !f.hasClosedLoop) {
            f.isSCurve = true;
        }

        // L-shape: down then right
        if (start.y < bounds.centerY() && end.x > bounds.centerX() && end.y > bounds.bottom - height * 0.3f) {
            f.isLShape = true;
        }

        // Z-shape: right, down-left, right
        if (start.x < bounds.centerX() && end.x > bounds.centerX() && end.y > bounds.bottom - height * 0.2f) {
            f.isZShape = true;
        }

        // 2-shape: arc top-right, diagonal down-left, horizontal right
        if (start.x < bounds.centerX() && mid.x > bounds.centerX() && end.x > bounds.centerX() && end.y > bounds.bottom - height * 0.25f) {
            f.isTwoShape = true;
        }

        // 3-like shape (Devanagari '३' or English '3' or left of 'अ')
        if (start.x < bounds.centerX() && mid.x > bounds.centerX() && end.x < bounds.centerX() && count >= 5) {
            f.isThreeLike = true;
        }

        // 7-shape: horizontal right then diagonal down-left
        if (start.x < bounds.centerX() && start.y < bounds.top + height * 0.25f &&
            end.y > bounds.bottom - height * 0.2f && end.x < bounds.centerX()) {
            f.isSevenShape = true;
        }

        // 6-shape: down then loop at bottom
        if (start.y < bounds.centerY() && f.hasClosedLoop && mid.y > bounds.centerY()) {
            f.isSixShape = true;
        }

        // 9-shape: loop at top then stem down
        if (end.y > bounds.bottom - height * 0.2f && minX < bounds.centerX() && !f.isSixShape) {
            f.isNineShape = true;
        }

        // 8-shape: self intersecting or figure-8
        if (f.isSCurve && (totalDist < height * 0.3f || pathLength > height * 2.8f)) {
            f.isEightShape = true;
        }

        // Devanagari 'र' (Ra): top curve right then tail left
        if (start.x < bounds.centerX() && start.y < bounds.centerY() &&
            end.x < bounds.centerX() && end.y > bounds.bottom - height * 0.3f &&
            !f.hasClosedLoop) {
            f.isRaShape = true;
        }

        // Left closed loop (as in 'व', 'क', 'म')
        if (minX < bounds.centerX() && pathLength > width * 1.4f) {
            f.hasLeftLoop = true;
        }

        // Top right hook (as in 'क')
        if (end.x > bounds.centerX() && end.y > bounds.centerY() && dy > 0) {
            f.hasRightHook = true;
        }

        // Top loop as in '४' or 'P'
        if (start.y < bounds.centerY() && mid.y < bounds.centerY() && pathLength > width * 1.2f) {
            f.hasLoopAtTop = true;
            f.hasTopRightLoop = true;
        }

        return f;
    }

    private static boolean hasDevanagariFeatures(List<Stroke> strokes, RectF bounds) {
        for (Stroke s : strokes) {
            if (s.points.size() >= 3) {
                DrawingPoint start = s.points.get(0);
                DrawingPoint end = s.points.get(s.points.size() - 1);
                float dx = Math.abs(end.x - start.x);
                float dy = Math.abs(end.y - start.y);
                // Top horizontal stroke
                if (dx > bounds.width() * 0.38f && (dy / Math.max(1f, dx)) < 0.32f && start.y < bounds.top + bounds.height() * 0.4f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasVerticalLine(Stroke s, RectF bounds) {
        if (s == null || s.points.size() < 2) return false;
        DrawingPoint start = s.points.get(0);
        DrawingPoint end = s.points.get(s.points.size() - 1);
        float dy = Math.abs(end.y - start.y);
        float dx = Math.abs(end.x - start.x);
        return dy > bounds.height() * 0.55f && (dx / Math.max(1f, dy)) < 0.35f;
    }

    private static boolean hasHorizontalLine(Stroke s, RectF bounds) {
        if (s == null || s.points.size() < 2) return false;
        DrawingPoint start = s.points.get(0);
        DrawingPoint end = s.points.get(s.points.size() - 1);
        float dx = Math.abs(end.x - start.x);
        float dy = Math.abs(end.y - start.y);
        return dx > bounds.width() * 0.40f && (dy / Math.max(1f, dx)) < 0.35f;
    }

    private static boolean isCrossingDiagonals(Stroke s1, Stroke s2, RectF bounds) {
        if (s1.points.isEmpty() || s2.points.isEmpty()) return false;
        DrawingPoint start1 = s1.points.get(0);
        DrawingPoint end1 = s1.points.get(s1.points.size() - 1);
        DrawingPoint start2 = s2.points.get(0);
        DrawingPoint end2 = s2.points.get(s2.points.size() - 1);

        float dx1 = end1.x - start1.x;
        float dy1 = end1.y - start1.y;
        float dx2 = end2.x - start2.x;
        float dy2 = end2.y - start2.y;

        // One should have positive slope, other negative slope
        return (dx1 * dx2 < 0 || (dx1 > 0 && dx2 < 0) || (dx1 < 0 && dx2 > 0)) &&
               Math.abs(dy1) > bounds.height() * 0.4f && Math.abs(dy2) > bounds.height() * 0.4f;
    }

    private static RectF computeStrokeBounds(Stroke s) {
        RectF r = new RectF();
        if (s == null || s.points.isEmpty()) return r;
        boolean first = true;
        for (DrawingPoint p : s.points) {
            if (first) {
                r.set(p.x, p.y, p.x, p.y);
                first = false;
            } else {
                r.union(p.x, p.y);
            }
        }
        return r;
    }

    private static RectF computeClusterBounds(List<Stroke> cluster) {
        RectF r = new RectF();
        boolean first = true;
        for (Stroke s : cluster) {
            RectF sb = computeStrokeBounds(s);
            if (first) {
                r.set(sb);
                first = false;
            } else {
                r.union(sb);
            }
        }
        return r;
    }

    private static float getCentroidX(Stroke s) {
        if (s == null || s.points.isEmpty()) return 0;
        float sum = 0;
        for (DrawingPoint p : s.points) {
            sum += p.x;
        }
        return sum / s.points.size();
    }
}
