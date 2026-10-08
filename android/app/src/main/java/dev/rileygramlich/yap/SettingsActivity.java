package dev.rileygramlich.yap;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static dev.rileygramlich.yap.SetupActivity.mark;

/** The steps to turn Ramble on (bubble or keyboard), plus where speech and tidy-up happen. */
public class SettingsActivity extends Activity {
    private Button micStep, bubbleStep, enableStep, switchStep;
    private EditText speech, url, model, say, type;
    private Switch punctuation;
    private LinearLayout dictionaryList;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        micStep = findViewById(R.id.step_mic);
        bubbleStep = findViewById(R.id.step_bubble);
        enableStep = findViewById(R.id.step_enable);
        switchStep = findViewById(R.id.step_switch);
        speech = findViewById(R.id.speech_url);
        url = findViewById(R.id.ollama_url);
        model = findViewById(R.id.ollama_model);
        say = findViewById(R.id.dictionary_say);
        type = findViewById(R.id.dictionary_type);
        dictionaryList = findViewById(R.id.dictionary_list);
        punctuation = findViewById(R.id.spoken_punctuation);
        findViewById(R.id.dictionary_add).setOnClickListener(v -> addWord());
        type.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            addWord();
            return true;
        });

        findViewById(R.id.back).setOnClickListener(v -> finish());
        micStep.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        bubbleStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        enableStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        switchStep.setOnClickListener(v -> getSystemService(InputMethodManager.class).showInputMethodPicker());

        findViewById(R.id.diag_copy).setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Ramble report", report()));
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.diag_clear).setOnClickListener(v -> { Diagnostics.clear(this); showDiagnostics(); });

        speech.setText(prefs.speechUrl());
        url.setText(prefs.ollamaUrl());
        model.setText(prefs.ollamaModel());
        punctuation.setChecked(prefs.spokenPunctuation());
        showDictionary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh(); // the keyboard picker is a dialog, not a new screen
    }

    @Override
    protected void onPause() {
        super.onPause();
        addWord(); // something typed but not added yet shouldn't be lost
        prefs.save(speech.getText().toString(), url.getText().toString(), model.getText().toString(), punctuation.isChecked());
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refresh();
    }

    /** Adds what's in the two boxes, replacing an entry for the same word. */
    private void addWord() {
        String said = say.getText().toString().trim(), typed = type.getText().toString().trim();
        if (said.isEmpty()) return;
        List<String[]> entries = prefs.dictionary();
        entries.removeIf(e -> e[0].equalsIgnoreCase(said));
        entries.add(new String[]{said, typed});
        prefs.saveDictionary(entries);
        say.setText("");
        type.setText("");
        say.requestFocus();
        showDictionary();
    }

    private void showDictionary() {
        dictionaryList.removeAllViews();
        List<String[]> entries = prefs.dictionary();
        for (String[] entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView word = new TextView(this);
            word.setText(entry[1].isEmpty() ? entry[0] : entry[0] + "  →  " + entry[1]);
            word.setTextSize(16);
            word.setTextColor(getColor(R.color.ink));
            row.addView(word, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView remove = new TextView(this);
            remove.setText("✕");
            remove.setTextSize(16);
            remove.setGravity(Gravity.CENTER);
            remove.setTextColor(getColor(R.color.ink_soft));
            remove.setContentDescription("Remove " + entry[0]);
            remove.setBackgroundResource(android.R.drawable.list_selector_background);
            remove.setOnClickListener(v -> {
                List<String[]> now = prefs.dictionary();
                now.removeIf(e -> e[0].equals(entry[0]));
                prefs.saveDictionary(now);
                showDictionary();
            });
            int size = Math.round(44 * getResources().getDisplayMetrics().density);
            row.addView(remove, new LinearLayout.LayoutParams(size, size));
            dictionaryList.addView(row);
        }
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nothing yet.");
            empty.setTextColor(getColor(R.color.ink_soft));
            empty.setPadding(0, 8, 0, 0);
            dictionaryList.addView(empty);
        }
    }

    private String deviceInfo() {
        String version = "?";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        return "Ramble " + version + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " · bubble " + (SetupActivity.isBubbleOn(this) ? "on" : "off")
                + " · keyboard " + (SetupActivity.isKeyboardCurrent(this) ? "Ramble" : "other");
    }

    private String report() {
        String log = Diagnostics.read(this);
        return deviceInfo() + "\n" + (log.isEmpty() ? "(no dictations logged yet)" : log);
    }

    private void showDiagnostics() {
        ((TextView) findViewById(R.id.diag_info)).setText(deviceInfo());
        String log = Diagnostics.read(this);
        ((TextView) findViewById(R.id.diag_log)).setText(log.isEmpty() ? "Nothing yet. Dictate something with the bubble, then come back." : log);
    }

    private void refresh() {
        showDiagnostics();
        mark(micStep, SetupActivity.hasMic(this), "Allow the microphone");
        mark(bubbleStep, SetupActivity.isBubbleOn(this), "Turn on the Ramble bubble");
        mark(enableStep, SetupActivity.isKeyboardEnabled(this), "Turn on the Ramble keyboard");
        mark(switchStep, SetupActivity.isKeyboardCurrent(this), "Switch to Ramble");
    }
}
