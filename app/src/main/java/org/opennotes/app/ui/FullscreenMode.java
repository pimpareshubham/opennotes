package org.opennotes.app.ui;

import android.app.Activity;

import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Keeps the app immersive while still allowing a swipe to reveal system bars temporarily. */
final class FullscreenMode {
    private FullscreenMode() {}

    static void apply(Activity activity) {
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                activity.getWindow(), activity.getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }
}
