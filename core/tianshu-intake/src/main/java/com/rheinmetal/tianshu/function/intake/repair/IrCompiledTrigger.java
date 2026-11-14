package com.rheinmetal.tianshu.function.intake.repair;

import java.util.List;

record IrCompiledTrigger(String moduleId, List<IrCompiledVoiceWord> wakeWords, List<IrCompiledVoiceWord> extraWords, int totalWords) {
    IrCompiledTrigger {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        wakeWords = wakeWords == null || wakeWords.isEmpty() ? List.of() : List.copyOf(wakeWords);
        extraWords = extraWords == null || extraWords.isEmpty() ? List.of() : List.copyOf(extraWords);
        totalWords = Math.max(1, totalWords);
    }
}
