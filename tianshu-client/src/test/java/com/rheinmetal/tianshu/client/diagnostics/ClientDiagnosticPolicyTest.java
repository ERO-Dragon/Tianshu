package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientDiagnosticPolicyTest {
    /** Whatever the session assembled; the policy itself keeps no list. */
    private static final List<String> KNOWN_MODULES = List.of(
            "module.asr",
            "module.ia",
            "module.ax",
            "module.llm",
            "module.tts"
    );

    @Test
    void globalDebugEnablesEveryModuleTheSessionAssembled() {
        ClientDiagnosticPolicy policy = policy(true);

        for (String moduleId : KNOWN_MODULES) {
            assertTrue(policy.test(moduleId), moduleId);
        }
    }

    @Test
    void disabledGlobalDebugAndUnknownModulesRemainDisabled() {
        ClientDiagnosticPolicy disabled = policy(false);
        ClientDiagnosticPolicy enabled = policy(true);

        for (String moduleId : KNOWN_MODULES) {
            assertFalse(disabled.test(moduleId), moduleId);
        }
        assertFalse(enabled.test("external.unknown"));
        assertFalse(enabled.test(null));
    }

    private static ClientDiagnosticPolicy policy(boolean debugEnabled) {
        ClientDiagnosticsConfiguration config = () -> debugEnabled;
        return new ClientDiagnosticPolicy(config, () -> Set.copyOf(KNOWN_MODULES));
    }
}
