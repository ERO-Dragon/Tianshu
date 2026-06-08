package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerDeliveryTarget;

import java.util.List;

record IrCompiledVoiceTrigger(String moduleId, List<IrCompiledVoiceWord> wakeWords, List<IrCompiledVoiceWord> extraWords, int totalWords, int priority, VoiceTriggerDeliveryTarget deliveryTarget) {
    IrCompiledVoiceTrigger {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        wakeWords = wakeWords == null || wakeWords.isEmpty() ? List.of() : List.copyOf(wakeWords);
        extraWords = extraWords == null || extraWords.isEmpty() ? List.of() : List.copyOf(extraWords);
        totalWords = Math.max(1, totalWords);
    }
}
