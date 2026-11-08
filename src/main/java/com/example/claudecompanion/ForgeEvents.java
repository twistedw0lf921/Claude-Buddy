package com.example.claudecompanion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

public class ForgeEvents {

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("claude")
                .requires(src -> !Config.OP_ONLY.get() || src.hasPermission(2))
                .then(Commands.literal("summon").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (Config.anthropicKey().isEmpty()) {
                        ctx.getSource().sendFailure(Component.literal(
                                "No Anthropic API key set. Edit config/claudecompanion-common.toml (or set ANTHROPIC_API_KEY)."));
                        return 0;
                    }
                    ClaudeManager.summon(p, true);
                    return 1;
                }))
                .then(Commands.literal("dismiss").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ClaudeManager.dismiss(p);
                    return 1;
                }))
                .then(Commands.literal("ask")
                        .then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ClaudeManager.onText(p, StringArgumentType.getString(ctx, "message"));
                            return 1;
                        }))));
    }

    /** Typing "claude, ..." or "@claude ..." in chat also talks to Claude. */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        String raw = event.getRawText().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("claude") || lower.startsWith("@claude")) {
            ClaudeManager.onText(event.getPlayer(), raw);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ClaudeManager.reset();
    }
}
