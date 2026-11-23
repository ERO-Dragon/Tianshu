package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import java.util.List;
import java.util.Map;

/**
 * SKILL.md 的 frontmatter（Agent Skills 规范的子集）。
 *
 * @param name         技能名：小写字母/数字/连字符，1–64，不能以连字符开头或结尾、不能双连字符
 * @param description  何时用这个技能，1–1024
 * @param license      可选
 * @param compatibility 可选，环境要求
 * @param allowedTools 可选，预先允许的工具（空格分隔）
 * @param metadata     额外的键值对（一层）
 */
public record DubheSkillMetadata(
        String name,
        String description,
        String license,
        String compatibility,
        List<String> allowedTools,
        Map<String, String> metadata
) {

    public DubheSkillMetadata {
        license = license == null ? "" : license.trim();
        compatibility = compatibility == null ? "" : compatibility.trim();
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}