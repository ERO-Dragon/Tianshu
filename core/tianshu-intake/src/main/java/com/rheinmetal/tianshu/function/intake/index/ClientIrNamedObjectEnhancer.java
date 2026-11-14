package com.rheinmetal.tianshu.function.intake.index;

import com.rheinmetal.tianshu.function.intake.repair.core.IRParseResult;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrContextHint;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.function.intake.repair.input.IrPreparedInput;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;

import java.util.List;

/**
 * Named-object enhancement backed by the client-lifetime index.
 *
 * <p>Matching runs on the filtered view; the displayed sentence is healed separately so fillers and
 * boundary words never leak into the text a prompt shows. Matches are built as pairs, so an object whose
 * display name is missing is dropped alone instead of shifting every later pairing.</p>
 */
public final class ClientIrNamedObjectEnhancer implements IrNamedObjectEnhancer {
    private final ClientNamedObjectIndexManager indexManager;

    public ClientIrNamedObjectEnhancer(ClientNamedObjectIndexManager indexManager) {
        this.indexManager = java.util.Objects.requireNonNull(indexManager, "indexManager");
    }

    @Override
    public IrNamedObjectEnhancementResult enhance(IrPreparedInput input) {
        return enhance(input, IrContextHint.empty());
    }

    @Override
    public IrNamedObjectEnhancementResult enhance(IrPreparedInput input, IrContextHint contextHint) {
        if (input == null || input.voiceText().isBlank() || input.filteredText().isBlank()) {
            return IrNamedObjectEnhancementResult.empty(input == null ? "" : input.voiceText());
        }
        IRParseResult matchParse = indexManager.parsePlayerCommand(input.filteredText(), true, contextHint);
        List<IrObjectMatch> matches = IrNamedObjectEnhancementResult.matchesFrom(
                matchParse,
                indexManager::resolveDisplayName,
                indexManager::resolveEntityDisplayName
        );
        return new IrNamedObjectEnhancementResult(repairedSentence(input, matchParse, contextHint), matches);
    }

    private String repairedSentence(IrPreparedInput input, IRParseResult matchParse, IrContextHint contextHint) {
        if (input.filteredText().equals(input.voiceText())) {
            return healedOr(matchParse, input.voiceText());
        }
        return healedOr(indexManager.parsePlayerCommand(input.voiceText(), true, contextHint), input.voiceText());
    }

    private static String healedOr(IRParseResult result, String fallback) {
        String healed = result == null ? null : result.getHealedRawText();
        return healed == null || healed.isBlank() ? fallback : healed;
    }
}