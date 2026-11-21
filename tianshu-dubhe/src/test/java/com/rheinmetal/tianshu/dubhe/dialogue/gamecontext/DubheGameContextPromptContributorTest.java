package com.rheinmetal.tianshu.dubhe.dialogue.gamecontext;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySnapshot;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheGameContextPromptContributorTest {
    @Test
    void dynamicFactsAndDynamicRagHitsShareDynamicContent() {
        DubheGameContextKnowledgePlanner planner = (request, context, budget) -> List.of(
                DubheKnowledgeHit.dynamic("dubhe.dynamic_knowledge.mock", List.of("minecraft:anvil | 当前指向铁砧时，应说明修复、改名和附魔合并。")),
                DubheKnowledgeHit.of("dubhe.static_knowledge.mock", List.of("minecraft:anvil | 铁砧可以修复工具。"))
        );
        DubhePromptAssemblyBuilder builder = new DubhePromptAssemblyBuilder();

        new DubheGameContextPromptContributor(planner).contribute(new DubhePromptBuildContext(
                new DubheRequest("request", "铁砧怎么用？", ""),
                new DubheContextSnapshot(
                        null,
                        DubheMemorySnapshot.empty(),
                        DubheRecentDialogueSnapshot.empty(),
                        List.of(DubheDynamicFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                        ""
                ),
                DubheContextBudget.DEFAULT,
                DubhePromptLanguage.ZH_CN,
                DubhePromptProfile.defaultFor(null, DubhePromptLanguage.ZH_CN)
        ), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("<game_context>"));
        assertTrue(content.contains("以下信息与玩家当前所处的情况相关"));
        assertTrue(content.contains("玩家准星指向 minecraft:anvil"));
        assertTrue(content.contains("当前指向铁砧时"));
        assertTrue(content.contains("以下是与玩家当前问题相关的游戏知识"));
        assertTrue(content.contains("铁砧可以修复工具。"));
        assertOrdered(content, "玩家当前所处的情况", "玩家准星指向 minecraft:anvil", "当前指向铁砧时", "玩家当前问题相关的游戏知识");
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
