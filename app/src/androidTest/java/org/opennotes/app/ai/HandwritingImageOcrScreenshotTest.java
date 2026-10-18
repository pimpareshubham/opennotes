package org.opennotes.app.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/** Screenshot-based regression test using the user's actual black-canvas handwriting sample. */
@RunWith(AndroidJUnit4.class)
public class HandwritingImageOcrScreenshotTest {
    @Test public void recognizesHowAreYouScreenshot() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Bitmap sample;
        try (InputStream input = context.getAssets().open("how_are_you_handwriting.png")) {
            sample = BitmapFactory.decodeStream(input);
        }
        assertTrue("handwriting screenshot should load", sample != null);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> output = new AtomicReference<>("");
        HandwritingImageOcr.recognizeBitmap(context, sample, "ENGLISH", text -> {
            output.set(text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
            latch.countDown();
        });
        assertTrue("OCR should finish", latch.await(30, TimeUnit.SECONDS));
        sample.recycle();
        assertTrue("sample transcript should stay close to 'How are you'; got: " + output.get(),
                normalizedEditDistance("howareyou", output.get()) <= 0.34f);
    }

    private static float normalizedEditDistance(String expected, String actual) {
        int[] previous = new int[actual.length() + 1];
        for (int j = 0; j <= actual.length(); j++) previous[j] = j;
        for (int i = 1; i <= expected.length(); i++) {
            int[] current = new int[actual.length() + 1];
            current[0] = i;
            for (int j = 1; j <= actual.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (expected.charAt(i - 1) == actual.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[actual.length()] / (float) Math.max(1, expected.length());
    }
}
