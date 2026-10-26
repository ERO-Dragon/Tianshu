package com.rheinmetal.tianshu.function.ia.score;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.AckPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoringContext;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoreResponsePayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueScoreCoordinatorTest {
    @Test
    void sendsOneRequestPerParticipantForSharedCapability() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            IaProtocolAdapter adapter = new IaProtocolAdapter(runtime);
            AtomicInteger requests = new AtomicInteger();
            adapter.registerScoreCapability("score.shared", (envelope, context) -> {
                requests.incrementAndGet();
                adapter.respondScore(envelope, new DialogueScoreResponsePayload(
                        ((com.rheinmetal.tianshu.function.ia.contract.DialogueScoreRequestPayload) envelope.payload()).requestId(),
                        ((com.rheinmetal.tianshu.function.ia.contract.DialogueScoreRequestPayload) envelope.payload()).participantId(),
                        0.8D,
                        System.currentTimeMillis(),
                        true
                ));
                context.complete(envelope.envelopeId());
            });
            DialogueScoreCoordinator coordinator = new DialogueScoreCoordinator(adapter, 200L, () -> 3L);
            AtomicReference<DialogueScoreCoordinator.Result> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            coordinator.collect(parent(), context(), List.of(participant("p1"), participant("p2")), 3L,
                    value -> { result.set(value); completed.countDown(); });

            assertTrue(completed.await(2L, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
            assertTrue(!result.get().timedOut());
            assertEquals(2, result.get().scores().size());
            assertEquals(0.8D, result.get().scores().get("p1").score());
            assertEquals(0.8D, result.get().scores().get("p2").score());
        } finally {
            runtime.close();
        }
    }

    @Test
    void timeoutCompletesWithoutBlockingWhenProviderDoesNotRespond() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            IaProtocolAdapter adapter = new IaProtocolAdapter(runtime);
            adapter.registerScoreCapability("score.silent", (envelope, context) -> { });
            DialogueScoreCoordinator coordinator = new DialogueScoreCoordinator(adapter, 20L, () -> 4L);
            AtomicReference<DialogueScoreCoordinator.Result> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            coordinator.collect(parent(), context(), List.of(participant("p1", "score.silent")), 4L,
                    value -> { result.set(value); completed.countDown(); });

            assertTrue(completed.await(2L, TimeUnit.SECONDS));
            assertTrue(result.get().timedOut());
            assertTrue(result.get().scores().isEmpty());
        } finally {
            runtime.close();
        }
    }

    @Test
    void noProviderCompletesAsFallbackRoundInsteadOfStale() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            IaProtocolAdapter adapter = new IaProtocolAdapter(runtime);
            DialogueScoreCoordinator coordinator = new DialogueScoreCoordinator(adapter, 20L, () -> 5L);
            AtomicReference<DialogueScoreCoordinator.Result> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            coordinator.collect(parent(), context(), List.of(participant("p1", "score.missing")), 5L,
                    value -> { result.set(value); completed.countDown(); });

            assertTrue(completed.await(2L, TimeUnit.SECONDS));
            assertTrue(!result.get().timedOut());
            assertTrue(!result.get().stale());
            assertTrue(result.get().scores().isEmpty());
        } finally {
            runtime.close();
        }
    }

    private static DialogueParticipantDescriptor participant(String id) {
        return participant(id, "score.shared");
    }

    private static DialogueParticipantDescriptor participant(String id, String capability) {
        return new DialogueParticipantDescriptor(
                id, "module." + id, id, 0, DialogueClaimProfile.DISABLED,
                DialogueTriggerGroup.EMPTY, "route." + id, DialogueTurnProcessingPolicy.DEFAULT,
                capability, 0.0D
        );
    }

    private static DialogueScoringContext context() {
        return new DialogueScoringContext("request-1", "player-1", "turn-1", List.of(), List.of(),
                DialogueContextFrame.empty("player-1").interactionHints(),
                DialogueContextFrame.empty("player-1").contextSnapshot(), System.currentTimeMillis(), System.currentTimeMillis() + 1_000L);
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope parent() {
        return EnvelopeBuilder.create()
                .sourceId("test")
                .targetMode(TargetMode.CAPABILITY)
                .target("test")
                .packetType(PacketType.REQUEST)
                .payloadType(PayloadType.CUSTOM)
                .ackPolicy(AckPolicy.NONE)
                .priority(Priority.NORMAL)
                .payload(new com.rheinmetal.tianshu.function.ia.contract.DialogueScoreResponsePayload("parent", "parent", 0.0D, 0L, false))
                .build();
    }
}
