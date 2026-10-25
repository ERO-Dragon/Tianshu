package com.rheinmetal.tianshu.api.assembly;

import com.rheinmetal.tianshu.api.game.GameEventFacts;

/**
 * The module that reacts to the player's situation.
 *
 * <p>The platform ticks it and hands it plain readings; whether a reading means "hurt", "dying" or
 * nothing at all is decided behind this interface. No module registered means the platform simply keeps
 * sampling and nobody listens.</p>
 */
public interface GameEventPort {

    /** Drops any baseline so the first reading of a new world reports nothing. */
    void resetSession();

    /** Closes pending aggregation windows while the world is still addressable. */
    void flush();

    /** Advances time-based windows; called every client tick. */
    void tick(long nowMillis);

    /** Feeds one reading; the port decides whether it crosses a threshold. */
    void sample(GameEventFacts facts);
}
