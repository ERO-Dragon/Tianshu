package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AXPlayerMemoryPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "player_memory";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null) {
            return;
        }
        List<AXMemoryBlockView> retrieved = context.context().memory().retrievedPlayerMemoryBlocks();
        List<AXMemoryBlockView> recent = context.context().memory().recentPlayerMemoryBlocks();
        List<AXMemoryBlockView> selectedRetrieved = selectBlocks(
                retrieved,
                context.budget().maxRetrievedMemoryItems(),
                new LinkedHashSet<>()
        );
        List<AXMemoryBlockView> selectedRecent = selectBlocks(
                recent,
                context.budget().maxRecentMemoryItems(),
                seenKeys(selectedRetrieved)
        );
        if (selectedRetrieved.isEmpty() && selectedRecent.isEmpty()) {
            return;
        }
        List<String> sections = new java.util.ArrayList<>();
        String retrievedSection = renderHistoryGroup(context, AXPromptTexts.PLAYER_MEMORY_REMEMBERED_HISTORY_GROUP, selectedRetrieved);
        if (!retrievedSection.isBlank()) {
            sections.add(retrievedSection);
        }
        String recentSection = renderHistoryGroup(context, AXPromptTexts.PLAYER_MEMORY_RECENT_HISTORY_GROUP, selectedRecent);
        if (!recentSection.isBlank()) {
            sections.add(recentSection);
        }
        String content = sections.stream().filter(text -> text != null && !text.isBlank()).collect(Collectors.joining("\n\n"));
        if (content.isBlank()) {
            return;
        }
        builder.addContextSection(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_PLAYER_MEMORY, content));
    }

    private String renderHistoryGroup(AXPromptBuildContext context, String groupTemplateKey, List<AXMemoryBlockView> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        String summaries = blocks.stream()
                .map(view -> renderMemorySummary(context, view))
                .filter(text -> text != null && !text.isBlank())
                .map(text -> AXPromptSectionRenderer.renderLine(context, AXPromptTexts.PLAYER_MEMORY_SUMMARY_LINE, "summary", text))
                .collect(Collectors.joining("\n"));
        if (summaries.isBlank()) {
            return "";
        }
        return AXPromptSectionRenderer.render(context, groupTemplateKey, Map.of("summaries", summaries));
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

    private String renderMemorySummary(AXPromptBuildContext context, AXMemoryBlockView view) {
        if (view.attachedMessages().isEmpty()) {
            return view.content();
        }
        String events = view.attachedMessages().stream()
                .map(event -> AXPromptSectionRenderer.renderLine(
                        context,
                        AXPromptTexts.PLAYER_MEMORY_CONCURRENT_EVENT_LINE,
                        "event",
                        event
                ))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
        if (events.isBlank()) {
            return view.content();
        }
        String eventGroup = AXPromptSectionRenderer.render(
                context,
                AXPromptTexts.PLAYER_MEMORY_CONCURRENT_EVENTS_GROUP,
                Map.of("events", events)
        );
        return eventGroup.isBlank() ? view.content() : view.content() + "\n" + eventGroup;
    }
}
