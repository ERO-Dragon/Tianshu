package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceEquipment;
import com.rheinmetal.tianshu.client.presence.model.PresencePlayerStatus;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceWorldEnvironment;
import com.rheinmetal.tianshu.protocol.payload.PresenceInteractionContextPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceInteractionContextWatcherTest {
    private final List<PresenceInteractionContextPayload> published = new ArrayList<>();

    @Test
    void publishesTheFirstCaptureAndNothingWhileTheInteractionStaysTheSame() {
        PresenceInteractionContextWatcher watcher = watcher(groups -> snapshot("player", "minecraft:stick", "touhou_little_maid:maid"));
        watcher.setSink(published::add);

        watcher.poll();
        watcher.poll();

        assertEquals(1, published.size());
        PresenceInteractionContextPayload payload = published.get(0);
        assertEquals("player", payload.playerId());
        assertEquals("minecraft:stick", payload.heldItemId());
        assertEquals("touhou_little_maid:maid", payload.crosshairEntityTypeId());
        assertTrue(payload.crosshairHit());
        assertEquals(List.of("minecraft:iron_helmet"), payload.equippedItemIds());
    }

    @Test
    void publishesAgainWhenTheHeldItemChanges() {
        String[] held = {"minecraft:stick"};
        PresenceInteractionContextWatcher watcher = watcher(groups -> snapshot("player", held[0], ""));
        watcher.setSink(published::add);

        watcher.poll();
        held[0] = "minecraft:apple";
        watcher.poll();

        assertEquals(2, published.size());
        assertEquals("minecraft:apple", published.get(1).heldItemId());
        assertFalse(published.get(1).crosshairHit());
    }

    @Test
    void aFailingCaptureDoesNotEscapeAndDoesNotPublish() {
        PresenceInteractionContextWatcher watcher = watcher(groups -> {
            throw new IllegalStateException("broken capture");
        });
        watcher.setSink(published::add);

        watcher.poll();

        assertTrue(published.isEmpty());
    }

    @Test
    void staysQuietOutsideAWorld() {
        PresenceInteractionContextWatcher watcher = watcher(groups -> snapshot("", "minecraft:stick", ""));
        watcher.setSink(published::add);

        watcher.poll();

        assertTrue(published.isEmpty());
    }

    @Test
    void resetMakesTheNextCapturePublishAgain() {
        PresenceInteractionContextWatcher watcher = watcher(groups -> snapshot("player", "minecraft:stick", ""));
        watcher.setSink(published::add);

        watcher.poll();
        watcher.reset();
        watcher.poll();

        assertEquals(2, published.size());
    }

    private PresenceInteractionContextWatcher watcher(ClientGameContextProvider platform) {
        return new PresenceInteractionContextWatcher(platform);
    }

    private static PresenceContextSnapshot snapshot(String playerId, String heldItemId, String crosshairEntityTypeId) {
        PresenceTargetSnapshot target = crosshairEntityTypeId.isBlank()
                ? PresenceTargetSnapshot.empty()
                : new PresenceTargetSnapshot("entity-1", crosshairEntityTypeId, "酒狐", 2.0D, true);
        return new PresenceContextSnapshot(
                playerId,
                "minecraft:overworld",
                "",
                PresenceScreenKind.NONE,
                heldItemId,
                "",
                List.of("minecraft:iron_helmet"),
                target,
                false,
                false,
                false,
                PresencePlayerStatus.empty(),
                PresenceWorldEnvironment.empty(),
                List.of(),
                PresenceEquipment.empty(),
                List.of(),
                1_000L
        );
    }
}