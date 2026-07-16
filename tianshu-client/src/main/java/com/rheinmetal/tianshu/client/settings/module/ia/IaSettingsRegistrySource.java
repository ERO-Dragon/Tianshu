package com.rheinmetal.tianshu.client.settings.module.ia;

import com.rheinmetal.tianshu.client.settings.module.ModuleDiagnosticsSettingsRegistrySource;

import java.util.Objects;

public final class IaSettingsRegistrySource extends ModuleDiagnosticsSettingsRegistrySource {
    public IaSettingsRegistrySource(IaSettingsAccess config) {
        super(
                "module.ia",
                "ia",
                40,
                Objects.requireNonNull(config, "config")::isIaDiagnosticsEnabled,
                config::setIaDiagnosticsEnabled,
                config::save
        );
    }
}
