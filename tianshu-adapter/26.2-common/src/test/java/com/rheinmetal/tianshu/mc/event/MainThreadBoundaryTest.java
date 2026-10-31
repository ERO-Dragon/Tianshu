package com.rheinmetal.tianshu.mc.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the host is allowed to do on the game thread, checked against the shared layer.
 *
 * <p>These rules used to be asserted against the loader's event class, which is where the bodies lived. The
 * bodies are in {@link ClientEvents} now, so the assertions follow them: a loader adapter that only forwards
 * cannot break a rule it does not implement.</p>
 */
final class MainThreadBoundaryTest {
    private static final String CLIENT_EVENTS = "../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/event/ClientEvents.java";

    @Test
    void tickAndWorldEventsOnlyForwardBoundedWork() throws Exception {
        String events = Files.readString(Path.of(CLIENT_EVENTS), StandardCharsets.UTF_8);
        String tick = methodBody(events, "public void onClientTick");

        assertTrue(events.contains("lifecycleAdapter.onWorldLogin()"));
        assertTrue(events.contains("lifecycleAdapter.onWorldLogout()"));
        assertFalse(events.contains("startRuntimeSession().join()"));
        assertFalse(events.contains("stopRuntimeSession().join()"));
        assertFalse(tick.contains("Thread.sleep"));
        assertFalse(tick.contains("Files."));
        assertFalse(tick.contains(".join()"));
        assertFalse(tick.contains("config.isAiEnabled()"));
    }

    @Test
    void resourceReloadReadsKeywordsInTheBackgroundPrepareStage() throws Exception {
        String listener = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/event/NamedObjectReloadListener.java"),
                StandardCharsets.UTF_8
        );

        String prepare = methodBody(listener, "protected byte[] prepare");
        String apply = methodBody(listener, "protected void apply");
        assertTrue(listener.contains("extends SimplePreparableReloadListener<byte[]>"));
        assertTrue(listener.contains("TianshuRuntimePorts.find(NamedObjectIndex.class)"));
        assertTrue(prepare.contains("readKeywords(resourceManager)"));
        assertTrue(apply.contains("TianshuRuntimePorts.find(NamedObjectIndex.class)"));
        assertTrue(apply.contains("index.reloadAsync("));
        assertFalse(apply.contains("readKeywords("));
        assertFalse(apply.contains("readAllBytes("));
    }

    @Test
    void namedObjectReloadRefreshesPlatformSnapshotBeforeBackgroundIndexing() throws Exception {
        String provider = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/host/RegistryDictionaryProvider.java"),
                StandardCharsets.UTF_8
        );
        String listener = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/event/NamedObjectReloadListener.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(provider.contains("void refresh()"));
        assertTrue(provider.contains("snapshot()"));
        assertTrue(listener.contains("refreshSnapshot.run()"));
    }

    @Test
    void worldIdentityIsCapturedOnTheClientThreadOnly() throws Exception {
        String capture = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/host/WorldIdentityCapture.java"),
                StandardCharsets.UTF_8
        );
        String events = Files.readString(Path.of(CLIENT_EVENTS), StandardCharsets.UTF_8);

        // The capture is the only reader of the live game; consumers see a plain snapshot.
        assertTrue(capture.contains("Minecraft.getInstance()"));
        assertTrue(capture.contains("public WorldFacts current()"));
        assertFalse(capture.contains("env.executeOnMainThread"));
        assertTrue(events.contains("public void onPlayerClone()"));
        assertTrue(events.contains("worldIdentityCapture.refresh()"));
        assertTrue(events.contains("worldIdentityCapture.clear()"));
    }

    @Test
    void userTriggeredFolderOpeningDoesNotPerformSynchronousFilesystemInspection() throws Exception {
        String environment = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/host/GameEnvironment.java"),
                StandardCharsets.UTF_8
        );

        String openFolder = methodBody(environment, "public void openFolder");
        assertFalse(openFolder.contains("Files."));
        assertFalse(openFolder.contains("toFile().isDirectory"));
    }

    @Test
    void hudRenderFramesAreSampledAtRenderFrequency() throws Exception {
        String events = Files.readString(Path.of(CLIENT_EVENTS), StandardCharsets.UTF_8);
        String host = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/ui/hud/HudLayerHost.java"),
                StandardCharsets.UTF_8
        );
        String layer = Files.readString(
                Path.of("../../tianshu-presence/src/main/java/com/rheinmetal/tianshu/function/presence/hud/PresenceHudLayer.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(methodBody(events, "public void onClientTick").contains("hudHost.render"));
        assertTrue(methodBody(events, "public void onRenderGui").contains("hudHost.render(graphics)"));
        assertTrue(host.contains("System.nanoTime()"), "the platform samples the render clock");
        assertTrue(methodBody(host, "public void render").contains("layer.render(frame, surface)"));
        assertTrue(methodBody(layer, "public void render").contains("statusTextController.update("));
        assertTrue(methodBody(layer, "public void render").contains("iconController.update("));
        assertFalse(layer.contains("System.nanoTime()"), "the feature reads the frame clock, never the system one");
    }

    @Test
    void staleWorldEventCleanupCannotClearANewerPublisherBinding() throws Exception {
        String hooks = Files.readString(
                Path.of("../1.21.2-1.21.4-common/src/main/java/com/rheinmetal/tianshu/mc/event/WorldEventHooks.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(hooks.contains("AtomicReference<Binding>"));
        assertTrue(hooks.contains("binding.compareAndSet(current, null)"));
        assertFalse(hooks.contains("binding = null"));
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
