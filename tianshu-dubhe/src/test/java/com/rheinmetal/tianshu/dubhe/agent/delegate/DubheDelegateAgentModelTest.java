package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentEntry;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentModelReply;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentRequest;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunCancellation;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepOutcome;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheDelegateAgentModelTest {

    private static final DubheSkillSpec ECHO = DubheSkillSpec.builder("echo", "回显一句话")
            .budget(DubheSkillBudget.of(1_000L, 1))
            .build();

    private static DubheAgentRequest request() {
        List<DubheAgentEntry> entries = List.of(
                new DubheAgentEntry(DubheAgentEntry.Kind.BRIEF, "问题"),
                new DubheAgentEntry(DubheAgentEntry.Kind.DIGEST, "<progress>{}</progress>"),
                DubheAgentEntry.assistant("", List.of(new LLMToolCallPayload("c1", "echo", "{}"))),
                DubheAgentEntry.toolResult("c1", "{\"fact\":1}"));
        return new DubheAgentRequest("task-1", "问题", entries, List.of(ECHO), 5_000L,
                DubheRunCancellation.none(), "");
    }

    private static DubheDelegateAgentModel modelThatAnswers(LLMPromptResultPayload result,
                                                            List<LLMPromptRequestPayload> captured) {
        DubheDelegateRequestSender sender = (payload, handler, timeout) -> {
            captured.add(payload);
            handler.onResult(result);
        };
        return new DubheDelegateAgentModel(sender, null);
    }

    @Test
    void theDirectoryTravelsAsNativeToolsAndTheConversationIsReplayed() {
        List<LLMPromptRequestPayload> captured = new ArrayList<>();
        DubheDelegateAgentModel model = modelThatAnswers(
                LLMPromptResultPayload.completed("req", "{\"result\":\"好\"}"), captured);

        model.next(request());

        LLMPromptRequestPayload payload = captured.get(0);
        assertEquals(1, payload.tools().size());
        assertEquals("echo", payload.tools().get(0).name());
        assertEquals("回显一句话", payload.tools().get(0).description());

        List<LLMPromptRequestPayload.MessageItemPayload> messages = payload.messages();
        assertEquals("问题", messages.get(0).content(), "第一句是玩家要办的事");
        assertEquals("<progress>{}</progress>", messages.get(1).content(), "进度摘要作为上下文");
        assertEquals("assistant", messages.get(2).role());
        assertEquals("c1", messages.get(2).toolCalls().get(0).id());
        assertEquals("tool", messages.get(3).role());
        assertEquals("c1", messages.get(3).toolCallId(), "工具结果必须回答对应的调用 id");
    }

    @Test
    void toolCallsBecomeSkillCalls() {
        List<LLMPromptRequestPayload> captured = new ArrayList<>();
        DubheDelegateAgentModel model = modelThatAnswers(
                LLMPromptResultPayload.completed("req", "", "", LLMPromptResultPayload.TokenUsagePayload.empty(),
                        List.of(new LLMToolCallPayload("c9", "echo", "{\"q\":\"x\"}"))),
                captured);

        DubheStepOutcome<DubheAgentModelReply> outcome = model.next(request());

        DubheStepOutcome.Finished<?> finished = assertInstanceOf(DubheStepOutcome.Finished.class, outcome);
        DubheAgentModelReply reply = (DubheAgentModelReply) finished.result();
        assertEquals(1, reply.calls().size());
        assertEquals("c9", reply.calls().get(0).id());
        assertEquals("echo", reply.calls().get(0).skillId());
        assertEquals("x", reply.calls().get(0).arguments().get("q").getAsString());
    }

    @Test
    void aTextOnlyAnswerIsAFinalReply() {
        List<LLMPromptRequestPayload> captured = new ArrayList<>();
        DubheDelegateAgentModel model = modelThatAnswers(
                LLMPromptResultPayload.completed("req", "{\"result\":\"好\"}"), captured);

        DubheStepOutcome<DubheAgentModelReply> outcome = model.next(request());

        DubheStepOutcome.Finished<?> finished = assertInstanceOf(DubheStepOutcome.Finished.class, outcome);
        DubheAgentModelReply reply = (DubheAgentModelReply) finished.result();
        assertTrue(reply.finished());
        assertEquals("好", reply.report().result());
        assertEquals("", reply.report().block());
    }

    @Test
    void anIncompleteResultIsRejectedBeforeItReachesTheModelLoop() {
        List<LLMPromptRequestPayload> captured = new ArrayList<>();
        DubheDelegateAgentModel model = modelThatAnswers(
                LLMPromptResultPayload.failed("req", "provider_down", "boom"), captured);

        assertInstanceOf(DubheStepOutcome.Rejected.class, model.next(request()));
    }
}