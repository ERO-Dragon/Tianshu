package com.rheinmetal.tianshu.function.asr;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrProtocolDocumentationBoundaryTest {
    private static final Path PROTOCOL_GUIDE = Path.of("doc/function/asr/ASR_协议中心使用文档.md");

    @Test
    void protocolGuideCoversEveryStableAsrProtocolSymbol() throws Exception {
        assertTrue(Files.isRegularFile(PROTOCOL_GUIDE), "ASR must provide its own protocol-center usage guide");
        String guide = Files.readString(PROTOCOL_GUIDE, StandardCharsets.UTF_8);

        for (String symbol : List.of(
                "ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY",
                "ProtocolTopics.INPUT_ASR_FINAL_TEXT",
                "ProtocolTopics.MODULE_STATUS",
                "ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT",
                "AsrSpeechActivityPayload",
                "AsrTextPayload",
                "ModuleStatusPayload",
                "RuntimeInterruptPayload",
                "TianshuModuleAssemblyContext.protocolRuntime()"
        )) {
            assertTrue(guide.contains(symbol), () -> "ASR protocol guide must document " + symbol);
        }
    }
}
