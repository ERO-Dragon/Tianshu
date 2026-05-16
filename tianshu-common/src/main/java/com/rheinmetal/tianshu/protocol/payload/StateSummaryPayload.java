package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.summary.StateSummary;

public record StateSummaryPayload(StateSummary summary) implements ITianshuPayload {
    public StateSummaryPayload {
        if (summary == null) {
            throw new IllegalArgumentException("summary cannot be null");
        }
    }
}
