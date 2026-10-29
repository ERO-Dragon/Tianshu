package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.client.host.game.GameInteractionContext;
import com.rheinmetal.tianshu.client.host.game.GameScreenKind;
import com.rheinmetal.tianshu.client.host.game.GameTarget;
import com.rheinmetal.tianshu.client.presence.FakeGameContextPort;
import com.rheinmetal.tianshu.protocol.payload.PresenceInteractionContextPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceInteractionContextWatcherTest {
    private final List<PresenceInteractionContextPayload> published = new ArrayList<>();
    private final FakeGameContextPort port = new FakeGameContextPort();

    @Test
    void publishesTheFirstCaptureAndNothingWhileTheInteractionStaysTheSame() {
        port.interaction(() -> interaction("minecraft:stick", "touhou_little_maid:maid"));
        PresenceInteractionContextWatcher watcher = new PresenceInteractionContextWatcher(port);
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
        assertEquals(List.of("interaction", "interaction"), port.captures(),
                "the per-tick path reads the interaction slice only");
    }

    @Test
    void publishesAgainWhenTheHeldItemChanges() {
        String[] held = {"minecraft:stick"};
        port.interaction(() -> interaction(held[0], ""));
        PresenceInteractionContextWatcher watcher = new PresenceInteractionContextWatcher(port);
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
        port.failWith(new IllegalStateException("broken capture"));
        PresenceInteractionContextWatcher watcher = new PresenceInteractionContextWatcher(port);
        watcher.setSink(published::add);

        watcher.poll();

        assertTrue(published.isEmpty());
    }

    @Test
    void staysQuietOutsideAWorld() {
        port.playerId("").interaction(() -> interaction("minecraft:stick", ""));
        PresenceInteractionContextWatcher watcher = new PresenceInteractionContextWatcher(port);
        watcher.setSink(published::add);

        watcher.poll();

        assertTrue(published.isEmpty());
    }

    @Test
    void resetMakesTheNextCapturePublishAgain() {
        port.interaction(() -> interaction("minecraft:stick", ""));
        PresenceInteractionContextWatcher watcher = new PresenceInteractionContextWatcher(port);
        watcher.setSink(published::add);

        watcher.poll();
        watcher.reset();
        watcher.poll();

        assertEquals(2, published.size());
    }

    private static GameInteractionContext interaction(String heldItemId, String crosshairEntityTypeId) {
        GameTarget target = crosshairEntityTypeId.isBlank()
                ? GameTarget.EMPTY
                : new GameTarget("entity-1", crosshairEntityTypeId, "酒狐", 2.0D, true);
        return new GameInteractionContext(
                "minecraft:overworld",
                "",
                GameScreenKind.NONE,
                heldItemId,
                "",
                List.of("minecraft:iron_helmet"),
                target,
                false,
                false,
                false,
                1_000L
        );
    }
}