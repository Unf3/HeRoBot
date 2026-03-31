package hero.bane.herobot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import hero.bane.herobot.HeroBotSettings;
import hero.bane.herobot.bot.BotPlayer;
import hero.bane.herobot.bot.BotPlayerActionPack;
import hero.bane.herobot.bot.BotPlayerActionPack.Action;
import hero.bane.herobot.bot.BotPlayerActionPack.ActionType;
import hero.bane.herobot.bot.connection.ServerPlayerInterface;
import hero.bane.herobot.command.helper.*;
import hero.bane.herobot.mixin.ServerCommonPacketListenerImplAccessor;
import hero.bane.herobot.util.ItemCooldown;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.HumanoidArm;

import java.util.List;
import java.util.Objects;

public class PlayerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(
                Commands.literal("player")
                        .requires(s -> !s.isPlayer() || s.getServer().getPlayerList().isOp(Objects.requireNonNull(s.getPlayer()).nameAndId()))
                        .then(Commands.argument("targets", EntityArgument.players())

                                .then(Commands.literal("stop")
                                        .executes(CommandHelper.manipulation(BotPlayerActionPack::stopAll)))

                                .then(makeActionCommand("use", ActionType.USE))
                                .then(makeActionCommand("swing", ActionType.SWING))
                                .then(makeActionCommand("jump", ActionType.JUMP))
                                .then(makeActionCommand("attack", ActionType.ATTACK))
                                .then(makeActionCommand("drop", ActionType.DROP_ITEM))
                                .then(makeActionCommand("dropStack", ActionType.DROP_STACK))
                                .then(makeActionCommand("swapHands", ActionType.SWAP_HANDS))

                                .then(Commands.literal("itemCd")
                                        .executes(ItemCooldown::itemCdClearAll)
                                        .then(Commands.argument("item", ItemArgument.item(ctx))
                                                .executes(ItemCooldown::itemCdAsk)
                                                .then(Commands.literal("reset")
                                                        .executes(ItemCooldown::itemCdReset))
                                                .then(Commands.literal("set")
                                                        .executes(ItemCooldown::itemCdSetDefault)
                                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                                                .executes(ItemCooldown::itemCdSetCustom)))))

                                .then(Commands.literal("hotbar")
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 9))
                                                .executes(c -> CommandHelper.manipulate(c,
                                                        ap -> ap.setSlot(IntegerArgumentType.getInteger(c, "slot"))))))

                                .then(Commands.literal("kill")
                                        .executes(PlayerCommand::kill))
                                .then(Commands.literal("disconnect")
                                        .executes(PlayerCommand::disconnect))

                                .then(Commands.literal("sneak")
                                        .executes(CommandHelper.manipulation(ap -> ap.setSneaking(true))))
                                .then(Commands.literal("unsneak")
                                        .executes(CommandHelper.manipulation(ap -> ap.setSneaking(false))))
                                .then(Commands.literal("sprint")
                                        .executes(CommandHelper.manipulation(ap -> ap.setSprinting(true))))
                                .then(Commands.literal("unsprint")
                                        .executes(CommandHelper.manipulation(ap -> ap.setSprinting(false))))

                                .then(Commands.literal("move")
                                        .executes(CommandHelper.manipulationAndStopPath(BotPlayerActionPack::stopMovement))
                                        .then(Commands.literal("forward")
                                                .executes(CommandHelper.manipulationAndStopPath(ap -> ap.setForward(1))))
                                        .then(Commands.literal("backward")
                                                .executes(CommandHelper.manipulationAndStopPath(ap -> ap.setForward(-1))))
                                        .then(Commands.literal("left")
                                                .executes(CommandHelper.manipulationAndStopPath(ap -> ap.setStrafing(1))))
                                        .then(Commands.literal("right")
                                                .executes(CommandHelper.manipulationAndStopPath(ap -> ap.setStrafing(-1)))))

                                .then(LookSubtree.build())

                                .then(Commands.literal("ping")
                                        .executes(PlayerCommand::pingGet)
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                                .suggests((c, b) -> {
                                                    b.suggest(0);
                                                    b.suggest(25);
                                                    b.suggest(50);
                                                    b.suggest(100);
                                                    b.suggest(150);
                                                    b.suggest(200);
                                                    return b.buildFuture();
                                                })
                                                .executes(PlayerCommand::pingSet)))

                                .then(Commands.literal("copycat")
                                        .then(Commands.argument("source", EntityArgument.player())
                                                .executes(context -> {
                                                    for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
                                                        bot.copycat(EntityArgument.getPlayer(context, "source"));
                                                    }
                                                    return 1;
                                                })))

                                .then(SkinSubtree.build())

                                .then(Commands.literal("autojump")
                                        .executes(CommandHelper.manipulation(BotPlayerActionPack::attemptAutoJump))
                                        .then(Commands.literal("true")
                                                .executes(c -> autoJump(c, true)))
                                        .then(Commands.literal("false")
                                                .executes(c -> autoJump(c, false))))

                                // There's probably a better name for this but idk, like dexterity maybe? Dexterousness?? whatever
                                .then(Commands.literal("handedness")
                                        .then(Commands.literal("left")
                                                .executes(c -> setHandedness(c, true)))
                                        .then(Commands.literal("right")
                                                .executes(c -> setHandedness(c, false))))

                                .then(InventorySubtree.buildInventory(ctx))
                                .then(InventorySubtree.buildContainer(ctx))

                                .then(PathSubtree.build(ctx))
                        )
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeActionCommand(String name, ActionType type) {
        return Commands.literal(name)
                .executes(CommandHelper.manipulation(ap -> ap.stop(type)))
                .then(Commands.literal("once")
                        .executes(CommandHelper.manipulation(ap -> ap.start(type, Action.once()))))
                .then(Commands.literal("continuous")
                        .executes(CommandHelper.manipulation(ap -> ap.start(type, Action.continuous())))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> {
                                            if (IntegerArgumentType.getInteger(c, "ticks") == 1) {
                                                return CommandHelper.manipulate(c, ap -> ap.start(type, Action.once()));
                                            } else {
                                                return CommandHelper.manipulate(c,
                                                        ap -> ap.start(type,
                                                                Action.continuous(IntegerArgumentType.getInteger(c, "ticks"))));
                                            }
                                        }
                                )))
                .then(Commands.literal("interval")
                        .then(Commands.argument("interval", IntegerArgumentType.integer(1))
                                .executes(c -> CommandHelper.manipulate(c,
                                        ap -> ap.start(type,
                                                Action.interval(IntegerArgumentType.getInteger(c, "interval")))))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(c -> CommandHelper.manipulate(c,
                                                ap -> ap.start(type,
                                                        Action.interval(
                                                                IntegerArgumentType.getInteger(c, "interval"),
                                                                IntegerArgumentType.getInteger(c, "ticks"))))))));
    }

    private static int kill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : CommandHelper.requireBotTargets(context))
            bot.kill(bot.level());
        return 1;
    }

    private static int disconnect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
            bot.ping = 0;
            bot.botPlayerDisconnect(Component.literal(""));
        }
        return 1;
    }

    private static int pingSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int value = IntegerArgumentType.getInteger(context, "value");
        for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
            bot.ping = value;
            ((ServerCommonPacketListenerImplAccessor) bot.connection).setLatency(value);
            context.getSource().getServer().getPlayerList().broadcastAll(
                    new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, bot));
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s ping to " + value + "ms"), false);
        }
        return 1;
    }

    private static int pingGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> botPlayerList = CommandHelper.requireBotTargets(context);
        if (!botPlayerList.isEmpty()) {
            int botPing = botPlayerList.getFirst().ping;
            int pingToTicks = HeroBotSettings.botPingToTicks;
            context.getSource().sendSuccess(() -> Component.literal("Bot Ping: " + botPing +
                    "ms\nDelay in Ticks: " + botPing / pingToTicks +
                    (botPing % pingToTicks > 0 ? "\n with a " + botPing % pingToTicks + "/" + pingToTicks + " chance to add a tick" : "")
            ), false);
            return botPing;
        } else {
            return 0;
        }
    }

    private static int autoJump(CommandContext<CommandSourceStack> context, boolean value)
            throws CommandSyntaxException {
        for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
            BotPlayerActionPack ap = ((ServerPlayerInterface) bot).getActionPack();
            ap.autoJump = value;
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s auto jump " + (value ? "on" : "off")), false);
        }
        return 1;
    }

    private static int setHandedness(CommandContext<CommandSourceStack> context, boolean leftHanded)
            throws CommandSyntaxException {
        HumanoidArm arm = leftHanded ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
            bot.setMainHand(arm);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + (leftHanded ? " Left-Handed" : " Right-Handed")), false);
        }
        return 1;
    }

}
