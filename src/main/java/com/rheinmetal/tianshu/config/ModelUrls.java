package com.rheinmetal.tianshu.config;

import java.util.List;

// 所有的URL都必须是打包好的.zip文件下载链接

public class ModelUrls {    
    // public static final String SHERPA_NATIVE_JAR_URL = "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.38/sherpa-onnx-native-lib-win-x64-v1.12.38.jar";
    // public static final String LLM_VULKAN_ZIP_URL = "https://gh-proxy.com/https://github.com/ggml-org/llama.cpp/releases/download/b8795/llama-b8795-bin-win-vulkan-x64.zip";
    public static final String SHERPA_NATIVE_JAR_URL = "https://my.microsoftpersonalcontent.com/personal/a08915a6df50e442/_layouts/15/download.aspx?UniqueId=0af3679b-73e2-451a-b195-464690d9a520&Translate=false&tempauth=v1e.eyJzaXRlaWQiOiJmOTJlMjBhMC04YmY1LTQ3YTMtOWYwZS0yMWYwM2EyM2QxZjciLCJhdWQiOiIwMDAwMDAwMy0wMDAwLTBmZjEtY2UwMC0wMDAwMDAwMDAwMDAvbXkubWljcm9zb2Z0cGVyc29uYWxjb250ZW50LmNvbUA5MTg4MDQwZC02YzY3LTRjNWItYjExMi0zNmEzMDRiNjZkYWQiLCJleHAiOiIxNzc2MjIxNTQ2In0.Yp0khYPr3ejDaRp2QhFZ6ju3gzB2mGZXb07ZRdSBf0LocE9CNxsFGS7M4V09bSh-4jzHcsSodxAwP-CIuen3DEDCGJ4EvbNtH1B89NzT9v2nXfTiPdmek8mHqPb51NBIDOWuiIXw_YKcIgqyYhRoYb3tOQfxsnwwb7Ly1cSYOLEwyg0Di5Voxia_gVMZ88sC_PzOF86eoKKvbC6R5f0zXNONd6d9N99KWqJ-uYF5pNtc5kl93lpLOd9sZk8OrvYxjSXBLJrTKTIjywVShoHt-4Ith-v5x4UOYO-K6VSDLxmNQN25zm1NpgeCOurHTm-dnkJcSyCoL5RkAq25HrlR_lzLDGTs8BpsP8IJOyeyYaHPAvl8ulxqVOATpasftl7ALTx2cQy6lPxWVlh9zZJRsPbnMRzMmWyj134z7DneE33fVgi0LfzzbagEth4z0cGstzpZ1398u5Tadc-KbbJl9pn5F9w-FsB_Ctshb9s6DmpswF7RsszuBDp-lbEKkyBDkwzdZ4Zyfe5qQAPq52TrFA.cmeZefyVUcJ4uQeQTKXKRZ1XhtBwMmKkv28YkCi02t4&ApiVersion=2.0";
    public static final String LLM_VULKAN_ZIP_URL = "https://my.microsoftpersonalcontent.com/personal/a08915a6df50e442/_layouts/15/download.aspx?UniqueId=903fbddf-d0e9-44fd-ad38-b231bf6447e9&Translate=false&tempauth=v1e.eyJzaXRlaWQiOiJmOTJlMjBhMC04YmY1LTQ3YTMtOWYwZS0yMWYwM2EyM2QxZjciLCJhdWQiOiIwMDAwMDAwMy0wMDAwLTBmZjEtY2UwMC0wMDAwMDAwMDAwMDAvbXkubWljcm9zb2Z0cGVyc29uYWxjb250ZW50LmNvbUA5MTg4MDQwZC02YzY3LTRjNWItYjExMi0zNmEzMDRiNjZkYWQiLCJleHAiOiIxNzc2MjIxMzQ2In0.vp-kk6toSUGpv1bjtBquNaqRmbZW4BDxA3RtQ5UY-0eCmVNjI8Q04Iixp3b0ANOH56weJ3QFXKLg7ucrpSyA_Bs9AUKaDHpDOijS8biosrH0P-hrt_LhoCeRF_LSjAIAhpx-J0xl9TelgugvOUaNe66xXPPwgPqWg_0x-aU5aqzbJRhBi8DmftJLTEm4qtDXB7WHa-51utpUrR7Jj5OAWPnCFgUGjyRfku_bKbuN2QOMOYSbFJod-Wz5g8d64m4qu8VuA9X27o6uyEMVWO4FWzVh4W02_AEpZUz2Pt_Hjc5KHeyEfe-Mae9PABs3QR2AN5mxFC3xpOJA7CxCDseTpfe0X9QWYASHoMG8zhE1HTKPSLYBgtv8-6O_t_7lyrHBM13bLJj49BbGvCqEqsQIXsTEmRITSlk_dc8C3CTaXaGCrp7uDdHOv1BYt54TZ6H2UhX2iOqUDysY3KQPgV2ifkmL8PSLtDQ5rd_KQZTTu1IjfR_2JDarZ3hD5Xbpok2qd_LRyHi1i2vfpe_CPtRaGg.MMmxzSvIC-ypVOZtkkqIn_u7oC_HoXBIVGTgqoS4PVo&ApiVersion=2.0";
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