package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

public record AXPromptBuildContext(
        AXRequest request,
        AXContextSnapshot context,
        AXContextBudget budget,
        AXPromptLanguage language,
        AXPromptProfile profile,
        AXPromptTexts texts
) {
    public AXPromptBuildContext(
            AXRequest request,
            AXContextSnapshot context,
            AXContextBudget budget,
            AXPromptLanguage language,
            AXPromptProfile profile
    ) {
        this(request, context, budget, language, profile, null);
    }

    public AXPromptBuildContext {
        budget = budget == null ? AXContextBudget.DEFAULT : budget;
        language = language == null ? AXPromptLanguage.EN_US : language;
        profile = profile == null ? AXPromptProfile.defaultFor(null, language) : profile;
        texts = texts == null ? AXPromptTexts.builtin(language) : texts;
    }
}
