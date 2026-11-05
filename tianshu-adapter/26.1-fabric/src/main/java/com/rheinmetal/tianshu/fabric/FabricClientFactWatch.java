package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.Map;

/**
 * The client-observed world facts Fabric has a native event for.
 *
 * <p>Two facts qualify, both from native events: a container screen being built (the screen's own notification) and
 * the client level changing ({@code ClientLevelEvents}).</p>
 *
 * <p>Boss engagement has no Fabric hook at all — the overlay exposes no accessor for the bars it is tracking — so
 * it is deliberately absent rather than approximated, and its consumers receive nothing on this loader.</p>
 */
final class FabricClientFactWatch {

    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> onScreenOpened(screen));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> onLevelChange(level));
    }

    /** The login level is not a dimension change; only later level loads are published. */
    private static boolean levelSeen;

    private static void onLevelChange(ClientLevel level) {
        if (!levelSeen) {
            levelSeen = true;
            return;
        }
        var dimension = level.dimension().identifier();
        WorldEventHooks.publish(new WorldEventPayload(
                WorldEventTypes.EVENT_DIMENSION_CHANGED,
                dimension.toString(),
                dimension.getPath(),
                Map.of(),
                System.currentTimeMillis()));
    }

    private static void onScreenOpened(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return;
        }
        WorldEventHooks.publish(new WorldEventPayload(
                WorldEventTypes.EVENT_CONTAINER_OPEN,
                "",
                "",
                Map.of(),
                System.currentTimeMillis()
        ));
    }

    private FabricClientFactWatch() {
    }
}