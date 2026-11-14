package com.rheinmetal.tianshu.api.tool;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional push side of the server catalogue. A subscriber is a per-player adapter channel; when the capability
 * table changes, the registry recomputes that player's visible tools and hands them to the channel.
 *
 * <p>This is additive: with no subscriber the platform still uses the login catalogue and the periodic refresh.
 * A subscriber exception is isolated; one broken transport must not break registration for everyone else.</p>
 */
public final class ToolCatalogPushRegistry {

    private record Subscriber(boolean operator, ToolCatalogPush push) {
    }

    private static final Map<String, Subscriber> SUBSCRIBERS = new ConcurrentHashMap<>();
    private static final CapabilityChange.Listener LISTENER = change -> broadcast();

    static {
        CapabilityCatalog.subscribe(LISTENER);
    }

    private ToolCatalogPushRegistry() {
    }

    public static void register(String playerId, boolean operator, ToolCatalogPush push) {
        if (playerId == null || playerId.isBlank() || push == null) {
            return;
        }
        SUBSCRIBERS.put(playerId, new Subscriber(operator, push));
        publish(playerId, new Subscriber(operator, push));
    }

    public static void unregister(String playerId) {
        if (playerId != null) {
            SUBSCRIBERS.remove(playerId);
        }
    }

    /** Tests only: forget every subscriber. */
    public static void clear() {
        SUBSCRIBERS.clear();
    }

    public static int subscriberCount() {
        return SUBSCRIBERS.size();
    }

    private static void broadcast() {
        for (Map.Entry<String, Subscriber> entry : SUBSCRIBERS.entrySet()) {
            publish(entry.getKey(), entry.getValue());
        }
    }

    private static void publish(String playerId, Subscriber subscriber) {
        List<String> ids = new ArrayList<>();
        for (ToolSpec spec : TianshuTools.catalog(ToolCaller.player(playerId, subscriber.operator()),
                ToolSide.SERVER)) {
            ids.add(spec.id().toString());
        }
        try {
            subscriber.push().push(playerId, List.copyOf(ids));
        } catch (RuntimeException ignored) {
            // A broken adapter channel must not break the registry or another player's push.
        }
    }
}
