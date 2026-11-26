package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;

import java.util.ArrayList;
import java.util.List;

/** Owns one server session's tools and ports. Closing releases only the instances it installed. */
public final class ServerRegistrations implements AutoCloseable {

    private record Installed(Class<?> port, Object implementation) {
    }

    private final List<TianshuTool<?, ?>> tools = new ArrayList<>();
    private final List<Installed> ports = new ArrayList<>();

    /** Registers a server-scoped port and remembers it for {@link #close()}. */
    public <T> void port(Class<T> type, T implementation) {
        TianshuRuntimePorts.register(type, implementation);
        if (type != null && implementation != null) {
            ports.add(new Installed(type, implementation));
        }
    }

    /** Register a server-owned instance and withdraw only that instance on close. */
    public void tool(TianshuTool<?, ?> tool) {
        if (tool == null || TianshuTools.find(tool.id()) == tool) {
            return;
        }
        try {
            TianshuTools.registerBuiltin(tool);
        } catch (CapabilityCatalog.DuplicateCapabilityException occupied) {
            // The existing provider owns this ID and its lifetime.
            return;
        }
        tools.add(tool);
    }

    /** True when this session installed the port, i.e. a lookup resolves to something a stop has to release. */
    public boolean owns(Class<?> type) {
        return ports.stream().anyMatch(installed -> installed.port().equals(type));
    }

    @Override
    public void close() {
        tools.forEach(TianshuTools::unregister);
        tools.clear();
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