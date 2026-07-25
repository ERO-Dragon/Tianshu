package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ir.IrProtocolAdapter;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.function.tts.TtsProtocolAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientDiagnosticPolicyTest {
    private static final List<String> KNOWN_MODULES = List.of(
            AsrProtocolAdapter.MODULE_ID,
            IrProtocolAdapter.MODULE_ID,
            IaProtocolAdapter.MODULE_ID,
            AXModule.MODULE_ID,
            LlmProtocolAdapter.MODULE_ID,
            TtsProtocolAdapter.MODULE_ID
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
