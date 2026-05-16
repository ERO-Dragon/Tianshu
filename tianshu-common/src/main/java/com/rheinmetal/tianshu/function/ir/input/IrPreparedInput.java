package com.rheinmetal.tianshu.function.ir.input;

import java.util.List;

public record IrPreparedInput(IrInputText source, String voiceText, String filteredText, List<String> removedFillers, List<String> removedEntityBoundaryWords) {
    public IrPreparedInput {
        if (voiceText == null) voiceText = "";
        voiceText = voiceText.trim();
        if (filteredText == null) filteredText = "";
        filteredText = filteredText.trim();
        removedFillers = normalize(removedFillers);
        removedEntityBoundaryWords = normalize(removedEntityBoundaryWords);
    }

    public IrInputText voiceInput() {
        if (source == null) {
            return new IrInputText(voiceText, voiceText, 0, 0L, "", System.currentTimeMillis());
        }
        return new IrInputText(voiceText, source.rawText(), source.turnId(), source.sessionId(), source.source(), source.createdAt());
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
