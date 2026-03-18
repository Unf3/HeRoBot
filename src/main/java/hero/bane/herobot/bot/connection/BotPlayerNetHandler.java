package hero.bane.herobot.bot.connection;

import hero.bane.herobot.bot.BotPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.jspecify.annotations.NonNull;

import java.util.Set;

public class BotPlayerNetHandler extends ServerGamePacketListenerImpl {

    public BotPlayerNetHandler(
            MinecraftServer server,
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie cookie
    ) {
        super(server, connection, player, cookie);
    }

    @Override
    public void send(@NonNull Packet<?> packet) {
    }

    @Override
    public void disconnect(@NonNull Component message) {
        ((BotPlayer) player).botPlayerDisconnect(message);
    }

    @Override
    public void teleport(@NonNull PositionMoveRotation pos, @NonNull Set<Relative> relatives) {
        super.teleport(pos, relatives);
        if (player.level().getPlayerByUUID(player.getUUID()) != null) {
            resetPosition();
            player.level().getChunkSource().move(player);
        }
    }
}
