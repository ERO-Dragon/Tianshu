package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameEffect;
import com.rheinmetal.tianshu.client.host.game.GameEquipment;
import com.rheinmetal.tianshu.client.host.game.GameInteractionContext;
import com.rheinmetal.tianshu.client.host.game.GameInventoryEntry;
import com.rheinmetal.tianshu.client.host.game.GamePlayerStatus;
import com.rheinmetal.tianshu.client.host.game.GameWorldEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Test double for the platform reading.
 *
 * <p>It records which slices were read, which is what the tests assert: the point of the grouped port is that a
 * caller pays only for the groups it asked for.</p>
 */
public final class FakeGameContextPort implements ClientGameContextPort {
    private final List<String> captures = new ArrayList<>();
    private String playerId = "player";
    private Supplier<GameInteractionContext> interaction = GameInteractionContext::empty;
    private Supplier<GamePlayerStatus> status = () -> GamePlayerStatus.EMPTY;
    private Supplier<List<GameInventoryEntry>> inventory = List::of;
    private Supplier<GameEquipment> equipment = () -> GameEquipment.EMPTY;
    private Supplier<List<GameEffect>> effects = List::of;
    private Supplier<GameWorldEnvironment> environment = () -> GameWorldEnvironment.EMPTY;
    private RuntimeException failure;

    public FakeGameContextPort playerId(String value) {
        this.playerId = value == null ? "" : value;
        return this;
    }

    public FakeGameContextPort interaction(Supplier<GameInteractionContext> value) {
        this.interaction = value == null ? GameInteractionContext::empty : value;
        return this;
    }

    public FakeGameContextPort status(Supplier<GamePlayerStatus> value) {
        this.status = value == null ? () -> GamePlayerStatus.EMPTY : value;
        return this;
    }

    public FakeGameContextPort inventory(Supplier<List<GameInventoryEntry>> value) {
        this.inventory = value == null ? List::of : value;
        return this;
    }

    public FakeGameContextPort equipment(Supplier<GameEquipment> value) {
        this.equipment = value == null ? () -> GameEquipment.EMPTY : value;
        return this;
    }

    public FakeGameContextPort effects(Supplier<List<GameEffect>> value) {
        this.effects = value == null ? List::of : value;
        return this;
    }

    public FakeGameContextPort environment(Supplier<GameWorldEnvironment> value) {
        this.environment = value == null ? () -> GameWorldEnvironment.EMPTY : value;
        return this;
    }

    public FakeGameContextPort failWith(RuntimeException value) {
        this.failure = value;
        return this;
    }

    /** The slices read since the last {@link #clearCaptures()}, in call order. */
    public List<String> captures() {
        return List.copyOf(captures);
    }

    public void clearCaptures() {
        captures.clear();
    }

    @Override
    public String playerId() {
        return playerId;
    }

    @Override
    public GameInteractionContext captureInteraction() {
        record("interaction");
        return nullSafe(interaction.get(), GameInteractionContext.empty());
    }

    @Override
    public GamePlayerStatus capturePlayerStatus() {
        record("status");
        return nullSafe(status.get(), GamePlayerStatus.EMPTY);
    }

    @Override
    public List<GameInventoryEntry> captureInventory() {
        record("inventory");
        List<GameInventoryEntry> value = inventory.get();
        return value == null ? List.of() : value;
    }

    @Override
    public GameEquipment captureEquipment() {
        record("equipment");
        return nullSafe(equipment.get(), GameEquipment.EMPTY);
    }

    @Override
    public List<GameEffect> captureEffects() {
        record("effects");
        List<GameEffect> value = effects.get();
        return value == null ? List.of() : value;
    }

    @Override
    public GameWorldEnvironment captureEnvironment() {
        record("environment");
        return nullSafe(environment.get(), GameWorldEnvironment.EMPTY);
    }

    private void record(String slice) {
        captures.add(slice);
        if (failure != null) {
            throw failure;
        }
    }

    private static <T> T nullSafe(T value, T fallback) {
        return value == null ? fallback : value;
    }
}