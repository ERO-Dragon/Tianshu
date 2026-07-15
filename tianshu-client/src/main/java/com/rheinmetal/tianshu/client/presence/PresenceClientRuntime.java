package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.Objects;
import java.util.List;

public final class PresenceClientRuntime {
    private final PresenceStateStore stateStore = new PresenceStateStore();
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextFactMapper contextFactMapper;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresenceEventCollector eventCollector;

    public PresenceClientRuntime(ClientGameContextProvider platform, PresenceTextProvider textProvider) {
        PresenceTextProvider effectiveTextProvider = textProvider == null ? PresenceTextProvider.NOOP : textProvider;
        displayPolicy = new PresenceDisplayPolicy(effectiveTextProvider);
        contextFactMapper = new PresenceContextFactMapper(effectiveTextProvider);
        contextQueryCoordinator = new PresenceContextQueryCoordinator(stateStore, contextFactMapper);
        eventCollector = new PresenceEventCollector(stateStore, Objects.requireNonNull(platform, "platform"));
    }

    public PresenceContextSnapshot contextSnapshot() {
        return stateStore.contextSnapshot();
    }

    public TianshuFunctionModuleInstaller moduleInstaller(ModuleRuntimeAccess moduleRuntime) {
        PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(moduleRuntime);
        contextQueryCoordinator.bindAdapter(adapter);
        eventCollector.setWorldEventSink(adapter::publishWorldEvent);
        eventCollector.setChatMessageSink(adapter::publishChatMessage);
        return new PresenceModuleInstaller(adapter, stateStore, displayPolicy, contextFactMapper, contextQueryCoordinator);
    }

    public void tick() {
        contextQueryCoordinator.processPending(eventCollector);
    }

    public void recordScreenChanged() {
        eventCollector.recordScreenChanged();
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

    public void recordPlayerChatMessage(String messageText, String senderId, String senderName) {
        eventCollector.recordPlayerChatMessage(messageText, senderId, senderName);
    }

    public void recordWorldEvents(List<PresenceWorldEventPayload> events) {
        eventCollector.recordWorldEvents(events);
    }

    public PresenceHudDisplay currentHudDisplay() {
        return displayPolicy.hudDisplay(stateStore.statusSnapshot());
    }
}
