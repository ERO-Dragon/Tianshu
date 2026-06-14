package com.rheinmetal.tianshu.function.asr.engine;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SherpaOnnxAsrDecodingPolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void transducerModelsUseModifiedBeamSearch() throws Exception {
        AsrModelInfo offline = model("offline-transducer", "transducer", false, Map.of(
                "encoder", "encoder.onnx",
                "decoder", "decoder.onnx",
                "joiner", "joiner.onnx",
                "tokens", "tokens.txt"
        ));
        AsrModelInfo online = model("online-transducer", "transducer", true, Map.of(
                "encoder", "encoder.onnx",
                "decoder", "decoder.onnx",
                "joiner", "joiner.onnx",
                "tokens", "tokens.txt"
        ));

        assertEquals("modified_beam_search", offlineDecodingMethod(build(offline)));
        assertEquals("modified_beam_search", onlineDecodingMethod(build(online)));
    }

    @Test
    void nonTransducerModelsUseGreedySearch() throws Exception {
        assertEquals("greedy_search", offlineDecodingMethod(build(model("whisper", "whisper", false, Map.of(
                "encoder", "encoder.onnx",
                "decoder", "decoder.onnx",
                "tokens", "tokens.txt"
        )))));
        assertEquals("greedy_search", offlineDecodingMethod(build(model("sensevoice", "sensevoice", false, Map.of(
                "model", "model.onnx",
                "tokens", "tokens.txt"
        )))));
        assertEquals("greedy_search", offlineDecodingMethod(build(model("offline-paraformer", "paraformer", false, Map.of(
                "model", "paraformer.onnx",
                "tokens", "tokens.txt"
        )))));
        assertEquals("greedy_search", onlineDecodingMethod(build(model("online-paraformer", "paraformer", true, Map.of(
                "encoder", "encoder.onnx",
                "decoder", "decoder.onnx",
                "tokens", "tokens.txt"
        )))));
    }

    private SherpaOnnxAsrConfigFactory.ResolvedConfig build(AsrModelInfo info) throws Exception {
        Path modelDir = tempDir.resolve(info.localKey());
        Files.createDirectories(modelDir);
        for (String file : info.getAllRequiredFiles()) {
            Files.writeString(modelDir.resolve(file), "test");
        }
        SherpaOnnxAsrConfigFactory factory = new SherpaOnnxAsrConfigFactory(new TestLlmSupport.FakeGameEnvironment());
        return factory.build(info, modelDir, null).orElseThrow();
    }

    private AsrModelInfo model(String name, String architecture, boolean streaming, Map<String, String> fileRoles) {
        AsrModelInfo info = new AsrModelInfo();
        info.name = name;
        info.displayName = name;
        info.id = name;
        info.architecture = architecture;
        info.isStreaming = streaming;
        info.lang = List.of("zh");
        info.fileRoles = new LinkedHashMap<>(fileRoles);
        return info;
    }

    private String offlineDecodingMethod(SherpaOnnxAsrConfigFactory.ResolvedConfig config) throws Exception {
        assertTrue(config.offline());
        return decodingMethod(config.offlineConfig());
    }

    private String onlineDecodingMethod(SherpaOnnxAsrConfigFactory.ResolvedConfig config) throws Exception {
        assertTrue(!config.offline());
        return decodingMethod(config.onlineConfig());
    }

    private String decodingMethod(Object config) throws Exception {
        Field field = config.getClass().getDeclaredField("decodingMethod");
        field.setAccessible(true);
        return (String) field.get(config);
    }
}
