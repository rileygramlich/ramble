package dev.rileygramlich.yap;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Ramble's main screen: whether it's ready (with the two setup steps until it is),
 * a box to try it in, and the history of what you've said. Everything else is
 * in Settings.
 */
public class SetupActivity extends Activity {
    private Button micStep, bubbleStep;
    private LinearLayout history;
    private final SharedPreferences.OnSharedPreferenceChangeListener historyChanged = (sp, key) -> showHistory();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_setup);
        micStep = findViewById(R.id.step_mic);
        bubbleStep = findViewById(R.id.step_bubble);
        history = findViewById(R.id.history);

        micStep.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        bubbleStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.open_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.clear_history).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Clear your whole history?")
                .setPositiveButton("Clear", (d, w) -> History.clear(this))
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        showHistory();
        // Dictations made in the box above (or anywhere) appear as they happen.
        getSharedPreferences("yap_history", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(historyChanged);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences("yap_history", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(historyChanged);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refresh();
    }

    private void refresh() {
        boolean mic = hasMic(this), bubble = isBubbleOn(this), keyboard = isKeyboardCurrent(this);
        mark(micStep, mic, "Allow the microphone");
        mark(bubbleStep, bubble, "Turn on the Ramble bubble");
        boolean ready = mic && (bubble || keyboard);
        findViewById(R.id.setup).setVisibility(ready ? View.GONE : View.VISIBLE);
        TextView status = findViewById(R.id.ready);
        status.setText(!ready
                ? "Two quick steps, once."
                : bubble
                ? "Ready. Open any keyboard, tap the little mic above it, talk, and tap ✓."
                : "Ready. Hold the mic on the Ramble keyboard and talk.");
    }

    // -- history -----------------------------------------------------------------
    private void showHistory() {
        history.removeAllViews();
        long now = System.currentTimeMillis();
        for (History.Entry entry : History.all(this)) history.addView(row(entry, now));
        boolean empty = history.getChildCount() == 0;
        findViewById(R.id.history_empty).setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.clear_history).setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** One dictation: tap to copy it, hold to delete it. */
    private View row(History.Entry entry, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spacing.topMargin = dp(8);
        row.setLayoutParams(spacing);

        TextView text = new TextView(this);
        text.setText(entry.text);
        text.setTextColor(getColor(R.color.ink));
        text.setTextSize(16);
        text.setLineSpacing(0, 1.15f);
        text.setMaxLines(4);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text);

        TextView when = new TextView(this);
        when.setText(DateUtils.getRelativeTimeSpanString(entry.at, now, DateUtils.MINUTE_IN_MILLIS));
        when.setTextColor(getColor(R.color.ink_soft));
        when.setTextSize(12);
        when.setPadding(0, dp(6), 0, 0);
        row.addView(when);

        row.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Ramble", entry.text));
            // Android 13+ shows its own "Copied" confirmation.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage("Delete this from your history?")
                    .setPositiveButton("Delete", (d, w) -> History.remove(this, entry.at))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        return row;
    }

    // -- setup state, shared with Settings ---------------------------------------
    static void mark(Button step, boolean done, String label) {
        step.setText(done ? "✓   " + label : label);
        step.setTextColor(step.getContext().getColor(done ? R.color.accent : R.color.ink));
        step.setAlpha(done ? 0.6f : 1f);
    }

    static boolean hasMic(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isBubbleOn(Context context) {
        String on = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new ComponentName(context, YapBubble.class).flattenToString());
    }

    static boolean isKeyboardEnabled(Context context) {
        for (InputMethodInfo info : context.getSystemService(InputMethodManager.class).getEnabledInputMethodList()) {
            if (info.getPackageName().equals(context.getPackageName())) return true;
        }
        return false;
    }

    static boolean isKeyboardCurrent(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id != null && id.startsWith(context.getPackageName() + "/");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
