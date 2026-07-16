package com.rheinmetal.tianshu.client.settings.module.ir;

public interface IrSettingsAccess {
    boolean isIrDiagnosticsEnabled();

    void setIrDiagnosticsEnabled(boolean enabled);

    void save();
}
