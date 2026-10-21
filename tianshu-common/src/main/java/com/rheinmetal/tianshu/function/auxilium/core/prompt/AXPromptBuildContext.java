package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

/**
 * 一次提示词装配所需的全部输入。
 *
 * <p>这里刻意不再携带 token 计数器：system 提示词已改为单一恒定的一份，不再按预算选档，
 * 装配阶段因而没有任何"先量一量再决定写什么"的需求。计数能力仍由
 * {@link com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem}
 * 在裁剪历史时使用。
 */
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
