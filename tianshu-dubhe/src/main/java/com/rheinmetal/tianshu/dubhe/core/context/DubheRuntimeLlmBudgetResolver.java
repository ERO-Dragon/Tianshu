package com.rheinmetal.tianshu.dubhe.core.context;

import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmPrimitiveClient;
import com.rheinmetal.tianshu.weaving.contract.LLMPrimitiveResultPayload;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves one turn's budget from the LLM-reported context total.
 *
 * <p>This is the only place where the construction-time fallback is swapped for the real per-turn
 * budget: on success {@link DubheRuntimeLlmBudget#fromSnapshot} derives a policy from the reported
 * {@code contextTokenBudget}, and on any failure the construction-time {@code fallbackPolicy} of the
 * module is used unchanged. Callers must not re-read {@link DubheMemoryWindowPolicy#DEFAULT} afterwards —
 * whatever this resolver completes with is the turn's truth.</p>
 */
public final class DubheRuntimeLlmBudgetResolver {
    private final DubheLlmPrimitiveClient primitiveClient;
    private final DubheMemoryWindowPolicy fallbackPolicy;

    public DubheRuntimeLlmBudgetResolver(DubheLlmPrimitiveClient primitiveClient, DubheMemoryWindowPolicy fallbackPolicy) {
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
        this.fallbackPolicy = fallbackPolicy == null ? DubheMemoryWindowPolicy.DEFAULT : fallbackPolicy;
    }

    public DubheRuntimeLlmBudget fallbackBudget() {
        return DubheRuntimeLlmBudget.fallback(fallbackPolicy);
    }

    public void cancel(String requestId, String reason) {
        primitiveClient.cancelRequest(
                normalizeRequestId(requestId),
                reason == null ? "AX turn cancelled" : reason
        );
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
                once.complete(DubheRuntimeLlmBudget.fromSnapshot(result.runtimeSnapshot(), fallbackPolicy));
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
            return "dubhe.llm.budget.status";
        }
        return requestId + ".llm_budget";
    }

    public interface Completion {
        void complete(DubheRuntimeLlmBudget budget);
    }
}
