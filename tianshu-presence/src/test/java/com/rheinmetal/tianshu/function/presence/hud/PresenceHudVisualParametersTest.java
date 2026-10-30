package com.rheinmetal.tianshu.function.presence.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceHudVisualParametersTest {
    @Test
    void transitionInterpolatesAnimationParametersWithoutChangingTheLayout() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24, false, 0.5D, 0.4D);
        PresenceHudVisualParameters from = new PresenceHudVisualParameters(
                PresenceHudVisualState.IDLE, 1.0F, 0.2F, 0.25F, 0.0F, layout
        );
        PresenceHudVisualParameters to = new PresenceHudVisualParameters(
                PresenceHudVisualState.CHAT_THINKING, 0.0F, 0.8F, 0.45F, 0.8F, layout
        );

        PresenceHudVisualParameters halfway = PresenceHudVisualParameters.interpolate(from, to, 0.5F);

        assertEquals(PresenceHudVisualState.CHAT_THINKING, halfway.state());
        assertEquals(0.5F, halfway.intensity(), 0.001F);
        assertEquals(0.5F, halfway.speed(), 0.001F);
        assertEquals(0.35F, halfway.convergence(), 0.001F);
        assertEquals(24, halfway.layout().sizePixels());
    }

    @Test
    void loadingAppearanceIsAContinuousTransitionParameter() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24, false, 0.5D, 0.4D);
        PresenceHudVisualParameters idle = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.IDLE, layout
        );
        PresenceHudVisualParameters loading = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.LOADING, layout
        );

        PresenceHudVisualParameters halfway = PresenceHudVisualParameters.interpolate(idle, loading, 0.5F);

        assertEquals(0.0F, idle.loadingBlend(), 0.001F);
        assertEquals(1.0F, loading.loadingBlend(), 0.001F);
        assertEquals(0.5F, halfway.loadingBlend(), 0.001F);
    }

    @Test
    void listeningGrowsTheShellAndAddsTheRipple() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24, false, 0.5D, 0.4D);

        PresenceHudVisualParameters idle = PresenceHudVisualParameters.forState(PresenceHudVisualState.IDLE, layout);
        PresenceHudVisualParameters listening = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.LISTENING, layout);

        assertTrue(listening.convergence() < idle.convergence(), "listening must be the larger sphere");
        assertTrue(listening.intensity() > idle.intensity(), "listening aurora is slightly stronger than idle");
        assertEquals(1.0F, listening.pulse(), 0.001F);
        assertEquals(0.0F, idle.pulse(), 0.001F);
    }

    @Test
    void thinkingAndRespondingShareTheShellWhileRespondingMatchesTheListeningAurora() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24, false, 0.5D, 0.4D);

        PresenceHudVisualParameters listening = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.LISTENING, layout);
        PresenceHudVisualParameters thinking = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.CHAT_THINKING, layout);
        PresenceHudVisualParameters responding = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.CHAT, layout);

        assertEquals(thinking.convergence(), responding.convergence(), 0.001F);
        assertEquals(listening.intensity(), responding.intensity(), 0.001F);
        assertTrue(thinking.intensity() > listening.intensity(), "thinking keeps the reference aurora");
        assertTrue(listening.convergence() < thinking.convergence(), "listening is larger than thinking");
        assertEquals(0.0F, responding.pulse(), 0.001F);
    }
}
