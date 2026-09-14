package com.rheinmetal.tianshu.client.diagnostics;

@FunctionalInterface
public interface ClientDiagnosticMessageSink {
    ClientDiagnosticMessageSink NOOP = message -> { };

    void publish(String message);
}
