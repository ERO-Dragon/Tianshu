package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceHudLayoutTest {
    @Test
    void defaultLayoutUsesTheExperienceLevelAnchor() {
        PresenceHudLayout layout = PresenceHudLayout.resolve(PresenceHudSettings.ENABLED, 320, 240);

        assertEquals(160.0F, layout.centerX(), 0.001F);
        assertTrue(layout.defaultAnchor());
        assertEquals(240.0F - 40.0F - PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS / 2.0F, layout.centerY(), 0.001F);
        assertEquals(PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS, layout.sizePixels());
    }

    @Test
    void customCoordinatesAreClampedToKeepTheIconOnScreen() {
        PresenceHudSettings settings = new PresenceHudSettings() {
            @Override
            public double iconPositionX() {
                return 2.0D;
            }

            @Override
            public double iconPositionY() {
                return 1.5D;
            }

            @Override
            public double iconSizePixels() {
                return 61.0D;
            }
        };

        PresenceHudLayout layout = PresenceHudLayout.resolve(settings, 320, 240);

        assertEquals(320.0F - layout.sizePixels() / 2.0F, layout.centerX(), 0.001F);
        assertEquals(240.0F - layout.sizePixels() / 2.0F, layout.centerY(), 0.001F);
        assertEquals(1.0D, layout.normalizedX(), 0.001D);
        assertEquals(1.0D, layout.normalizedY(), 0.001D);
        assertTrue(!layout.defaultAnchor());
        assertEquals(61.0F, layout.sizePixels(), 0.001F);
    }

    @Test
    void iconSizeIsClampedToTheSupportedPixelRange() {
        PresenceHudSettings settings = new PresenceHudSettings() {
            @Override
            public double iconSizePixels() {
                return 1000.0D;
            }
        };

        PresenceHudLayout layout = PresenceHudLayout.resolve(settings, 320, 240);

        assertEquals(PresenceHudSettings.MAX_ICON_SIZE_PIXELS, layout.sizePixels(), 0.001F);
    }
}
