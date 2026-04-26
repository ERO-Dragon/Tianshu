package com.rheinmetal.tianshu.provider;

public final class WorldStateProvider {

    private final IPlayerStateProvider playerState;
    private final IInventoryDataProvider inventory;
    private final IEnvironmentAwarenessProvider environment;
    private final ITargetScannerProvider targetScanner;
    private final IWorldDataProvider worldData;
    private final IRecipeDataProvider recipe;
    private final IRenderContextProvider renderContext;
    private final ISocialDataProvider social;
    private final IAudioEventProvider audioEvent;

    public WorldStateProvider(
            IPlayerStateProvider playerState,
            IInventoryDataProvider inventory,
            IEnvironmentAwarenessProvider environment,
            ITargetScannerProvider targetScanner,
            IWorldDataProvider worldData,
            IRecipeDataProvider recipe,
            IRenderContextProvider renderContext,
            ISocialDataProvider social,
            IAudioEventProvider audioEvent
    ) {
        this.playerState = playerState;
        this.inventory = inventory;
        this.environment = environment;
        this.targetScanner = targetScanner;
        this.worldData = worldData;
        this.recipe = recipe;
        this.renderContext = renderContext;
        this.social = social;
        this.audioEvent = audioEvent;
    }

    public IPlayerStateProvider getPlayerState() { return playerState; }
    public IInventoryDataProvider getInventory() { return inventory; }
    public IEnvironmentAwarenessProvider getEnvironment() { return environment; }
    public ITargetScannerProvider getTargetScanner() { return targetScanner; }
    public IWorldDataProvider getWorldData() { return worldData; }
    public IRecipeDataProvider getRecipe() { return recipe; }
    public IRenderContextProvider getRenderContext() { return renderContext; }
    public ISocialDataProvider getSocial() { return social; }
    public IAudioEventProvider getAudioEvent() { return audioEvent; }
}
