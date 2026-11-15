package com.rheinmetal.tianshu.mc.body;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.rheinmetal.tianshu.api.agent.AgentBody;
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
                        .then(Commands.literal("list").executes(BodyCommands::list))));
    }

    private static CompletableFuture<Suggestions> suggestKinds(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        for (BodyKind kind : BodyKind.values()) {
            builder.suggest(kind.token());
        }
        return builder.buildFuture();
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
        List<AgentBody> bodies = BodyRuntime.require().list();
        if (bodies.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no agent bodies"), false);
            return 0;
        }
        String ids = bodies.stream().map(AgentBody::id).collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("agent bodies: " + ids), false);
        return bodies.size();
    }
}