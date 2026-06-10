package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class IrVoiceTriggerMatcher {
    IrMatchBatch match(IrInputText input, List<IrCompiledVoiceTrigger> index) {
        if (input == null || input.blank()) {
            return new IrMatchBatch(input, List.of());
        }
        String tokenText = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(input.text()));
        if (tokenText.isBlank() || index == null || index.isEmpty()) {
            return new IrMatchBatch(input, List.of());
        }
        List<IrVoiceMatch> matches = new ArrayList<>();
        for (IrCompiledVoiceTrigger trigger : index) {
            List<String> matchedWakeWords = collectTokenMatches(tokenText, trigger.wakeWords());
            List<String> matchedExtraWords = collectTokenMatches(tokenText, trigger.extraWords());
            if (matchedWakeWords.isEmpty() && matchedExtraWords.isEmpty()) {
                continue;
            }
            matches.add(new IrVoiceMatch(trigger.moduleId(), matchedWakeWords, matchedExtraWords, voiceTriggerConfidence(trigger, matchedWakeWords, matchedExtraWords), trigger.priority()));
        }
        matches.sort(Comparator
                .comparingDouble(IrVoiceMatch::confidence).reversed()
                .thenComparing(IrVoiceMatch::priority, Comparator.reverseOrder())
                .thenComparing(IrVoiceMatch::moduleId));
        return new IrMatchBatch(input, matches);
    }

    private List<String> collectTokenMatches(String tokenText, List<IrCompiledVoiceWord> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (IrCompiledVoiceWord word : words) {
            if (!word.normalizedText().isBlank() && tokenText.contains(word.normalizedText())) {
                matches.add(word.rawText());
            }
        }
        return matches;
    }

    private double voiceTriggerConfidence(IrCompiledVoiceTrigger trigger, List<String> matchedWakeWords, List<String> matchedExtraWords) {
        int matched = matchedWakeWords.size() + matchedExtraWords.size();
        return Math.min(1.0D, matched / (double) trigger.totalWords());
    }
}
