package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AXRecentDialoguePromptContributorTest {
    private final AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);

    /**
     * One wrapper for every non-player source: an event that carries a speaker says who spoke inside the body,
     * because a model reading only the sentence cannot tell a companion's words from the player's own.
     */
    @Test
    void anotherSpeakersLineBecomesAnEventBodyNamingTheSpeaker() {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = render(List.of(
                new AXRawTurn("", "user", "帮我看看附近有什么", 1000L, scope.worldId(), "session", "turn-1", 0, 0, ""),
                AXRawTurn.worldEvent(scope, "Steve", "有人看到村庄吗？", 2000L, "presence.chat:1"),
                new AXRawTurn("", "assistant", "我会根据上下文判断。", 3000L, scope.worldId(), "session", "turn-1", 0, 0, "")
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
                AXRawTurn.worldEvent(scope, "玩家击杀了僵尸", 1000L)
        ));

        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("<event>玩家击杀了僵尸</event>", messages.get(0).content());
        assertFalse(messages.get(0).content().contains("同伴"), "nobody spoke, so nobody is named");
    }

    private List<LLMPromptRequestPayload.MessageItemPayload> render(List<AXRawTurn> turns) {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();
        new AXRecentDialoguePromptContributor().contribute(new AXPromptBuildContext(
                new AXRequest("request", "当前问题", ""),
                new AXContextSnapshot(scope, null, new AXRecentDialogueSnapshot(turns), List.of(), ""),
                AXContextBudget.DEFAULT,
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);
        return builder.build().messages();
    }
}
