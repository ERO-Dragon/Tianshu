package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SKILL.md 的前缀（frontmatter）解析器。
 *
 * <p>只解析 Agent Skills 规范用到的那个 YAML 子集，不是通用 YAML：</p>
 * <ul>
 *   <li>顶层标量：{@code name / description / license / compatibility / allowed-tools}；</li>
 *   <li>{@code metadata:} 下一层缩进的 {@code key: value}；</li>
 *   <li>值允许单双引号包裹；不支持多行折叠、锚点、列表等其余语法。</li>
 * </ul>
 *
 * <p>未知的顶层键直接报错：配置写错了应该在加载时炸，而不是被静默忽略。</p>
 */
public final class DubheSkillFrontmatter {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1024;

    private DubheSkillFrontmatter() {
    }

    public record Parsed(DubheSkillMetadata metadata, String body) {
    }

    public static Parsed parse(String document) {
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("SKILL.md is empty");
        }
        List<String> lines = java.util.Arrays.asList(document.replace("\r\n", "\n").split("\n", -1));
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            throw new IllegalArgumentException("SKILL.md must start with a frontmatter block (---)");
        }
        int end = -1;
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).trim().equals("---")) {
                end = index;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md frontmatter is not closed with ---");
        }

        String name = "";
        String description = "";
        String license = "";
        String compatibility = "";
        List<String> allowedTools = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        boolean inMetadata = false;

        for (int index = 1; index < end; index++) {
            String raw = lines.get(index);
            if (raw.isBlank()) {
                continue;
            }
            boolean indented = raw.startsWith("  ") || raw.startsWith("\t");
            String line = raw.trim();
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("bad frontmatter line: " + line);
            }
            String key = line.substring(0, separator).trim();
            String value = unquote(line.substring(separator + 1).trim());

            if (indented) {
                if (!inMetadata) {
                    throw new IllegalArgumentException("indented key outside metadata: " + key);
                }
                metadata.put(key, value);
                continue;
            }
            inMetadata = false;
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "license" -> license = value;
                case "compatibility" -> compatibility = value;
                case "allowed-tools" -> {
                    if (!value.isBlank()) {
                        allowedTools.addAll(List.of(value.split("\\s+")));
                    }
                }
                case "metadata" -> inMetadata = true;
                default -> throw new IllegalArgumentException("unknown frontmatter key: " + key);
            }
        }

        validateName(name);
        if (description.isBlank() || description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description must be 1-" + MAX_DESCRIPTION + " characters");
        }
        String body = String.join("\n", lines.subList(end + 1, lines.size())).trim();
        return new Parsed(
                new DubheSkillMetadata(name, description, license, compatibility, allowedTools, metadata),
                body
        );
    }

    private static void validateName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("frontmatter name is required");
        }
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("frontmatter name exceeds " + MAX_NAME + " characters");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("frontmatter name must be lowercase words joined by single hyphens: " + name);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}