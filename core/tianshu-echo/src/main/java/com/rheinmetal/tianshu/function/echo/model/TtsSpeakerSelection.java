package com.rheinmetal.tianshu.function.echo.model;

import java.util.Map;

/**
 * Picks the voice index a piece of text should be spoken with.
 *
 * <p>A multi-speaker model can hold voices for several scripts — Kokoro 1.1-zh ships 100 Chinese
 * voices and 3 English ones, and an English sentence read by a Chinese voice keeps the Chinese
 * accent. The catalog names one voice per Unicode script, so nothing here has to know which language
 * a script belongs to; the script of the first letter decides. A Chinese sentence therefore keeps its
 * Chinese voice even when it quotes a Latin name, while a sentence that opens in Latin uses the Latin
 * voice, and text with no letter at all keeps the configured voice.</p>
 */
public final class TtsSpeakerSelection {

    private TtsSpeakerSelection() {
    }

    /**
     * @param info     model whose catalog entry declares the voices, may be {@code null}
     * @param text     text about to be spoken, may be {@code null}
     * @param fallback voice to use when the text names no script the model has a voice for
     */
    public static int select(TtsModelInfo info, String text, int fallback) {
        Map<String, Integer> byScript = info == null ? null : info.speakerIdByScript;
        if (byScript == null || byScript.isEmpty() || text == null || text.isEmpty()) {
            return fallback;
        }
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            Integer voice = byScript.get(Character.UnicodeScript.of(codePoint).name());
            return voice == null ? fallback : Math.max(0, voice);
        }
        return fallback;
    }
}
