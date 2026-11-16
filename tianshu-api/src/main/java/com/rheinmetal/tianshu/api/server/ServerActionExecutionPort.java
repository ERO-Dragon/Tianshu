package com.rheinmetal.tianshu.api.server;

import java.util.Map;

/**
 * Runs one whitelisted server action for a player, on the server.
 *
 * <p>An action is a server-side capability: only there do the player, the level, the registries and the operator check
 * exist. The tool layer therefore asks this port instead of sending a request over the network — one whitelist, one
 * execution path, and no second copy of the switch state on the client.</p>
 *
 * <p>The adapter is responsible for the gates: whether the action's switch is on, whether the caller may run it, and
 * whether the arguments make sense. A refusal comes back as a {@link ServerActionResult} with its own status, never as
 * an exception, so the tool layer can keep the code it promises the model.</p>
 */
public interface ServerActionExecutionPort {

    /**
     * @param actorId the player the action runs for, as the platform resolved the caller
     * @param action  one of the whitelisted action names
     * @param params  the arguments, already trimmed by the caller
     */
    ServerActionResult execute(String actorId, String action, Map<String, String> params);
}