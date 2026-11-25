package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.installation.ModuleFailurePolicy;
import com.rheinmetal.tianshu.core.lifecycle.installation.TianshuModuleInstallation;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.lifecycle.status.ModuleLifecycleState;
import com.rheinmetal.tianshu.core.lifecycle.status.ModuleLifecycleStatus;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class TianshuModuleHost {
    private final IGameEnvironment env;
    private final List<TianshuModuleInstallation> installations = new CopyOnWriteArrayList<>();
    /**
     * Every phase this host has <em>entered</em>, in order, whether or not the module got through it.
     *
     * <p>Cleanup used to walk the "succeeded" lists, so a module that registered a handler and then threw was never
     * unregistered: the handler stayed routable while the diagnostics called the module FAILED. Recording the attempt
     * before invoking makes the reverse walk symmetric — what was entered is what gets undone.</p>
     */
    private final List<PhaseAttempt> attempted = new ArrayList<>();

    private record PhaseAttempt(TianshuModuleInstallation installation, ModuleLifecyclePhase phase) {
    }
    private final Map<String, ModuleLifecycleStatus> moduleStatuses = new ConcurrentHashMap<>();

    public TianshuModuleHost(IGameEnvironment env) {
        this.env = env;
    }

    public void registerModule(TianshuManagedModule module) {
        registerOptionalModule(module);
    }

    public void registerOptionalModule(TianshuManagedModule module) {
        if (module != null) {
            addInstallation(TianshuModuleInstallation.optional(module));
        }
    }

    public void registerOptionalModule(TianshuManagedModule module, RuntimeCapability... providedCapabilities) {
        if (module != null) {
            addInstallation(TianshuModuleInstallation.optional(module, providedCapabilities));
        }
    }

    public void registerRequiredModule(TianshuManagedModule module) {
        if (module != null) {
            addInstallation(TianshuModuleInstallation.required(module));
        }
    }

    public void registerRequiredModule(TianshuManagedModule module, RuntimeCapability... providedCapabilities) {
        if (module != null) {
            addInstallation(TianshuModuleInstallation.required(module, providedCapabilities));
        }
    }

    private void addInstallation(TianshuModuleInstallation installation) {
        installations.add(installation);
        markStatus(installation, ModuleLifecycleState.DECLARED, null, null);
    }

    public Collection<ModuleLifecycleStatus> moduleStatuses() {
        return moduleStatuses.values().stream()
                .sorted(java.util.Comparator.comparing(ModuleLifecycleStatus::moduleId))
                .toList();
    }

    public List<TianshuManagedModule> managedModules() {
        return installations.stream()
                .map(TianshuModuleInstallation::module)
                .toList();
    }

    public void registerAll(ModuleRegistrationContext context, RuntimeCapabilityRegistry capabilities) {
        attempted.removeIf(attempt -> attempt.phase() == ModuleLifecyclePhase.REGISTER);
        for (TianshuModuleInstallation installation : installations) {
            installCapabilities(capabilities, installation);
            attempted.add(new PhaseAttempt(installation, ModuleLifecyclePhase.REGISTER));
            if (invoke(installation, ModuleLifecyclePhase.REGISTER, capabilities, () -> installation.module().register(context))) {
                markStatus(installation, ModuleLifecycleState.REGISTERED, ModuleLifecyclePhase.REGISTER, null);
            }
        }
    }

    public void registerAll(ModuleRegistrationContext context) {
        registerAll(context, null);
    }

    public void prepareAll(ModuleRuntimeContext context) {
        attempted.removeIf(attempt -> attempt.phase() == ModuleLifecyclePhase.PREPARE);
        RuntimeCapabilityRegistry capabilities = context == null ? null : context.runtimeState().capabilities();
        // Only what was actually registered: a module that failed to register has no business being prepared.
        for (TianshuModuleInstallation installation : entered(ModuleLifecyclePhase.REGISTER)) {
            attempted.add(new PhaseAttempt(installation, ModuleLifecyclePhase.PREPARE));
            if (invoke(installation, ModuleLifecyclePhase.PREPARE, capabilities, () -> installation.module().prepare(context))) {
                markStatus(installation, ModuleLifecycleState.PREPARED, ModuleLifecyclePhase.PREPARE, null);
            }
        }
    }

    public void startAll(ModuleRuntimeContext context) {
        attempted.removeIf(attempt -> attempt.phase() == ModuleLifecyclePhase.START);
        RuntimeCapabilityRegistry capabilities = context == null ? null : context.runtimeState().capabilities();
        for (TianshuModuleInstallation installation : entered(ModuleLifecyclePhase.PREPARE)) {
            attempted.add(new PhaseAttempt(installation, ModuleLifecyclePhase.START));
            if (invoke(installation, ModuleLifecyclePhase.START, capabilities, () -> installation.module().start(context))) {
                markStatus(installation, ModuleLifecycleState.STARTED, ModuleLifecyclePhase.START, null);
            }
        }
    }

    public void stopAll() {
        ModuleLifecycleException firstFailure = null;
        // Everything whose start was entered is stopped, including the module whose start threw halfway.
        List<TianshuModuleInstallation> startedAttempts = entered(ModuleLifecyclePhase.START);
        for (int i = startedAttempts.size() - 1; i >= 0; i--) {
            TianshuModuleInstallation installation = startedAttempts.get(i);
            firstFailure = invokeCleanup(firstFailure, installation, ModuleLifecyclePhase.STOP, installation.module()::stop);
        }
        attempted.removeIf(attempt -> attempt.phase() == ModuleLifecyclePhase.START);
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void destroyAll() {
        ModuleLifecycleException firstFailure = null;
        // Same rule one level down: a module that registered and then threw in prepare still opened something.
        List<TianshuModuleInstallation> preparedAttempts = entered(ModuleLifecyclePhase.PREPARE);
        for (int i = preparedAttempts.size() - 1; i >= 0; i--) {
            TianshuModuleInstallation installation = preparedAttempts.get(i);
            firstFailure = invokeCleanup(firstFailure, installation, ModuleLifecyclePhase.DESTROY, installation.module()::destroy);
        }
        attempted.removeIf(attempt -> attempt.phase() == ModuleLifecyclePhase.PREPARE);
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void unregisterAll(java.util.function.Consumer<String> unregisterModule) {
        ModuleLifecycleException firstFailure = null;
        // A module that registered a handler before throwing left it routable; undoing the attempt is what removes it.
        List<TianshuModuleInstallation> registeredAttempts = entered(ModuleLifecyclePhase.REGISTER);
        for (int i = registeredAttempts.size() - 1; i >= 0; i--) {
            TianshuModuleInstallation installation = registeredAttempts.get(i);
            firstFailure = invokeCleanup(firstFailure, installation, ModuleLifecyclePhase.UNREGISTER, () -> unregisterModule.accept(installation.moduleId()));
        }
        attempted.removeIf(attempt -> attempt.phase() == ModuleLifecyclePhase.REGISTER);
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void clear() {
        installations.clear();
        attempted.clear();
        moduleStatuses.clear();
    }

    public void clearActiveInstallations() {
        installations.clear();
        attempted.clear();
        // Statuses deliberately survive: "a module failed to start" stays visible to diagnostics after the
        // session is torn down (pinned by TianshuCoreManagerLifecycleTest#requiredPrepareFailureRemainsVisibleAfterCleanup).
    }

    /** The installations whose entry into one phase was recorded, in order. */
    private List<TianshuModuleInstallation> entered(ModuleLifecyclePhase phase) {
        List<TianshuModuleInstallation> result = new ArrayList<>();
        for (PhaseAttempt attempt : attempted) {
            if (attempt.phase() == phase) {
                result.add(attempt.installation());
            }
        }
        return result;
    }

    private boolean invoke(TianshuModuleInstallation installation, ModuleLifecyclePhase phase, RuntimeCapabilityRegistry capabilities, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception | LinkageError exception) {
            return handleFailure(installation, phase, capabilities, exception);
        }
    }

    private boolean handleFailure(TianshuModuleInstallation installation, ModuleLifecyclePhase phase,
                                  RuntimeCapabilityRegistry capabilities, Throwable exception) {
        ModuleLifecycleException failure = new ModuleLifecycleException(installation.moduleId(), phase, exception);
        markStatus(installation, ModuleLifecycleState.FAILED, phase, exception.getMessage());
        markCapabilitiesFailed(capabilities, installation, phase, exception);
        if (installation.required()) {
            throw failure;
        }
        // The reason, not just the phase: "phase=REGISTER failed" sends whoever reads the log to the module's own
        // code anyway, and the module is optional, so this line is the only place the cause is ever written down.
        env.warn("可选模块生命周期阶段失败，module=" + installation.moduleId() + ", phase=" + phase
                + ", cause=" + exception);
        return false;
    }

    private ModuleLifecycleException invokeCleanup(ModuleLifecycleException firstFailure, TianshuModuleInstallation installation, ModuleLifecyclePhase phase, Runnable action) {
        try {
            action.run();
            markCleanupStatus(installation, phase);
            return firstFailure;
        } catch (Exception | LinkageError exception) {
            ModuleLifecycleException failure = new ModuleLifecycleException(installation.moduleId(), phase, exception);
            ModuleLifecycleStatus current = moduleStatuses.get(installation.moduleId());
            if (current == null || !current.failed()) {
                markStatus(installation, ModuleLifecycleState.FAILED, phase, exception.getMessage());
            }
            if (!installation.required()) {
                env.warn("可选模块清理阶段失败，module=" + installation.moduleId() + ", phase=" + phase);
                return firstFailure;
            }
            if (firstFailure == null) {
                return failure;
            }
            firstFailure.addSuppressed(failure);
            return firstFailure;
        }
    }

    private void markCleanupStatus(TianshuModuleInstallation installation, ModuleLifecyclePhase phase) {
        ModuleLifecycleStatus current = moduleStatuses.get(installation.moduleId());
        if (current != null && current.failed()) {
            return;
        }
        if (phase == ModuleLifecyclePhase.STOP) {
            markStatus(installation, ModuleLifecycleState.STOPPED, phase, null);
        } else if (phase == ModuleLifecyclePhase.DESTROY) {
            markStatus(installation, ModuleLifecycleState.DESTROYED, phase, null);
        } else if (phase == ModuleLifecyclePhase.UNREGISTER) {
            markStatus(installation, ModuleLifecycleState.UNREGISTERED, phase, null);
        }
    }

    private void installCapabilities(RuntimeCapabilityRegistry capabilities, TianshuModuleInstallation installation) {
        if (capabilities == null) {
            return;
        }
        for (RuntimeCapability capability : installation.providedCapabilities()) {
            capabilities.install(capability, installation.moduleId());
        }
    }

    private void markCapabilitiesFailed(RuntimeCapabilityRegistry capabilities, TianshuModuleInstallation installation, ModuleLifecyclePhase phase, Throwable exception) {
        if (capabilities == null) {
            return;
        }
        String reason = "Module lifecycle failed at " + phase + ": " + exception.getMessage();
        for (RuntimeCapability capability : installation.providedCapabilities()) {
            capabilities.markFailed(capability, installation.moduleId(), reason);
        }
    }

    private void markStatus(TianshuModuleInstallation installation, ModuleLifecycleState state, ModuleLifecyclePhase phase, String failureReason) {
        moduleStatuses.put(installation.moduleId(), new ModuleLifecycleStatus(
                installation.moduleId(),
                installation.failurePolicy(),
                state,
                phase,
                failureReason
        ));
    }
}
