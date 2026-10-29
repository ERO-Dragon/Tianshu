package com.rheinmetal.tianshu.api.assembly;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusQuery;

import java.util.List;
import java.util.Optional;

/**
 * The kernel services a module's UI may use.
 *
 * <p>A settings page needs to reach its module's services, to observe the pipeline it is part of and to ask for a
 * refresh after it saves. This port is that narrow surface: it exposes neither the host nor the module registry, so a
 * page cannot take a privileged path.</p>
 */
public interface ModuleRuntimeServices {

    <T> Optional<T> findService(Class<T> type);

    <T> T requireService(Class<T> type);

    void requestRefresh(RuntimeRefreshReason reason);

    /**
     * The statuses the kernel has cached, filtered by module and/or type.
     *
     * <p>Read-only and bounded by the cache: a diagnostics page can show what the pipeline is doing without
     * subscribing to anything or keeping its own history.</p>
     */
    List<ModuleStatus> queryModuleStatuses(ModuleStatusQuery query);
}
