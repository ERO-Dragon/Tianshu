package com.rheinmetal.tianshu.function.llm.runtime;

public interface LlmPerformanceProvider {
    LlmPerformanceProvider UNAVAILABLE = () -> LlmPerformanceSnapshot.unavailable();

    LlmPerformanceSnapshot performanceSnapshot();
}
