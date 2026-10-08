package dev.rileygramlich.yap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * The bars in the bubble's pill. While listening they scroll left with your
 * voice, newest on the right; while Ramble is writing they ripple on their own.
 *
 * Loudness is shown in decibels, the way meters do, against a range that follows
 * this mic and this voice: the bottom tracks the room's noise floor (so silence
 * stays flat), the top tracks your recent loudest moments (so normal speech fills
 * most of the height whether the phone is close or at arm's length).
 */
final class Waveform extends View {
    private static final int BARS = 18;
    /** Below this many dB above the noise floor counts as silence and draws flat. */
    private static final float GATE_DB = 6f;
    /** The smallest range shown, so a quiet room doesn't turn breathing into speech. */
    private static final float MIN_RANGE_DB = 18f;
    /** How fast the noise floor may rise, and the peak fall, per reading (50 ms). */
    private static final float FLOOR_RISE_DB = 0.25f, PEAK_FALL_DB = 0.12f;

    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private boolean thinking;
    private float floorDb, peakDb;

    Waveform(Context context, int color) {
        super(context);
        this.color = color;
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        reset();
    }

    private void reset() {
        java.util.Arrays.fill(levels, 0f);
        floorDb = Float.NaN;  // set by the first reading, so each recording starts from its own room
    }

    /** One microphone reading: the RMS of the last 50 ms, 0 to 1. */
    void push(float rms) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1);
        levels[BARS - 1] = level(rms);
        invalidate();
    }

    /** 0 (silence) to 1 (as loud as you've been lately). Visible for tests. */
    float level(float rms) {
        float db = Math.max(-80f, 20f * (float) Math.log10(Math.max(rms, 1e-5f)));
        if (Float.isNaN(floorDb)) { floorDb = db; peakDb = db + MIN_RANGE_DB; }
        // The noise floor drops to any quieter reading at once and creeps up slowly,
        // so speech doesn't drag it up but a noisier room eventually does.
        floorDb = db < floorDb ? db : Math.min(floorDb + FLOOR_RISE_DB, db);
        floorDb = Math.max(-80f, Math.min(floorDb, -30f));
        // The peak jumps to any louder reading and eases down between words.
        peakDb = Math.max(db, peakDb - PEAK_FALL_DB);
        peakDb = Math.max(peakDb, floorDb + MIN_RANGE_DB);
        float bottom = floorDb + GATE_DB;
        float t = (db - bottom) / (peakDb - bottom);
        t = Math.max(0f, Math.min(1f, t));
        // A gentle curve so mid-loudness syllables read clearly.
        return (float) Math.pow(t, 0.75);
    }

    void listen() {
        thinking = false;
        reset();
        invalidate();
    }

    void think() {
        thinking = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float step = w / BARS, bar = step * 0.55f, mid = h / 2f;
        paint.setStrokeWidth(bar);
        double t = SystemClock.uptimeMillis() / 180.0;
        int alpha = (color >>> 24) == 0 ? 255 : (color >>> 24);
        for (int i = 0; i < BARS; i++) {
            float level = thinking ? 0.3f + 0.25f * (float) Math.sin(t - i * 0.55) : levels[i];
            // Silence draws as a small dot; speech as a bar up to the full height.
            float half = Math.max(0f, level * (h - bar)) / 2f;
            float x = step * i + step / 2f;
            // Older bars (to the left) fade a little, so the newest sound stands out.
            paint.setAlpha(Math.round(alpha * (thinking ? 1f : 0.45f + 0.55f * (i + 1) / BARS)));
            canvas.drawLine(x, mid - half, x, mid + half, paint);
        }
        if (thinking) postInvalidateOnAnimation();
    }
}
