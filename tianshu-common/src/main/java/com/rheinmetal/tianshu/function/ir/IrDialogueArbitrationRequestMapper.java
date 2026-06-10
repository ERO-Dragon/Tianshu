package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ir.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;

import java.util.List;
import java.util.UUID;

final class IrDialogueArbitrationRequestMapper {
    private static final long DEFAULT_EXPIRE_MILLIS = 10_000L;

    DialogueArbitrationRequestPayload map(IrInputText voiceInput, IrPreparedInput preparedInput, IrNamedObjectEnhancementResult namedObjectEnhancement, IrMatchBatch matchBatch) {
        long nowMillis = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String playerId = resolvePlayerId(voiceInput);
        String turnId = Integer.toString(voiceInput == null ? 0 : voiceInput.turnId());
        List<String> matchedWakeWords = matchBatch == null ? java.util.List.<String>of() : matchBatch.matches().stream().<String>flatMap(match -> match.matchedWakeWords().stream()).distinct().toList();
        List<String> matchedItemIds = namedObjectEnhancement == null ? List.of() : namedObjectEnhancement.matchedItemIds();
        List<String> matchedEntityTypeIds = namedObjectEnhancement == null ? List.of() : namedObjectEnhancement.matchedEntityTypeIds();
        String repairedText = resolveRepairedText(voiceInput, preparedInput, namedObjectEnhancement);
        String normalizedText = preparedInput == null ? (voiceInput == null ? "" : voiceInput.text()) : preparedInput.filteredText();
        return new DialogueArbitrationRequestPayload(
                requestId,
                IrProtocolAdapter.MODULE_ID,
                playerId,
                turnId,
                voiceInput == null ? 0L : voiceInput.sessionId(),
                repairedText,
                normalizedText,
                matchedWakeWords,
                matchedItemIds,
                matchedEntityTypeIds,
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

    private String resolveRepairedText(IrInputText voiceInput, IrPreparedInput preparedInput, IrNamedObjectEnhancementResult namedObjectEnhancement) {
        if (voiceInput != null && !voiceInput.text().isBlank()) {
            return voiceInput.text();
        }
        if (namedObjectEnhancement != null && namedObjectEnhancement.repairedText() != null && !namedObjectEnhancement.repairedText().isBlank()) {
            return namedObjectEnhancement.repairedText();
        }
        if (preparedInput != null && !preparedInput.voiceText().isBlank()) {
            return preparedInput.voiceText();
        }
        return voiceInput == null ? "" : voiceInput.text();
    }
}
