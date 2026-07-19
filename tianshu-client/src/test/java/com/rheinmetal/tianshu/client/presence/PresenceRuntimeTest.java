package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
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
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceRuntimeTest {
    @Test
    void collectorCapturesOnlyPortableContextSnapshots() {
        AtomicReference<Set<PresenceContextGroup>> requestedGroups = new AtomicReference<>();
        ClientGameContextProvider provider = (groups, inputKind) -> {
            requestedGroups.set(Set.copyOf(groups));
            return snapshot("player", inputKind);
        };
        PresenceStateStore stateStore = new PresenceStateStore();
        PresenceEventCollector collector = new PresenceEventCollector(stateStore, provider);

        collector.recordScreenChanged();

        assertEquals(EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT), requestedGroups.get());
        assertEquals("player", stateStore.contextSnapshot().playerId());
        assertEquals(PresenceInputKind.NONE, stateStore.contextSnapshot().recentInputKind());
    }

    @Test
    void advancementPayloadsReachWorldSinkWithoutNativePackets() {
        PresenceStateStore stateStore = new PresenceStateStore();
        PresenceEventCollector collector = new PresenceEventCollector(stateStore, (groups, inputKind) -> PresenceContextSnapshot.empty());
        List<PresenceWorldEventPayload> published = new ArrayList<>();
        collector.setWorldEventSink(published::add);
        PresenceWorldEventPayload first = worldEvent("first");
        PresenceWorldEventPayload second = worldEvent("second");

        collector.recordWorldEvents(List.of(first, second));

        assertEquals(List.of(first, second), published);
    }

    @Test
    void stateStoreRefreshesOnlyDirtyOrUnavailableGroups() {
        PresenceStateStore store = new PresenceStateStore();
        store.updateGroups(snapshot("player", PresenceInputKind.NONE), EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT));

        assertTrue(store.groupsNeedingRefresh(EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT)).isEmpty());

        store.markDirty(PresenceContextGroup.INTERACTION_CONTEXT);

        assertEquals(
                EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT),
                store.groupsNeedingRefresh(EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT))
        );
    }

    @Test
    void dynamicContextGroupsAreRefreshedForEachRequest() {
        PresenceStateStore store = new PresenceStateStore();
        store.updateGroups(snapshot("player", PresenceInputKind.NONE), EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY));

        assertEquals(
                EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY),
                store.groupsNeedingRefresh(EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY))
        );
    }

    @Test
    void worldResetDropsContextAndStatusFromPreviousWorld() {
        PresenceStateStore store = new PresenceStateStore();
        store.updateGroups(snapshot("world-a", PresenceInputKind.NONE), EnumSet.of(
                PresenceContextGroup.INTERACTION_CONTEXT,
                PresenceContextGroup.PLAYER_INVENTORY
        ));
        store.updateStatus(new com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.THINKING,
                com.rheinmetal.tianshu.client.presence.model.PresenceSeverity.INFO,
                "module.llm",
                "presence.status.thinking",
                System.currentTimeMillis(),
                5_000L,
                Map.of()
        ));

        store.resetWorldState();

        assertEquals("", store.contextSnapshot().playerId());
        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.IDLE,
                store.statusSnapshot().statusType());
        assertFalse(store.groupsNeedingRefresh(EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY)).isEmpty());
    }

    @Test
    void queuedQueryIsFailedWhenWorldStopsAndCannotContinueAfterRestart() {
        PresenceStateStore store = new PresenceStateStore();
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(store, new PresenceContextFactMapper());
        coordinator.startWorldSession();
        PresenceContextQueryPayload payload = new PresenceContextQueryPayload(
                "request-1", "session-1", "turn-1", "player", "world-a", "minecraft:overworld", "", List.of(),
                System.currentTimeMillis(), List.of(PresenceContextFactIds.PLAYER_INVENTORY)
        );
        RecordingProtocolContext context = new RecordingProtocolContext();

        coordinator.handleQuery(envelope(payload), context, payload);
        coordinator.stopWorldSession();
        coordinator.startWorldSession();
        coordinator.processPending(new PresenceEventCollector(store, (groups, inputKind) -> snapshot("world-b", inputKind)));

        assertEquals("PRESENCE_WORLD_STOPPED", context.failureCode);
        assertEquals(0, context.completedCount);
        assertTrue(context.responseSubmissions.isEmpty());
    }

    @Test
    void moduleLoadingStatusIsTranslatedByPresenceWithoutModuleSpecificTypes() {
        com.rheinmetal.tianshu.client.presence.status.PresenceModuleStatusMapper mapper =
                new com.rheinmetal.tianshu.client.presence.status.PresenceModuleStatusMapper();
        ModuleStatus status = ModuleStatus.keyed(
                "module.tts",
                "runtime.waiting",
                "tianshu.presence.module.tts.loading",
                ModuleStatusSeverity.INFO,
                4_000L,
                Map.of("loadStage", "preloading")
        );

        var snapshot = mapper.fromStatus(status);

        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.PREPARING, snapshot.statusType());
        assertEquals("tianshu.presence.status.preparing", snapshot.messageKey());
        assertEquals("preloading", snapshot.attributes().get("loadStage"));

        PresenceStateStore store = new PresenceStateStore();
        store.updateStatus(snapshot);
        store.updateStatus(new com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy()
                .fromAsr(com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload.speaking(1L)));
        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.LISTENING,
                store.statusSnapshot().statusType());
    }

    @Test
    void terminalStatusClearsItsSourceAndRevealsTheNextActiveActivity() {
        PresenceStateStore store = new PresenceStateStore();
        store.updateStatus(status(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.THINKING,
                "module.llm", Map.of(), 10_000L
        ));
        store.updateStatus(status(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.SPEAKING,
                "module.tts", Map.of(), 10_000L
        ));
        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.SPEAKING,
                store.statusSnapshot().statusType());

        store.updateStatus(status(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.IDLE,
                "module.tts", Map.of(), 0L
        ));

        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.THINKING,
                store.statusSnapshot().statusType());
    }

    @Test
    void hiddenSourceIsExcludedWithoutHidingOtherVisibleActivity() {
        PresenceStateStore store = new PresenceStateStore();
        store.updateStatus(status(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.SPEAKING,
                "module.tts", Map.of(), 10_000L
        ));
        store.updateStatus(status(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.THINKING,
                "module.llm", Map.of(), 10_000L
        ));

        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.THINKING,
                store.statusSnapshot(source -> !"module.tts".equals(source)).statusType());
    }

    @Test
    void terminalFromOlderSessionCannotClearNewerListeningActivity() {
        PresenceStateStore store = new PresenceStateStore();
        long now = System.currentTimeMillis();
        store.updateStatus(new com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.LISTENING,
                com.rheinmetal.tianshu.client.presence.model.PresenceSeverity.INFO,
                "module.asr", "tianshu.presence.status.listening", now, 10_000L, Map.of("sessionId", "1")
        ));
        store.updateStatus(new com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.LISTENING,
                com.rheinmetal.tianshu.client.presence.model.PresenceSeverity.INFO,
                "module.asr", "tianshu.presence.status.listening", now + 1L, 10_000L, Map.of("sessionId", "2")
        ));

        store.updateStatus(new com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot(
                com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.IDLE,
                com.rheinmetal.tianshu.client.presence.model.PresenceSeverity.INFO,
                "module.asr", "tianshu.presence.status.idle", now + 2L, 0L, Map.of("sessionId", "1")
        ));

        assertEquals("2", store.statusSnapshot().attributes().get("sessionId"));
    }

    @Test
    void pendingContextResponsesAreBoundedPerClientTick() {
        PresenceStateStore store = new PresenceStateStore();
        PresenceContextQueryCoordinator coordinator = new PresenceContextQueryCoordinator(store, new PresenceContextFactMapper());
        coordinator.startWorldSession();
        List<RecordingProtocolContext> contexts = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            PresenceContextQueryPayload payload = new PresenceContextQueryPayload(
                    "request-" + index, "session", "turn", "player", "world", "minecraft:overworld", "", List.of(),
                    System.currentTimeMillis(), List.of(PresenceContextFactIds.PLAYER_INVENTORY)
            );
            RecordingProtocolContext context = new RecordingProtocolContext();
            contexts.add(context);
            coordinator.handleQuery(envelope(payload), context, payload);
        }

        PresenceEventCollector collector = new PresenceEventCollector(store, (groups, inputKind) -> snapshot("player", inputKind));
        coordinator.processPending(collector);

        assertEquals(8L, contexts.stream().filter(context -> !context.failureCode.isBlank()).count());

        coordinator.processPending(collector);

        assertEquals(9L, contexts.stream().filter(context -> !context.failureCode.isBlank()).count());
    }

    @Test
    void productStatusUsesClientTranslationKeysInsteadOfTechnicalFallbacks() {
        com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy policy =
                new com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy(new PresenceTextProvider() {
                    @Override
                    public boolean exists(String key) {
                        return "tianshu.presence.status.speaking".equals(key);
                    }

                    @Override
                    public String text(String key, Object... args) {
                        return "localized-speaking";
                    }
                });

        var display = policy.hudDisplay(policy.fromTts(
                com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload.now(
                        com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState.SPEAKING
                )
        ));

        assertEquals("localized-speaking", display.text());
    }

    private static PresenceWorldEventPayload worldEvent(String id) {
        return new PresenceWorldEventPayload(id, "test", "player", "dimension", 1L, Map.of("id", id));
    }

    private static PresenceContextSnapshot snapshot(String playerId, PresenceInputKind inputKind) {
        PresenceContextSnapshot empty = PresenceContextSnapshot.empty();
        return new PresenceContextSnapshot(
                playerId,
                "minecraft:overworld",
                PresenceScreenKind.NONE,
                "",
                "",
                List.of(),
                empty.crosshairTarget(),
                false,
                false,
                false,
                inputKind,
                empty.playerStatus(),
                empty.worldEnvironment(),
                List.of(),
                List.of(),
                Map.of(),
                1L
        );
    }

    private static com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot status(
            com.rheinmetal.tianshu.client.presence.model.PresenceStatusType type,
            String source,
            Map<String, String> attributes,
            long ttlMillis
    ) {
        return new com.rheinmetal.tianshu.client.presence.model.PresenceStatusSnapshot(
                type,
                com.rheinmetal.tianshu.client.presence.model.PresenceSeverity.INFO,
                source,
                "presence.status." + type.name().toLowerCase(),
                System.currentTimeMillis(),
                ttlMillis,
                attributes
        );
    }

    private static TianshuEnvelope envelope(PresenceContextQueryPayload payload) {
        long now = System.currentTimeMillis();
        return new TianshuEnvelope(new EnvelopeHeader(
                "envelope-1", "trace-1", "", "module.test", TargetMode.CAPABILITY,
                "PRESENCE.QUERY_CONTEXT", DeliveryPolicy.WAIT_IN_QUEUE, PacketType.REQUEST,
                PayloadType.PRESENCE_CONTEXT_QUERY, AckPolicy.EXPECT_SUCCESS_OR_FAILURE, Priority.NORMAL,
                ThreadPolicy.ASYNC_WORKER, now, now + 10_000L, now + 10_000L,
                CancellationScope.SELF_ONLY, FailurePolicy.PROPAGATE_CANCEL
        ), payload);
    }

    private static final class RecordingProtocolContext implements ProtocolContext {
        private String failureCode = "";
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
            failureCode = reasonCode;
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
