package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.memory.AXStmBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class AXPlayerMemoryPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().memory() == null || context.budget().maxMemoryItems() <= 0) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<AXStmBlock> blocks = context.context().memory().playerMemoryBlocks();
        int blockLimit = Math.max(0, context.budget().maxMemoryItems());
        blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .skip(Math.max(0, blocks.size() - blockLimit))
                .map(AXStmBlock::content)
                .forEach(lines::add);
        if (lines.isEmpty()) {
            return;
        }
        builder.addSystemMessage(wrap("player_memory", lines.stream().map(text -> "- " + text.trim()).collect(Collectors.joining("\n"))));
    }

    private String wrap(String tag, String content) {
        return "<" + tag + ">\n" + content + "\n</" + tag + ">";
    }
}
