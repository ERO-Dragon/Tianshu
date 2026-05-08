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
import java.util.concurrent.atomic.AtomicLong;

public class VoiceResourceManager {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final AtomicLong version = new AtomicLong();

    public VoiceResourceManager(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    public VoiceResourceSnapshot materialize(VoiceTriggerRegistry registry) {
        long currentVersion = version.incrementAndGet();
        Path zhFile = resolveHotwordsFile("zh");
        Path enFile = resolveHotwordsFile("en");
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
        } catch (Exception e) {
            env.error("语音资源物化失败", e);
        }
        return new VoiceResourceSnapshot(currentVersion, zhFile, enFile, registry);
    }

    public Path resolveHotwordsFile(String language) {
        return config.getAsrBasePath().resolve("hotwords").resolve(language).resolve("hotwords.txt");
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
