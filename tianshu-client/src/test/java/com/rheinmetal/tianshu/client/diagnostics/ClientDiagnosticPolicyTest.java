package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientDiagnosticPolicyTest {
    /** The pipeline ids the kernel names; identical to the adapters' MODULE_ID constants by construction. */
    private static final List<String> KNOWN_MODULES = List.of(
            ProtocolModuleIds.ASR,
            ProtocolModuleIds.IA,
            ProtocolModuleIds.AX,
            ProtocolModuleIds.LLM,
            ProtocolModuleIds.TTS
    );

    @Test
    void globalDebugEnablesEveryKnownDiagnosticModule() {
        ClientDiagnosticPolicy policy = new ClientDiagnosticPolicy(enabled(true));

        for (String moduleId : KNOWN_MODULES) {
            assertTrue(policy.test(moduleId), moduleId);
        }
    }

    @Test
    void disabledGlobalDebugAndUnknownModulesRemainDisabled() {
        ClientDiagnosticPolicy disabled = new ClientDiagnosticPolicy(enabled(false));
        ClientDiagnosticPolicy enabled = new ClientDiagnosticPolicy(enabled(true));

        for (String moduleId : KNOWN_MODULES) {
            assertFalse(disabled.test(moduleId), moduleId);
        }
        assertFalse(enabled.test("external.unknown"));
        assertFalse(enabled.test(null));
    }

    private static ClientDiagnosticsConfiguration enabled(boolean value) {
        return () -> value;
    }
}
