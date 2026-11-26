package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.CommandExecutionPort;
import com.rheinmetal.tianshu.api.game.CommandRequest;
import com.rheinmetal.tianshu.api.game.CommandResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Asks to run one command as an actor; nothing runs until a human approves that exact line.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides who the command runs as, after the same approval.</p>
 *
 * <p>The first call comes back pending with a token an operator approves with {@code /tianshu approve <token>}; the
 * retry carries the token and runs once. That is the whole design: a switch could be flipped by the caller, an
 * approval cannot.</p>
 */
final class CommandRunOps {

    private CommandRunOps() {
    }

    static CompletableFuture<CommandResult> run(ActorRef actor, String line, String approval) {
        CommandRequest request = new CommandRequest(line, approval);
        return TianshuRuntimePorts.find(CommandExecutionPort.class)
                .map(port -> port.run(actor, request).thenCompose(result -> switch (result.status()) {
                    case EXECUTED, PENDING_APPROVAL -> CompletableFuture.completedFuture(result);
                    case REJECTED -> CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                            result.message().isBlank() ? "the command was not run" : result.message()));
                }))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "running commands is only available on the server")));
    }
}