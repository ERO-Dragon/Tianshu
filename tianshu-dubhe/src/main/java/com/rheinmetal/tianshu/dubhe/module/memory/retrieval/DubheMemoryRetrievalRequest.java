package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextTier;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;

/**
 * One retrieval request.
 *
 * <p>{@code contextTier} is the tier of the per-turn context budget ({@code DubheMemoryWindowPolicy.tier()}),
 * and it is what makes chain expansion follow the real window instead of a fixed constant. When the
 * tier is unknown it resolves to the compact tier, i.e. the most conservative breadth.</p>
 */
public record DubheMemoryRetrievalRequest(
        DubheScope scope,
        DubheRequest request,
        int maxBlocks,
        int tokenBudget,
        DubheContextTier contextTier
) {
    public DubheMemoryRetrievalRequest {
        scope = scope == null ? DubheScope.unknown() : scope;
        maxBlocks = Math.max(0, maxBlocks);
        tokenBudget = Math.max(0, tokenBudget);
        contextTier = contextTier == null ? DubheContextTier.COMPACT : contextTier;
    }

    public String queryText() {
        return request == null ? "" : request.userText();
    }
}
