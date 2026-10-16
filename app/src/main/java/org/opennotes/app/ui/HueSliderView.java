package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class HueSliderView extends View {

    public interface OnHueChangeListener {
        void onHueChanged(float hue);
    }

    private float hue = 0f;
    private Paint barPaint;
    private Paint thumbPaint;
    private Paint thumbBorderPaint;
    private RectF barBounds = new RectF();
    private OnHueChangeListener listener;

    private static final int[] HUE_COLORS = new int[]{
            0xFFFF0000, // Red
            0xFFFFFF00, // Yellow
            0xFF00FF00, // Green
            0xFF00FFFF, // Cyan
            0xFF0000FF, // Blue
            0xFFFF00FF, // Magenta
            0xFFFF0000  // Red
    };

    public HueSliderView(Context context) {
        super(context);
        init();
    }

    public HueSliderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HueSliderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.FILL);

        thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setStrokeWidth(3f);
        thumbBorderPaint.setColor(Color.WHITE);
    }

    public void setOnHueChangeListener(OnHueChangeListener listener) {
        this.listener = listener;
    }

    public void setHue(float hue) {
        this.hue = Math.max(0f, Math.min(360f, hue));
        invalidate();
    }

    public float getHue() {
        return hue;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = h * 0.25f;
        barBounds.set(padding, padding, w - padding, h - padding);

        LinearGradient gradient = new LinearGradient(
                barBounds.left, 0, barBounds.right, 0,
                HUE_COLORS, null, Shader.TileMode.CLAMP);
        barPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (barBounds.width() <= 0) return;

        // Draw rainbow bar
        canvas.drawRoundRect(barBounds, barBounds.height() / 2f, barBounds.height() / 2f, barPaint);

        // Draw thumb indicator
        float thumbX = barBounds.left + (hue / 360f) * barBounds.width();
        float thumbY = barBounds.centerY();
        float thumbRadius = getHeight() * 0.38f;

        thumbPaint.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbBorderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float x = Math.max(barBounds.left, Math.min(barBounds.right, event.getX()));
                hue = ((x - barBounds.left) / barBounds.width()) * 360f;
                hue = Math.max(0f, Math.min(360f, hue));
                invalidate();
                if (listener != null) {
                    listener.onHueChanged(hue);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
