package dev.rileygramlich.yap;

import android.content.Context;
import android.content.SharedPreferences;

/** The keyboard's few settings, edited in SetupActivity. */
final class Prefs {
    static final String DEFAULT_MODEL = "qwen2.5:1.5b";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("yap", Context.MODE_PRIVATE);
    }

    /** Your own Ollama server, e.g. over Tailscale. Blank = rules-only tidy-up on the phone. */
    String ollamaUrl() { return sp.getString("ollama_url", ""); }
    String ollamaModel() { return sp.getString("ollama_model", DEFAULT_MODEL); }
    /** Names and jargon, comma separated, that Whisper should expect. */
    String vocabulary() { return sp.getString("vocabulary", ""); }

    void save(String url, String model, String vocabulary) {
        sp.edit()
                .putString("ollama_url", url.trim())
                .putString("ollama_model", model.trim().isEmpty() ? DEFAULT_MODEL : model.trim())
                .putString("vocabulary", vocabulary.trim())
                .apply();
    }
}
