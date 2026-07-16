package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleProtocolUsageDocumentationBoundaryTest {
    private static final Map<Path, String> FUNCTION_PROTOCOL_GUIDES = Map.of(
            Path.of("doc/function/asr/ASR_协议中心使用文档.md"), "ProtocolTopics.INPUT_ASR_FINAL_TEXT",
            Path.of("doc/function/ir/IR_协议中心使用文档.md"), "ProtocolTopics.IR_RESULT",
            Path.of("doc/function/ia/IA_外部模组仲裁接入说明.md"), "ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER",
            Path.of("doc/function/auxilium/AX_协议中心使用文档.md"), "AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY",
            Path.of("doc/function/llm/LLM接口设计.md"), "ProtocolCapabilities.LLM_REQUEST",
            Path.of("doc/function/tts/TTS_协议中心使用文档.md"), "ProtocolCapabilities.TTS_SPEAK"
    );

    @Test
    void everyCrossModuleFunctionHasItsOwnProtocolUsageContract() throws Exception {
        for (Map.Entry<Path, String> entry : FUNCTION_PROTOCOL_GUIDES.entrySet()) {
            assertTrue(Files.isRegularFile(entry.getKey()), () -> "missing module protocol guide: " + entry.getKey());
            String guide = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
            assertTrue(
                    guide.contains(entry.getValue()),
                    () -> entry.getKey() + " must document stable protocol symbol " + entry.getValue()
            );
        }
    }

    @Test
    void externalIaGuideDoesNotRecommendDirectImplementationServiceAccess() throws Exception {
        Path guidePath = Path.of("doc/function/ia/IA_外部模组仲裁接入说明.md");
        String guide = Files.readString(guidePath, StandardCharsets.UTF_8);

        assertFalse(guide.contains("IaModuleService"));
    }
}
