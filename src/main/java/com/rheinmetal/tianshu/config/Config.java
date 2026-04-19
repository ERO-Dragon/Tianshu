package com.rheinmetal.tianshu.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {
    public static final String DEFAULT_ROOT_DIR_NAME = "config/TianshuAIAssistant";
    public static final ModConfigSpec SPEC;

    // --- 基础配置 ---
    public static final ModConfigSpec.BooleanValue AI_ENABLED;
    public enum TriggerMode { ALWAYS, PUSH_TO_TALK, WAKE_WORD }
    public static final ModConfigSpec.EnumValue<TriggerMode> TRIGGER_MODE;
    public static final ModConfigSpec.ConfigValue<String> WAKE_WORD;

    // --- 性能配置 ---
    public enum VramTier { LIGHT, STANDARD, DELUXE, CUSTOM }
    public static final ModConfigSpec.EnumValue<VramTier> VRAM_TIER;
    public static final ModConfigSpec.IntValue CUSTOM_VRAM_GB;

    // --- GUI 专属存储容器 (玩家不可见，由 GUI 界面自动读写) ---
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_ASR_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_LLM_NAME;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_TTS_NAME;

    // --- 内部配置 (高级用户/隐藏) ---
    public static final ModConfigSpec.IntValue ASR_PORT;
    public static final ModConfigSpec.IntValue LLM_PORT;
    public static final ModConfigSpec.IntValue TTS_PORT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("=======================", "天枢 AI - 核心设置", "=======================").push("general");
        AI_ENABLED = builder.define("enabled", true);
        TRIGGER_MODE = builder.defineEnum("triggerMode", TriggerMode.PUSH_TO_TALK);
        WAKE_WORD = builder.define("wakeWord", "天枢");
        builder.pop();

        builder.comment("=======================", "性能与显存设置", "=======================").push("performance");
        VRAM_TIER = builder.defineEnum("vramTier", VramTier.STANDARD);
        CUSTOM_VRAM_GB = builder.defineInRange("customVramGB", 8, 1, 128);
        builder.pop();

        builder.comment("=======================", "底层服务设置 (普通玩家请勿修改)", "=======================").push("internal");
        ASR_PORT = builder.defineInRange("asrPort", 18765, 1024, 65535);
        LLM_PORT = builder.defineInRange("llmPort", 18766, 1024, 65535);
        TTS_PORT = builder.defineInRange("ttsPort", 18767, 1024, 65535);

        // 【修复点】：把这三个变量藏在 internal 分组下，默认值为空字符串
        CUSTOM_ASR_NAME = builder.comment("GUI内部使用，请勿手动修改").define("customAsrName", "");
        CUSTOM_LLM_NAME = builder.comment("GUI内部使用，请勿手动修改").define("customLlmName", "");
        CUSTOM_TTS_NAME = builder.comment("GUI内部使用，请勿手动修改").define("customTtsName", "");
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {}

    // --- 约定好的绝对路径逻辑 ---
    public static Path getRootPath() {
        return Paths.get(net.minecraft.client.Minecraft.getInstance().gameDirectory.getAbsolutePath(), DEFAULT_ROOT_DIR_NAME);
    }
    public static Path getAsrBasePath() { return getRootPath().resolve("models/asr"); }
    public static Path getLlmBasePath() { return getRootPath().resolve("models/llm"); }
    public static Path getTtsBasePath() { return getRootPath().resolve("models/tts"); }

    // 统一返回模型所在的根文件夹路径 (必须是纯文件夹名)
    public static String getPresetAsrName(VramTier tier) {
        return switch (tier) {
            case LIGHT -> "Zipformer";
            case STANDARD -> "ParaformerOnnx";
            case DELUXE -> "ParaformerOnnx";
            default -> "Zipformer";
        };
    }

    public static String getPresetLlmName(VramTier tier) {
        return switch (tier) {
            case LIGHT -> "Qwen3-0.6B";
            case STANDARD -> "Qwen3-4B";
            case DELUXE -> "Qwen3-7B";
            default -> "Qwen3-0.6B";
        };
    }

    public static String getPresetTtsName(VramTier tier) {
        return switch (tier) {
            case LIGHT -> "PiperTTS";
            case STANDARD -> "MeloTTS";
            case DELUXE -> "MossTTSNano";
            default -> "PiperTTS";
        };
    }

    public static Path getAsrModelPath() {
        return getAsrBasePath().resolve(getTargetFileName(getPresetAsrName(VramTier.LIGHT), getPresetAsrName(VramTier.STANDARD), getPresetAsrName(VramTier.DELUXE), CUSTOM_ASR_NAME.get()));
    }

    // 统一返回模型所在的根文件夹路径
    public static Path getLlmModelPath() {
        return getLlmBasePath().resolve(getTargetFileName(getPresetLlmName(VramTier.LIGHT), getPresetLlmName(VramTier.STANDARD), getPresetLlmName(VramTier.DELUXE), CUSTOM_LLM_NAME.get()));
    }

    // 统一返回模型所在的根文件夹路径 (必须是纯文件夹名)
    public static Path getTtsModelPath() {
        return getTtsBasePath().resolve(getTargetFileName(getPresetTtsName(VramTier.LIGHT), getPresetTtsName(VramTier.STANDARD), getPresetTtsName(VramTier.DELUXE), CUSTOM_TTS_NAME.get()));
    }

    private static String getTargetFileName(String lightName, String standardName, String deluxeName, String customName) {
        VramTier currentTier = VRAM_TIER.get();

        // 1. 如果是自定义模式，且 GUI 写入了文件名，就用它
        if (currentTier == VramTier.CUSTOM && customName != null && !customName.trim().isEmpty()) {
            return customName.trim();
        }

        // 2. 否则根据预设映射
        switch (currentTier) {
            case LIGHT: return lightName;
            case STANDARD: return standardName;
            case DELUXE: return deluxeName;
            default: return lightName; // 兜底
        }
    }
    
    // 获取预设LLM文件名
    private static String getPresetLlmFileName(VramTier tier) {
        return switch (tier) {
            case LIGHT -> "Qwen3-0.6B-Q4_K_M.gguf";
            case STANDARD -> "Qwen3-4B-Q4_K_M.gguf";
            case DELUXE -> "qwen-deluxe.gguf";
            default -> "Qwen3-0.6B-Q4_K_M.gguf";
        };
    }
    //提供给llm启动用的gguf文件路径
    public static Path getLlmGgufFilePath() {
        VramTier tier = VRAM_TIER.get();
        String modelName = getTargetFileName(
                getPresetLlmName(VramTier.LIGHT),
                getPresetLlmName(VramTier.STANDARD),
                getPresetLlmName(VramTier.DELUXE),
                CUSTOM_LLM_NAME.get()
        );
        Path modelDir = getLlmBasePath().resolve(modelName);

        if (tier == VramTier.CUSTOM) {
            String customName = CUSTOM_LLM_NAME.get();
            if (customName != null && customName.trim().toLowerCase().endsWith(".gguf")) {
                return getLlmBasePath().resolve(customName.trim());
            }
            return modelDir.resolve("model.gguf"); 
        }

        return modelDir.resolve(getPresetLlmFileName(tier));
    }
}
