package com.rheinmetal.tianshu.api.tool;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional push side of the server catalogue. A subscriber is a per-player adapter channel; when the capability
 * table changes, the registry recomputes that player's visible tools and hands them to the channel.
 *
 * <p>This is additive: with no subscriber the platform still uses the login catalogue and the periodic refresh.
 * A subscriber exception is isolated; one broken transport must not break registration for everyone else.</p>
 *
 * <p>Change-driven pushes run on their own thread and collapse bursts. They used to run on whichever thread changed the
 * table — for MCP that is the bridge's connect thread, which holds that server's lock while it enumerates fifty tools,
 * so fifty changes meant fifty full-table scans plus fifty packets built inside the lock. The push implementations hop
 * to the game thread themselves, so doing this work elsewhere is safe; collapsing means a server connecting costs one
 * publish per player instead of one per tool.</p>
 */
public final class ToolCatalogPushRegistry {

    private record Subscriber(boolean operator, ToolCatalogPush push) {
    }

    private static final Map<String, Subscriber> SUBSCRIBERS = new ConcurrentHashMap<>();
    private static final CapabilityChange.Listener LISTENER = change -> broadcast();
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static final ExecutorService PUSHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-catalog-push");
        thread.setDaemon(true);
        return thread;
    });

    static {
        CapabilityCatalog.subscribe(LISTENER);
    }

    private ToolCatalogPushRegistry() {
    }

    public static void register(String playerId, boolean operator, ToolCatalogPush push) {
        if (playerId == null || playerId.isBlank() || push == null) {
            return;
        }
        Subscriber subscriber = new Subscriber(operator, push);
        SUBSCRIBERS.put(playerId, subscriber);
        // The player is waiting for their first catalogue, so this one is answered on the calling thread.
        publish(playerId, subscriber);
    }

    public static void unregister(String playerId) {
        if (playerId != null) {
            SUBSCRIBERS.remove(playerId);
        }
    }

    /** Releases subscriptions when the hosting server stops. */
    public static void clear() {
        SUBSCRIBERS.clear();
        PENDING.set(false);
    }

    public static int subscriberCount() {
        return SUBSCRIBERS.size();
    }

    private static void broadcast() {
        PENDING.set(true);
        if (PUSHER.isShutdown()) {
            return;
        }
        try {
            PUSHER.execute(ToolCatalogPushRegistry::drain);
        } catch (RejectedExecutionException shuttingDown) {
            // The process is going away; the next session pushes the whole catalogue per player anyway.
        }
    }

    /** Publishes once per burst: a change that arrives while this runs sets the flag again for a second pass. */
    private static void drain() {
        while (PENDING.getAndSet(false)) {
            publishAll();
        }
    }

    private static void publishAll() {
        if (SUBSCRIBERS.isEmpty()) {
            return;
        }
        // The visible set depends on the caller only through its operator flag, so it is computed twice per change
        // rather than once per player.
        List<ToolCatalogEntry> ordinary = visibleEntries(false);
        List<ToolCatalogEntry> privileged = visibleEntries(true);
        for (Map.Entry<String, Subscriber> entry : SUBSCRIBERS.entrySet()) {
            Subscriber subscriber = entry.getValue();
            deliver(entry.getKey(), subscriber, subscriber.operator() ? privileged : ordinary);
        }
    }

    private static List<ToolCatalogEntry> visibleEntries(boolean operator) {
        List<ToolCatalogEntry> entries = new ArrayList<>();
        for (ToolSpec spec : TianshuTools.catalog(ToolCaller.player("", operator), ToolSide.SERVER)) {
            if (spec.category().isServerSide()) {
                entries.add(ToolCatalogEntry.of(spec));
            }
        }
        return List.copyOf(entries);
    }

    private static void publish(String playerId, Subscriber subscriber) {
        deliver(playerId, subscriber, visibleEntries(subscriber.operator()));
    }

    private static void deliver(String playerId, Subscriber subscriber, List<ToolCatalogEntry> tools) {
        try {
            subscriber.push().push(playerId, tools);
        } catch (RuntimeException ignored) {
            // A broken adapter channel must not break the registry or another player's push.
        }
    }
}
