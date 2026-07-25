package com.rheinmetal.tianshu.protocol.payload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PresenceActivityPayloadTest {
    @Test
    void startedActivityRequiresStableIdentity() {
        PresenceActivityPayload payload = PresenceActivityPayload.started(
                " llm.model.load ",
                PresenceActivityType.LOADING,
                30_000L
        );

        assertEquals(PresenceActivityAction.STARTED, payload.action());
        assertEquals("llm.model.load", payload.activityId());
        assertEquals(30_000L, payload.ttlMillis());
    }

    @Test
    void endedActivityCarriesNoArtificialLifetime() {
        PresenceActivityPayload payload = PresenceActivityPayload.ended(
                "llm.model.load",
                PresenceActivityType.LOADING
        );

        assertEquals(PresenceActivityAction.ENDED, payload.action());
        assertEquals(0L, payload.ttlMillis());
    }

    @Test
    void startedActivityRejectsMissingLifetime() {
        assertThrows(IllegalArgumentException.class, () -> PresenceActivityPayload.started(
                "llm.model.load",
                PresenceActivityType.LOADING,
                0L
        ));
    }

    @Test
    void activityRejectsBlankIdentity() {
        assertThrows(IllegalArgumentException.class, () -> PresenceActivityPayload.started(
                " ",
                PresenceActivityType.PROCESSING_TASK,
                1_000L
        ));
    }
}
