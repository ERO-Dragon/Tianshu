package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentEntry;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentModel;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentModelReply;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentRequest;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentReport;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunExpiry;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheSkillCall;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepFailure;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepOutcome;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepSignal;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMToolSpecPayload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link DubheAgentModel}: carries the skill directory as native tools and maps tool calls back.
 *
 * <p>The provider conversation is replayed from the transcript: the assistant message that requested calls keeps
 * them, and each tool message names the call it answers. That is what the provider protocol requires, and it is
 * why the transcript stores structured entries instead of plain text.</p>
 */
public final class DubheDelegateAgentModel implements DubheAgentModel {

    static final int MAX_TOKENS = 800;
    static final float TEMPERATURE = 0.2F;
    private static final String REQUEST_ID_PREFIX = "dubhe.agent.";
    private static final String REASON_RESULT_FAILED = "delegate.result_failed";
    private static final String REASON_REPORT_MISSING = "delegate.report_missing";
    private static final String REASON_SEND_FAILED = "delegate.send_failed";

    private final DubheDelegateRequestSender requestSender;
    private final DubheDelegatePromptRepository promptRepository;
    private int step;

    public DubheDelegateAgentModel(
            DubheDelegateRequestSender requestSender,
            DubheDelegatePromptRepository promptRepository
    ) {
        if (requestSender == null) {
            throw new IllegalArgumentException("requestSender must not be null");
        }
        this.requestSender = requestSender;
        this.promptRepository = promptRepository;
    }

    @Override
    public DubheStepOutcome<DubheAgentModelReply> next(DubheAgentRequest request) {
        DubheStepSignal<DubheAgentModelReply> signal = new DubheStepSignal<>();
        try {
            requestSender.send(
                    build(request),
                    new ReplyHandler(signal),
                    Duration.ofMillis(request.remainingMillis()));
        } catch (RuntimeException rejected) {
            return new DubheStepOutcome.Rejected<>(new DubheStepFailure(REASON_SEND_FAILED, rejected));
        }
        try {
            return signal.await(request.remainingMillis(), request.cancellation());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new DubheStepOutcome.Aborted<>(
                    DubheTurnCancellation.expired("agent step interrupted"), DubheRunExpiry.NONE);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            return new DubheStepOutcome.Aborted<>(
                    DubheTurnCancellation.expired("agent step wait timed out"), DubheRunExpiry.NONE);
        }
    }

    private LLMPromptRequestPayload build(DubheAgentRequest request) {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
        String system = promptRepository == null ? "" : promptRepository.agentSystemPrompt();
        if (!system.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(system));
        }
        messages.add(LLMPromptRequestPayload.MessageItemPayload.user(request.question()));
        if (!request.notice().isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(request.notice()));
        }
        for (DubheAgentEntry entry : request.transcript()) {
            switch (entry.kind()) {
                case BRIEF -> {
                    // The question is already the first user message.
                }
                case DIGEST -> messages.add(LLMPromptRequestPayload.MessageItemPayload.user(entry.text()));
                case ASSISTANT -> messages.add(new LLMPromptRequestPayload.MessageItemPayload(
                        "assistant", entry.text(), "", entry.toolCalls()));
                case TOOL_RESULT -> messages.add(new LLMPromptRequestPayload.MessageItemPayload(
                        "tool", entry.text(), entry.toolCallId(), List.of()));
            }
        }
        List<LLMToolSpecPayload> tools = request.directory().stream()
                .map(spec -> new LLMToolSpecPayload(spec.id(), spec.description(), spec.inputSchema().toString()))
                .toList();
        return new LLMPromptRequestPayload(
                REQUEST_ID_PREFIX + request.taskId() + "." + (step++),
                messages,
                tools,
                null,
                false,
                false,
                MAX_TOKENS,
                2,
                new LLMPromptRequestPayload.SamplingPayload(TEMPERATURE, null, null, null)
        );
    }

    private static JsonObject parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            var parsed = JsonParser.parseString(argumentsJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject().deepCopy() : new JsonObject();
        } catch (RuntimeException malformed) {
            return new JsonObject();
        }
    }

    private static final class ReplyHandler implements DubheLlmRequestHandler {

        private final DubheStepSignal<DubheAgentModelReply> signal;

        private ReplyHandler(DubheStepSignal<DubheAgentModelReply> signal) {
            this.signal = signal;
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            if (payload == null || !payload.isCompleted()) {
                signal.complete(new DubheStepOutcome.Rejected<>(new DubheStepFailure(REASON_RESULT_FAILED)));
                return;
            }
            List<DubheSkillCall> calls = payload.toolCalls().stream()
                    .map(call -> new DubheSkillCall(
                            call.id(), call.name(), parseArguments(call.argumentsJson())))
                    .toList();
            if (!calls.isEmpty()) {
                signal.complete(new DubheStepOutcome.Finished<>(
                        new DubheAgentModelReply(payload.text(), calls, null)));
                return;
            }
            DubheAgentReport report;
            try {
                report = DubheAgentReport.fromJson(payload.text()).clamped();
            } catch (RuntimeException unusable) {
                signal.complete(new DubheStepOutcome.Rejected<>(
                        new DubheStepFailure(REASON_REPORT_MISSING, unusable)));
                return;
            }
            signal.complete(new DubheStepOutcome.Finished<>(DubheAgentModelReply.finished(report)));
        }

        @Override
        public void onCancelled(DubheTurnCancellation cancellation) {
            signal.complete(new DubheStepOutcome.Aborted<>(
                    cancellation == null
                            ? DubheTurnCancellation.playerInterrupted("delegate cancelled")
                            : cancellation,
                    DubheRunExpiry.NONE));
        }

        @Override
        public void onCancellationResult(LLMPromptResultPayload payload) {
            signal.complete(new DubheStepOutcome.Rejected<>(new DubheStepFailure(REASON_RESULT_FAILED)));
        }
    }
}