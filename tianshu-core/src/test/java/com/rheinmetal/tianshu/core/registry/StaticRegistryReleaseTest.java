package com.rheinmetal.tianshu.core.registry;

import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static registries must fall back to their baseline when whoever filled them lets go.
 *
 * <p>These live for the whole process, so a module that subscribes and never unsubscribes leaves its own lambda
 * behind: growth on a long-running server, and a leaked class loader under development hot reload. The registry cannot
 * enforce that on its own — it can only hand out an undo — so the undo is what has to be tested.</p>
 */
class StaticRegistryReleaseTest {

    @AfterEach
    void clearRegistries() {
        TianshuModuleProviderRegistry.clear();
    }

    @Test
    void aSignalSubscriptionStopsDeliveringOnceItIsUndone() {
        AtomicInteger delivered = new AtomicInteger();
        String channel = "acceptance:release";

        InputSignals.Subscription unsubscribe = InputSignals.subscribe(channel, text -> delivered.incrementAndGet());
        assertTrue(InputSignals.hasSubscribers(channel), "the subscription must be visible while it is held");

        unsubscribe.close();

        assertFalse(InputSignals.hasSubscribers(channel),
                "after the undo the channel must hold nothing, or a module's handler outlives the module");
        assertEquals(0, delivered.get(), "nothing was published, so nothing may have been delivered");
    }

    @Test
    void aModuleFactoryRegistryFallsBackToEmpty() {
        TianshuModuleProviderRegistry.clear();
        assertTrue(TianshuModuleProviderRegistry.factories().isEmpty(), "the baseline is empty");

        TianshuModuleProviderRegistry.register(context -> {
            throw new UnsupportedOperationException("not part of this test");
        });
        assertEquals(1, TianshuModuleProviderRegistry.factories().size(), "a registration must be visible");

        TianshuModuleProviderRegistry.clear();

        assertTrue(TianshuModuleProviderRegistry.factories().isEmpty(),
                "clearing must return the registry to its baseline, not leave the last factory behind");
    }
}