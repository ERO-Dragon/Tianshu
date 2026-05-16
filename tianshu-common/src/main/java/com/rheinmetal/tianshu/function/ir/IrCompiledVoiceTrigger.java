package com.rheinmetal.tianshu.function.ir;

import java.util.List;

record IrCompiledVoiceTrigger(String moduleId, List<IrCompiledVoiceWord> hotwords, List<IrCompiledVoiceWord> extraWords, int totalWords) {
    IrCompiledVoiceTrigger {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        hotwords = hotwords == null || hotwords.isEmpty() ? List.of() : List.copyOf(hotwords);
        extraWords = extraWords == null || extraWords.isEmpty() ? List.of() : List.copyOf(extraWords);
        totalWords = Math.max(1, totalWords);
    }
}
