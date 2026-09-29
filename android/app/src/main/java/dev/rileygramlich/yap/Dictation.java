package dev.rileygramlich.yap;

import android.content.Context;

import java.net.HttpURLConnection;
import java.net.URL;

/** Audio in, tidy text out. Shared by the keyboard and the bubble. */
final class Dictation {
    private Dictation() {}

    /** Whisper, then the tidy-up. Slow: call it off the main thread. */
    static String run(Context context, float[] audio) throws Exception {
        Prefs prefs = new Prefs(context);
        String raw = Whisper.run(context, audio, prefs.vocabulary());
        return Polish.run(raw, prefs.ollamaUrl(), prefs.ollamaModel(), prefs.vocabulary(), 4000);
    }

    /** Wake the tidy-up model while the person is still talking. */
    static void warm(Context context) {
        Prefs prefs = new Prefs(context);
        String url = prefs.ollamaUrl();
        if (url.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url.replaceAll("/+$", "") + "/api/generate").openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(20000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(("{\"model\":\"" + prefs.ollamaModel() + "\",\"keep_alive\":\"30m\"}").getBytes());
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {
            }
        }, "yap-warm").start();
    }
}
