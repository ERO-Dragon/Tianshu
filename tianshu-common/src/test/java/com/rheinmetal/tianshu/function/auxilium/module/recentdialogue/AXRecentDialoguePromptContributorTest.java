package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialoguePromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXRecentDialoguePromptContributorTest {
    @Test
    void gameChatTurnsAreInjectedAsContextNotUserInstructions() {
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXRecentDialogueSnapshot recentDialogue = new AXRecentDialogueSnapshot(List.of(
                new AXRawTurn("", "user", "帮我看看附近有什么", 1000L, scope.worldId(), "session", "turn-1", 0, 0, ""),
                AXRawTurn.gameChat(scope, "Steve", "有人看到村庄吗？", 2000L, "presence.chat:1"),
                new AXRawTurn("", "assistant", "我会根据上下文判断。", 3000L, scope.worldId(), "session", "turn-1", 0, 0, "")
        ));
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXRecentDialoguePromptContributor().contribute(new AXPromptBuildContext(
                new AXRequest("request", "当前问题", ""),
                new AXContextSnapshot(scope, null, recentDialogue, List.of(), ""),
                new AXContextBudget(4000, 4, 8, 4),
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);

        // 近期对话展开为对话流，不再注入 system 段
        List<LLMPromptRequestPayload.MessageItemPayload> messages = builder.build().messages();
        assertEquals(3, messages.size());
        // 第一条：玩家输入 → user
        assertEquals("user", messages.get(0).role());
        assertEquals("帮我看看附近有什么", messages.get(0).content());
        // 第二条：其他玩家聊天 → user + xml 标签包裹，避免模型一并作答
        assertEquals("user", messages.get(1).role());
        assertEquals("<chat speaker=\"Steve\">有人看到村庄吗？</chat>", messages.get(1).content());
        // 第三条：AX 回复 → assistant
        assertEquals("assistant", messages.get(2).role());
        assertEquals("我会根据上下文判断。", messages.get(2).content());
    }
}
