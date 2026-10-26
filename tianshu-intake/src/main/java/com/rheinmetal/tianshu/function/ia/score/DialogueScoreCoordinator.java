package com.rheinmetal.tianshu.function.ia.score;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoringContext;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantScore;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoreRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoreResponsePayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public final class DialogueScoreCoordinator {
    private final IaProtocolAdapter adapter;
    private final long timeoutMillis;
    private final LongSupplier generationSupplier;
    private final ConcurrentMap<String, PendingRound> rounds = new ConcurrentHashMap<>();

    public DialogueScoreCoordinator(IaProtocolAdapter adapter, long timeoutMillis, LongSupplier generationSupplier) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.timeoutMillis = Math.max(0L, timeoutMillis);
        this.generationSupplier = generationSupplier == null ? () -> 0L : generationSupplier;
    }

    public void collect(
            TianshuEnvelope parent,
            DialogueScoringContext scoringContext,
            List<DialogueParticipantDescriptor> participants,
            long generation,
            Completion completion
    ) {
        Objects.requireNonNull(completion, "completion");
        if (parent == null || scoringContext == null || participants == null || participants.isEmpty()) {
            completion.complete(new Result(Map.of(), false, true));
            return;
        }
        PendingRound round = new PendingRound(scoringContext.requestId(), generation, completion);
        if (rounds.putIfAbsent(scoringContext.requestId(), round) != null) {
            completion.complete(new Result(Map.of(), false, true));
            return;
        }
        List<DialogueParticipantDescriptor> providers = new java.util.ArrayList<>();
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant != null && !participant.scoreCapability().isBlank()
                    && adapter.hasCapabilityProvider(participant.scoreCapability())) {
                providers.add(participant);
                round.expectedParticipants.add(participant.participantId());
            }
        }
        if (providers.isEmpty()) {
            complete(round, false, false);
            return;
        }
        for (DialogueParticipantDescriptor participant : providers) {
            DialogueScoreRequestPayload payload = new DialogueScoreRequestPayload(
                    scoringContext.requestId(), participant.participantId(), scoringContext
            );
            TianshuEnvelope request = adapter.buildScoreRequest(parent, participant.scoreCapability(), payload);
            round.requestIds.add(request.envelopeId());
            adapter.registerScoreResponse(request.envelopeId(), this::handleResponse);
            adapter.submitScoreRequest(request);
        }
        if (round.completed.get()) {
            return;
        }
        round.timeoutHandle = adapter.scheduleScoreTimeout(
                scoringContext.requestId(), () -> timeout(scoringContext.requestId()), Duration.ofMillis(timeoutMillis)
        );
    }

    public void clear() {
        for (String requestId : rounds.keySet()) {
            PendingRound round = rounds.get(requestId);
            if (round != null) {
                complete(round, false, true);
            }
        }
    }

    private void handleResponse(TianshuEnvelope envelope, ProtocolContext context) {
        String requestEnvelopeId = envelope == null ? "" : envelope.parentId();
        PendingRound round = rounds.values().stream()
                .filter(candidate -> candidate.requestIds.contains(requestEnvelopeId))
                .findFirst().orElse(null);
        if (round == null) {
            completeContext(context, envelope);
            return;
        }
        if (generationSupplier.getAsLong() != round.generation) {
            complete(round, false, true);
            completeContext(context, envelope);
            return;
        }
        if (envelope.payload() instanceof DialogueScoreResponsePayload response
                && round.expectedParticipants.contains(response.participantId())
                && response.requestId().equals(round.requestId)
                && round.received.add(response.participantId())) {
            round.scores.put(response.participantId(), new DialogueParticipantScore(
                    response.participantId(), response.score(), DialogueParticipantScore.Source.DYNAMIC, "provider"
            ));
            if (round.received.containsAll(round.expectedParticipants)) {
                complete(round, false, false);
            }
        }
        completeContext(context, envelope);
    }

    private void timeout(String requestId) {
        PendingRound round = rounds.get(requestId);
        if (round != null) {
            complete(round, true, false);
        }
    }

    private void complete(PendingRound round, boolean timedOut, boolean stale) {
        if (!round.completed.compareAndSet(false, true)) {
            return;
        }
        rounds.remove(round.requestId, round);
        if (round.timeoutHandle != null && !round.timeoutHandle.isDone()) {
            round.timeoutHandle.cancel("IA_SCORE_ROUND_COMPLETED");
        }
        round.requestIds.forEach(adapter::unregisterScoreResponses);
        round.completion.complete(new Result(Map.copyOf(round.scores), timedOut, stale));
    }

    private static void completeContext(ProtocolContext context, TianshuEnvelope envelope) {
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    public interface Completion {
        void complete(Result result);
    }

    public record Result(Map<String, DialogueParticipantScore> scores, boolean timedOut, boolean stale) {
        public Result {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
        }
    }

    private static final class PendingRound {
        private final String requestId;
        private final long generation;
        private final Completion completion;
        private final java.util.Set<String> requestIds = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> expectedParticipants = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> received = ConcurrentHashMap.newKeySet();
        private final Map<String, DialogueParticipantScore> scores = new ConcurrentHashMap<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile ProtocolTaskHandle timeoutHandle;

        private PendingRound(String requestId, long generation, Completion completion) {
            this.requestId = requestId;
            this.generation = generation;
            this.completion = completion;
        }
    }
}
