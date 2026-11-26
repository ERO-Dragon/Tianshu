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
    void contentKindsAndOwnersCanBeReleasedIndependentlyAndReinstalled() throws Exception {
        Path skills = Files.createDirectory(root.resolve("skills"));
        Path prompts = Files.createDirectory(root.resolve("prompts"));
        Files.writeString(prompts.resolve("prompt.md"), "---\nname: prompt\ndescription: prompt\n---\nbody");
        var old = CapabilitySources.registerSkills("example", skills);
        try (var sameOwner = CapabilitySources.registerPrompts("example", prompts);
             var other = CapabilitySources.registerPrompts("other", prompts)) {
            old.close();
            try (var replacement = CapabilitySources.registerSkills("example", skills)) {
                old.close();
                assertThrows(IllegalArgumentException.class, () -> CapabilitySources.registerSkills("example", skills));
                assertTrue(CapabilityCatalog.find(CapabilityId.parse("other:prompt")).isPresent());
                assertTrue(CapabilityCatalog.find(CapabilityId.parse("example:prompt")).isPresent());
            }
        }
        assertTrue(CapabilityCatalog.find(CapabilityId.parse("example:prompt")).isEmpty());
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