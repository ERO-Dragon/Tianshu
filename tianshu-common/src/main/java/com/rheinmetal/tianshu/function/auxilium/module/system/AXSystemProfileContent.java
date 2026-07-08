package com.rheinmetal.tianshu.function.auxilium.module.system;

public record AXSystemProfileContent(
        String identity,
        String behaviorRules
) {
    public static final AXSystemProfileContent EMPTY = new AXSystemProfileContent("", "");

    public AXSystemProfileContent {
        identity = identity == null ? "" : identity.trim();
        behaviorRules = behaviorRules == null ? "" : behaviorRules.trim();
    }

    public boolean isEmpty() {
        return identity.isBlank() && behaviorRules.isBlank();
    }
}
