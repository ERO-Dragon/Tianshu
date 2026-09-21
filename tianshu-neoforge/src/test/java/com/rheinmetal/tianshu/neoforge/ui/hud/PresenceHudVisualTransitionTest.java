package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceHudVisualTransitionTest {
    @Test
    void stateChangesEaseAnimationParametersWithoutPresetCrossfade() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);

        transition.accept(PresenceHudVisualState.IDLE, false, 1_000L, layout);
        transition.accept(PresenceHudVisualState.CHAT, true, 1_100L, layout);

        PresenceHudVisualParameters halfway = transition.sample(1_220L, layout);

        assertEquals(PresenceHudVisualState.CHAT, halfway.state());
        assertTrue(halfway.intensity() > 0.34F);
        assertTrue(halfway.intensity() < 0.92F);
        assertTrue(halfway.pulse() > 0.0F);
        assertEquals(24.0F, halfway.layout().sizePixels(), 0.001F);
    }

    @Test
    void unchangedStateDoesNotRestartTheTransition() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);

        transition.accept(PresenceHudVisualState.IDLE, false, 1_000L, layout);
        transition.accept(PresenceHudVisualState.IDLE, false, 1_100L, layout);

        PresenceHudVisualParameters sample = transition.sample(1_240L, layout);

        assertEquals(0.34F, sample.intensity(), 0.001F);
    }

    @Test
    void loadingLayerFadesAtRenderSampleTime() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);

        transition.accept(PresenceHudVisualState.IDLE, false, 1_000L, layout);
        transition.accept(PresenceHudVisualState.LOADING, false, 1_100L, layout);

        PresenceHudVisualParameters halfway = transition.sample(1_220L, layout);

        assertTrue(halfway.loadingBlend() > 0.0F);
        assertTrue(halfway.loadingBlend() < 1.0F);
    }
}
