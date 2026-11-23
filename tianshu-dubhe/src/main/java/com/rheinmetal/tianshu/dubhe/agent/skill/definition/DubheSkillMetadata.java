package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import java.util.List;

/**
 * SKILL.md 里天枢用得到的元数据。
 *
 * <p>解析统一走舟楫的 {@link com.rheinmetal.tianshu.api.capability.skill.FrontMatter}；这里只保留代理目录需要的字段，
 * 不再复制一份 YAML 解析器。</p>
 */
public record DubheSkillMetadata(
        String name,
        String description,
        List<String> allowedTools
) {

    public DubheSkillMetadata {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}