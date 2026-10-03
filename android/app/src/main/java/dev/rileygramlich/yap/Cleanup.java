package dev.rileygramlich.yap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The instant, always-on tidy-up: spoken commands ("new line", "scratch that"),
 * filler words, stutters and spacing. A port of desktop/ramble/cleanup.py rules();
 * keep the two in step.
 */
final class Cleanup {
    private static final int I = Pattern.CASE_INSENSITIVE;
    private static final Pattern FILLERS = Pattern.compile("(?<![\\w'])(?:u+m+|u+h+m*|e+r+m+|h+m+)(?![\\w'])[,.]?\\s*", I);
    private static final Pattern SCRATCH = Pattern.compile("[,.]?\\s*\\bscratch that\\b[.,!?]*\\s*", I);
    private static final Pattern NEW_PARAGRAPH = Pattern.compile(",?\\s*\\bnew paragraph\\b[.,!?]*\\s*", I);
    private static final Pattern NEW_LINE = Pattern.compile(",?\\s*\\bnew line\\b[.,!?]*\\s*", I);
    private static final Pattern STUTTER = Pattern.compile("\\b(\\w+)(?:,?\\s+\\1\\b)+", I);
    private static final Set<String> KEEP_DOUBLED = new HashSet<>(Arrays.asList("that", "had", "is", "do", "very", "no", "bye", "ha"));
    /**
     * A command said as its own sentence at the very end: type what came before,
     * then press Enter or the app's Send button. "Hi. Send it." sends; "Can you
     * send it?" and "I'll send it" are just words.
     */
    private static final Pattern COMMAND = Pattern.compile(
            "(?:^|(?<=[.!?,]))\\s*(?:(?<enter>(?:press|hit)\\s+(?:enter|return))"
                    + "|(?<send>send\\s+(?:it|that|this|(?:the\\s+)?message)))\\s*[.!]*\\s*$", I);
    private static final Pattern SENTENCE_START = Pattern.compile("(^|[.!?]\\s+)([a-z])");

    private Cleanup() {}

    /** "Hi. Send it." → {"Hi.", "send"}. The action is "enter", "send", or null. */
    static String[] command(String text) {
        text = text.trim();
        Matcher m = COMMAND.matcher(text);
        if (!m.find()) return new String[]{text, null};
        return new String[]{text.substring(0, m.start()).replaceAll("[ ,]+$", ""), m.group("enter") != null ? "enter" : "send"};
    }

    static String rules(String text) {
        text = scratch(text.trim());
        text = FILLERS.matcher(text).replaceAll("");
        Matcher m = STUTTER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String word = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(KEEP_DOUBLED.contains(word.toLowerCase()) ? m.group(0) : word));
        }
        m.appendTail(sb);
        text = sb.toString();
        text = NEW_PARAGRAPH.matcher(text).replaceAll("\n\n");
        text = NEW_LINE.matcher(text).replaceAll("\n");
        return tidy(text);
    }

    /** "…at five. Scratch that. At six." drops the sentence before the command. */
    private static String scratch(String text) {
        Matcher m;
        while ((m = SCRATCH.matcher(text)).find()) {
            String before = text.substring(0, m.start()).replaceAll("\\s+$", "");
            int cut = Math.max(Math.max(before.lastIndexOf(". "), before.lastIndexOf("? ")),
                    Math.max(before.lastIndexOf("! "), before.lastIndexOf('\n')));
            before = cut >= 0 ? before.substring(0, cut + 1) : "";
            text = (before + " " + text.substring(m.end())).trim();
        }
        return text;
    }

    private static String tidy(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            line = line.replaceAll("[ \\t]+", " ").trim();
            line = line.replaceAll("\\s+([,.!?;:])", "$1");
            line = line.replaceAll("([,;:])(?:\\s*[,;:])+", "$1");
            line = line.replaceAll("^[,;:.]\\s*", "");
            line = line.replaceAll(",([.!?])", "$1");
            Matcher m = SENTENCE_START.matcher(line);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2).toUpperCase()));
            m.appendTail(sb);
            lines.add(sb.toString());
        }
        return String.join("\n", lines).trim();
    }
}
