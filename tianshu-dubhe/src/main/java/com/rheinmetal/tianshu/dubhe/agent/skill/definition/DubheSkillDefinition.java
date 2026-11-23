package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import java.util.Map;

/**
 * 一个加载好的技能包：入口元数据 + 正文 + 按需读取的 references。
 *
 * <p>渐进式披露（G58）：调用方先看 {@link #metadata()}（约百 token），决定激活时才读 {@link #body()}，
 * 具体细节再按相对路径读 {@link #reference(String)}。三者是分开的访问方法，不存在"一加载就全塞进上下文"。</p>
 *
 * <p>reference 的路径只能是包内相对路径（`references/xxx.md` 或更浅），`..` 与绝对路径一律拒绝。</p>
 */
public record DubheSkillDefinition(
        DubheSkillMetadata metadata,
        String body,
        Map<String, String> references
) {

    public DubheSkillDefinition {
        body = body == null ? "" : body.trim();
        references = references == null ? Map.of() : Map.copyOf(references);
    }

    /** 按需读取一篇 reference；不存在或路径不合法时返回空串。 */
    public String reference(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String normalized = relativePath.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            return "";
        }
        return references.getOrDefault(normalized, "");
    }
}