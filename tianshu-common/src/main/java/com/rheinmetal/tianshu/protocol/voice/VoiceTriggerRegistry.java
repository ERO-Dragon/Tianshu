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

    public void register(VoiceTriggerRegistration registration) {
        synchronized (this) {
            registrations.put(registration.moduleId(), registration);
        }
        notifyChanged();
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
            words.addAll(registration.hotwords());
            words.addAll(registration.extraWords());
        }
        return List.copyOf(words);
    }

    public synchronized List<VoiceTriggerMatch> match(String text) {
        String normalizedText = TextListNormalizer.normalizeText(text);
        if (normalizedText.isEmpty()) {
            return List.of();
        }
        List<VoiceTriggerMatch> matches = new ArrayList<>();
        for (VoiceTriggerRegistration registration : registrations.values()) {
            List<String> matchedHotwords = TextListNormalizer.collectMatches(normalizedText, registration.hotwords());
            List<String> matchedExtraWords = TextListNormalizer.collectMatches(normalizedText, registration.extraWords());
            if (matchedHotwords.isEmpty() && matchedExtraWords.isEmpty()) {
                continue;
            }
            double confidence = confidence(registration, matchedHotwords, matchedExtraWords);
            matches.add(new VoiceTriggerMatch(registration.moduleId(), matchedHotwords, matchedExtraWords, confidence));
        }
        return List.copyOf(matches);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private static double confidence(VoiceTriggerRegistration registration, List<String> matchedHotwords, List<String> matchedExtraWords) {
        int total = Math.max(1, registration.hotwords().size() + registration.extraWords().size());
        double score = matchedHotwords.size() * 2.0D + matchedExtraWords.size();
        return Math.min(1.0D, score / Math.max(2.0D, total));
    }
}
