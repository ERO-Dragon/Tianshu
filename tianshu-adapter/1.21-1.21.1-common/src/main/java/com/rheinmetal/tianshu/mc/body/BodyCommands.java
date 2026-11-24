package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityAccess;
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
                                        .executes(BodyCommands::spawn)
                                        // The optional flag gives the body a player identity: the server then treats
                                        // it as a player, so claims and player rules apply to what it does.
                                        .then(Commands.literal("player")
                                                .executes(BodyCommands::spawnPlayerBacked))))
                        .then(Commands.literal("despawn")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(BodyCommands::suggestBodies)
                                        .executes(BodyCommands::despawn)))
                        .then(Commands.literal("list").executes(BodyCommands::list))
                        .then(Commands.literal("access")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(BodyCommands::suggestBodies)
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests(BodyCommands::suggestAccess)
                                                .executes(BodyCommands::access))))
                        .then(Commands.literal("trust")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(BodyCommands::suggestBodies)
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(BodyCommands::trust))))
                        .then(Commands.literal("untrust")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(BodyCommands::suggestBodies)
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(BodyCommands::untrust))))
                        .then(Commands.literal("give")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(BodyCommands::suggestBodies)
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .then(Commands.argument("count",
                                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                                        .integer(1, 2304))
                                                        .executes(BodyCommands::give)))))));
    }

    private static CompletableFuture<Suggestions> suggestKinds(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        for (BodyKind kind : BodyKind.values()) {
            if (BodyCarriers.available(kind)) {
                builder.suggest(kind.token());
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestAccess(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        for (EntityAccess access : EntityAccess.values()) {
            builder.suggest(access.name().toLowerCase(java.util.Locale.ROOT));
        }
        return builder.buildFuture();
    }

    private static int access(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = resolveId(source, StringArgumentType.getString(context, "id"));
        if (id == null) {
            return 0;
        }
        String mode = StringArgumentType.getString(context, "mode");
        EntityAccess parsed = null;
        for (EntityAccess candidate : EntityAccess.values()) {
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
        EntityAccess applied = parsed;
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
        String id = resolveId(source, StringArgumentType.getString(context, "id"));
        if (id == null) {
            return 0;
        }
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

    private static int give(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = resolveId(source, StringArgumentType.getString(context, "id"));
        if (id == null) {
            return 0;
        }
        String itemToken = StringArgumentType.getString(context, "item");
        net.minecraft.resources.ResourceLocation itemId = net.minecraft.resources.ResourceLocation.tryParse(itemToken);
        net.minecraft.world.item.Item item = itemId == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            source.sendFailure(Component.literal("unknown item: " + itemToken));
            return 0;
        }
        if (BodyRuntime.require().state(id).isEmpty()) {
            source.sendFailure(Component.literal("no live agent body: " + id));
            return 0;
        }
        int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count");
        int added = BodyRuntime.require().give(id, new net.minecraft.world.item.ItemStack(item, count));
        if (added <= 0) {
            source.sendFailure(Component.literal("no room in " + id));
            return 0;
        }
        int given = added;
        source.sendSuccess(() -> Component.literal("gave " + given + " " + itemToken + " to " + id), true);
        return added;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return spawn(context, false);
    }

    private static int spawnPlayerBacked(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return spawn(context, true);
    }

    private static int spawn(CommandContext<CommandSourceStack> context, boolean playerIdentity)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String token = StringArgumentType.getString(context, "kind");
        BodyKind kind = BodyKind.parse(token).orElse(null);
        if (kind == null || !BodyCarriers.available(kind)) {
            source.sendFailure(spawnFailure(BodyFailure.KIND_INVALID));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        EntityActor body;
        try {
            body = BodyRuntime.require().spawn(player, kind, playerIdentity);
        } catch (ToolException failure) {
            source.sendFailure(spawnFailure(BodyFailure.fromId(failure.getMessage()).orElse(BodyFailure.SPAWN_FAILED)));
            return 0;
        }
        String identity = playerIdentity ? " (player identity)" : "";
        source.sendSuccess(() -> Component.literal("spawned agent body " + describe(body) + identity), true);
        return 1;
    }

    private static Component spawnFailure(BodyFailure reason) {
        // Vanilla clients on Ferry-only servers translate the nested vanilla component locally.
        return Component.translatableWithFallback(reason.translationKey(), "%s",
                Component.translatable("commands.summon.failed"));
    }

    private static int despawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String token = StringArgumentType.getString(context, "id");
        if ("all".equalsIgnoreCase(token.trim())) {
            List<EntityActor> all = BodyRuntime.require().all();
            int removed = 0;
            for (EntityActor body : all) {
                if (BodyRuntime.require().despawn(body.id())) {
                    removed++;
                }
            }
            int count = removed;
            source.sendSuccess(() -> Component.literal("removed " + count + " agent bod(y/ies)"), true);
            return removed;
        }
        String id = resolveId(source, token);
        if (id == null) {
            return 0;
        }
        if (!BodyRuntime.require().despawn(id)) {
            source.sendFailure(Component.literal("no live agent body: " + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("removed agent body " + id), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<EntityActor> bodies = BodyRuntime.require().all();
        if (bodies.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no agent bodies"), false);
            return 0;
        }
        // Every body reports where it is and which identity it acts under: "spawned" without a position left the
        // operator guessing whether the body even exists.
        for (EntityActor body : bodies) {
            String line = describe(body);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return bodies.size();
    }

    private static String describe(EntityActor body) {
        StringBuilder text = new StringBuilder(body.id());
        text.append("  identity=").append(body.playerLike() ? "player" : "ordinary");
        text.append("  at=").append(where(body));
        return text.toString();
    }

    private static String where(EntityActor body) {
        try {
            var position = body.position();
            return String.format(java.util.Locale.ROOT, "%s %.1f %.1f %.1f",
                    position.dimension(), position.x(), position.y(), position.z());
        } catch (RuntimeException gone) {
            return "(not in a level)";
        }
    }
    /**
     * Resolves what the operator typed into a live body id.
     *
     * <p>The canonical id is a UUID, which nobody should have to retype: the index printed by
     * {@code /tianshu body list}, any unique prefix of the id, or the full id all work. An ambiguous prefix is refused
     * with the candidates rather than picking one.</p>
     */
    private static String resolveId(CommandSourceStack source, String token) {
        List<EntityActor> bodies = BodyRuntime.require().all();
        if (token == null || token.isBlank()) {
            source.sendFailure(Component.literal("no body reference given"));
            return null;
        }
        String trimmed = token.trim();
        String indexText = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        if (indexText.chars().allMatch(Character::isDigit) && !indexText.isEmpty()) {
            int index = Integer.parseInt(indexText);
            if (index >= 1 && index <= bodies.size()) {
                return bodies.get(index - 1).id();
            }
            source.sendFailure(Component.literal("no body #" + index + "; there are " + bodies.size()));
            return null;
        }
        String needle = indexText.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        List<String> matches = bodies.stream()
                .map(EntityActor::id)
                .filter(id -> id.replace("-", "").toLowerCase(java.util.Locale.ROOT).startsWith(needle))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("no live agent body matches " + token));
            return null;
        }
        source.sendFailure(Component.literal("ambiguous, matches: " + String.join(", ", matches)));
        return null;
    }

    private static CompletableFuture<Suggestions> suggestBodies(CommandContext<CommandSourceStack> context,
                                                                SuggestionsBuilder builder) {
        List<EntityActor> bodies = BodyRuntime.require().all();
        int index = 1;
        for (EntityActor body : bodies) {
            builder.suggest("#" + index, Component.literal(body.id()));
            index++;
        }
        return builder.buildFuture();
    }
}