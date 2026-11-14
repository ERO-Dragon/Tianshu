package com.rheinmetal.tianshu.function.intake.repair.input;

import com.rheinmetal.tianshu.function.intake.repair.core.IntentKeywordLoader;

/**
 * Builds the two text views one utterance is analysed through.
 *
 * <p>{@code replaceHomophones} currently only trims. It is deliberately a named step, not an implied one:
 * a general homophone correction is not implemented, and callers must not assume the voice text was rewritten
 * beyond filler and boundary-word removal.</p>
 */
public final class IrInputPreprocessor {
    public IrPreparedInput prepare(IrInputText input) {
        if (input == null || input.blank()) {
            return new IrPreparedInput(input, "", "");
        }
        String voiceText = replaceHomophones(input.text());
        String withoutFillers = removeKeywords(voiceText, IntentKeywordLoader.getKeywords("FILLER_WORDS"));
        String withoutBoundaryWords = removeKeywords(withoutFillers, IntentKeywordLoader.getKeywords("ENTITY_BOUNDARY_WORDS"));
        return new IrPreparedInput(input, voiceText, withoutBoundaryWords);
    }

    private String replaceHomophones(String text) {
        return text == null ? "" : text.trim();
    }

    private String removeKeywords(String text, String[] keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.length == 0) {
            return text == null ? "" : text.trim();
        }
        String result = text;
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String trimmed = keyword.trim();
            if (result.contains(trimmed)) {
                result = result.replace(trimmed, " ");
            }
        }
        return normalizeSpaces(result);
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
}
