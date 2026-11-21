package com.rheinmetal.tianshu.dubhe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DubheRuntimePolicyTest {
    @Test
    void defaultsPreserveCurrentRuntimeTimeoutsInOnePolicyObject() {
        DubheRuntimePolicy policy = DubheRuntimePolicy.defaults();

        assertEquals(1_000L, policy.retrievalPrimitiveTimeoutMillis());
        assertEquals(30_000L, policy.maintenancePrimitiveTimeoutMillis());
        assertEquals(2_000L, policy.ragTimeoutMillis());
        assertEquals(300L, policy.dynamicFactTimeoutMillis());
    }

    @Test
    void rejectsInvalidTimeoutsAtPolicyBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new DubheRuntimePolicy(0L, 30_000L, 2_000L, 300L));
        assertThrows(IllegalArgumentException.class, () -> new DubheRuntimePolicy(1_000L, -1L, 2_000L, 300L));
        assertThrows(IllegalArgumentException.class, () -> new DubheRuntimePolicy(1_000L, 30_000L, 0L, 300L));
        assertThrows(IllegalArgumentException.class, () -> new DubheRuntimePolicy(1_000L, 30_000L, 2_000L, -1L));
    }

    @Test
    void axModuleDoesNotScatterRuntimeTimeoutLiterals() throws Exception {
        String code = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/dubhe/DubheModule.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(code.contains("new DubheLlmPrimitiveClient(adapter, 1_000L)"));
        assertFalse(code.contains("new DubheLlmPrimitiveClient(adapter, 30_000L)"));
        assertFalse(code.contains("new DubheLlmRagClient(adapter, 2_000L)"));
        assertFalse(code.contains("new DubheDynamicFactClient(adapter, new DubheDynamicKnowledgeFormatter(promptRepository, promptLanguageProvider), 300L)"));
    }
}
