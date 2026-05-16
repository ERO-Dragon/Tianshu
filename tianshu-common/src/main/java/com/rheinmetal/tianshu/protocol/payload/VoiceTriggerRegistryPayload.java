package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandCategory;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandScope;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.List;

public record VoiceTriggerRegistryPayload(
        String moduleId,
        List<String> hotwords,
        List<String> commandWords,
        VoiceCommandCategory category,
        int priority,
        VoiceCommandScope scope,
        boolean dialogueEligible
) implements ITianshuPayload {
    public VoiceTriggerRegistryPayload(String moduleId, List<String> hotwords, List<String> extraWords) {
        this(moduleId, hotwords, extraWords, VoiceCommandCategory.GENERAL, 0, VoiceCommandScope.CLIENT, false);
    }

    public VoiceTriggerRegistryPayload {
        VoiceTriggerRegistration registration = new VoiceTriggerRegistration(moduleId, hotwords, commandWords, category, priority, scope, dialogueEligible);
        moduleId = registration.moduleId();
        hotwords = registration.hotwords();
        commandWords = registration.commandWords();
        category = registration.category();
        priority = registration.priority();
        scope = registration.scope();
        dialogueEligible = registration.dialogueEligible();
    }

    public List<String> extraWords() {
        return commandWords;
    }

    public VoiceTriggerRegistration toRegistration() {
        return new VoiceTriggerRegistration(moduleId, hotwords, commandWords, category, priority, scope, dialogueEligible);
    }
}
