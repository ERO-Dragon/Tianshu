package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

public class TtsVoiceLibraryService {
    private final IGameEnvironment env;
    private final ITianshuConfig config;

    public TtsVoiceLibraryService(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    public void openVoiceLibraryFolder() {
        try {
            Path dir = config.getVoiceLibraryPath();
            Files.createDirectories(dir);
            env.openFolder(dir);
        } catch (Exception e) {
            env.error("打开音色库目录失败", e);
        }
    }

    public List<String> listVoiceSamples() {
        Path voiceDir = config.getVoiceLibraryPath();
        if (!Files.isDirectory(voiceDir)) {
            return Collections.emptyList();
        }
        try (var stream = Files.list(voiceDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(this::isSupportedAudioFile)
                    .sorted(String::compareToIgnoreCase)
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public Path resolveVoiceSamplePath(String sampleName) {
        if (sampleName == null || sampleName.isBlank()) {
            return null;
        }
        Path fileName = Path.of(sampleName).getFileName();
        if (fileName == null) {
            return null;
        }
        Path resolved = config.getVoiceLibraryPath().resolve(fileName.toString()).normalize();
        Path root = config.getVoiceLibraryPath().normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return null;
        }
        return resolved;
    }

    public String importVoiceSample(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return "";
        }
        String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
        if (!isSupportedAudioFile(fileName)) {
            return "";
        }
        try {
            Path dir = config.getVoiceLibraryPath();
            Files.createDirectories(dir);
            Path target = uniqueTarget(dir, fileName);
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return target.getFileName().toString();
        } catch (IOException e) {
            env.error("导入 TTS 音色失败", e);
            return "";
        }
    }

    private Path uniqueTarget(Path dir, String fileName) {
        Path target = dir.resolve(fileName).normalize();
        if (!Files.exists(target)) {
            return target;
        }
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            Path candidate = dir.resolve(base + "-" + i + ext).normalize();
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(System.currentTimeMillis() + "-" + fileName).normalize();
    }

    private boolean isSupportedAudioFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac");
    }
}
