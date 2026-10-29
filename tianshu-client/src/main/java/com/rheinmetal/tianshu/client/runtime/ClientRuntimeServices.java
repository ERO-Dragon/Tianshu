package com.rheinmetal.tianshu.client.runtime;

import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;
import com.rheinmetal.tianshu.core.TianshuCoreManager;

import java.util.List;
import java.util.Objects;

/**
 * The client resources the session state machine drives.
 *
 * <p>Module-owned resources are deliberately absent: a module's index, model cache or engine belongs to
 * that module and is started and stopped there. The kernel only drives what it owns, plus the
 * {@link WorldSessionListener}s the composition root registered — without knowing which module is behind one.</p>
 */
public record ClientRuntimeServices(
        TianshuCoreManager coreManager,
        AudioManager audioManager,
        ClientDiagnosticRouter diagnosticRouter,
        List<WorldSessionListener> worldSessionListeners
) {
    public ClientRuntimeServices {
        Objects.requireNonNull(coreManager, "coreManager");
        Objects.requireNonNull(audioManager, "audioManager");
        Objects.requireNonNull(diagnosticRouter, "diagnosticRouter");
        worldSessionListeners = worldSessionListeners == null ? List.of() : List.copyOf(worldSessionListeners);
    }
}
