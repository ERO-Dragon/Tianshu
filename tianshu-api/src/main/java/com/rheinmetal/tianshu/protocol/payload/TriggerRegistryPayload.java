package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.trigger.TriggerCategory;
import com.rheinmetal.tianshu.protocol.trigger.TriggerScope;
import com.rheinmetal.tianshu.protocol.trigger.TriggerDeliveryTarget;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistration;

import java.util.List;

public record TriggerRegistryPayload(
        String moduleId,
        List<String> wakeWords,
        List<String> commandWords,
        TriggerCategory category,
        TriggerScope scope,
        boolean dialogueEligible,
        TriggerDeliveryTarget deliveryTarget
) implements ITianshuPayload {
    public TriggerRegistryPayload(String moduleId, List<String> wakeWords, List<String> extraWords) {
        this(moduleId, wakeWords, extraWords, TriggerCategory.GENERAL, TriggerScope.CLIENT, false, null);
    }

    public TriggerRegistryPayload(String moduleId, List<String> wakeWords, List<String> extraWords, TriggerCategory category, TriggerScope scope, boolean dialogueEligible) {
        this(moduleId, wakeWords, extraWords, category, scope, dialogueEligible, null);
    }

    public TriggerRegistryPayload {
        TriggerRegistration registration = new TriggerRegistration(moduleId, wakeWords, commandWords, category, scope, dialogueEligible, deliveryTarget);
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

    public TriggerRegistration toRegistration() {
        return new TriggerRegistration(moduleId, wakeWords, commandWords, category, scope, dialogueEligible, deliveryTarget);
    }
}
