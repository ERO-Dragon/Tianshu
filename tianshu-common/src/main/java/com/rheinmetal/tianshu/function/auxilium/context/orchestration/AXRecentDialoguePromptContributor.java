package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.memory.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AXRecentDialoguePromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null || context.budget().maxShortTermTurns() <= 0) {
            return;
        }
        List<AXRawTurn> turns = context.context().memory().recentDialogueTurns();
        List<AXRawTurn> selected = turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .skip(Math.max(0, turns.size() - context.budget().maxShortTermTurns()))
                .toList();
        List<String> chatMessages = new ArrayList<>();
        List<AXRawTurn> dialogueTurns = new ArrayList<>();
        for (AXRawTurn turn : selected) {
            if (turn.gameChatRole()) {
                chatMessages.add(renderGameChat(context, turn));
                continue;
            }
            dialogueTurns.add(turn);
        }
        if (!chatMessages.isEmpty()) {
            String content = chatMessages.stream()
                    .map(text -> AXPromptSectionRenderer.renderLine(context, AXPromptTexts.GAME_CHAT_ITEM_LINE, "message", text))
                    .collect(Collectors.joining("\n"));
            builder.addSystemMessage(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_GAME_CHAT, content));
        }
        for (AXRawTurn turn : dialogueTurns) {
            builder.addMessage(turn.assistantRole() ? "assistant" : "user", turn.content());
        }

    }

    private String renderGameChat(AXPromptBuildContext context, AXRawTurn turn) {
        String sender = turn.speakerName();
        if (sender == null || sender.isBlank()) {
            sender = context.texts().text(AXPromptTexts.CHAT_UNKNOWN_SENDER);
        }
        return context.texts().render(AXPromptTexts.CHAT_MESSAGE_LINE, Map.of(
                "sender", sender,
                "message", turn.content()
        ));
    }
}
