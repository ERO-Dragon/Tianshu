package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.protocol.AxCapabilities;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceCapabilities;

import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AXProtocolDocumentationBoundaryTest {
    private static final Path PROTOCOL_GUIDE = Path.of("../doc/function/auxilium/AX_协议中心使用文档.md");

    @Test
    void protocolGuideCoversEveryStableAxProtocolSymbol() throws Exception {
        assertTrue(Files.isRegularFile(PROTOCOL_GUIDE), "AX must provide its own protocol-center usage guide");
        String guide = Files.readString(PROTOCOL_GUIDE, StandardCharsets.UTF_8);

        for (String symbol : List.of(
                "TianshuModuleAssemblyContext.moduleRuntime()",
                "ModuleRuntimeAccess",
                "AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY",
                "AXProtocolAdapter.CONTEXT_INPUT_CAPABILITY",
                "AXProtocolAdapter.EVENT_INPUT_CAPABILITY",
                "DialogueDeliveryPayload",
                "DialogueContextInputPayload",
                "AXWorldEventPayload",
                "AX.DIALOGUE_INPUT",
                "AX.CONTEXT_INPUT",
                "AX.EVENT_INPUT",
                "DIALOGUE.PARTICIPANT_REGISTER",
                "DIALOGUE.PARTICIPANT_UNREGISTER",
                "DIALOGUE.SESSION_CONTROL",
                "LLM.REQUEST",
                "LLM.PRIMITIVE_QUERY",
                "LLM.CACHE_MANAGE",
                "PresenceCapabilities.QUERY_CONTEXT",
                "TTS_SPEAK",
                "TTS_CONTROL",
                "PresenceTopics.WORLD_EVENT",
                "PresenceWorldEventPayload.EVENT_CHAT_MESSAGE",
                "ProtocolTopics.MODULE_STATUS"
        )) {
            assertTrue(guide.contains(symbol), () -> "AX protocol guide must document " + symbol);
        }
    }
}
