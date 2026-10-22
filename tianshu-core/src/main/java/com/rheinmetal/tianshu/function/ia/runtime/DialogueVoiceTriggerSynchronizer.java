package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimMode;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandCategory;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandScope;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DialogueVoiceTriggerSynchronizer {
    private final Set<String> syncedModules = new LinkedHashSet<>();
    private VoiceTriggerRegistry registry;

    public synchronized void bind(VoiceResourceAccess resources, List<DialogueParticipantDescriptor> participants) {
        VoiceTriggerRegistry nextRegistry = resources == null ? null : resources.voiceTriggers();
        if (registry != nextRegistry) {
            unregisterAll();
            registry = nextRegistry;
        }
        synchronizeBoundRegistry(participants);
    }

    public synchronized void synchronize(List<DialogueParticipantDescriptor> participants) {
        if (registry == null) {
            return;
        }
        synchronizeBoundRegistry(participants);
    }

    public synchronized void unbind() {
        unregisterAll();
        registry = null;
    }

    private void synchronizeBoundRegistry(List<DialogueParticipantDescriptor> participants) {
        if (registry == null) {
            return;
        }
        Map<String, TriggerWords> desiredTriggers = triggersByModule(participants);
        for (String syncedModule : Set.copyOf(syncedModules)) {
            if (!desiredTriggers.containsKey(syncedModule)) {
                registry.unregisterModule(syncedModule);
                syncedModules.remove(syncedModule);
            }
        }
        for (Map.Entry<String, TriggerWords> entry : desiredTriggers.entrySet()) {
            TriggerWords words = entry.getValue();
            registry.register(new VoiceTriggerRegistration(
                    entry.getKey(),
                    words.wakeWords(),
                    words.extraWords(),
                    VoiceCommandCategory.GENERAL,
                    VoiceCommandScope.CLIENT,
                    true
            ));
            syncedModules.add(entry.getKey());
        }
    }

    private void unregisterAll() {
        if (registry != null) {
            for (String moduleId : Set.copyOf(syncedModules)) {
                registry.unregisterModule(moduleId);
            }
        }
        syncedModules.clear();
    }

    private Map<String, TriggerWords> triggersByModule(List<DialogueParticipantDescriptor> participants) {
        if (participants == null || participants.isEmpty()) {
            return Map.of();
        }
        Map<String, TriggerAccumulator> accumulators = new LinkedHashMap<>();
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant == null || participant.moduleId().isBlank()) {
                continue;
            }
            TriggerWords words = triggerWordsFor(participant);
            if (words.empty()) {
                continue;
            }
            accumulators.computeIfAbsent(participant.moduleId(), ignored -> new TriggerAccumulator())
                    .add(words);
        }
        Map<String, TriggerWords> result = new LinkedHashMap<>();
        for (Map.Entry<String, TriggerAccumulator> entry : accumulators.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toWords());
        }
        return result;
    }

    private TriggerWords triggerWordsFor(DialogueParticipantDescriptor participant) {
        LinkedHashSet<String> wakeWords = new LinkedHashSet<>(claimWakeWords(participant));
        LinkedHashSet<String> extraWords = new LinkedHashSet<>();
        DialogueVoiceTriggerGroup group = participant.voiceTriggerGroup();
        if (group != null) {
            wakeWords.addAll(group.wakeWords());
            extraWords.addAll(group.extraWords());
        }
        return new TriggerWords(List.copyOf(wakeWords), List.copyOf(extraWords));
    }

    private List<String> claimWakeWords(DialogueParticipantDescriptor descriptor) {
        if (descriptor.claimProfile() == null || descriptor.claimProfile().mode() != DialogueClaimMode.RULES) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        descriptor.claimProfile().rules().forEach(rule -> {
            if (rule == null || rule.conditions().isEmpty()) {
                return;
            }
            for (DialogueClaimCondition condition : rule.conditions()) {
                if (condition != null && condition.type() == DialogueClaimConditionType.WAKE_WORD) {
                    words.addAll(condition.values());
                }
            }
        });
        return words.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static final class TriggerAccumulator {
        private final LinkedHashSet<String> wakeWords = new LinkedHashSet<>();
        private final LinkedHashSet<String> extraWords = new LinkedHashSet<>();
        private void add(TriggerWords words) {
            wakeWords.addAll(words.wakeWords());
            extraWords.addAll(words.extraWords());
        }

        private TriggerWords toWords() {
            return new TriggerWords(List.copyOf(wakeWords), List.copyOf(extraWords));
        }
    }

    private record TriggerWords(List<String> wakeWords, List<String> extraWords) {
        private boolean empty() {
            return wakeWords.isEmpty() && extraWords.isEmpty();
        }
    }
}
