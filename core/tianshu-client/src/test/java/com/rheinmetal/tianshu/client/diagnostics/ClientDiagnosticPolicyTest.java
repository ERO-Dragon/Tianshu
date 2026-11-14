package com.rheinmetal.tianshu.client.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission only: is this module part of the session? Whether a record reaches the log or the chat line is the
 * router's decision, so the debug switch must not appear here — if it did, turning chat off would silence the log
 * too, which is exactly what a release build must not do.
 */
final class ClientDiagnosticPolicyTest {
    /** Whatever the session assembled; the policy itself keeps no list. */
    private static final List<String> KNOWN_MODULES = List.of(
            "module.asr",
            "module.ia",
            "module.ax",
            "module.llm",
            "module.tts"
    );

    private static final ClientDiagnosticPolicy POLICY =
            new ClientDiagnosticPolicy(() -> Set.copyOf(KNOWN_MODULES));

    @Test
    void everyModuleTheSessionAssembledIsAdmitted() {
        for (String moduleId : KNOWN_MODULES) {
            assertTrue(POLICY.test(moduleId), moduleId);
        }
    }

    @Test
    void unknownAndBlankModulesAreRejected() {
        assertFalse(POLICY.test("external.unknown"));
        assertFalse(POLICY.test(null));
        assertFalse(POLICY.test(" "));
    }

    @Test
    void admissionDoesNotDependOnTheDebugSwitch() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/rheinmetal/tianshu/client/diagnostics/ClientDiagnosticPolicy.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(source.contains("isDebugEnabled"), "the debug switch belongs to the output routing, not to admission");
        assertFalse(source.contains("ClientDiagnosticsConfiguration"));
    }
}
