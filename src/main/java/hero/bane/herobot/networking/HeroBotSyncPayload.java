package hero.bane.herobot.networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public record HeroBotSyncPayload(String settingsJson) implements CustomPacketPayload {

    public static final Type<HeroBotSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("herobot", "sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HeroBotSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, HeroBotSyncPayload::settingsJson,
                    HeroBotSyncPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
