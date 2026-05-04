package com.rheinmetal.tianshu.function.GeminiCard;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record GeminiCardHoverPayload(
        GeminiCardItemData hoveredItem,
        GeminiCardComparisonData comparison,
        long stableForMs
) implements ITianshuPayload {
    public GeminiCardHoverPayload {
        if (stableForMs < 0L) stableForMs = 0L;
    }
}
