package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

public interface AXPromptContributor {
    String sectionId();

    void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder);
}
