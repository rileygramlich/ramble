package dev.rileygramlich.yap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * The bars in the bubble's pill. While listening they scroll left with your
 * voice, newest on the right; while Ramble is writing they ripple on their own.
 */
final class Waveform extends View {
    private static final int BARS = 18;

    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean thinking;

    Waveform(Context context, int color) {
        super(context);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** One microphone reading (RMS, about 0 to 0.3 for speech). */
    void push(float rms) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1);
        levels[BARS - 1] = Math.min(1f, (float) Math.sqrt(rms) * 2.4f);
        invalidate();
    }

    void listen() {
        thinking = false;
        java.util.Arrays.fill(levels, 0f);
        invalidate();
    }

    void think() {
        thinking = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float step = w / BARS, bar = step * 0.5f, mid = h / 2f, min = bar;
        paint.setStrokeWidth(bar);
        double t = SystemClock.uptimeMillis() / 180.0;
        for (int i = 0; i < BARS; i++) {
            float level = thinking ? 0.25f + 0.2f * (float) Math.sin(t - i * 0.55) : levels[i];
            float half = Math.max(min, level * (h - bar)) / 2f;
            float x = step * i + step / 2f;
            canvas.drawLine(x, mid - half + bar / 2f, x, mid + half - bar / 2f, paint);
        }
        if (thinking) postInvalidateOnAnimation();
    }
}
