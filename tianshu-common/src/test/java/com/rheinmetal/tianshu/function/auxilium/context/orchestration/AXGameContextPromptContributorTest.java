package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextFact;
import com.rheinmetal.tianshu.function.auxilium.knowledge.AXKnowledgeHit;
import com.rheinmetal.tianshu.function.auxilium.knowledge.AXStaticKnowledgePlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AXGameContextPromptContributorTest {
    @Test
    void dynamicFactsAndKnowledgeShareTheSameGameContextSection() {
        AXStaticKnowledgePlanner planner = (request, context, budget) -> List.of(
                AXKnowledgeHit.of("ax.static_knowledge.mock", List.of("minecraft:anvil | 铁砧可以修复工具。"))
        );
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXGameContextPromptContributor(planner).contribute(new AXPromptBuildContext(
                new AXRequest("request", "铁砧怎么用？", ""),
                new AXContextSnapshot(
                        null,
                        null,
                        List.of(AXRuntimeContextFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                        ""
                ),
                AXContextBudget.DEFAULT,
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("<game_context>"));
        assertTrue(content.contains("动态环境"));
        assertTrue(content.contains("minecraft:anvil"));
        assertTrue(content.contains("静态知识"));
        assertTrue(content.contains("铁砧可以修复工具。"));
    }
}
