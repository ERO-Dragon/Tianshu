package com.rheinmetal.tianshu.function.auxilium.storage;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXJsonStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsMalformedJsonLinesWithoutDroppingWholeFile() throws Exception {
        Path file = tempDir.resolve("events.jsonl");
        Files.writeString(
                file,
                "{\"id\":\"one\"}\n" +
                        "{broken\n" +
                        "{\"id\":\"two\"}\n",
                StandardCharsets.UTF_8
        );
        AXJsonStore store = new AXJsonStore(new TestLlmSupport.FakeGameEnvironment());

        assertEquals(
                java.util.List.of("one", "two"),
                store.readJsonLines(file).stream().map(json -> json.get("id").getAsString()).toList()
        );
    }
}
