package com.rheinmetal.tianshu.client.settings.module.ir;

import com.rheinmetal.tianshu.client.settings.module.ModuleDiagnosticsSettingsRegistrySource;

import java.util.Objects;

public final class IrSettingsRegistrySource extends ModuleDiagnosticsSettingsRegistrySource {
    public IrSettingsRegistrySource(IrSettingsAccess config) {
        super(
                "module.ir",
                "ir",
                15,
                Objects.requireNonNull(config, "config")::isIrDiagnosticsEnabled,
                config::setIrDiagnosticsEnabled,
                config::save
        );
    }
}
