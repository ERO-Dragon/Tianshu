package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXRecentDialoguePromptContributorTest {
    @Test
    void gameChatTurnsAreInjectedAsContextNotUserInstructions() {
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXMemorySnapshot memory = new AXMemorySnapshot(
                "persona",
                List.of(),
                List.of(
                        AXRawTurn.dialogue(scope, "user", "帮我看看附近有什么", "session", "turn-1"),
                        AXRawTurn.gameChat(scope, "Steve", "有人看到村庄吗？", 1000L, "presence.chat:1"),
                        AXRawTurn.dialogue(scope, "assistant", "我会根据上下文判断。", "session", "turn-1")
                )
        );
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXRecentDialoguePromptContributor().contribute(new AXPromptBuildContext(
                new AXRequest("request", "当前问题", ""),
                new AXContextSnapshot(scope, memory, List.of(), ""),
                new AXContextBudget(4000, 4, 8, 4),
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);

        var messages = builder.build().messages();
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("<game_chat>"));
        assertTrue(messages.get(0).content().contains("Steve说：有人看到村庄吗？"));
        assertTrue(messages.get(0).content().contains("</game_chat>"));
        assertEquals("user", messages.get(1).role());
        assertEquals("assistant", messages.get(2).role());
    }
}
