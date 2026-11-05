package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.mc.event.ClientEvents;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * NeoForge's delivery of the events; the decisions live in {@link ClientEvents}.
 *
 * <p>Every method here is one loader fact turned into one shared call. Keeping the bodies out of this class is
 * what makes the second loader cheap: its own adapter is the same shape, and a change to what the host does on
 * a tick cannot land in only one of them.</p>
 */
public final class NeoForgeClientEvents {

    private final ClientEvents events;

    public NeoForgeClientEvents(ClientEvents events) {
        this.events = java.util.Objects.requireNonNull(events, "events");
    }

    @SubscribeEvent
    public void onWorldLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        events.onWorldLogin();
    }

    @SubscribeEvent
    public void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        events.onPlayerClone();
    }

    @SubscribeEvent
    public void onWorldLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        events.onWorldLogout();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        events.onClientTick();
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        events.onScreenInit(screen, event::addListener);
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        events.onRenderGui(event.getGuiGraphics());
    }

    /**
     * Only a real player line becomes a world event: a system message is the server talking, not a participant,
     * and the shared layer drops a line without a sender anyway.
     */
    @SubscribeEvent
    public void onClientChatReceived(ClientChatReceivedEvent event) {
        if (!(event instanceof ClientChatReceivedEvent.Player) || event.isSystem()) {
            return;
        }
        String senderId = event.getSender() == null ? "" : event.getSender().toString();
        events.onChatMessage(event.getMessage().getString(), senderId, senderName(event));
    }

    public void resetVoiceInputState() {
        events.resetVoiceInputState();
    }

    private static String senderName(ClientChatReceivedEvent event) {
        if (event.getBoundChatType() == null || event.getBoundChatType().name() == null) {
            return "";
        }
        return event.getBoundChatType().name().getString();
    }
}
