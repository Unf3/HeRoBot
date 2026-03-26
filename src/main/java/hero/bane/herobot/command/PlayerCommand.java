package hero.bane.herobot.command;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import hero.bane.herobot.HeroBotSettings;
import hero.bane.herobot.bot.BotPathing;
import hero.bane.herobot.bot.BotPlayer;
import hero.bane.herobot.bot.BotPlayerActionPack;
import hero.bane.herobot.bot.BotPlayerActionPack.Action;
import hero.bane.herobot.bot.BotPlayerActionPack.ActionType;
import hero.bane.herobot.bot.connection.ServerPlayerInterface;
import hero.bane.herobot.bot.pathing.BotPathSettings;
import hero.bane.herobot.bot.pathing.BotPathfinder;
import hero.bane.herobot.mixin.PlayerAccessor;
import hero.bane.herobot.mixin.ServerCommonPacketListenerImplAccessor;
import hero.bane.herobot.util.ItemCooldown;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
                                        .executes(manipulationAndStopPath(BotPlayerActionPack::stopMovement))
                                        .then(literal("forward")
                                                .executes(manipulationAndStopPath(ap -> ap.setForward(1))))
                                        .then(literal("backward")
                                                .executes(manipulationAndStopPath(ap -> ap.setForward(-1))))
                                        .then(literal("left")
                                                .executes(manipulationAndStopPath(ap -> ap.setStrafing(1))))
                                        .then(literal("right")
                                                .executes(manipulationAndStopPath(ap -> ap.setStrafing(-1)))))

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
                                                        .then(literal("delta")
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
                                                        .then(literal("delta")
                                                                .then(argument("ticks", IntegerArgumentType.integer(1))
                                                                        .executes(c -> lookUpon(c, LookMode.EYES, IntegerArgumentType.getInteger(c, "ticks")))))))
                                        .then(literal("at")
                                                .then(argument("position", Vec3Argument.vec3())
                                                        .executes(c -> manipulate(c,
                                                                ap -> ap.lookAt(Vec3Argument.getVec3(c, "position"))))
                                                        .then(literal("delta")
                                                                .then(argument("ticks", IntegerArgumentType.integer(1))
                                                                        .executes(c -> manipulate(c,
                                                                                ap -> ap.lookAt(Vec3Argument.getVec3(c, "position"),
                                                                                        IntegerArgumentType.getInteger(c, "ticks"))))))))
                                        .then(argument("direction", RotationArgument.rotation())
                                                .suggests((c, b) -> {
                                                    b.suggest("~ ~");
                                                    return b.buildFuture();
                                                })
                                                .executes(c -> manipulate(c,
                                                        ap -> ap.look(
                                                                RotationArgument.getRotation(c, "direction")
                                                                        .getRotation(c.getSource()))))
                                                .then(literal("delta")
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
                                        .then(makeSkinPartCommand("hat", BotPlayer.SKIN_HAT))
                                        .then(literal("forceload")
                                                .executes(PlayerCommand::forceLoadSkin)))

                                .then(literal("autojump")
                                        .executes(manipulation(BotPlayerActionPack::attemptAutoJump))
                                        .then(literal("true")
                                                .executes(c -> autoJump(c, true)))
                                        .then(literal("false")
                                                .executes(c -> autoJump(c, false))))

                                // There's probably a better name for this but idk, like dexterity maybe? Dexterousness?? whatever
                                .then(literal("handedness")
                                        .then(literal("left")
                                                .executes(c -> setHandedness(c, true)))
                                        .then(literal("right")
                                                .executes(c -> setHandedness(c, false))))

                                .then(literal("path")
                                        .then(literal("pos")
                                                .then(argument("position", Vec3Argument.vec3())
                                                        .executes(PlayerCommand::pathToPos)))
                                        .then(literal("entity")
                                                .then(argument("entity", EntityArgument.entity())
                                                        .executes(PlayerCommand::pathToEntity)))
                                        .then(literal("stop")
                                                .executes(PlayerCommand::pathStop))
                                        .then(literal("settings")
                                                .executes(PlayerCommand::listPathSettings)
                                                .then(literal("avoidedBlocks")
                                                        .executes(PlayerCommand::listAvoidedBlocks)
                                                        .then(literal("add")
                                                                .then(argument("block", BlockStateArgument.block(ctx))
                                                                        .executes(PlayerCommand::addAvoidedBlock)))
                                                        .then(literal("remove")
                                                                .then(argument("block", BlockStateArgument.block(ctx))
                                                                        .executes(PlayerCommand::removeAvoidedBlock)))
                                                        .then(literal("clear")
                                                                .executes(PlayerCommand::clearAvoidedBlocks)))
                                                .then(literal("moveType")
                                                        .executes(PlayerCommand::getMoveType)
                                                        .then(literal("walk")
                                                                .executes(c -> setMoveType(c, BotPathSettings.MoveType.WALK)))
                                                        .then(literal("sprint")
                                                                .executes(c -> setMoveType(c, BotPathSettings.MoveType.SPRINT)))
                                                        .then(literal("sprintjump")
                                                                .executes(c -> setMoveType(c, BotPathSettings.MoveType.SPRINT_JUMP))))
                                                .then(literal("target")
                                                        .then(literal("horizontal")
                                                                .executes(PlayerCommand::getMaxHorizontalDistance)
                                                                .then(argument("value", DoubleArgumentType.doubleArg(0.01))
                                                                        .executes(PlayerCommand::setMaxHorizontalDistance)))
                                                        .then(literal("vertical")
                                                                .executes(PlayerCommand::getMaxVerticalDistance)
                                                                .then(argument("value", DoubleArgumentType.doubleArg(-1))
                                                                        .executes(PlayerCommand::setMaxVerticalDistance))))
                                                .then(literal("node")
                                                        .then(literal("horizontal")
                                                                .executes(PlayerCommand::getNodeHorizontalDistance)
                                                                .then(argument("value", DoubleArgumentType.doubleArg(0.01))
                                                                        .executes(PlayerCommand::setNodeHorizontalDistance)))
                                                        .then(literal("vertical")
                                                                .executes(PlayerCommand::getNodeVerticalDistance)
                                                                .then(argument("value", DoubleArgumentType.doubleArg(-1))
                                                                        .executes(PlayerCommand::setNodeVerticalDistance)))
                                                )
                                                .then(literal("stopFollowing")
                                                        .executes(PlayerCommand::getStopFollowing)
                                                        .then(literal("true")
                                                                .executes(c -> setStopFollowing(c, true)))
                                                        .then(literal("false")
                                                                .executes(c -> setStopFollowing(c, false))))
                                                .then(literal("debug")
                                                        .executes(PlayerCommand::getDebug)
                                                        .then(literal("true")
                                                                .executes(c -> setDebug(c, true)))
                                                        .then(literal("false")
                                                                .executes(c -> setDebug(c, false))))
                                                .then(literal("cost")
                                                        .then(literal("horizontal")
                                                                .executes(PlayerCommand::getHorizontalMoveCost)
                                                                .then(argument("value", DoubleArgumentType.doubleArg(0.01))
                                                                        .executes(PlayerCommand::setHorizontalMoveCost)))
                                                        .then(literal("vertical")
                                                                .executes(PlayerCommand::getVerticalMoveCost)
                                                                .then(argument("value", DoubleArgumentType.doubleArg(0.01))
                                                                        .executes(PlayerCommand::setVerticalMoveCost))))))
                        )
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeActionCommand(String name, ActionType type) {
        return literal(name)
                .executes(manipulation(ap -> ap.stop(type)))
                .then(literal("once")
                        .executes(manipulation(ap -> ap.start(type, Action.once()))))
                .then(literal("continuous")
                        .executes(manipulation(ap -> ap.start(type, Action.continuous())))
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> {
                                            if (IntegerArgumentType.getInteger(c, "ticks") == 1) {
                                                return manipulate(c, ap -> ap.start(type, Action.once()));
                                            } else {
                                                return manipulate(c,
                                                        ap -> ap.start(type,
                                                                Action.continuous(IntegerArgumentType.getInteger(c, "ticks"))));
                                            }
                                        }
                                )))
                .then(literal("interval")
                        .then(argument("interval", IntegerArgumentType.integer(1))
                                .executes(c -> manipulate(c,
                                        ap -> ap.start(type,
                                                Action.interval(IntegerArgumentType.getInteger(c, "interval")))))
                                .then(argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(c -> manipulate(c,
                                                ap -> ap.start(type,
                                                        Action.interval(
                                                                IntegerArgumentType.getInteger(c, "interval"),
                                                                IntegerArgumentType.getInteger(c, "ticks"))))))));
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

    private static int manipulateAndStopPath(CommandContext<CommandSourceStack> context,
                                             Consumer<BotPlayerActionPack> action)
            throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.clearPathFollower();
            action.accept(((ServerPlayerInterface) bot).getActionPack());
        }
        return 1;
    }

    private static Command<CommandSourceStack> manipulationAndStopPath(Consumer<BotPlayerActionPack> action) {
        return c -> manipulateAndStopPath(c, action);
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
            ((ServerCommonPacketListenerImplAccessor) bot.connection).setLatency(value);
            context.getSource().getServer().getPlayerList().broadcastAll(
                    new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, bot));
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
                .then(literal("delta")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(c -> manipulate(c,
                                        ap -> ap.look(direction, IntegerArgumentType.getInteger(c, "ticks"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeLookRelativeCommand(String name, float yaw) {
        return literal(name)
                .executes(manipulation(ap -> ap.turn(yaw, (float) 0)))
                .then(literal("delta")
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
                .then(literal("delta")
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
                        c.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s " + name + " layer " + (enabled ? "on" : "off")), false);
                    }
                    return 1;
                });
    }

    private static int forceLoadSkin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var server = context.getSource().getServer();
        for (BotPlayer bot : requireBotTargets(context)) {
            String uuid = bot.getUUID().toString().replace("-", "");
            String name = bot.getGameProfile().name();
            context.getSource().sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);

            CompletableFuture.supplyAsync(() -> {
                try {
                    HttpURLConnection connection = (HttpURLConnection) URI.create(
                            "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"
                    ).toURL().openConnection();
                    connection.setConnectTimeout(5000); //5s timeout, if you have 5000 ping to the internet, something's wrong
                    connection.setReadTimeout(5000);
                    if (connection.getResponseCode() != 200) return null;
                    try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                        return JsonParser.parseReader(reader).getAsJsonObject();
                    }
                } catch (Exception e) {
                    return null;
                }
            }).thenAcceptAsync(json -> {
                if (json == null) {
                    context.getSource().sendFailure(Component.literal("Failed to fetch skin for " + name));
                    return;
                }

                ImmutableMultimap.Builder<String, Property> builder = ImmutableMultimap.builder();
                JsonArray properties = json.getAsJsonArray("properties");
                if (properties != null) {
                    for (var element : properties) {
                        JsonObject prop = element.getAsJsonObject();
                        String propName = prop.get("name").getAsString();
                        String value = prop.get("value").getAsString();
                        String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
                        builder.put(propName, new Property(propName, value, signature));
                    }
                }
                GameProfile newProfile = new GameProfile(bot.getUUID(), name, new PropertyMap(builder.build()));
                ((PlayerAccessor) bot).setGameProfile(newProfile);

                var playerList = server.getPlayerList();
                playerList.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID())));
                playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)));

                ServerLevel level = bot.level();
                level.getChunkSource().removeEntity(bot);
                level.getChunkSource().addEntity(bot);

                context.getSource().sendSuccess(() -> Component.literal("Force-loaded skin for " + name), false);
            }, server);
        }
        return 1;
    }

    private static int autoJump(CommandContext<CommandSourceStack> context, boolean value)
            throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            BotPlayerActionPack ap = ((ServerPlayerInterface) bot).getActionPack();
            ap.autoJump = value;
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s auto jump " + (value ? "on" : "off")), false);
        }
        return 1;
    }

    private static int pathToPos(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 target = Vec3Argument.getVec3(context, "position");
        for (BotPlayer bot : requireBotTargets(context)) {
            BotPathSettings settings = bot.getPathSettings();

            BotPathing existing = bot.getPathFollower();
            if (existing != null && !existing.isDone() && !existing.isEntityMode()
                    && existing.getTarget().distanceTo(target) < 0.01) {
                context.getSource().sendFailure(Component.literal(bot.getGameProfile().name() + " is already pathing to that target"));
                return 0;
            }

            if (settings.isWithinTarget(
                    Math.sqrt(hDistSq(bot.position(), target)),
                    Math.abs(bot.position().y - target.y))) {
                context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " is already at target"), false);
                continue;
            }

            List<BlockPos> path = BotPathfinder.findPath(bot.level(), bot.blockPosition(), target, settings, bot);
            if (path == null || path.isEmpty()) {
                context.getSource().sendFailure(Component.literal("Failed to find a path for " + bot.getGameProfile().name()));
                return 0;
            }

            BotPathing follower = new BotPathing(bot, path, target, context.getSource(), settings);
            bot.setPathFollower(follower);
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " is pathing to " +
                    String.format("%.1f, %.1f, %.1f", target.x, target.y, target.z)), false);
        }
        return 1;
    }

    private static int pathToEntity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity target = EntityArgument.getEntity(context, "entity");
        for (BotPlayer bot : requireBotTargets(context)) {
            BotPathing existing = bot.getPathFollower();
            if (existing != null && !existing.isDone() && existing.isEntityMode()
                    && existing.getTargetEntity() == target) {
                context.getSource().sendFailure(Component.literal(bot.getGameProfile().name() + " is already pathing to that target"));
                return 0;
            }

            BotPathSettings settings = bot.getPathSettings();
            BotPathing follower = new BotPathing(bot, target, context.getSource(), settings);
            bot.setPathFollower(follower);
            String targetName = target.getName().getString();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " is following " + targetName), false);
        }
        return 1;
    }

    private static int pathStop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.clearPathFollower();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " stopped pathing"), false);
        }
        return 1;
    }


    private static int listPathSettings(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            BotPathSettings s = bot.getPathSettings();
            String msg = bot.getGameProfile().name() + "'s path settings:" +
                    "\n  moveType: " + s.getMoveType().displayName() +
                    "\n  final horizontal: " + s.getMaxHorizontalDistance() +
                    "\n  final vertical: " + (s.getMaxVerticalDistance() < 0 ? "ground-seek" : s.getMaxVerticalDistance()) +
                    "\n  node horizontal: " + s.getNodeHorizontalDistance() +
                    "\n  node vertical: " + (s.getNodeVerticalDistance() < 0 ? "disabled" : s.getNodeVerticalDistance()) +
                    "\n  stopFollowing: " + s.isStopFollowing() +
                    "\n  debug: " + s.isDebug() +
                    "\n  cost horizontal: " + s.getHorizontalMoveCost() +
                    "\n  cost vertical: " + s.getVerticalMoveCost();
            context.getSource().sendSuccess(() -> Component.literal(msg), false);
        }
        return 1;
    }

    private static int listAvoidedBlocks(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            BotPathSettings settings = bot.getPathSettings();
            if (settings.getAvoidedBlocks().isEmpty()) {
                context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " has no avoided blocks"), false);
            } else {
                String list = settings.getAvoidedBlocks().stream()
                        .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
                        .collect(Collectors.joining(", "));
                context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " avoided blocks: " + list), false);
            }
        }
        return 1;
    }

    private static int addAvoidedBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Block block = BlockStateArgument.getBlock(context, "block").getState().getBlock();
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().addAvoidedBlock(block);
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            context.getSource().sendSuccess(() -> Component.literal("Added " + name + " to " + bot.getGameProfile().name() + "'s avoided blocks"), false);
        }
        return 1;
    }

    private static int removeAvoidedBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Block block = BlockStateArgument.getBlock(context, "block").getState().getBlock();
        for (BotPlayer bot : requireBotTargets(context)) {
            boolean removed = bot.getPathSettings().removeAvoidedBlock(block);
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (removed) {
                context.getSource().sendSuccess(() -> Component.literal("Removed " + name + " from " + bot.getGameProfile().name() + "'s avoided blocks"), false);
            } else {
                context.getSource().sendFailure(Component.literal(name + " was not in " + bot.getGameProfile().name() + "'s avoided blocks"));
            }
        }
        return 1;
    }

    private static int clearAvoidedBlocks(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().clearAvoidedBlocks();
            context.getSource().sendSuccess(() -> Component.literal("Cleared " + bot.getGameProfile().name() + "'s avoided blocks"), false);
        }
        return 1;
    }

    private static int getMoveType(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            String type = bot.getPathSettings().getMoveType().displayName();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s move type: " + type), false);
        }
        return 1;
    }

    private static int setMoveType(CommandContext<CommandSourceStack> context, BotPathSettings.MoveType type) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setMoveType(type);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s move type to " + type.displayName()), false);
        }
        return 1;
    }

    private static int getMaxHorizontalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            double val = bot.getPathSettings().getMaxHorizontalDistance();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s target horizontal: " + val + " (default: 1.0)"), false);
        }
        return 1;
    }

    private static int setMaxHorizontalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setMaxHorizontalDistance(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s target horizontal to " + value), false);
        }
        return 1;
    }

    private static int getMaxVerticalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            double val = bot.getPathSettings().getMaxVerticalDistance();
            String display = val < 0 ? "ground-seek" : String.valueOf(val);
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s target vertical: " + display + " (default: 2.0)"), false);
        }
        return 1;
    }

    private static int setMaxVerticalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setMaxVerticalDistance(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s target vertical to " + (value < 0 ? "ground-seek" : value)), false);
        }
        return 1;
    }

    private static int getNodeHorizontalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            double val = bot.getPathSettings().getNodeHorizontalDistance();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s node horizontal: " + val + " (default: 0.5)"), false);
        }
        return 1;
    }

    private static int setNodeHorizontalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setNodeHorizontalDistance(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s node horizontal to " + value), false);
        }
        return 1;
    }

    private static int getNodeVerticalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            double val = bot.getPathSettings().getNodeVerticalDistance();
            String display = val < 0 ? "disabled" : String.valueOf(val);
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s node vertical: " + display + " (default: 1.0)"), false);
        }
        return 1;
    }

    private static int setNodeVerticalDistance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setNodeVerticalDistance(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s node vertical to " + (value < 0 ? "disabled" : value)), false);
        }
        return 1;
    }

    private static int getStopFollowing(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            boolean val = bot.getPathSettings().isStopFollowing();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s stopFollowing: " + val + " (default: true)"), false);
        }
        return 1;
    }

    private static int setStopFollowing(CommandContext<CommandSourceStack> context, boolean value) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setStopFollowing(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s stopFollowing to " + value), false);
        }
        return 1;
    }

    private static int getDebug(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            boolean val = bot.getPathSettings().isDebug();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s debug: " + val + " (default: false)"), false);
        }
        return 1;
    }

    private static int setDebug(CommandContext<CommandSourceStack> context, boolean value) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setDebug(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s debug to " + value), false);
        }
        return 1;
    }

    private static int getHorizontalMoveCost(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            double val = bot.getPathSettings().getHorizontalMoveCost();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s cost horizontal: " + val + " (default: 1.0)"), false);
        }
        return 1;
    }

    private static int setHorizontalMoveCost(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setHorizontalMoveCost(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s cost horizontal to " + value), false);
        }
        return 1;
    }

    private static int getVerticalMoveCost(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        for (BotPlayer bot : requireBotTargets(context)) {
            double val = bot.getPathSettings().getVerticalMoveCost();
            context.getSource().sendSuccess(() -> Component.literal(bot.getGameProfile().name() + "'s cost vertical: " + val + " (default: 1.5)"), false);
        }
        return 1;
    }

    private static int setVerticalMoveCost(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(context, "value");
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.getPathSettings().setVerticalMoveCost(value);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s cost vertical to " + value), false);
        }
        return 1;
    }

    private static double hDistSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static int setHandedness(CommandContext<CommandSourceStack> context, boolean leftHanded)
            throws CommandSyntaxException {
        HumanoidArm arm = leftHanded ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        for (BotPlayer bot : requireBotTargets(context)) {
            bot.setMainHand(arm);
            context.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + (leftHanded ? " Left-Handed" : " Right-Handed")), false);
        }
        return 1;
    }
}
