package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextProvider;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.render.PresenceHudRenderer;
import com.rheinmetal.tianshu.client.presence.render.PresenceRenderer;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class PresenceClientRuntime {
    private final PresenceStateStore stateStore = new PresenceStateStore();
    private final PresenceDisplayPolicy displayPolicy = new PresenceDisplayPolicy();
    private final PresenceContextFactMapper contextFactMapper = new PresenceContextFactMapper();
    private final PresenceEventCollector eventCollector = new PresenceEventCollector(stateStore);
    private final PresenceContextProvider contextProvider = new PresenceContextProvider(stateStore);
    private final PresenceRenderer renderer = new PresenceHudRenderer(stateStore, displayPolicy);

    public DialogueContextProvider contextProvider() {
        return contextProvider;
    }

    public PresenceContextSnapshot contextSnapshot() {
        return stateStore.contextSnapshot();
    }

    public TianshuFunctionModuleInstaller moduleInstaller(ProtocolRuntime protocolRuntime) {
        PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(protocolRuntime);
        eventCollector.setWorldEventSink(adapter::publishWorldEvent);
        eventCollector.setChatMessageSink(adapter::publishChatMessage);
        return new PresenceModuleInstaller(adapter, stateStore, displayPolicy, contextFactMapper);
    }

    public void tick() {
        eventCollector.tick();
    }

    public void recordScreenChanged(Screen screen) {
        eventCollector.recordScreenChanged(screen);
    }

    public void recordVoiceKeyInput() {
        eventCollector.recordVoiceKeyInput();
    }

    public void recordKeyboardInput() {
        eventCollector.recordKeyboardInput();
    }

    public void recordMouseInput() {
        eventCollector.recordMouseInput();
    }

    public void recordPlayerChatMessage(Component message, UUID senderId, String senderName) {
        eventCollector.recordPlayerChatMessage(message, senderId, senderName);
    }

    public void recordAdvancementUpdate(net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket packet) {
        eventCollector.recordAdvancementUpdate(packet);
    }

    public void render(GuiGraphics graphics, float partialTick) {
        renderer.render(graphics, partialTick);
    }
}
