package com.rheinmetal.tianshu.function.intake.repair.enhance;

import com.rheinmetal.tianshu.function.intake.repair.core.IRCommandService;
import com.rheinmetal.tianshu.function.intake.repair.core.IRParseResult;
import com.rheinmetal.tianshu.function.intake.repair.input.IrPreparedInput;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;

import java.util.List;

public final class DefaultIrNamedObjectEnhancer implements IrNamedObjectEnhancer {
    private final IRCommandService commandService;

    public DefaultIrNamedObjectEnhancer(IRCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public IrNamedObjectEnhancementResult enhance(IrPreparedInput input) {
        if (input == null || input.voiceText().isBlank()) {
            return IrNamedObjectEnhancementResult.empty(input == null ? "" : input.voiceText());
        }
        if (commandService == null || !commandService.isReady() || input.filteredText().isBlank()) {
            return IrNamedObjectEnhancementResult.empty(input.voiceText());
        }
        IRParseResult matchParse = commandService.parse(input.filteredText(), null, true);
        List<IrObjectMatch> matches = IrNamedObjectEnhancementResult.matchesFrom(
                matchParse,
                commandService::resolveDisplayName,
                commandService::resolveEntityDisplayName
        );
        return new IrNamedObjectEnhancementResult(repairedSentence(input, matchParse), matches);
    }

    /**
     * Heals the object words in the sentence the player actually said.
     *
     * <p>The matching pass runs on the filtered view (fillers and boundary words removed), so its healed
     * text is that view rather than the sentence. When the two views differ the sentence is repaired by a
     * second pass over it — the only way to keep the displayed utterance whole and corrected at once.</p>
     */
    private String repairedSentence(IrPreparedInput input, IRParseResult matchParse) {
        if (input.filteredText().equals(input.voiceText())) {
            return healedOr(matchParse, input.voiceText());
        }
        return healedOr(commandService.parse(input.voiceText(), null, true), input.voiceText());
    }

    private static String healedOr(IRParseResult result, String fallback) {
        String healed = result == null ? null : result.getHealedRawText();
        return healed == null || healed.isBlank() ? fallback : healed;
    }
}