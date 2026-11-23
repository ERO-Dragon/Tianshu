package com.rheinmetal.tianshu.dubhe.settings;

import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheAutonomyConsentTest {

    @Test
    void everyTierAsksOnceAndIsRememberedSeparately() {
        MapStorage storage = new MapStorage();
        DubheAutonomyConsent consent = new DubheAutonomyConsent(storage);

        assertTrue(consent.needsConsent(ToolAutonomy.READ_ONLY));
        assertTrue(consent.needsConsent(ToolAutonomy.CONSERVATIVE));

        consent.confirm(ToolAutonomy.READ_ONLY);

        assertFalse(consent.needsConsent(ToolAutonomy.READ_ONLY), "confirmed tiers never ask again");
        assertTrue(consent.needsConsent(ToolAutonomy.CONSERVATIVE), "confirming one tier must not confirm another");
    }

    @Test
    void thePendingTierTracksWhatTheQuestionWasAboutAndClearsOnConfirm() {
        DubheAutonomyConsent consent = new DubheAutonomyConsent(new MapStorage());

        consent.markPending(ToolAutonomy.CONSERVATIVE);
        assertEquals(ToolAutonomy.CONSERVATIVE, consent.pendingTier());

        consent.confirm(ToolAutonomy.CONSERVATIVE);
        assertNull(consent.pendingTier(), "answering the question clears it");

        consent.markPending(ToolAutonomy.FULL);
        consent.clearPending();
        assertNull(consent.pendingTier());
    }

    private static final class MapStorage implements DubheAutonomyConsent.Storage {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key, String fallback) {
            return values.getOrDefault(key, fallback);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }
    }
}