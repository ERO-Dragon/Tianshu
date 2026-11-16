package com.rheinmetal.tianshu.api.entity;

import java.util.concurrent.CompletableFuture;

/**
 * Using the item in the body's active slot, the way a player right-clicks.
 *
 * <p>Instant uses (a bucket, flint and steel, bone meal, a spawn egg) complete on the spot. Items that start a
 * held use (food, a bow, a crossbow) run a bounded session: the platform ticks {@code onUseTick}, then either
 * finishes the item (food) or releases it (bow). {@code holdTicks} of zero means "the item's own duration, or one
 * second for items with an effectively unlimited one".</p>
 */
public interface EntityItemUsePort {

    CompletableFuture<ActionResult> use(ActorRef actor, ItemUseTarget target, int holdTicks);
}