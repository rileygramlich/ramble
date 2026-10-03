package dev.rileygramlich.yap;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The last 100 things you said, newest first, from the bubble and the keyboard.
 * Kept only on the phone, and shown on Ramble's main screen.
 */
final class History {
    static final int LIMIT = 100;

    static final class Entry {
        final String text;
        final long at;

        Entry(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    private History() {}

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("yap_history", Context.MODE_PRIVATE);
    }

    static synchronized List<Entry> all(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(store(context).getString("entries", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject e = saved.getJSONObject(i);
                entries.add(new Entry(e.getString("text"), e.getLong("at")));
            }
        } catch (Exception ignored) {
            // A damaged history isn't worth crashing over; start again.
        }
        return entries;
    }

    static synchronized void add(Context context, String text) {
        String said = text.trim();
        if (said.isEmpty()) return;
        List<Entry> entries = all(context);
        entries.add(0, new Entry(said, System.currentTimeMillis()));
        save(context, entries.subList(0, Math.min(entries.size(), LIMIT)));
    }

    static synchronized void remove(Context context, long at) {
        List<Entry> entries = all(context);
        entries.removeIf(e -> e.at == at);
        save(context, entries);
    }

    static synchronized void clear(Context context) {
        store(context).edit().remove("entries").apply();
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray out = new JSONArray();
        try {
            for (Entry e : entries) out.put(new JSONObject().put("text", e.text).put("at", e.at));
        } catch (Exception ignored) {
        }
        store(context).edit().putString("entries", out.toString()).apply();
    }
}
