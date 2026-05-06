package hero.bane.herobot;

import hero.bane.herobot.command.*;
import hero.bane.herobot.networking.HeroBotSyncPayload;
import hero.bane.herobot.rule.RuleConfigIO;
import hero.bane.herobot.util.delayer.DelayedQueue;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeroBot implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("HeroBot");
    public static MinecraftServer currentServer = null;

    @Override
    public void onInitialize() {
        HeroBotSettings.init();

        PayloadTypeRegistry.clientboundPlay().register(HeroBotSyncPayload.TYPE, HeroBotSyncPayload.STREAM_CODEC);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
        {
            PlayerCommand.register(dispatcher, registryAccess);
            PlayerSpawnCommand.register(dispatcher, registryAccess);
            DistanceCommand.register(dispatcher, registryAccess);
            HeroBotCommand.register(dispatcher, registryAccess);
            DelayedCommand.register(dispatcher, registryAccess);
            ChunkResetterCommand.register(dispatcher, registryAccess);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> currentServer = server);

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> currentServer = null);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                syncSettingsToPlayer(handler.player));

        RuleConfigIO.onSettingsChanged = HeroBot::syncSettingsToAllPlayers;

        ServerTickEvents.END_SERVER_TICK.register(DelayedQueue::tick);
    }

    public static void syncSettingsToPlayer(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, HeroBotSyncPayload.TYPE)) {
            ServerPlayNetworking.send(player, new HeroBotSyncPayload(RuleConfigIO.serializeCurrentSettings()));
        }
    }

    public static void syncSettingsToAllPlayers() {
        if (currentServer == null) return;
        HeroBotSyncPayload payload = new HeroBotSyncPayload(RuleConfigIO.serializeCurrentSettings());
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, HeroBotSyncPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
