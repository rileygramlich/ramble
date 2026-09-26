package dev.rileygramlich.yap;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** whisper.cpp on the phone. One model, loaded once and shared. */
final class Whisper {
    static final int SAMPLE_RATE = 16_000;
    private static final String MODEL = "ggml-base.en-q5_1.bin";

    static {
        System.loadLibrary("yapwhisper");
    }

    private static native long load(String path);
    private static native void free(long handle);
    private static native String transcribe(long handle, float[] audio, int threads, String prompt);

    private static long handle;

    private Whisper() {}

    /** Loads the model the first time; slow (a second or so), so call it off the main thread. */
    static synchronized void ensureLoaded(Context context) throws Exception {
        if (handle != 0) return;
        File file = new File(context.getFilesDir(), MODEL);
        if (!file.exists()) copyAsset(context, file);
        handle = load(file.getAbsolutePath());
        if (handle == 0) throw new IllegalStateException("Couldn't load the speech model");
    }

    static synchronized String run(Context context, float[] audio, String vocabulary) throws Exception {
        ensureLoaded(context);
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String prompt = vocabulary == null || vocabulary.trim().isEmpty() ? null : vocabulary.trim() + ".";
        return transcribe(handle, audio, threads, prompt).trim();
    }

    private static void copyAsset(Context context, File dest) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        try (InputStream in = context.getAssets().open(MODEL); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IllegalStateException("Couldn't unpack the speech model");
    }
}
