package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PresenceHudVisualParametersTest {
    @Test
    void transitionInterpolatesAnimationParametersWithoutChangingTheLayout() {
        PresenceHudLayout layout = new PresenceHudLayout(100.0F, 80.0F, 24, false, 0.5D, 0.4D);
        PresenceHudVisualParameters from = new PresenceHudVisualParameters(
                PresenceHudVisualState.IDLE, false, 1.0F, 0.2F, 0.25F, 0.0F, layout
        );
        PresenceHudVisualParameters to = new PresenceHudVisualParameters(
                PresenceHudVisualState.CHAT_THINKING, true, 0.0F, 0.8F, 0.45F, 0.8F, layout
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
                PresenceHudVisualState.IDLE, false, layout
        );
        PresenceHudVisualParameters loading = PresenceHudVisualParameters.forState(
                PresenceHudVisualState.LOADING, false, layout
        );

        PresenceHudVisualParameters halfway = PresenceHudVisualParameters.interpolate(idle, loading, 0.5F);

        assertEquals(0.0F, idle.loadingBlend(), 0.001F);
        assertEquals(1.0F, loading.loadingBlend(), 0.001F);
        assertEquals(0.5F, halfway.loadingBlend(), 0.001F);
    }
}
