package com.rheinmetal.tianshu.client.runtime;

import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;

import java.util.Objects;

public record ClientRuntimeServices(
        TianshuCoreManager coreManager,
        AudioManager audioManager,
        ClientDiagnosticRouter diagnosticRouter,
        PresenceClientRuntime presenceRuntime,
        ClientNamedObjectIndexManager namedObjectIndexManager
) {
    public ClientRuntimeServices {
        Objects.requireNonNull(coreManager, "coreManager");
        Objects.requireNonNull(audioManager, "audioManager");
        Objects.requireNonNull(diagnosticRouter, "diagnosticRouter");
        Objects.requireNonNull(presenceRuntime, "presenceRuntime");
        Objects.requireNonNull(namedObjectIndexManager, "namedObjectIndexManager");
    }
}
