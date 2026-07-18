package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestLlmSupport {
    private TestLlmSupport() {
    }

    public static final class FakeGameEnvironment implements IGameEnvironment {
        public final List<String> infos = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) { infos.add(msg); }
        @Override public void warn(String msg) { warnings.add(msg); }
        @Override public void error(String msg, Throwable t) { errors.add(msg); }
        @Override public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() { return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP; }
    }

    public static final class FakeConfig implements AsrConfiguration, LlmConfiguration, TtsConfiguration,
            AXStorageConfiguration, VoiceResourceConfiguration {
        private final Path root;
        private boolean aiEnabled = true;
        private String customLlmName = "test-llm";
        private int taskAdmissionQueueSize = 0;
        private int taskAgingBoostPerRequest = 1;
        private int libsChatQueueSize = 1;
        private long llmAutoLoadDelayMillis = 3_000L;
        private long ttsAutoLoadDelayMillis = 1_000L;

        public FakeConfig(Path root) {
            this.root = root;
        }

        public FakeConfig llmEnabled(boolean enabled) {
            this.aiEnabled = enabled;
            return this;
        }

        public FakeConfig customLlmName(String name) {
            this.customLlmName = name;
            return this;
        }

        public FakeConfig taskAdmissionQueueSize(int value) {
            this.taskAdmissionQueueSize = value;
            return this;
        }

        public FakeConfig taskAgingBoostPerRequest(int value) {
            this.taskAgingBoostPerRequest = value;
            return this;
        }

        public FakeConfig libsChatQueueSize(int value) {
            this.libsChatQueueSize = value;
            return this;
        }

        public FakeConfig llmAutoLoadDelayMillis(long value) {
            this.llmAutoLoadDelayMillis = value;
            return this;
        }

        public FakeConfig ttsAutoLoadDelayMillis(long value) {
            this.ttsAutoLoadDelayMillis = value;
            return this;
        }

        @Override public boolean isAsrEnabled() { return aiEnabled; }
        @Override public boolean isLlmEnabled() { return aiEnabled; }
        @Override public boolean isTtsEnabled() { return aiEnabled; }
        @Override public TriggerMode getTriggerMode() { return TriggerMode.ALWAYS; }
        @Override public String getSelectedMicName() { return ""; }
        @Override public boolean isAsrRnnoiseEnabled() { return false; }
        @Override public boolean isAsrHighPassFilterEnabled() { return true; }
        @Override public boolean isAsrVadEnabled() { return false; }
        @Override public String getCustomAsrName() { return ""; }
        @Override public String getCustomLlmName() { return customLlmName; }
        @Override public String getCustomTtsName() { return ""; }
        @Override public Path getAsrBasePath() { return root.resolve("asr"); }
        @Override public Path getLlmBasePath() { return root.resolve("llm"); }
        @Override public Path getTtsBasePath() { return root.resolve("tts"); }
        @Override public Path getVoiceLibraryPath() { return getTtsBasePath().resolve("voice"); }
        @Override public Path storageRoot() { return root.resolve("ax").resolve("cache"); }
        @Override public int getLlmTaskAdmissionQueueSize() { return taskAdmissionQueueSize; }
        @Override public int getLlmTaskAgingBoostPerRequest() { return taskAgingBoostPerRequest; }
        @Override public int getLlmLibsChatQueueSize() { return libsChatQueueSize; }
        @Override public long getLlmAutoLoadDelayMillis() { return llmAutoLoadDelayMillis; }
        @Override public long getTtsAutoLoadDelayMillis() { return ttsAutoLoadDelayMillis; }
    }
}
