package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerDeliveryTarget;

import java.util.List;

record IrCompiledVoiceTrigger(String moduleId, List<IrCompiledVoiceWord> hotwords, List<IrCompiledVoiceWord> extraWords, int totalWords, int priority, VoiceTriggerDeliveryTarget deliveryTarget) {
    IrCompiledVoiceTrigger {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        hotwords = hotwords == null || hotwords.isEmpty() ? List.of() : List.copyOf(hotwords);
        extraWords = extraWords == null || extraWords.isEmpty() ? List.of() : List.copyOf(extraWords);
        totalWords = Math.max(1, totalWords);
    }
}
