package com.rheinmetal.tianshu.api.assembly;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

import java.util.Optional;

/**
 * The kernel services a module's UI may use.
 *
 * <p>A settings page needs to reach its module's services and to ask for a refresh after it saves. This
 * port is that narrow surface: it exposes neither the host nor the module registry, so a page cannot
 * take a privileged path.</p>
 */
public interface ModuleRuntimeServices {

    <T> Optional<T> findService(Class<T> type);

    <T> T requireService(Class<T> type);

    void requestRefresh(RuntimeRefreshReason reason);
}
