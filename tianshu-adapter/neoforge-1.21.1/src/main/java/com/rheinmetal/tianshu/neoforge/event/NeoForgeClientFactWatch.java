package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facts the client itself can observe exactly, without a server component.
 *
 * <p>Three of them live here because the client has the event and the server does not need to be involved: a level
 * being (re)built is a dimension change or a respawn, and opening a container screen is a container being opened.
 * Reporting them here means they keep working on a server that does not run Tianshu at all.</p>
 *
 * <p>The first level load after joining a world is the join itself, not a move, so it is swallowed once; logging out
 * drops that memory so the next join is a join again.</p>
 */
public final class NeoForgeClientFactWatch {

    private boolean levelSeen;

    public void register(IEventBus gameBus) {
        gameBus.addListener(this::onLevelLoad);
        gameBus.addListener(this::onPlayerClone);
        gameBus.addListener(this::onScreenOpening);
        // A new session starts from "not in a world yet": without this the next join would be reported as a move.
        gameBus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> reset());
    }

    public void onLevelLoad(LevelEvent.Load event) {
        if (!levelSeen) {
            levelSeen = true;
            return;
        }
        if (!(event.getLevel() instanceof ClientLevel level)) {
            return;
        }
        publish(PresenceWorldEventPayload.EVENT_DIMENSION_CHANGED, level, dimensionValues(level));
    }

    public void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        Level level = event.getNewPlayer() == null ? null : event.getNewPlayer().level();
        publish(PresenceWorldEventPayload.EVENT_RESPAWN, level, dimensionValues(level));
    }

    public void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof AbstractContainerScreen<?>) {
            publish(PresenceWorldEventPayload.EVENT_CONTAINER_OPEN, null, Map.of());
        }
    }

    /** Drops the "already in a world" flag: the next level load after a login is a join again. */
    public void reset() {
        levelSeen = false;
    }

    /**
     * The dimension a fact happened in, as both the id and the player's own name for it.
     *
     * <p>The event layer prefers the name and falls back to the id, so a missing translation degrades to a readable
     * identifier instead of a blank sentence. The name is resolved here, on the client, because only the client knows
     * the player's language.</p>
     */
    private static Map<String, String> dimensionValues(Level level) {
        if (level == null) {
            return Map.of();
        }
        ResourceLocation id = level.dimension().location();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PresenceWorldEventPayload.VALUE_DIMENSION, id.toString());
        values.put(PresenceWorldEventPayload.VALUE_DIMENSION_NAME,
                ClientLanguagePolicy.registryDisplayName(id, "dimension",
                        ClientLanguagePolicy.captureCurrentLanguageCode()));
        return values;
    }

    private void publish(String factId, Level level, Map<String, String> values) {
        NeoForgePresenceHooks.recordFact(new PresenceWorldEventPayload(
                null,
                factId,
                "",
                level == null ? "" : level.dimension().location().toString(),
                System.currentTimeMillis(),
                values));
    }
}
