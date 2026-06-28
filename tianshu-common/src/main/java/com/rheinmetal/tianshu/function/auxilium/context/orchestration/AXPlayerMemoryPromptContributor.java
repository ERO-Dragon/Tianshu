package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AXPlayerMemoryPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null || context.budget().maxMemoryItems() <= 0) {
            return;
        }
        List<AXMemoryBlockView> retrieved = context.context().memory().retrievedPlayerMemoryBlocks();
        List<AXMemoryBlockView> recent = context.context().memory().recentPlayerMemoryBlocks();
        int blockLimit = Math.max(0, context.budget().maxMemoryItems());
        List<AXMemoryBlockView> selectedRetrieved = selectBlocks(retrieved, blockLimit, new LinkedHashSet<>());
        List<AXMemoryBlockView> selectedRecent = selectBlocks(recent, Math.max(0, blockLimit - selectedRetrieved.size()), seenKeys(selectedRetrieved));
        if (selectedRetrieved.isEmpty() && selectedRecent.isEmpty()) {
            return;
        }
        List<String> sections = new java.util.ArrayList<>();
        String retrievedSection = renderGroup(context, AXPromptTexts.PLAYER_MEMORY_RETRIEVED_TITLE, selectedRetrieved);
        if (!retrievedSection.isBlank()) {
            sections.add(retrievedSection);
        }
        String recentSection = renderGroup(context, AXPromptTexts.PLAYER_MEMORY_RECENT_TITLE, selectedRecent);
        if (!recentSection.isBlank()) {
            sections.add(recentSection);
        }
        String content = sections.stream().filter(text -> text != null && !text.isBlank()).collect(Collectors.joining("\n\n"));
        if (content.isBlank()) {
            return;
        }
        builder.addSystemMessage(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_PLAYER_MEMORY, content));
    }

    private String renderGroup(AXPromptBuildContext context, String titleKey, List<AXMemoryBlockView> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        String title = context.texts().text(titleKey);
        String body = blocks.stream()
                .map(view -> render(context, view))
                .filter(text -> text != null && !text.isBlank())
                .map(text -> AXPromptSectionRenderer.renderLine(context, AXPromptTexts.PLAYER_MEMORY_BLOCK_LINE, "content", text))
                .collect(Collectors.joining("\n"));
        if (body.isBlank()) {
            return "";
        }
        if (title.isBlank()) {
            return body;
        }
        return title + "\n" + body;
    }

    private List<AXMemoryBlockView> selectBlocks(List<AXMemoryBlockView> blocks, int limit, LinkedHashSet<String> seen) {
        if (blocks == null || blocks.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<AXMemoryBlockView> selected = new java.util.ArrayList<>();
        for (AXMemoryBlockView view : blocks) {
            if (view == null || view.isEmpty()) {
                continue;
            }
            String key = blockKey(view);
            if (!seen.add(key)) {
                continue;
            }
            selected.add(view);
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private LinkedHashSet<String> seenKeys(List<AXMemoryBlockView> blocks) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (blocks == null) {
            return seen;
        }
        for (AXMemoryBlockView block : blocks) {
            if (block != null && !block.isEmpty()) {
                seen.add(blockKey(block));
            }
        }
        return seen;
    }

    private String blockKey(AXMemoryBlockView view) {
        if (view == null || view.block() == null) {
            return "";
        }
        String id = view.block().id();
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        return view.block().contentHash();
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
