package com.rheinmetal.tianshu.protocol.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class VoiceTriggerRegistry {
    private final Map<String, VoiceTriggerRegistration> registrations = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public VoiceTriggerRegistrationResult register(VoiceTriggerRegistration registration) {
        synchronized (this) {
            registrations.put(registration.moduleId(), registration);
            VoiceTriggerRegistrationResult result = VoiceTriggerRegistrationResult.accepted(registration.moduleId(), conflictsFor(registration));
            notifyChanged();
            return result;
        }
    }

    public void unregisterModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        synchronized (this) {
            registrations.remove(moduleId.trim());
        }
        notifyChanged();
    }

    public void addChangeListener(Runnable changeListener) {
        if (changeListener != null) {
            changeListeners.addIfAbsent(changeListener);
        }
    }

    public void removeChangeListener(Runnable changeListener) {
        if (changeListener != null) {
            changeListeners.remove(changeListener);
        }
    }

    public void setChangeListener(Runnable changeListener) {
        changeListeners.clear();
        addChangeListener(changeListener);
    }

    public synchronized List<VoiceTriggerRegistration> registrations() {
        return List.copyOf(registrations.values());
    }

    public synchronized List<String> asrHotwords() {
        Set<String> words = new LinkedHashSet<>();
        for (VoiceTriggerRegistration registration : registrations.values()) {
            words.addAll(registration.wakeWords());
            words.addAll(registration.extraWords());
        }
        return List.copyOf(words);
    }

    public synchronized List<VoiceTriggerConflict> conflicts() {
        return collectConflicts(registrations.values().stream().toList());
    }

    public synchronized List<VoiceTriggerMatch> match(String text) {
        String normalizedText = TextListNormalizer.normalizeText(text);
        if (normalizedText.isEmpty()) {
            return List.of();
        }
        List<VoiceTriggerMatch> matches = new ArrayList<>();
        for (VoiceTriggerRegistration registration : registrations.values()) {
            List<String> matchedWakeWords = TextListNormalizer.collectMatches(normalizedText, registration.wakeWords());
            List<String> matchedExtraWords = TextListNormalizer.collectMatches(normalizedText, registration.extraWords());
            if (matchedWakeWords.isEmpty() && matchedExtraWords.isEmpty()) {
                continue;
            }
            double confidence = confidence(registration, matchedWakeWords, matchedExtraWords);
            matches.add(new VoiceTriggerMatch(registration.moduleId(), matchedWakeWords, matchedExtraWords, confidence));
        }
        return List.copyOf(matches);
    }

    private List<VoiceTriggerConflict> conflictsFor(VoiceTriggerRegistration registration) {
        if (registration == null) {
            return List.of();
        }
        return collectConflicts(registrations.values().stream()
                .filter(candidate -> candidate.moduleId().equals(registration.moduleId()) || sharesWord(candidate, registration))
                .toList());
    }

    private boolean sharesWord(VoiceTriggerRegistration left, VoiceTriggerRegistration right) {
        Set<String> rightWords = new LinkedHashSet<>();
        rightWords.addAll(right.wakeWords());
        rightWords.addAll(right.extraWords());
        for (String word : left.wakeWords()) {
            if (rightWords.contains(word)) return true;
        }
        for (String word : left.extraWords()) {
            if (rightWords.contains(word)) return true;
        }
        return false;
    }

    private List<VoiceTriggerConflict> collectConflicts(List<VoiceTriggerRegistration> registrations) {
        Map<String, List<String>> ownersByWord = new LinkedHashMap<>();
        for (VoiceTriggerRegistration registration : registrations) {
            Set<String> words = new LinkedHashSet<>();
            words.addAll(registration.wakeWords());
            words.addAll(registration.extraWords());
            for (String word : words) {
                ownersByWord.computeIfAbsent(word, ignored -> new ArrayList<>()).add(registration.moduleId());
            }
        }
        List<VoiceTriggerConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ownersByWord.entrySet()) {
            VoiceTriggerConflict conflict = new VoiceTriggerConflict(entry.getKey(), entry.getValue());
            if (conflict.valid()) {
                conflicts.add(conflict);
            }
        }
        return List.copyOf(conflicts);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private static double confidence(VoiceTriggerRegistration registration, List<String> matchedWakeWords, List<String> matchedExtraWords) {
        int total = Math.max(1, registration.wakeWords().size() + registration.extraWords().size());
        double score = matchedWakeWords.size() * 2.0D + matchedExtraWords.size();
        return Math.min(1.0D, score / Math.max(2.0D, total));
    }
}
