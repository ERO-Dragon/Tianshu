package com.rheinmetal.tianshu.core.config;

import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A list survives a round trip whatever its elements contain.
 *
 * <p>The store promises the caller never has to avoid a separator. Joining on a newline broke that promise: one element
 * containing a newline came back as two, and a list holding a single empty string came back empty.</p>
 */
class PropertyModuleConfigStoreListTest {

    @TempDir
    Path directory;

    @Test
    void anElementContainingANewlineStaysOneElement() {
        PropertyModuleConfigStore store = store();

        store.setStringList("prompt", List.of("first line\nsecond line", "another"));
        store.save();

        assertEquals(List.of("first line\nsecond line", "another"), reopen().getStringList("prompt", List.of()));
    }

    @Test
    void anEmptyListAndASingleEmptyStringAreDistinct() {
        PropertyModuleConfigStore store = store();
        store.setStringList("empty", List.of());
        store.setStringList("blank", List.of(""));
        store.save();

        PropertyModuleConfigStore reopened = reopen();
        assertEquals(List.of(), reopened.getStringList("empty", List.of("fallback")));
        assertEquals(List.of(""), reopened.getStringList("blank", List.of("fallback")));
    }

    @Test
    void elementsOnEitherSideOfAnEmptyOneKeepTheirOrder() {
        PropertyModuleConfigStore store = store();
        store.setStringList("mixed", List.of("a", "", "b", ""));
        store.save();

        assertEquals(List.of("a", "", "b", ""), reopen().getStringList("mixed", List.of()));
    }

    @Test
    void aFileWrittenByTheOldEncodingIsStillReadable() throws Exception {
        Path file = directory.resolve("legacy.properties");
        // What Properties.store writes for a value that contains a newline: the escape, not the character.
        Files.writeString(file, "keys=alpha\\nbeta\n");

        PropertyModuleConfigStore store = new PropertyModuleConfigStore(file, LogSink.NOOP);

        assertEquals(List.of("alpha", "beta"), store.getStringList("keys", List.of()),
                "existing files must keep working after the encoding change");
    }

    @Test
    void aTruncatedValueDegradesToReadableTextInsteadOfThrowing() throws Exception {
        Path file = directory.resolve("truncated.properties");
        Files.writeString(file, "keys=v2:5:ab");

        PropertyModuleConfigStore store = new PropertyModuleConfigStore(file, LogSink.NOOP);
        List<String> values = store.getStringList("keys", List.of());

        assertTrue(values.size() <= 1, "a damaged value must not invent elements: " + values);
    }

    private PropertyModuleConfigStore store() {
        return new PropertyModuleConfigStore(directory.resolve("module.properties"), LogSink.NOOP);
    }

    private PropertyModuleConfigStore reopen() {
        return new PropertyModuleConfigStore(directory.resolve("module.properties"), LogSink.NOOP);
    }
}