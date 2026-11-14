package com.rheinmetal.tianshu.function.auxilium;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AXRuntimePolicyTest {
    @Test
    void defaultsPreserveCurrentRuntimeTimeoutsInOnePolicyObject() {
        AXRuntimePolicy policy = AXRuntimePolicy.defaults();

        assertEquals(1_000L, policy.retrievalPrimitiveTimeoutMillis());
        assertEquals(30_000L, policy.maintenancePrimitiveTimeoutMillis());
        assertEquals(2_000L, policy.ragTimeoutMillis());
        assertEquals(300L, policy.dynamicFactTimeoutMillis());
    }

    @Test
    void rejectsInvalidTimeoutsAtPolicyBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new AXRuntimePolicy(0L, 30_000L, 2_000L, 300L));
        assertThrows(IllegalArgumentException.class, () -> new AXRuntimePolicy(1_000L, -1L, 2_000L, 300L));
        assertThrows(IllegalArgumentException.class, () -> new AXRuntimePolicy(1_000L, 30_000L, 0L, 300L));
        assertThrows(IllegalArgumentException.class, () -> new AXRuntimePolicy(1_000L, 30_000L, 2_000L, -1L));
    }

    @Test
    void axModuleDoesNotScatterRuntimeTimeoutLiterals() throws Exception {
        String code = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/auxilium/AXModule.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(code.contains("new AXLlmPrimitiveClient(adapter, 1_000L)"));
        assertFalse(code.contains("new AXLlmPrimitiveClient(adapter, 30_000L)"));
        assertFalse(code.contains("new AXLlmRagClient(adapter, 2_000L)"));
        assertFalse(code.contains("new AXDynamicFactClient(adapter, new AXDynamicKnowledgeFormatter(promptRepository, promptLanguageProvider), 300L)"));
    }
}
