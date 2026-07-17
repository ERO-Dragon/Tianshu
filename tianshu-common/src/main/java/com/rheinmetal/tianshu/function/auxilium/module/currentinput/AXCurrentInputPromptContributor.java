package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
public final class AXCurrentInputPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "current_input";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        String text = context.request() == null ? "" : context.request().userText();
        builder.addDialogueTurn("user", limit(text, context.budget().maxCurrentInputChars()));
    }

    private String limit(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.strip();
    }
}
