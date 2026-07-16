package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandCategory;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandScope;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerDeliveryTarget;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.List;

public record VoiceTriggerRegistryPayload(
        String moduleId,
        List<String> wakeWords,
        List<String> commandWords,
        VoiceCommandCategory category,
        VoiceCommandScope scope,
        boolean dialogueEligible,
        VoiceTriggerDeliveryTarget deliveryTarget
) implements ITianshuPayload {
    public VoiceTriggerRegistryPayload(String moduleId, List<String> wakeWords, List<String> extraWords) {
        this(moduleId, wakeWords, extraWords, VoiceCommandCategory.GENERAL, VoiceCommandScope.CLIENT, false, null);
    }

    public VoiceTriggerRegistryPayload(String moduleId, List<String> wakeWords, List<String> extraWords, VoiceCommandCategory category, VoiceCommandScope scope, boolean dialogueEligible) {
        this(moduleId, wakeWords, extraWords, category, scope, dialogueEligible, null);
    }

    public VoiceTriggerRegistryPayload {
        VoiceTriggerRegistration registration = new VoiceTriggerRegistration(moduleId, wakeWords, commandWords, category, scope, dialogueEligible, deliveryTarget);
        moduleId = registration.moduleId();
        wakeWords = registration.wakeWords();
        commandWords = registration.commandWords();
        category = registration.category();
        scope = registration.scope();
        dialogueEligible = registration.dialogueEligible();
        deliveryTarget = registration.deliveryTarget();
    }

    public List<String> extraWords() {
        return commandWords;
    }

    public VoiceTriggerRegistration toRegistration() {
        return new VoiceTriggerRegistration(moduleId, wakeWords, commandWords, category, scope, dialogueEligible, deliveryTarget);
    }
}
