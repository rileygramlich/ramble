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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import static dev.rileygramlich.yap.SetupActivity.mark;

/** The steps to turn Yap on (bubble or keyboard), plus where speech and tidy-up happen. */
public class SettingsActivity extends Activity {
    private Button micStep, bubbleStep, enableStep, switchStep;
    private EditText speech, url, model, vocabulary;
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
        vocabulary = findViewById(R.id.vocabulary);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        micStep.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        bubbleStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        enableStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        switchStep.setOnClickListener(v -> getSystemService(InputMethodManager.class).showInputMethodPicker());

        findViewById(R.id.diag_copy).setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Yap report", report()));
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.diag_clear).setOnClickListener(v -> { Diagnostics.clear(this); showDiagnostics(); });

        speech.setText(prefs.speechUrl());
        url.setText(prefs.ollamaUrl());
        model.setText(prefs.ollamaModel());
        vocabulary.setText(prefs.vocabulary());
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
        prefs.save(speech.getText().toString(), url.getText().toString(), model.getText().toString(), vocabulary.getText().toString());
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refresh();
    }

    private String deviceInfo() {
        String version = "?";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        return "Yap " + version + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " · bubble " + (SetupActivity.isBubbleOn(this) ? "on" : "off")
                + " · keyboard " + (SetupActivity.isKeyboardCurrent(this) ? "Yap" : "other");
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
        mark(bubbleStep, SetupActivity.isBubbleOn(this), "Turn on the Yap bubble");
        mark(enableStep, SetupActivity.isKeyboardEnabled(this), "Turn on the Yap keyboard");
        mark(switchStep, SetupActivity.isKeyboardCurrent(this), "Switch to Yap");
    }
}
