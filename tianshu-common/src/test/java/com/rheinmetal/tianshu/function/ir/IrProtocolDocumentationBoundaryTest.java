package com.rheinmetal.tianshu.function.ir;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IrProtocolDocumentationBoundaryTest {
    private static final Path PROTOCOL_GUIDE = Path.of("doc/function/ir/IR_协议中心使用文档.md");

    @Test
    void protocolGuideCoversEveryStableIrProtocolSymbol() throws Exception {
        assertTrue(Files.isRegularFile(PROTOCOL_GUIDE), "IR must provide its own protocol-center usage guide");
        String guide = Files.readString(PROTOCOL_GUIDE, StandardCharsets.UTF_8);

        for (String symbol : List.of(
                "TianshuModuleAssemblyContext.protocolRuntime()",
                "ProtocolCapabilities.IR_PARSE",
                "PayloadType.IR_PARSE",
                "IrParsePayload",
                "ProtocolTopics.INPUT_ASR_FINAL_TEXT",
                "ProtocolTopics.IR_RESULT",
                "IrResultPayload",
                "ProtocolCapabilities.DIALOGUE_ARBITRATE",
                "DialogueArbitrationRequestPayload",
                "ProtocolCapabilities.PRESENCE_QUERY_CONTEXT",
                "PresenceContextSnapshotPayload"
        )) {
            assertTrue(guide.contains(symbol), () -> "IR protocol guide must document " + symbol);
        }
    }
}
