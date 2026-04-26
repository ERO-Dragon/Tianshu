package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.SoundEventData;

import java.util.List;

public interface IAudioEventProvider {

    List<SoundEventData> pollRecentSoundEvents();
}
