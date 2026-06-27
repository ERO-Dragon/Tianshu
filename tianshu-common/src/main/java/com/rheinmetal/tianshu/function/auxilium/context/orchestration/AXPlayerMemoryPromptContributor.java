package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AXPlayerMemoryPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null || context.budget().maxMemoryItems() <= 0) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<AXMemoryBlockView> blocks = context.context().memory().playerMemoryBlocks();
        int blockLimit = Math.max(0, context.budget().maxMemoryItems());
        blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .skip(Math.max(0, blocks.size() - blockLimit))
                .map(view -> render(context, view))
                .forEach(lines::add);
        if (lines.isEmpty()) {
            return;
        }
        String content = lines.stream()
                .map(text -> AXPromptSectionRenderer.renderLine(context, AXPromptTexts.PLAYER_MEMORY_BLOCK_LINE, "content", text))
                .collect(Collectors.joining("\n"));
        builder.addSystemMessage(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_PLAYER_MEMORY, content));
    }

    private String render(AXPromptBuildContext context, AXMemoryBlockView view) {
        StringBuilder builder = new StringBuilder(view.content());
        if (!view.attachedMessages().isEmpty()) {
            String title = context.texts().text(AXPromptTexts.PLAYER_MEMORY_ATTACHED_TITLE);
            builder.append('\n').append(AXPromptSectionRenderer.render(context, AXPromptTexts.PLAYER_MEMORY_ATTACHED_HEADER, Map.of("title", title)));
            for (String message : view.attachedMessages()) {
                builder.append('\n').append(AXPromptSectionRenderer.render(context, AXPromptTexts.PLAYER_MEMORY_ATTACHED_LINE, Map.of("message", message)));
            }
        }
        return builder.toString();
    }
}
