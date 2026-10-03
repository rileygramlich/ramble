package dev.rileygramlich.yap;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Ramble keyboard: a big mic button plus the few keys you need around
 * dictation. Hold the mic to talk and let go to type it; tap it to talk
 * hands-free and tap again to finish.
 */
public class YapKeyboard extends InputMethodService {
    private static final long TAP_MS = 300;
    private static final long REPEAT_DELAY_MS = 400, REPEAT_EVERY_MS = 60;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recorder recorder = new Recorder();

    private TextView status;
    private View mic;
    private long pressedAt;
    private boolean handsFree, busy;

    @Override
    public void onCreate() {
        super.onCreate();
        worker.execute(() -> {
            try {
                Whisper.ensureLoaded(this); // so the first dictation isn't the slow one
            } catch (Exception e) {
                main.post(() -> say("Couldn't load the speech model: " + e.getMessage()));
            }
        });
    }

    @Override
    public View onCreateInputView() {
        View root = getLayoutInflater().inflate(R.layout.keyboard, null);
        status = root.findViewById(R.id.status);
        mic = root.findViewById(R.id.mic);
        mic.setOnTouchListener(this::onMicTouch);
        root.findViewById(R.id.space).setOnClickListener(v -> commit(" "));
        root.findViewById(R.id.enter).setOnClickListener(v -> enter());
        View globe = root.findViewById(R.id.globe);
        globe.setOnClickListener(v -> { if (!switchToPreviousInputMethod()) picker(); });
        globe.setOnLongClickListener(v -> { picker(); return true; });
        repeat(root.findViewById(R.id.backspace), () -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL));
        idle();
        return root;
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (recorder.isRecording()) { // keyboard hidden mid-dictation: drop it quietly
            recorder.stop();
            handsFree = false;
            showLevel(0);
        }
    }

    // -- mic ---------------------------------------------------------------------
    private boolean onMicTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                if (busy) return true;
                pressedAt = SystemClock.uptimeMillis();
                if (handsFree) {
                    handsFree = false;
                    finish();
                } else {
                    begin();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                if (!recorder.isRecording() || handsFree) return true;
                if (SystemClock.uptimeMillis() - pressedAt < TAP_MS) {
                    handsFree = true;
                    say("Listening… tap to finish");
                } else {
                    finish();
                }
                return true;
        }
        return false;
    }

    private void begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Ramble needs the microphone. Opening setup…");
            startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        try {
            recorder.start(rms -> main.post(() -> showLevel(rms)));
        } catch (Exception e) {
            say(e.getMessage());
            return;
        }
        mic.setActivated(true);
        say("Listening…");
        Dictation.warm(this);
    }

    private void finish() {
        float[] audio = recorder.stop();
        mic.setActivated(false);
        showLevel(0);
        if (!Recorder.isSpeech(audio)) {
            idle();
            return;
        }
        busy = true;
        say("Writing…");
        worker.execute(() -> {
            Dictation.Result said;
            try {
                said = Dictation.run(this, audio);
            } catch (Exception e) {
                main.post(() -> { busy = false; say("That didn't work: " + e.getMessage()); });
                return;
            }
            main.post(() -> {
                busy = false;
                History.add(this, said.text);
                // Before Enter/Send, no trailing space: it would end up in the message.
                if (!said.text.isEmpty()) insert(said.text + (said.action == null ? " " : ""));
                if (said.action != null) enter();
                idle();
            });
        });
    }

    // -- typing ------------------------------------------------------------------
    private void insert(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        boolean needsSpace = before != null && before.length() > 0 && !Character.isWhitespace(before.charAt(0));
        ic.commitText((needsSpace ? " " : "") + text, 1);
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null) return;
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    private void picker() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }

    /** A key that repeats while held, like backspace on any keyboard. */
    private void repeat(View key, Runnable action) {
        Runnable[] loop = new Runnable[1];
        loop[0] = () -> { action.run(); main.postDelayed(loop[0], REPEAT_EVERY_MS); };
        key.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    action.run();
                    main.postDelayed(loop[0], REPEAT_DELAY_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    main.removeCallbacks(loop[0]);
                    return true;
            }
            return false;
        });
    }

    // -- feedback ----------------------------------------------------------------
    private void idle() { say("Hold to talk · tap for hands-free"); }

    private void say(String message) {
        if (status != null) status.setText(message);
    }

    private void showLevel(float rms) {
        if (mic == null) return;
        float scale = 1f + Math.min(0.18f, rms * 4f);
        mic.animate().scaleX(scale).scaleY(scale).setDuration(80).start();
    }

    @Override
    public void onDestroy() {
        if (recorder.isRecording()) recorder.stop();
        worker.shutdown();
        super.onDestroy();
    }
}
