package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentDigest;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentEntry;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheLlmAgentCompactorTest {

    private static DubheDelegatePromptRepository prompts() {
        return new DubheDelegatePromptRepository(
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));
    }

    @Test
    void theDigestIsParsedFromTheModelAnswer() {
        List<LLMPromptRequestPayload> captured = new ArrayList<>();
        DubheDelegateRequestSender sender = (payload, handler, timeout) -> {
            captured.add(payload);
            handler.onResult(LLMPromptResultPayload.completed("req",
                    "{\"goal\":\"问题\",\"done\":[\"查过\"],\"facts\":[\"事实\"],"
                            + "\"ruledOut\":[\"排除\"],\"open\":[],\"next\":\"下一步\"}"));
        };

        DubheAgentDigest digest = new DubheLlmAgentCompactor(sender, prompts()).compact(
                "问题", null, List.of(DubheAgentEntry.assistant("中间步骤")));

        assertEquals("问题", digest.goal());
        assertEquals(List.of("查过"), digest.done());
        assertEquals(List.of("事实"), digest.facts());
        assertEquals(List.of("排除"), digest.ruledOut());
        assertEquals("下一步", digest.next());
        List<LLMPromptRequestPayload.MessageItemPayload> messages = captured.get(0).messages();
        assertTrue(messages.get(messages.size() - 1).content().contains("\"goal\""),
                "the evicted rounds travel as data");
    }

    @Test
    void anUnparseableDigestFailsTheTaskInsteadOfInventingOne() {
        DubheDelegateRequestSender sender = (payload, handler, timeout) ->
                handler.onResult(LLMPromptResultPayload.completed("req", "not json"));

        DubheLlmAgentCompactor compactor = new DubheLlmAgentCompactor(sender, prompts());

        assertThrows(IllegalStateException.class,
                () -> compactor.compact("问题", null, List.of(DubheAgentEntry.assistant("步骤"))));
    }
}