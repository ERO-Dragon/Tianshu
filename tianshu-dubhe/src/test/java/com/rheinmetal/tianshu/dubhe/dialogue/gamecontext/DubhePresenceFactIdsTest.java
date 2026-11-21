package com.rheinmetal.tianshu.dubhe.dialogue.gamecontext;

import com.rheinmetal.tianshu.presence.protocol.PresenceContextFactIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a turn asks the presence module for.
 *
 * <p>This matters because of where the two input paths differ: a spoken turn arrives with the world as it was
 * at the moment of speaking, while a typed turn has no delivery to carry that. The typed path therefore depends
 * on this request — if it stopped asking for interaction context, a typed turn would silently lose held item,
 * crosshair target and sneaking, which is exactly the information a question like "what am I looking at"
 * needs.</p>
 */
class DubhePresenceFactIdsTest {

    @Test
    void aTurnAsksForTheInteractionContextSoTypedInputSeesTheSameWorld() {
        assertTrue(DubhePresenceFactIds.DEFAULT_DYNAMIC_FACTS.contains(PresenceContextFactIds.INTERACTION_CONTEXT),
                "held item, crosshair target, interaction keys and sneaking travel in this fact");
    }

    @Test
    void aTurnAsksForTheFactsThePromptCanActuallyRender() {
        assertTrue(DubhePresenceFactIds.DEFAULT_DYNAMIC_FACTS.contains(PresenceContextFactIds.PLAYER_STATUS),
                "status carries the dimension and vitals");
        assertTrue(DubhePresenceFactIds.DEFAULT_DYNAMIC_FACTS.contains(PresenceContextFactIds.WORLD_ENVIRONMENT),
                "environment carries biome, weather and time");
    }
}
