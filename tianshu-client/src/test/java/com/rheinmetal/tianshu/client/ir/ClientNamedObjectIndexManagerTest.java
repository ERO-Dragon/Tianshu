package com.rheinmetal.tianshu.client.ir;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientNamedObjectIndexManagerTest {
    @Test
    void concurrentInitializationIsCoalescedAndReloadsAreSerialized() throws Exception {
        Path cacheRoot = Files.createTempDirectory("tianshu-ir-test");
        CountDownLatch allowBuild = new CountDownLatch(1);
        AtomicInteger activeBuilds = new AtomicInteger();
        AtomicInteger maxActiveBuilds = new AtomicInteger();
        NamedObjectDictionaryProvider provider = () -> {
            int active = activeBuilds.incrementAndGet();
            maxActiveBuilds.accumulateAndGet(active, Math::max);
            try {
                allowBuild.await(5, TimeUnit.SECONDS);
                return Map.of("item:minecraft:stone", List.of("stone"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Map.of();
            } finally {
                activeBuilds.decrementAndGet();
            }
        };

        try (ClientNamedObjectIndexManager manager = new ClientNamedObjectIndexManager(provider, cacheRoot, () -> "en_us")) {
            CompletableFuture<Boolean> first = manager.initializeAsync("first");
            CompletableFuture<Boolean> duplicate = manager.initializeAsync("duplicate");
            assertSame(first, duplicate);

            CompletableFuture<Boolean> reloadOne = manager.reloadAsync("reload-one", null);
            CompletableFuture<Boolean> reloadTwo = manager.reloadAsync("reload-two", null);
            allowBuild.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(reloadOne.get(5, TimeUnit.SECONDS));
            assertTrue(reloadTwo.get(5, TimeUnit.SECONDS));
            assertTrue(maxActiveBuilds.get() == 1);
        }
    }

    @Test
    void closeRejectsNewWork() throws Exception {
        ClientNamedObjectIndexManager manager = manager(Files.createTempDirectory("tianshu-ir-test"));
        assertTrue(manager.initializeAsync("test").get(5, TimeUnit.SECONDS));

        manager.close();

        assertThrows(Exception.class, () -> manager.reloadAsync("closed", null).get(5, TimeUnit.SECONDS));
        assertThrows(Exception.class, () -> manager.initializeAsync("closed").get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeDuringBuildDoesNotWriteCache() throws Exception {
        Path cacheRoot = Files.createTempDirectory("tianshu-ir-close-test");
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch allowBuild = new CountDownLatch(1);
        ClientNamedObjectIndexManager manager = new ClientNamedObjectIndexManager(() -> {
            buildStarted.countDown();
            try {
                allowBuild.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Return a result after cancellation to exercise the generation guard.
            }
            return Map.of("item:minecraft:stone", List.of("stone"));
        }, cacheRoot, () -> "en_us");

        CompletableFuture<Boolean> initialization = manager.initializeAsync("closing");
        assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
        manager.close();
        allowBuild.countDown();

        assertFalse(initialization.get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(cacheRoot.resolve("named-object-ir-cache.bin")));
    }

    private static ClientNamedObjectIndexManager manager(Path cacheRoot) {
        return new ClientNamedObjectIndexManager(
                () -> Map.of("item:minecraft:stone", List.of("stone")),
                cacheRoot,
                () -> "en_us"
        );
    }
}
