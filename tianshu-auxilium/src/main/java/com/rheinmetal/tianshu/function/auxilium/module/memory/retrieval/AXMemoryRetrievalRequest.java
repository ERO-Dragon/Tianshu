package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextTier;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

/**
 * One retrieval request.
 *
 * <p>{@code contextTier} is the tier of the per-turn context budget ({@code AXMemoryWindowPolicy.tier()}),
 * and it is what makes chain expansion follow the real window instead of a fixed constant. When the
 * tier is unknown it resolves to the compact tier, i.e. the most conservative breadth.</p>
 */
public record AXMemoryRetrievalRequest(
        AXScope scope,
        AXRequest request,
        int maxBlocks,
        int tokenBudget,
        AXContextTier contextTier
) {
    public AXMemoryRetrievalRequest {
        scope = scope == null ? AXScope.unknown() : scope;
        maxBlocks = Math.max(0, maxBlocks);
        tokenBudget = Math.max(0, tokenBudget);
        contextTier = contextTier == null ? AXContextTier.COMPACT : contextTier;
    }

    public String queryText() {
        return request == null ? "" : request.userText();
    }
}
