package com.rheinmetal.tianshu.client.capability;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mod can register its own content under its id; reserved and duplicate namespaces are refused. */
class CapabilitySourcesTest {

    @TempDir
    Path root;

    @AfterEach
    void clean() {
        CapabilitySources.closeAll();
    }

    @Test
    void aModRegistersResourcesUnderItsOwnNamespace() throws Exception {
        Files.writeString(root.resolve("memo.md"),
                "---\nname: memo\ndescription: a memo\n---\nbody", StandardCharsets.UTF_8);

        CapabilitySources.registerResources("example", root);
        assertTrue(CapabilityCatalog.find(CapabilityId.of("example", "memo")).isPresent());

        assertThrows(IllegalArgumentException.class,
                () -> CapabilitySources.registerResources("example", root));
        assertThrows(IllegalArgumentException.class,
                () -> CapabilitySources.registerResources("user", root));

        CapabilitySources.closeAll();
        assertTrue(CapabilityCatalog.find(CapabilityId.of("example", "memo")).isEmpty());
    }
}