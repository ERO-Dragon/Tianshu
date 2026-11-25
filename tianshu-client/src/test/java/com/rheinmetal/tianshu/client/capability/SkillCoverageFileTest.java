package com.rheinmetal.tianshu.client.capability;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.SkillCoverage;
import com.rheinmetal.tianshu.api.capability.skill.SkillFileProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skill's coverage lives beside {@code SKILL.md}, not inside it: the file format is the general one, and our index is
 * our business. These cases check both halves 鈥?that a coverage file is read, and that a skill without one still works.
 */
class SkillCoverageFileTest {

    @TempDir
    Path root;

    private SkillFileProvider provider;

    @AfterEach
    void clean() {
        if (provider != null) {
            provider.unload();
            provider = null;
        }
    }

    @Test
    void theCoverageFileSaysWhichCapabilitiesTheSkillTeaches() throws Exception {
        write("beta", "beta", "A body skill", "# Beta\nbody");
        writeFile("beta", "coverage.txt", "# what this teaches\ntianshu:body.mine\n\ntianshu:body.move\n");
        provider = SkillFileProvider.install("user", root);

        var row = CapabilityCatalog.find(CapabilityId.of("user", "beta")).orElseThrow();

        assertEquals(List.of(CapabilityId.parse("tianshu:body.mine"), CapabilityId.parse("tianshu:body.move")),
                List.copyOf(SkillCoverage.coversOf(row)));
    }

    @Test
    void aSkillWithoutCoverageStillLoads() throws Exception {
        write("gamma", "gamma", "A plain skill", "# Gamma\nbody");
        provider = SkillFileProvider.install("user", root);

        var row = CapabilityCatalog.find(CapabilityId.of("user", "gamma")).orElseThrow();

        assertTrue(SkillCoverage.coversOf(row).isEmpty(), "no coverage file means no claims about what it teaches");
    }

    @Test
    void theBuiltInBodySkillDeclaresItsTools() {
        provider = SkillFileProvider.installClasspath("tianshu", "tianshu/skills");

        var row = CapabilityCatalog.find(CapabilityId.of("tianshu", "body-tools")).orElseThrow();

        assertTrue(SkillCoverage.coversOf(row).contains(CapabilityId.parse("tianshu:body.mine")),
                "the shipped body skill must point at the tools it documents: " + SkillCoverage.coversOf(row));
    }

    private void write(String file, String name, String description, String body) throws Exception {
        Path directory = root.resolve(file);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body,
                StandardCharsets.UTF_8);
    }

    private void writeFile(String file, String relative, String content) throws Exception {
        Path target = root.resolve(file).resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}