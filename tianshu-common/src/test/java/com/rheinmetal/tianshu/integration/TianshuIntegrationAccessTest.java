package com.rheinmetal.tianshu.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TianshuIntegrationAccessTest {
    @AfterEach
    void clearPublishedApi() {
        TianshuIntegrationApi current = TianshuIntegrationAccess.currentOrNull();
        if (current != null) {
            TianshuIntegrationAccess.clear(current);
        }
    }

    @Test
    void reportsUnavailableWhenNothingIsPublished() {
        assertFalse(TianshuIntegrationAccess.isAvailable());
        assertNull(TianshuIntegrationAccess.currentOrNull());
        assertThrows(IllegalStateException.class, TianshuIntegrationAccess::require);
    }

    @Test
    void publishesAndReturnsCurrentApi() {
        TianshuIntegrationApi api = apiStub();

        TianshuIntegrationAccess.publish(api);

        assertTrue(TianshuIntegrationAccess.isAvailable());
        assertSame(api, TianshuIntegrationAccess.currentOrNull());
        assertSame(api, TianshuIntegrationAccess.require());
    }

    @Test
    void clearingPreviousInstanceDoesNotRemoveReplacement() {
        TianshuIntegrationApi previous = apiStub();
        TianshuIntegrationApi replacement = apiStub();
        TianshuIntegrationAccess.publish(previous);
        TianshuIntegrationAccess.publish(replacement);

        TianshuIntegrationAccess.clear(previous);

        assertSame(replacement, TianshuIntegrationAccess.currentOrNull());
    }

    @Test
    void clearingCurrentInstanceMakesAccessUnavailable() {
        TianshuIntegrationApi api = apiStub();
        TianshuIntegrationAccess.publish(api);

        TianshuIntegrationAccess.clear(api);

        assertFalse(TianshuIntegrationAccess.isAvailable());
        assertNull(TianshuIntegrationAccess.currentOrNull());
    }

    @Test
    void publishRejectsNull() {
        assertThrows(NullPointerException.class, () -> TianshuIntegrationAccess.publish(null));
    }

    private static TianshuIntegrationApi apiStub() {
        return (TianshuIntegrationApi) Proxy.newProxyInstance(
                TianshuIntegrationApi.class.getClassLoader(),
                new Class<?>[]{TianshuIntegrationApi.class},
                (proxy, method, args) -> null
        );
    }
}
