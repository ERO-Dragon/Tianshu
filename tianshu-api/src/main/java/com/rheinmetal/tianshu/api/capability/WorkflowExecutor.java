package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.CancellationSignal;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.concurrent.CompletableFuture;

/**
 * Runs a published workflow, one capability call at a time.
 *
 * <p>The four rules this class exists for:</p>
 * <ol>
 *   <li><strong>Bounded</strong> — the steps are data, so the number of calls is fixed when the plan is declared; there
 *   is nothing in a plan that can loop. That is the bound, and it is why a composite cannot become a runaway process.</li>
 *   <li><strong>Stoppable</strong> — the cancellation signal is read between steps, which is the granularity a plan
 *   actually has: a step already in flight cannot be resumed, but the run stops there and says how far it got.</li>
 *   <li><strong>Judged as the caller</strong> — steps go through {@link CapabilityCatalog#invoke} with the caller's
 *   identity, so a shared workflow can never hand its caller the provider's permissions.</li>
 *   <li><strong>Attributed</strong> — a failure names the step, the capability and the module that provides it.</li>
 * </ol>
 *
 * <p>The executor does not swallow failures: it stops the run, because the steps after a failed one were written
 * assuming it worked.</p>
 */
public final class WorkflowExecutor {

    private WorkflowExecutor() {
    }

    public static CompletableFuture<WorkflowRun> run(WorkflowDeclaration plan, ToolCaller caller, ToolSide side,
                                                     CancellationSignal cancellation) {
        CancellationSignal stop = cancellation == null ? CancellationSignal.NEVER : cancellation;
        int total = plan.stepCount();
        // A run in progress is "how many steps are done plus nothing to report"; anything to report ends the chain. The
        // two are kept apart on purpose: a WorkflowRun that merely says "not finished yet" would be indistinguishable
        // from a real outcome, and every guard around it would be one mistake away from ending the run early.
        CompletableFuture<Progress> chain = CompletableFuture.completedFuture(new Progress(0, null));

        for (int index = 0; index < total; index++) {
            final int step = index;
            final WorkflowStep next = plan.steps().get(index);
            chain = chain.thenCompose(progress -> {
                if (progress.outcome() != null) {
                    return CompletableFuture.completedFuture(progress);
                }
                if (stop.isCancelled()) {
                    return CompletableFuture.completedFuture(new Progress(progress.executed(),
                            WorkflowRun.stoppedAt(plan.id(), progress.executed(), total)));
                }
                return CapabilityCatalog.invoke(next.capability(), next.args(), caller, side)
                        .handle((answer, error) -> {
                            String refusal = refusalOf(answer, error);
                            if (refusal == null) {
                                return new Progress(progress.executed() + 1, null);
                            }
                            return new Progress(progress.executed(), WorkflowRun.failedAt(plan.id(),
                                    progress.executed(), total, step,
                                    describe(step, next.capability(), refusal)));
                        });
            });
        }
        return chain.thenApply(progress -> progress.outcome() != null
                ? progress.outcome()
                : WorkflowRun.finished(plan.id(), total));
    }

    /** Steps that succeeded so far, and the outcome if the run already ended. */
    private record Progress(int executed, WorkflowRun outcome) {
    }

    /** Null when the answer means "the step worked"; a reason otherwise. */
    private static String refusalOf(JsonObject answer, Throwable error) {
        if (error != null) {
            return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        if (answer == null) {
            return "the capability answered nothing";
        }
        boolean ok = answer.has("ok") && answer.get("ok").isJsonPrimitive() && answer.get("ok").getAsBoolean();
        if (ok) {
            return null;
        }
        String code = answer.has("code") ? answer.get("code").getAsString() : "FAILED";
        String message = answer.has("error") ? answer.get("error").getAsString() : "";
        return message.isBlank() ? code : code + " " + message;
    }

    private static String describe(int index, CapabilityId capability, String reason) {
        String provider = CapabilityCatalog.find(capability)
                .map(CapabilityDescriptor::provider)
                .orElse("no registered provider");
        return "step " + (index + 1) + " (" + capability + ", provided by " + provider + "): " + reason;
    }
}