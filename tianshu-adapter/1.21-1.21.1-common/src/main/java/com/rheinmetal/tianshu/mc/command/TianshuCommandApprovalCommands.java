package com.rheinmetal.tianshu.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.rheinmetal.tianshu.api.game.CommandApprovals;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /tianshu approve <token> | pending}: the human half of {@code command.run}.
 *
 * <p>This is the only way an approval comes into existence, and it needs permission level 2. Separating it from the tool
 * is the point: the party who asks and the party who says yes are not the same party, and that is the only thing that
 * makes "ask first" mean anything.</p>
 */
public final class TianshuCommandApprovalCommands {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private TianshuCommandApprovalCommands() {
    }

    /** Adds the {@code approve} / {@code pending} subcommands under {@code /tianshu}. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandApprovals approvals) {
        dispatcher.register(Commands.literal("tianshu")
                .then(Commands.literal("approve")
                        .requires(source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL))
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(context -> approve(context, approvals))))
                .then(Commands.literal("pending")
                        .requires(source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL))
                        .executes(context -> pending(context, approvals))));
    }

    private static int approve(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                               CommandApprovals approvals) {
        String token = StringArgumentType.getString(context, "token");
        CommandApprovals effective = approvals == null ? CommandApprovals.shared() : approvals;
        if (!effective.approve(token)) {
            context.getSource().sendFailure(Component.literal("no pending command with that token: " + token));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "approved " + token.toUpperCase(java.util.Locale.ROOT) + " — it runs once, on the caller's next attempt"),
                false);
        return 1;
    }

    private static int pending(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                               CommandApprovals approvals) {
        CommandApprovals effective = approvals == null ? CommandApprovals.shared() : approvals;
        var lines = effective.pendingLines();
        if (lines.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("nothing is waiting for approval"), false);
            return 1;
        }
        for (String line : lines) {
            context.getSource().sendSystemMessage(Component.literal(line));
        }
        return lines.size();
    }
}