package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.settings.layout.ScrollState;
import com.rheinmetal.tianshu.client.settings.layout.SettingsLayout;
import com.rheinmetal.tianshu.client.settings.layout.SettingsLayoutItem;
import com.rheinmetal.tianshu.client.settings.layout.SettingsViewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettingsLayoutTest {
    @Test
    void scrollOffsetIsClampedWhenContentOrViewportChanges() {
        ScrollState scroll = new ScrollState(80, 200, 100);

        assertEquals(100, scroll.withOffset(500).offset());
        assertEquals(0, scroll.withOffset(-20).offset());
        assertEquals(20, scroll.withMetrics(120, 100).offset());
        assertFalse(scroll.withMetrics(80, 100).canScroll());
    }

    @Test
    void layoutKeepsContentCoordinatesStableWhileTranslatingVisibleRows() {
        SettingsViewport viewport = new SettingsViewport(20, 80, 30);
        SettingsLayout layout = new SettingsLayout(20, viewport);

        SettingsLayoutItem first = layout.nextIntersecting(20);
        layout.gap();
        SettingsLayoutItem second = layout.nextIntersecting(20);

        assertEquals(20, first.contentY());
        assertEquals(-10, first.screenY());
        assertFalse(first.visible());
        assertEquals(44, second.contentY());
        assertEquals(14, second.screenY());
        assertTrue(second.visible());
        assertEquals(44, layout.contentHeight());
    }
}
