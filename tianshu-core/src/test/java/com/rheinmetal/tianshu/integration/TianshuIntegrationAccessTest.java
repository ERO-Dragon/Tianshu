package com.rheinmetal.tianshu.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        TianshuIntegrationAccess.resetListenersForTests();
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

    @Test
    void assembledListenerRunsOnceWhenTheApiIsPublished() {
        TianshuIntegrationApi api = apiStub();
        java.util.List<TianshuIntegrationApi> seen = new java.util.ArrayList<>();

        TianshuIntegrationAccess.registerAssembledListener(seen::add);
        TianshuIntegrationAccess.publish(api);

        assertEquals(java.util.List.of(api), seen);
    }

    @Test
    void listenerRegisteredAfterPublicationRunsImmediately() {
        TianshuIntegrationApi api = apiStub();
        TianshuIntegrationAccess.publish(api);
        java.util.List<TianshuIntegrationApi> seen = new java.util.ArrayList<>();

        TianshuIntegrationAccess.registerAssembledListener(seen::add);

        assertEquals(java.util.List.of(api), seen);
    }

    @Test
    void registeringTheSameListenerTwiceKeepsOneRegistration() {
        TianshuIntegrationApi api = apiStub();
        java.util.List<TianshuIntegrationApi> seen = new java.util.ArrayList<>();
        Consumer<TianshuIntegrationApi> listener = seen::add;

        TianshuIntegrationAccess.registerAssembledListener(listener);
        TianshuIntegrationAccess.registerAssembledListener(listener);
        TianshuIntegrationAccess.publish(api);

        assertEquals(1, seen.size());
    }

    @Test
    void aNewSessionPublishesAgainButOnlyOncePerInstance() {
        TianshuIntegrationApi first = apiStub();
        TianshuIntegrationApi second = apiStub();
        java.util.List<TianshuIntegrationApi> seen = new java.util.ArrayList<>();
        TianshuIntegrationAccess.registerAssembledListener(seen::add);

        TianshuIntegrationAccess.publish(first);
        TianshuIntegrationAccess.publish(first);
        TianshuIntegrationAccess.publish(second);

        assertEquals(java.util.List.of(first, second), seen);
    }

    @Test
    void unregisteredListenerIsNoLongerCalled() {
        TianshuIntegrationApi api = apiStub();
        java.util.List<TianshuIntegrationApi> seen = new java.util.ArrayList<>();
        Consumer<TianshuIntegrationApi> listener = seen::add;

        TianshuIntegrationAccess.registerAssembledListener(listener);
        TianshuIntegrationAccess.unregisterAssembledListener(listener);
        TianshuIntegrationAccess.publish(api);

        assertTrue(seen.isEmpty());
    }

    @Test
    void throwingListenerIsContainedAndDoesNotBlockTheNextOne() {
        TianshuIntegrationApi api = apiStub();
        java.util.List<TianshuIntegrationApi> seen = new java.util.ArrayList<>();
        long failuresBefore = com.rheinmetal.tianshu.api.assembly.SignalFailures.failureCount();
        TianshuIntegrationAccess.registerAssembledListener(ignored -> {
            throw new IllegalStateException("broken third-party listener");
        });
        TianshuIntegrationAccess.registerAssembledListener(seen::add);

        TianshuIntegrationAccess.publish(api);

        assertEquals(java.util.List.of(api), seen, "assembly must continue after a broken listener");
        assertEquals(failuresBefore + 1L, com.rheinmetal.tianshu.api.assembly.SignalFailures.failureCount());
    }

    private static TianshuIntegrationApi apiStub() {
        return (TianshuIntegrationApi) Proxy.newProxyInstance(
                TianshuIntegrationApi.class.getClassLoader(),
                new Class<?>[]{TianshuIntegrationApi.class},
                (proxy, method, args) -> null
        );
    }
}
