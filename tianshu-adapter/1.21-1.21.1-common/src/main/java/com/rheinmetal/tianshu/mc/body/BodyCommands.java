package com.rheinmetal.tianshu.mc.body;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.BodyAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * {@code /tianshu body spawn|despawn|list}: the operator switch for agent bodies.
 *
 * <p>Spawning is how a body comes into existence until the agent runtime can ask for one itself. It is
 * permission-gated and prints the body id, so a later tool call can name exactly the body it means.</p>
 */
public final class BodyCommands {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private BodyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tianshu")
                .then(Commands.literal("body")
                        .requires(source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .suggests(BodyCommands::suggestKinds)
                                        .executes(BodyCommands::spawn)))
                        .then(Commands.literal("despawn")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(BodyCommands::despawn)))
                        .then(Commands.literal("list").executes(BodyCommands::list))
                        .then(Commands.literal("access")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests(BodyCommands::suggestAccess)
                                                .executes(BodyCommands::access))))
                        .then(Commands.literal("trust")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(BodyCommands::trust))))
                        .then(Commands.literal("untrust")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(BodyCommands::untrust))))));
    }

    private static CompletableFuture<Suggestions> suggestKinds(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        for (BodyKind kind : BodyKind.values()) {
            builder.suggest(kind.token());
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestAccess(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        for (BodyAccess access : BodyAccess.values()) {
            builder.suggest(access.name().toLowerCase(java.util.Locale.ROOT));
        }
        return builder.buildFuture();
    }

    private static int access(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        String mode = StringArgumentType.getString(context, "mode");
        BodyAccess parsed = null;
        for (BodyAccess candidate : BodyAccess.values()) {
            if (candidate.name().equalsIgnoreCase(mode)) {
                parsed = candidate;
            }
        }
        if (parsed == null) {
            source.sendFailure(Component.literal("unknown access mode: " + mode));
            return 0;
        }
        if (!BodyRuntime.require().setAccess(id, parsed)) {
            source.sendFailure(Component.literal("no live agent body: " + id));
            return 0;
        }
        BodyAccess applied = parsed;
        source.sendSuccess(() -> Component.literal("access of " + id + " is now "
                + applied.name().toLowerCase(java.util.Locale.ROOT)), true);
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> context) {
        return setTrusted(context, true);
    }

    private static int untrust(CommandContext<CommandSourceStack> context) {
        return setTrusted(context, false);
    }

    private static int setTrusted(CommandContext<CommandSourceStack> context, boolean trusted) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        String player = StringArgumentType.getString(context, "player");
        java.util.UUID playerId;
        try {
            playerId = java.util.UUID.fromString(player);
        } catch (IllegalArgumentException notAUuid) {
            source.sendFailure(Component.literal("not a player UUID: " + player));
            return 0;
        }
        boolean changed = trusted
                ? BodyRuntime.require().trust(id, playerId)
                : BodyRuntime.require().untrust(id, playerId);
        if (!changed) {
            source.sendFailure(Component.literal("no live agent body: " + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal((trusted ? "trusted " : "untrusted ") + player
                + (trusted ? " for " : " on ") + id), true);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String token = StringArgumentType.getString(context, "kind");
        BodyKind kind = BodyKind.parse(token).orElse(null);
        if (kind == null) {
            source.sendFailure(Component.literal("unknown body kind: " + token));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        AgentBody body = BodyRuntime.require().spawn(player, kind);
        source.sendSuccess(() -> Component.literal("spawned agent body " + body.id()), true);
        return 1;
    }

    private static int despawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        if (!BodyRuntime.require().despawn(id)) {
            source.sendFailure(Component.literal("no live agent body: " + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("removed agent body " + id), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<AgentBody> bodies = BodyRuntime.require().all();
        if (bodies.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no agent bodies"), false);
            return 0;
        }
        String ids = bodies.stream().map(AgentBody::id).collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("agent bodies: " + ids), false);
        return bodies.size();
    }
}