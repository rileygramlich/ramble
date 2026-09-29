package dev.rileygramlich.yap;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The Yap bubble: a floating light-blue mic over every app, like Wispr Flow's,
 * so you keep your normal keyboard. Tap it and it opens into a pill with a live
 * waveform: ✓ (or a tap on the waveform) types what you said, ✕ throws it away.
 * Or hold the mic and let go. The text lands in whichever box has the cursor.
 * Drag the mic to move it; it only shows while a text box is focused.
 *
 * It's an accessibility service because that's what's allowed to draw over
 * other apps and put text into their fields.
 */
public class YapBubble extends AccessibilityService {
    private static final long TAP_MS = 300;
    private static final int SIZE_DP = 56, PILL_DP = 184, BUTTON_DP = 40, EDGE_DP = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recorder recorder = new Recorder();
    private final Runnable openPill = () -> { if (recorder.isRecording() && !this.dragging) mode(Mode.LISTENING); };
    private final Runnable check = () -> show(recorder.isRecording() || this.busy || editableFocused());

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

    @Override
    protected void onServiceConnected() {
        windows = getSystemService(WindowManager.class);
        prefs = new Prefs(this);
        slop = ViewConfiguration.get(this).getScaledTouchSlop() * 2;

        int ink = getColor(R.color.bubble_ink);
        bubble = new LinearLayout(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundResource(R.drawable.bubble);
        bubble.setElevation(dp(6));
        bubble.setPadding(dp(8), 0, dp(8), 0);
        bubble.setOnTouchListener(this::onTouch);

        mic = new ImageView(this);
        mic.setImageResource(R.drawable.ic_bubble_mic);
        mic.setContentDescription("Yap: tap to talk");
        bubble.addView(mic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        cancelButton = button(R.drawable.ic_bubble_cancel, "Cancel");
        cancelButton.setOnClickListener(v -> cancel());
        bubble.addView(cancelButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        wave = new Waveform(this, ink);
        LinearLayout.LayoutParams waveSize = new LinearLayout.LayoutParams(0, dp(28), 1f);
        waveSize.setMargins(dp(10), 0, dp(10), 0);
        bubble.addView(wave, waveSize);

        doneButton = button(R.drawable.ic_bubble_done, "Done");
        doneButton.setOnClickListener(v -> { handsFree = false; finish(); });
        bubble.addView(doneButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        layout = new WindowManager.LayoutParams(dp(SIZE_DP), dp(SIZE_DP),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable, so the text box keeps the cursor and your keyboard stays up.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START;
        left = prefs.bubbleLeft();
        layout.y = prefs.bubbleY() >= 0 ? prefs.bubbleY() : getResources().getDisplayMetrics().heightPixels / 3;
        mode(Mode.IDLE);

        worker.execute(() -> {
            try {
                Whisper.ensureLoaded(this); // so the first dictation isn't the slow one
            } catch (Exception e) {
                main.post(() -> toast("Couldn't load the speech model: " + e.getMessage()));
            }
        });
        check.run();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        main.removeCallbacks(check); // events come in bursts; look once they settle
        main.postDelayed(check, 150);
    }

    @Override
    public void onInterrupt() {
        if (recorder.isRecording()) cancel();
    }

    private boolean editableFocused() {
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return field != null && field.isEditable();
    }

    private void show(boolean visible) {
        if (bubble == null || visible == shown) return;
        if (visible) windows.addView(bubble, layout);
        else windows.removeView(bubble);
        shown = visible;
    }

    private enum Mode { IDLE, LISTENING, WRITING }

    /** The round mic at rest; a pill with the waveform while listening and writing. */
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
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
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

    private void snapToEdge() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        left = layout.x + layout.width / 2 < screen.widthPixels / 2;
        layout.y = Math.max(0, Math.min(layout.y, screen.heightPixels - layout.height));
        place();
        prefs.saveBubble(left, layout.y);
    }

    // -- dictation ---------------------------------------------------------------
    private void begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Yap needs the microphone. Opening setup…");
            startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        try {
            recorder.start(rms -> main.post(() -> wave.push(rms)));
        } catch (Exception e) {
            toast(e.getMessage());
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
        if (isSilent(audio)) {
            mode(Mode.IDLE);
            toast("Android gave Yap silence instead of your voice. Open the Yap app once, then try again.");
            return;
        }
        if (!Recorder.isSpeech(audio)) {
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
                main.post(() -> { done(); toast("That didn't work: " + e.getMessage()); });
                return;
            }
            main.post(() -> {
                done();
                // Before Enter/Send, no trailing space: it would end up in the message.
                boolean landed = said.text.isEmpty() || insert(said.text + (said.action == null ? " " : ""));
                // A beat later, so the app has the text (and has shown its Send button).
                if (said.action != null && landed) main.postDelayed(() -> press(said.action), 250);
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
    /** False if the text ended up on the clipboard instead of in a text box. */
    private boolean insert(String text) {
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) {
            copy(text.trim());
            toast("Copied: no text box had the cursor");
            return false;
        }
        CharSequence current = field.isPassword() ? null : field.getText();
        if (current != null && isPlaceholder(field, current)) current = "";
        // Set the text ourselves only when we know exactly where the cursor is. Otherwise
        // paste, and the app puts it at its own cursor.
        if (current != null && (current.length() == 0 || cursorInside(field, current)) && splice(field, current, text)) return true;
        return paste(field, text);
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

    /** For fields that won't take text directly (web pages, password boxes): paste, then put the clipboard back. */
    private boolean paste(AccessibilityNodeInfo field, String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.getPrimaryClip(); // null if Android won't let us read it
        copy(text);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            toast("Couldn't type into that box, so it's on the clipboard");
            return false;
        }
        if (previous != null) main.postDelayed(() -> clipboard.setPrimaryClip(previous), 500);
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
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
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
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Yap", text));
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
