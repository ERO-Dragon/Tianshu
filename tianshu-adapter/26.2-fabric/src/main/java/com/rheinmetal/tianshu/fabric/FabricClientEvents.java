package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.mc.event.ClientEvents;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.resources.Identifier;

/**
 * Fabric's delivery of the events; the decisions live in {@link ClientEvents}.
 *
 * <p>Every callback here is one loader fact turned into one shared call. The one gap is the player-clone callback:
 * Fabric has no equivalent, and the shared layer already refreshes a stale world identity on the next tick, so
 * the world facts follow the player rather than the event.</p>
 */
final class FabricClientEvents {

    /** The HUD layer this mod adds; Fabric now identifies every HUD element by an id so packs and mods can order it. */
    private static final Identifier HUD_LAYER = Identifier.fromNamespaceAndPath(ModIdentity.MOD_ID, "hud");

    static void register(ClientEvents events) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> events.onWorldLogin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> events.onWorldLogout());
        ClientTickEvents.END_CLIENT_TICK.register(client -> events.onClientTick());
        // AFTER_INIT runs once the screen has built its widgets, which is exactly what the pause-menu slot needs.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                events.onScreenInit(screen, widget -> FabricScreens.add(screen, widget)));
        // Fabric replaced the single HudRenderCallback with the ordered HudElementRegistry: this layer is drawn last,
        // which is where a HUD overlay belongs.
        HudElementRegistry.addLast(HUD_LAYER, (graphics, deltaTracker) -> events.onRenderGui(graphics));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                events.onChatMessage(
                        message.getString(),
                        // 1.21.9 ships authlib 7, where GameProfile is a record: id() replaced getId().
                        sender == null ? "" : sender.id().toString(),
                        params == null ? "" : params.name().getString()));
    }

    private FabricClientEvents() {
    }
}
