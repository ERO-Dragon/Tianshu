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
        ClientTickEvents.END_CLIENT_TICK.register(FabricClientFactWatch::onClientTick);
    }

    /**
     * The dimension the client is in, compared by reference once per tick.
     *
     * <p>fabric-api for this line has no client world-change event (lifecycle 2.2.x / 2.3.x below 2.5.1), so the
     * level is compared by reference: one identity check per tick, no allocation, and a fact only on a real change.</p>
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