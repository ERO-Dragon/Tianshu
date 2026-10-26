package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;

import java.util.Optional;

/**
 * The icon element: visible whenever the HUD is on, animated by {@link PresenceHudVisualTransition}.
 *
 * <p>The resolved layout is cached and re-resolved only when the screen size, the icon size or the configured
 * position changes, so a steady frame builds no objects at all: the transition returns the same parameter
 * instance and this controller returns a frame that carries it.</p>
 */
public final class PresenceIconElementController implements PresenceHudElementController {
    public static final String ELEMENT_ID = "presence.icon";
    private static final long TRANSITION_MILLIS = 240L;

    private final PresenceHudSettings settings;
    private final PresenceHudVisualTransition visualTransition = new PresenceHudVisualTransition(TRANSITION_MILLIS);
    private final PresenceHudElementStateTracker elementState = new PresenceHudElementStateTracker();
    private PresenceHudLayout cachedLayout;
    private int cachedScreenWidth = -1;
    private int cachedScreenHeight = -1;
    private double cachedIconSize = -1.0D;
    private double cachedPositionX = Double.NaN;
    private double cachedPositionY = Double.NaN;

    public PresenceIconElementController(PresenceHudSettings settings) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
    }

    @Override
    public Optional<PresenceHudElementFrame> update(PresenceHudElementUpdateContext context) {
        if (context == null) {
            return Optional.empty();
        }
        long nowMillis = context.nowMillis();
        PresenceHudDisplay display = context.display();
        if (!visible(display)) {
            elementState.enter(PresenceHudElementState.HIDDEN, nowMillis);
            visualTransition.reset();
            return Optional.empty();
        }
        elementState.enter(PresenceHudElementState.ACTIVE, nowMillis);
        PresenceHudLayout layout = layoutFor(context);
        visualTransition.accept(display.visualState(), display.listening(), nowMillis, layout);
        return Optional.of(new PresenceHudElementFrame(
                ELEMENT_ID,
                PresenceHudElementType.ICON,
                elementState.state(),
                display,
                new PresenceHudElementTiming(nowMillis, elementState.enteredAtMillis()),
                visualTransition.sample(nowMillis)
        ));
    }

    @Override
    public void reset() {
        elementState.reset();
        visualTransition.reset();
        cachedLayout = null;
    }

    private PresenceHudLayout layoutFor(PresenceHudElementUpdateContext context) {
        int screenWidth = context.screenWidth();
        int screenHeight = context.screenHeight();
        double iconSize = settings.iconSizePixels();
        double positionX = settings.iconPositionX();
        double positionY = settings.iconPositionY();
        if (cachedLayout == null
                || cachedScreenWidth != screenWidth
                || cachedScreenHeight != screenHeight
                || cachedIconSize != iconSize
                || cachedPositionX != positionX
                || cachedPositionY != positionY) {
            cachedLayout = PresenceHudLayout.resolve(settings, screenWidth, screenHeight);
            cachedScreenWidth = screenWidth;
            cachedScreenHeight = screenHeight;
            cachedIconSize = iconSize;
            cachedPositionX = positionX;
            cachedPositionY = positionY;
        }
        return cachedLayout;
    }

    private boolean visible(PresenceHudDisplay display) {
        return display != null && display.visible() && settings.iconEnabled();
    }
}
