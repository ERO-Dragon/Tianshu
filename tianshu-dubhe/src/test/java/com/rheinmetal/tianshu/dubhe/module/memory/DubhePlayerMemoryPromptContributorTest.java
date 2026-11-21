package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryBlock;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSnapshot;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptProfile;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DubhePlayerMemoryPromptContributorTest {
    @Test
    void attachedMessagesFollowTheirStmInPlayerMemorySection() {
        DubheScope scope = new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);
        DubheStmBlock stm = new DubheStmBlock("", "", scope.worldId(), 1000L, 900L, 950L, "", "", 1, 0, "玩家准备挖矿。", List.of("awe_1"));
        DubheMemorySnapshot memory = new DubheMemorySnapshot(
                List.of(new DubheMemoryBlockView(stm, List.of("玩家解锁成就：Stone Age", "玩家死亡一次。"))),
                DubheMemorySummaryBlock.empty()
        );
        DubhePromptAssemblyBuilder builder = new DubhePromptAssemblyBuilder();

        new DubhePlayerMemoryPromptContributor().contribute(new DubhePromptBuildContext(
                new DubheRequest("request", "之前发生了什么？", ""),
                new DubheContextSnapshot(scope, memory, DubheRecentDialogueSnapshot.empty(), List.of(), ""),
                DubheContextBudget.DEFAULT,
                DubhePromptLanguage.ZH_CN,
                DubhePromptProfile.defaultFor(null, DubhePromptLanguage.ZH_CN)
        ), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("你记得此前与玩家发生过这些事情"));
        assertTrue(content.contains("玩家准备挖矿。"));
        assertTrue(content.contains("当时还发生了"));
        assertTrue(content.contains("玩家解锁成就：Stone Age"));
        assertTrue(content.contains("玩家死亡一次。"));
    }
}
