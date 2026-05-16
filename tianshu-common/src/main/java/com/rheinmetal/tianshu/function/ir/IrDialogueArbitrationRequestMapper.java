package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ir.enhance.IrItemEnhancementResult;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;

import java.util.List;
import java.util.UUID;

final class IrDialogueArbitrationRequestMapper {
    private static final long DEFAULT_EXPIRE_MILLIS = 10_000L;

    DialogueArbitrationRequestPayload map(IrInputText voiceInput, IrPreparedInput preparedInput, IrItemEnhancementResult itemEnhancement, IrMatchBatch matchBatch) {
        long nowMillis = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String playerId = resolvePlayerId(voiceInput);
        String turnId = Integer.toString(voiceInput == null ? 0 : voiceInput.turnId());
        List<String> matchedHotwords = matchBatch == null ? List.of() : matchBatch.matches().stream().flatMap(match -> match.matchedHotwords().stream()).distinct().toList();
        List<String> matchedEntityRefs = matchBatch == null ? List.of() : matchBatch.matches().stream().map(IrVoiceMatch::moduleId).filter(value -> value != null && !value.isBlank()).distinct().toList();
        List<String> matchedItemIds = itemEnhancement == null ? List.of() : itemEnhancement.matchedItemIds();
        String repairedText = resolveRepairedText(voiceInput, preparedInput, itemEnhancement);
        String normalizedText = preparedInput == null ? (voiceInput == null ? "" : voiceInput.text()) : preparedInput.filteredText();
        return new DialogueArbitrationRequestPayload(
                requestId,
                IrProtocolAdapter.MODULE_ID,
                playerId,
                turnId,
                repairedText,
                normalizedText,
                matchedHotwords,
                matchedItemIds,
                matchedEntityRefs,
                DialogueInteractionHints.empty(),
                DialogueContextSnapshot.empty(playerId),
                nowMillis,
                nowMillis + DEFAULT_EXPIRE_MILLIS
        );
    }

    private String resolvePlayerId(IrInputText input) {
        if (input == null || input.source().isBlank()) {
            return "local";
        }
        return input.source();
    }

    private String resolveRepairedText(IrInputText voiceInput, IrPreparedInput preparedInput, IrItemEnhancementResult itemEnhancement) {
        if (itemEnhancement != null && !itemEnhancement.repairedText().isBlank()) {
            return itemEnhancement.repairedText();
        }
        if (preparedInput != null && !preparedInput.voiceText().isBlank()) {
            return preparedInput.voiceText();
        }
        return voiceInput == null ? "" : voiceInput.text();
    }
}
