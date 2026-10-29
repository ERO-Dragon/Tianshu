package com.rheinmetal.tianshu.client.runtime;

import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;
import com.rheinmetal.tianshu.core.TianshuCoreManager;

import java.util.Objects;

/**
 * The client resources the session state machine drives.
 *
 * <p>Module-owned resources are deliberately absent: a module's index, model cache or engine belongs to
 * that module and is started and stopped there. A feature that needs a world session opens it from its own
 * module lifecycle, so the kernel never names one.</p>
 */
public record ClientRuntimeServices(
        TianshuCoreManager coreManager,
        AudioManager audioManager,
        ClientDiagnosticRouter diagnosticRouter
) {
    public ClientRuntimeServices {
        Objects.requireNonNull(coreManager, "coreManager");
        Objects.requireNonNull(audioManager, "audioManager");
        Objects.requireNonNull(diagnosticRouter, "diagnosticRouter");
    }
}
