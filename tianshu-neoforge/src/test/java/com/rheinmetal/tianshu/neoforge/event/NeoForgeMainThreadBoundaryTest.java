package com.rheinmetal.tianshu.neoforge.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeMainThreadBoundaryTest {
    @Test
    void tickAndWorldEventsOnlyForwardBoundedWork() throws Exception {
        String events = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NeoForgeClientEvents.java"),
                StandardCharsets.UTF_8
        );
        String tick = methodBody(events, "public void onClientTick");

        assertTrue(events.contains("lifecycleAdapter.onWorldLogin()"));
        assertTrue(events.contains("lifecycleAdapter.onWorldLogout()"));
        assertFalse(events.contains("startRuntimeSession().join()"));
        assertFalse(events.contains("stopRuntimeSession().join()"));
        assertFalse(tick.contains("Thread.sleep"));
        assertFalse(tick.contains("Files."));
        assertFalse(tick.contains(".join()"));
    }

    @Test
    void resourceReloadReadsKeywordsInTheBackgroundPrepareStage() throws Exception {
        String listener = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NamedObjectReloadListener.java"),
                StandardCharsets.UTF_8
        );

        String prepare = methodBody(listener, "protected byte[] prepare");
        String apply = methodBody(listener, "protected void apply");
        assertTrue(listener.contains("extends SimplePreparableReloadListener<byte[]>"));
        assertTrue(prepare.contains("readKeywords(resourceManager)"));
        assertTrue(apply.contains("indexManager.reloadAsync("));
        assertFalse(apply.contains("readKeywords("));
        assertFalse(apply.contains("readAllBytes("));
    }

    @Test
    void namedObjectReloadRefreshesPlatformSnapshotBeforeBackgroundIndexing() throws Exception {
        String provider = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/adapter/NeoForgeNamedObjectDictionaryProvider.java"),
                StandardCharsets.UTF_8
        );
        String listener = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NamedObjectReloadListener.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(provider.contains("void refresh()"));
        assertTrue(provider.contains("snapshot()"));
        assertTrue(listener.contains("refreshSnapshot.run()"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        int opening = source.indexOf('{', start);
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("Missing method: " + signature);
    }
}
