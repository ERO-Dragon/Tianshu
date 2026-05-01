package com.rheinmetal.tianshu.function.GeminiCard;

public record GeminiCardContext(
        boolean enabled,
        GeminiCardItemData hoveredItem,
        GeminiCardComparisonData comparison
) {
}
