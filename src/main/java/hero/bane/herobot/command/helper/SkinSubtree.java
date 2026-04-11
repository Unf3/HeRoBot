package hero.bane.herobot.command.helper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import hero.bane.herobot.bot.BotPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class SkinSubtree {

    private SkinSubtree() {}

    //TODO: Allow img files that are the correct dimensions to be a skin file -- maybe through mineskin
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("skin")
                .then(makeSkinPartCommand("cape", BotPlayer.SKIN_CAPE))
                .then(makeSkinPartCommand("jacket", BotPlayer.SKIN_JACKET))
                .then(makeSkinPartCommand("leftSleeve", BotPlayer.SKIN_LEFT_SLEEVE))
                .then(makeSkinPartCommand("rightSleeve", BotPlayer.SKIN_RIGHT_SLEEVE))
                .then(makeSkinPartCommand("leftPant", BotPlayer.SKIN_LEFT_PANT))
                .then(makeSkinPartCommand("rightPant", BotPlayer.SKIN_RIGHT_PANT))
                .then(makeSkinPartCommand("hat", BotPlayer.SKIN_HAT))
                .then(Commands.literal("force")
                        .executes(SkinSubtree::forceLoadSkin)
                        .then(Commands.literal("uuid")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(SkinSubtree::forceLoadSkinUUID)))
                        .then(Commands.literal("name")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(SkinSubtree::forceLoadSkinName))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSkinPartCommand(String name, byte mask) {
        return Commands.literal(name)
                .executes(c -> {
                    for (BotPlayer bot : CommandHelper.requireBotTargets(c)) {
                        bot.toggleSkinPart(mask); // Use this [BotPlayer.toggleSkinPart] to toggle skin parts in other mods y'alls
                        boolean enabled = bot.isSkinPartEnabled(mask);
                        c.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s " + name + " layer " + (enabled ? "on" : "off")), false);
                    }
                    return 1;
                });
    }

    private static void forceLoad(CommandContext<CommandSourceStack> context, Function<BotPlayer, CompletableFuture<Boolean>> loader) throws CommandSyntaxException {
        for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
            String name = bot.getGameProfile().name();
            context.getSource().sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);
            loader.apply(bot).thenAccept(success -> {
                if (success) {
                    context.getSource().sendSuccess(() -> Component.literal("Force-loaded skin for " + name), false);
                } else {
                    context.getSource().sendFailure(Component.literal("Failed to fetch skin for " + name));
                }
            });
        }
    }

    public static int forceLoadSkin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        forceLoad(context, BotPlayer::forceLoadSkin);
        return 1;
    }

    public static int forceLoadSkinUUID(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID uuid = UuidArgument.getUuid(context, "uuid");
        forceLoad(context, bot -> bot.forceLoadSkin(uuid));
        return 1;
    }

    public static int forceLoadSkinName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String skinName = StringArgumentType.getString(context, "name");
        forceLoad(context, bot -> bot.forceLoadSkin(skinName));
        return 1;
    }


}
