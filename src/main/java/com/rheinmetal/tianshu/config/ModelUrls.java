package com.rheinmetal.tianshu.config;

import java.util.List;

public class ModelUrls {

    public static final List<String> ASR_LIGHT_URLS = List.of(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.onnx?download=true"
    );

    public static final List<String> ASR_STANDARD_URLS = List.of(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt?download=true"
    );

    public static final List<String> ASR_DELUXE_URLS = List.of(
        "https://example.com/SenseVoiceLarge/model.onnx",
        "https://example.com/SenseVoiceLarge/tokens.bin"
    );

    public static final String LLM_LIGHT_URL = "https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf";
    public static final String LLM_STANDARD_URL = "https://hf-mirror.com/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf?download=true";
    public static final String LLM_DELUXE_URL = "https://example.com/qwen-14b.gguf";

    public static final List<String> TTS_LIGHT_URLS = List.of(
        "https://hf-mirror.com/csukuangfj/vits-piper-zh_CN-huayan-medium/resolve/main/zh_CN-huayan-medium.onnx?download=true",
        "https://hf-mirror.com/csukuangfj/vits-piper-zh_CN-huayan-medium/resolve/main/zh_CN-huayan-medium.onnx.json?download=true",
        "https://hf-mirror.com/csukuangfj/vits-piper-zh_CN-huayan-medium/resolve/main/tokens.txt?download=true"
    );

    public static final List<String> TTS_STANDARD_URLS = List.of(
        "https://hf-mirror.com/mradermacher/QWEN-TTS-GGUF/resolve/main/QWEN-TTS.Q4_K_M.gguf?download=true"
    );

    public static final List<String> TTS_DELUXE_URLS = List.of(
        "https://hf-mirror.com/mradermacher/Qwen3-1.7B-Multilingual-TTS-GGUF/resolve/main/Qwen3-1.7B-Multilingual-TTS.Q4_K_M.gguf?download=true"
    );
}
