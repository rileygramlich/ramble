package dev.rileygramlich.yap;

import android.content.Context;
import android.content.SharedPreferences;

/** Yap's few settings, edited in SetupActivity. */
final class Prefs {
    static final String DEFAULT_MODEL = "qwen2.5:1.5b";
    /** `yap serve` on Art, the home machine, over Tailscale: speech and tidy-up in one go. */
    static final String DEFAULT_SPEECH_URL = "http://100.112.5.58:8723";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("yap", Context.MODE_PRIVATE);
    }

    /** Where to send audio first. Blank = always transcribe on the phone. */
    String speechUrl() { return sp.getString("speech_url", DEFAULT_SPEECH_URL); }
    /** Only used when Art's speech server can't be reached. Blank = the phone's rules. */
    String ollamaUrl() { return sp.getString("ollama_url", ""); }
    String ollamaModel() { return sp.getString("ollama_model", DEFAULT_MODEL); }
    /** Names and jargon, comma separated, that Whisper should expect. */
    String vocabulary() { return sp.getString("vocabulary", ""); }

    /** Where you last dragged the bubble. */
    boolean bubbleLeft() { return sp.getBoolean("bubble_left", false); }
    int bubbleY() { return sp.getInt("bubble_y", -1); }

    void saveBubble(boolean left, int y) {
        sp.edit().putBoolean("bubble_left", left).putInt("bubble_y", y).apply();
    }

    void save(String speech, String url, String model, String vocabulary) {
        sp.edit()
                .putString("speech_url", speech.trim())
                .putString("ollama_url", url.trim())
                .putString("ollama_model", model.trim().isEmpty() ? DEFAULT_MODEL : model.trim())
                .putString("vocabulary", vocabulary.trim())
                .apply();
    }
}
