package com.rheinmetal.tianshu.snapshot;

public final class GameSettingsData {

    public final float gamma;
    public final float masterVolume;
    public final int renderDistance;
    public final String language;

    public GameSettingsData(float gamma, float masterVolume, int renderDistance, String language) {
        this.gamma = gamma;
        this.masterVolume = masterVolume;
        this.renderDistance = renderDistance;
        this.language = language;
    }

    public float getGamma() { return gamma; }
    public float getMasterVolume() { return masterVolume; }
    public int getRenderDistance() { return renderDistance; }
    public String getLanguage() { return language; }
}
