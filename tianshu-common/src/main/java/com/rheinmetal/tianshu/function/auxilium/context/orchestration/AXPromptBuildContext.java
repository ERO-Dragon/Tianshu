package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptProfile;

public record AXPromptBuildContext(
        AXRequest request,
        AXContextSnapshot context,
        AXContextBudget budget,
        AXPromptLanguage language,
        AXPromptProfile profile
) {
    public AXPromptBuildContext {
        budget = budget == null ? AXContextBudget.DEFAULT : budget;
        language = language == null ? AXPromptLanguage.EN_US : language;
        profile = profile == null ? AXPromptProfile.defaultFor(null, language) : profile;
    }
}
