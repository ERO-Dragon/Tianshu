package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.memory.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AXRecentDialoguePromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "recent_dialogue";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null || context.budget().maxShortTermTurns() <= 0) {
            return;
        }
        List<AXRawTurn> turns = context.context().memory().recentDialogueTurns();
        List<AXRawTurn> selected = turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .skip(Math.max(0, turns.size() - context.budget().maxShortTermTurns()))
                .sorted(Comparator.comparingLong(AXRawTurn::createdAtMillis))
                .toList();
        if (selected.isEmpty()) {
            return;
        }
        String content = selected.stream()
                .map(turn -> renderTurn(context, turn))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
        builder.addSystemMessage(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_RECENT_DIALOGUE, content));
    }

    private String renderTurn(AXPromptBuildContext context, AXRawTurn turn) {
        if (turn == null) {
            return "";
        }
        String speaker = speakerLabel(context, turn);
        String message = turn.content();
        if (speaker.isBlank() || message == null || message.isBlank()) {
            return "";
        }
        return context.texts().render(AXPromptTexts.RECENT_DIALOGUE_LINE, Map.of(
                "speaker", speaker,
                "message", message
        ));
    }

    private String speakerLabel(AXPromptBuildContext context, AXRawTurn turn) {
        if (turn.gameChatRole()) {
            String sender = turn.speakerName();
            if (sender == null || sender.isBlank()) {
                return context.texts().text(AXPromptTexts.RECENT_DIALOGUE_UNKNOWN_SPEAKER);
            }
            return sender.trim();
        }
        if (turn.assistantRole()) {
            return context.texts().text(AXPromptTexts.RECENT_DIALOGUE_ASSISTANT_SPEAKER);
        }
        return context.texts().text(AXPromptTexts.RECENT_DIALOGUE_USER_SPEAKER);
    }
}
