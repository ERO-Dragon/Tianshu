package com.rheinmetal.tianshu.api.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;

/** The host's action ceiling for every NPC in this server session. */
@FunctionalInterface
public interface ServerBodyAutonomyCeiling {
    BodyAutonomy max();

    static BodyAutonomy current() {
        return TianshuRuntimePorts.find(ServerBodyAutonomyCeiling.class)
                .map(ServerBodyAutonomyCeiling::max).orElse(BodyAutonomy.SURVIVAL);
    }
}
