package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import com.rheinmetal.tianshu.client.tool.BuiltinServerTools;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCatalogPushRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import com.rheinmetal.tianshu.mc.host.ServerTicks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pairs adapter registrations, tick callbacks and body state with the server that owns them, and owns the one moment
 * a player's catalogue starts flowing.
 *
 * <p>A player's client announces its channels some time after it joins, and every loader puts that boundary in a
 * different place. Joining therefore only records the player; {@link #tick()} keeps asking the loader's
 * {@link ServerCatalogueChannel} until it says the client can take a catalogue, then registers the change-driven push
 * and stops asking. Players that leave before their channel arrives are forgotten, so a failed join costs nothing.</p>
 */
public final class ServerToolSession {
    /** The approval gate for server actions: the level an operator has. */
    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private MinecraftServer server;

    private ServerCatalogueChannel catalogue;

    private final Set<UUID> awaitingCatalogue = new LinkedHashSet<>();

    private final Set<UUID> catalogued = new LinkedHashSet<>();

    private ServerRegistrations registrations;

    private WorldLocateSource locate;

    public void start(MinecraftServer server, ServerCatalogueChannel catalogue,
                      Consumer<ServerRegistrations> install) {
        release();
        this.server = Objects.requireNonNull(server, "server");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
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

    /**
     * A player joined. Their catalogue is delivered as soon as the client admits the channel, not necessarily now.
     *
     * <p>Safe to call again for the same player: a loader that also listens for late channel announcements (Fabric
     * reports them after JOIN) reaches here twice, and the second call finds the player already catalogued.</p>
     */
    public void onPlayerJoin(ServerPlayer player) {
        if (player == null || catalogue == null || catalogued.contains(player.getUUID())) {
            return;
        }
        awaitingCatalogue.add(player.getUUID());
        deliverCatalogue(player);
    }

    /** A player left: forget the pending delivery and the push subscription keyed by them. */
    public void onPlayerLogout(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        awaitingCatalogue.remove(id);
        catalogued.remove(id);
        ToolCatalogPushRegistry.unregister(id.toString());
        // The throttle is keyed by (tool, caller); without this every player who ever called a tool keeps a bucket
        // for the life of the process.
        TianshuTools.clearRateLimits(id.toString());
    }

    public void tick() {
        ServerTicks.INSTANCE.fire();
        if (locate != null) {
            locate.tick();
        }
        deliverPendingCatalogues();
    }

    public void stop(MinecraftServer server) {
        try {
            BodyRuntime.current().ifPresent(bodies -> bodies.persistence().save(server, bodies));
        } finally {
            release();
        }
    }

    private void deliverPendingCatalogues() {
        if (awaitingCatalogue.isEmpty() || server == null) {
            return;
        }
        for (UUID id : List.copyOf(awaitingCatalogue)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                awaitingCatalogue.remove(id);
                continue;
            }
            deliverCatalogue(player);
        }
    }

    private void deliverCatalogue(ServerPlayer player) {
        if (catalogue == null) {
            return;
        }
        if (!catalogue.ready(player)) {
            return;
        }
        UUID id = player.getUUID();
        String playerId = id.toString();
        boolean operator = player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
        // Registering publishes the current catalogue immediately, which is the delivery this method is named for.
        ToolCatalogPushRegistry.register(playerId, operator, (ignored, tools) -> catalogue.send(player, tools));
        awaitingCatalogue.remove(id);
        catalogued.add(id);
    }

    private void release() {
        awaitingCatalogue.clear();
        catalogued.clear();
        server = null;
        catalogue = null;
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