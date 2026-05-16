package com.rheinmetal.tianshu.protocol.voice;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private volatile VoiceResourceSnapshot currentSnapshot;

    public VoiceResourceManager(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    public VoiceResourceSnapshot materialize(VoiceTriggerRegistry registry) {
        long currentVersion = version.incrementAndGet();
        Path zhFile = resolveHotwordsFile("zh");
        Path enFile = resolveHotwordsFile("en");
        VoiceResourceSnapshot snapshot;
        try {
            Set<String> zhWords = new LinkedHashSet<>();
            Set<String> enWords = new LinkedHashSet<>();
            for (String word : registry.asrHotwords()) {
                if (isEnglishWord(word)) {
                    enWords.add(word);
                } else {
                    zhWords.add(word);
                }
            }
            writeHotwords(zhFile, zhWords);
            writeHotwords(enFile, enWords);
            env.info("语音资源已物化，version=" + currentVersion + ", zh=" + zhWords.size() + ", en=" + enWords.size());
            snapshot = new VoiceResourceSnapshot(currentVersion, zhFile, enFile, registry);
        } catch (Exception e) {
            env.error("语音资源物化失败", e);
            snapshot = new VoiceResourceSnapshot(currentVersion, zhFile, enFile, registry);
        }
        currentSnapshot = snapshot;
        notifySnapshotListeners(snapshot);
        return snapshot;
    }

    @Override
    public VoiceResourceSnapshot snapshot() {
        VoiceResourceSnapshot snapshot = currentSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        return new VoiceResourceSnapshot(version.get(), resolveHotwordsFile("zh"), resolveHotwordsFile("en"), null);
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

    private void notifySnapshotListeners(VoiceResourceSnapshot snapshot) {
        for (Consumer<VoiceResourceSnapshot> listener : snapshotListeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception exception) {
                env.error("语音资源快照监听器执行失败", exception);
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
}
