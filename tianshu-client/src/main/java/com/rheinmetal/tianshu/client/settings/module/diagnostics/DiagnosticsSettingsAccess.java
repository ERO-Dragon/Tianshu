package com.rheinmetal.tianshu.client.settings.module.diagnostics;

public interface DiagnosticsSettingsAccess {
    boolean isIrDiagnosticsEnabled();
    void setIrDiagnosticsEnabled(boolean enabled);
    boolean isIaDiagnosticsEnabled();
    void setIaDiagnosticsEnabled(boolean enabled);
    void save();
}
