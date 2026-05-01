package com.rheinmetal.tianshu.function.GeminiCard;

import java.util.List;

public enum GeminiCardMechanismKey {
    REPAIRABLE("可维修"),
    UPGRADEABLE("可升级"),
    GROWABLE("可成长"),
    CHARGEABLE("可蓄力"),
    RESOURCE_COST("资源消耗"),
    SKILL_BINDING("技能绑定"),
    TRIGGER_CONDITION("触发条件"),
    USAGE_LIMITATION("使用限制"),
    DURABILITY_MECHANISM("耐久机制"),
    DEATH_PENALTY("特殊死亡惩罚");

    public static final List<GeminiCardMechanismKey> PROMPT_ORDER = List.of(values());

    private final String label;

    GeminiCardMechanismKey(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
