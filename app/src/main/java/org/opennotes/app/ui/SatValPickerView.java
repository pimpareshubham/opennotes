package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class SatValPickerView extends View {

    public interface OnColorChangeListener {
        void onColorChanged(float hue, float sat, float val);
    }

    private float hue = 0f;
    private float sat = 1f;
    private float val = 1f;

    private Paint paint;
    private Paint cursorPaint;
    private Paint cursorOuterPaint;
    private RectF bounds = new RectF();
    private ComposeShader shader;

    private OnColorChangeListener listener;

    public SatValPickerView(Context context) {
        super(context);
        init();
    }

    public SatValPickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SatValPickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(4f);
        cursorPaint.setColor(Color.WHITE);

        cursorOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorOuterPaint.setStyle(Paint.Style.STROKE);
        cursorOuterPaint.setStrokeWidth(2f);
        cursorOuterPaint.setColor(0x88000000);
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }

    public void setHue(float hue) {
        this.hue = Math.max(0f, Math.min(360f, hue));
        updateShader();
        invalidate();
    }

    public void setSatVal(float sat, float val) {
        this.sat = Math.max(0f, Math.min(1f, sat));
        this.val = Math.max(0f, Math.min(1f, val));
        invalidate();
    }

    public float getHue() {
        return hue;
    }

    public float getSat() {
        return sat;
    }

    public float getVal() {
        return val;
    }

    public int getColor() {
        return Color.HSVToColor(new float[]{hue, sat, val});
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        updateShader();
    }

    private void updateShader() {
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        int rgb = Color.HSVToColor(new float[]{hue, 1f, 1f});
        Shader valShader = new LinearGradient(
                bounds.left, bounds.top, bounds.left, bounds.bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);

        Shader satShader = new LinearGradient(
                bounds.left, bounds.top, bounds.right, bounds.top,
                Color.WHITE, rgb, Shader.TileMode.CLAMP);

        shader = new ComposeShader(valShader, satShader, PorterDuff.Mode.SRC_OVER);
        paint.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bounds.width() <= 0) return;

        // Draw rounded rectangle field
        canvas.drawRoundRect(bounds, 16f, 16f, paint);

        // Draw cursor
        float cx = bounds.left + sat * bounds.width();
        float cy = bounds.top + (1f - val) * bounds.height();

        canvas.drawCircle(cx, cy, 14f, cursorPaint);
        canvas.drawCircle(cx, cy, 16f, cursorOuterPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float x = Math.max(bounds.left, Math.min(bounds.right, event.getX()));
                float y = Math.max(bounds.top, Math.min(bounds.bottom, event.getY()));

                sat = (x - bounds.left) / bounds.width();
                val = 1f - ((y - bounds.top) / bounds.height());

                invalidate();
                if (listener != null) {
                    listener.onColorChanged(hue, sat, val);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
