package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能包加载器（Agent Skills 规范的本地实现）。
 *
 * <p>目录形状：</p>
 * <pre>
 * skill-name/
 * ├── SKILL.md          # 必需：frontmatter + 正文
 * └── references/       # 可选：按需读取的参考文档
 *     ├── xxx.md
 *     └── yyy.md
 * </pre>
 *
 * <p>渐进式披露：{@link #loadMetadata(Path)} 只读 SKILL.md 的 frontmatter（目录用），
 * {@link #load(Path)} 才读正文与 references（激活用）。references 只扫一层 `.md`，不递归。</p>
 *
 * <p>安全：技能名必须与目录名一致；references 的键由文件名拼出，路径穿越（`..`、绝对路径）一律拒绝。</p>
 */
public final class DubheSkillDefinitionLoader {

    private static final String ENTRY = "SKILL.md";
    private static final String REFERENCES = "references";

    private DubheSkillDefinitionLoader() {
    }

    /** 目录形态的完整加载：frontmatter + 正文 + references。 */
    public static DubheSkillDefinition load(Path directory) {
        Path root = requireDirectory(directory);
        Path entry = root.resolve(ENTRY);
        if (!Files.isRegularFile(entry)) {
            throw new IllegalArgumentException("skill directory has no " + ENTRY + ": " + root);
        }
        DubheSkillFrontmatter.Parsed parsed = parse(entry, root);
        Map<String, String> references = new LinkedHashMap<>();
        Path referencesDir = root.resolve(REFERENCES);
        if (Files.isDirectory(referencesDir)) {
            try (Stream<Path> files = Files.list(referencesDir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(path -> references.put(
                                REFERENCES + "/" + path.getFileName(),
                                read(path)));
            } catch (IOException failure) {
                throw new IllegalArgumentException("cannot read references of " + root, failure);
            }
        }
        return new DubheSkillDefinition(parsed.metadata(), parsed.body(), references);
    }

    /** 只读 frontmatter（目录用）：不碰正文之外的 references，也不要求 references 存在。 */
    public static DubheSkillMetadata loadMetadata(Path directory) {
        Path root = requireDirectory(directory);
        Path entry = root.resolve(ENTRY);
        if (!Files.isRegularFile(entry)) {
            throw new IllegalArgumentException("skill directory has no " + ENTRY + ": " + root);
        }
        return parse(entry, root).metadata();
    }

    /** 文本形态（测试与内嵌资源）：调用方自己给出 reference 表。 */
    public static DubheSkillDefinition loadText(String document, String expectedName, Map<String, String> references) {
        DubheSkillFrontmatter.Parsed parsed = DubheSkillFrontmatter.parse(document);
        requireMatchingName(parsed.metadata().name(), expectedName);
        return new DubheSkillDefinition(parsed.metadata(), parsed.body(), references);
    }

    private static DubheSkillFrontmatter.Parsed parse(Path entry, Path root) {
        String document;
        try {
            document = Files.readString(entry, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read " + entry, failure);
        }
        DubheSkillFrontmatter.Parsed parsed = DubheSkillFrontmatter.parse(document);
        requireMatchingName(parsed.metadata().name(), root.getFileName().toString());
        return parsed;
    }

    private static void requireMatchingName(String name, String expected) {
        if (expected != null && !expected.isBlank() && !name.equals(expected)) {
            throw new IllegalArgumentException("skill name '" + name + "' must match its directory '" + expected + "'");
        }
    }

    private static Path requireDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("skill package must be a directory: " + directory);
        }
        return directory.toAbsolutePath().normalize();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read reference " + path, failure);
        }
    }
}