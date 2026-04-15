package com.rheinmetal.tianshu.config;

import java.util.List;

// 所有的URL都必须是打包好的.zip文件下载链接

public class ModelUrls {    
    // public static final String SHERPA_NATIVE_JAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.38/sherpa-onnx-native-lib-win-x64-v1.12.38.jar";
    // public static final String LLM_VULKAN_ZIP_URL = "https://github.com/ggml-org/llama.cpp/releases/download/b8795/llama-b8795-bin-win-vulkan-x64.zip";
    public static final String SHERPA_NATIVE_JAR_URL = "https://gitee.com/erodragon/minecraft-tianshu-ai/releases/download/v.sherpa-onnx-win-x64/sherpa-onnx-native-lib-win-x64-v1.12.38.jar";
    public static final String LLM_VULKAN_ZIP_URL = "https://gitee.com/erodragon/minecraft-tianshu-ai/releases/download/v.llama-win-vulkan-x64/llama-b8797-bin-win-vulkan-x64.zip";
    // ASR模型URL集合
    //k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12
    public static final List<String> ASR_LIGHT_URLS = List.of(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.onnx?download=true"
        // "https://hf-mirror.com/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main/decoder-epoch-20-avg-1-chunk-16-left-128.onnx?download=true",
        // "https://hf-mirror.com/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main/encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx?download=true",
        // "https://hf-mirror.com/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main/joiner-epoch-20-avg-1-chunk-16-left-128.onnx?download=true",
        // "https://hf-mirror.com/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main/tokens.txt?download=true"
    );
    //ParaformerOnnx
    public static final List<String> ASR_STANDARD_URLS = List.of(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt?download=true"
    );
    //TODO：待决定
    public static final List<String> ASR_DELUXE_URLS = List.of(
        "https://example.com/SenseVoiceLarge/model.onnx",
        "https://example.com/SenseVoiceLarge/tokens.bin"
    );
    
    // LLM模型URL（保持单文件）
    public static final String LLM_LIGHT_URL = "https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf";
    public static final String LLM_STANDARD_URL = "https://hf-mirror.com/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf?download=true";
    public static final String LLM_DELUXE_URL = "https://example.com/qwen-14b.gguf";
    
    // TTS模型URL集合
    //Piper
    public static final List<String> TTS_LIGHT_URLS = List.of(
        "https://hf-mirror.com/csukuangfj/vits-piper-zh_CN-huayan-medium/resolve/main/zh_CN-huayan-medium.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/vits-piper-zh_CN-huayan-medium/resolve/main/zh_CN-huayan-medium.onnx.json?download=true",
        "https://hf-mirror.com/csukuangfj/vits-piper-zh_CN-huayan-medium/resolve/main/tokens.txt?download=true"
    );
    //0.6BQwenTTS
    public static final List<String> TTS_STANDARD_URLS = List.of(
        "https://hf-mirror.com/mradermacher/QWEN-TTS-GGUF/resolve/main/QWEN-TTS.Q4_K_M.gguf?download=true"
    );
    //0.6BQwenTTS
    // public static final List<String> TTS_STANDARD_URLS = List.of(
    //     "https://hf-mirror.com/Jahaz/Qwen3-tts-0.6b-gguf-for-koboldcpp/resolve/main/qwen3-tts-0.6b-iq4_xs.gguf?download=true",
    //     "https://hf-mirror.com/Jahaz/Qwen3-tts-0.6b-gguf-for-koboldcpp/resolve/main/qwen3-tts-tokenizer-iq4_xs-v2.gguf?download=true"
    // );
    //1.7BQwenTTS
    public static final List<String> TTS_DELUXE_URLS = List.of(
        "https://hf-mirror.com/mradermacher/Qwen3-1.7B-Multilingual-TTS-GGUF/resolve/main/Qwen3-1.7B-Multilingual-TTS.Q4_K_M.gguf?download=true"
    );
}