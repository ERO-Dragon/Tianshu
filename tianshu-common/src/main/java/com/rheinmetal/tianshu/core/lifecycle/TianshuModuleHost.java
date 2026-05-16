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
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TianshuModuleHost {
    private final IGameEnvironment env;
    private final List<TianshuModuleInstallation> installations = new ArrayList<>();
    private final List<TianshuModuleInstallation> registered = new ArrayList<>();
    private final List<TianshuModuleInstallation> prepared = new ArrayList<>();
    private final List<TianshuModuleInstallation> started = new ArrayList<>();
    private final Map<String, ModuleLifecycleStatus> moduleStatuses = new LinkedHashMap<>();

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
        return List.copyOf(moduleStatuses.values());
    }

    public List<TianshuManagedModule> managedModules() {
        return installations.stream()
                .map(TianshuModuleInstallation::module)
                .toList();
    }

    public void registerAll(ModuleRegistrationContext context, RuntimeCapabilityRegistry capabilities) {
        registered.clear();
        for (TianshuModuleInstallation installation : installations) {
            installCapabilities(capabilities, installation);
            if (invoke(installation, ModuleLifecyclePhase.REGISTER, capabilities, () -> installation.module().register(context))) {
                registered.add(installation);
                markStatus(installation, ModuleLifecycleState.REGISTERED, ModuleLifecyclePhase.REGISTER, null);
            }
        }
    }

    public void registerAll(ModuleRegistrationContext context) {
        registerAll(context, null);
    }

    public void prepareAll(ModuleRuntimeContext context) {
        prepared.clear();
        RuntimeCapabilityRegistry capabilities = context == null ? null : context.runtimeState().capabilities();
        for (TianshuModuleInstallation installation : registered) {
            if (invoke(installation, ModuleLifecyclePhase.PREPARE, capabilities, () -> installation.module().prepare(context))) {
                prepared.add(installation);
                markStatus(installation, ModuleLifecycleState.PREPARED, ModuleLifecyclePhase.PREPARE, null);
            }
        }
    }

    public void startAll(ModuleRuntimeContext context) {
        started.clear();
        RuntimeCapabilityRegistry capabilities = context == null ? null : context.runtimeState().capabilities();
        for (TianshuModuleInstallation installation : prepared) {
            if (invoke(installation, ModuleLifecyclePhase.START, capabilities, () -> installation.module().start(context))) {
                started.add(installation);
                markStatus(installation, ModuleLifecycleState.STARTED, ModuleLifecyclePhase.START, null);
            }
        }
    }

    public void stopAll() {
        ModuleLifecycleException firstFailure = null;
        for (int i = started.size() - 1; i >= 0; i--) {
            TianshuModuleInstallation installation = started.get(i);
            firstFailure = invokeCleanup(firstFailure, installation, ModuleLifecyclePhase.STOP, installation.module()::stop);
        }
        started.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void destroyAll() {
        ModuleLifecycleException firstFailure = null;
        for (int i = prepared.size() - 1; i >= 0; i--) {
            TianshuModuleInstallation installation = prepared.get(i);
            firstFailure = invokeCleanup(firstFailure, installation, ModuleLifecyclePhase.DESTROY, installation.module()::destroy);
        }
        prepared.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void unregisterAll(ProtocolRuntime runtime) {
        ModuleLifecycleException firstFailure = null;
        for (int i = registered.size() - 1; i >= 0; i--) {
            TianshuModuleInstallation installation = registered.get(i);
            firstFailure = invokeCleanup(firstFailure, installation, ModuleLifecyclePhase.UNREGISTER, () -> runtime.unregisterModule(installation.moduleId()));
        }
        registered.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void clear() {
        installations.clear();
        registered.clear();
        prepared.clear();
        started.clear();
        moduleStatuses.clear();
    }

    public void clearActiveInstallations() {
        installations.clear();
        registered.clear();
        prepared.clear();
        started.clear();
    }

    private boolean invoke(TianshuModuleInstallation installation, ModuleLifecyclePhase phase, RuntimeCapabilityRegistry capabilities, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception exception) {
            ModuleLifecycleException failure = new ModuleLifecycleException(installation.moduleId(), phase, exception);
            markStatus(installation, ModuleLifecycleState.FAILED, phase, exception.getMessage());
            markCapabilitiesFailed(capabilities, installation, phase, exception);
            if (installation.required()) {
                throw failure;
            }
            env.warn("可选模块生命周期阶段失败，module=" + installation.moduleId() + ", phase=" + phase);
            return false;
        }
    }

    private ModuleLifecycleException invokeCleanup(ModuleLifecycleException firstFailure, TianshuModuleInstallation installation, ModuleLifecyclePhase phase, Runnable action) {
        try {
            action.run();
            markCleanupStatus(installation, phase);
            return firstFailure;
        } catch (Exception exception) {
            ModuleLifecycleException failure = new ModuleLifecycleException(installation.moduleId(), phase, exception);
            markStatus(installation, ModuleLifecycleState.FAILED, phase, exception.getMessage());
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

    private void markCapabilitiesFailed(RuntimeCapabilityRegistry capabilities, TianshuModuleInstallation installation, ModuleLifecyclePhase phase, Exception exception) {
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
