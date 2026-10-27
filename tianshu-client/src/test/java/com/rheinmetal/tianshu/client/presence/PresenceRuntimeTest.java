package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextResponder;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import com.rheinmetal.tianshu.protocol.AckPolicy;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeHeader;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceRuntimeTest {
    @Test
    void chatLinesArePublishedAsOneWindowedWorldEventOnlyInsideASession() {
        AtomicLong now = new AtomicLong(1_000L);
        List<PresenceWorldEventPayload> published = new ArrayList<>();
        PresenceEventCollector collector = collectorWithClock(now, published);

        collector.recordPlayerChatMessage("outside", "uuid-player", "Player");
        assertTrue(published.isEmpty());

        collector.startWorldSession();
        collector.recordPlayerChatMessage("inside", "uuid-player", "Player");
        collector.recordPlayerChatMessage("   ", "uuid-player", "Player");
        collector.recordPlayerChatMessage("inside", "", "Player");
        collector.flushWorldEvents();
        assertTrue(published.isEmpty(), "a window is only published once its second has elapsed");

        now.set(2_000L);
        collector.flushWorldEvents();

        assertEquals(1, published.size());
        assertEquals(PresenceWorldEventPayload.EVENT_CHAT_MESSAGE, published.get(0).eventType());
        assertEquals("inside", published.get(0).values().get(PresenceWorldEventPayload.VALUE_TEXT));
        assertEquals(1, published.get(0).totalCount());
    }

    @Test
    void worldEventsStopWithTheSession() {
        AtomicLong now = new AtomicLong(1_000L);
        List<PresenceWorldEventPayload> published = new ArrayList<>();
        PresenceEventCollector collector = collectorWithClock(now, published);
        PresenceWorldEventPayload first = worldEvent("first");

        collector.recordWorldEvents(List.of(first));
        collector.startWorldSession();
        collector.recordWorldEvents(java.util.Arrays.asList(first, null));
        collector.stopWorldSession();
        now.set(2_000L);
        collector.flushWorldEvents();
        assertTrue(published.isEmpty(), "a window that ended with its world is dropped, never published late");

        collector.startWorldSession();
        collector.recordWorldEvents(List.of(first));
        now.set(3_000L);
        collector.flushWorldEvents();

        assertEquals(1, published.size());
        assertEquals("first", published.get(0).values().get("id"));
        assertEquals(1, published.get(0).totalCount());
    }

    @Test
    void oneCaptureServesEveryQueryDrainedInTheSameTick() {
        List<Set<PresenceContextGroup>> captures = new ArrayList<>();
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(groups -> {
            captures.add(Set.copyOf(groups));
            return snapshot("player");
        }, null);
        RecordingResponder responder = new RecordingResponder();
        coordinator.bindResponder(responder);
        coordinator.startWorldSession();

        coordinator.handleQuery(envelope(query("request-1", PresenceContextFactIds.PLAYER_INVENTORY)),
                new RecordingProtocolContext(), query("request-1", PresenceContextFactIds.PLAYER_INVENTORY));
        coordinator.handleQuery(envelope(query("request-2", PresenceContextFactIds.PLAYER_STATUS)),
                new RecordingProtocolContext(), query("request-2", PresenceContextFactIds.PLAYER_STATUS));

        coordinator.processPending();

        assertEquals(1, captures.size());
        assertEquals(
                EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY, PresenceContextGroup.PLAYER_STATUS),
                captures.get(0)
        );
        assertEquals(List.of("request-1", "request-2"), responder.requestIds);
        assertTrue(responder.factsByRequest.get(0).isEmpty(), "an empty capture contributes no fact");
    }

    @Test
    void requestWithoutFactGroupsAnswersWithoutReadingTheGame() {
        List<PresenceContextGroup> captured = new ArrayList<>();
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(groups -> {
            captured.addAll(groups);
            return snapshot("player");
        }, null);
        RecordingResponder responder = new RecordingResponder();
        coordinator.bindResponder(responder);
        coordinator.startWorldSession();
        PresenceContextQueryPayload payload = query("request-empty");
        RecordingProtocolContext context = new RecordingProtocolContext();

        coordinator.handleQuery(envelope(payload), context, payload);
        coordinator.processPending();

        assertTrue(captured.isEmpty());
        assertEquals(0, context.failureCodes.size());
        assertEquals(List.of("request-empty"), responder.requestIds);
        assertTrue(responder.factsByRequest.get(0).isEmpty());
    }

    @Test
    void captureFailureFailsTheRequestInsteadOfEscapingTheTick() {
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(groups -> {
            throw new IllegalStateException("boom");
        }, null);
        coordinator.startWorldSession();
        PresenceContextQueryPayload payload = query("request-1", PresenceContextFactIds.PLAYER_STATUS);
        RecordingProtocolContext context = new RecordingProtocolContext();

        coordinator.handleQuery(envelope(payload), context, payload);
        coordinator.processPending();

        assertEquals(List.of("PRESENCE_CAPTURE_FAILED"), context.failureCodes);
    }

    @Test
    void queuedQueryIsFailedWhenWorldStopsAndCannotContinueAfterRestart() {
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(
                groups -> snapshot("world-b"), null);
        coordinator.startWorldSession();
        PresenceContextQueryPayload payload = query("request-1", PresenceContextFactIds.PLAYER_INVENTORY);
        RecordingProtocolContext context = new RecordingProtocolContext();

        coordinator.handleQuery(envelope(payload), context, payload);
        coordinator.stopWorldSession();
        coordinator.startWorldSession();
        coordinator.processPending();

        assertEquals(List.of("PRESENCE_WORLD_STOPPED"), context.failureCodes);
        assertEquals(0, context.completedCount);
        assertTrue(context.responseSubmissions.isEmpty());
    }

    @Test
    void pendingQueriesAreBoundedAndTheRemainderIsAnsweredOnTheNextTick() {
        List<Set<PresenceContextGroup>> captures = new ArrayList<>();
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(groups -> {
            captures.add(Set.copyOf(groups));
            return snapshot("player");
        }, null);
        coordinator.startWorldSession();
        for (int index = 0; index < 9; index++) {
            PresenceContextQueryPayload payload = query("request-" + index, PresenceContextFactIds.PLAYER_INVENTORY);
            coordinator.handleQuery(envelope(payload), new RecordingProtocolContext(), payload);
        }

        coordinator.processPending();
        assertEquals(1, captures.size());

        coordinator.processPending();
        assertEquals(2, captures.size());
    }

    @Test
    void hudIsHiddenOutsideAnActiveWorldSession() {
        PresenceClientRuntime runtime = new PresenceClientRuntime(
                groups -> PresenceContextSnapshot.empty(),
                PresenceTextProvider.NOOP
        );

        assertFalse(runtime.currentHudDisplay().visible());

        runtime.startWorldSession();
        PresenceHudDisplay firstDisplay = runtime.currentHudDisplay();
        assertTrue(firstDisplay.visible());
        assertSame(firstDisplay, runtime.currentHudDisplay());

        runtime.stopWorldSession();
        assertFalse(runtime.currentHudDisplay().visible());
    }

    @Test
    void activityTrackerStartsIdleForAnUnknownSession() {
        PresenceActivityTracker tracker = new PresenceActivityTracker();
        assertEquals(
                com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState.IDLE,
                tracker.snapshot().primaryState()
        );
    }

    private static PresenceEventCollector collectorWithClock(
            AtomicLong now,
            List<PresenceWorldEventPayload> published
    ) {
        PresenceEventCollector collector = new PresenceEventCollector(
                LogSink.NOOP,
                new com.rheinmetal.tianshu.client.presence.capture.PresenceWorldEventAggregator(now::get)
        );
        collector.setWorldEventSink(published::add);
        return collector;
    }

    private static PresenceWorldEventPayload worldEvent(String id) {
        return new PresenceWorldEventPayload(id, "test", "player", "dimension", 1L, Map.of("id", id));
    }

    private static PresenceContextSnapshot snapshot(String playerId) {
        return new PresenceContextSnapshot(
                playerId,
                "minecraft:overworld",
                "",
                com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind.NONE,
                "",
                "",
                List.of(),
                com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot.empty(),
                false,
                false,
                false,
                com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus.empty(),
                com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment.empty(),
                List.of(),
                com.rheinmetal.tianshu.client.presence.model.PresenceEquipment.empty(),
                List.of(),
                1L
        );
    }

    private static PresenceContextQueryPayload query(String requestId, String... factIds) {
        return new PresenceContextQueryPayload(
                requestId,
                "session-1",
                "turn-1",
                "player",
                "world-a",
                "minecraft:overworld",
                "",
                List.of(),
                System.currentTimeMillis(),
                List.of(factIds)
        );
    }

    private static TianshuEnvelope envelope(PresenceContextQueryPayload payload) {
        long now = System.currentTimeMillis();
        return new TianshuEnvelope(new EnvelopeHeader(
                "envelope-" + payload.requestId(),
                "trace-1",
                "",
                "module.test",
                TargetMode.CAPABILITY,
                "PRESENCE.QUERY_CONTEXT",
                DeliveryPolicy.WAIT_IN_QUEUE,
                PacketType.REQUEST,
                PayloadType.PRESENCE_CONTEXT_QUERY,
                AckPolicy.EXPECT_SUCCESS_OR_FAILURE,
                Priority.NORMAL,
                ThreadPolicy.ASYNC_WORKER,
                now,
                now + 10_000L,
                now + 10_000L,
                CancellationScope.SELF_ONLY,
                FailurePolicy.PROPAGATE_CANCEL
        ), payload);
    }

    private static final class RecordingResponder implements PresenceContextResponder {
        private final List<String> requestIds = new ArrayList<>();
        private final List<List<PresenceContextSnapshotPayload.FactPayload>> factsByRequest = new ArrayList<>();

        @Override
        public void respond(
                TianshuEnvelope request,
                String requestId,
                List<PresenceContextSnapshotPayload.FactPayload> facts,
                ProtocolContext context
        ) {
            requestIds.add(requestId);
            factsByRequest.add(List.copyOf(facts));
            context.complete(request.envelopeId());
        }
    }

    private static final class RecordingProtocolContext implements ProtocolContext {
        private final List<String> failureCodes = new ArrayList<>();
        private int completedCount;
        private final List<TianshuEnvelope> responseSubmissions = new ArrayList<>();

        @Override
        public void submit(TianshuEnvelope envelope) {
            responseSubmissions.add(envelope);
        }

        @Override
        public void complete(String envelopeId) {
            completedCount++;
        }

        @Override
        public void fail(String envelopeId, String reasonCode, String message, Throwable throwable) {
            failureCodes.add(reasonCode);
        }

        @Override
        public void cancel(String envelopeId, String reasonCode, String message) {
        }

        @Override
        public boolean isCancelled(String envelopeId) {
            return false;
        }

        @Override
        public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {
        }
    }
}
