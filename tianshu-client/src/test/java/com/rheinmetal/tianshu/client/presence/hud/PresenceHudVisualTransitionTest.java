package com.rheinmetal.tianshu.client.presence.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceHudVisualTransitionTest {
    @Test
    void stateChangesEaseAnimationParametersWithoutPresetCrossfade() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);

        transition.accept(PresenceHudVisualState.IDLE, false, 1_000L, layout);
        transition.accept(PresenceHudVisualState.CHAT, true, 1_100L, layout);

        PresenceHudVisualParameters halfway = transition.sample(1_220L);

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

        PresenceHudVisualParameters sample = transition.sample(1_240L);

        assertEquals(0.34F, sample.intensity(), 0.001F);
    }

    @Test
    void settledFramesReuseTheSameParametersInsteadOfRebuildingThem() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);
        transition.accept(PresenceHudVisualState.TASK, false, 1_000L, layout);

        PresenceHudVisualParameters first = transition.sample(1_000L);
        PresenceHudVisualParameters settled = transition.sample(2_000L);
        transition.accept(PresenceHudVisualState.TASK, false, 2_000L, layout);
        PresenceHudVisualParameters afterAnotherFrame = transition.sample(2_016L);

        assertSame(first, settled);
        assertSame(first, afterAnotherFrame);
    }

    @Test
    void aLayoutChangeMovesTheSettledElementWithoutRestartingTheAnimation() {
        PresenceHudLayout before = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudLayout after = new PresenceHudLayout(160.0F, 80.0F, 32.0F, false, 0.6D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);
        transition.accept(PresenceHudVisualState.TASK, false, 1_000L, before);

        transition.accept(PresenceHudVisualState.TASK, false, 1_100L, after);

        PresenceHudVisualParameters sample = transition.sample(1_100L);
        assertEquals(160.0F, sample.layout().centerX(), 0.001F);
        assertEquals(32.0F, sample.layout().sizePixels(), 0.001F);
        assertEquals(0.78F, sample.intensity(), 0.001F);
    }

    @Test
    void loadingLayerFadesAtRenderSampleTime() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24.0F, false, 0.5D, 0.4D);
        PresenceHudVisualTransition transition = new PresenceHudVisualTransition(240L);

        transition.accept(PresenceHudVisualState.IDLE, false, 1_000L, layout);
        transition.accept(PresenceHudVisualState.LOADING, false, 1_100L, layout);

        PresenceHudVisualParameters halfway = transition.sample(1_220L);

        assertTrue(halfway.loadingBlend() > 0.0F);
        assertTrue(halfway.loadingBlend() < 1.0F);
    }
}
