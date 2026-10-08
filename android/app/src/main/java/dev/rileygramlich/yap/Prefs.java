package dev.rileygramlich.yap;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ramble's few settings, edited in SetupActivity. */
final class Prefs {
    static final String DEFAULT_MODEL = "qwen2.5:1.5b";
    /** Blank by default: everything happens on the phone until you point it at `ramble serve`. */
    static final String DEFAULT_SPEECH_URL = "";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("yap", Context.MODE_PRIVATE);
    }

    /** Where to send audio first. Blank = always transcribe on the phone. */
    String speechUrl() { return sp.getString("speech_url", DEFAULT_SPEECH_URL); }
    /** Only used when the computer's speech server can't be reached. Blank = the phone's rules. */
    String ollamaUrl() { return sp.getString("ollama_url", ""); }
    String ollamaModel() { return sp.getString("ollama_model", DEFAULT_MODEL); }
    /** "haha period" → "haha." */
    boolean spokenPunctuation() { return sp.getBoolean("spoken_punctuation", true); }

    /**
     * Your dictionary, in the order you added it. Each entry is {say, type}: type is ""
     * for a word to spell right, else what to type when you say it.
     */
    List<String[]> dictionary() {
        List<String[]> out = new ArrayList<>();
        String json = sp.getString("dictionary", null);
        if (json == null) { // from before the list: words separated by commas
            for (String word : sp.getString("vocabulary", "").split(","))
                if (!word.trim().isEmpty()) out.add(new String[]{word.trim(), ""});
            return out;
        }
        try {
            JSONArray entries = new JSONArray(json);
            for (int i = 0; i < entries.length(); i++) {
                JSONArray e = entries.getJSONArray(i);
                out.add(new String[]{e.getString(0), e.optString(1, "")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Names and jargon, comma separated, that Whisper should expect: your words, and words you type instead. */
    String vocabulary() {
        List<String> words = new ArrayList<>();
        for (String[] e : dictionary()) {
            if (e[1].isEmpty()) words.add(e[0]);
            else if (e[1].matches(".*\\p{L}.*")) words.add(e[1]);
        }
        return String.join(", ", words);
    }

    /** Say this → type that. */
    Map<String, String> replacements() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] e : dictionary()) if (!e[1].isEmpty()) out.put(e[0], e[1]);
        return out;
    }

    void saveDictionary(List<String[]> entries) {
        JSONArray out = new JSONArray();
        for (String[] e : entries) out.put(new JSONArray().put(e[0]).put(e[1]));
        sp.edit().putString("dictionary", out.toString()).apply();
    }

    /** Where you last dragged the bubble: which edge, and how far (px) above the keyboard. */
    boolean bubbleLeft() { return sp.getBoolean("bubble_left", false); }
    int bubbleLift() { return sp.getInt("bubble_lift", -1); }

    void saveBubble(boolean left, int lift) {
        sp.edit().putBoolean("bubble_left", left).putInt("bubble_lift", lift).apply();
    }

    void save(String speech, String url, String model, boolean spokenPunctuation) {
        sp.edit()
                .putString("speech_url", speech.trim())
                .putString("ollama_url", url.trim())
                .putString("ollama_model", model.trim().isEmpty() ? DEFAULT_MODEL : model.trim())
                .putBoolean("spoken_punctuation", spokenPunctuation)
                .apply();
    }
}
