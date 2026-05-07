package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.List;

public record VoiceTriggerRegistryPayload(String moduleId, List<String> hotwords, List<String> extraWords) implements ITianshuPayload {
    public VoiceTriggerRegistryPayload {
        VoiceTriggerRegistration registration = new VoiceTriggerRegistration(moduleId, hotwords, extraWords);
        moduleId = registration.moduleId();
        hotwords = registration.hotwords();
        extraWords = registration.extraWords();
    }

    public VoiceTriggerRegistration toRegistration() {
        return new VoiceTriggerRegistration(moduleId, hotwords, extraWords);
    }
}
