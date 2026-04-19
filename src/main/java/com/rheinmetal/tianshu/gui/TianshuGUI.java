package com.rheinmetal.tianshu.gui;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.client.TianshuClient;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.config.Config.TriggerMode;
import com.rheinmetal.tianshu.config.Config.VramTier;
import com.rheinmetal.tianshu.config.ModelSettings;
import com.rheinmetal.tianshu.config.ModelUrls;
import com.rheinmetal.tianshu.core.EnvSetupManager;
import com.rheinmetal.tianshu.core.ProcessManager;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.engine.AsrEngine;
import com.rheinmetal.tianshu.core.engine.TtsEngine;
import com.rheinmetal.tianshu.model.ModelDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

@OnlyIn(Dist.CLIENT)
public class TianshuGUI extends Screen {
    private static final Component TITLE = Component.literal("天枢 AI 控制台");
    private static final Component AI_ENABLED_TEXT = Component.literal("AI状态: 开启");
    private static final Component AI_DISABLED_TEXT = Component.literal("AI状态: 关闭");
    private static final Component TRIGGER_MODE_ALWAYS_TEXT = Component.literal("触发模式: 始终");
    private static final Component TRIGGER_MODE_PUSH_TO_TALK_TEXT = Component.literal("触发模式: 按键");
    private static final Component TRIGGER_MODE_WAKE_WORD_TEXT = Component.literal("触发模式: 热词");
    private static final Component WAKE_WORD_PROMPT = Component.literal("输入唤醒词");
    private static final Component VRAM_TIER_LIGHT_TEXT = Component.literal("模型预设: 低配");
    private static final Component VRAM_TIER_STANDARD_TEXT = Component.literal("模型预设: 中配");
    private static final Component VRAM_TIER_DELUXE_TEXT = Component.literal("模型预设: 高配");
    private static final Component VRAM_TIER_CUSTOM_TEXT = Component.literal("模型预设: 自定义");
    private static final Component DOWNLOAD_PRESET_TEXT = Component.literal("下载当前预设");
    private static final Component DOWNLOADED_TEXT = Component.literal("已下载");
    private static final Component APPLY_CONFIG_TEXT = Component.literal("应用配置");
    private static final Component EXIT_TEXT = Component.literal("退出");
    private static final Component CONFIG_SAVED_TEXT = Component.literal("配置已保存");
    private static final String TTS_PREVIEW_TEXT = "你好，我是天枢，这是当前音色的试听效果。";

    private boolean isEnabled;
    private TriggerMode currentMode;
    private VramTier currentVramTier;
    private EditBox wakeWordEditBox;

    private boolean showProgressBars = false;
    private Button downloadPresetBtn;
    private Queue<java.util.Map.Entry<String, Path>> downloadQueue;
    private volatile int asrProgress = 0;
    private volatile int llmProgress = 0;
    private volatile int ttsProgress = 0;
    private String currentDownloadLabel = "";

    private boolean envSetupNeeded = false;
    private boolean envSetupInProgress = false;
    private volatile int envSetupProgress = 0;
    private String envSetupStage = "";

    private Component infoText = Component.literal("");
    private long infoTextExpiryTime = 0;

    private final Config.VramTier initialVramTier;
    private final String initialCustomAsr;
    private final String initialCustomLlm;
    private final String initialCustomTts;

    private volatile boolean asrPreviewRunning = false;
    private volatile boolean ttsPreviewRunning = false;
    private volatile String asrPreviewResult = "";
    private float ttsSpeed = 1.0f;
    private TtsSpeedSlider ttsSpeedSlider;
    private int leftScrollOffset = 0;
    private static final String[] PERSONA_PRESETS = {"默认", "开朗健谈", "稳健务实", "温柔体贴", "严肃专业"};
    private static final String[] PERSONA_PROMPTS = {"", "你是一个开朗、健谈的助手，喜欢用轻松愉快的语气回答问题，偶尔会开个小玩笑。", "你是一个稳健、务实的助手，回答简洁明了，注重实用性和准确性。", "你是一个温柔、体贴的助手，善于倾听，回答时充满关怀。", "你是一个严肃、专业的助手，回答严谨精确，注重逻辑和数据。"};

    public TianshuGUI() {
        super(TITLE);
        this.isEnabled = Config.AI_ENABLED.get();
        this.currentMode = Config.TRIGGER_MODE.get();
        this.currentVramTier = Config.VRAM_TIER.get();
        this.initialVramTier = this.currentVramTier;
        this.initialCustomAsr = Config.CUSTOM_ASR_NAME.get();
        this.initialCustomLlm = Config.CUSTOM_LLM_NAME.get();
        this.initialCustomTts = Config.CUSTOM_TTS_NAME.get();
        this.envSetupNeeded = !EnvSetupManager.isEnvironmentReady();
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        if (envSetupNeeded && !envSetupInProgress && !EnvSetupManager.isSetupCompleted()) {
            initSetupScreen();
            return;
        }

        if (envSetupInProgress) {
            return;
        }

        initMainScreen();
    }

    private void initSetupScreen() {
        int buttonWidth = 220;
        int buttonHeight = 24;
        this.addRenderableWidget(BrightButton.create(Component.literal("检测运行环境"), b -> {
            b.active = false;
            envSetupInProgress = true;
            envSetupProgress = 0;
            envSetupStage = "正在检测...";
            this.clearWidgets();

            EnvSetupManager.startSetup(new EnvSetupManager.SetupCallback() {
                @Override
                public void onProgress(String stage, int percent) {
                    envSetupStage = stage;
                    envSetupProgress = percent;
                }

                @Override
                public void onSuccess() {
                    Minecraft.getInstance().execute(() -> {
                        envSetupNeeded = false;
                        envSetupInProgress = false;
                        EnvSetupManager.markSetupCompleted();
                        try {
                            Tianshu.reloadNative();
                        } catch (Exception e) {
                            Tianshu.LOGGER.error("重新加载 Native 库失败", e);
                        }
                        TianshuCoreManager.getInstance().onEnvSetupFinished();
                        init();
                    });
                }

                @Override
                public void onError(String message) {
                    Minecraft.getInstance().execute(() -> {
                        envSetupInProgress = false;
                        infoText = Component.literal("§c" + message);
                        infoTextExpiryTime = System.currentTimeMillis() + 8000;
                        init();
                    });
                }
            });
        }).pos((this.width - buttonWidth) / 2, this.height / 2 - 20).size(buttonWidth, buttonHeight).build());
    }

    private void initMainScreen() {
        int leftPanelX = 24;
        int topY = 42;
        int panelGap = 16;
        int panelHeight = this.height - 84;
        int leftPanelWidth = Math.max(180, (int) (this.width * 0.25f));
        int rightPanelX = leftPanelX + leftPanelWidth + panelGap;
        int rightPanelWidth = this.width - rightPanelX - 24;
        int buttonWidth = leftPanelWidth - 28;
        int buttonHeight = 22;
        int baseY = topY + 18;
        int y = baseY - leftScrollOffset;

        if (y >= topY && y < topY + panelHeight - 60) {
            this.addRenderableWidget(BrightButton.create(isEnabled ? AI_ENABLED_TEXT : AI_DISABLED_TEXT, b -> {
                isEnabled = !isEnabled;
                Config.AI_ENABLED.set(isEnabled);
                Config.SPEC.save();
                this.init();
            }).pos(leftPanelX + 14, y).size(buttonWidth, buttonHeight).build());
        }
        y += 30;

        if (y >= topY && y < topY + panelHeight - 60) {
            this.addRenderableWidget(BrightButton.create(getTriggerModeText(currentMode), b -> {
                switch (currentMode) {
                    case ALWAYS -> currentMode = TriggerMode.PUSH_TO_TALK;
                    case PUSH_TO_TALK -> currentMode = TriggerMode.WAKE_WORD;
                    case WAKE_WORD -> currentMode = TriggerMode.ALWAYS;
                }
                Config.TRIGGER_MODE.set(currentMode);
                Config.SPEC.save();
                this.init();
            }).pos(leftPanelX + 14, y).size(buttonWidth, buttonHeight).build());
        }
        y += 30;

        if (currentMode == TriggerMode.WAKE_WORD) {
            if (y >= topY && y < topY + panelHeight - 60) {
                wakeWordEditBox = new EditBox(this.font, leftPanelX + 14, y, buttonWidth, 20, WAKE_WORD_PROMPT);
                wakeWordEditBox.setValue(Config.WAKE_WORD.get());
                wakeWordEditBox.setResponder(Config.WAKE_WORD::set);
                this.addRenderableWidget(wakeWordEditBox);
            }
            y += 30;
        }

        int presetButtonWidth = buttonWidth - 110;
        boolean isCustom = currentVramTier == VramTier.CUSTOM;
        boolean isAlreadyDownloaded = !isCustom && checkPresetModelsExist(currentVramTier);

        if (y >= topY && y < topY + panelHeight - 60) {
            this.addRenderableWidget(BrightButton.create(getVramTierText(currentVramTier), b -> {
                switch (currentVramTier) {
                    case LIGHT -> currentVramTier = VramTier.STANDARD;
                    case STANDARD -> currentVramTier = VramTier.DELUXE;
                    case DELUXE -> currentVramTier = VramTier.CUSTOM;
                    case CUSTOM -> currentVramTier = VramTier.LIGHT;
                }
                Config.VRAM_TIER.set(currentVramTier);
                Config.SPEC.save();
                showProgressBars = false;
                this.init();
            }).pos(leftPanelX + 14, y).size(presetButtonWidth, buttonHeight).build());

            downloadPresetBtn = BrightButton.create(isAlreadyDownloaded ? DOWNLOADED_TEXT : DOWNLOAD_PRESET_TEXT, b -> {
                if (!isAlreadyDownloaded) {
                    Config.VRAM_TIER.set(currentVramTier);
                    Config.SPEC.save();
                    showProgressBars = true;
                    b.active = false;
                    ModelDownload();
                }
            }).pos(leftPanelX + 18 + presetButtonWidth, y).size(96, buttonHeight).build();
            downloadPresetBtn.active = !isCustom && !isAlreadyDownloaded && !showProgressBars;
            this.addRenderableWidget(downloadPresetBtn);
        }
        y += 30;

        if (y >= topY && y < topY + panelHeight - 60) {
            AudioManager audioMgr = TianshuClient.getAudioManager();
            String micName = audioMgr.getCurrentMicName();
            String micLabel = micName.length() > 14 ? micName.substring(0, 12) + ".." : micName;
            this.addRenderableWidget(BrightButton.create(Component.literal("麦克风: " + micLabel), b -> {
                audioMgr.switchToNextMic();
                this.init();
            }).pos(leftPanelX + 14, y).size(buttonWidth, buttonHeight).build());
        }

        int bottomBtnWidth = (buttonWidth - 10) / 2;
        this.addRenderableWidget(BrightButton.create(APPLY_CONFIG_TEXT, b -> saveConfig())
                .pos(leftPanelX + 14, topY + panelHeight - 34)
                .size(bottomBtnWidth, buttonHeight)
                .build());
        this.addRenderableWidget(BrightButton.create(EXIT_TEXT, b -> this.onClose())
                .pos(leftPanelX + 14 + bottomBtnWidth + 10, topY + panelHeight - 34)
                .size(bottomBtnWidth, buttonHeight)
                .build());

        addRightPanelSettings(rightPanelX, topY, rightPanelWidth);
    }

    private void addRightPanelSettings(int rightPanelX, int topY, int rightPanelWidth) {
        int innerX = rightPanelX + 12;
        int innerY = topY + 20;
        int innerWidth = rightPanelWidth - 24;
        int blockGap = 12;
        int panelHeight = this.height - 84;
        int blockHeight = (panelHeight - 34 - blockGap * 2) / 3;

        int asrY = innerY;
        int llmY = innerY + blockHeight + blockGap;
        int ttsY = innerY + (blockHeight + blockGap) * 2;

        boolean isCustom = currentVramTier == VramTier.CUSTOM;

        if (isCustom) {
            addSelectModelButton(innerX, asrY, innerWidth, "ASR");
            addSelectModelButton(innerX, llmY, innerWidth, "LLM");
            addSelectModelButton(innerX, ttsY, innerWidth, "TTS");
        }

        int bottomRowY = blockHeight - 26;

        this.addRenderableWidget(BrightButton.create(
                Component.literal(asrPreviewRunning ? "试音中" : "试音"), b -> {
                    startAsrPreview();
                }).pos(innerX + 10, asrY + bottomRowY)
                .size(48, 16)
                .build());

        Path asrModelDir = resolveModelDir("ASR");
        boolean isTransducer = asrModelDir != null && AsrEngine.detectTransducer(asrModelDir);
        BrightButton hotwordBtn = BrightButton.create(
                Component.literal("热词"), b -> openHotwordEditor(asrModelDir))
                .pos(innerX + 66, asrY + bottomRowY)
                .size(48, 16)
                .build();
        hotwordBtn.active = isTransducer && asrModelDir != null;
        this.addRenderableWidget(hotwordBtn);

        this.addRenderableWidget(BrightButton.create(
                Component.literal(ttsPreviewRunning ? "试听中" : "试听"), b -> {
                    startTtsPreview();
                }).pos(innerX + 10, ttsY + bottomRowY)
                .size(48, 16)
                .build());

        Path ttsModelDir = resolveModelDir("TTS");
        if (ttsModelDir != null) {
            ModelSettings.TtsSettings ttsSettings = ModelSettings.loadTtsSettings(ttsModelDir);
            ttsSpeed = (float) ttsSettings.speed;
            int sliderY = ttsY + bottomRowY;
            int sliderWidth = Math.min(120, innerWidth - 70);
            ttsSpeedSlider = new TtsSpeedSlider(innerX + 66, sliderY, sliderWidth, 16, ttsSpeed, ttsModelDir);
            this.addRenderableWidget(ttsSpeedSlider);
        }

        Path llmModelDir = resolveModelDir("LLM");
        if (llmModelDir != null) {
            ModelSettings.LlmSettings llmSettings = ModelSettings.loadLlmSettings(llmModelDir);
            int personaY = llmY + bottomRowY;
            this.addRenderableWidget(BrightButton.create(
                    Component.literal(getPersonaLabel(llmSettings.systemPrompt)), b -> {
                        cyclePersona(llmSettings, llmModelDir);
                        b.setMessage(Component.literal(getPersonaLabel(llmSettings.systemPrompt)));
                    }).pos(innerX + 10, personaY).size(80, 16).build());
        }
    }

    private void addSelectModelButton(int x, int y, int width, String type) {
        int buttonWidth = 72;
        int buttonHeight = 18;
        int buttonY = y + 8;
        int buttonX = x + width - buttonWidth - 10;

        this.addRenderableWidget(BrightButton.create(Component.literal("选择模型"), b -> {
            minecraft.setScreen(new ModelSelectScreen(this, type));
        }).pos(buttonX, buttonY)
                .size(buttonWidth, buttonHeight)
                .build());
    }

    private Path resolveModelDir(String type) {
        String modelName = getDisplayModelName(type);
        if (modelName == null || modelName.isEmpty() || modelName.startsWith("选择")) {
            return null;
        }
        return switch (type) {
            case "ASR" -> Config.getAsrBasePath().resolve(modelName);
            case "LLM" -> Config.getLlmBasePath().resolve(modelName);
            case "TTS" -> Config.getTtsBasePath().resolve(modelName);
            default -> null;
        };
    }

    private void openHotwordEditor(Path modelDir) {
        minecraft.setScreen(new HotwordEditScreen(this, modelDir));
    }

    private String getDisplayModelName(String type) {
        return switch (type) {
            case "ASR" -> {
                if (currentVramTier == VramTier.CUSTOM) {
                    yield Config.CUSTOM_ASR_NAME.get() != null && !Config.CUSTOM_ASR_NAME.get().isEmpty() ? Config.CUSTOM_ASR_NAME.get() : "选择ASR模型";
                }
                yield Config.getPresetAsrName(currentVramTier);
            }
            case "LLM" -> {
                if (currentVramTier == VramTier.CUSTOM) {
                    yield Config.CUSTOM_LLM_NAME.get() != null && !Config.CUSTOM_LLM_NAME.get().isEmpty() ? Config.CUSTOM_LLM_NAME.get() : "选择LLM模型";
                }
                yield Config.getPresetLlmName(currentVramTier);
            }
            case "TTS" -> {
                if (currentVramTier == VramTier.CUSTOM) {
                    yield Config.CUSTOM_TTS_NAME.get() != null && !Config.CUSTOM_TTS_NAME.get().isEmpty() ? Config.CUSTOM_TTS_NAME.get() : "选择TTS模型";
                }
                yield Config.getPresetTtsName(currentVramTier);
            }
            default -> "未知模型";
        };
    }

    private boolean checkPresetModelsExist(VramTier tier) {
        Path asrDir = Config.getAsrBasePath().resolve(Config.getPresetAsrName(tier));
        Path llmDir = Config.getLlmBasePath().resolve(Config.getPresetLlmName(tier));
        Path ttsDir = Config.getTtsBasePath().resolve(Config.getPresetTtsName(tier));
        return checkAsrModelExists(asrDir) && checkLlmModelExists(llmDir) && checkTtsModelExists(ttsDir);
    }

    private boolean checkAsrModelExists(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return false;
        }
        try {
            return Files.list(dir).anyMatch(p -> p.toString().endsWith(".onnx") || p.toString().endsWith(".bin"));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean checkLlmModelExists(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return false;
        }
        try {
            return Files.list(dir).anyMatch(p -> p.toString().endsWith(".gguf"));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean checkTtsModelExists(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return false;
        }
        try {
            return Files.list(dir).anyMatch(p -> p.toString().endsWith(".onnx") || p.toString().endsWith(".bin") || p.toString().endsWith(".gguf"));
        } catch (IOException e) {
            return false;
        }
    }

    private void saveConfig() {
        Config.AI_ENABLED.set(isEnabled);
        Config.TRIGGER_MODE.set(currentMode);
        if (currentMode == TriggerMode.WAKE_WORD && wakeWordEditBox != null) {
            Config.WAKE_WORD.set(wakeWordEditBox.getValue());
        }
        Config.VRAM_TIER.set(currentVramTier);

        boolean modelChanged = !this.currentVramTier.equals(this.initialVramTier)
                || !Objects.equals(Config.CUSTOM_ASR_NAME.get(), this.initialCustomAsr)
                || !Objects.equals(Config.CUSTOM_LLM_NAME.get(), this.initialCustomLlm)
                || !Objects.equals(Config.CUSTOM_TTS_NAME.get(), this.initialCustomTts);

        Config.SPEC.save();
        if (modelChanged) {
            infoText = Component.literal("模型切换中，请稍候...");
            infoTextExpiryTime = System.currentTimeMillis() + 5000;
            final ProcessManager currentProcessManager = TianshuClient.getProcessManager();
            new Thread(() -> {
                TianshuClient.asrReady = false;
                TianshuClient.llmReady = false;
                TianshuClient.ttsReady = false;
                TianshuCoreManager.getInstance().destroyOnWorldLeft();
                currentProcessManager.stopServices();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                TianshuCoreManager.getInstance().tryInitOnWorldJoined();
                currentProcessManager.startLlmServerForDev();
            }, "Tianshu-Restarter").start();
        } else {
            infoText = CONFIG_SAVED_TEXT;
            infoTextExpiryTime = System.currentTimeMillis() + 3000;
        }
    }

    private void startAsrPreview() {
        if (asrPreviewRunning) {
            return;
        }
        asrPreviewRunning = true;
        asrPreviewResult = "";
        infoText = Component.literal("ASR 试听准备中，请在开始录制后说话...");
        infoTextExpiryTime = System.currentTimeMillis() + 10000;
        this.init();

        new Thread(() -> {
            AsrEngine previewEngine = new AsrEngine();
            AudioManager audioManager = TianshuClient.getAudioManager();
            try {
                Path modelPath = Config.getAsrModelPath();
                previewEngine.initialize(modelPath.toString());
                infoText = Component.literal("ASR 试听录音中，请在 4 秒内说话...");
                infoTextExpiryTime = System.currentTimeMillis() + 6000;
                audioManager.startRecording();
                Thread.sleep(4000);
                byte[] audioData = audioManager.stopRecording();
                if (audioData == null || audioData.length == 0) {
                    asrPreviewResult = "未录到有效音频";
                    infoText = Component.literal("ASR 试听失败：未录到有效音频");
                    infoTextExpiryTime = System.currentTimeMillis() + 5000;
                    return;
                }

                String result = previewEngine.recognizeComplete(audioData);
                asrPreviewResult = result == null || result.isBlank() ? "未识别到内容" : result;
                infoText = Component.literal("ASR 试听完成：" + asrPreviewResult);
                infoTextExpiryTime = System.currentTimeMillis() + 8000;
            } catch (Throwable t) {
                Tianshu.LOGGER.error("ASR 试听失败", t);
                asrPreviewResult = "试听失败";
                infoText = Component.literal("ASR 试听失败，请检查模型与麦克风");
                infoTextExpiryTime = System.currentTimeMillis() + 6000;
                try {
                    audioManager.stopRecording();
                } catch (Throwable ignored) {
                }
            } finally {
                try {
                    previewEngine.shutdown();
                } catch (Throwable ignored) {
                }
                asrPreviewRunning = false;
                Minecraft.getInstance().execute(this::init);
            }
        }, "Tianshu-ASR-Preview").start();
    }

    private void startTtsPreview() {
        if (ttsPreviewRunning) {
            return;
        }
        ttsPreviewRunning = true;
        infoText = Component.literal("TTS 试听准备中...");
        infoTextExpiryTime = System.currentTimeMillis() + 10000;
        this.init();

        new Thread(() -> {
            TtsEngine previewEngine = new TtsEngine();
            AudioManager audioManager = TianshuClient.getAudioManager();
            try {
                Path modelPath = Config.getTtsModelPath();
                previewEngine.initialize(modelPath.toString());
                if (!previewEngine.isInitialized()) {
                    infoText = Component.literal("TTS 试听失败：模型初始化失败");
                    infoTextExpiryTime = System.currentTimeMillis() + 6000;
                    return;
                }

                infoText = Component.literal("TTS 试听播放中...");
                infoTextExpiryTime = System.currentTimeMillis() + 6000;
                audioManager.startTtsPlayback(previewEngine.getSampleRate());
                Thread.sleep(120);
                previewEngine.synthesizeSpeech(TTS_PREVIEW_TEXT, audioManager::feedTtsAudio);
                audioManager.stopTtsPlayback();
                infoText = Component.literal("TTS 试听完成");
                infoTextExpiryTime = System.currentTimeMillis() + 4000;
            } catch (Throwable t) {
                Tianshu.LOGGER.error("TTS 试听失败", t);
                infoText = Component.literal("TTS 试听失败，请检查模型与音频输出");
                infoTextExpiryTime = System.currentTimeMillis() + 6000;
            } finally {
                try {
                    audioManager.stopTtsPlayback();
                } catch (Throwable ignored) {
                }
                try {
                    previewEngine.shutdown();
                } catch (Throwable ignored) {
                }
                ttsPreviewRunning = false;
                Minecraft.getInstance().execute(this::init);
            }
        }, "Tianshu-TTS-Preview").start();
    }

    private Component getVramTierText(VramTier tier) {
        return switch (tier) {
            case LIGHT -> VRAM_TIER_LIGHT_TEXT;
            case STANDARD -> VRAM_TIER_STANDARD_TEXT;
            case DELUXE -> VRAM_TIER_DELUXE_TEXT;
            case CUSTOM -> VRAM_TIER_CUSTOM_TEXT;
        };
    }

    private Component getTriggerModeText(TriggerMode mode) {
        return switch (mode) {
            case ALWAYS -> TRIGGER_MODE_ALWAYS_TEXT;
            case PUSH_TO_TALK -> TRIGGER_MODE_PUSH_TO_TALK_TEXT;
            case WAKE_WORD -> TRIGGER_MODE_WAKE_WORD_TEXT;
        };
    }

    private void ModelDownload() {
        List<String> asrUrls = getAsrUrlByTier(currentVramTier);
        String llmUrl = getLlmUrlByTier(currentVramTier);
        List<String> ttsUrls = getTtsUrlByTier(currentVramTier);
        if (asrUrls.stream().anyMatch(url -> url.contains("example.com")) || llmUrl.contains("example.com") || ttsUrls.stream().anyMatch(url -> url.contains("example.com")) || asrUrls.isEmpty() || llmUrl.isEmpty() || ttsUrls.isEmpty()) {
            handleDownloadError("模型下载链接未配置");
            return;
        }

        downloadQueue = new LinkedList<>();
        Path asrDir = Config.getAsrModelPath();
        for (String url : asrUrls) {
            addTaskToQueue(url, asrDir);
        }
        addTaskToQueue(llmUrl, Config.getLlmModelPath());
        Path ttsDir = Config.getTtsModelPath();
        for (String url : ttsUrls) {
            addTaskToQueue(url, ttsDir);
        }

        if (!downloadQueue.isEmpty()) {
            startNextDownload();
        } else {
            handleDownloadComplete();
        }
    }

    private void addTaskToQueue(String url, Path baseDir) {
        try {
            String fileName = new java.net.URI(url).getPath().substring(new java.net.URI(url).getPath().lastIndexOf('/') + 1);
            Path filePath = baseDir.resolve(fileName);
            if (!Files.exists(filePath)) {
                downloadQueue.add(new java.util.AbstractMap.SimpleEntry<>(url, filePath));
            }
        } catch (Exception e) {
            handleDownloadError("URL解析失败: " + url);
        }
    }

    private void startNextDownload() {
        if (downloadQueue == null || downloadQueue.isEmpty()) {
            handleDownloadComplete();
            return;
        }
        java.util.Map.Entry<String, Path> task = downloadQueue.poll();
        String url = task.getKey();
        Path filePath = task.getValue();

        if (filePath.startsWith(Config.getAsrModelPath())) {
            currentDownloadLabel = "ASR:";
            asrProgress = 0;
        } else if (filePath.startsWith(Config.getLlmModelPath())) {
            currentDownloadLabel = "LLM:";
            llmProgress = 0;
        } else {
            currentDownloadLabel = "TTS:";
            ttsProgress = 0;
        }

        ModelDownloader.downloadAsync(url, filePath, new ModelDownloader.DownloadCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (totalBytes > 0) {
                    int progress = (int) Math.round((double) downloadedBytes * 100 / totalBytes);
                    if (currentDownloadLabel.equals("ASR:")) {
                        asrProgress = progress;
                    } else if (currentDownloadLabel.equals("LLM:")) {
                        llmProgress = progress;
                    } else {
                        ttsProgress = progress;
                    }
                }
            }

            @Override
            public void onSuccess(Path p) {
                startNextDownload();
            }

            @Override
            public void onError(String m) {
                downloadQueue.clear();
                handleDownloadError(m);
            }
        });
    }

    private List<String> getAsrUrlByTier(VramTier tier) {
        return switch (tier) {
            case LIGHT -> ModelUrls.ASR_LIGHT_URLS;
            case STANDARD -> ModelUrls.ASR_STANDARD_URLS;
            case DELUXE -> ModelUrls.ASR_DELUXE_URLS;
            default -> ModelUrls.ASR_STANDARD_URLS;
        };
    }

    private String getLlmUrlByTier(VramTier tier) {
        return switch (tier) {
            case LIGHT -> ModelUrls.LLM_LIGHT_URL;
            case STANDARD -> ModelUrls.LLM_STANDARD_URL;
            case DELUXE -> ModelUrls.LLM_DELUXE_URL;
            default -> ModelUrls.LLM_STANDARD_URL;
        };
    }

    private List<String> getTtsUrlByTier(VramTier tier) {
        return switch (tier) {
            case LIGHT -> ModelUrls.TTS_LIGHT_URLS;
            case STANDARD -> ModelUrls.TTS_STANDARD_URLS;
            case DELUXE -> ModelUrls.TTS_DELUXE_URLS;
            default -> ModelUrls.TTS_STANDARD_URLS;
        };
    }

    private void handleDownloadComplete() {
        Minecraft.getInstance().execute(() -> {
            showProgressBars = false;
            this.init();
        });
    }

    private void handleDownloadError(String errorMessage) {
        Minecraft.getInstance().execute(() -> {
            infoText = Component.literal(errorMessage);
            infoTextExpiryTime = System.currentTimeMillis() + 5000;
            showProgressBars = false;
            this.init();
        });
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawString(this.font, TITLE, (this.width - this.font.width(TITLE)) / 2, 16, 0xFFFFFF);

        if (envSetupInProgress) {
            int progressBarWidth = 220;
            int progressBarHeight = 12;
            int progressBarX = (this.width - progressBarWidth) / 2;
            int progressBarY = this.height / 2 + 10;
            guiGraphics.drawString(this.font, envSetupStage, (this.width - this.font.width(envSetupStage)) / 2, progressBarY - 20, 0xFFFFFF);
            guiGraphics.fill(progressBarX, progressBarY, progressBarX + progressBarWidth, progressBarY + progressBarHeight, 0xFF333333);
            guiGraphics.fill(progressBarX, progressBarY, progressBarX + (progressBarWidth * envSetupProgress / 100), progressBarY + progressBarHeight, 0xFF00FF00);
            guiGraphics.drawString(this.font, envSetupProgress + "%", progressBarX + progressBarWidth + 10, progressBarY, 0xFFFFFF);
            return;
        }

        if (envSetupNeeded && !EnvSetupManager.isSetupCompleted()) {
            return;
        }

        int leftPanelX = 24;
        int topY = 42;
        int panelGap = 16;
        int panelHeight = this.height - 84;
        int leftPanelWidth = Math.max(180, (int) (this.width * 0.25f));
        int rightPanelX = leftPanelX + leftPanelWidth + panelGap;
        int rightPanelWidth = this.width - rightPanelX - 24;

        drawPanel(guiGraphics, leftPanelX, topY, leftPanelWidth, panelHeight, 0xCC1A1A2E, 0xFF6CB4EE);
        drawPanel(guiGraphics, rightPanelX, topY, rightPanelWidth, panelHeight, 0xCC1A1A2E, 0xFF6CB4EE);

        guiGraphics.drawString(this.font, Component.literal("基础控制区"), leftPanelX + 14, topY + 6, 0xD8F1FF);
        guiGraphics.drawString(this.font, Component.literal("模型详情与扩展配置"), rightPanelX + 14, topY + 6, 0xD8F1FF);

        drawRightInfoPanels(guiGraphics, rightPanelX, topY, rightPanelWidth, panelHeight);
        drawBottomInfo(guiGraphics);
    }

    private void drawRightInfoPanels(GuiGraphics guiGraphics, int rightPanelX, int topY, int rightPanelWidth, int panelHeight) {
        int innerX = rightPanelX + 12;
        int innerY = topY + 20;
        int innerWidth = rightPanelWidth - 24;
        int blockGap = 12;
        int blockHeight = (panelHeight - 34 - blockGap * 2) / 3;

        drawModelBlock(guiGraphics, innerX, innerY, innerWidth, blockHeight, "ASR", getDisplayModelName("ASR"), getAsrInfoLines());
        drawModelBlock(guiGraphics, innerX, innerY + blockHeight + blockGap, innerWidth, blockHeight, "LLM", getDisplayModelName("LLM"), getLlmInfoLines());
        drawModelBlock(guiGraphics, innerX, innerY + (blockHeight + blockGap) * 2, innerWidth, blockHeight, "TTS", getDisplayModelName("TTS"), getTtsInfoLines());

        if (showProgressBars && currentVramTier != VramTier.CUSTOM) {
            drawProgressOverlay(guiGraphics, innerX, innerY, innerWidth, blockHeight, blockGap);
        }
    }

    private void drawModelBlock(GuiGraphics guiGraphics, int x, int y, int width, int height, String type, String modelName, List<String> lines) {
        drawPanel(guiGraphics, x, y, width, height, 0xCC16213E, 0xFF6CB4EE);

        int textLeftPad = 10;
        int textRightPad = 10;
        boolean isCustom = currentVramTier == VramTier.CUSTOM;
        int reservedRight = isCustom ? 90 : textRightPad;
        int maxTextWidth = width - textLeftPad - reservedRight;
        int infoMaxWidth = width - textLeftPad - textRightPad;

        drawClippedString(guiGraphics, type + " 模块", x + textLeftPad, y + 8, maxTextWidth, 0xFFFFFF);
        drawClippedString(guiGraphics, "模型: " + modelName, x + textLeftPad, y + 24, infoMaxWidth, 0xFFE39B);

        int lineY = y + 42;
        for (String line : lines) {
            if (lineY > y + height - 14) {
                break;
            }
            drawClippedString(guiGraphics, line, x + textLeftPad, lineY, infoMaxWidth, 0xD8E6F0);
            lineY += 12;
        }
    }

    private void drawClippedString(GuiGraphics guiGraphics, String text, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) return;
        int textWidth = this.font.width(text);
        if (textWidth <= maxWidth) {
            guiGraphics.drawString(this.font, text, x, y, color);
        } else {
            int ellipsisWidth = this.font.width("...");
            int targetWidth = maxWidth - ellipsisWidth;
            if (targetWidth <= 0) {
                guiGraphics.drawString(this.font, "...", x, y, color);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                int nextWidth = this.font.width(sb.toString() + text.charAt(i));
                if (nextWidth > targetWidth) break;
                sb.append(text.charAt(i));
            }
            guiGraphics.drawString(this.font, sb.toString() + "...", x, y, color);
        }
    }

    private List<String> getAsrInfoLines() {
        List<String> lines = new ArrayList<>();
        if (asrPreviewRunning) {
            lines.add("正在录音，请说话...");
        } else if (!asrPreviewResult.isEmpty()) {
            lines.add("识别结果: " + asrPreviewResult);
        } else {
            Path asrDir = resolveModelDir("ASR");
            if (asrDir != null) {
                boolean transducer = AsrEngine.detectTransducer(asrDir);
                if (transducer) {
                    boolean hasHotwords = java.nio.file.Files.exists(asrDir.resolve("hotwords.txt"));
                    lines.add(hasHotwords ? "热词增强已启用" : "可点击下方\"热词\"添加热词");
                }
            }
        }
        return lines;
    }

    private List<String> getLlmInfoLines() {
        List<String> lines = new ArrayList<>();
        Path llmDir = resolveModelDir("LLM");
        if (llmDir != null) {
            ModelSettings.LlmSettings settings = ModelSettings.loadLlmSettings(llmDir);
            String label = getPersonaLabel(settings.systemPrompt);
            lines.add(label);
        }
        return lines;
    }

    private List<String> getTtsInfoLines() {
        List<String> lines = new ArrayList<>();
        if (ttsPreviewRunning) {
            lines.add("正在播放试听...");
        }
        Path ttsDir = resolveModelDir("TTS");
        if (ttsDir != null) {
            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(ttsDir);
            lines.add("语速: " + String.format("%.1f", settings.speed));
        }
        return lines;
    }

    private void drawProgressOverlay(GuiGraphics guiGraphics, int innerX, int innerY, int innerWidth, int blockHeight, int blockGap) {
        drawSingleProgressBar(guiGraphics, "ASR:", asrProgress, innerX + 12, innerY + blockHeight - 18, innerWidth - 90, 8, innerWidth - 78);
        drawSingleProgressBar(guiGraphics, "LLM:", llmProgress, innerX + 12, innerY + blockHeight + blockGap + blockHeight - 18, innerWidth - 90, 8, innerWidth - 78);
        drawSingleProgressBar(guiGraphics, "TTS:", ttsProgress, innerX + 12, innerY + (blockHeight + blockGap) * 2 + blockHeight - 18, innerWidth - 90, 8, innerWidth - 78);
    }

    private void drawBottomInfo(GuiGraphics guiGraphics) {
        if (!infoText.getString().isEmpty() && System.currentTimeMillis() < infoTextExpiryTime) {
            guiGraphics.drawString(this.font, infoText, (this.width - this.font.width(infoText)) / 2, this.height - 26, 0xFFFF66);
        } else if (System.currentTimeMillis() >= infoTextExpiryTime && !infoText.getString().isEmpty()) {
            infoText = Component.literal("");
        }
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int width, int height, int fillColor, int borderColor) {
        guiGraphics.fill(x, y, x + width, y + height, fillColor);
        guiGraphics.fill(x, y, x + width, y + 1, borderColor);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, borderColor);
        guiGraphics.fill(x, y, x + 1, y + height, borderColor);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, borderColor);
    }

    private void drawSingleProgressBar(GuiGraphics g, String label, int percent, int x, int y, int w, int h, int tOff) {
        g.drawString(this.font, label, x, y - 10, 0xFFFFFF);
        g.fill(x, y, x + w, y + h, 0xFF333333);
        g.fill(x, y, x + (w * percent / 100), y + h, 0xFF00FF00);
        g.drawString(this.font, percent + "%", x + tOff, y - 10, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        try {
            AudioManager audioManager = TianshuClient.getAudioManager();
            if (asrPreviewRunning) {
                audioManager.stopRecording();
            }
            if (ttsPreviewRunning) {
                audioManager.stopTtsPlayback();
            }
        } catch (Throwable ignored) {
        }
        asrPreviewRunning = false;
        ttsPreviewRunning = false;
        super.onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalScroll, double verticalScroll) {
        int leftPanelX = 24;
        int leftPanelWidth = Math.max(180, (int) (this.width * 0.25f));
        if (mouseX >= leftPanelX && mouseX <= leftPanelX + leftPanelWidth) {
            if (verticalScroll < 0 && leftScrollOffset < 60) {
                leftScrollOffset += 12;
                this.init();
            } else if (verticalScroll > 0 && leftScrollOffset > 0) {
                leftScrollOffset -= 12;
                this.init();
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalScroll, verticalScroll);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String getPersonaLabel(String systemPrompt) {
        for (int i = 0; i < PERSONA_PROMPTS.length; i++) {
            if (PERSONA_PROMPTS[i].equals(systemPrompt)) return "人设: " + PERSONA_PRESETS[i];
        }
        if (systemPrompt == null || systemPrompt.isEmpty()) return "人设: 默认";
        return "人设: 自定义";
    }

    private void cyclePersona(ModelSettings.LlmSettings settings, java.nio.file.Path modelDir) {
        int currentIdx = 0;
        for (int i = 0; i < PERSONA_PROMPTS.length; i++) {
            if (PERSONA_PROMPTS[i].equals(settings.systemPrompt)) { currentIdx = i; break; }
        }
        int nextIdx = (currentIdx + 1) % PERSONA_PRESETS.length;
        settings.systemPrompt = PERSONA_PROMPTS[nextIdx];
        ModelSettings.saveLlmSettings(modelDir, settings);
    }

    class TtsSpeedSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
        private float speed;
        private final java.nio.file.Path modelDir;

        TtsSpeedSlider(int x, int y, int width, int height, float initialSpeed, java.nio.file.Path modelDir) {
            super(x, y, width, height, Component.literal("语速"), (initialSpeed - 0.5) / 1.5);
            this.speed = initialSpeed;
            this.modelDir = modelDir;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("语速: " + String.format("%.1f", speed)));
        }

        @Override
        protected void applyValue() {
            speed = (float) (0.5 + value * 1.5);
            speed = Math.round(speed * 10.0f) / 10.0f;
            ttsSpeed = speed;
            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(modelDir);
            settings.speed = speed;
            ModelSettings.saveTtsSettings(modelDir, settings);
            updateMessage();
        }

        public float getSpeed() {
            return speed;
        }
    }

    static class BrightButton extends Button {
        private static final int BG_ACTIVE = 0xFF3A7BD5;
        private static final int BG_ACTIVE_HOVER = 0xFF4A9BF5;
        private static final int BG_DISABLED = 0xFF2A3A4A;
        private static final int BORDER_ACTIVE = 0xFF5AACFF;
        private static final int BORDER_DISABLED = 0xFF3A4A5A;
        private static final int TEXT_ACTIVE = 0xFFFFFFFF;
        private static final int TEXT_DISABLED = 0xFF667788;

        BrightButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        static BrightButtonBuilder create(Component message, OnPress onPress) {
            return new BrightButtonBuilder(message, onPress);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int bg = this.active ? (this.isHovered() ? BG_ACTIVE_HOVER : BG_ACTIVE) : BG_DISABLED;
            int border = this.active ? BORDER_ACTIVE : BORDER_DISABLED;
            int textCol = this.active ? TEXT_ACTIVE : TEXT_DISABLED;

            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();

            guiGraphics.fill(x, y, x + w, y + h, bg);
            guiGraphics.fill(x, y, x + w, y + 1, border);
            guiGraphics.fill(x, y + h - 1, x + w, y + h, border);
            guiGraphics.fill(x, y, x + 1, y + h, border);
            guiGraphics.fill(x + w - 1, y, x + w, y + h, border);

            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String text = getMessage().getString();
            int textWidth = font.width(text);
            int textX = x + (w - textWidth) / 2;
            int textY = y + (h - 8) / 2;
            guiGraphics.drawString(font, getMessage(), textX, textY, textCol);
        }

        static class BrightButtonBuilder {
            private final Component message;
            private final OnPress onPress;
            private int x, y, width, height;

            BrightButtonBuilder(Component message, OnPress onPress) {
                this.message = message;
                this.onPress = onPress;
            }

            BrightButtonBuilder pos(int x, int y) {
                this.x = x;
                this.y = y;
                return this;
            }

            BrightButtonBuilder size(int width, int height) {
                this.width = width;
                this.height = height;
                return this;
            }

            BrightButton build() {
                return new BrightButton(x, y, width, height, message, onPress);
            }
        }
    }
}
