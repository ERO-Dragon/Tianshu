package com.rheinmetal.tianshu.function.auxilium;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AXProtocolDocumentationBoundaryTest {
    private static final Path PROTOCOL_GUIDE = Path.of("doc/function/auxilium/AX_协议中心使用文档.md");

    @Test
    void protocolGuideCoversEveryStableAxProtocolSymbol() throws Exception {
        assertTrue(Files.isRegularFile(PROTOCOL_GUIDE), "AX must provide its own protocol-center usage guide");
        String guide = Files.readString(PROTOCOL_GUIDE, StandardCharsets.UTF_8);

        for (String symbol : List.of(
                "TianshuModuleAssemblyContext.moduleRuntime()",
                "ModuleRuntimeAccess",
                "AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY",
                "DialogueDeliveryPayload",
                "ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER",
                "ProtocolCapabilities.DIALOGUE_PARTICIPANT_UNREGISTER",
                "ProtocolCapabilities.DIALOGUE_SESSION_CONTROL",
                "ProtocolCapabilities.LLM_REQUEST",
                "ProtocolCapabilities.LLM_PRIMITIVE_QUERY",
                "ProtocolCapabilities.LLM_CACHE_MANAGE",
                "ProtocolCapabilities.PRESENCE_QUERY_CONTEXT",
                "ProtocolCapabilities.TTS_SPEAK",
                "ProtocolCapabilities.TTS_CONTROL",
                "ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY",
                "PresenceWorldEventPayload.TOPIC",
                "PresenceChatMessagePayload.TOPIC",
                "ProtocolTopics.MODULE_STATUS"
        )) {
            assertTrue(guide.contains(symbol), () -> "AX protocol guide must document " + symbol);
        }
    }
}
