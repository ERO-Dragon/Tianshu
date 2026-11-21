package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptProfile;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptTexts;

/**
 * 一次提示词装配所需的全部输入。
 *
 * <p>这里刻意不再携带 token 计数器：system 提示词已改为单一恒定的一份，不再按预算选档，
 * 装配阶段因而没有任何"先量一量再决定写什么"的需求。计数能力仍由
 * {@link com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSystem}
 * 在裁剪历史时使用。
 */
public record DubhePromptBuildContext(
        DubheRequest request,
        DubheContextSnapshot context,
        DubheContextBudget budget,
        DubhePromptLanguage language,
        DubhePromptProfile profile,
        DubhePromptTexts texts
) {
    public DubhePromptBuildContext(
            DubheRequest request,
            DubheContextSnapshot context,
            DubheContextBudget budget,
            DubhePromptLanguage language,
            DubhePromptProfile profile
    ) {
        this(request, context, budget, language, profile, null);
    }

    public DubhePromptBuildContext {
        budget = budget == null ? DubheContextBudget.DEFAULT : budget;
        language = language == null ? DubhePromptLanguage.EN_US : language;
        profile = profile == null ? DubhePromptProfile.defaultFor(null, language) : profile;
        texts = texts == null ? DubhePromptTexts.builtin(language) : texts;
    }
}
