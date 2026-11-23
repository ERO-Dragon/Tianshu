package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextTier;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

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
        DubheContextTier contextTier,
        /**
         * Earliest timestamp of a turn still in the raw window; blocks whose source range reaches it are already
         * verbatim in the prompt, so their facts must not be injected again (G36). 0 disables the exclusion.
         */
        long windowFromMillis
) {

    /** Request without window exclusion, for callers that have no raw window (tests, cold starts). */
    public DubheMemoryRetrievalRequest(
            DubheScope scope, DubheRequest request, int maxBlocks, int tokenBudget, DubheContextTier contextTier) {
        this(scope, request, maxBlocks, tokenBudget, contextTier, 0L);
    }
    public DubheMemoryRetrievalRequest {
        scope = scope == null ? DubheScope.unknown() : scope;
        maxBlocks = Math.max(0, maxBlocks);
        tokenBudget = Math.max(0, tokenBudget);
        contextTier = contextTier == null ? DubheContextTier.COMPACT : contextTier;
        windowFromMillis = Math.max(0L, windowFromMillis);
    }

    public String queryText() {
        return request == null ? "" : request.userText();
    }
}
