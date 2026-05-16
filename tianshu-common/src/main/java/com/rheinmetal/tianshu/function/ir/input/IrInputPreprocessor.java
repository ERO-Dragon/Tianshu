package com.rheinmetal.tianshu.function.ir.input;

import com.rheinmetal.tianshu.function.ir.core.IntentKeywordLoader;

import java.util.ArrayList;
import java.util.List;

public final class IrInputPreprocessor {
    public IrPreparedInput prepare(IrInputText input) {
        if (input == null || input.blank()) {
            return new IrPreparedInput(input, "", "", List.of(), List.of());
        }
        String voiceText = replaceHomophones(input.text());
        RemovalResult fillerResult = removeKeywords(voiceText, IntentKeywordLoader.getKeywords("FILLER_WORDS"));
        RemovalResult boundaryResult = removeKeywords(fillerResult.text(), IntentKeywordLoader.getKeywords("ENTITY_BOUNDARY_WORDS"));
        return new IrPreparedInput(input, voiceText, normalizeSpaces(boundaryResult.text()), fillerResult.removed(), boundaryResult.removed());
    }

    private String replaceHomophones(String text) {
        return text == null ? "" : text.trim();
    }

    private RemovalResult removeKeywords(String text, String[] keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.length == 0) {
            return new RemovalResult(text == null ? "" : text.trim(), List.of());
        }
        String result = text;
        List<String> removed = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String trimmed = keyword.trim();
            if (!result.contains(trimmed)) {
                continue;
            }
            result = result.replace(trimmed, " ");
            removed.add(trimmed);
        }
        return new RemovalResult(normalizeSpaces(result), removed);
    }

    private String normalizeSpaces(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace('，', ' ')
                .replace(',', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record RemovalResult(String text, List<String> removed) {
        private RemovalResult {
            if (text == null) text = "";
            text = text.trim();
            removed = removed == null || removed.isEmpty() ? List.of() : List.copyOf(removed);
        }
    }
}
