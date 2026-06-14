package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;

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
    }

    public static final class FakeConfig implements ITianshuConfig {
        private final Path root;
        private boolean aiEnabled = true;
        private String customLlmName = "test-llm";
        private Path llmGgufFilePath;
        private int taskAdmissionQueueSize = 0;
        private int taskAgingBoostPerRequest = 1;
        private int libsChatQueueSize = 1;
        private int taskHotSuspendSlots = 0;

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

        public FakeConfig llmGgufFilePath(Path path) {
            this.llmGgufFilePath = path;
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

        public FakeConfig taskHotSuspendSlots(int value) {
            this.taskHotSuspendSlots = value;
            return this;
        }

        @Override public boolean isAiEnabled() { return aiEnabled; }
        @Override public void setAiEnabled(boolean enabled) { this.aiEnabled = enabled; }
        @Override public TriggerMode getTriggerMode() { return TriggerMode.ALWAYS; }
        @Override public void setTriggerMode(TriggerMode mode) {}
        @Override public int getAsrPort() { return 0; }
        @Override public int getLlmPort() { return 0; }
        @Override public int getTtsPort() { return 0; }
        @Override public String getCustomAsrName() { return ""; }
        @Override public void setCustomAsrName(String name) {}
        @Override public String getCustomLlmName() { return customLlmName; }
        @Override public void setCustomLlmName(String name) { this.customLlmName = name; }
        @Override public String getCustomTtsName() { return ""; }
        @Override public void setCustomTtsName(String name) {}
        @Override public Path getRootPath() { return root; }
        @Override public Path getGameConfigDir() { return root.resolve("config"); }
        @Override public Path getAsrBasePath() { return root.resolve("asr"); }
        @Override public Path getLlmBasePath() { return root.resolve("llm"); }
        @Override public Path getTtsBasePath() { return root.resolve("tts"); }
        @Override public Path getAsrModelPath() { return getAsrBasePath().resolve("model"); }
        @Override public Path getLlmModelPath() { return getLlmBasePath().resolve("model"); }
        @Override public Path getTtsModelPath() { return getTtsBasePath().resolve("model"); }
        @Override public Path getLlmGgufFilePath() { return llmGgufFilePath; }
        @Override public Path getVoiceLibraryPath() { return getTtsBasePath().resolve("voice"); }
        @Override public int getLlmTaskAdmissionQueueSize() { return taskAdmissionQueueSize; }
        @Override public int getLlmTaskAgingBoostPerRequest() { return taskAgingBoostPerRequest; }
        @Override public int getLlmLibsChatQueueSize() { return libsChatQueueSize; }
        @Override public int getLlmTaskHotSuspendSlots() { return taskHotSuspendSlots; }
        @Override public void save() {}
    }
}
