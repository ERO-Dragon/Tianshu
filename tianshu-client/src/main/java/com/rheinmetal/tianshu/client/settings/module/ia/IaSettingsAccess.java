package com.rheinmetal.tianshu.client.settings.module.ia;

public interface IaSettingsAccess {
    boolean isIaDiagnosticsEnabled();

    void setIaDiagnosticsEnabled(boolean enabled);

    void save();
}
