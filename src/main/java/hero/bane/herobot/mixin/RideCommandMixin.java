package hero.bane.herobot.mixin;

import hero.bane.herobot.HeroBotSettings;
import net.minecraft.server.commands.RideCommand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RideCommand.class)
public class RideCommandMixin {

    @Redirect(
            method = "mount(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)I",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;is(Ljava/lang/Object;)Z"
            )
    )
    private static boolean allowPlayerMounts(Entity entity, Object type) {
            return false;
    }
}
