package com.rheinmetal.tianshu.dubhe.dialogue.currentinput;

import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptContributor;
public final class DubheCurrentInputPromptContributor implements DubhePromptContributor {
    public static final String SECTION_ID = "current_input";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder) {
        String text = context.request() == null ? "" : context.request().userText();
        builder.addCurrentInput(limit(text, context.budget().maxCurrentInputChars()));
    }

    private String limit(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.strip();
    }
}
