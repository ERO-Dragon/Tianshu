package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.ArrayList;
import java.util.List;

final class IrVoiceTriggerIndexer {
    List<IrCompiledVoiceTrigger> compile(List<VoiceTriggerRegistration> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return List.of();
        }
        List<IrCompiledVoiceTrigger> compiled = new ArrayList<>(registrations.size());
        for (VoiceTriggerRegistration registration : registrations) {
            List<IrCompiledVoiceWord> wakeWords = compileWords(registration.wakeWords());
            List<IrCompiledVoiceWord> extraWords = compileWords(registration.extraWords());
            if (wakeWords.isEmpty() && extraWords.isEmpty()) {
                continue;
            }
            compiled.add(new IrCompiledVoiceTrigger(registration.moduleId(), wakeWords, extraWords, wakeWords.size() + extraWords.size()));
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
