package com.rheinmetal.tianshu.dubhe.module.recentdialogue;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptProfile;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DubheRecentDialoguePromptContributorTest {
    private final DubheScope scope = new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);

    /**
     * One wrapper for every non-player source: an event that carries a speaker says who spoke inside the body,
     * because a model reading only the sentence cannot tell a companion's words from the player's own.
     */
    @Test
    void anotherSpeakersLineBecomesAnEventBodyNamingTheSpeaker() {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = render(List.of(
                new DubheRawTurn("", "user", "帮我看看附近有什么", 1000L, scope.worldId(), "session", "turn-1", 0, 0, ""),
                DubheRawTurn.worldEvent(scope, "Steve", "有人看到村庄吗？", 2000L, "presence.chat:1"),
                new DubheRawTurn("", "assistant", "我会根据上下文判断。", 3000L, scope.worldId(), "session", "turn-1", 0, 0, "")
        ));

        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("帮我看看附近有什么", messages.get(0).content());
        assertEquals("user", messages.get(1).role());
        assertEquals("<event>同伴 Steve 说：有人看到村庄吗？</event>", messages.get(1).content());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("我会根据上下文判断。", messages.get(2).content());
        for (LLMPromptRequestPayload.MessageItemPayload message : messages) {
            assertFalse(message.content().contains("<chat"), "the chat wrapper is retired: " + message.content());
        }
    }

    /** An event nobody spoke: the fact is the whole body. */
    @Test
    void anEventWithoutASpeakerIsTheFactAlone() {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = render(List.of(
                DubheRawTurn.worldEvent(scope, "玩家击杀了僵尸", 1000L)
        ));

        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("<event>玩家击杀了僵尸</event>", messages.get(0).content());
        assertFalse(messages.get(0).content().contains("同伴"), "nobody spoke, so nobody is named");
    }

    private List<LLMPromptRequestPayload.MessageItemPayload> render(List<DubheRawTurn> turns) {
        DubhePromptAssemblyBuilder builder = new DubhePromptAssemblyBuilder();
        new DubheRecentDialoguePromptContributor().contribute(new DubhePromptBuildContext(
                new DubheRequest("request", "当前问题", ""),
                new DubheContextSnapshot(scope, null, new DubheRecentDialogueSnapshot(turns), List.of(), ""),
                DubheContextBudget.DEFAULT,
                DubhePromptLanguage.ZH_CN,
                DubhePromptProfile.defaultFor(null, DubhePromptLanguage.ZH_CN)
        ), builder);
        return builder.build().messages();
    }
}
