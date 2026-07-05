package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AXRuntimeLlmBudgetResolver {
    private final AXLlmPrimitiveClient primitiveClient;
    private final AXMemoryWindowPolicy fallbackPolicy;

    public AXRuntimeLlmBudgetResolver(AXLlmPrimitiveClient primitiveClient, AXMemoryWindowPolicy fallbackPolicy) {
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
        this.fallbackPolicy = fallbackPolicy == null ? AXMemoryWindowPolicy.DEFAULT : fallbackPolicy;
    }

    public AXRuntimeLlmBudget fallbackBudget() {
        return AXRuntimeLlmBudget.fallback(fallbackPolicy);
    }

    public void resolveContextBudget(String requestId, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        Completion once = once(completion);
        try {
            primitiveClient.requestStatus(normalizeRequestId(requestId), result -> {
                if (result == null || !LLMPrimitiveResultPayload.STATUS_COMPLETED.equals(result.status())) {
                    once.complete(fallbackBudget());
                    return;
                }
                once.complete(AXRuntimeLlmBudget.fromSnapshot(result.runtimeSnapshot(), fallbackPolicy));
            });
        } catch (RuntimeException exception) {
            once.complete(fallbackBudget());
        }
    }

    private Completion once(Completion completion) {
        AtomicBoolean completed = new AtomicBoolean(false);
        return budget -> {
            if (completed.compareAndSet(false, true)) {
                completion.complete(budget == null ? fallbackBudget() : budget);
            }
        };
    }

    private static String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return "ax.llm.budget.status";
        }
        return requestId + ".llm_budget";
    }

    public interface Completion {
        void complete(AXRuntimeLlmBudget budget);
    }
}
