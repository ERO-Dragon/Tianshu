package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

import java.util.Map;

/**
 * Renders one historical turn into the text a prompt message carries.
 *
 * <p>Only two shapes leave this class: the dialogue text itself, and a single {@code <event>} wrapper for every
 * non-player source. There is no second wrapper any more — another player's chat line, a world event and a
 * message another mod delivered are all events. What the model still needs to tell apart travels in the body:
 * when the turn carries a speaker, the body names them, because a model reading only the sentence cannot tell a
 * companion's words from the player's own.
 *
 * <p>Kept in one place because the prompt contributor and the token counter have to agree. Two copies of the
 * wrapper would drift, and the budget would then be measured against text nobody sends.
 */
public final class AXRawTurnPromptText {

    public static final String EVENT_OPEN = "<event>";
    public static final String EVENT_CLOSE = "</event>";

    private AXRawTurnPromptText() {
    }

    /**
     * @param texts the prompt vocabulary, or {@code null} on a path that only needs the wrapper (token counting)
     */
    public static String render(AXRawTurn turn, AXPromptTexts texts) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return "";
        }
        String content = turn.content().trim();
        if (!turn.worldEventRole()) {
            return content;
        }
        String speaker = turn.speakerName() == null ? "" : turn.speakerName().trim();
        if (speaker.isEmpty()) {
            return EVENT_OPEN + content + EVENT_CLOSE;
        }
        String said = texts == null
                ? ""
                : texts.render(AXPromptTexts.RECENT_DIALOGUE_EVENT_SAID, Map.of("speaker", speaker, "text", content));
        // A catalog without the key still has to say who spoke; a space is the least a neutral separator can be.
        return EVENT_OPEN + (said.isBlank() ? speaker + " " + content : said) + EVENT_CLOSE;
    }
}
