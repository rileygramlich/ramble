package dev.rileygramlich.yap;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
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
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The Yap bubble: a small light-blue mic over every app, like Wispr Flow's, so
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
    private static final long TAP_MS = 300;
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
        mic.setContentDescription("Yap: tap to talk");
        bubble.addView(mic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        cancelButton = button(R.drawable.ic_bubble_cancel, "Cancel");
        cancelButton.setOnClickListener(v -> cancel());
        bubble.addView(cancelButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));

        wave = new Waveform(this, ink);
        LinearLayout.LayoutParams waveSize = new LinearLayout.LayoutParams(0, dp(18), 1f);
        waveSize.setMargins(dp(8), 0, dp(8), 0);
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
     * Where the keyboard is, or null if none is open. Yap's own keyboard counts as
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
            toast("Yap needs the microphone. Opening setup…");
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
                History.add(this, said.text);
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
        AccessibilityNodeInfo field = focusedField();
        if (field == null) {
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

    /**
     * For fields that won't take text directly (web pages, password boxes, boxes that
     * might be showing a placeholder): the text passes through the clipboard for a
     * moment, then the clipboard goes back to how it was. It's marked sensitive while
     * it's there, so the keyboard doesn't offer it as a paste suggestion and Android's
     * "Copied" preview doesn't show it.
     */
    private boolean paste(AccessibilityNodeInfo field, String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.getPrimaryClip(); // null when Android won't let Yap read it
        ClipData passing = ClipData.newPlainText("Yap", text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            passing.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(passing);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            copy(text.trim());
            toast("Couldn't type into that box, so it's on the clipboard");
            return false;
        }
        main.postDelayed(() -> {
            // Android usually hides the old clipboard from Yap. Then the best we can do
            // is not leave the dictation sitting there (it's in Yap's history anyway).
            if (previous != null) clipboard.setPrimaryClip(previous);
            else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
        }, 500);
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
