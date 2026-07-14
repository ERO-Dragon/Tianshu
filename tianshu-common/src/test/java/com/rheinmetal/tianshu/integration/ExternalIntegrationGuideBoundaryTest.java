package com.rheinmetal.tianshu.integration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalIntegrationGuideBoundaryTest {
    private static final Path GUIDE = Path.of("../EXTERNAL_INTEGRATION_GUIDE.md");

    @Test
    void guideCoversEveryPublicIntegrationOperation() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);

        for (Method method : TianshuIntegrationApi.class.getDeclaredMethods()) {
            assertTrue(
                    guide.contains("api." + method.getName() + "("),
                    () -> "external integration guide must document TianshuIntegrationApi."
                            + method.getName() + "()"
            );
        }
    }

    @Test
    void guideDoesNotReferenceRemovedIntegrationContracts() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);

        assertFalse(guide.contains("StateSummary"));
        assertFalse(guide.contains("STATE_SUMMARY"));
        assertFalse(guide.contains("submitStateSummary"));
        assertFalse(guide.contains("queryStateSummaries"));
        assertFalse(guide.contains("matchedHotwords"));
        assertFalse(guide.contains("matchedEntityRefs"));
    }
}
