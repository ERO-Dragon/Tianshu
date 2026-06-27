package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.memory.AXRawTurn;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;

public final class AXRecentDialoguePromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null || context.budget().maxShortTermTurns() <= 0) {
            return;
        }
        List<AXRawTurn> turns = context.context().memory().recentDialogueTurns();
        turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .skip(Math.max(0, turns.size() - context.budget().maxShortTermTurns()))
                .map(this::toMessage)
                .forEach(message -> builder.addMessage(message.role(), message.content()));
    }

    private LLMPromptRequestPayload.MessageItemPayload toMessage(AXRawTurn turn) {
        String role = turn.assistantRole() ? "assistant" : "user";
        return LLMPromptRequestPayload.MessageItemPayload.of(role, turn.content());
    }
}
