package com.rheinmetal.tianshu.function.auxilium.module.memory.summary;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 摘要块贡献器（section id {@code memory_summary}）。
 *
 * <p>把累积的单元摘要按<b>新→旧</b>渲染成一条独立消息，位置固定为 system 之后——由
 * {@link AXPromptAssemblyBuilder#addSummaryBlock} 的槽位保证，与贡献器执行顺序无关。</p>
 *
 * <p>它替换掉了旧的"最近 N 个 STM 块进尾部"槽位：摘要块不再是每轮都会变的尾部资料，而是前缀的一部分；
 * 只有换代才允许它的字节改变。</p>
 */
public final class AXMemorySummaryPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "memory_summary";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null) {
            return;
        }
        AXMemorySummaryBlock block = context.context().memory().summaryBlock();
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
        builder.addSummaryBlock(AXPromptSectionRenderer.render(
                context,
                AXPromptTexts.MEMORY_SUMMARY_BLOCK,
                Map.of("units", units)
        ));
    }

    private String renderUnit(AXPromptBuildContext context, AXMemoryBlockView view) {
        if (view == null) {
            return "";
        }
        return AXPromptSectionRenderer.renderLine(context, AXPromptTexts.MEMORY_SUMMARY_UNIT_LINE, "summary", view.content());
    }
}
