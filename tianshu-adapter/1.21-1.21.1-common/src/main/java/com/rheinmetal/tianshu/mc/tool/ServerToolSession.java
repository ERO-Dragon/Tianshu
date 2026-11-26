package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import com.rheinmetal.tianshu.client.tool.BuiltinServerTools;
import com.rheinmetal.tianshu.api.tool.ToolCatalogPushRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import com.rheinmetal.tianshu.mc.host.ServerTicks;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

/** Pairs adapter registrations, tick callbacks and body state with the server that owns them. */
public final class ServerToolSession {
    private ServerRegistrations registrations;

    private WorldLocateSource locate;

    public void start(MinecraftServer server, Consumer<ServerRegistrations> install) {
        release();
        registrations = new ServerRegistrations();
        try {
            locate = new WorldLocateSource(server);
            registrations.port(WorldLocatePort.class, locate);
            BuiltinServerTools.forEach(registrations::tool);
            install.accept(registrations);
        } catch (RuntimeException | Error failure) {
            release();
            throw failure;
        }
    }

    public void tick() {
        ServerTicks.INSTANCE.fire();
        if (locate != null) {
            locate.tick();
        }
    }

    public void stop(MinecraftServer server) {
        try {
            BodyRuntime.current().ifPresent(bodies -> bodies.persistence().save(server, bodies));
        } finally {
            release();
        }
    }

    private void release() {
        if (registrations != null) {
            if (locate != null) {
                locate.close();
                locate = null;
            }
            registrations.close();
            registrations = null;
            ServerTicks.INSTANCE.clear();
            ToolCatalogPushRegistry.clear();
            BodyRuntime.stop();
        }
    }
}
