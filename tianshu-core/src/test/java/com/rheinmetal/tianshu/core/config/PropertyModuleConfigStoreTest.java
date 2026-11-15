package com.rheinmetal.tianshu.core.config;

import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's promise is "never lose a key silently".
 *
 * <p>The case that matters is not a malformed file — that one was always handled — but a file this process
 * cannot <em>read</em>: a locked file on Windows, an ACL change, a flaky share. Treating that as "no file" and
 * saving over it replaced the player's api keys and prompts with defaults, with only one log line to show for it.
 */
class PropertyModuleConfigStoreTest {

    @TempDir
    Path root;

    @Test
    void aFileThatCannotBeReadIsPreservedInsteadOfOverwritten() throws Exception {
        // A directory at the file path is the portable stand-in for "present but unreadable": opening it raises
        // an IOException that is not NoSuchFileException, which is exactly the branch a locked file takes.
        Path file = root.resolve("module.llm.properties");
        Files.createDirectories(file);

        PropertyModuleConfigStore store = new PropertyModuleConfigStore(file, LogSink.NOOP);
        assertEquals("fallback", store.getString("api.key", "fallback"),
                "an unreadable file must still let the module start from defaults");

        store.setString("api.key", "new-key");
        store.save();

        Path backup = file.resolveSibling(file.getFileName() + ".corrupt");
        assertTrue(Files.exists(backup), "the unreadable original must be kept, not replaced in place");
        assertTrue(Files.isRegularFile(file), "the new settings must land where the module expects them");

        PropertyModuleConfigStore reloaded = new PropertyModuleConfigStore(file, LogSink.NOOP);
        assertEquals("new-key", reloaded.getString("api.key", "fallback"));
    }

    @Test
    void aMissingFileIsWrittenWithoutABackup() {
        Path file = root.resolve("module.asr.properties");
        PropertyModuleConfigStore store = new PropertyModuleConfigStore(file, LogSink.NOOP);

        store.setString("language", "zh");
        store.save();

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")),
                "a first run has nothing to preserve");
    }

    @Test
    void anIntOutsideTheIntRangeFallsBackInsteadOfTruncating() throws Exception {
        Path file = root.resolve("module.voice.properties");
        Files.writeString(file, "threads=3000000000\n", StandardCharsets.ISO_8859_1);

        PropertyModuleConfigStore store = new PropertyModuleConfigStore(file, LogSink.NOOP);

        assertEquals(4, store.getInt("threads", 4),
                "3000000000 used to truncate to a negative thread count");
    }

    @Test
    void aValueThatIsNotANumberFallsBack() throws Exception {
        Path file = root.resolve("module.echo.properties");
        Files.writeString(file, "volume=LOUD\n", StandardCharsets.ISO_8859_1);

        PropertyModuleConfigStore store = new PropertyModuleConfigStore(file, LogSink.NOOP);

        assertEquals(80, store.getInt("volume", 80));
        assertEquals(0.5D, store.getDouble("volume", 0.5D), 1.0E-9D);
    }
}