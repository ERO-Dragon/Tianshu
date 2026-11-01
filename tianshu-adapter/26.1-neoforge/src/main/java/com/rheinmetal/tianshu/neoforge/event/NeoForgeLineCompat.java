package com.rheinmetal.tianshu.neoforge.event;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bridges the two NeoForge members that were renamed between {@code 26.1.1} and {@code 26.1.2}.
 *
 * <p>This adapter ships one jar for 26.1, 26.1.1 and 26.1.2, so it compiles against the oldest patch. Two public
 * members moved inside that window and the old names were deleted rather than deprecated, so no single source can
 * spell both:</p>
 *
 * <ul>
 *   <li>the player block-break event: {@code BlockEvent.BreakEvent} (26.1.0, 26.1.1) became
 *       {@code net.neoforged.neoforge.event.level.block.BreakBlockEvent} (26.1.2);</li>
 *   <li>the post-damage amount: {@code LivingDamageEvent.Post.getNewDamage()} (26.1.0, 26.1.1) became
 *       {@code getInflictedDamage()} (26.1.2).</li>
 * </ul>
 *
 * <p>This is a rename bridge, not a general compatibility layer: both are reached by name, a name that is absent is
 * skipped, and a build that carries neither yields no fact (the same honest gap as a listener that never fires).
 * The block state comes from the stable {@code BlockEvent} base class, which both variants extend, and every lookup
 * is cached per class, so the reflection cost is paid once per session.</p>
 */
public final class NeoForgeLineCompat {

    /** The break event's names, newest first: exactly one of them exists in any 26.1 build. */
    private static final List<String> BREAK_EVENT_CLASS_NAMES = List.of(
            "net.neoforged.neoforge.event.level.block.BreakBlockEvent",
            "net.neoforged.neoforge.event.level.BlockEvent$BreakEvent");

    /** The post-damage accessor names, newest first. */
    private static final List<String> DAMAGE_ACCESSOR_NAMES = List.of("getInflictedDamage", "getNewDamage");

    private static final String PLAYER_ACCESSOR_NAME = "getPlayer";

    private static final Map<MemberKey, Optional<Method>> MEMBERS = new ConcurrentHashMap<>();

    private NeoForgeLineCompat() {
    }

    /**
     * Registers {@code handler} for the player block-break event of whichever 26.1 build is running.
     *
     * @param handler receives the breaking player and the block state that was broken
     * @return whether a break event class exists in this build ({@code false} means it was renamed again)
     */
    public static boolean registerBlockBreak(IEventBus gameBus, BiConsumer<Player, BlockState> handler) {
        for (String name : BREAK_EVENT_CLASS_NAMES) {
            Class<?> eventType = loadOrNull(name);
            if (eventType == null) {
                continue;
            }
            listen(gameBus, eventType, event -> deliverBreak(event, handler));
            return true;
        }
        return false;
    }

    /**
     * The amount of damage a completed hit reported, through whichever accessor this build has.
     *
     * <p>{@code getInflictedDamage()} is the damage dealt after mitigation; the older {@code getNewDamage()} is the
     * same number under its previous name, so the answer keeps the meaning it had before the rename instead of
     * falling back to an unrelated field.</p>
     *
     * @return the reported damage, or {@code 0} when this build carries neither accessor
     */
    public static float damageTaken(LivingDamageEvent.Post event) {
        for (String accessor : DAMAGE_ACCESSOR_NAMES) {
            Optional<Method> method = member(event.getClass(), accessor);
            if (method.isPresent()) {
                Object value = invoke(method.get(), event);
                return value instanceof Number amount ? amount.floatValue() : 0.0F;
            }
        }
        return 0.0F;
    }

    private static void deliverBreak(Object event, BiConsumer<Player, BlockState> handler) {
        if (!(event instanceof BlockEvent blockEvent)) {
            return;
        }
        Optional<Method> playerAccessor = member(event.getClass(), PLAYER_ACCESSOR_NAME);
        if (playerAccessor.isEmpty()) {
            return;
        }
        if (invoke(playerAccessor.get(), event) instanceof Player player) {
            handler.accept(player, blockEvent.getState());
        }
    }

    /** Registers a listener for a class that is only known by name; the bus keys listeners by exact event class. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void listen(IEventBus gameBus, Class<?> eventType, Consumer<Object> listener) {
        gameBus.addListener(EventPriority.NORMAL, false, (Class) eventType, (Consumer) listener);
    }

    private static Class<?> loadOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    private static Optional<Method> member(Class<?> owner, String name) {
        return MEMBERS.computeIfAbsent(new MemberKey(owner, name), key -> {
            try {
                return Optional.of(key.owner().getMethod(key.name()));
            } catch (NoSuchMethodException absent) {
                return Optional.empty();
            }
        });
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private record MemberKey(Class<?> owner, String name) {
    }
}
