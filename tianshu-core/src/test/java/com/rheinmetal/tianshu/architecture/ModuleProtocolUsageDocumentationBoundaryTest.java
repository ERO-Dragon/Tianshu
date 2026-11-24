package com.rheinmetal.tianshu.architecture;

import com.rheinmetal.tianshu.intake.protocol.IntakeTopics;

import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleProtocolUsageDocumentationBoundaryTest {
    private static final Map<Path, String> FUNCTION_PROTOCOL_GUIDES = Map.of(
            WorkspacePaths.doc("function/intake/ASR_协议中心使用文档.md"), "IntakeTopics.INPUT_ASR_FINAL_TEXT",
            WorkspacePaths.doc("function/intake/repair/IR_协议中心使用文档.md"), "IntakeTopics.IR_RESULT",
            WorkspacePaths.doc("function/intake/ia/IA_外部模组仲裁接入说明.md"), "DIALOGUE_PARTICIPANT_REGISTER",
            WorkspacePaths.doc("function/dubhe/Dubhe_对外接口.md"), "DubheProtocolAdapter.DIALOGUE_INPUT_CAPABILITY",
            WorkspacePaths.doc("function/weaving/LLM接口设计.md"), "LLM_REQUEST",
            WorkspacePaths.doc("function/echo/TTS_协议中心使用文档.md"), "TTS_SPEAK"
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
        Path guidePath = WorkspacePaths.doc("function/intake/ia/IA_外部模组仲裁接入说明.md");
        String guide = Files.readString(guidePath, StandardCharsets.UTF_8);

        assertFalse(guide.contains("IaModuleService"));
    }
}
