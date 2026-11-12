package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side machine operation behind {@code tianshu:block.machine}.
 *
 * <p>Only machines the platform recognizes are accepted; anything else is refused with a reason rather than handed
 * to a generic "tick this block" call, which would be a cheat surface rather than a capability.</p>
 */
public interface BlockMachinePort {

    CompletableFuture<MachineOpResult> operate(String playerId, MachineOpRequest request);
}
