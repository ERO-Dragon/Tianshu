package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventTypes;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.Map;

/**
 * The client-observed world facts Fabric has a native event for.
 *
 * <p>Only one qualifies: a container screen being built. Fabric's screen event is sent by the screen itself, so
 * the host neither polls nor compares anything per tick — it reacts to a notification the game already sends.</p>
 *
 * <p>The facts that would need polling are deliberately absent rather than approximated: a dimension change and a
 * respawn have no Fabric client event, and a per-tick identity comparison to synthesise one is a cost paid every
 * tick for an event that happens rarely. Their consumers simply receive nothing on this loader (see
 * {@code DeliveryCoverageTest}, which lists the gaps as data so they cannot be forgotten).</p>
 */
final class FabricClientFactWatch {

    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> onScreenOpened(screen));
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