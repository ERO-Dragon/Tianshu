package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AXPlayerMemoryPromptContributorTest {
    @Test
    void attachedMessagesFollowTheirStmInPlayerMemorySection() {
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXStmBlock stm = new AXStmBlock("", "", scope.worldId(), 1000L, 900L, 950L, "", "", 1, 0, "玩家准备挖矿。", List.of("awe_1"));
        AXMemorySnapshot memory = new AXMemorySnapshot(
                "persona",
                List.of(new AXMemoryBlockView(stm, List.of("玩家解锁成就：Stone Age", "玩家死亡一次。"))),
                List.of()
        );
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXPlayerMemoryPromptContributor().contribute(new AXPromptBuildContext(
                new AXRequest("request", "之前发生了什么？", ""),
                new AXContextSnapshot(scope, memory, List.of(), ""),
                new AXContextBudget(4000, 4, 8, 4),
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("玩家准备挖矿。"));
        assertTrue(content.contains("附属消息"));
        assertTrue(content.contains("玩家解锁成就：Stone Age"));
        assertTrue(content.contains("玩家死亡一次。"));
    }
}
