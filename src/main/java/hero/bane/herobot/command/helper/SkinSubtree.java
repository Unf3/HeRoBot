package hero.bane.herobot.command.helper;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import hero.bane.herobot.bot.BotPlayer;
import hero.bane.herobot.mixin.PlayerAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SkinSubtree {

    private SkinSubtree() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("skin")
                .then(makeSkinPartCommand("cape", BotPlayer.SKIN_CAPE))
                .then(makeSkinPartCommand("jacket", BotPlayer.SKIN_JACKET))
                .then(makeSkinPartCommand("leftSleeve", BotPlayer.SKIN_LEFT_SLEEVE))
                .then(makeSkinPartCommand("rightSleeve", BotPlayer.SKIN_RIGHT_SLEEVE))
                .then(makeSkinPartCommand("leftPant", BotPlayer.SKIN_LEFT_PANT))
                .then(makeSkinPartCommand("rightPant", BotPlayer.SKIN_RIGHT_PANT))
                .then(makeSkinPartCommand("hat", BotPlayer.SKIN_HAT))
                .then(Commands.literal("forceload")
                        .executes(SkinSubtree::forceLoadSkin));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeSkinPartCommand(String name, byte mask) {
        return Commands.literal(name)
                .executes(c -> {
                    for (BotPlayer bot : CommandHelper.requireBotTargets(c)) {
                        bot.toggleSkinPart(mask);
                        boolean enabled = bot.isSkinPartEnabled(mask);
                        c.getSource().sendSuccess(() -> Component.literal("Set " + bot.getGameProfile().name() + "'s " + name + " layer " + (enabled ? "on" : "off")), false);
                    }
                    return 1;
                });
    }

    private static int forceLoadSkin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var server = context.getSource().getServer();
        for (BotPlayer bot : CommandHelper.requireBotTargets(context)) {
            String uuid = bot.getUUID().toString().replace("-", "");
            String name = bot.getGameProfile().name();
            context.getSource().sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);

            CompletableFuture.supplyAsync(() -> {
                try {
                    HttpURLConnection connection = (HttpURLConnection) URI.create(
                            "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"
                    ).toURL().openConnection();
                    connection.setConnectTimeout(5000);
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
}
