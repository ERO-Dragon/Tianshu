package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        store.updateGroups(snapshot("player", PresenceInputKind.NONE), EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY));

        assertTrue(store.groupsNeedingRefresh(EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY)).isEmpty());

        store.markDirty(PresenceContextGroup.PLAYER_INVENTORY);

        assertEquals(
                EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY),
                store.groupsNeedingRefresh(EnumSet.of(PresenceContextGroup.PLAYER_INVENTORY))
        );
    }

    @Test
    void moduleLoadingStatusIsTranslatedByPresenceWithoutModuleSpecificTypes() {
        com.rheinmetal.tianshu.client.presence.status.PresenceModuleStatusMapper mapper =
                new com.rheinmetal.tianshu.client.presence.status.PresenceModuleStatusMapper();
        ModuleStatus status = ModuleStatus.keyed(
                "module.tts",
                "runtime.waiting",
                "tianshu.presence.module.tts.loading",
                "",
                ModuleStatusSeverity.INFO,
                4_000L,
                Map.of("loadStage", "preloading")
        );

        var snapshot = mapper.fromStatus(status);

        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.THINKING, snapshot.statusType());
        assertEquals("preloading", snapshot.attributes().get("loadStage"));

        PresenceStateStore store = new PresenceStateStore();
        store.updateStatus(snapshot);
        store.updateStatus(new com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy()
                .fromAsr(com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload.speaking(1L)));
        assertEquals(com.rheinmetal.tianshu.client.presence.model.PresenceStatusType.LISTENING,
                store.statusSnapshot().statusType());
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
}
