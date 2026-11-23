package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentCompactor;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentDigest;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentEntry;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunCancellation;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepFailure;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepOutcome;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepSignal;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;

import java.time.Duration;
import java.util.List;

/**
 * Compacts evicted agent transcript rounds into a progress digest through the model.
 *
 * <p>It is a separate call, not an in-loop trick: the digest is the only way to keep "what was already ruled out"
 * after the transcript shrinks. A failure here ends the task instead of inventing a digest — a fabricated progress
 * note would make the agent repeat work silently.</p>
 */
public final class DubheLlmAgentCompactor implements DubheAgentCompactor {

    static final int MAX_TOKENS = 800;
    static final long TIMEOUT_MILLIS = 60_000L;
    private static final String REQUEST_ID_PREFIX = "dubhe.compact.";
    private static final String REASON_COMPACT_FAILED = "delegate.compact_failed";

    private final DubheDelegateRequestSender requestSender;
    private final DubheDelegatePromptRepository promptRepository;

    public DubheLlmAgentCompactor(
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
    public DubheAgentDigest compact(String goal, DubheAgentDigest previous, List<DubheAgentEntry> evicted) {
        DubheStepSignal<DubheAgentDigest> signal = new DubheStepSignal<>();
        try {
            requestSender.send(build(goal, previous, evicted), new DigestHandler(signal),
                    Duration.ofMillis(TIMEOUT_MILLIS));
        } catch (RuntimeException rejected) {
            throw new IllegalStateException("compaction request was rejected", rejected);
        }
        DubheStepOutcome<DubheAgentDigest> outcome;
        try {
            outcome = signal.await(TIMEOUT_MILLIS, DubheRunCancellation.none());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("compaction interrupted", interrupted);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new IllegalStateException("compaction timed out", timedOut);
        }
        if (outcome instanceof DubheStepOutcome.Finished<?> finished) {
            return (DubheAgentDigest) finished.result();
        }
        throw new IllegalStateException("compaction did not finish: " + outcome);
    }

    private LLMPromptRequestPayload build(String goal, DubheAgentDigest previous, List<DubheAgentEntry> evicted) {
        JsonObject body = new JsonObject();
        body.addProperty("goal", goal);
        if (previous != null) {
            body.add("previous", JsonParser.parseString(previous.toJson()));
        }
        JsonArray rounds = new JsonArray();
        for (DubheAgentEntry entry : evicted) {
            JsonObject round = new JsonObject();
            round.addProperty("kind", entry.kind().name().toLowerCase());
            round.addProperty("text", entry.text());
            rounds.add(round);
        }
        body.add("evicted", rounds);

        String system = promptRepository == null ? "" : promptRepository.compactionSystemPrompt();
        String instruction = promptRepository == null ? "" : promptRepository.compactionInstruction(body.toString());
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new java.util.ArrayList<>();
        if (!system.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(system));
        }
        messages.add(LLMPromptRequestPayload.MessageItemPayload.user(instruction));
        return new LLMPromptRequestPayload(
                REQUEST_ID_PREFIX + Math.abs(goal.hashCode()),
                messages,
                false,
                false,
                MAX_TOKENS,
                2,
                new LLMPromptRequestPayload.SamplingPayload(0.2F, null, null, null)
        );
    }

    private static final class DigestHandler implements DubheLlmRequestHandler {

        private final DubheStepSignal<DubheAgentDigest> signal;

        private DigestHandler(DubheStepSignal<DubheAgentDigest> signal) {
            this.signal = signal;
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            if (payload == null || !payload.isCompleted()) {
                signal.complete(new DubheStepOutcome.Rejected<>(new DubheStepFailure(REASON_COMPACT_FAILED)));
                return;
            }
            try {
                signal.complete(new DubheStepOutcome.Finished<>(
                        DubheAgentDigest.fromJson(payload.text())));
            } catch (RuntimeException unusable) {
                signal.complete(new DubheStepOutcome.Rejected<>(
                        new DubheStepFailure(REASON_COMPACT_FAILED, unusable)));
            }
        }

        @Override
        public void onCancelled(com.rheinmetal.tianshu.dubhe.DubheTurnCancellation cancellation) {
            signal.complete(new DubheStepOutcome.Aborted<>(
                    cancellation == null
                            ? com.rheinmetal.tianshu.dubhe.DubheTurnCancellation.playerInterrupted(
                                    "compaction cancelled")
                            : cancellation,
                    com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunExpiry.NONE));
        }

        @Override
        public void onCancellationResult(LLMPromptResultPayload payload) {
            signal.complete(new DubheStepOutcome.Rejected<>(new DubheStepFailure(REASON_COMPACT_FAILED)));
        }
    }
}