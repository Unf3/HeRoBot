package hero.bane.herobot.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import hero.bane.herobot.HeroBotSettings;
import hero.bane.herobot.bot.BotPlayer;
import hero.bane.herobot.bot.BotPlayerActionPack;
import hero.bane.herobot.bot.BotPlayerActionPack.Action;
import hero.bane.herobot.bot.BotPlayerActionPack.ActionType;
import hero.bane.herobot.bot.connection.ServerPlayerInterface;
import hero.bane.herobot.util.ItemCooldown;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class PlayerCommand {
    private static final SimpleCommandExceptionType NOT_BOT =
            new SimpleCommandExceptionType(Component.literal("Only bot players can be targeted"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(
                literal("player")
                        .requires(s -> !s.isPlayer() || s.getServer().getPlayerList().isOp(Objects.requireNonNull(s.getPlayer()).nameAndId()))
                        .then(argument("targets", EntityArgument.players())

                                .then(literal("stop")
                                        .executes(manipulation(BotPlayerActionPack::stopAll)))

                                .then(makeActionCommand("use", ActionType.USE))
                                .then(makeActionCommand("swing", ActionType.SWING))
                                .then(makeActionCommand("jump", ActionType.JUMP))
                                .then(makeActionCommand("attack", ActionType.ATTACK))
                                .then(makeActionCommand("drop", ActionType.DROP_ITEM))
                                .then(makeActionCommand("dropStack", ActionType.DROP_STACK))
                                .then(makeActionCommand("swapHands", ActionType.SWAP_HANDS))

                                .then(literal("itemCd")
                                        .executes(ItemCooldown::itemCdClearAll)
                                        .then(argument("item", ItemArgument.item(ctx))
                                                .executes(ItemCooldown::itemCdAsk)
                                                .then(literal("reset")
                                                        .executes(ItemCooldown::itemCdReset))
                                                .then(literal("set")
                                                        .executes(ItemCooldown::itemCdSetDefault)
                                                        .then(argument("ticks", IntegerArgumentType.integer(0))
                                                                .executes(ItemCooldown::itemCdSetCustom)))))

                                .then(literal("hotbar")
                                        .then(argument("slot", IntegerArgumentType.integer(1, 9))
                                                .executes(c -> manipulate(c,
                                                        ap -> ap.setSlot(IntegerArgumentType.getInteger(c, "slot"))))))

                                .then(literal("kill")
                                        .executes(PlayerCommand::kill))
                                .then(literal("disconnect")
                                        .executes(PlayerCommand::disconnect))

                                .then(literal("sneak")
                                        .executes(manipulation(ap -> ap.setSneaking(true))))
                                .then(literal("unsneak")
                                        .executes(manipulation(ap -> ap.setSneaking(false))))
                                .then(literal("sprint")
                                        .executes(manipulation(ap -> ap.setSprinting(true))))
                                .then(literal("unsprint")
                                        .executes(manipulation(ap -> ap.setSprinting(false))))

                                .then(literal("move")
                                        .executes(manipulation(BotPlayerActionPack::stopMovement))
                                        .then(literal("forward")
                                                .executes(manipulation(ap -> ap.setForward(1))))
                                        .then(literal("backward")
                                                .executes(manipulation(ap -> ap.setForward(-1))))
                                        .then(literal("left")
                                                .executes(manipulation(ap -> ap.setStrafing(1))))
                                        .then(literal("right")
                                                .executes(manipulation(ap -> ap.setStrafing(-1)))))

                                .then(literal("look")
                                        // Cardinals
                                        .then(makeLookDirectionCommand("north", Direction.NORTH))
                                        .then(makeLookDirectionCommand("south", Direction.SOUTH))
                                        .then(makeLookDirectionCommand("east", Direction.EAST))
                                        .then(makeLookDirectionCommand("west", Direction.WEST))

                                        // Verticals (idk, it's technically relative cause you look vertically but also still looking where you were looking before)
                                        .then(makeLookDirectionCommand("up", Direction.UP))
                                        .then(makeLookDirectionCommand("down", Direction.DOWN))

                                        // Relatives
                                        .then(makeLookRelativeCommand("left", -90))
                                        .then(makeLookRelativeCommand("right", 90))
                                        .then(makeLookRelativeCommand("back", 180))

                                        .then(literal("relative")
                                                .then(argument("rotation", RotationArgument.rotation())
                                                        .executes(c -> lookRelative(c, 0))
                                                        .then(literal("interpolate")
                                                                .then(argument("ticks", IntegerArgumentType.integer(1))
                                                                        .executes(c -> lookRelative(c, IntegerArgumentType.getInteger(c, "ticks")))))))

                                        .then(literal("random")
                                                .executes(manipulation(ap -> {
                                                    float yaw = ThreadLocalRandom.current().nextFloat() * 360 - 180;
                                                    float pitch = ThreadLocalRandom.current().nextFloat() * 180 - 90;
                                                    ap.look(yaw, pitch);
                                                })))
                                        .then(literal("upon")
                                                .then(argument("entity", EntityArgument.entity())
                                                        .executes(c -> lookUpon(c, LookMode.EYES, 0))
                                                        .then(makeLookUponMode("eyes", LookMode.EYES))
                                                        .then(makeLookUponMode("feet", LookMode.FEET))
                                                        .then(makeLookUponMode("closest", LookMode.CLOSEST))
                                                        .then(literal("interpolate")
                                                                .then(argument("ticks", IntegerArgumentType.integer(1))
                                                                        .executes(c -> lookUpon(c, LookMode.EYES, IntegerArgumentType.getInteger(c, "ticks")))))))
                                        .then(literal("at")
                                                .then(argument("position", Vec3Argument.vec3())
                                                        .executes(c -> manipulate(c,
                                                                ap -> ap.lookAt(Vec3Argument.getVec3(c, "position"))))
                                                        .then(literal("interpolate")
                                                                .then(argument("ticks", IntegerArgumentType.integer(1))
                                                                        .executes(c -> manipulate(c,
                                                                                ap -> ap.lookAt(Vec3Argument.getVec3(c, "position"),
                                                                                        IntegerArgumentType.getInteger(c, "ticks"))))))))
                                        .then(argument("direction", RotationArgument.rotation())
                                                .executes(c -> manipulate(c,
                                                        ap -> ap.look(
                                                                RotationArgument.getRotation(c, "direction")
                                                                        .getRotation(c.getSource()))))
                                                .then(literal("interpolate")
                                                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                                                .executes(c -> manipulate(c,
                                                                        ap -> ap.look(
                                                                                RotationArgument.getRotation(c, "direction")
                                                                                        .getRotation(c.getSource()),
                                                                                IntegerArgumentType.getInteger(c, "ticks"))))))))
                                .then(literal("ping")
                                        .executes(PlayerCommand::pingGet)
                                        .then(argument("value", IntegerArgumentType.integer(0))
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

                                .then(literal("copycat")
                                        .then(argument("source", EntityArgument.player())
                                                .executes(context -> {
                                                    ServerPlayer source = EntityArgument.getPlayer(context, "source");
                                                    for (BotPlayer bot : requireBotTargets(context)) {
                                                        bot.copycat(source);
                                                    }
                                                    return 1;
                                                })))

                                .then(literal("skin")
                                        .then(makeSkinPartCommand("cape", BotPlayer.SKIN_CAPE))
                                        .then(makeSkinPartCommand("jacket", BotPlayer.SKIN_JACKET))
                                        .then(makeSkinPartCommand("leftSleeve", BotPlayer.SKIN_LEFT_SLEEVE))
                                        .then(makeSkinPartCommand("rightSleeve", BotPlayer.SKIN_RIGHT_SLEEVE))
                                        .then(makeSkinPartCommand("leftPant", BotPlayer.SKIN_LEFT_PANT))
                                        .then(makeSkinPartCommand("rightPant", BotPlayer.SKIN_RIGHT_PANT))
                                        .then(makeSkinPartCommand("hat", BotPlayer.SKIN_HAT)))

                                // There's probably a better name for this but idk, like dexterity maybe? Dextrousness?? whatever
                                .then(literal("handedness")
                                        .then(literal("left")
                                                .executes(c -> setHandedness(c, true)))
                                        .then(literal("right")
                                                .executes(c -> setHandedness(c, false))))
                        )
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeActionCommand(String name, ActionType type) {
        return literal(name)
                .executes(manipulation(ap -> ap.stop(type)))
                .then(literal("once")
                        .executes(manipulation(ap -> ap.start(type, Action.once()))))
                .then(literal("continuous")
                        .executes(manipulation(ap -> ap.start(type, Action.continuous()))))
                .then(literal("interval")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> manipulate(c,
                                        ap -> ap.start(type,
                                                Action.interval(IntegerArgumentType.getInteger(c, "ticks")))))));
    }


    private static List<BotPlayer> requireBotTargets(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
        List<BotPlayer> bots = new ArrayList<>();

        for (ServerPlayer p : players) {
            if (!(p instanceof BotPlayer bot))
                throw NOT_BOT.create();
            bots.add(bot);
        }

        if (bots.isEmpty())
            throw NOT_BOT.create();

        return bots;
    }

    private static int manipulate(CommandContext<CommandSourceStack> context,
                                  Consumer<BotPlayerActionPack> action)
            throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context))
            action.accept(((ServerPlayerInterface) bot).getActionPack());
        return 1;
    }

    private static Command<CommandSourceStack> manipulation(Consumer<BotPlayerActionPack> action) {
        return c -> manipulate(c, action);
    }

    private static int kill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context))
            bot.kill(bot.level());
        return 1;
    }

    private static int disconnect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.ping = 0;
            bot.botPlayerDisconnect(Component.literal(""));
        }
        return 1;
    }

    private static int pingSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int value = IntegerArgumentType.getInteger(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.ping = value;
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s ping to " + value + "ms"), false);
        }
        return 1;
    }

    private static int pingGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> botPlayerList = requireBotTargets(context);
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


    private enum LookMode {EYES, FEET, CLOSEST}

    private static int lookUpon(CommandContext<CommandSourceStack> context, LookMode mode, int ticks)
            throws CommandSyntaxException {
        Entity target = EntityArgument.getEntity(context, "entity");
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");

        for (ServerPlayer player : players) {
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookTarget = switch (mode) {
                case FEET -> target.position();
                case EYES -> target.getEyePosition();
                case CLOSEST -> closestPointToBox(eyePos, target.getBoundingBox());
            };

            if (player instanceof ServerPlayerInterface spi)
                spi.getActionPack().lookAt(lookTarget, ticks);
            else
                player.lookAt(EntityAnchorArgument.Anchor.EYES, lookTarget);
        }

        return players.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeLookDirectionCommand(String name, Direction direction) {
        return literal(name)
                .executes(manipulation(ap -> ap.look(direction)))
                .then(literal("interpolate")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> manipulate(c,
                                        ap -> ap.look(direction, IntegerArgumentType.getInteger(c, "ticks"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeLookRelativeCommand(String name, float yaw) {
        return literal(name)
                .executes(manipulation(ap -> ap.turn(yaw, (float) 0)))
                .then(literal("interpolate")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> manipulate(c,
                                        ap -> ap.turn(yaw, (float) 0, IntegerArgumentType.getInteger(c, "ticks"))))));
    }

    private static int lookRelative(CommandContext<CommandSourceStack> context, int ticks)
            throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            CommandSourceStack botSource = context.getSource().withRotation(new Vec2(bot.getXRot(), bot.getYRot()));
            Vec2 rotation = RotationArgument.getRotation(context, "rotation").getRotation(botSource);
            BotPlayerActionPack ap = ((ServerPlayerInterface) bot).getActionPack();
            if (ticks > 0) {
                ap.look(rotation, ticks);
            } else {
                ap.look(rotation);
            }
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeLookUponMode(String name, LookMode mode) {
        return literal(name)
                .executes(c -> lookUpon(c, mode, 0))
                .then(literal("interpolate")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> lookUpon(c, mode, IntegerArgumentType.getInteger(c, "ticks")))));
    }

    private static Vec3 closestPointToBox(Vec3 eye, AABB box) {
        return new Vec3(
                Mth.clamp(eye.x, box.minX, box.maxX),
                Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ)
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSkinPartCommand(String name, byte mask) {
        return literal(name)
                .executes(c -> {
                    for (BotPlayer bot : requireBotTargets(c)) {
                        bot.toggleSkinPart(mask);
                        boolean enabled = bot.isSkinPartEnabled(mask);
                        c.getSource().sendSuccess(() -> Component.literal(
                                bot.getGameProfile().name() + ": " + name + " " + (enabled ? "shown" : "hidden")), false);
                    }
                    return 1;
                });
    }

    private static int setHandedness(CommandContext<CommandSourceStack> context, boolean leftHanded)
            throws CommandSyntaxException {
        HumanoidArm arm = leftHanded ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        String handName = leftHanded ? "left" : "right";
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.setMainHand(arm);
            context.getSource().sendSuccess(() -> Component.literal(
                    bot.getGameProfile().name() + ": main hand set to " + handName), false);
        }
        return 1;
    }
}
