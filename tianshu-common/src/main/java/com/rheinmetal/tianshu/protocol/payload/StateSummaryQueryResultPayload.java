package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.summary.StateSummary;
import com.rheinmetal.tianshu.protocol.summary.StateSummaryQuery;

import java.util.List;

public record StateSummaryQueryResultPayload(StateSummaryQuery query, List<StateSummary> summaries, long timestampMillis) implements ITianshuPayload {
    public StateSummaryQueryResultPayload {
        if (query == null) {
            throw new IllegalArgumentException("query cannot be null");
        }
        summaries = summaries == null || summaries.isEmpty() ? List.of() : List.copyOf(summaries);
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }
}
