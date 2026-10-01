package dev.rileygramlich.yap;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A short on-phone log of what the bubble did with each dictation (which text box
 * it found, how it typed, whether the text stuck), shown in Settings → Diagnostics
 * so a problem can be reported without a cable or adb. Never stores what you said.
 */
final class Diagnostics {
    private static final String TAG = "Yap";
    private static final int KEEP = 60;

    private Diagnostics() {}

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("yap_diag", Context.MODE_PRIVATE);
    }

    static synchronized void log(Context context, String message) {
        Log.i(TAG, message);
        String stamp = new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date());
        String[] lines = read(context).isEmpty() ? new String[0] : read(context).split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - (KEEP - 1)); i < lines.length; i++) out.append(lines[i]).append('\n');
        out.append(stamp).append("  ").append(message);
        store(context).edit().putString("log", out.toString()).apply();
    }

    static String read(Context context) {
        return store(context).getString("log", "");
    }

    static void clear(Context context) {
        store(context).edit().remove("log").apply();
    }
}
