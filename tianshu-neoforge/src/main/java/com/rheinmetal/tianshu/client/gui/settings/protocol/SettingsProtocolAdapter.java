package com.rheinmetal.tianshu.client.gui.settings.protocol;

import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.SettingsEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class SettingsProtocolAdapter extends AbstractProtocolAdapter implements SettingsEventPublisher {
    public static final String MODULE_ID = "client.settings";
    public static final String SOURCE_ID = "client.settings";

    public SettingsProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.mainThreadUi());
    }

    @Override
    public void publishSaved(SettingsSaveEvent event) {
        publish("saved", event.moduleId(), event.allModules(), event.success(), event.savedAny(), event.requiresRestart(), event.requiresReload(), event.message());
    }

    @Override
    public void publishReset(SettingsResetEvent event) {
        publish("reset", event.moduleId(), false, event.success(), false, false, false, event.message());
    }

    @Override
    public void publishValidationFailed(SettingsValidationFailureEvent event) {
        publish("validation_failed", event.moduleId(), event.allModules(), false, false, false, false, event.message());
    }

    private TianshuEnvelope publish(String action, String moduleId, boolean allModules, boolean success, boolean savedAny, boolean requiresRestart, boolean requiresReload, String message) {
        return publishTopic(ProtocolTopics.SETTINGS_EVENT, PayloadType.CUSTOM, new SettingsEventPayload(action, moduleId, allModules, success, savedAny, requiresRestart, requiresReload, message));
    }
}
