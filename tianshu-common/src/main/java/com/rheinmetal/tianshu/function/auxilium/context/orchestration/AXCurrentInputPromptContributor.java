package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

public final class AXCurrentInputPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        String text = context.request() == null ? "" : context.request().userText();
        builder.addUserMessage(text);
    }
}
