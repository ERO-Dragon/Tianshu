package com.rheinmetal.tianshu.function.auxilium.prompt;

import java.util.List;

public record AXPromptPlan(
        AXPromptProfile profile,
        List<AXPromptSection> sections
) {
    public AXPromptPlan {
        profile = profile == null ? AXPromptProfile.defaultFor(AXPromptTask.GENERAL_AX, AXPromptLanguage.EN_US) : profile;
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
