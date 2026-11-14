package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves one turn's budget from the LLM-reported context total.
 *
 * <p>This is the only place where the construction-time fallback is swapped for the real per-turn
 * budget: on success {@link AXRuntimeLlmBudget#fromSnapshot} derives a policy from the reported
 * {@code contextTokenBudget}, and on any failure the construction-time {@code fallbackPolicy} of the
 * module is used unchanged. Callers must not re-read {@link AXMemoryWindowPolicy#DEFAULT} afterwards —
 * whatever this resolver completes with is the turn's truth.</p>
 */
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
