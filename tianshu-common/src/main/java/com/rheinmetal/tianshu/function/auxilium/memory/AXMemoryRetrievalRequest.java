package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

public record AXMemoryRetrievalRequest(
        AXScope scope,
        AXRequest request,
        int maxBlocks,
        int tokenBudget
) {
    public AXMemoryRetrievalRequest {
        scope = scope == null ? AXScope.unknown() : scope;
        maxBlocks = Math.max(0, maxBlocks);
        tokenBudget = Math.max(0, tokenBudget);
    }

    public String queryText() {
        return request == null ? "" : request.userText();
    }
}
