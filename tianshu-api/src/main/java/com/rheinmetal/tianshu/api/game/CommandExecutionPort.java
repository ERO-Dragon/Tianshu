package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Runs one command as the actor, after a human approved it.
 *
 * <p>This is the sharpest capability the platform has, so the shape is deliberately narrow: the target is the actor,
 * the permission level is the server's setting, and nothing runs on the first call — a request comes back pending, an
 * operator approves the token it returned, and only then does the retry execute exactly once.</p>
 *
 * <p>Why a token instead of a switch: a switch is something the caller can flip. The point of "ask first" is that the
 * party who can say yes is not the party who asked.</p>
 */
public interface CommandExecutionPort {

    CompletableFuture<CommandResult> run(ActorRef actor, CommandRequest request);
}