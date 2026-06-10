package com.rheinmetal.tianshu.protocol.voice;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class VoiceResourceManager implements VoiceResourceAccess {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final AtomicLong version = new AtomicLong();
    private final List<Consumer<VoiceResourceSnapshot>> snapshotListeners = new CopyOnWriteArrayList<>();
    private final VoiceTriggerRegistry triggerRegistry = new VoiceTriggerRegistry();

    private volatile VoiceResourceSnapshot currentSnapshot;

    public VoiceResourceManager(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    public VoiceTriggerRegistry voiceTriggers() {
        return triggerRegistry;
    }

    public VoiceResourceSnapshot materialize() {
        return materialize(triggerRegistry);
    }

    public VoiceResourceSnapshot materialize(VoiceTriggerRegistry registry) {
        VoiceTriggerRegistry effectiveRegistry = registry == null ? triggerRegistry : registry;
        Path zhFile = resolveHotwordsFile("zh");
        Path enFile = resolveHotwordsFile("en");
        try {
            HotwordGroups groups = splitHotwords(effectiveRegistry.asrHotwords());
            String fingerprint = fingerprint(groups.zhWords(), groups.enWords());
            VoiceResourceSnapshot previous = currentSnapshot;
            if (previous != null && fingerprint.equals(previous.hotwordFingerprint())) {
                return previous;
            }

            writeHotwords(zhFile, groups.zhWords());
            writeHotwords(enFile, groups.enWords());
            VoiceResourceSnapshot snapshot = new VoiceResourceSnapshot(
                    version.incrementAndGet(),
                    zhFile,
                    enFile,
                    fingerprint,
                    effectiveRegistry
            );
            currentSnapshot = snapshot;
            env.info("Voice resources materialized, version=" + snapshot.version()
                    + ", zh=" + groups.zhWords().size()
                    + ", en=" + groups.enWords().size());
            notifySnapshotListeners(snapshot);
            return snapshot;
        } catch (Exception exception) {
            env.error("Voice resource materialization failed", exception);
            VoiceResourceSnapshot snapshot = new VoiceResourceSnapshot(
                    version.incrementAndGet(),
                    zhFile,
                    enFile,
                    "",
                    effectiveRegistry
            );
            currentSnapshot = snapshot;
            notifySnapshotListeners(snapshot);
            return snapshot;
        }
    }

    @Override
    public VoiceResourceSnapshot snapshot() {
        VoiceResourceSnapshot snapshot = currentSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        return new VoiceResourceSnapshot(version.get(), resolveHotwordsFile("zh"), resolveHotwordsFile("en"), "", triggerRegistry);
    }

    @Override
    public Path resolveHotwordsFile(String language) {
        return config.getAsrBasePath().resolve("hotwords").resolve(language).resolve("hotwords.txt");
    }

    @Override
    public void addChangeListener(Consumer<VoiceResourceSnapshot> listener) {
        if (listener != null) {
            snapshotListeners.add(listener);
        }
    }

    @Override
    public void removeChangeListener(Consumer<VoiceResourceSnapshot> listener) {
        if (listener != null) {
            snapshotListeners.remove(listener);
        }
    }

    private HotwordGroups splitHotwords(List<String> words) {
        Set<String> zhWords = new LinkedHashSet<>();
        Set<String> enWords = new LinkedHashSet<>();
        if (words != null) {
            for (String word : words) {
                if (isEnglishWord(word)) {
                    enWords.add(word.trim());
                } else if (word != null && !word.isBlank()) {
                    zhWords.add(word.trim());
                }
            }
        }
        return new HotwordGroups(zhWords, enWords);
    }

    private void notifySnapshotListeners(VoiceResourceSnapshot snapshot) {
        for (Consumer<VoiceResourceSnapshot> listener : snapshotListeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception exception) {
                env.error("Voice resource snapshot listener failed", exception);
            }
        }
    }

    private void writeHotwords(Path file, Set<String> words) throws IOException {
        Files.createDirectories(file.getParent());
        if (words == null || words.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.write(file, List.copyOf(words), StandardCharsets.UTF_8);
    }

    private String fingerprint(Collection<String> zhWords, Collection<String> enWords) {
        StringBuilder builder = new StringBuilder();
        appendFingerprintGroup(builder, "zh", zhWords);
        appendFingerprintGroup(builder, "en", enWords);
        return Integer.toHexString(builder.toString().hashCode());
    }

    private void appendFingerprintGroup(StringBuilder builder, String language, Collection<String> words) {
        builder.append(language).append(':');
        if (words != null) {
            words.stream()
                    .filter(word -> word != null && !word.isBlank())
                    .map(String::trim)
                    .sorted()
                    .forEach(word -> builder.append(word).append('\n'));
        }
        builder.append(';');
    }

    private boolean isEnglishWord(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        String trimmed = word.trim();
        boolean hasAsciiLetter = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                return false;
            }
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                hasAsciiLetter = true;
            }
        }
        return hasAsciiLetter && trimmed.toLowerCase(Locale.ROOT).chars().anyMatch(c -> c >= 'a' && c <= 'z');
    }

    private record HotwordGroups(Set<String> zhWords, Set<String> enWords) {
    }
}
