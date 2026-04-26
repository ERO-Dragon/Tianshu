package com.rheinmetal.tianshu.gui;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.NeoForgeConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.core.EnvSetupManager;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.Engine.AsrEngine;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@OnlyIn(Dist.CLIENT)
public class TianshuGUI extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    private final TianshuCoreManager coreManager;
    private final NeoForgeConfig config;
    private final AudioManager audioManager;
    private final NeoForgeNativeLibBridge nativeLibBridge;

    private boolean isEnabled;
    private TriggerMode currentMode;
    private VramTier currentVramTier;
    private EditBox wakeWordEditBox;

    private boolean showProgressBars = false;
    private Button downloadPresetBtn;
    private volatile int asrProgress = 0;
    private volatile int llmProgress = 0;
    private volatile int ttsProgress = 0;

    private boolean envSetupNeeded = false;
    private boolean envSetupInProgress = false;
    private volatile int envSetupProgress = 0;
    private String envSetupStage = "";

    private Component infoText = Component.literal("");
    private long infoTextExpiryTime = 0;

    private final VramTier initialVramTier;
    private final String initialCustomAsr;
    private final String initialCustomLlm;
    private final String initialCustomTts;

    private volatile String asrPreviewResult = "";
    private float ttsSpeed = 1.0f;
    private TtsSpeedSlider ttsSpeedSlider;
    private int leftScrollOffset = 0;

    private static final String[] PERSONA_PRESETS = {"默认", "开朗健谈", "稳健务实", "温柔体贴", "严肃专业"};
    private static final String[] PERSONA_PROMPTS = {"", "你是一个开朗、健谈的助手，喜欢用轻松愉快的语气回答问题，偶尔会开个小玩笑。", "你是一个稳健、务实的助手，回答简洁明了，注重实用性和准确性。", "你是一个温柔、体贴的助手，善于倾听，回答时充满关怀。", "你是一个严肃、专业的助手，回答严谨精确，注重逻辑和数据。"};

    public TianshuGUI(TianshuCoreManager coreManager, NeoForgeConfig config, AudioManager audioManager, NeoForgeNativeLibBridge nativeLibBridge) {
        super(TITLE);
        this.coreManager = coreManager;
        this.config = config;
        this.audioManager = audioManager;
        this.nativeLibBridge = nativeLibBridge;
        this.isEnabled = config.isAiEnabled();
        this.currentMode = config.getTriggerMode();
        this.currentVramTier = config.getVramTier();
        this.initialVramTier = this.currentVramTier;
        this.initialCustomAsr = config.getCustomAsrName();
        this.initialCustomLlm = config.getCustomLlmName();
        this.initialCustomTts = config.getCustomTtsName();
        this.envSetupNeeded = !coreManager.getEnvSetupManager().isEnvironmentReady();
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        if (envSetupNeeded && !envSetupInProgress && !coreManager.getEnvSetupManager().isSetupCompleted()) {
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

            coreManager.getEnvSetupManager().startSetup(new EnvSetupManager.SetupCallback() {
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
                        coreManager.getEnvSetupManager().markSetupCompleted();
                        coreManager.onEnvSetupFinished();
                        init();
                    });
                }

                @Override
                public void onError(String message) {
                    Minecraft.getInstance().execute(() -> {
                        envSetupInProgress = false;
                        infoText = Component.literal("\u00a7c" + message);
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
                config.setAiEnabled(isEnabled);
                config.save();
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
                config.setTriggerMode(currentMode);
                config.save();
                this.init();
            }).pos(leftPanelX + 14, y).size(buttonWidth, buttonHeight).build());
        }
        y += 30;

        if (currentMode == TriggerMode.WAKE_WORD) {
            if (y >= topY && y < topY + panelHeight - 60) {
                wakeWordEditBox = new EditBox(this.font, leftPanelX + 14, y, buttonWidth, 20, WAKE_WORD_PROMPT);
                wakeWordEditBox.setValue(config.getWakeWord());
                wakeWordEditBox.setResponder(config::setWakeWord);
                this.addRenderableWidget(wakeWordEditBox);
            }
            y += 30;
        }

        int downloadBtnWidth = buttonWidth / 3;
        int presetButtonWidth = buttonWidth - downloadBtnWidth - 4;
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
                config.setVramTier(currentVramTier);
                config.save();
                showProgressBars = false;
                this.init();
            }).pos(leftPanelX + 14, y).size(presetButtonWidth, buttonHeight).build());

            downloadPresetBtn = BrightButton.create(isAlreadyDownloaded ? DOWNLOADED_TEXT : DOWNLOAD_PRESET_TEXT, b -> {
                if (!isAlreadyDownloaded) {
                    config.setVramTier(currentVramTier);
                    config.save();
                    showProgressBars = true;
                    b.active = false;
                    ModelDownload();
                }
            }).pos(leftPanelX + 14 + presetButtonWidth + 4, y).size(downloadBtnWidth, buttonHeight).build();
            downloadPresetBtn.active = !isCustom && !isAlreadyDownloaded && !showProgressBars;
            this.addRenderableWidget(downloadPresetBtn);
        }
        y += 30;

        if (y >= topY && y < topY + panelHeight - 60) {
            String micName = audioManager.getCurrentMicName();
            String micLabel = micName.length() > 14 ? micName.substring(0, 12) + ".." : micName;
            this.addRenderableWidget(BrightButton.create(Component.literal("麦克风: " + micLabel), b -> {
                audioManager.switchToNextMic();
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
        int blockHeight = (panelHeight - 58 - blockGap * 2) / 3;
        int blockStartY = innerY + 24;

        int asrY = blockStartY;
        int llmY = blockStartY + blockHeight + blockGap;
        int ttsY = blockStartY + (blockHeight + blockGap) * 2;

        boolean isCustom = currentVramTier == VramTier.CUSTOM;

        if (isCustom) {
            addSelectModelButton(innerX, asrY, innerWidth, "ASR");
            addSelectModelButton(innerX, llmY, innerWidth, "LLM");
            addSelectModelButton(innerX, ttsY, innerWidth, "TTS");
        }

        int bottomRowY = blockHeight - 26;

        boolean previewRunning = coreManager.isPreviewRunning();

        BrightButton asrPreviewBtn = BrightButton.create(
                Component.literal(previewRunning ? "试音中" : "试音"), b -> startAsrPreview())
                .pos(innerX + 10, asrY + bottomRowY)
                .size(48, 16)
                .build();
        asrPreviewBtn.active = !previewRunning;
        this.addRenderableWidget(asrPreviewBtn);

        Path asrModelDir = resolveModelDir("ASR");
        boolean isTransducer = asrModelDir != null && AsrEngine.detectTransducer(asrModelDir);
        BrightButton hotwordBtn = BrightButton.create(
                Component.literal("热词"), b -> openHotwordEditor(asrModelDir))
                .pos(innerX + 66, asrY + bottomRowY)
                .size(48, 16)
                .build();
        hotwordBtn.active = isTransducer && asrModelDir != null;
        this.addRenderableWidget(hotwordBtn);

        BrightButton ttsPreviewBtn = BrightButton.create(
                Component.literal(previewRunning ? "试听中" : "试听"), b -> startTtsPreview())
                .pos(innerX + 10, ttsY + bottomRowY)
                .size(48, 16)
                .build();
        ttsPreviewBtn.active = !previewRunning;
        this.addRenderableWidget(ttsPreviewBtn);

        Path ttsModelDir = resolveModelDir("TTS");
        TtsModelInfo currentTtsInfo = ttsModelDir != null ? coreManager.resolveCurrentTtsModelInfo() : null;

        int ttsWidgetX = innerX + 66;
        if (ttsModelDir != null) {
            ModelSettings.TtsSettings ttsSettings = ModelSettings.loadTtsSettings(ttsModelDir);
            ttsSpeed = (float) ttsSettings.speed;
            int sliderY = ttsY + bottomRowY;
            int sliderWidth = Math.min(120, innerWidth - 70);
            ttsSpeedSlider = new TtsSpeedSlider(ttsWidgetX, sliderY, sliderWidth, 16, ttsSpeed, ttsModelDir);
            this.addRenderableWidget(ttsSpeedSlider);
            ttsWidgetX += sliderWidth + 4;
        }

        if (currentTtsInfo != null && currentTtsInfo.supportsVoiceClone()) {
            int remainingWidth = innerX + innerWidth - ttsWidgetX - 10;
            if (remainingWidth >= 36) {
                int libraryButtonWidth = Math.min(44, remainingWidth);
                this.addRenderableWidget(BrightButton.create(
                        Component.literal("音色库"), b -> coreManager.openVoiceLibraryFolder())
                        .pos(ttsWidgetX, ttsY + bottomRowY)
                        .size(libraryButtonWidth, 16)
                        .build());
                ttsWidgetX += libraryButtonWidth + 4;
            }

            String currentVoice = ttsModelDir != null ? ModelSettings.loadTtsSettings(ttsModelDir).selectedVoiceSample : "";
            String voiceLabel = currentVoice == null || currentVoice.isEmpty() ? "默认" : currentVoice;
            if (voiceLabel.length() > 8) voiceLabel = voiceLabel.substring(0, 7) + "..";
            int voiceButtonWidth = innerX + innerWidth - ttsWidgetX - 10;
            if (voiceButtonWidth >= 44) {
                this.addRenderableWidget(BrightButton.create(
                        Component.literal(voiceLabel), b -> {
                            if (ttsModelDir == null) return;
                            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(ttsModelDir);
                            List<String> samples = coreManager.listVoiceSamples();
                            List<String> options = new ArrayList<>(samples);
                            options.add(0, "");
                            if (options.isEmpty()) return;
                            int idx = options.indexOf(settings.selectedVoiceSample);
                            if (idx < 0) idx = 0;
                            idx = (idx + 1) % options.size();
                            settings.selectedVoiceSample = options.get(idx);
                            ModelSettings.saveTtsSettings(ttsModelDir, settings);
                            this.init();
                        }).pos(ttsWidgetX, ttsY + bottomRowY)
                        .size(voiceButtonWidth, 16)
                        .build());
            }
        } else if (currentTtsInfo != null && currentTtsInfo.supportsSpeakerSelection()) {
            if (ttsModelDir != null) {
                ModelSettings.TtsSettings ttsSettings = ModelSettings.loadTtsSettings(ttsModelDir);
                this.addRenderableWidget(BrightButton.create(
                        Component.literal("说话人: " + ttsSettings.speakerId), b -> {
                            ttsSettings.speakerId = (ttsSettings.speakerId + 1) % Math.max(1, 10);
                            ModelSettings.saveTtsSettings(ttsModelDir, ttsSettings);
                            b.setMessage(Component.literal("说话人: " + ttsSettings.speakerId));
                        }).pos(ttsWidgetX, ttsY + bottomRowY)
                        .size(64, 16)
                        .build());
            }
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

        if ("TTS".equals(type)) {
            this.addRenderableWidget(BrightButton.create(Component.literal("选择模型"), b -> {
                minecraft.setScreen(new TtsModelSelectScreen(this, config, coreManager, audioManager, nativeLibBridge));
            }).pos(buttonX, buttonY).size(buttonWidth, buttonHeight).build());
        } else if ("ASR".equals(type)) {
            this.addRenderableWidget(BrightButton.create(Component.literal("选择模型"), b -> {
                minecraft.setScreen(new AsrModelSelectScreen(this, config, coreManager, audioManager, nativeLibBridge));
            }).pos(buttonX, buttonY).size(buttonWidth, buttonHeight).build());
        } else {
            this.addRenderableWidget(BrightButton.create(Component.literal("选择模型"), b -> {
                minecraft.setScreen(new ModelSelectScreen(this, type, config, coreManager, audioManager, nativeLibBridge));
            }).pos(buttonX, buttonY).size(buttonWidth, buttonHeight).build());
        }
    }

    private Path resolveModelDir(String type) {
        String modelName = getDisplayModelName(type);
        if (modelName == null || modelName.isEmpty() || modelName.startsWith("选择")) {
            return null;
        }
        return switch (type) {
            case "ASR" -> config.getAsrBasePath().resolve(modelName);
            case "LLM" -> config.getLlmBasePath().resolve(modelName);
            case "TTS" -> {
                Path currentTtsDir = coreManager.resolveCurrentTtsModelDir();
                if (currentTtsDir != null) {
                    yield currentTtsDir;
                }
                yield config.getTtsBasePath().resolve(modelName);
            }
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
                    String name = config.getCustomAsrName();
                    yield (name != null && !name.isEmpty()) ? name : "选择ASR模型";
                }
                yield com.rheinmetal.tianshu.constant.ModelPresets.getPresetAsrName(currentVramTier);
            }
            case "LLM" -> {
                if (currentVramTier == VramTier.CUSTOM) {
                    String name = config.getCustomLlmName();
                    yield (name != null && !name.isEmpty()) ? name : "选择LLM模型";
                }
                yield com.rheinmetal.tianshu.constant.ModelPresets.getPresetLlmName(currentVramTier);
            }
            case "TTS" -> {
                if (currentVramTier == VramTier.CUSTOM) {
                    String name = config.getCustomTtsName();
                    yield (name != null && !name.isEmpty()) ? name : "选择TTS模型";
                }
                yield com.rheinmetal.tianshu.constant.ModelPresets.getPresetTtsName(currentVramTier);
            }
            default -> "未知模型";
        };
    }

    private boolean checkPresetModelsExist(VramTier tier) {
        return coreManager.getModelManager().checkPresetModelsExist(tier);
    }

    private void saveConfig() {
        config.setAiEnabled(isEnabled);
        config.setTriggerMode(currentMode);
        if (currentMode == TriggerMode.WAKE_WORD && wakeWordEditBox != null) {
            config.setWakeWord(wakeWordEditBox.getValue());
        }
        config.setVramTier(currentVramTier);

        boolean llmChanged = !this.currentVramTier.equals(this.initialVramTier)
                || !Objects.equals(config.getCustomLlmName(), this.initialCustomLlm);

        config.save();

        infoText = Component.literal("正在重启引擎，请稍候...");
        infoTextExpiryTime = System.currentTimeMillis() + 8000;
        coreManager.restartEngineAsync(llmChanged, () -> Minecraft.getInstance().execute(() -> {
            infoText = CONFIG_SAVED_TEXT;
            infoTextExpiryTime = System.currentTimeMillis() + 3000;
            TianshuGUI.this.init();
        }));
    }

    private void startAsrPreview() {
        if (coreManager.isPreviewRunning()) return;
        asrPreviewResult = "";
        infoText = Component.literal("ASR 试听准备中，请在开始录制后说话...");
        infoTextExpiryTime = System.currentTimeMillis() + 10000;
        this.init();

        coreManager.previewAsr(new TianshuCoreManager.PreviewAsrCallback() {
            @Override
            public void onReady() {
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal("ASR 试听录音中，请在 5 秒内说话...");
                    infoTextExpiryTime = System.currentTimeMillis() + 6000;
                });
            }

            @Override
            public void onResult(String text) {
                asrPreviewResult = text;
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal("ASR 试听完成：" + text);
                    infoTextExpiryTime = System.currentTimeMillis() + 8000;
                });
            }

            @Override
            public void onError(String message) {
                asrPreviewResult = "试听失败";
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal("ASR 试听失败：" + message);
                    infoTextExpiryTime = System.currentTimeMillis() + 6000;
                });
            }

            @Override
            public void onFinish() {
                Minecraft.getInstance().execute(TianshuGUI.this::init);
            }
        });
    }

    private void startTtsPreview() {
        if (coreManager.isPreviewRunning()) return;
        infoText = Component.literal("TTS 试听准备中...");
        infoTextExpiryTime = System.currentTimeMillis() + 10000;
        this.init();

        coreManager.previewTts(TTS_PREVIEW_TEXT, ttsSpeed, (TtsModelInfo) null, new TianshuCoreManager.PreviewTtsCallback() {
            @Override
            public void onReady() {
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal("TTS 试听播放中...");
                    infoTextExpiryTime = System.currentTimeMillis() + 6000;
                });
            }

            @Override
            public void onPlaying() {
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal("TTS 试听完成");
                    infoTextExpiryTime = System.currentTimeMillis() + 4000;
                });
            }

            @Override
            public void onError(String message) {
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal("TTS 试听失败：" + message);
                    infoTextExpiryTime = System.currentTimeMillis() + 6000;
                });
            }

            @Override
            public void onFinish() {
                Minecraft.getInstance().execute(TianshuGUI.this::init);
            }
        });
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
        coreManager.downloadPresetModels(currentVramTier, new TianshuCoreManager.DownloadProgressCallback() {
            @Override
            public void onProgress(String label, int percent) {
                switch (label) {
                    case "ASR:" -> asrProgress = percent;
                    case "LLM:" -> llmProgress = percent;
                    case "TTS:" -> ttsProgress = percent;
                }
            }

            @Override
            public void onComplete() {
                Minecraft.getInstance().execute(() -> {
                    showProgressBars = false;
                    TianshuGUI.this.init();
                });
            }

            @Override
            public void onError(String message) {
                Minecraft.getInstance().execute(() -> {
                    infoText = Component.literal(message);
                    infoTextExpiryTime = System.currentTimeMillis() + 5000;
                    showProgressBars = false;
                    TianshuGUI.this.init();
                });
            }
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

        if (envSetupNeeded && !coreManager.getEnvSetupManager().isSetupCompleted()) {
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

        drawEnginePhaseIndicator(guiGraphics, innerX, innerY, innerWidth);

        int blockHeight = (panelHeight - 58 - blockGap * 2) / 3;
        int blockStartY = innerY + 24;

        drawModelBlock(guiGraphics, innerX, blockStartY, innerWidth, blockHeight, "ASR", getDisplayModelName("ASR"), getAsrInfoLines());
        drawModelBlock(guiGraphics, innerX, blockStartY + blockHeight + blockGap, innerWidth, blockHeight, "LLM", getDisplayModelName("LLM"), getLlmInfoLines());
        drawModelBlock(guiGraphics, innerX, blockStartY + (blockHeight + blockGap) * 2, innerWidth, blockHeight, "TTS", getDisplayModelName("TTS"), getTtsInfoLines());

        if (showProgressBars && currentVramTier != VramTier.CUSTOM) {
            drawProgressOverlay(guiGraphics, innerX, blockStartY, innerWidth, blockHeight, blockGap);
        }
    }

    private void drawEnginePhaseIndicator(GuiGraphics guiGraphics, int x, int y, int width) {
        TianshuCoreManager.EnginePhase phase = coreManager.getEnginePhase();
        String label;
        int color;
        switch (phase) {
            case IDLE -> { label = "系统待机"; color = 0xFF888888; }
            case INITIALIZING -> { label = "系统启动中..."; color = 0xFFFFCC44; }
            case PARTIALLY_READY -> { label = "部分就绪"; color = 0xFF66BBFF; }
            case FULLY_READY -> { label = "系统就绪"; color = 0xFF66FF66; }
            case RESTARTING -> { label = "系统重启中..."; color = 0xFFFF8844; }
            case DESTROYED -> { label = "系统已关闭"; color = 0xFFFF4444; }
            default -> { label = "未知"; color = 0xFF888888; }
        }
        String statusText = "\u25CF " + label;
        guiGraphics.drawString(this.font, statusText, x, y, color);
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
            if (lineY > y + height - 14) break;
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
        if (coreManager.isPreviewRunning()) {
            lines.add("正在录音，请说话...");
        } else if (!asrPreviewResult.isEmpty()) {
            lines.add("识别结果: " + asrPreviewResult);
        } else {
            AsrModelInfo asrInfo = coreManager.resolveCurrentAsrModelInfo();
            if (asrInfo != null) {
                lines.add("类型: " + asrInfo.getModelType());
                if (asrInfo.isStreaming) lines.add("流式识别");
                lines.add("质量 " + asrInfo.getQualityTier() + " | 性能 " + asrInfo.getPerformanceClass());
                if (asrInfo.supportHotwords) {
                    Path asrDir = resolveModelDir("ASR");
                    boolean hasHotwords = asrDir != null && Files.exists(asrDir.resolve("hotwords.txt"));
                    lines.add(hasHotwords ? "热词增强已启用" : "可点击下方\"热词\"添加热词");
                }
            } else {
                Path asrDir = resolveModelDir("ASR");
                if (asrDir != null) {
                    boolean transducer = AsrEngine.detectTransducer(asrDir);
                    if (transducer) {
                        boolean hasHotwords = Files.exists(asrDir.resolve("hotwords.txt"));
                        lines.add(hasHotwords ? "热词增强已启用" : "可点击下方\"热词\"添加热词");
                    }
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
            lines.add(getPersonaLabel(settings.systemPrompt));
        }
        return lines;
    }

    private List<String> getTtsInfoLines() {
        List<String> lines = new ArrayList<>();
        if (coreManager.isPreviewRunning()) lines.add("正在播放试听...");
        Path ttsDir = resolveModelDir("TTS");
        if (ttsDir != null) {
            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(ttsDir);
            lines.add("语速: " + String.format("%.1f", settings.speed));
            TtsModelInfo info = coreManager.resolveCurrentTtsModelInfo();
            if (info != null && info.supportsVoiceClone()) {
                lines.add("音色: " + (settings.selectedVoiceSample == null || settings.selectedVoiceSample.isEmpty() ? "默认" : settings.selectedVoiceSample));
            } else if (info != null && info.supportsSpeakerSelection()) {
                lines.add("说话人: " + settings.speakerId);
            }
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
        coreManager.stopPreview();
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

    private void cyclePersona(ModelSettings.LlmSettings settings, Path modelDir) {
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
        private final Path modelDir;

        TtsSpeedSlider(int x, int y, int width, int height, float initialSpeed, Path modelDir) {
            super(x, y, width, height, Component.literal("语速"), Math.min(1.0, Math.max(0.0, initialSpeed / 5.0f)));
            this.speed = initialSpeed;
            this.modelDir = modelDir;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("语速: " + String.format("%.1f", speed) + "x"));
        }

        @Override
        protected void applyValue() {
            speed = Math.round((float) (value * 5.0) * 10.0f) / 10.0f;
            ttsSpeed = speed;
            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(modelDir);
            settings.speed = speed;
            ModelSettings.saveTtsSettings(modelDir, settings);
            updateMessage();
        }

        public float getSpeed() { return speed; }
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

            BrightButtonBuilder pos(int x, int y) { this.x = x; this.y = y; return this; }
            BrightButtonBuilder size(int width, int height) { this.width = width; this.height = height; return this; }
            BrightButton build() { return new BrightButton(x, y, width, height, message, onPress); }
        }
    }
}
