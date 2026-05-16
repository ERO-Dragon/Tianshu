package com.rheinmetal.tianshu.function.ir.enhance;

import java.util.List;

public record IrItemEnhancementResult(String repairedText, List<String> matchedItemNames, List<String> matchedItemIds, boolean matched) {
    public IrItemEnhancementResult {
        if (repairedText == null) {
            repairedText = "";
        }
        repairedText = repairedText.trim();
        matchedItemNames = normalize(matchedItemNames);
        matchedItemIds = normalize(matchedItemIds);
    }

    public static IrItemEnhancementResult empty(String repairedText) {
        return new IrItemEnhancementResult(repairedText, List.of(), List.of(), false);
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
