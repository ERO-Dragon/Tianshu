package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.intake.repair.core.IRBaseUtils;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistration;

import java.util.ArrayList;
import java.util.List;

final class IrTriggerIndexer {
    List<IrCompiledTrigger> compile(List<TriggerRegistration> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return List.of();
        }
        List<IrCompiledTrigger> compiled = new ArrayList<>(registrations.size());
        for (TriggerRegistration registration : registrations) {
            List<IrCompiledVoiceWord> wakeWords = compileWords(registration.wakeWords());
            List<IrCompiledVoiceWord> extraWords = compileWords(registration.extraWords());
            if (wakeWords.isEmpty() && extraWords.isEmpty()) {
                continue;
            }
            compiled.add(new IrCompiledTrigger(registration.moduleId(), wakeWords, extraWords, wakeWords.size() + extraWords.size()));
        }
        return List.copyOf(compiled);
    }

    private List<IrCompiledVoiceWord> compileWords(List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<IrCompiledVoiceWord> compiled = new ArrayList<>(words.size());
        for (String word : words) {
            String normalized = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(word));
            if (!normalized.isBlank()) {
                compiled.add(new IrCompiledVoiceWord(word, normalized));
            }
        }
        return List.copyOf(compiled);
    }
}
