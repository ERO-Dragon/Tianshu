package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.gui.GuiContributionDescriptor;

public record GuiContributionPayload(GuiContributionDescriptor contribution) implements ITianshuPayload {
    public GuiContributionPayload {
        if (contribution == null) {
            throw new IllegalArgumentException("contribution cannot be null");
        }
    }
}
