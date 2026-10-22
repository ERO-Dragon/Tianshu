package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索命中记忆的尾部段（section id {@code player_memory}）。
 *
 * <p>只负责<b>检索召回</b>的那部分：RAG 命中的事件摘要连同当时的世界事件，作为尾部动态资料渲染。
 * 窗口边界的累积摘要不在这里——它属于可缓存前缀，由
 * {@link com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryPromptContributor}
 * 渲染在 system 之后。旧的"最近 N 个 STM 块"槽位已删除，不允许两套并存。</p>
 */
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
        List<AXMemoryBlockView> selected = selectBlocks(
                context.context().memory().retrievedPlayerMemoryBlocks(),
                context.budget().maxRetrievedMemoryItems()
        );
        if (selected.isEmpty()) {
            return;
        }
        String content = renderHistoryGroup(context, AXPromptTexts.PLAYER_MEMORY_REMEMBERED_HISTORY_GROUP, selected);
        if (content.isBlank()) {
            return;
        }
        builder.addTailSection(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_PLAYER_MEMORY, content));
    }

    private String renderHistoryGroup(AXPromptBuildContext context, String groupTemplateKey, List<AXMemoryBlockView> blocks) {
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

    private List<AXMemoryBlockView> selectBlocks(List<AXMemoryBlockView> blocks, int limit) {
        if (blocks == null || blocks.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<AXMemoryBlockView> selected = new ArrayList<>();
        for (AXMemoryBlockView view : blocks) {
            if (view == null || view.isEmpty()) {
                continue;
            }
            selected.add(view);
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected);
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
