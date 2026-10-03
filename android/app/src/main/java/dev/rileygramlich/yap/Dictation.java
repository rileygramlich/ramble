package dev.rileygramlich.yap;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Audio in, tidy text out. Shared by the keyboard and the bubble.
 *
 * Your computer first, if you've set one up: the audio goes over Tailscale to
 * `ramble serve`, which runs a much bigger Whisper than a phone can and the tidy-up
 * next to it. If it doesn't answer, the phone does it all itself, and doesn't try the
 * computer again for a minute
 * so you're not kept waiting on every dictation while you're away from home.
 */
final class Dictation {
    private static final int CONNECT_MS = 1500, RETRY_SERVER_MS = 60_000;
    private static volatile long skipServerUntil;

    private Dictation() {}

    /** Tidy text, plus a spoken command to carry out after typing it. */
    static final class Result {
        final String text;
        /** "enter", "send", or null. */
        final String action;

        Result(String text, String action) {
            this.text = text;
            this.action = action;
        }
    }

    /** Slow: call it off the main thread. */
    static Result run(Context context, float[] audio) throws Exception {
        Prefs prefs = new Prefs(context);
        String server = prefs.speechUrl();
        if (!server.isEmpty() && SystemClock.elapsedRealtime() >= skipServerUntil) {
            try {
                return onServer(server, audio, prefs.vocabulary());
            } catch (Exception e) {
                skipServerUntil = SystemClock.elapsedRealtime() + RETRY_SERVER_MS;
            }
        }
        String[] spoken = Cleanup.command(Whisper.run(context, audio, prefs.vocabulary()));
        String text = spoken[0].isEmpty() ? "" : Polish.run(spoken[0], prefs.ollamaUrl(), prefs.ollamaModel(), prefs.vocabulary(), 4000);
        return new Result(text, spoken[1]);
    }

    private static Result onServer(String url, float[] audio, String vocabulary) throws Exception {
        ByteBuffer pcm = ByteBuffer.allocate(audio.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : audio) pcm.putShort((short) Math.max(-32768, Math.min(32767, Math.round(s * 32767))));

        HttpURLConnection c = (HttpURLConnection) new URL(url.replaceAll("/+$", "") + "/dictate").openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(15_000 + audio.length / Whisper.SAMPLE_RATE * 1000); // longer clips take longer
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(pcm.capacity());
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("X-Yap-Vocabulary", URLEncoder.encode(vocabulary, "UTF-8"));
            c.getOutputStream().write(pcm.array());
            if (c.getResponseCode() != 200) throw new IllegalStateException("The speech server said " + c.getResponseCode());
            JSONObject reply = new JSONObject(readAll(c.getInputStream()));
            return new Result(reply.getString("text"), reply.isNull("action") ? null : reply.optString("action", null));
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** Wake the phone's own tidy-up server, if one is set, while the person is still talking. */
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
        }, "ramble-warm").start();
    }
}
