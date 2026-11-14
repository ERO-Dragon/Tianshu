package com.rheinmetal.tianshu.protocol.trigger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TriggerRegistry implements TriggerAccess {
    private final Map<String, TriggerRegistration> registrations = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public TriggerRegistrationResult register(TriggerRegistration registration) {
        TriggerRegistrationResult result;
        synchronized (this) {
            registrations.put(registration.moduleId(), registration);
            result = TriggerRegistrationResult.accepted(registration.moduleId(), conflictsFor(registration));
        }
        notifyChanged();
        return result;
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

    public synchronized List<TriggerRegistration> registrations() {
        return List.copyOf(registrations.values());
    }

    public synchronized List<String> registeredWords() {
        Set<String> words = new LinkedHashSet<>();
        for (TriggerRegistration registration : registrations.values()) {
            words.addAll(registration.wakeWords());
            words.addAll(registration.extraWords());
        }
        return List.copyOf(words);
    }

    public synchronized List<TriggerConflict> conflicts() {
        return collectConflicts(registrations.values().stream().toList());
    }

    private List<TriggerConflict> conflictsFor(TriggerRegistration registration) {
        if (registration == null) {
            return List.of();
        }
        return collectConflicts(registrations.values().stream()
                .filter(candidate -> candidate.moduleId().equals(registration.moduleId()) || sharesWord(candidate, registration))
                .toList());
    }

    private boolean sharesWord(TriggerRegistration left, TriggerRegistration right) {
        Set<String> rightWords = new LinkedHashSet<>();
        rightWords.addAll(right.wakeWords());
        rightWords.addAll(right.extraWords());
        for (String word : left.wakeWords()) {
            if (overlapsAny(word, rightWords)) return true;
        }
        for (String word : left.extraWords()) {
            if (overlapsAny(word, rightWords)) return true;
        }
        return false;
    }

    private List<TriggerConflict> collectConflicts(List<TriggerRegistration> registrations) {
        Map<String, List<String>> ownersByWord = new LinkedHashMap<>();
        for (TriggerRegistration registration : registrations) {
            Set<String> words = new LinkedHashSet<>();
            words.addAll(registration.wakeWords());
            words.addAll(registration.extraWords());
            for (String word : words) {
                ownersByWord.computeIfAbsent(word, ignored -> new ArrayList<>()).add(registration.moduleId());
            }
        }
        List<TriggerConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ownersByWord.entrySet()) {
            TriggerConflict conflict = new TriggerConflict(entry.getKey(), entry.getValue());
            if (conflict.valid()) {
                conflicts.add(conflict);
            }
        }
        conflicts.addAll(collectContainmentConflicts(registrations));
        return List.copyOf(conflicts);
    }

    private List<TriggerConflict> collectContainmentConflicts(List<TriggerRegistration> registrations) {
        if (registrations == null || registrations.size() < 2) {
            return List.of();
        }
        List<TriggerConflict> conflicts = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (int i = 0; i < registrations.size(); i++) {
            TriggerRegistration left = registrations.get(i);
            for (int j = i + 1; j < registrations.size(); j++) {
                TriggerRegistration right = registrations.get(j);
                for (String leftWord : triggerWords(left)) {
                    for (String rightWord : triggerWords(right)) {
                        if (!containsEither(leftWord, rightWord) || sameNormalized(leftWord, rightWord)) {
                            continue;
                        }
                        String label = conflictLabel(leftWord, rightWord);
                        String key = TextListNormalizer.normalizeText(label) + "|" + left.moduleId() + "|" + right.moduleId();
                        if (emitted.add(key)) {
                            conflicts.add(new TriggerConflict(label, List.of(left.moduleId(), right.moduleId())));
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    private static List<String> triggerWords(TriggerRegistration registration) {
        if (registration == null) {
            return List.of();
        }
        Set<String> words = new LinkedHashSet<>();
        words.addAll(registration.wakeWords());
        words.addAll(registration.extraWords());
        return List.copyOf(words);
    }

    private static boolean overlapsAny(String word, Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream().anyMatch(candidate -> sameNormalized(word, candidate) || containsEither(word, candidate));
    }

    private static boolean containsEither(String left, String right) {
        String normalizedLeft = TextListNormalizer.normalizeText(left);
        String normalizedRight = TextListNormalizer.normalizeText(right);
        return !normalizedLeft.isBlank()
                && !normalizedRight.isBlank()
                && (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft));
    }

    private static boolean sameNormalized(String left, String right) {
        return TextListNormalizer.normalizeText(left).equals(TextListNormalizer.normalizeText(right));
    }

    private static String conflictLabel(String left, String right) {
        String normalizedLeft = TextListNormalizer.normalizeText(left);
        String normalizedRight = TextListNormalizer.normalizeText(right);
        if (normalizedLeft.length() <= normalizedRight.length()) {
            return left + " / " + right;
        }
        return right + " / " + left;
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
