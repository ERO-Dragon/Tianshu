package com.rheinmetal.tianshu.client.speech;

import java.util.List;

/**
 * One sentence on its way to the voice.
 *
 * <p><b>Why both a key and a literal text are carried</b> (exactly one is set):</p>
 * <ul>
 *   <li>{@code key} + {@code arguments}: everything that is player-facing wording the project owns — a
 *       delegation receipt ("let me look that up"), a failure / timeout / cancellation notice. The
 *       wording lives in the language table, so the outlet resolves the key on the platform side and
 *       the shared layer never holds natural language.</li>
 *   <li>{@code text}: the delegation conclusion, which is model output that must be spoken
 *       <em>as it is</em>. Re-rendering it would mean a second free-form generation and could smuggle
 *       material back in that the layer already stripped (design §6.2).</li>
 * </ul>
 *
 * <p>An event sentence is always a literal: the producer rendered it before submitting the event.</p>
 *
 * @param source    which producer the line belongs to
 * @param priority  where the line joins the queue
 * @param key       language key, or {@code null} for a literal line
 * @param arguments language key arguments, empty for a literal line
 * @param text      literal sentence, or {@code null} for a keyed line
 */
public record AXSpeechLine(
        AXSpeechSource source,
        AXSpeechPriority priority,
        String key,
        List<String> arguments,
        String text
) {
    public AXSpeechLine {
        if (source == null) {
            throw new IllegalArgumentException("speech source is required");
        }
        priority = priority == null ? AXSpeechPriority.NORMAL : priority;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        key = key == null ? null : key.trim();
        text = text == null ? null : text.trim();
        boolean keyed = key != null && !key.isEmpty();
        boolean literal = text != null && !text.isEmpty();
        if (keyed == literal) {
            throw new IllegalArgumentException("a speech line is either a key or a literal text, not both or neither");
        }
    }

    public static AXSpeechLine keyed(AXSpeechSource source, AXSpeechPriority priority, String key, List<String> arguments) {
        return new AXSpeechLine(source, priority, key, arguments, null);
    }

    public static AXSpeechLine literal(AXSpeechSource source, AXSpeechPriority priority, String text) {
        return new AXSpeechLine(source, priority, null, List.of(), text);
    }

    public boolean isKeyed() {
        return key != null && !key.isEmpty();
    }
}
