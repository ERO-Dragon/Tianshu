package com.rheinmetal.tianshu.dubhe.module.memory.summary;

import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptContributor;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptSectionRenderer;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemoryBlockView;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptTexts;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 摘要块贡献器（section id {@code memory_summary}）。
 *
 * <p>把累积的单元摘要按<b>新→旧</b>渲染成一条独立消息，位置固定为 system 之后——由
 * {@link DubhePromptAssemblyBuilder#addSummaryBlock} 的槽位保证，与贡献器执行顺序无关。</p>
 *
 * <p>它替换掉了旧的"最近 N 个 STM 块进尾部"槽位：摘要块不再是每轮都会变的尾部资料，而是前缀的一部分；
 * 只有换代才允许它的字节改变。</p>
 */
public final class DubheMemorySummaryPromptContributor implements DubhePromptContributor {
    public static final String SECTION_ID = "memory_summary";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null) {
            return;
        }
        DubheMemorySummaryBlock block = context.context().memory().summaryBlock();
        if (block == null || block.isEmpty()) {
            return;
        }
        String units = block.units().stream()
                .map(view -> renderUnit(context, view))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
        if (units.isBlank()) {
            return;
        }
        builder.addSummaryBlock(DubhePromptSectionRenderer.render(
                context,
                DubhePromptTexts.MEMORY_SUMMARY_BLOCK,
                Map.of("units", units)
        ));
    }

    private String renderUnit(DubhePromptBuildContext context, DubheMemoryBlockView view) {
        if (view == null) {
            return "";
        }
        return DubhePromptSectionRenderer.renderLine(context, DubhePromptTexts.MEMORY_SUMMARY_UNIT_LINE, "summary", view.content());
    }
}
