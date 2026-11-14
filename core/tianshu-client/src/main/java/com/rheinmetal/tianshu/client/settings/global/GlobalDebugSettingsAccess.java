package com.rheinmetal.tianshu.client.settings.global;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;

public interface GlobalDebugSettingsAccess extends ClientDiagnosticsConfiguration {
    void setDebugEnabled(boolean enabled);

    void save();
}
