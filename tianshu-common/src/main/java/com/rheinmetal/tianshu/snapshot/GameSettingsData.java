package com.rheinmetal.tianshu.snapshot;

public final class GameSettingsData {

    public final float gamma;
    public final float masterVolume;
    public final int renderDistance;
    public final String language;
    public final float fov;
    public final String difficulty;
    public final float musicVolume;
    public final float soundVolume;

    public GameSettingsData(
            float gamma,
            float masterVolume,
            int renderDistance,
            String language,
            float fov,
            String difficulty,
            float musicVolume,
            float soundVolume
    ) {
        this.gamma = gamma;
        this.masterVolume = masterVolume;
        this.renderDistance = renderDistance;
        this.language = language;
        this.fov = fov;
        this.difficulty = difficulty;
        this.musicVolume = musicVolume;
        this.soundVolume = soundVolume;
    }

    public float getGamma() { return gamma; }
    public float getMasterVolume() { return masterVolume; }
    public int getRenderDistance() { return renderDistance; }
    public String getLanguage() { return language; }
    public float getFov() { return fov; }
    public String getDifficulty() { return difficulty; }
    public float getMusicVolume() { return musicVolume; }
    public float getSoundVolume() { return soundVolume; }
}
