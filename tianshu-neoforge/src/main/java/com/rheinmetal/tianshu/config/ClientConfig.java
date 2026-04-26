package com.rheinmetal.tianshu.config;

import com.rheinmetal.tianshu.constant.ModelPresets;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.core.FeatureManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientConfig implements com.rheinmetal.tianshu.api.ITianshuConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue AI_ENABLED;
    public static final ModConfigSpec.EnumValue<TriggerMode> TRIGGER_MODE;
    public static final ModConfigSpec.ConfigValue<String> WAKE_WORD;
    public static final ModConfigSpec.EnumValue<VramTier> VRAM_TIER;
    public static final ModConfigSpec.IntValue CUSTOM_VRAM_GB;
    public static final ModConfigSpec.IntValue ASR_PORT;
    public static final ModConfigSpec.IntValue LLM_PORT;
    public static final ModConfigSpec.IntValue TTS_PORT;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_ASR_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_LLM_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_TTS_NAME;

    public static final ModConfigSpec.BooleanValue TACTICAL_RADAR_ENABLED;
    public static final ModConfigSpec.BooleanValue NAVIGATION_ENABLED;
    public static final ModConfigSpec.BooleanValue RECIPE_PANEL_ENABLED;
    public static final ModConfigSpec.BooleanValue AUDIO_RADAR_ENABLED;
    public static final ModConfigSpec.BooleanValue COMPANION_CARD_ENABLED;
    public static final ModConfigSpec.BooleanValue DURABILITY_ALERT_ENABLED;
    public static final ModConfigSpec.BooleanValue CHAT_ASSISTANT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("天枢 AI - 核心设置").push("general");
        AI_ENABLED = builder.define("enabled", true);
        TRIGGER_MODE = builder.defineEnum("triggerMode", TriggerMode.PUSH_TO_TALK);
        WAKE_WORD = builder.define("wakeWord", "天枢");
        builder.pop();

        builder.comment("功能开关（纯客户端）").push("features");
        TACTICAL_RADAR_ENABLED = builder.comment("战术 MR 系统")
                .define("tacticalRadar", true);
        NAVIGATION_ENABLED = builder.comment("导航 HUD")
                .define("navigation", true);
        RECIPE_PANEL_ENABLED = builder.comment("悬浮合成面板")
                .define("recipePanel", true);
        AUDIO_RADAR_ENABLED = builder.comment("听觉预警雷达")
                .define("audioRadar", true);
        COMPANION_CARD_ENABLED = builder.comment("智能伴生卡片")
                .define("companionCard", true);
        DURABILITY_ALERT_ENABLED = builder.comment("关键链路熔断预警系统")
                .define("durabilityAlert", true);
        CHAT_ASSISTANT = builder.comment("聊天助手（同声传译与语音发送）")
                .define("chatAssistant", true);
        builder.pop();

        builder.comment("性能与显存设置").push("performance");
        VRAM_TIER = builder.defineEnum("vramTier", VramTier.STANDARD);
        CUSTOM_VRAM_GB = builder.defineInRange("customVramGB", 8, 1, 128);
        builder.pop();

        builder.comment("底层服务设置（尽量不要修改）").push("internal");
        ASR_PORT = builder.defineInRange("asrPort", 18765, 1024, 65535);
        LLM_PORT = builder.defineInRange("llmPort", 18766, 1024, 65535);
        TTS_PORT = builder.defineInRange("ttsPort", 18767, 1024, 65535);
        CUSTOM_ASR_NAME = builder.define("customAsrName", "");
        CUSTOM_LLM_NAME = builder.define("customLlmName", "");
        CUSTOM_TTS_NAME = builder.define("customTtsName", "");
        builder.pop();

        SPEC = builder.build();
    }

    @Override
    public boolean isAiEnabled() {
        return AI_ENABLED.get();
    }

    @Override
    public void setAiEnabled(boolean enabled) {
        AI_ENABLED.set(enabled);
    }

    @Override
    public TriggerMode getTriggerMode() {
        return TRIGGER_MODE.get();
    }

    @Override
    public void setTriggerMode(TriggerMode mode) {
        TRIGGER_MODE.set(mode);
    }

    @Override
    public String getWakeWord() {
        return WAKE_WORD.get();
    }

    @Override
    public void setWakeWord(String word) {
        WAKE_WORD.set(word);
    }

    @Override
    public VramTier getVramTier() {
        return VRAM_TIER.get();
    }

    @Override
    public void setVramTier(VramTier tier) {
        VRAM_TIER.set(tier);
    }

    @Override
    public int getCustomVramGB() {
        return CUSTOM_VRAM_GB.get();
    }

    @Override
    public int getAsrPort() {
        return ASR_PORT.get();
    }

    @Override
    public int getLlmPort() {
        return LLM_PORT.get();
    }

    @Override
    public int getTtsPort() {
        return TTS_PORT.get();
    }

    @Override
    public String getCustomAsrName() {
        return CUSTOM_ASR_NAME.get();
    }

    @Override
    public void setCustomAsrName(String name) {
        CUSTOM_ASR_NAME.set(name);
    }

    @Override
    public String getCustomLlmName() {
        return CUSTOM_LLM_NAME.get();
    }

    @Override
    public void setCustomLlmName(String name) {
        CUSTOM_LLM_NAME.set(name);
    }

    @Override
    public String getCustomTtsName() {
        return CUSTOM_TTS_NAME.get();
    }

    @Override
    public void setCustomTtsName(String name) {
        CUSTOM_TTS_NAME.set(name);
    }

    @Override
    public Path getRootPath() {
        return Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), ModelPresets.DEFAULT_ROOT_DIR_NAME);
    }

    @Override
    public Path getAsrBasePath() {
        return getRootPath().resolve("models/asr");
    }

    @Override
    public Path getLlmBasePath() {
        return getRootPath().resolve("models/llm");
    }

    @Override
    public Path getTtsBasePath() {
        return getRootPath().resolve("models/tts");
    }

    @Override
    public Path getVoiceLibraryPath() {
        return getRootPath().resolve("voices");
    }

    @Override
    public Path getAsrModelPath() {
        VramTier tier = getVramTier();
        String name = ModelPresets.resolveTargetModelName(
                tier,
                ModelPresets.getPresetAsrName(VramTier.LIGHT),
                ModelPresets.getPresetAsrName(VramTier.STANDARD),
                ModelPresets.getPresetAsrName(VramTier.DELUXE),
                getCustomAsrName()
        );
        return getAsrBasePath().resolve(name);
    }

    @Override
    public Path getLlmModelPath() {
        VramTier tier = getVramTier();
        String name = ModelPresets.resolveTargetModelName(
                tier,
                ModelPresets.getPresetLlmName(VramTier.LIGHT),
                ModelPresets.getPresetLlmName(VramTier.STANDARD),
                ModelPresets.getPresetLlmName(VramTier.DELUXE),
                getCustomLlmName()
        );
        return getLlmBasePath().resolve(name);
    }

    @Override
    public Path getTtsModelPath() {
        VramTier tier = getVramTier();
        String name = ModelPresets.resolveTargetModelName(
                tier,
                ModelPresets.getPresetTtsName(VramTier.LIGHT),
                ModelPresets.getPresetTtsName(VramTier.STANDARD),
                ModelPresets.getPresetTtsName(VramTier.DELUXE),
                getCustomTtsName()
        );
        return getTtsBasePath().resolve(name);
    }

    @Override
    public Path getLlmGgufFilePath() {
        VramTier tier = getVramTier();
        String modelName = ModelPresets.resolveTargetModelName(
                tier,
                ModelPresets.getPresetLlmName(VramTier.LIGHT),
                ModelPresets.getPresetLlmName(VramTier.STANDARD),
                ModelPresets.getPresetLlmName(VramTier.DELUXE),
                getCustomLlmName()
        );
        Path modelDir = getLlmBasePath().resolve(modelName);

        if (tier == VramTier.CUSTOM) {
            String customName = getCustomLlmName();
            if (customName != null && customName.trim().toLowerCase().endsWith(".gguf")) {
                return getLlmBasePath().resolve(customName.trim());
            }
            if (Files.isDirectory(modelDir)) {
                Path preferred = modelDir.resolve("model.gguf");
                if (Files.isRegularFile(preferred)) {
                    return preferred;
                }
                try (var stream = Files.list(modelDir)) {
                    Path ggufFile = stream
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".gguf"))
                        .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                        .findFirst()
                        .orElse(null);
                    if (ggufFile != null) {
                        return ggufFile;
                    }
                } catch (IOException e) {
                }
            }
            return modelDir.resolve("model.gguf");
        }

        return modelDir.resolve(ModelPresets.getPresetLlmFileName(tier));
    }

    public boolean isTacticalRadarEnabled() {
        return TACTICAL_RADAR_ENABLED.get();
    }

    public void setTacticalRadarEnabled(boolean enabled) {
        TACTICAL_RADAR_ENABLED.set(enabled);
    }

    public boolean isNavigationEnabled() {
        return NAVIGATION_ENABLED.get();
    }

    public void setNavigationEnabled(boolean enabled) {
        NAVIGATION_ENABLED.set(enabled);
    }

    public boolean isRecipePanelEnabled() {
        return RECIPE_PANEL_ENABLED.get();
    }

    public void setRecipePanelEnabled(boolean enabled) {
        RECIPE_PANEL_ENABLED.set(enabled);
    }

    public boolean isAudioRadarEnabled() {
        return AUDIO_RADAR_ENABLED.get();
    }

    public void setAudioRadarEnabled(boolean enabled) {
        AUDIO_RADAR_ENABLED.set(enabled);
    }

    public boolean isCompanionCardEnabled() {
        return COMPANION_CARD_ENABLED.get();
    }

    public void setCompanionCardEnabled(boolean enabled) {
        COMPANION_CARD_ENABLED.set(enabled);
    }

    public boolean isDurabilityAlertEnabled() {
        return DURABILITY_ALERT_ENABLED.get();
    }

    public void setDurabilityAlertEnabled(boolean enabled) {
        DURABILITY_ALERT_ENABLED.set(enabled);
    }

    public static void syncToFeatureManager() {
        FeatureManager.syncFromClientConfig(
                TACTICAL_RADAR_ENABLED.get(),
                NAVIGATION_ENABLED.get(),
                RECIPE_PANEL_ENABLED.get(),
                AUDIO_RADAR_ENABLED.get(),
                COMPANION_CARD_ENABLED.get(),
                DURABILITY_ALERT_ENABLED.get(),
                CHAT_ASSISTANT.get()
        );
    }

    @Override
    public void save() {
        SPEC.save();
    }
}
