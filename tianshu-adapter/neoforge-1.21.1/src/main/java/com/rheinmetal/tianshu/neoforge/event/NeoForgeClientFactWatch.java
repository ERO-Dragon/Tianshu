package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;

/**
 * Facts the client itself can observe exactly, without a server component.
 *
 * <p>Three of them live here because the client has the event and the server does not need to be involved: a level
 * being (re)built is a dimension change or a respawn, and opening a container screen is a container being opened.
 * Reporting them here means they keep working on a server that does not run Tianshu at all.</p>
 *
 * <p>The first level load after joining a world is the join itself, not a move, so it is swallowed once.</p>
 */
public final class NeoForgeClientFactWatch {

    private boolean levelSeen;

    public void register(IEventBus gameBus) {
        gameBus.addListener(this::onLevelLoad);
        gameBus.addListener(this::onPlayerClone);
        gameBus.addListener(this::onScreenOpening);
    }

    public void onLevelLoad(LevelEvent.Load event) {
        if (!levelSeen) {
            levelSeen = true;
            return;
        }
        if (!(event.getLevel() instanceof net.minecraft.world.level.Level level)) {
            return;
        }
        publish(PresenceWorldEventPayload.EVENT_DIMENSION_CHANGED,
                level.dimension().location().toString(), Map.of());
    }

    public void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        publish(PresenceWorldEventPayload.EVENT_RESPAWN, "", Map.of());
    }

    public void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof AbstractContainerScreen<?>) {
            publish(PresenceWorldEventPayload.EVENT_CONTAINER_OPEN, "", Map.of());
        }
    }

    /** Drops the "already in a world" flag: the next level load after a login is a join again. */
    public void reset() {
        levelSeen = false;
    }

    private void publish(String factId, String dimensionId, Map<String, String> values) {
        NeoForgePresenceHooks.recordServerFact(new PresenceWorldEventPayload(
                null,
                factId,
                "",
                dimensionId == null ? "" : dimensionId,
                System.currentTimeMillis(),
                values));
    }
}