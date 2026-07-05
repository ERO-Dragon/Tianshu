package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AXGameContextPromptContributorTest {
    @Test
    void dynamicFactsAndDynamicRagHitsShareDynamicContent() {
        AXGameContextKnowledgePlanner planner = (request, context, budget) -> List.of(
                AXKnowledgeHit.dynamic("ax.dynamic_knowledge.mock", List.of("minecraft:anvil | 当前指向铁砧时，应说明修复、改名和附魔合并。")),
                AXKnowledgeHit.of("ax.static_knowledge.mock", List.of("minecraft:anvil | 铁砧可以修复工具。"))
        );
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXGameContextPromptContributor(planner).contribute(new AXPromptBuildContext(
                new AXRequest("request", "铁砧怎么用？", ""),
                new AXContextSnapshot(
                        null,
                        AXMemorySnapshot.empty(null),
                        AXRecentDialogueSnapshot.empty(),
                        List.of(AXDynamicFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                        ""
                ),
                AXContextBudget.DEFAULT,
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("<game_context>"));
        assertTrue(content.contains("动态内容"));
        assertTrue(content.contains("玩家准星指向 minecraft:anvil"));
        assertTrue(content.contains("当前指向铁砧时"));
        assertTrue(content.contains("静态内容"));
        assertTrue(content.contains("铁砧可以修复工具。"));
        assertOrdered(content, "动态内容", "玩家准星指向 minecraft:anvil", "当前指向铁砧时", "静态内容");
    }

    private static void assertOrdered(String text, String... fragments) {
        int cursor = -1;
        for (String fragment : fragments) {
            int index = text.indexOf(fragment);
            assertTrue(index > cursor, "fragment out of order or missing: " + fragment);
            cursor = index;
        }
    }
}
