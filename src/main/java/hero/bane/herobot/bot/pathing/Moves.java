package hero.bane.herobot.bot.pathing;

import net.minecraft.world.level.Level;

import static hero.bane.herobot.bot.pathing.MovementHelper.canWalkOn;
import static hero.bane.herobot.bot.pathing.MovementHelper.isPassable;

public enum Moves {

    DOWNWARD(0, -1, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            if (!isPassable(level, x, y - 1, z, settings)) {
                return;
            }
            if (!canWalkOn(level, x, y - 2, z, settings)) {
                return;
            }
            result.x = x;
            result.y = y - 1;
            result.z = z;
            result.cost = settings.getVerticalMoveCost();
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            if (!isPassable(level, x, y + 2, z, settings)) {
                return;
            }
            if (!canWalkOn(level, x, y, z, settings)) {
                return;
            }
            result.x = x;
            result.y = y + 1;
            result.z = z;
            result.cost = settings.getVerticalMoveCost() + JUMP_PENALTY;
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            traverseCost(level, y, x, z - 1, settings, result);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            traverseCost(level, y, x, z + 1, settings, result);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            traverseCost(level, y, x + 1, z, settings, result);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            traverseCost(level, y, x - 1, z, settings, result);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            ascendCost(level, x, y, z, x, z - 1, settings, maxJump, result);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            ascendCost(level, x, y, z, x, z + 1, settings, maxJump, result);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            ascendCost(level, x, y, z, x + 1, z, settings, maxJump, result);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            ascendCost(level, x, y, z, x - 1, z, settings, maxJump, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            descendCost(level, y, x, z - 1, settings, maxFall, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            descendCost(level, y, x, z + 1, settings, maxFall, result);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            descendCost(level, y, x + 1, z, settings, maxFall, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            descendCost(level, y, x - 1, z, settings, maxFall, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            diagonalCost(level, x, y, z, x + 1, z - 1, settings, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            diagonalCost(level, x, y, z, x - 1, z - 1, settings, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            diagonalCost(level, x, y, z, x + 1, z + 1, settings, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            diagonalCost(level, x, y, z, x - 1, z + 1, settings, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            parkourCost(level, x, y, z, 0, -1, settings, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            parkourCost(level, x, y, z, 0, +1, settings, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            parkourCost(level, x, y, z, +1, 0, settings, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            parkourCost(level, x, y, z, -1, 0, settings, result);
        }
    },

    SWIM_NORTH(0, 0, -1) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            swimHorizontal(level, x, y, z, x, z - 1, settings, result);
        }
    },

    SWIM_SOUTH(0, 0, +1) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            swimHorizontal(level, x, y, z, x, z + 1, settings, result);
        }
    },

    SWIM_EAST(+1, 0, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            swimHorizontal(level, x, y, z, x + 1, z, settings, result);
        }
    },

    SWIM_WEST(-1, 0, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            swimHorizontal(level, x, y, z, x - 1, z, settings, result);
        }
    },

    SWIM_UP(0, +1, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            swimVertical(level, x, y, z, 1, settings, result);
        }
    },

    SWIM_DOWN(0, -1, 0) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            swimVertical(level, x, y, z, -1, settings, result);
        }
    },

    BUBBLE_COLUMN_UP(0, +1, 0, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            if (!MovementHelper.isBubbleColumnUp(level, x, y, z)) return;
            int height = MovementHelper.getBubbleColumnHeight(level, x, y, z, true);
            int destY = y + height;
            if (MovementHelper.canSwimThrough(level, x, destY, z) || canWalkOn(level, x, destY - 1, z, settings)) {
                result.x = x;
                result.y = destY;
                result.z = z;
                result.cost = height * BUBBLE_COST_PER_BLOCK;
            }
        }
    },

    BUBBLE_COLUMN_DOWN(0, -1, 0, false, true) {
        @Override
        public void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result) {
            if (!MovementHelper.isBubbleColumnDown(level, x, y, z)) return;
            int depth = MovementHelper.getBubbleColumnHeight(level, x, y, z, false);
            int destY = y - depth;
            if (MovementHelper.canSwimThrough(level, x, destY, z) || canWalkOn(level, x, destY - 1, z, settings)) {
                result.x = x;
                result.y = destY;
                result.z = z;
                result.cost = depth * BUBBLE_COST_PER_BLOCK;
            }
        }
    };

    private static final double SQRT_2 = Math.sqrt(2);
    private static final double JUMP_PENALTY = 0.5;
    private static final double SPRINT_MULTIPLIER = 0.6;
    private static final double FALL_COST_PER_BLOCK = 0.3;
    private static final double BUBBLE_COST_PER_BLOCK = 0.1;

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;
    public final boolean dynamicXZ;
    public final boolean dynamicY;

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    public abstract void apply(Level level, int x, int y, int z, PathSettings settings, int maxJump, int maxFall, MoveResult result);

    private static void traverseCost(Level level, int y, int destX, int destZ, PathSettings settings, MoveResult result) {
        if (!isPassable(level, destX, y, destZ, settings)) return;
        if (!isPassable(level, destX, y + 1, destZ, settings)) return;
        if (!canWalkOn(level, destX, y - 1, destZ, settings)) return;

        result.x = destX;
        result.y = y;
        result.z = destZ;
        double cost = settings.getHorizontalMoveCost();
        if (settings.getMoveType() != PathSettings.MoveType.WALK) {
            cost *= SPRINT_MULTIPLIER;
        }
        result.cost = cost;
    }

    private static void ascendCost(Level level, int x, int y, int z, int destX, int destZ, PathSettings settings, int maxJump, MoveResult result) {
        if (maxJump < 1) return;
        if (!isPassable(level, x, y + 2, z, settings)) return;
        if (!isPassable(level, destX, y + 1, destZ, settings)) return;
        if (!isPassable(level, destX, y + 2, destZ, settings)) return;
        if (!canWalkOn(level, destX, y, destZ, settings)) return;

        result.x = destX;
        result.y = y + 1;
        result.z = destZ;
        result.cost = settings.getHorizontalMoveCost() + settings.getVerticalMoveCost() + JUMP_PENALTY;
    }

    private static void descendCost(Level level, int y, int destX, int destZ, PathSettings settings, int maxFall, MoveResult result) {
        if (!isPassable(level, destX, y, destZ, settings)) return;
        if (!isPassable(level, destX, y + 1, destZ, settings)) return;

        if (canWalkOn(level, destX, y - 2, destZ, settings)
                && isPassable(level, destX, y - 1, destZ, settings)) {
            result.x = destX;
            result.y = y - 1;
            result.z = destZ;
            result.cost = settings.getHorizontalMoveCost() + FALL_COST_PER_BLOCK;
            return;
        }

        if (!isPassable(level, destX, y - 1, destZ, settings)) return;

        for (int drop = 2; drop <= maxFall; drop++) {
            int landY = y - drop;
            if (canWalkOn(level, destX, landY - 1, destZ, settings)
                    && isPassable(level, destX, landY, destZ, settings)
                    && isPassable(level, destX, landY + 1, destZ, settings)) {
                result.x = destX;
                result.y = landY;
                result.z = destZ;
                result.cost = settings.getHorizontalMoveCost() + drop * FALL_COST_PER_BLOCK;
                return;
            }
            if (!isPassable(level, destX, landY, destZ, settings)) return;
        }
    }

    private static void diagonalCost(Level level, int x, int y, int z, int destX, int destZ, PathSettings settings, MoveResult result) {
        if (!isPassable(level, destX, y, destZ, settings)) return;
        if (!isPassable(level, destX, y + 1, destZ, settings)) return;
        if (!canWalkOn(level, destX, y - 1, destZ, settings)) return;

        int adjX = destX;
        int adjZ = z;
        if (!isPassable(level, adjX, y, adjZ, settings)) return;
        if (!isPassable(level, adjX, y + 1, adjZ, settings)) return;

        adjX = x;
        adjZ = destZ;
        if (!isPassable(level, adjX, y, adjZ, settings)) return;
        if (!isPassable(level, adjX, y + 1, adjZ, settings)) return;

        result.x = destX;
        result.y = y;
        result.z = destZ;
        double cost = settings.getHorizontalMoveCost() * SQRT_2;
        if (settings.getMoveType() != PathSettings.MoveType.WALK) {
            cost *= SPRINT_MULTIPLIER;
        }
        result.cost = cost;
    }

    private static void parkourCost(Level level, int x, int y, int z, int dx, int dz, PathSettings settings, MoveResult result) {
        if (settings.getMoveType() == PathSettings.MoveType.WALK) return;

        int maxDist = settings.getMoveType() == PathSettings.MoveType.SPRINT_JUMP ? 4 : 3;

        if (!canWalkOn(level, x, y - 1, z, settings)) return;

        int adjX = x + dx;
        int adjZ = z + dz;
        if (canWalkOn(level, adjX, y - 1, adjZ, settings)) {
            return;
        }
        if (!isPassable(level, adjX, y, adjZ, settings)) return;
        if (!isPassable(level, adjX, y + 1, adjZ, settings)) return;
        if (!isPassable(level, x, y + 2, z, settings)) return;

        for (int dist = 2; dist <= maxDist; dist++) {
            int landX = x + dx * dist;
            int landZ = z + dz * dist;

            boolean clearPath = true;
            for (int d = 2; d < dist; d++) {
                int midX = x + dx * d;
                int midZ = z + dz * d;
                if (!isPassable(level, midX, y, midZ, settings)
                        || !isPassable(level, midX, y + 1, midZ, settings)
                        || !isPassable(level, midX, y + 2, midZ, settings)) {
                    clearPath = false;
                    break;
                }
            }
            if (!clearPath) break;

            if (!isPassable(level, landX, y + 2, landZ, settings)) continue;

            if (!isPassable(level, landX, y, landZ, settings)) continue;
            if (!isPassable(level, landX, y + 1, landZ, settings)) continue;
            if (!canWalkOn(level, landX, y - 1, landZ, settings)) continue;

            double cost = settings.getHorizontalMoveCost() * SPRINT_MULTIPLIER * dist + JUMP_PENALTY;

            result.x = landX;
            result.y = y;
            result.z = landZ;
            result.cost = cost;
            return;
        }

        int maxAscendDist = 3;
        for (int dist = 2; dist <= maxAscendDist; dist++) {
            int landX = x + dx * dist;
            int landZ = z + dz * dist;

            boolean clearPath = true;
            for (int d = 1; d < dist; d++) {
                int midX = x + dx * d;
                int midZ = z + dz * d;
                if (!isPassable(level, midX, y + 1, midZ, settings)
                        || !isPassable(level, midX, y + 2, midZ, settings)) {
                    clearPath = false;
                    break;
                }
            }
            if (!clearPath) break;

            if (!isPassable(level, landX, y + 1, landZ, settings)) continue;
            if (!isPassable(level, landX, y + 2, landZ, settings)) continue;
            if (!isPassable(level, landX, y + 3, landZ, settings)) continue;
            if (!canWalkOn(level, landX, y, landZ, settings)) continue;

            double cost = settings.getHorizontalMoveCost() * SPRINT_MULTIPLIER * dist + settings.getVerticalMoveCost() + JUMP_PENALTY;

            result.x = landX;
            result.y = y + 1;
            result.z = landZ;
            result.cost = cost;
            return;
        }
    }

    private static void swimHorizontal(Level level, int x, int y, int z, int destX, int destZ, PathSettings settings, MoveResult result) {
        boolean srcWater = MovementHelper.isWater(level, x, y, z);
        boolean swimming = MovementHelper.isWater(level, destX, y, destZ);
        if (!srcWater && !swimming) return;

        if (!MovementHelper.canSwimThrough(level, destX, y, destZ)
                && !isPassable(level, destX, y, destZ, settings)) return;
        if (!MovementHelper.canSwimThrough(level, destX, y + 1, destZ)
                && !isPassable(level, destX, y + 1, destZ, settings)) return;

        boolean landing = !swimming && canWalkOn(level, destX, y - 1, destZ, settings);
        if (!landing && !swimming) return;

        result.x = destX;
        result.y = y;
        result.z = destZ;
        result.cost = swimming
                ? settings.getHorizontalMoveCost() * settings.getSwimCostMultiplier()
                : settings.getHorizontalMoveCost();
    }

    private static void swimVertical(Level level, int x, int y, int z, int dy, PathSettings settings, MoveResult result) {
        if (!MovementHelper.isWater(level, x, y, z)) return;

        int destY = y + dy;
        if (dy > 0) {
            if (!MovementHelper.canSwimThrough(level, x, destY, z)
                    && !isPassable(level, x, destY, z, settings)) return;
            if (!MovementHelper.canSwimThrough(level, x, destY + 1, z)
                    && !isPassable(level, x, destY + 1, z, settings)) return;
        } else {
            if (!MovementHelper.canSwimThrough(level, x, destY, z)) return;
        }

        result.x = x;
        result.y = destY;
        result.z = z;
        result.cost = settings.getVerticalMoveCost() * settings.getSwimCostMultiplier();
    }
}
