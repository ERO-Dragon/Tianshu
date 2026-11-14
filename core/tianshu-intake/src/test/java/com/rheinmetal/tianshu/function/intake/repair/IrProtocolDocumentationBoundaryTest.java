package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.ia.protocol.IaCapabilities;

import com.rheinmetal.tianshu.function.intake.protocol.IntakeTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceCapabilities;

import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextSnapshotPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IrProtocolDocumentationBoundaryTest {
    private static final Path PROTOCOL_GUIDE = Path.of("../doc/function/intake/repair/IR_协议中心使用文档.md");

    @Test
    void protocolGuideCoversEveryStableIrProtocolSymbol() throws Exception {
        assertTrue(Files.isRegularFile(PROTOCOL_GUIDE), "IR must provide its own protocol-center usage guide");
        String guide = Files.readString(PROTOCOL_GUIDE, StandardCharsets.UTF_8);

        for (String symbol : List.of(
                "TianshuModuleAssemblyContext.moduleRuntime()",
                "ModuleRuntimeAccess",
                "IntakeTopics.INPUT_ASR_FINAL_TEXT",
                "IntakeTopics.IR_RESULT",
                "IrResultPayload",
                "IrObjectMatch",
                "IrModuleMatch",
                "DIALOGUE.ARBITRATE",
                "PresenceCapabilities.QUERY_CONTEXT",
                "PresenceContextSnapshotPayload"
        )) {
            assertTrue(guide.contains(symbol), () -> "IR protocol guide must document " + symbol);
        }
    }
}
