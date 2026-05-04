package com.rheinmetal.tianshu.function.GeminiCard;

import java.util.Map;

public record GeminiCardItemData(
        String semanticKey,
        String itemId,
        GeminiCardItemKind kind,
        String comparisonKey,
        boolean empty,
        boolean damageable,
        int maxDamage,
        int damage,
        double attackDamage,
        double attackSpeed,
        double armor,
        Map<String, Integer> enchantments,
        Map<String, String> mechanisms
) {
    public int durabilityLeft() {
        return Math.max(0, maxDamage - damage);
    }
}
