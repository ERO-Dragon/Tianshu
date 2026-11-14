package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.intake.repair.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputText;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class IrTriggerMatcher {
    IrMatchBatch match(IrInputText input, List<IrCompiledTrigger> index) {
        if (input == null || input.blank()) {
            return new IrMatchBatch(input, List.of());
        }
        String[] tokens = IRBaseUtils.tokenize(input.text());
        if (tokens.length == 0 || index == null || index.isEmpty()) {
            return new IrMatchBatch(input, List.of());
        }
        List<TriggerHits> hits = new ArrayList<>();
        for (IrCompiledTrigger trigger : index) {
            List<String> wakeWords = collectTokenMatches(tokens, trigger.wakeWords());
            List<String> extraWords = collectTokenMatches(tokens, trigger.extraWords());
            if (wakeWords.isEmpty() && extraWords.isEmpty()) {
                continue;
            }
            // Wake words and auxiliary words stay separate all the way out: only a wake word may satisfy a
            // wake-word condition, and merging them here silently widened that condition.
            hits.add(new TriggerHits(trigger.moduleId(), List.copyOf(wakeWords), List.copyOf(extraWords),
                    triggerConfidence(trigger, wakeWords.size() + extraWords.size())));
        }
        // A module that matched more of its words explains the utterance better, so its hits come first. The score
        // orders the evidence and stays here: publishing it invites a consumer to invent a threshold, which is
        // exactly what the ordering already answers.
        hits.sort(Comparator
                .comparingDouble(TriggerHits::confidence).reversed()
                .thenComparing(TriggerHits::moduleId));
        List<IrModuleMatch> matches = new ArrayList<>();
        for (TriggerHits triggerHits : hits) {
            for (String word : triggerHits.wakeWords()) {
                matches.add(new IrModuleMatch(triggerHits.moduleId(), word, word, IrModuleMatch.KIND_WAKE));
            }
            for (String word : triggerHits.extraWords()) {
                matches.add(new IrModuleMatch(triggerHits.moduleId(), word, word, IrModuleMatch.KIND_EXTRA));
            }
        }
        return new IrMatchBatch(input, matches);
    }

    private List<String> collectTokenMatches(String[] tokens, List<IrCompiledVoiceWord> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (IrCompiledVoiceWord word : words) {
            if (!word.isEmpty() && containsSequence(tokens, word.tokens())) {
                matches.add(word.rawText());
            }
        }
        return matches;
    }

    /** Whether {@code needle} appears as a contiguous run of tokens inside {@code haystack}. */
    private static boolean containsSequence(String[] haystack, String[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        int lastStart = haystack.length - needle.length;
        for (int start = 0; start <= lastStart; start++) {
            boolean matched = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (!haystack[start + offset].equals(needle[offset])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private double triggerConfidence(IrCompiledTrigger trigger, int matchedWords) {
        return Math.min(1.0D, matchedWords / (double) trigger.totalWords());
    }

    /** One module's hits, kept together so the score can order them without travelling into the published evidence. */
    private record TriggerHits(String moduleId, List<String> wakeWords, List<String> extraWords, double confidence) {
    }
}