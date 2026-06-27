package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

public final class AXProvidedContextPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().providedContext().isBlank()) {
            return;
        }
        builder.addSystemMessage("<provided_context>\n" + context.context().providedContext() + "\n</provided_context>");
    }
}
