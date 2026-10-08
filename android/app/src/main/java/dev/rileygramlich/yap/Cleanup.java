package dev.rileygramlich.yap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The instant, always-on tidy-up: spoken commands ("new line", "scratch that"),
 * spoken punctuation and emoji, your own replacements, filler words, stutters and spacing. A port of desktop/ramble/cleanup.py rules();
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

    /**
     * Spoken punctuation: "haha period" → "haha.", "is that right question mark" →
     * "is that right?". Smart about it: after "a", "the", "my", "grace"… or before
     * "of", "is"… the word is the noun ("a period of time", "the comma is wrong").
     */
    private static final Map<String, String> PUNCTUATION = map(
            "period", ".", "full stop", ".", "comma", ",", "question mark", "?",
            "exclamation mark", "!", "exclamation point", "!", "colon", ":",
            "semicolon", ";", "semi colon", ";", "ellipsis", "…", "dot dot dot", "…");
    private static final Pattern SPOKEN_PUNCTUATION = Pattern.compile(
            "(?<before>\\s*[,.!?;:]?)\\s*\\b(?<word>" + alternatives(PUNCTUATION.keySet()) + ")\\b(?<after>[,.!?;:]*)", I);
    private static final Set<String> NOUN_BEFORE = new HashSet<>(Arrays.asList(
            "a", "an", "the", "each", "every", "one", "any", "some", "which", "what", "whose", "my", "your", "his",
            "her", "our", "their", "its", "same", "whole", "entire", "long", "short", "trial", "grace", "waiting",
            "cooling", "probation", "probationary", "free", "menstrual", "time", "given", "certain", "specific",
            "first", "last", "next", "second", "third", "another", "oxford", "serial", "big", "huge", "double",
            "single", "proper", "missing", "extra", "stray", "spoken"));
    private static final Set<String> NOUN_AFTER = new HashSet<>(Arrays.asList(
            "of", "in", "when", "where", "during", "between", "from", "for", "is", "was", "will", "ends", "ended",
            "starts", "started", "has", "had", "after", "before", "goes", "key", "symbol", "sign", "cancer"));
    /** Spoken emoji: "that's hilarious laughing emoji" → "that's hilarious 😂". */
    private static final Map<String, String> EMOJI = map(
            "laughing", "😂", "crying laughing", "😂", "tears of joy", "😂", "rolling on the floor laughing", "🤣",
            "smiley", "😊", "smiley face", "😊", "smiling", "😊", "smile", "😊", "blushing", "😊", "grinning", "😀",
            "sweat smile", "😅", "wink", "😉", "winking", "😉", "upside down", "🙃", "heart eyes", "😍",
            "kiss", "😘", "kissing", "😘", "cool", "😎", "sunglasses", "😎", "thinking", "🤔", "eye roll", "🙄",
            "eyeroll", "🙄", "smirk", "😏", "smirking", "😏", "grimacing", "😬", "grimace", "😬", "sad", "😞",
            "crying", "😢", "sobbing", "😭", "sob", "😭", "angry", "😠", "mind blown", "🤯", "skull", "💀",
            "shrug", "🤷", "facepalm", "🤦", "heart", "❤️", "red heart", "❤️", "broken heart", "💔",
            "thumbs up", "👍", "thumbs down", "👎", "clap", "👏", "clapping", "👏", "pray", "🙏", "praying", "🙏",
            "prayer", "🙏", "praying hands", "🙏", "folded hands", "🙏", "wave", "👋", "waving", "👋",
            "ok hand", "👌", "okay hand", "👌", "muscle", "💪", "flex", "💪", "eyes", "👀", "fire", "🔥",
            "hundred", "💯", "100", "💯", "party", "🎉", "party popper", "🎉", "tada", "🎉", "sparkles", "✨",
            "star", "⭐", "rocket", "🚀", "check", "✅", "check mark", "✅", "cross mark", "❌", "x", "❌",
            "salute", "🫡", "melting", "🫠", "pleading", "🥺", "puppy eyes", "🥺", "nerd", "🤓", "clown", "🤡");
    private static final Pattern SPOKEN_EMOJI = Pattern.compile(
            ",?\\s*\\b(?<name>" + alternatives(EMOJI.keySet()) + ")[\\s-]?emoji\\b\\.?", I);
    private static final String SYMBOLS = ".,!?;:…";
    /** "Haha" at the end of a message, the way people text it: no period unless you say "period". */
    private static final String LAUGH = "(?:(?:ha){2,}h?|ha(?:[\\s-]ha)+|(?:he){2,}|lol|lmao|lmfao|rofl)";
    private static final Pattern LAUGH_PERIOD = Pattern.compile("\\b(" + LAUGH + ")\\.(?=[ \\t]*(?:\\n|$))", I);
    private static final Pattern LAUGH_AT_END = Pattern.compile("\\b" + LAUGH + "\\s*$", I);

    private Cleanup() {}

    /** "Hi. Send it." → {"Hi.", "send"}. The action is "enter", "send", or null. */
    static String[] command(String text) {
        text = text.trim();
        Matcher m = COMMAND.matcher(text);
        if (!m.find()) return new String[]{text, null};
        return new String[]{text.substring(0, m.start()).replaceAll("[ ,]+$", ""), m.group("enter") != null ? "enter" : "send"};
    }

    static String rules(String text) {
        return rules(text, true, null);
    }

    /** The instant pass. {@code replacements} are your own "say this → type that" pairs. */
    static String rules(String text, boolean punctuation, Map<String, String> replacements) {
        text = scratch(text.trim());
        text = FILLERS.matcher(text).replaceAll("");
        text = LAUGH_PERIOD.matcher(text).replaceAll("$1"); // Whisper's period, before a spoken one can be added
        text = replace(text, replacements);
        text = emoji(text);
        if (punctuation) text = punctuate(text); // before stutters, or "dot dot dot" loses two dots
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
        // Just "period" on its own: the symbol, to go after what's already in the box.
        if (punctuation && !text.isEmpty() && SYMBOLS.indexOf(text.charAt(0)) >= 0) {
            String rest = tidy(text.substring(1));
            return text.charAt(0) + (rest.isEmpty() ? "" : " " + rest);
        }
        return tidy(text);
    }

    /** The model likes to put back the period after a final "haha" that rules left off. */
    static String bareLaugh(String before, String reply) {
        return LAUGH_AT_END.matcher(before).find() ? LAUGH_PERIOD.matcher(reply).replaceAll("$1") : reply;
    }

    /** Does this start with punctuation that belongs right after the previous word? */
    static boolean attaches(String text) {
        return !text.isEmpty() && SYMBOLS.indexOf(text.charAt(0)) >= 0;
    }

    /** Your own pairs, longest first, matched as whole words and ignoring case. */
    static String replace(String text, Map<String, String> pairs) {
        if (pairs == null) return text;
        List<String> says = new ArrayList<>(pairs.keySet());
        says.sort((a, b) -> b.length() - a.length());
        for (String say : says) {
            if (say.trim().isEmpty()) continue;
            String out = pairs.get(say), pattern = "(?<!\\w)" + phrase(say.trim(), "[\\s,-]+") + "(?!\\w)";
            if (!out.matches("(?s).*[\\p{L}\\p{N}].*")) { // an emoji: like the built-in ones, no ", 💙."
                pattern = ",?\\s*" + pattern + "\\.?";
                out = " " + out;
            }
            text = Pattern.compile(pattern, I | Pattern.UNICODE_CASE).matcher(text).replaceAll(Matcher.quoteReplacement(out));
        }
        return text;
    }

    private static String emoji(String text) {
        Matcher m = SPOKEN_EMOJI.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(" " + EMOJI.get(spaced(m.group("name")))));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String punctuate(String text) {
        Matcher m = SPOKEN_PUNCTUATION.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String[] before = text.substring(0, m.start()).trim().split("\\s+");
            String[] after = text.substring(m.end()).trim().split("\\s+");
            String previous = before[before.length - 1].replaceAll("\\W", "").toLowerCase();
            String following = m.group("after").isEmpty() ? after[0].replaceAll("\\W", "").toLowerCase() : "";
            String word = spaced(m.group("word"));
            String symbol = PUNCTUATION.containsKey(word) ? PUNCTUATION.get(word) : PUNCTUATION.get(word.replace(" ", ""));
            boolean noun = NOUN_BEFORE.contains(previous) || NOUN_AFTER.contains(following);
            m.appendReplacement(sb, Matcher.quoteReplacement(noun || symbol == null ? m.group() : symbol + " "));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String spaced(String words) {
        return words.toLowerCase().replaceAll("[\\s-]+", " ");
    }

    /** "heart eyes" → a pattern that also takes "heart-eyes" and "hearteyes". Longest first, so it wins. */
    private static String alternatives(Set<String> phrases) {
        List<String> sorted = new ArrayList<>(phrases);
        sorted.sort((a, b) -> b.length() - a.length());
        List<String> out = new ArrayList<>();
        for (String p : sorted) out.add(phrase(p, "[\\s-]?"));
        return String.join("|", out);
    }

    private static String phrase(String words, String gap) {
        List<String> parts = new ArrayList<>();
        for (String w : words.split("\\s+")) parts.add(Pattern.quote(w));
        return String.join(gap, parts);
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
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
            line = line.replaceAll("\\s+([,.!?;:…])", "$1");
            line = line.replaceAll("(?<!\\.)\\.\\.(?!\\.)", "."); // "haha. period." said both ways
            line = line.replaceAll("([!?…])\\.", "$1");
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
