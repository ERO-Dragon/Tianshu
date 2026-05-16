package com.rheinmetal.tianshu.function.llm.gateway;

import com.rheinmetal.tianshu.function.llm.LlmInvocationService;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.function.llm.inference.LlmGenerationOptions;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationError;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationFinishReason;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationHandle;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationLane;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationRequest;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationResult;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagContext;
import com.rheinmetal.tianshu.function.llm.inference.LlmStreamSink;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultLlmTaskGatewayService {
    private final LlmInvocationService invocationService;
    private final LlmProtocolAdapter adapter;
    private final LlmGatewayAdmissionController admissionController;
    private final LlmUsageAuthorizer authorizer;
    private final LlmGatewayScheduler scheduler;
    private final Map<String, LlmGatewayTask> pendingAuthorizationTasks = new ConcurrentHashMap<>();
    private final Map<String, ProtocolTaskHandle> authorizationTimeouts = new ConcurrentHashMap<>();

    public DefaultLlmTaskGatewayService(LlmInvocationService invocationService, LlmProtocolAdapter adapter, LlmGatewayPolicy policy, LlmUsageAuthorizer authorizer) {
        this.invocationService = invocationService;
        this.adapter = adapter;
        LlmGatewayPolicy effectivePolicy = policy == null ? LlmGatewayPolicy.DEFAULT : policy;
        this.admissionController = new LlmGatewayAdmissionController(effectivePolicy);
        this.authorizer = authorizer;
        this.scheduler = new LlmGatewayScheduler(effectivePolicy);
    }

    public LlmGatewayAdmissionResult submit(LlmGatewayRequest request, TianshuEnvelope parent) {
        long now = System.currentTimeMillis();
        int pendingForSource = request == null ? 0 : scheduler.pendingCountForSource(request.sourceId());
        LlmGatewayAdmissionResult admission = admissionController.admit(request, pendingForSource, now);
        if (!admission.accepted()) {
            return admission;
        }
        LlmGatewayTask task = new LlmGatewayTask(admission.request(), parent);
        if (task.request().requiresAuthorization()) {
            return startAuthorization(task);
        }
        return scheduleAuthorized(task);
    }

    public void handleAuthorizationCompletion(LlmUsageAuthorizationCompletion completion) {
        if (completion == null || completion.taskId() == null) {
            return;
        }
        LlmGatewayTask task = pendingAuthorizationTasks.remove(completion.taskId());
        cancelAuthorizationTimeout(completion.taskId());
        if (task == null || task.terminal()) {
            return;
        }
        LlmUsageAuthorizationDecision decision = completion.decision();
        if (decision == null || !decision.allowed()) {
            completeTerminal(task, LlmGatewayTaskState.FAILED, new LlmGatewayError(
                    decision == null ? "LLM_USAGE_AUTH_UNAVAILABLE" : decision.reasonCode(),
                    decision == null ? "LLM usage authorization is unavailable" : decision.message()
            ));
            return;
        }
        scheduleAuthorized(task);
    }

    private LlmGatewayAdmissionResult startAuthorization(LlmGatewayTask task) {
        if (authorizer == null) {
            return LlmGatewayAdmissionResult.rejected("LLM_USAGE_AUTH_UNAVAILABLE", "LLM usage authorization is unavailable");
        }
        task.transitionTo(LlmGatewayTaskState.AUTHORIZING);
        LlmUsageAuthorizationStartResult started = authorizer.startAuthorization(task.request(), task.parent());
        if (started == null || !started.started()) {
            completeTerminal(task, LlmGatewayTaskState.FAILED, new LlmGatewayError(
                    started == null ? "LLM_USAGE_AUTH_UNAVAILABLE" : started.code(),
                    started == null ? "LLM usage authorization is unavailable" : started.message()
            ));
            return LlmGatewayAdmissionResult.rejected(
                    started == null ? "LLM_USAGE_AUTH_UNAVAILABLE" : started.code(),
                    started == null ? "LLM usage authorization is unavailable" : started.message()
            );
        }
        pendingAuthorizationTasks.put(task.request().taskId(), task);
        ProtocolTaskHandle timeoutHandle = adapter.scheduleAuthorizationTimeout(
                "llm-auth-timeout-" + task.request().taskId(),
                () -> timeoutAuthorization(task.request().taskId()),
                authorizationTimeoutMillis(task.request())
        );
        authorizationTimeouts.put(task.request().taskId(), timeoutHandle);
        return LlmGatewayAdmissionResult.accepted(task.request());
    }

    private long authorizationTimeoutMillis(LlmGatewayRequest request) {
        long requestExpireAt = request.expireAtMillis();
        long now = System.currentTimeMillis();
        if (requestExpireAt > now) {
            return Math.max(1L, Math.min(5000L, requestExpireAt - now));
        }
        return 5000L;
    }

    private void timeoutAuthorization(String taskId) {
        LlmGatewayTask task = pendingAuthorizationTasks.remove(taskId);
        authorizationTimeouts.remove(taskId);
        if (task == null || task.terminal()) {
            return;
        }
        if (authorizer != null) {
            authorizer.cancel(taskId);
        }
        completeTerminal(task, LlmGatewayTaskState.FAILED, new LlmGatewayError("LLM_USAGE_AUTH_UNAVAILABLE", "LLM usage authorization timed out"));
    }

    private void cancelAuthorizationTimeout(String taskId) {
        ProtocolTaskHandle handle = authorizationTimeouts.remove(taskId);
        if (handle != null && !handle.isDone()) {
            handle.cancel("LLM authorization completed");
        }
    }

    private LlmGatewayAdmissionResult scheduleAuthorized(LlmGatewayTask task) {
        task.transitionTo(LlmGatewayTaskState.ACCEPTED);
        LlmGatewayScheduler.ScheduleDecision decision = scheduler.schedule(task);
        if (decision.error() != null) {
            return LlmGatewayAdmissionResult.rejected(decision.error().code(), decision.error().message());
        }
        if (decision.submitNow()) {
            submitToInvocation(task);
        }
        return LlmGatewayAdmissionResult.accepted(task.request());
    }

    public boolean cancel(String taskId, String reasonCode, String message) {
        LlmGatewayTask authorizing = pendingAuthorizationTasks.remove(taskId);
        cancelAuthorizationTimeout(taskId);
        if (authorizing != null) {
            if (authorizer != null) {
                authorizer.cancel(taskId);
            }
            completeTerminal(authorizing, LlmGatewayTaskState.CANCELLED, new LlmGatewayError(normalizeReason(reasonCode), message));
            return true;
        }
        LlmGatewayTask pending = scheduler.cancelPending(taskId);
        if (pending != null) {
            completeTerminal(pending, LlmGatewayTaskState.CANCELLED, new LlmGatewayError(normalizeReason(reasonCode), message));
            drainPending();
            return true;
        }
        LlmGatewayTask submitted = scheduler.submitted(taskId);
        if (submitted != null) {
            if (submitted.invocationHandle() != null) {
                submitted.invocationHandle().cancel();
            }
            completeTerminal(submitted, LlmGatewayTaskState.CANCELLED, new LlmGatewayError(normalizeReason(reasonCode), message));
            scheduler.completeSubmitted(taskId);
            drainPending();
            return true;
        }
        return false;
    }

    public void shutdown() {
        authorizationTimeouts.values().forEach(handle -> {
            if (handle != null && !handle.isDone()) {
                handle.cancel("LLM task gateway is shutting down");
            }
        });
        authorizationTimeouts.clear();
        pendingAuthorizationTasks.values().forEach(task -> completeTerminal(task, LlmGatewayTaskState.CANCELLED, new LlmGatewayError("GATEWAY_SHUTDOWN", "LLM task gateway is shutting down")));
        pendingAuthorizationTasks.clear();
        scheduler.cancelAll(task -> completeTerminal(task, LlmGatewayTaskState.CANCELLED, new LlmGatewayError("GATEWAY_SHUTDOWN", "LLM task gateway is shutting down")));
    }

    private void submitToInvocation(LlmGatewayTask task) {
        if (task.request().isExpired(System.currentTimeMillis())) {
            completeTerminal(task, LlmGatewayTaskState.EXPIRED, new LlmGatewayError("TASK_EXPIRED", "LLM task request is expired"));
            scheduler.completeSubmitted(task.request().taskId());
            drainPending();
            return;
        }
        LlmInvocationRequest invocationRequest = toInvocationRequest(task.request());
        LlmInvocationHandle handle = task.request().stream()
                ? invocationService.submitTask(invocationRequest, streamSink(task))
                : invocationService.submitTask(invocationRequest);
        task.attachInvocationHandle(handle);
        handle.resultFuture().whenComplete((result, throwable) -> handleInvocationComplete(task, result, throwable));
    }

    private LlmInvocationRequest toInvocationRequest(LlmGatewayRequest request) {
        LlmGenerationOptions options = LlmGenerationOptions.DEFAULT_TASK
                .streaming(request.stream())
                .thinking(request.thinking())
                .useRag(request.useRag())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .taskPriority(request.taskPriority())
                .taskPreemptible(request.taskPreemptible())
                .lane(LlmInvocationLane.TASK);
        LlmRagContext ragContext = request.dynamicFacts().isEmpty()
                ? LlmRagContext.routing(request.ragRouting())
                : LlmRagContext.dynamic(request.dynamicFacts(), request.ragRouting());
        return new LlmInvocationRequest(request.taskId(), request.messages(), options, ragContext);
    }

    private LlmStreamSink streamSink(LlmGatewayTask task) {
        return new LlmStreamSink() {
            @Override
            public void onChunk(String text) {
                task.transitionTo(LlmGatewayTaskState.STREAMING);
                routeChunk(task, text);
            }

            @Override
            public void onFinish(LlmInvocationFinishReason finishReason) {
                routeStreamEnd(task);
            }

            @Override
            public void onError(LlmInvocationError error) {
                routeStreamEnd(task);
            }
        };
    }

    private void handleInvocationComplete(LlmGatewayTask task, LlmInvocationResult result, Throwable throwable) {
        if (task.terminal()) {
            return;
        }
        scheduler.completeSubmitted(task.request().taskId());
        if (throwable != null) {
            Throwable actual = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
            completeTerminal(task, LlmGatewayTaskState.FAILED, new LlmGatewayError("LLM_INVOCATION_FAILED", actual.getMessage()));
        } else if (result == null) {
            completeTerminal(task, LlmGatewayTaskState.FAILED, new LlmGatewayError("LLM_INVOCATION_EMPTY_RESULT", "LLM invocation returned no result"));
        } else if (result.finishReason() == LlmInvocationFinishReason.COMPLETED) {
            task.transitionTo(LlmGatewayTaskState.COMPLETED);
            task.markTerminal();
            routeResult(task, new LlmTaskResultPayload(task.request().taskId(), task.request().purpose(), "COMPLETED", result.text(), null, ""));
        } else if (result.finishReason() == LlmInvocationFinishReason.CANCELLED) {
            completeTerminal(task, LlmGatewayTaskState.CANCELLED, new LlmGatewayError("LLM_INVOCATION_CANCELLED", "LLM invocation was cancelled"));
        } else {
            LlmInvocationError error = result.error();
            completeTerminal(task, LlmGatewayTaskState.FAILED, new LlmGatewayError(error == null ? "LLM_INVOCATION_FAILED" : error.code(), error == null ? "LLM invocation failed" : error.message()));
        }
        drainPending();
    }

    private void completeTerminal(LlmGatewayTask task, LlmGatewayTaskState state, LlmGatewayError error) {
        if (task.terminal()) {
            return;
        }
        task.transitionTo(state);
        task.markTerminal();
        routeResult(task, new LlmTaskResultPayload(
                task.request().taskId(),
                task.request().purpose(),
                state.name(),
                "",
                error == null ? null : error.code(),
                error == null ? "" : error.message()
        ));
    }

    private void drainPending() {
        List<LlmGatewayTask> readyTasks = scheduler.drainReadyTasks(System.currentTimeMillis());
        for (LlmGatewayTask task : readyTasks) {
            if (task.state() == LlmGatewayTaskState.EXPIRED) {
                completeTerminal(task, LlmGatewayTaskState.EXPIRED, new LlmGatewayError("TASK_EXPIRED", "LLM task request is expired"));
            } else {
                submitToInvocation(task);
            }
        }
    }

    private void routeChunk(LlmGatewayTask task, String text) {
        if (task.parent() == null || !task.request().stream()) {
            return;
        }
        adapter.publishTaskStreamChunk(task.parent(), new LlmTaskStreamChunkPayload(
                task.request().taskId(),
                task.request().purpose(),
                task.nextStreamChunkIndex(),
                text,
                false
        ));
    }

    private void routeStreamEnd(LlmGatewayTask task) {
        if (task.parent() == null || !task.request().stream()) {
            return;
        }
        adapter.publishTaskStreamEnd(task.parent(), new LlmTaskStreamChunkPayload(
                task.request().taskId(),
                task.request().purpose(),
                task.nextStreamChunkIndex(),
                "",
                true
        ));
    }

    private void routeResult(LlmGatewayTask task, LlmTaskResultPayload payload) {
        if (task.parent() != null) {
            adapter.respondTaskResult(task.parent(), payload);
        }
    }

    private String normalizeReason(String value) {
        return value == null || value.isBlank() ? "TASK_CANCELLED" : value.trim();
    }
}
