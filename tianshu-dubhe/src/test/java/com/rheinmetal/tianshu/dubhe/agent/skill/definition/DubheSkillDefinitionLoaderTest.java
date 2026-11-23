package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSkillDefinitionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsMetadataBodyAndReferences() throws Exception {
        Path skill = skillDir("search",
                """
                        ---
                        name: search
                        description: 联网技能族入口：按问题类型去对应的源检索。
                        allowed-tools: web.search web.fetch
                        metadata:
                          owner: dubhe
                          version: "1"
                        ---
                        遇到模组问题去 mcmod；遇到功能需求去 mcmod / CurseForge / Modrinth。
                        """);
        write(skill.resolve("references/routing.md"), "问题类型 -> 源");
        write(skill.resolve("references/reading.md"), "怎么读结果");

        DubheSkillDefinition definition = DubheSkillDefinitionLoader.load(skill);

        assertEquals("search", definition.metadata().name());
        assertTrue(definition.metadata().description().contains("联网技能族"));
        assertEquals(java.util.List.of("web.search", "web.fetch"), definition.metadata().allowedTools());
        assertEquals("dubhe", definition.metadata().metadata().get("owner"));
        assertTrue(definition.body().contains("CurseForge"));
        assertEquals("问题类型 -> 源", definition.reference("references/routing.md"));
        assertEquals(2, definition.references().size());
    }

    @Test
    void metadataCanBeReadWithoutAnyReferencesDirectory() throws Exception {
        Path skill = skillDir("search",
                """
                        ---
                        name: search
                        description: entry
                        ---
                        body
                        """);

        DubheSkillMetadata metadata = DubheSkillDefinitionLoader.loadMetadata(skill);

        assertEquals("search", metadata.name());
        assertTrue(Files.notExists(skill.resolve("references")), "metadata load must not require references");
    }

    @Test
    void onlyOneLevelOfReferencesIsScanned() throws Exception {
        Path skill = skillDir("search",
                """
                        ---
                        name: search
                        description: entry
                        ---
                        body
                        """);
        write(skill.resolve("references/top.md"), "top");
        write(skill.resolve("references/nested/deep.md"), "deep");

        DubheSkillDefinition definition = DubheSkillDefinitionLoader.load(skill);

        assertEquals(1, definition.references().size(), "references are one level deep");
        assertEquals("", definition.reference("references/nested/deep.md"));
    }

    @Test
    void pathTraversalIsRefused() throws Exception {
        Path skill = skillDir("search",
                """
                        ---
                        name: search
                        description: entry
                        ---
                        body
                        """);
        DubheSkillDefinition definition = DubheSkillDefinitionLoader.load(skill);

        assertEquals("", definition.reference("../secret.md"));
        assertEquals("", definition.reference("/etc/passwd"));
    }

    @Test
    void invalidFrontmatterIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DubheSkillFrontmatter.parse(
                "name: search\n"));
        assertThrows(IllegalArgumentException.class, () -> DubheSkillFrontmatter.parse(
                "---\ndescription: x\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> DubheSkillFrontmatter.parse(
                "---\nname: Search\ndescription: x\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> DubheSkillFrontmatter.parse(
                "---\nname: search\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> DubheSkillFrontmatter.parse(
                "---\nname: search\ndescription: x\nunknown: y\n---\n"));
    }

    @Test
    void aSkillNameMustMatchItsDirectory() throws Exception {
        Path skill = skillDir("search",
                """
                        ---
                        name: other
                        description: entry
                        ---
                        body
                        """);

        assertThrows(IllegalArgumentException.class, () -> DubheSkillDefinitionLoader.load(skill));
    }

    @Test
    void textFormChecksTheExpectedName() {
        String document = """
                ---
                name: search
                description: entry
                ---
                body
                """;

        assertEquals("body", DubheSkillDefinitionLoader.loadText(document, "search", Map.of()).body());
        assertThrows(IllegalArgumentException.class,
                () -> DubheSkillDefinitionLoader.loadText(document, "other", Map.of()));
    }

    private Path skillDir(String name, String document) throws Exception {
        Path directory = tempDir.resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), document, StandardCharsets.UTF_8);
        return directory;
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}