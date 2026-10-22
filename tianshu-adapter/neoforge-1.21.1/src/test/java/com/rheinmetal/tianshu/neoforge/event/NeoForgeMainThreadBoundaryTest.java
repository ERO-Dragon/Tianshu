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
        assertFalse(tick.contains("config.isAiEnabled()"));
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
        assertTrue(listener.contains("Supplier<ClientNamedObjectIndexManager>"));
        assertTrue(prepare.contains("readKeywords(resourceManager)"));
        assertTrue(apply.contains("indexManagerSupplier.get()"));
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

    @Test
    void backgroundModuleConfigurationOnlyReadsTheCapturedLanguageSnapshot() throws Exception {
        String bootstrap = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"),
                StandardCharsets.UTF_8
        );
        String config = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(bootstrap.contains("languageSnapshot::promptLanguage"));
        assertFalse(bootstrap.contains("ClientLanguagePolicy::currentPromptLanguage"));
        assertFalse(methodBody(config, "public String getLlmEmbeddingModelName").contains("Minecraft.getInstance()"));
    }

    @Test
    void backgroundPathConsumersOnlyReadTheCapturedGameDirectory() throws Exception {
        String bootstrap = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"),
                StandardCharsets.UTF_8
        );
        String config = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );
        String environment = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/adapter/NeoForgeEnvironment.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(bootstrap.contains("FMLPaths.GAMEDIR.get()"));
        assertTrue(bootstrap.contains("new ClientConfig(gameDirectory,"));
        assertFalse(config.contains("Minecraft.getInstance()"));
        assertFalse(environment.contains("Minecraft.getInstance().gameDirectory"));
    }

    @Test
    void axWorldIdentityProviderDoesNotReadMinecraftFromBackgroundCallers() throws Exception {
        String provider = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/adapter/NeoForgeAXWorldIdentityProvider.java"),
                StandardCharsets.UTF_8
        );
        String events = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NeoForgeClientEvents.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(provider.contains("Minecraft"));
        assertTrue(events.contains("onPlayerClone"));
        assertTrue(events.contains("worldIdentityCapture.refresh()"));
        assertTrue(events.contains("worldIdentityCapture.clear()"));
    }

    @Test
    void userTriggeredFolderOpeningDoesNotPerformSynchronousFilesystemInspection() throws Exception {
        String environment = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/adapter/NeoForgeEnvironment.java"),
                StandardCharsets.UTF_8
        );

        String openFolder = methodBody(environment, "public void openFolder");
        assertFalse(openFolder.contains("Files."));
        assertFalse(openFolder.contains("toFile().isDirectory"));
    }

    @Test
    void gameShutdownUsesTheExplicitClientLifecycleBeforeTheJvmFallback() throws Exception {
        String modEntry = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/TianshuNeoForge.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(modEntry.contains("GameShuttingDownEvent"));
        assertTrue(modEntry.contains("clientBootstrap.shutdown()"));
    }

    @Test
    void hudRenderFramesAreSampledAtRenderFrequency() throws Exception {
        String events = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NeoForgeClientEvents.java"),
                StandardCharsets.UTF_8
        );
        String renderer = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/hud/PresenceHudRenderer.java"),
                StandardCharsets.UTF_8
        );
        String iconRenderer = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/hud/PresenceIconElementRenderer.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(methodBody(events, "public void onClientTick").contains("presenceHudRenderer.update()"));
        assertTrue(methodBody(events, "public void onRenderGui").contains("presenceHudRenderer.render(event.getGuiGraphics())"));
        assertTrue(methodBody(renderer, "public void render").contains("statusTextController.update("));
        assertTrue(methodBody(renderer, "public void render").contains("iconController.update("));
        assertTrue(renderer.contains("System.nanoTime()"));
        assertTrue(iconRenderer.contains("frame.timing().updatedAtMillis()"));
        assertFalse(iconRenderer.contains("System.nanoTime()"));
    }

    @Test
    void shaderRegistrationFailureDoesNotMakeHudStartupFatal() throws Exception {
        String bootstrap = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"),
                StandardCharsets.UTF_8
        );

        String registration = methodBody(bootstrap, "private static void registerShader");
        assertTrue(registration.contains("event.registerShader("));
        assertFalse(registration.contains("throw new IllegalStateException"));
    }

    @Test
    void stalePresenceCleanupCannotClearANewerRuntimeBinding() throws Exception {
        String hooks = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NeoForgePresenceHooks.java"),
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
