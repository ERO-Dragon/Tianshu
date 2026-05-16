package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.summary.StateSummaryQuery;

public record StateSummaryQueryPayload(StateSummaryQuery query, long timestampMillis) implements ITianshuPayload {
    public StateSummaryQueryPayload {
        if (query == null) {
            throw new IllegalArgumentException("query cannot be null");
        }
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }
}
