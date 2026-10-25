package com.rheinmetal.tianshu.testkit;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AuxiliumStorageConfiguration;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestLlmSupport {
    private TestLlmSupport() {
    }

    public static final class FakeConfig implements IntakeConfiguration, WeavingConfiguration, EchoConfiguration,
            AuxiliumStorageConfiguration {
        private final Path root;
        private boolean aiEnabled = true;
        private String customLlmName = "test-llm";
        private int taskAdmissionQueueSize = 0;
        private int taskAgingBoostPerRequest = 1;
        private String embeddingModelName = "";
    private int embeddingGpuLayers = WeavingConfiguration.EMBEDDING_GPU_LAYERS_CPU;
        private long llmAutoLoadDelayMillis = 3_000L;
        private long ttsAutoLoadDelayMillis = 1_000L;
        private String remoteApiKey = "";

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

        public FakeConfig embeddingGpuLayers(int layers) {
        this.embeddingGpuLayers = layers;
        return this;
    }

    @Override
    public int getLlmEmbeddingGpuLayers() {
        return embeddingGpuLayers;
    }

    public FakeConfig embeddingModelName(String name) {
            this.embeddingModelName = name == null ? "" : name;
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

        /** Cloud credential; blank means the cloud lane is unconfigured and cannot start. */
        public FakeConfig remoteApiKey(String apiKey) {
            this.remoteApiKey = apiKey == null ? "" : apiKey;
            return this;
        }

        @Override public boolean isAsrEnabled() { return aiEnabled; }
        @Override public boolean isLlmEnabled() { return aiEnabled; }
        @Override public boolean isTtsEnabled() { return aiEnabled; }
        @Override public String getSelectedMicName() { return ""; }
        @Override public boolean isAsrRnnoiseEnabled() { return false; }
        @Override public boolean isAsrHighPassFilterEnabled() { return true; }
        @Override public String getCustomAsrName() { return ""; }
        @Override public String getCustomLlmName() { return customLlmName; }
        @Override public String getCustomTtsName() { return ""; }
        @Override public String getLlmRemoteApiKey() { return remoteApiKey; }
        @Override public Path getAsrBasePath() { return root.resolve("asr"); }
        @Override public Path getLlmBasePath() { return root.resolve("llm"); }
        @Override public Path getTtsBasePath() { return root.resolve("tts"); }
        @Override public Path getVoiceLibraryPath() { return getTtsBasePath().resolve("voice"); }
        @Override public Path storageRoot() { return root.resolve("ax").resolve("cache"); }
        @Override public int getLlmTaskAdmissionQueueSize() { return taskAdmissionQueueSize; }
        @Override public int getLlmTaskAgingBoostPerRequest() { return taskAgingBoostPerRequest; }
        @Override public String getLlmEmbeddingModelName() { return embeddingModelName; }
        @Override public long getLlmAutoLoadDelayMillis() { return llmAutoLoadDelayMillis; }
        @Override public long getTtsAutoLoadDelayMillis() { return ttsAutoLoadDelayMillis; }
    }
}
