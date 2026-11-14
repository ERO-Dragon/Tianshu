package com.rheinmetal.tianshu.function.intake.repair.input;

/**
 * One prepared utterance: the repaired body and the filtered view used for matching.
 *
 * <p>Two texts rather than one because the consumers need different things: the player-facing text keeps the
 * sentence whole, while the matcher compares a view with fillers and boundary words removed. What was removed
 * is not kept — no consumer ever read it, and the filtered text already carries the result.</p>
 */
public record IrPreparedInput(IrInputText source, String voiceText, String filteredText) {
    public IrPreparedInput {
        if (voiceText == null) voiceText = "";
        voiceText = voiceText.trim();
        if (filteredText == null) filteredText = "";
        filteredText = filteredText.trim();
    }

    public IrInputText voiceInput() {
        if (source == null) {
            return new IrInputText(voiceText, voiceText, 0, 0L, "", System.currentTimeMillis());
        }
        return new IrInputText(voiceText, source.rawText(), source.turnId(), source.sessionId(), source.source(), source.createdAt());
    }
}
