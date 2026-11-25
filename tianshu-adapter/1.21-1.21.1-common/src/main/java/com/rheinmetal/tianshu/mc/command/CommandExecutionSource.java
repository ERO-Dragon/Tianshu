package com.rheinmetal.tianshu.mc.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.CommandApprovals;
import com.rheinmetal.tianshu.api.game.CommandExecutionPort;
import com.rheinmetal.tianshu.api.game.CommandRequest;
import com.rheinmetal.tianshu.api.game.CommandResult;
import com.rheinmetal.tianshu.mc.tool.ToolActor;
import com.rheinmetal.tianshu.mc.tool.ToolActors;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/**
 * Runs one command as the actor, and only after a human approved that exact line.
 *
 * <p>Two things this deliberately does not do. It does not try to work out whether the command's <em>arguments</em> name
 * someone else: that needs the semantics of every command in every mod, and getting it wrong would turn "only on
 * yourself" into a false sense of safety. Instead the approval names the exact line, and the human reading it is the
 * decision. And it does not raise the permission level on its own: the server's setting decides what the actor may do,
 * and the approval only says "this line, this once".</p>
 *
 * <p>The source entity is the actor, so commands that act on their source (gamemode, effect clear, spawnpoint) act on the
 * actor without being told to.</p>
 */
public final class CommandExecutionSource implements CommandExecutionPort {

    private final MinecraftServer server;
    private final CommandApprovals approvals;
    private final IntSupplier permissionLevel;

    public CommandExecutionSource(MinecraftServer server, CommandApprovals approvals, IntSupplier permissionLevel) {
        this.server = server;
        this.approvals = approvals == null ? CommandApprovals.shared() : approvals;
        this.permissionLevel = permissionLevel == null ? () -> 0 : permissionLevel;
    }

    @Override
    public CompletableFuture<CommandResult> run(ActorRef actor, CommandRequest request) {
        // Commands must run on the server thread, but hopping there when we are already there costs a deadlock: a caller
        // that blocks on the result from the same thread waits for a task queued behind itself.
        if (server.isSameThread()) {
            return CompletableFuture.completedFuture(runNow(actor, request));
        }
        CompletableFuture<CommandResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(runNow(actor, request)));
        return result;
    }

    private CommandResult runNow(ActorRef actor, CommandRequest request) {
        if (request == null || request.line().isBlank()) {
            return CommandResult.rejected("a command.run call needs a command line");
        }
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return CommandResult.rejected(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        Entity entity = holder.living();
        if (entity == null) {
            return CommandResult.rejected("that actor has no entity in the world to run a command as");
        }
        String actorKey = actor.kind() + ":" + actor.id();
        String approvedLine;
        if (!request.hasApproval()) {
            String token = approvals.request(actorKey, request.line());
            return CommandResult.pending(token, "this command needs an operator's approval: /tianshu approve " + token);
        }
        var consumed = approvals.consume(request.approval(), actorKey, request.line());
        if (consumed.isEmpty()) {
            return CommandResult.rejected("that approval is unknown, expired, already used, or was given for another "
                    + "command");
        }
        approvedLine = consumed.get();
        return execute(entity, approvedLine);
    }

    private CommandResult execute(Entity entity, String line) {
        RecordingSource recording = new RecordingSource();
        ServerLevel level = entity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        if (level == null) {
            return CommandResult.rejected("that actor is not in a server level");
        }
        CommandSourceStack stack = new CommandSourceStack(
                recording,
                Vec3.atCenterOf(entity.blockPosition()),
                Vec2.ZERO,
                level,
                Math.max(0, permissionLevel.getAsInt()),
                entity.getName().getString(),
                entity.getDisplayName(),
                server,
                entity);
        try {
            int outcome = server.getCommands().getDispatcher().execute(line, stack);
            String output = recording.text();
            return CommandResult.executed(output.isBlank() ? Integer.toString(outcome) : output);
        } catch (CommandSyntaxException malformed) {
            return CommandResult.rejected("the command was refused: " + malformed.getMessage() + levelHint());
        } catch (RuntimeException failure) {
            return CommandResult.rejected("the command failed: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage())
                    + levelHint());
        }
    }

    /**
     * Brigadier reports a command whose requirement fails as unknown, so a low level looks like a typo.
     *
     * <p>Saying which number to change is the difference between "the platform is broken" and "raise the setting".</p>
     */
    private String levelHint() {
        int level = permissionLevel.getAsInt();
        return level >= 4 ? "" : " (commands run at permission level " + level
                + "; raise command_permission_level if this one needs more)";
    }

    /** Collects what the command says, so the caller can read the result instead of guessing it. */
    private static final class RecordingSource implements CommandSource {

        private final StringBuilder text = new StringBuilder();

        @Override
        public void sendSystemMessage(Component message) {
            if (message == null) {
                return;
            }
            String line = message.getString();
            if (!line.isBlank()) {
                text.append(line).append('\n');
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private String text() {
            return text.toString().trim();
        }
    }
}