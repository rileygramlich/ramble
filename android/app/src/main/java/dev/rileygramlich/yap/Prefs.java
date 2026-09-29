package dev.rileygramlich.yap;

import android.content.Context;
import android.content.SharedPreferences;

/** Yap's few settings, edited in SetupActivity. */
final class Prefs {
    static final String DEFAULT_MODEL = "qwen2.5:1.5b";
    /** Ollama on Art, the home machine, over Tailscale. */
    static final String DEFAULT_URL = "http://100.112.5.58:11434";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("yap", Context.MODE_PRIVATE);
    }

    /** Your own Ollama server, over Tailscale. Blank = rules-only tidy-up on the phone. */
    String ollamaUrl() { return sp.getString("ollama_url", DEFAULT_URL); }
    String ollamaModel() { return sp.getString("ollama_model", DEFAULT_MODEL); }
    /** Names and jargon, comma separated, that Whisper should expect. */
    String vocabulary() { return sp.getString("vocabulary", ""); }

    /** Where you last dragged the bubble. */
    boolean bubbleLeft() { return sp.getBoolean("bubble_left", false); }
    int bubbleY() { return sp.getInt("bubble_y", -1); }

    void saveBubble(boolean left, int y) {
        sp.edit().putBoolean("bubble_left", left).putInt("bubble_y", y).apply();
    }

    void save(String url, String model, String vocabulary) {
        sp.edit()
                .putString("ollama_url", url.trim())
                .putString("ollama_model", model.trim().isEmpty() ? DEFAULT_MODEL : model.trim())
                .putString("vocabulary", vocabulary.trim())
                .apply();
    }
}
