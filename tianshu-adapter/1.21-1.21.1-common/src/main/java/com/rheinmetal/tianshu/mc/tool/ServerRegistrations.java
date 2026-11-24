package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.util.ArrayList;
import java.util.List;

/**
 * What one server start installed, so the matching stop can take it back.
 *
 * <p>The loaders used to register ports and tools inline in the start callback and release almost none of them. That had
 * two consequences the review found: a port holding the {@code MinecraftServer} stayed reachable after the world was
 * closed, and the second server start in the same process registered a fresh action-tool instance over an id that
 * already existed — which throws, and aborts the rest of the start.</p>
 *
 * <p>Two lifetimes are therefore kept apart on purpose:</p>
 * <ul>
 *   <li><b>ports</b> are server-scoped: they capture the server, so {@link #close()} removes each one it installed, and
 *       only if it is still the instance this session installed.</li>
 *   <li><b>tools</b> are process-scoped and stateless: they are registered once and left alone. Registering them again
 *       for a second world would be a no-op rather than an error.</li>
 * </ul>
 */
public final class ServerRegistrations implements AutoCloseable {

    private record Installed(Class<?> port, Object implementation) {
    }

    private final List<Installed> ports = new ArrayList<>();

    /** Registers a server-scoped port and remembers it for {@link #close()}. */
    public <T> void port(Class<T> type, T implementation) {
        TianshuRuntimePorts.register(type, implementation);
        if (type != null && implementation != null) {
            ports.add(new Installed(type, implementation));
        }
    }

    /**
     * Registers a tool unless the id is already present.
     *
     * <p>The instance is a descriptor, not a session object, so the one already in the registry is as good as a new
     * one; creating a second instance for the same id is what used to throw on the second world.</p>
     */
    public void tool(TianshuTool<?, ?> tool) {
        if (tool == null) {
            return;
        }
        ToolId id = tool.id();
        if (TianshuTools.find(id) == null) {
            TianshuTools.register(tool);
        }
    }

    /** True when this session installed the port, i.e. a lookup resolves to something a stop has to release. */
    public boolean owns(Class<?> type) {
        return ports.stream().anyMatch(installed -> installed.port().equals(type));
    }

    @Override
    public void close() {
        // Reverse order: later ports may depend on the ones registered before them.
        for (int index = ports.size() - 1; index >= 0; index--) {
            Installed installed = ports.get(index);
            unregister(installed.port(), installed.implementation);
        }
        ports.clear();
    }

    /** Erases the type parameter so one loop can release ports of different types. */
    @SuppressWarnings("unchecked")
    private static <T> void unregister(Class<?> port, Object implementation) {
        TianshuRuntimePorts.unregister((Class<T>) port, (T) implementation);
    }
}