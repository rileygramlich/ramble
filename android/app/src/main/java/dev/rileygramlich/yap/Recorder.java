package dev.rileygramlich.yap;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Arrays;

/** Microphone capture at 16 kHz mono float, the format whisper wants. */
final class Recorder {
    interface Level { void onLevel(float rms); }

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private float[] samples = new float[Whisper.SAMPLE_RATE * 30];
    private int size;

    boolean isRecording() { return running; }

    @SuppressLint("MissingPermission") // checked by the keyboard before calling
    void start(Level level) {
        int min = AudioRecord.getMinBufferSize(Whisper.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Whisper.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, Math.max(min, Whisper.SAMPLE_RATE / 5 * 4));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            throw new IllegalStateException("The microphone is busy or not allowed");
        }
        size = 0;
        running = true;
        record.startRecording();
        thread = new Thread(() -> {
            float[] chunk = new float[Whisper.SAMPLE_RATE / 10];
            while (running) {
                int n = record.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) continue;
                synchronized (this) {
                    if (size + n > samples.length) samples = Arrays.copyOf(samples, samples.length * 2);
                    System.arraycopy(chunk, 0, samples, size, n);
                    size += n;
                }
                double sum = 0;
                for (int i = 0; i < n; i++) sum += chunk[i] * chunk[i];
                level.onLevel((float) Math.sqrt(sum / n));
            }
        }, "yap-mic");
        thread.start();
    }

    float[] stop() {
        running = false;
        try {
            if (thread != null) thread.join(500);
        } catch (InterruptedException ignored) {
        }
        if (record != null) {
            record.stop();
            record.release();
            record = null;
        }
        synchronized (this) {
            return Arrays.copyOf(samples, size);
        }
    }

    /** Too short or too quiet to be words. Whisper invents "Thank you." from silence. */
    static boolean isSpeech(float[] audio) {
        if (audio.length < Whisper.SAMPLE_RATE * 0.3) return false;
        double sum = 0;
        for (float s : audio) sum += s * s;
        return Math.sqrt(sum / audio.length) > 0.003;
    }
}
