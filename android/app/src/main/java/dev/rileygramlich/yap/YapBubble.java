package dev.rileygramlich.yap;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.SurroundingText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The Ramble bubble: a small light-blue mic over every app, like Wispr Flow's, so
 * you keep your normal keyboard. Like Wispr Flow's, it only appears while a
 * keyboard is open, resting see-through at the screen edge just above it.
 * Tap it and it opens into a small see-through pill with a live waveform:
 * ✓ (or a tap on the waveform) types what you said, ✕ throws it away. Or hold
 * the mic and let go. The text lands in whichever box has the cursor. Drag it
 * to change sides, or how high above the keyboard it sits.
 *
 * It's an accessibility service because that's what's allowed to draw over
 * other apps and put text into their fields.
 */
public class YapBubble extends AccessibilityService {
    private static final String TAG = "Ramble";
    private static final long TAP_MS = 300;
    /** How long to wait before re-reading the box to see if the text stuck. */
    private static final long VERIFY_MS = 350;
    private static final int SIZE_DP = 40, PILL_DP = 156, BUTTON_DP = 28, EDGE_DP = 4;
    /** See-through at rest on the edge, a little less so while it's working. */
    private static final float IDLE_ALPHA = 0.45f, ACTIVE_ALPHA = 0.8f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recorder recorder = new Recorder();
    private final Runnable openPill = () -> { if (recorder.isRecording() && !this.dragging) mode(Mode.LISTENING); };
    private final Runnable check = this::refresh;

    private WindowManager windows;
    private WindowManager.LayoutParams layout;
    private LinearLayout bubble;
    private ImageView mic, cancelButton, doneButton;
    private Waveform wave;
    private Prefs prefs;
    private boolean shown, handsFree, busy, dragging, left;
    private long pressedAt;
    private float downX, downY;
    private int startX, startY, slop;
    /** Top of the open keyboard on screen, and how far above it the bubble rests. */
    private int keyboardTop = -1, lift;

    @Override
    protected void onServiceConnected() {
        windows = getSystemService(WindowManager.class);
        prefs = new Prefs(this);
        slop = ViewConfiguration.get(this).getScaledTouchSlop() * 2;

        int ink = getColor(R.color.bubble_ink);
        bubble = new LinearLayout(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundResource(R.drawable.bubble);
        bubble.setPadding(dp(6), 0, dp(6), 0);
        bubble.setOnTouchListener(this::onTouch);
        if (Build.VERSION.SDK_INT >= 29) {
            // It sits in the edge strip where a swipe means Back; dragging it shouldn't go back.
            bubble.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                    v.setSystemGestureExclusionRects(java.util.List.of(new Rect(0, 0, r - l, b - t))));
        }

        mic = new ImageView(this);
        mic.setImageResource(R.drawable.ic_bubble_mic);
        mic.setContentDescription("Ramble: tap to talk");
        bubble.addView(mic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        cancelButton = button(R.drawable.ic_bubble_cancel, "Cancel");
        cancelButton.setOnClickListener(v -> cancel());
        bubble.addView(cancelButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        wave = new Waveform(this, ink);
        LinearLayout.LayoutParams waveSize = new LinearLayout.LayoutParams(0, dp(30), 1f);
        waveSize.setMargins(dp(6), 0, dp(6), 0);
        bubble.addView(wave, waveSize);

        doneButton = button(R.drawable.ic_bubble_done, "Done");
        doneButton.setOnClickListener(v -> { handsFree = false; finish(); });
        bubble.addView(doneButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        layout = new WindowManager.LayoutParams(dp(SIZE_DP), dp(SIZE_DP),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable, so the text box keeps the cursor and your keyboard stays up.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START;
        // Place it in plain screen coordinates, the same ones the keyboard's bounds use.
        // Otherwise Android shifts it down past the status bar and camera cutout.
        if (Build.VERSION.SDK_INT >= 30) {
            layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            layout.setFitInsetsTypes(0);
        } else if (Build.VERSION.SDK_INT >= 28) {
            layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        left = prefs.bubbleLeft();
        lift = prefs.bubbleLift() >= 0 ? prefs.bubbleLift() : dp(12);
        layout.y = getResources().getDisplayMetrics().heightPixels / 2;
        mode(Mode.IDLE);

        worker.execute(() -> {
            try {
                Whisper.ensureLoaded(this); // so the first dictation isn't the slow one
            } catch (Exception e) {
                main.post(() -> toast("Couldn't load the speech model: " + e.getMessage()));
            }
        });
        check.run();
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) listenForTests();
    }

    /**
     * Debug builds only: lets the emulator (which has no working mic) exercise the
     * typing step. adb shell am broadcast -a dev.rileygramlich.yap.TEST_INSERT --es text "hello"
     */
    private void listenForTests() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String text = intent.getStringExtra("text");
                if (text != null) main.post(() -> insert(text + " ", landed -> Log.i(TAG, "test insert landed=" + landed)));
            }
        };
        IntentFilter filter = new IntentFilter("dev.rileygramlich.yap.TEST_INSERT");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        main.removeCallbacks(check); // events come in bursts; look once they settle
        main.postDelayed(check, 150);
        main.postDelayed(check, 900);
    }

    @Override
    public void onInterrupt() {
        if (recorder.isRecording()) cancel();
    }

    /** Show the bubble only while a keyboard is open (or it's still working), resting just above it. */
    private void refresh() {
        Rect keyboard = keyboardBounds();
        if (keyboard != null) {
            keyboardTop = keyboard.top;
            if (!dragging) layout.y = clampY(keyboardTop - layout.height - lift);
        }
        boolean wasShown = shown;
        show(recorder.isRecording() || busy || keyboard != null);
        if (wasShown && shown && !dragging) windows.updateViewLayout(bubble, layout);
    }

    /**
     * Where the keyboard is, or null if none is open. Ramble's own keyboard counts as
     * none: it has its own big mic.
     */
    private Rect keyboardBounds() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && root.getPackageName() != null && getPackageName().contentEquals(root.getPackageName())) return null;
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            return bounds.isEmpty() ? null : bounds;
        }
        return null;
    }

    private int clampY(int y) {
        int bottom = (keyboardTop > 0 ? keyboardTop : getResources().getDisplayMetrics().heightPixels) - layout.height;
        return Math.max(0, Math.min(y, bottom));
    }

    /**
     * The text box with the cursor. Some apps (web views especially) don't report it
     * to findFocus, so look through each window as well.
     */
    private AccessibilityNodeInfo focusedField() {
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field != null && field.isEditable()) return field;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            AccessibilityNodeInfo focused = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) return focused;
        }
        return null;
    }

    private void show(boolean visible) {
        if (bubble == null || visible == shown) return;
        if (visible) windows.addView(bubble, layout);
        else windows.removeView(bubble);
        shown = visible;
    }

    private enum Mode { IDLE, LISTENING, WRITING }

    /** The small round mic at rest; a small pill with the waveform while listening and writing. */
    private void mode(Mode m) {
        boolean idle = m == Mode.IDLE;
        mic.setVisibility(idle ? View.VISIBLE : View.GONE);
        wave.setVisibility(idle ? View.GONE : View.VISIBLE);
        int buttons = m == Mode.LISTENING ? View.VISIBLE : idle ? View.GONE : View.INVISIBLE;
        cancelButton.setVisibility(buttons);
        doneButton.setVisibility(buttons);
        if (m == Mode.LISTENING) wave.listen();
        if (m == Mode.WRITING) wave.think();
        layout.width = dp(idle ? SIZE_DP : PILL_DP);
        bubble.animate().alpha(idle ? IDLE_ALPHA : ACTIVE_ALPHA).setDuration(150).start();
        place();
    }

    /** Keep the bubble against its edge, so the pill grows away from it. */
    private void place() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        layout.x = left ? dp(EDGE_DP) : screen - layout.width - dp(EDGE_DP);
        if (shown) windows.updateViewLayout(bubble, layout);
    }

    private ImageView button(int icon, String label) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setBackgroundResource(R.drawable.bubble_button);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setContentDescription(label);
        return b;
    }

    // -- touch -------------------------------------------------------------------
    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downX = e.getRawX();
                downY = e.getRawY();
                startX = layout.x;
                startY = layout.y;
                if (busy) return true;
                bubble.animate().alpha(1f).setDuration(80).start();
                pressedAt = SystemClock.uptimeMillis();
                if (handsFree) {
                    handsFree = false;
                    finish();
                } else {
                    begin();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!dragging && !handsFree && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                    if (recorder.isRecording() && !handsFree) cancel(); // it was a drag, not a hold
                }
                if (dragging) {
                    layout.x = startX + (int) dx;
                    layout.y = startY + (int) dy;
                    windows.updateViewLayout(bubble, layout);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    snapToEdge();
                    if (!recorder.isRecording() && !busy) mode(Mode.IDLE);
                    return true;
                }
                if (!recorder.isRecording() || handsFree) return true;
                if (SystemClock.uptimeMillis() - pressedAt < TAP_MS) {
                    handsFree = true; // a tap: keep listening until ✓, ✕ or another tap
                    main.removeCallbacks(openPill);
                    openPill.run();
                } else {
                    finish();
                }
                return true;
        }
        return false;
    }

    /** Drop it on the nearer edge, and remember the side and how high above the keyboard it is. */
    private void snapToEdge() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        left = layout.x + layout.width / 2 < screen.widthPixels / 2;
        layout.y = clampY(layout.y);
        if (keyboardTop > 0) lift = keyboardTop - layout.height - layout.y;
        place();
        prefs.saveBubble(left, lift);
    }

    // -- dictation ---------------------------------------------------------------
    private void begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Ramble needs the microphone. Opening setup…");
            startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            mode(Mode.IDLE);
            return;
        }
        try {
            recorder.start(rms -> main.post(() -> wave.push(rms)));
        } catch (Exception e) {
            toast(e.getMessage());
            mode(Mode.IDLE);
            return;
        }
        main.postDelayed(openPill, 150); // not at once: this press might turn out to be a drag
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        Dictation.warm(this);
    }

    private void cancel() {
        main.removeCallbacks(openPill);
        recorder.stop();
        handsFree = false;
        mode(Mode.IDLE);
    }

    private void finish() {
        main.removeCallbacks(openPill);
        float[] audio = recorder.stop();
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        diag("finished recording: " + String.format(java.util.Locale.US, "%.1f", audio.length / (float) Whisper.SAMPLE_RATE) + " s"
                + (focusedField() == null ? ", no text box focused right now" : ""));
        if (isSilent(audio)) {
            diag("the recording was pure silence (mic blocked)");
            mode(Mode.IDLE);
            toast("Android gave Ramble silence instead of your voice. Open the Ramble app once, then try again.");
            return;
        }
        if (!Recorder.isSpeech(audio)) {
            diag("no speech in the recording, nothing typed");
            mode(Mode.IDLE);
            return;
        }
        busy = true;
        mode(Mode.WRITING);
        worker.execute(() -> {
            Dictation.Result said;
            try {
                said = Dictation.run(this, audio);
            } catch (Exception e) {
                main.post(() -> { done(); diag("transcribing failed: " + e.getMessage()); toast("That didn't work: " + e.getMessage()); });
                return;
            }
            main.post(() -> {
                done();
                History.add(this, said.text);
                // Before Enter/Send, no trailing space: it would end up in the message.
                diag("dictation done: " + said.text.length() + " chars" + (said.action != null ? ", then " + said.action : ""));
                Runnable then = () -> { if (said.action != null) main.postDelayed(() -> press(said.action), 250); };
                if (said.text.isEmpty()) then.run();
                // Before Enter/Send, no trailing space: it would end up in the message.
                // Press it only if the text landed, and a beat later so the app shows its Send button.
                else insert(said.text + (said.action == null ? " " : ""), landed -> { if (landed) then.run(); });
            });
        });
    }

    private void done() {
        busy = false;
        mode(Mode.IDLE);
        check.run();
    }

    /** Exact zeros for a whole recording means the system blocked the mic, not a quiet room. */
    private static boolean isSilent(float[] audio) {
        if (audio.length < Whisper.SAMPLE_RATE / 2) return false;
        for (float s : audio) if (s != 0f) return false;
        return true;
    }

    // -- typing ------------------------------------------------------------------
    /**
     * Type the text into whichever box has the cursor, then check it stuck. On a real
     * phone the keyboard can quietly undo text set from outside (it still holds its own
     * idea of the word being typed), so each way of typing is verified by re-reading the
     * box a moment later before trying the next. Calls back with false only if the text
     * ended up on the clipboard.
     */
    private void insert(String text, java.util.function.Consumer<Boolean> done) {
        AccessibilityNodeInfo field = focusedField();
        CharSequence current = null;
        int before = -1;
        if (field != null) {
            field.refresh();
            current = field.isPassword() ? null : field.getText();
            boolean placeholder = current != null && isPlaceholder(field, current);
            if (placeholder) current = "";
            before = current == null ? -1 : current.length();
            diag("box: " + field.getClassName() + " in " + field.getPackageName() + ", " + (current == null ? "text unreadable" : before + " chars")
                    + ", cursor " + field.getTextSelectionStart() + (placeholder ? ", showing placeholder" : ""));
        } else {
            diag("no text box visible to accessibility");
        }

        // 1. Android 13+: type through the box's own input connection, exactly like a keyboard.
        //    Works in apps whose boxes ignore the accessibility actions below (Compose, web views).
        if (typeLikeAKeyboard(text)) {
            if (field == null) {
                diag("typed through the keyboard connection (can't re-read this box to check)");
                done.accept(true);
                return;
            }
            final AccessibilityNodeInfo f = field;
            final CharSequence cur = current;
            final int b = before;
            main.postDelayed(() -> {
                if (stuck(f, text, b)) {
                    diag("typed through the keyboard connection: stuck");
                    done.accept(true);
                } else {
                    diag("keyboard connection didn't stick, typing directly instead");
                    typeDirectlyOrPaste(f, cur, text, b, done);
                }
            }, VERIFY_MS);
            return;
        }

        if (field == null) {
            diag("no keyboard connection either, copied instead");
            copy(text.trim());
            toast("Copied: no text box had the cursor");
            done.accept(false);
            return;
        }
        typeDirectlyOrPaste(field, current, text, before, done);
    }

    /**
     * Commit the text through the accessibility input connection (Android 13+, needs
     * flagInputMethodEditor). Adds a space first if the cursor sits right after a word.
     * False if there's no connection, e.g. no box is focused or the Android is older.
     */
    private boolean typeLikeAKeyboard(String text) {
        if (Build.VERSION.SDK_INT < 33) return false;
        InputMethod im = getInputMethod();
        if (im == null) return false;
        InputMethod.AccessibilityInputConnection ic = im.getCurrentInputConnection();
        if (ic == null) return false;
        String piece = text;
        try {
            SurroundingText around = ic.getSurroundingText(1, 0, 0);
            if (around != null && around.getSelectionStart() > 0) {
                char prev = around.getText().charAt(around.getSelectionStart() - 1);
                if (!Character.isWhitespace(prev)) piece = " " + text;
            }
            ic.commitText(piece, 1, null);
            return true;
        } catch (RuntimeException e) {
            diag("keyboard connection failed: " + e.getMessage());
            return false;
        }
    }

    /** 2. Set the text through accessibility when we know where the cursor is, else paste. */
    private void typeDirectlyOrPaste(AccessibilityNodeInfo field, CharSequence current, String text, int before,
                                     java.util.function.Consumer<Boolean> done) {
        boolean canSplice = current != null && (current.length() == 0 || cursorInside(field, current));
        if (canSplice && splice(field, current, text)) {
            main.postDelayed(() -> {
                if (stuck(field, text, before)) {
                    diag("typed directly: stuck");
                    done.accept(true);
                } else {
                    diag("typed directly but the app undid it, pasting instead");
                    pasteThenCheck(field, text, before, done);
                }
            }, VERIFY_MS);
            return;
        }
        diag(canSplice ? "app refused direct typing, pasting instead" : "cursor position unclear, pasting");
        pasteThenCheck(field, text, before, done);
    }

    /** 3. Paste, check, and as a last resort leave the text on the clipboard and say so. */
    private void pasteThenCheck(AccessibilityNodeInfo field, String text, int before, java.util.function.Consumer<Boolean> done) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.getPrimaryClip(); // null when Android won't let Ramble read it
        ClipData passing = ClipData.newPlainText("Ramble", text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            passing.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(passing);
        boolean accepted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        main.postDelayed(() -> {
            if (accepted && stuck(field, text, before)) {
                diag("pasted: stuck");
                // Don't leave the dictation on the clipboard (it's in Ramble's history anyway).
                if (previous != null) clipboard.setPrimaryClip(previous);
                else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
                done.accept(true);
                return;
            }
            diag(accepted ? "paste accepted but nothing appeared, left on clipboard" : "app refused paste, left on clipboard");
            copy(text.trim());
            toast("Couldn't type into " + appName(field) + ", so it's on the clipboard. Long-press the box to paste.");
            done.accept(false);
        }, VERIFY_MS);
    }

    /**
     * Did the text land? Re-read the box. Password boxes can't be read and some apps
     * report nothing at all; then trust the app, as before.
     */
    private static boolean stuck(AccessibilityNodeInfo field, String text, int before) {
        if (!field.refresh()) return true; // the box went away (e.g. the message was sent)
        if (field.isPassword()) return true;
        CharSequence now = field.getText();
        if (now == null) return true;
        String want = text.trim();
        return now.toString().contains(want) || (before >= 0 && now.length() >= before + want.length());
    }

    private String appName(AccessibilityNodeInfo field) {
        try {
            CharSequence pkg = field.getPackageName();
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(String.valueOf(pkg), 0)).toString();
        } catch (Exception e) {
            return "that app";
        }
    }

    private void diag(String message) {
        Diagnostics.log(this, message);
    }

    /**
     * Empty boxes often report their grey placeholder as their text: Google Messages
     * says "Message", and typing after it gave "Message hello". Not every app flags
     * it as a hint, so also compare against the hint.
     */
    private static boolean isPlaceholder(AccessibilityNodeInfo field, CharSequence current) {
        if (field.isShowingHintText()) return true;
        CharSequence hint = field.getHintText();
        return hint != null && hint.length() > 0 && hint.toString().contentEquals(current);
    }

    /**
     * A cursor inside real text. Placeholders that slip past isPlaceholder usually report
     * no cursor, or one at 0, so both of those go the paste route.
     */
    private static boolean cursorInside(AccessibilityNodeInfo field, CharSequence current) {
        int start = field.getTextSelectionStart();
        return start > 0 && start <= current.length();
    }

    /** Put the text in at the cursor directly. Leaves the clipboard alone. */
    private static boolean splice(AccessibilityNodeInfo field, CharSequence current, String text) {
        int start = field.getTextSelectionStart(), end = field.getTextSelectionEnd();
        if (start < 0 || start > current.length()) start = end = current.length();
        end = Math.max(start, Math.min(end, current.length()));
        boolean needsSpace = start > 0 && !Character.isWhitespace(current.charAt(start - 1));
        String piece = (needsSpace ? " " : "") + text;
        String updated = current.subSequence(0, start) + piece + current.subSequence(end, current.length());

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;
        Bundle cursor = new Bundle();
        cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + piece.length());
        cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + piece.length());
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor);
        return true;
    }

    // -- spoken commands ---------------------------------------------------------
    private static final Pattern SEND_LABEL = Pattern.compile("send( (sms|mms|message|text|chat|email|now))?", Pattern.CASE_INSENSITIVE);

    /**
     * "send it": tap the app's Send button, because Enter in most phone chat apps
     * only starts a new line. "press enter", or no Send button found: the
     * keyboard's Enter/Go/Search action.
     */
    private void press(String action) {
        AccessibilityNodeInfo field = focusedField();
        if ("send".equals(action)) {
            AccessibilityNodeInfo send = sendButton(field);
            if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        if (field != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return;
        toast("send".equals(action) ? "Couldn't find a Send button here" : "Couldn't press Enter here");
    }

    /** The Send button in the same window as the text box: labelled "Send", "Send SMS", … */
    private AccessibilityNodeInfo sendButton(AccessibilityNodeInfo field) {
        AccessibilityNodeInfo root = field != null && field.getWindow() != null ? field.getWindow().getRoot() : getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (isSendLabel(node.getContentDescription()) || isSendLabel(node.getText())) {
                // Compose often puts the label on an icon inside the clickable button.
                for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
                    if (n.isClickable() && n.isEnabled()) return n;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private static boolean isSendLabel(CharSequence label) {
        return label != null && SEND_LABEL.matcher(label.toString().trim()).matches();
    }

    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Ramble", text));
    }

    // -- feedback ----------------------------------------------------------------
    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        if (recorder.isRecording()) recorder.stop();
        show(false);
        worker.shutdown();
        super.onDestroy();
    }
}
