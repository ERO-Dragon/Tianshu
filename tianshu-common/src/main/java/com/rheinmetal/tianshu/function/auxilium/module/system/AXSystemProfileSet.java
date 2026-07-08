package com.rheinmetal.tianshu.function.auxilium.module.system;

import java.util.List;

public record AXSystemProfileSet(
        AXSystemProfileContent shortProfile,
        AXSystemProfileContent standardProfile,
        AXSystemProfileContent fullProfile
) {
    public AXSystemProfileSet {
        standardProfile = normalize(standardProfile, AXSystemProfileContent.EMPTY);
        shortProfile = normalize(shortProfile, standardProfile);
        fullProfile = normalize(fullProfile, standardProfile);
    }

    public static AXSystemProfileSet single(String identity, String behaviorRules) {
        AXSystemProfileContent content = new AXSystemProfileContent(identity, behaviorRules);
        return new AXSystemProfileSet(content, content, content);
    }

    public List<AXSystemProfileContent> largestFirst() {
        return List.of(fullProfile, standardProfile, shortProfile);
    }

    private static AXSystemProfileContent normalize(AXSystemProfileContent value, AXSystemProfileContent fallback) {
        if (value == null || value.isEmpty()) {
            return fallback == null ? AXSystemProfileContent.EMPTY : fallback;
        }
        return value;
    }
}
