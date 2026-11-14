package com.rheinmetal.tianshu.api.diagnostics;

@FunctionalInterface
public interface DiagnosticSink {
    DiagnosticSink NOOP = event -> { };

    void publish(DiagnosticEvent event);
}
