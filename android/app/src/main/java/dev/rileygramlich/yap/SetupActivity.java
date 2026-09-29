package dev.rileygramlich.yap;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** First-run setup and settings: the steps to turn Yap on (bubble or keyboard), plus tidy-up options. */
public class SetupActivity extends Activity {
    private Button micStep, bubbleStep, enableStep, switchStep;
    private EditText url, model, vocabulary;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_setup);
        prefs = new Prefs(this);
        micStep = findViewById(R.id.step_mic);
        bubbleStep = findViewById(R.id.step_bubble);
        enableStep = findViewById(R.id.step_enable);
        switchStep = findViewById(R.id.step_switch);
        url = findViewById(R.id.ollama_url);
        model = findViewById(R.id.ollama_model);
        vocabulary = findViewById(R.id.vocabulary);

        micStep.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        bubbleStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        enableStep.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        switchStep.setOnClickListener(v -> getSystemService(InputMethodManager.class).showInputMethodPicker());

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
        prefs.save(url.getText().toString(), model.getText().toString(), vocabulary.getText().toString());
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refresh();
    }

    private void refresh() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean bubble = isBubbleOn();
        boolean enabled = isEnabled();
        boolean current = isCurrent();
        mark(micStep, mic, "1. Allow the microphone");
        mark(bubbleStep, bubble, "2. Turn on the Yap bubble");
        mark(enableStep, enabled, "Turn on the Yap keyboard");
        mark(switchStep, current, "Switch to Yap");
        TextView ready = findViewById(R.id.ready);
        ready.setText(!mic || !(bubble || current)
                ? "Two quick steps, once."
                : bubble
                ? "All set. Tap the box below, tap the floating mic, talk, and tap it again."
                : "All set. Tap the box below, hold the mic, and talk.");
    }

    private void mark(Button step, boolean done, String label) {
        step.setText(done ? "✓  " + label : label);
        step.setAlpha(done ? 0.55f : 1f);
    }

    private boolean isBubbleOn() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new ComponentName(this, YapBubble.class).flattenToString());
    }

    private boolean isEnabled() {
        for (InputMethodInfo info : getSystemService(InputMethodManager.class).getEnabledInputMethodList()) {
            if (info.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private boolean isCurrent() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id != null && id.startsWith(getPackageName() + "/");
    }
}
