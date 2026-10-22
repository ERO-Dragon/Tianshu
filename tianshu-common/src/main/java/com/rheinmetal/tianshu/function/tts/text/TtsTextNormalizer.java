package com.rheinmetal.tianshu.function.tts.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes synthesizer input pronounceable. It acts only on the text that goes into speech synthesis: stored
 * history and the chat UI keep the original characters.
 *
 * <p>Why this exists: what the player hears is what the model wrote, and the speech path carries no layout
 * semantics. Markdown emphasis marks, list bullets, emoji, URLs and brackets are all read out character by
 * character; item ids ({@code minecraft:anvil}) and coordinates ({@code 120,64,-300}) additionally make the
 * engine insert unnatural pauses on {@code :} and {@code ,}. The prompt can only lower their probability, so
 * this class is the deterministic backstop.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li><b>Pure</b>: no state, no logging, no clock. The same input always yields the same output
 *       ({@link #normalize} and {@link #normalizeWithReport});</li>
 *   <li><b>Idempotent</b>: {@code normalize(normalize(x)) == normalize(x)}, and already clean text is returned
 *       untouched;</li>
 *   <li><b>Word-free</b>: every substitution either deletes characters or turns them into punctuation (URLs are
 *       dropped, never read as "link"), so this class carries no localized text and needs no resource key.</li>
 * </ul>
 *
 * <p>The only degradation that is counted: an item id whose namespace-stripped body has nothing readable (for
 * example {@code a:__}). The fragment is kept verbatim and reported through
 * {@link Result#unresolvedIdentifiers()}; whether that deserves a diagnostic is the caller's decision.</p>
 */
public final class TtsTextNormalizer {

    /** Outcome of one pass: the text to speak plus the number of ids that could not be made readable. */
    public record Result(String text, int unresolvedIdentifiers) {
        public Result {
            text = text == null ? "" : text;
            unresolvedIdentifiers = Math.max(0, unresolvedIdentifiers);
        }
    }

    // Reasoning traces must never reach speech.
    private static final Pattern THINK_BLOCK =
            Pattern.compile("(?is)<(?:think|reasoning|reflection)[^>]*>.*?</(?:think|reasoning|reflection)>");
    private static final Pattern THINK_SELF_CLOSING =
            Pattern.compile("(?i)<(?:think|reasoning|reflection)[^>]*/>");

    // Code: whole fence lines go away, inline backticks lose only their marks so the content survives.
    private static final Pattern CODE_FENCE = Pattern.compile("(?m)^\\s*(?:```|~~~).*$");
    private static final Pattern INLINE_CODE = Pattern.compile("`+");

    // Markdown structure marks.
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern MD_QUOTE = Pattern.compile("(?m)^\\s*>\\s?");
    private static final Pattern MD_LIST_MARKER =
            Pattern.compile("(?m)^\\s*(?:[-*+•·▪◦]|\\d{1,2}[.)])\\s+");
    private static final Pattern MD_EMPHASIS_STRONG = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern MD_EMPHASIS_UNDERSCORE = Pattern.compile("__([^_]+)__");
    private static final Pattern MD_EMPHASIS_SINGLE = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern MD_TABLE_PIPE = Pattern.compile("\\|");

    // URLs and emoji: nothing about them is worth speaking, so they are dropped.
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{1F1E6}-\\x{1F1FF}\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}"
                    + "\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0E}\\x{FE0F}\\x{200D}\\x{20E3}]");

    // Item ids: minecraft:anvil -> anvil. The namespace carries no information for a listener.
    private static final Pattern IDENTIFIER =
            Pattern.compile("(?<![\\w.\\-])([a-z0-9_.\\-]+):([a-z0-9_./\\-]+)(?![\\w.\\-])");
    private static final Pattern IDENTIFIER_SEPARATOR = Pattern.compile("[_/]+");

    // Brackets: the marks go away, their content stays (a space keeps the words apart).
    private static final Pattern BRACKETS = Pattern.compile("[()\\[\\]（）【】]");

    // Dashes and ellipses collapse into one comma: punctuation is a pause, those characters are noise.
    private static final Pattern ELLIPSIS = Pattern.compile("…+|\\.{3,}|。{2,}|·{3,}");
    private static final Pattern DASH = Pattern.compile("—+|―+|－{2,}|-{2,}");
    private static final Pattern PAUSE_RUN = Pattern.compile("[，,、；：]+");
    private static final Pattern LEADING_PAUSE = Pattern.compile("^[，,、；：]+");
    private static final Pattern TRAILING_PAUSE = Pattern.compile("[，,、；：\\-—]+$");

    // Commas inside numeric triples: dropping them removes the "120, pause, 64" artifact.
    private static final Pattern NUMBER_SEPARATOR = Pattern.compile("(?<=\\d),\\s?(?=\\s?-?\\d)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Cleans one piece of text that is about to be spoken.
     *
     * @param rawText model output; {@code null} or blank yields an empty string
     * @return speakable text (trimmed, runs of whitespace collapsed into one space)
     */
    public String normalize(String rawText) {
        return normalizeWithReport(rawText).text();
    }

    /**
     * Same as {@link #normalize}, but also reports how many item ids could not be made readable, so the caller
     * can record a diagnostic.
     */
    public Result normalizeWithReport(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new Result("", 0);
        }
        String text = THINK_BLOCK.matcher(rawText).replaceAll("");
        text = THINK_SELF_CLOSING.matcher(text).replaceAll("");
        text = CODE_FENCE.matcher(text).replaceAll("");
        text = MD_LINK.matcher(text).replaceAll("$1");
        text = INLINE_CODE.matcher(text).replaceAll("");
        text = MD_HEADING.matcher(text).replaceAll("");
        text = MD_QUOTE.matcher(text).replaceAll("");
        text = MD_LIST_MARKER.matcher(text).replaceAll("");
        text = MD_EMPHASIS_STRONG.matcher(text).replaceAll("$1");
        text = MD_EMPHASIS_UNDERSCORE.matcher(text).replaceAll("$1");
        text = MD_EMPHASIS_SINGLE.matcher(text).replaceAll("$1");
        text = URL.matcher(text).replaceAll("");
        text = MD_TABLE_PIPE.matcher(text).replaceAll(" ");
        text = EMOJI.matcher(text).replaceAll("");

        IdentifierRewrite identifiers = rewriteIdentifiers(text);
        text = BRACKETS.matcher(identifiers.text()).replaceAll(" ");
        text = ELLIPSIS.matcher(text).replaceAll("，");
        text = DASH.matcher(text).replaceAll("，");
        text = NUMBER_SEPARATOR.matcher(text).replaceAll(" ");

        text = text.replace('\r', ' ').replace('\n', ' ');
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = LEADING_PAUSE.matcher(text).replaceAll("");
        text = TRAILING_PAUSE.matcher(text).replaceAll("");
        text = PAUSE_RUN.matcher(text).replaceAll("，");
        return new Result(text.trim(), identifiers.unresolved());
    }

    /** Drops the namespace of item ids; a body with nothing readable is kept verbatim and counted. */
    private static IdentifierRewrite rewriteIdentifiers(String text) {
        Matcher matcher = IDENTIFIER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int unresolved = 0;
        while (matcher.find()) {
            String readable = IDENTIFIER_SEPARATOR.matcher(matcher.group(2)).replaceAll(" ").trim();
            if (readable.isEmpty()) {
                unresolved++;
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(readable));
            }
        }
        matcher.appendTail(out);
        return new IdentifierRewrite(out.toString(), unresolved);
    }

    private record IdentifierRewrite(String text, int unresolved) {
    }
}
