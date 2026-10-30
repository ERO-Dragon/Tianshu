package com.rheinmetal.tianshu.neoforge.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the host contract that are about this loader specifically.
 *
 * <p>What the host <em>does</em> on a tick, a frame or a session change is asserted in the shared layer
 * ({@code mc.event.MainThreadBoundaryTest}); what is asserted here is only how NeoForge hands those moments over,
 * how the game directory reaches the config, and that a visual shader cannot take the client down.</p>
 */
final class NeoForgeAdapterBoundaryTest {
    private static final String BOOTSTRAP = "src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java";

    @Test
    void backgroundModuleConfigurationOnlyReadsTheCapturedLanguageSnapshot() throws Exception {
        String bootstrap = Files.readString(Path.of(BOOTSTRAP), StandardCharsets.UTF_8);
        String config = Files.readString(
                Path.of("../../tianshu-weaving/src/main/java/com/rheinmetal/tianshu/function/weaving/settings/WeavingConfig.java"),
                StandardCharsets.UTF_8
        );

        // The mapping from a platform locale to a prompt language moved into the module that owns it.
        assertFalse(bootstrap.contains("AXPromptLanguageProvider"));
        assertTrue(Files.readString(
                Path.of("../../tianshu-auxilium/src/main/java/com/rheinmetal/tianshu/function/auxilium/AuxiliumModuleProvider.java"),
                StandardCharsets.UTF_8
        ).contains("AXPromptLanguageProvider.ofCode(ports.texts()::currentLanguage)"));
        assertFalse(bootstrap.contains("ClientLanguagePolicy::currentPromptLanguage"));
        assertFalse(methodBody(config, "public String getLlmEmbeddingModelName").contains("Minecraft.getInstance()"));
    }

    @Test
    void backgroundPathConsumersOnlyReadTheCapturedGameDirectory() throws Exception {
        String bootstrap = Files.readString(Path.of(BOOTSTRAP), StandardCharsets.UTF_8);
        String config = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );
        String environment = Files.readString(
                Path.of("../common/src/main/java/com/rheinmetal/tianshu/mc/host/GameEnvironment.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(bootstrap.contains("FMLPaths.GAMEDIR.get()"));
        assertTrue(bootstrap.contains("new ClientConfig(gameDirectory)"));
        assertFalse(config.contains("Minecraft.getInstance()"));
        assertFalse(environment.contains("Minecraft.getInstance().gameDirectory"));
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
    void shaderRegistrationFailureDoesNotMakeHudStartupFatal() throws Exception {
        String bootstrap = Files.readString(Path.of(BOOTSTRAP), StandardCharsets.UTF_8);

        String registration = methodBody(bootstrap, "private void registerShader");
        assertTrue(registration.contains("event.registerShader("));
        assertFalse(registration.contains("throw new IllegalStateException"));
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
