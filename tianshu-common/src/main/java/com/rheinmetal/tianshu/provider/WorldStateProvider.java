package com.rheinmetal.tianshu.provider;

public final class WorldStateProvider {

    private final IPlayerStateProvider playerState;
    private final IInventoryDataProvider inventory;
    private final IEnvironmentAwarenessProvider environment;
    private final ISocialDataProvider social;

    public WorldStateProvider(
            IPlayerStateProvider playerState,
            IInventoryDataProvider inventory,
            IEnvironmentAwarenessProvider environment,
            ISocialDataProvider social
    ) {
        this.playerState = playerState;
        this.inventory = inventory;
        this.environment = environment;
        this.social = social;
    }

    public IPlayerStateProvider getPlayerState() { return playerState; }
    public IInventoryDataProvider getInventory() { return inventory; }
    public IEnvironmentAwarenessProvider getEnvironment() { return environment; }
    public ISocialDataProvider getSocial() { return social; }
}
