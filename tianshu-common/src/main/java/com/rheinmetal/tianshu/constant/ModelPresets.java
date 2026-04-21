package com.rheinmetal.tianshu.constant;

public final class ModelPresets {
    public static final String DEFAULT_ROOT_DIR_NAME = "config/TianshuAIAssistant";

    public static final String[] PERSONA_PRESETS = {"默认", "开朗健谈", "稳健务实", "温柔体贴", "严肃专业"};
    public static final String[] PERSONA_PROMPTS = {
        "",
        "你是一个开朗、健谈的助手，喜欢用轻松愉快的语气回答问题，偶尔会开个小玩笑。",
        "你是一个稳健、务实的助手，回答简洁明了，注重实用性和准确性。",
        "你是一个温柔、体贴的助手，善于倾听，回答时充满关怀。",
        "你是一个严肃、专业的助手，回答严谨精确，注重逻辑和数据。"
    };

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
            case LIGHT -> "vits-zh-hf-keqing";
            case STANDARD -> "MOSS-TTS-Nano-100M-ONNX";
            case DELUXE -> "MOSS-TTS-Nano-100M-ONNX";
            default -> "vits-zh-hf-keqing";
        };
    }

    public static String getPresetTtsModelId(VramTier tier) {
        return switch (tier) {
            case LIGHT -> "csukuangfj/vits-zh-hf-keqing";
            case STANDARD -> "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX";
            case DELUXE -> "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX";
            default -> "csukuangfj/vits-zh-hf-keqing";
        };
    }

    public static String getPresetLlmFileName(VramTier tier) {
        return switch (tier) {
            case LIGHT -> "Qwen3-0.6B-Q4_K_M.gguf";
            case STANDARD -> "Qwen3-4B-Q4_K_M.gguf";
            case DELUXE -> "qwen-deluxe.gguf";
            default -> "Qwen3-0.6B-Q4_K_M.gguf";
        };
    }

    public static String resolveTargetModelName(
            VramTier currentTier,
            String lightName, String standardName, String deluxeName,
            String customName) {
        if (currentTier == VramTier.CUSTOM && customName != null && !customName.trim().isEmpty()) {
            return customName.trim();
        }
        return switch (currentTier) {
            case LIGHT -> lightName;
            case STANDARD -> standardName;
            case DELUXE -> deluxeName;
            default -> lightName;
        };
    }

    private ModelPresets() {}
}
