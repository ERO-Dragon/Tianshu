package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.Map;

/**
 * The client-observed world facts Fabric has a native event for.
 *
 * <p>Two facts qualify: a container screen being built, and the dimension changing. The screen event is sent by the
 * screen itself, so that one is a notification the game already sends; the dedicated level-change event only exists
 * from lifecycle 2.5 onward and is renamed in 26.x, so this whole pre-26 range uses one per-tick reference
 * comparison instead of branching per line, and publishes only when the reference really changed.</p>
 *
 * <p>Boss engagement has no Fabric hook at all — the overlay exposes no accessor for the bars it is tracking — so
 * it is deliberately absent rather than approximated, and its consumers receive nothing on this loader.</p>
 */
final class FabricClientFactWatch {

    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> onScreenOpened(screen));
        ClientTickEvents.END_CLIENT_TICK.register(FabricClientFactWatch::onClientTick);
    }

    /**
     * The dimension the client is in, compared by reference once per tick.
     *
     * <p>fabric-api 0.102.0 for 1.21 still ships lifecycle 2.3.x, which has no client world-change event; this
     * line therefore uses one identity check per tick and publishes only when the reference actually changes.</p>
     */
    private static ClientLevel lastLevel;

    private static void onClientTick(net.minecraft.client.Minecraft client) {
        ClientLevel level = client.level;
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;
        if (level == null) {
            return;
        }
        var dimension = level.dimension().location();
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