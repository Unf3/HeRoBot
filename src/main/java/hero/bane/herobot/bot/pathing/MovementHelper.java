package hero.bane.herobot.bot.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class MovementHelper {

    private MovementHelper() {
    }

    public static boolean isPassable(Level level, int x, int y, int z, PathSettings settings) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) return false;
        return settings.isNotAvoided(state.getBlock());
    }

    public static boolean canWalkOn(Level level, int x, int y, int z, PathSettings settings) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.getCollisionShape(level, pos).isEmpty()) return false;
        return settings.isNotAvoided(state.getBlock());
    }

    public static boolean isWalkable(Level level, int x, int y, int z, PathSettings settings) {
        if (!isPassable(level, x, y, z, settings)) return false;
        if (!isPassable(level, x, y + 1, z, settings)) return false;
        return canWalkOn(level, x, y - 1, z, settings);
    }
}
