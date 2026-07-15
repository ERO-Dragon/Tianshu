package com.rheinmetal.tianshu.client.config;

public interface ClientDiagnosticsConfiguration {
    boolean isAsrDiagnosticsEnabled();
    boolean isIrDiagnosticsEnabled();
    boolean isIaDiagnosticsEnabled();
    boolean isAxDiagnosticsEnabled();
    boolean isLlmDiagnosticsEnabled();
    boolean isTtsDiagnosticsEnabled();
}
