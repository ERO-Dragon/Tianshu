package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.rheinmetal.tianshu.mc.event.WorldEventHooks;

/**
 * Turns "a boss bar appeared on screen" into a world event.
 *
 * <p>The boss bar is the only reliable client-side signal that a boss fight started — it is what the player sees, and
 * it exists for every boss regardless of which mod implemented it. The overlay event fires every frame while a bar is
 * visible, so the watch keeps the ids it has already reported and only speaks up the first time one appears; a bar
 * that comes back after its id was dropped is a new fight.</p>
 *
 * <p>Client-only class: it is registered from the client branch of the mod entry point and never loaded on a server.</p>
 */
public final class NeoForgeBossEventWatch {

    private static final Set<UUID> REPORTED = new LinkedHashSet<>();

    private NeoForgeBossEventWatch() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(NeoForgeBossEventWatch::onBossEventProgress);
        // Bars belong to one session: the next world must be able to report the same boss again.
        gameBus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> reset());
    }

    public static void onBossEventProgress(CustomizeGuiOverlayEvent.BossEventProgress event) {
        if (event == null || event.getBossEvent() == null) {
            return;
        }
        UUID id = event.getBossEvent().getId();
        if (id == null || !REPORTED.add(id)) {
            return;
        }
        WorldEventHooks.publish(new WorldEventPayload(
                WorldEventTypes.EVENT_BOSS_ENGAGE,
                "",
                "",
                Map.of(WorldEventTypes.VALUE_ENTITY_NAME, event.getBossEvent().getName().getString()),
                System.currentTimeMillis()
        ));
    }

    /** Drops the reported ids: a new world must be able to report the same boss again. */
    public static void reset() {
        REPORTED.clear();
    }
}
