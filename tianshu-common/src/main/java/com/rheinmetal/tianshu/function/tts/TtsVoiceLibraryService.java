package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class TtsVoiceLibraryService {
    private final IGameEnvironment env;
    private final TtsConfiguration config;
    private final ModuleExecutionAccess execution;
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Runnable> refreshCallbacks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<List<String>> voiceSamples = new AtomicReference<>(List.of());

    public TtsVoiceLibraryService(IGameEnvironment env, TtsConfiguration config, ModuleExecutionAccess execution) {
        this.env = env;
        this.config = config;
        this.execution = execution;
        refreshVoiceSamplesAsync(null);
    }

    public List<String> voiceSamples() {
        return voiceSamples.get();
    }

    public void refreshVoiceSamplesAsync(Runnable completion) {
        if (completion != null) {
            refreshCallbacks.add(completion);
        }
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        ProtocolTaskHandle handle = execution.submit(
                taskSpec("refresh", 1),
                () -> {
                    try {
                        refreshVoiceSamples();
                    } finally {
                        completeRefresh();
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            completeRefresh();
        }
    }

    private void completeRefresh() {
        Runnable callback;
        while ((callback = refreshCallbacks.poll()) != null) {
            try {
                callback.run();
            } catch (RuntimeException exception) {
                env.error("tts.voice_library.refresh_callback_failed", exception);
            }
        }
        refreshQueued.set(false);
        if (!refreshCallbacks.isEmpty()) {
            refreshVoiceSamplesAsync(null);
        }
    }

    public void importVoiceSampleAsync(Path source, Consumer<String> completion) {
        ProtocolTaskHandle handle = execution.submit(
                taskSpec("import", 2),
                () -> {
                    String imported = "";
                    try {
                        imported = importVoiceSample(source);
                        refreshVoiceSamples();
                    } catch (RuntimeException failure) {
                        env.error("tts.voice_library.import_failed", failure);
                    }
                    if (completion != null) {
                        completion.accept(imported);
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED && completion != null) {
            completion.accept("");
        }
    }

    public void openVoiceLibraryFolderAsync() {
        execution.submit(taskSpec("open", 1), this::openVoiceLibraryFolder);
    }

    private ProtocolTaskSpec taskSpec(String operation, int queueCapacity) {
        return ProtocolTaskSpec.builder()
                .moduleId("module.tts")
                .lane(ExecutionLane.IO)
                .concurrencyKey("module.tts:voice-library:" + operation)
                .maxConcurrency(1)
                .queueCapacity(queueCapacity)
                .build();
    }

    private void openVoiceLibraryFolder() {
        try {
            Path dir = config.getVoiceLibraryPath();
            Files.createDirectories(dir);
            env.openFolder(dir);
        } catch (Exception e) {
            env.error("tts.voice_library.open_failed", e);
        }
    }

    private void refreshVoiceSamples() {
        voiceSamples.set(scanVoiceSamples());
    }

    private List<String> scanVoiceSamples() {
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

    private String importVoiceSample(Path source) {
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
            Files.copy(source, target);
            return target.getFileName().toString();
        } catch (Exception e) {
            env.error("tts.voice_library.import_failed", e);
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
