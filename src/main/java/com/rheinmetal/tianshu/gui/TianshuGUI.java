package com.rheinmetal.tianshu.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.rheinmetal.tianshu.client.TianshuClient;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.config.Config.TriggerMode;
import com.rheinmetal.tianshu.config.Config.VramTier;
import com.rheinmetal.tianshu.config.ModelUrls;
import com.rheinmetal.tianshu.model.ModelDownloader;
import com.rheinmetal.tianshu.core.ProcessManager;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.EnvSetupManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private static final Component ASR_SETTINGS_TITLE = Component.literal("ASR 模型");
    private static final Component LLM_SETTINGS_TITLE = Component.literal("LLM 模型");
    private static final Component TTS_SETTINGS_TITLE = Component.literal("TTS 模型");
    private static final Component APPLY_CONFIG_TEXT = Component.literal("应用配置");
    private static final Component EXIT_TEXT = Component.literal("退出");
    private static final Component CONFIG_SAVED_TEXT = Component.literal("配置已保存");

    private boolean isEnabled;
    private TriggerMode currentMode;
    private VramTier currentVramTier;
    private EditBox wakeWordEditBox;

    private boolean showProgressBars = false;
    private Button downloadPresetBtn;
    private java.util.Queue<java.util.Map.Entry<String, java.nio.file.Path>> downloadQueue;
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
    private boolean isWaitingForKey = false;
    private static final Component WAITING_KEY_TEXT = Component.literal(">>> 请按下任意键 <<<");

    private final Config.VramTier initialVramTier;
    private final String initialCustomAsr;
    private final String initialCustomLlm;
    private final String initialCustomTts;

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
        super.init(); this.clearWidgets();

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
        int screenWidth = this.width;
        int buttonWidth = 200;
        int buttonHeight = 25;

        this.addRenderableWidget(Button.builder(
            Component.literal("检测运行环境"),
            b -> {
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

                            com.rheinmetal.tianshu.Tianshu.LOGGER.info("环境检测通过，重新加载 Native 库...");
                            try {
                                com.rheinmetal.tianshu.Tianshu.reloadNative();
                            } catch (Exception e) {
                                com.rheinmetal.tianshu.Tianshu.LOGGER.error("重新加载 Native 库失败", e);
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
            }
        ).pos((screenWidth - buttonWidth) / 2, this.height / 2 - 20).size(buttonWidth, buttonHeight).build());
    }

    private void initMainScreen() {
        int screenWidth = this.width;
        int buttonWidth = 200; int buttonHeight = 25; int buttonSpacing = 15;

        // 1. AI开关
        this.addRenderableWidget(Button.builder(isEnabled ? AI_ENABLED_TEXT : AI_DISABLED_TEXT, b -> { isEnabled = !isEnabled; Config.AI_ENABLED.set(isEnabled); Config.SPEC.save(); this.init(); }).pos((screenWidth - buttonWidth) / 2, 50).size(buttonWidth, buttonHeight).build());

        // 2. 触发模式
        int triggerButtonY = 90;
        this.addRenderableWidget(
            Button.builder(getTriggerModeText(currentMode), b -> { switch (currentMode) { case ALWAYS -> currentMode = TriggerMode.PUSH_TO_TALK; case PUSH_TO_TALK -> currentMode = TriggerMode.WAKE_WORD; case WAKE_WORD -> currentMode = TriggerMode.ALWAYS; } Config.TRIGGER_MODE.set(currentMode); Config.SPEC.save(); this.init(); }).pos((screenWidth - buttonWidth) / 2, triggerButtonY).size(buttonWidth, buttonHeight).build());

        // 3. 动态区域
        int dynamicY = 130;
        
        if (currentMode == TriggerMode.WAKE_WORD) {
            wakeWordEditBox = new EditBox(this.font, (screenWidth - 200) / 2, dynamicY, 200, 20, WAKE_WORD_PROMPT);
            wakeWordEditBox.setValue(Config.WAKE_WORD.get());
            wakeWordEditBox.setResponder(Config.WAKE_WORD::set);
            this.addRenderableWidget(wakeWordEditBox);
        }

        // 4. 模型预设 + 下载按钮
        int presetY = dynamicY + buttonHeight + buttonSpacing;
        int presetButtonWidth = 150; int downloadButtonWidth = 100;
        int startX = (screenWidth - presetButtonWidth - downloadButtonWidth - 10) / 2;

        this.addRenderableWidget(Button.builder(getVramTierText(currentVramTier), b -> { switch (currentVramTier) { case LIGHT -> currentVramTier = VramTier.STANDARD; case STANDARD -> currentVramTier = VramTier.DELUXE; case DELUXE -> currentVramTier = VramTier.CUSTOM; case CUSTOM -> currentVramTier = VramTier.LIGHT; } Config.VRAM_TIER.set(currentVramTier); Config.SPEC.save(); showProgressBars = false; this.init(); }).pos(startX, presetY).size(presetButtonWidth, buttonHeight).build());

        // 【关键修复】判断是否已下载，动态改变按钮状态和文字
        boolean isCustom = (currentVramTier == VramTier.CUSTOM);
        boolean isAlreadyDownloaded = !isCustom && checkPresetModelsExist(currentVramTier);

        downloadPresetBtn = Button.builder(isAlreadyDownloaded ? DOWNLOADED_TEXT : DOWNLOAD_PRESET_TEXT, b -> {
            if (!isAlreadyDownloaded) {
                // 【关键修复】：点击下载时，必须先同步预设到 Config，否则下载器路径会错乱！
                Config.VRAM_TIER.set(currentVramTier);
                Config.SPEC.save();

                showProgressBars = true; b.active = false; ModelDownload();
            }
        }).pos(startX + presetButtonWidth + 10, presetY).size(downloadButtonWidth, buttonHeight).build();
        downloadPresetBtn.active = !isCustom && !isAlreadyDownloaded && !showProgressBars;
        this.addRenderableWidget(downloadPresetBtn);

        // 5. 核心改造：模型选择按钮区域
        int contentStartY = presetY + buttonHeight + buttonSpacing+10;
        int panelWidth = 200; int panelX = (screenWidth - panelWidth) / 2;
        int sectionHeight = 70; int modelButtonHeight = 20;

        // ASR 模型选择按钮
        String asrButtonText;
        if (isCustom) {
            asrButtonText = Config.CUSTOM_ASR_NAME.get() != null && !Config.CUSTOM_ASR_NAME.get().isEmpty() ? Config.CUSTOM_ASR_NAME.get() : "选择ASR模型";
        } else {
            asrButtonText = Config.getPresetAsrName(currentVramTier);
        }
        Button asrModelButton = Button.builder(Component.literal(asrButtonText), b -> {
            if (isCustom) {
                // 打开文件夹选择
                openModelFolderSelector("ASR");
            }
        }).pos(panelX, contentStartY).size(panelWidth, modelButtonHeight).build();
        asrModelButton.active = isCustom;
        this.addRenderableWidget(asrModelButton);

        // LLM 模型选择按钮
        String llmButtonText;
        if (isCustom) {
            llmButtonText = Config.CUSTOM_LLM_NAME.get() != null && !Config.CUSTOM_LLM_NAME.get().isEmpty() ? Config.CUSTOM_LLM_NAME.get() : "选择LLM模型";
        } else {
            llmButtonText = Config.getPresetLlmName(currentVramTier);
        }
        Button llmModelButton = Button.builder(Component.literal(llmButtonText), b -> {
            if (isCustom) {
                // 打开文件夹选择
                openModelFolderSelector("LLM");
            }
        }).pos(panelX, contentStartY + sectionHeight).size(panelWidth, modelButtonHeight).build();
        llmModelButton.active = isCustom;
        this.addRenderableWidget(llmModelButton);

        // TTS 模型选择按钮
        String ttsButtonText;
        if (isCustom) {
            ttsButtonText = Config.CUSTOM_TTS_NAME.get() != null && !Config.CUSTOM_TTS_NAME.get().isEmpty() ? Config.CUSTOM_TTS_NAME.get() : "选择TTS模型";
        } else {
            ttsButtonText = Config.getPresetTtsName(currentVramTier);
        }
        Button ttsModelButton = Button.builder(Component.literal(ttsButtonText), b -> {
            if (isCustom) {
                // 打开文件夹选择
                openModelFolderSelector("TTS");
            }
        }).pos(panelX, contentStartY + sectionHeight * 2).size(panelWidth, modelButtonHeight).build();
        ttsModelButton.active = isCustom;
        this.addRenderableWidget(ttsModelButton);

        // 6. 底部按钮
        int bottomBtnWidth = 100; int buttonY = this.height - 30;
        int applyButtonX = (this.width - bottomBtnWidth * 2 - 10) / 2;
        int exitButtonX = applyButtonX + bottomBtnWidth + 10;
        this.addRenderableWidget(Button.builder(APPLY_CONFIG_TEXT, b -> { saveConfig(); }).pos(applyButtonX, buttonY).size(bottomBtnWidth, buttonHeight).build());
        this.addRenderableWidget(Button.builder(EXIT_TEXT, b -> this.onClose()).pos(exitButtonX, buttonY).size(bottomBtnWidth, buttonHeight).build());
    }
    // 检查当前预设的模型文件/文件夹是否已经存在于硬盘上（深度校验）
    private boolean checkPresetModelsExist(VramTier tier) {
        Path asrDir = Config.getAsrBasePath().resolve(Config.getPresetAsrName(tier));
        Path llmDir = Config.getLlmBasePath().resolve(Config.getPresetLlmName(tier));
        Path ttsDir = Config.getTtsBasePath().resolve(Config.getPresetTtsName(tier));
        
        return checkAsrModelExists(asrDir) && checkLlmModelExists(llmDir) && checkTtsModelExists(ttsDir);
    }
    
    // 深度检查ASR模型是否存在
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
    
    // 深度检查LLM模型是否存在
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
    
    // 深度检查TTS模型是否存在
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

    // 预设对应的真实文件夹/文件名（需要跟你实际下载解压后的名字保持一致！）
    // 使用Config中的方法获取预设模型名称

    private void saveConfig() {
        // 1. 把界面的状态刷入 Config（不涉及 processManager，非常安全）
        Config.AI_ENABLED.set(isEnabled);
        Config.TRIGGER_MODE.set(currentMode);
        if (currentMode == TriggerMode.WAKE_WORD && wakeWordEditBox != null) Config.WAKE_WORD.set(wakeWordEditBox.getValue());
        Config.VRAM_TIER.set(currentVramTier);

        // 2. 对比现在的 Config 和刚打开界面时的快照
        boolean modelChanged = !this.currentVramTier.equals(this.initialVramTier) ||
                !java.util.Objects.equals(Config.CUSTOM_ASR_NAME.get(), this.initialCustomAsr) ||
                !java.util.Objects.equals(Config.CUSTOM_LLM_NAME.get(), this.initialCustomLlm) ||
                !java.util.Objects.equals(Config.CUSTOM_TTS_NAME.get(), this.initialCustomTts);

        // 3. 统一保存
        Config.SPEC.save();
        // 如果模型路径变化，重启服务
        if (modelChanged) {
            infoText = Component.literal("模型切换中，请稍候...");
            infoTextExpiryTime = System.currentTimeMillis() + 5000;
            
            // 在启动线程前，提前拿到当前 processManager 的引用，防止并发被替换
            final ProcessManager currentProcessManager = TianshuClient.getProcessManager();
            new Thread(() -> {
                // 1. 重置状态标记
                TianshuClient.asrReady = false;
                TianshuClient.llmReady = false;
                TianshuClient.ttsReady = false;

                // 2. 销毁 ASR 引擎
                TianshuCoreManager.getInstance().destroyOnWorldLeft();

                // 3. 杀死旧的 LLM/TTS 进程
                currentProcessManager.stopServices();

                // 4. 原地睡 2 秒
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                // 5. 重新拉起 ASR 引擎 (Config里已经是新路径了)
                TianshuCoreManager.getInstance().tryInitOnWorldJoined();

                // 6. 重新拉起 LLM 进程 (不要去管 ensureInitialized，不要动 Workers！)
                currentProcessManager.startLlmServerForDev();
            }, "Tianshu-Restarter").start();
        } else {
            infoText = CONFIG_SAVED_TEXT;
            infoTextExpiryTime = System.currentTimeMillis() + 3000;
        }
    }

    // 打开模型文件夹选择器
    private void openModelFolderSelector(String modelType) {
        try {
            Path basePath = switch (modelType) {
                case "ASR" -> Config.getAsrBasePath();
                case "LLM" -> Config.getLlmBasePath();
                case "TTS" -> Config.getTtsBasePath();
                default -> throw new IllegalArgumentException("Unknown model type: " + modelType);
            };

            // 确保基目录存在
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
            }

            // 扫描基目录下的所有文件夹
            List<Path> validDirs = Files.list(basePath)
                    .filter(Files::isDirectory)
                    .filter(dir -> {
                        try {
                            switch (modelType) {
                                case "ASR":
                                    return Files.list(dir).anyMatch(p -> p.toString().endsWith(".onnx") || p.toString().endsWith(".bin"));
                                case "LLM":
                                    return Files.list(dir).anyMatch(p -> p.toString().endsWith(".gguf"));
                                case "TTS":
                                    return Files.list(dir).anyMatch(p -> p.toString().endsWith(".onnx") || p.toString().endsWith(".bin") || p.toString().endsWith(".gguf"));
                                default:
                                    return false;
                            }
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            // 创建文件夹选择屏幕
            if (validDirs.isEmpty()) {
                infoText = Component.literal("未找到有效模型文件夹");
                infoTextExpiryTime = System.currentTimeMillis() + 3000;
                return;
            }

            // 简单实现：显示一个包含文件夹列表的屏幕
            // 这里使用临时的选择屏幕
            Minecraft.getInstance().setScreen(new Screen(Component.literal("选择" + modelType + "模型")) {
                private final List<Button> folderButtons = new ArrayList<>();

                @Override
                protected void init() {
                    super.init();
                    int buttonY = 50;
                    for (Path dir : validDirs) {
                        String folderName = dir.getFileName().toString();
                        Button folderButton = Button.builder(Component.literal(folderName), b -> {
                            // 保存选择的文件夹名称
                            switch (modelType) {
                                case "ASR":
                                    Config.CUSTOM_ASR_NAME.set(folderName);
                                    break;
                                case "LLM":
                                    Config.CUSTOM_LLM_NAME.set(folderName);
                                    break;
                                case "TTS":
                                    Config.CUSTOM_TTS_NAME.set(folderName);
                                    break;
                            }
                            Config.SPEC.save();
                            // 返回主屏幕
                            Minecraft.getInstance().setScreen(new TianshuGUI());
                        }).pos((width - 200) / 2, buttonY).size(200, 20).build();
                        folderButtons.add(folderButton);
                        addRenderableWidget(folderButton);
                        buttonY += 30;
                    }

                    // 添加取消按钮
                    Button cancelButton = Button.builder(Component.literal("取消"), b -> {
                        Minecraft.getInstance().setScreen(new TianshuGUI());
                    }).pos((width - 100) / 2, buttonY + 20).size(100, 20).build();
                    addRenderableWidget(cancelButton);
                }

                @Override
                public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                    renderBackground(guiGraphics, mouseX, mouseY, partialTick);
                    super.render(guiGraphics, mouseX, mouseY, partialTick);
                    guiGraphics.drawString(font, "选择" + modelType + "模型文件夹", (width - font.width("选择" + modelType + "模型文件夹")) / 2, 20, 0xFFFFFF);
                }
            });
        } catch (IOException e) {
            com.rheinmetal.tianshu.Tianshu.LOGGER.error("打开模型文件夹选择器失败", e);
            infoText = Component.literal("打开文件夹选择器失败");
            infoTextExpiryTime = System.currentTimeMillis() + 3000;
        }
    }

    private Component getVramTierText(VramTier tier) { return switch (tier) { case LIGHT -> VRAM_TIER_LIGHT_TEXT; case STANDARD -> VRAM_TIER_STANDARD_TEXT; case DELUXE -> VRAM_TIER_DELUXE_TEXT; case CUSTOM -> VRAM_TIER_CUSTOM_TEXT; }; }
    private Component getTriggerModeText(TriggerMode mode) { return switch (mode) { case ALWAYS -> TRIGGER_MODE_ALWAYS_TEXT; case PUSH_TO_TALK -> TRIGGER_MODE_PUSH_TO_TALK_TEXT; case WAKE_WORD -> TRIGGER_MODE_WAKE_WORD_TEXT; }; }

    // ==================== 下载逻辑 ====================
    private void ModelDownload() {
        // 1. 检查URL配置 (保持原有逻辑不变)
        java.util.List<String> asrUrls = getAsrUrlByTier(currentVramTier);
        String llmUrl = getLlmUrlByTier(currentVramTier);
        java.util.List<String> ttsUrls = getTtsUrlByTier(currentVramTier);
        if (asrUrls.stream().anyMatch(url -> url.contains("example.com")) || llmUrl.contains("example.com") || ttsUrls.stream().anyMatch(url -> url.contains("example.com")) || asrUrls.isEmpty() || llmUrl.isEmpty() || ttsUrls.isEmpty()) {
            handleDownloadError("模型下载链接未配置"); return;
        }

        // 2. 收集所有需要下载的任务到队列
        downloadQueue = new java.util.LinkedList<>();
        java.nio.file.Path asrDir = Config.getAsrModelPath();
        for (String url : asrUrls) { addTaskToQueue(url, asrDir, "ASR:"); }
        addTaskToQueue(llmUrl, Config.getLlmModelPath(), "LLM:");
        java.nio.file.Path ttsDir = Config.getTtsModelPath();
        for (String url : ttsUrls) { addTaskToQueue(url, ttsDir, "TTS:"); }

        // 3. 如果队列不为空，开始下载第一个
        if (!downloadQueue.isEmpty()) {
            startNextDownload();
        } else {
            handleDownloadComplete(); // 全部已存在
        }
    }

    // 辅助方法：解析URL并加入队列（如果文件已存在则跳过）
    private void addTaskToQueue(String url, java.nio.file.Path baseDir, String label) {
        try {
            String fileName = new java.net.URI(url).getPath().substring(new java.net.URI(url).getPath().lastIndexOf('/') + 1);
            java.nio.file.Path filePath = baseDir.resolve(fileName);
            if (!java.nio.file.Files.exists(filePath)) {
                downloadQueue.add(new java.util.AbstractMap.SimpleEntry<>(url, filePath));
            }
        } catch (Exception e) { handleDownloadError("URL解析失败: " + url); }
    }

    // 新增 startNextDownload() 方法
    private void startNextDownload() {
        if (downloadQueue == null || downloadQueue.isEmpty()) {
            handleDownloadComplete();
            return;
        }
        java.util.Map.Entry<String, java.nio.file.Path> task = downloadQueue.poll();
        String url = task.getKey();
        java.nio.file.Path filePath = task.getValue();

        // 判断当前任务标签
        if (filePath.startsWith(Config.getAsrModelPath())) currentDownloadLabel = "ASR:";
        else if (filePath.startsWith(Config.getLlmModelPath())) currentDownloadLabel = "LLM:";
        else currentDownloadLabel = "TTS:";

        // 重置对应分类的进度
        if (currentDownloadLabel.equals("ASR:")) asrProgress = 0;
        else if (currentDownloadLabel.equals("LLM:")) llmProgress = 0;
        else ttsProgress = 0;

        ModelDownloader.downloadAsync(url, filePath, new ModelDownloader.DownloadCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (totalBytes > 0) {
                    int progress = (int) Math.round((double) downloadedBytes * 100 / totalBytes);
                    if (currentDownloadLabel.equals("ASR:")) asrProgress = progress;
                    else if (currentDownloadLabel.equals("LLM:")) llmProgress = progress;
                    else ttsProgress = progress;
                }
            }
            @Override
            public void onSuccess(java.nio.file.Path p) {
                startNextDownload(); // 当前成功，立刻触发下一个
            }
            @Override
            public void onError(String m) {
                downloadQueue.clear(); // 出错清空剩余任务
                handleDownloadError(m);
            }
        });
    }

    private java.util.List<String> getAsrUrlByTier(VramTier tier) { return switch (tier) { case LIGHT -> ModelUrls.ASR_LIGHT_URLS; case STANDARD -> ModelUrls.ASR_STANDARD_URLS; case DELUXE -> ModelUrls.ASR_DELUXE_URLS; default -> ModelUrls.ASR_STANDARD_URLS; }; }
    private String getLlmUrlByTier(VramTier tier) { return switch (tier) { case LIGHT -> ModelUrls.LLM_LIGHT_URL; case STANDARD -> ModelUrls.LLM_STANDARD_URL; case DELUXE -> ModelUrls.LLM_DELUXE_URL; default -> ModelUrls.LLM_STANDARD_URL; }; }
    private java.util.List<String> getTtsUrlByTier(VramTier tier) { return switch (tier) { case LIGHT -> ModelUrls.TTS_LIGHT_URLS; case STANDARD -> ModelUrls.TTS_STANDARD_URLS; case DELUXE -> ModelUrls.TTS_DELUXE_URLS; default -> ModelUrls.TTS_STANDARD_URLS; }; }

    private void handleDownloadComplete() { Minecraft.getInstance().execute(() -> { showProgressBars = false; this.init(); }); }
    private void handleDownloadError(String errorMessage) { Minecraft.getInstance().execute(() -> { infoText = Component.literal(errorMessage); infoTextExpiryTime = System.currentTimeMillis() + 5000; showProgressBars = false; this.init(); }); }

    // ==================== 渲染逻辑 ====================
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawString(this.font, TITLE, (this.width - this.font.width(TITLE)) / 2, 20, 0xFFFFFF);

        if (envSetupInProgress) {
            int progressBarWidth = 200;
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

        int buttonHeight = 25, buttonSpacing = 15;
        int presetY = 130 + buttonHeight + buttonSpacing;
        int contentStartY = presetY + buttonHeight + buttonSpacing;
        int sectionHeight = 70;

        // 根据模式切换标题颜色：自定义模式黄色，预设模式灰色
        int titleColor = (currentVramTier == VramTier.CUSTOM) ? 0xFFFF00 : 0xAAAAAA;

        // 永远绘制模型标题
        guiGraphics.drawString(this.font, ASR_SETTINGS_TITLE, (this.width - this.font.width(ASR_SETTINGS_TITLE)) / 2, contentStartY, titleColor);
        guiGraphics.drawString(this.font, LLM_SETTINGS_TITLE, (this.width - this.font.width(LLM_SETTINGS_TITLE)) / 2, contentStartY + sectionHeight, titleColor);
        guiGraphics.drawString(this.font, TTS_SETTINGS_TITLE, (this.width - this.font.width(TTS_SETTINGS_TITLE)) / 2, contentStartY + sectionHeight * 2, titleColor);

        // 如果正在下载，覆盖一层进度条
        if (showProgressBars && currentVramTier != VramTier.CUSTOM) {
            int progressBarWidth = 200; int progressBarHeight = 10;
            int progressBarX = (this.width - progressBarWidth) / 2; int textOffset = 200 + 10;
            // 绘制在列表上方，遮挡住列表
            drawSingleProgressBar(guiGraphics, "ASR:", asrProgress, progressBarX, contentStartY + 45, progressBarWidth, progressBarHeight, textOffset);
            drawSingleProgressBar(guiGraphics, "LLM:", llmProgress, progressBarX, contentStartY + sectionHeight + 45, progressBarWidth, progressBarHeight, textOffset);
            drawSingleProgressBar(guiGraphics, "TTS:", ttsProgress, progressBarX, contentStartY + sectionHeight * 2 + 45, progressBarWidth, progressBarHeight, textOffset);
        }

        // 底部提示信息
        if (!infoText.getString().isEmpty() && System.currentTimeMillis() < infoTextExpiryTime) {
            guiGraphics.drawString(this.font, infoText, (this.width - this.font.width(infoText)) / 2, this.height - 60, 0xFFFF00);
        } else if (System.currentTimeMillis() >= infoTextExpiryTime && !infoText.getString().isEmpty()) {
            infoText = Component.literal("");
        }
    }

    private void drawSingleProgressBar(GuiGraphics g, String label, int percent, int x, int y, int w, int h, int tOff) {
        g.drawString(this.font, label, x, y - 10, 0xFFFFFF);
        g.fill(x, y, x + w, y + h, 0xFF333333);
        g.fill(x, y, x + (w * percent / 100), y + h, 0xFF00FF00);
        g.drawString(this.font, percent + "%", x + tOff, y - 10, 0xFFFFFF);
    }

    @Override public void onClose() { super.onClose(); }
    @Override public boolean isPauseScreen() { return false; }
}
