package hero.bane.herobot.client;

import hero.bane.herobot.networking.HeroBotSyncPayload;
import hero.bane.herobot.rule.RuleConfigIO;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class HeroBotClient implements ClientModInitializer {

    private static boolean heroBotLoaded = false;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(HeroBotSyncPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                RuleConfigIO.applyRemoteSettings(payload.settingsJson());
                heroBotLoaded = true;
            });
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            heroBotLoaded = false;
            RuleConfigIO.reapplyLayers();
        });
    }

    public static boolean isHeroBotLoaded() {
        return heroBotLoaded;
    }
}
