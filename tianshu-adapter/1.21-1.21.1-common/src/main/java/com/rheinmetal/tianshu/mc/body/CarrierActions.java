package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * The handlers the built-in carriers offer. This is the same contract a third-party mod implements, which is what
 * keeps the built-in carriers from getting a private path through the platform.
 */
public final class CarrierActions implements AgentBodyActions {

    private final AgentMotionPort motion;

    public CarrierActions(MinecraftServer server, BodyRegistry bodies) {
        this.motion = new AgentMotionSource(server, bodies);
    }

    @Override
    public Optional<AgentMotionPort> motion() {
        return Optional.of(motion);
    }
}