package com.rheinmetal.tianshu.function.ir.enhance;

import java.util.List;

/**
 * Named object enhancement output keeps natural language and structured identifiers separate.
 *
 * @param repairedText natural-language repaired text for downstream dialogue, never a resource-id replacement surface
 * @param matchedItemNames human-readable matched item names
 * @param matchedItemIds structured item ids for machine routing and context
 * @param matchedEntityNames human-readable matched entity type names
 * @param matchedEntityTypeIds structured entity type ids for dialogue arbitration
 * @param matched whether any named object was matched
 */
public record IrNamedObjectEnhancementResult(String repairedText, List<String> matchedItemNames, List<String> matchedItemIds, List<String> matchedEntityNames, List<String> matchedEntityTypeIds, boolean matched) {
    public IrNamedObjectEnhancementResult(String repairedText, List<String> matchedItemNames, List<String> matchedItemIds, boolean matched) {
        this(repairedText, matchedItemNames, matchedItemIds, List.of(), List.of(), matched);
    }

    public IrNamedObjectEnhancementResult {
        if (repairedText == null) {
            repairedText = "";
        }
        repairedText = repairedText.trim();
        matchedItemNames = normalize(matchedItemNames);
        matchedItemIds = normalize(matchedItemIds);
        matchedEntityNames = normalize(matchedEntityNames);
        matchedEntityTypeIds = normalize(matchedEntityTypeIds);
    }

    public static IrNamedObjectEnhancementResult empty(String repairedText) {
        return new IrNamedObjectEnhancementResult(repairedText, List.of(), List.of(), List.of(), List.of(), false);
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
