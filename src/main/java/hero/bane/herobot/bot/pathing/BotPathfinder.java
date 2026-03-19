package hero.bane.herobot.bot.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class BotPathfinder {

    private static final int noFall = 256;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static int getMaxFallDistance(Player player) {
        if (player instanceof ServerPlayer sp && sp.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            return noFall;
        }
        return (int) player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
    }

    private static int getMaxJumpHeight(Player player) {
        int level = 0;
        if (player.hasEffect(MobEffects.JUMP_BOOST)) {
            level = Objects.requireNonNull(player.getEffect(MobEffects.JUMP_BOOST)).getAmplifier() + 1;
        }
        double velocity = 0.42 + level * 0.1;
        return (int) (velocity * velocity / 0.16);
    }

    public static List<BlockPos> findPath(Level world, BlockPos start, Vec3 target, BotPathSettings settings, Player player) {
        return findPath(world, start, target, settings, player, 50000);
    }

    public static List<BlockPos> findPath(Level world, BlockPos start, Vec3 target, BotPathSettings settings, Player player, int maxIterations) {
        int maxJump = getMaxJumpHeight(player);
        int maxFall = getMaxFallDistance(player);

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<Long, Double> bestCost = new HashMap<>();

        Node startNode = new Node(start, 0, heuristic(start, target), null);
        openSet.add(startNode);
        bestCost.put(start.asLong(), 0.0);

        while (!openSet.isEmpty() && maxIterations-- > 0) {
            Node current = openSet.poll();

            if (isWithinGoal(current.pos, target, settings)) {
                return smoothPath(reconstructPath(current), world, settings);
            }

            if (current.gCost > bestCost.getOrDefault(current.pos.asLong(), Double.MAX_VALUE)) {
                continue;
            }

            for (Neighbor neighbor : getNeighbors(world, current.pos, settings, maxJump, maxFall)) {
                double tentativeG = current.gCost + neighbor.cost;
                long key = neighbor.pos.asLong();

                if (tentativeG < bestCost.getOrDefault(key, Double.MAX_VALUE)) {
                    bestCost.put(key, tentativeG);
                    openSet.add(new Node(neighbor.pos, tentativeG, tentativeG + heuristic(neighbor.pos, target), current));
                }
            }
        }

        return null;
    }

    private static boolean isWithinGoal(BlockPos pos, Vec3 target, BotPathSettings settings) {
        double hDist = closestHDistToBlock(pos, target);
        double vDist = Math.abs(pos.getY() - target.y);
        return settings.isWithinTarget(hDist, vDist);
    }

    private static double heuristic(BlockPos pos, Vec3 target) {
        double dx = pos.getX() + 0.5 - target.x;
        double dy = pos.getY() - target.y;
        double dz = pos.getZ() + 0.5 - target.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Neighbor(BlockPos pos, double cost) {}

    private static List<Neighbor> getNeighbors(Level level, BlockPos pos, BotPathSettings settings, int maxJump, int maxFall) {
        List<Neighbor> neighbors = new ArrayList<>();
        for (int[] off : CARDINAL) {
            addCardinalNeighbors(level, pos, off[0], off[1], neighbors, settings, maxJump, maxFall);
        }
        for (int[] off : DIAGONAL) {
            addDiagonalNeighbor(level, pos, off[0], off[1], neighbors, settings, maxJump);
        }
        return neighbors;
    }

    private static void addCardinalNeighbors(Level level, BlockPos pos, int dx, int dz, List<Neighbor> neighbors, BotPathSettings settings, int maxJump, int maxFall) {
        int posX = pos.getX();
        int posY = pos.getY();
        int posZ = pos.getZ();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        int upX = posX + dx;
        int upZ = posZ + dz;
        for (int climb = 1; climb <= maxJump; climb++) {
            mutable.set(posX, posY + 1 + climb, posZ);
            if (!isPassable(level, mutable, settings)) break;
            BlockPos up = new BlockPos(upX, posY + climb, upZ);
            if (isWalkable(level, up, settings)) {
                neighbors.add(new Neighbor(up, settings.getHorizontalMoveCost() + settings.getVerticalMoveCost() * climb));
            }
        }

        // Same-level and down use spacing 2, hardcoded [for some reason whenever I put it at 3 it just breaks. It may be due to going up/down steps]
        int x = posX + dx * 2;
        int z = posZ + dz * 2;

        BlockPos sameLevel = new BlockPos(x, posY, z);
        if (isWalkable(level, sameLevel, settings)) {
            neighbors.add(new Neighbor(sameLevel, settings.getHorizontalMoveCost()));
        }

        mutable.set(x, posY, z);
        if (!isPassable(level, mutable, settings)) return;
        mutable.set(x, posY + 1, z);
        if (!isPassable(level, mutable, settings)) return;

        for (int drop = 1; drop <= maxFall; drop++) {
            BlockPos landing = new BlockPos(x, posY - drop, z);
            if (isWalkable(level, landing, settings)) {
                neighbors.add(new Neighbor(landing, settings.getHorizontalMoveCost() + drop * 0.3));
                return;
            }
            if (!isPassable(level, landing, settings)) return;
        }
    }

    private static void addDiagonalNeighbor(Level level, BlockPos pos, int dx, int dz, List<Neighbor> neighbors, BotPathSettings settings, int maxJump) {
        int posX = pos.getX();
        int posY = pos.getY();
        int posZ = pos.getZ();
        int x = posX + dx;
        int z = posZ + dz;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        BlockPos target = new BlockPos(x, posY, z);
        if (isWalkable(level, target, settings)) {
            mutable.set(posX + dx, posY, posZ);
            boolean adj1Pass = isPassable(level, mutable, settings);
            if (adj1Pass) {
                mutable.setY(posY + 1);
                adj1Pass = isPassable(level, mutable, settings);
            }
            if (adj1Pass) {
                mutable.set(posX, posY, posZ + dz);
                if (isPassable(level, mutable, settings)) {
                    mutable.setY(posY + 1);
                    if (isPassable(level, mutable, settings)) {
                        neighbors.add(new Neighbor(target, settings.getHorizontalMoveCost() * 1.4));
                    }
                }
            }
        }

        int adj1X = posX + dx;
        int adj2Z = posZ + dz;
        for (int climb = 1; climb <= maxJump; climb++) {
            mutable.set(posX, posY + 1 + climb, posZ);
            if (!isPassable(level, mutable, settings)) break;
            BlockPos up = new BlockPos(x, posY + climb, z);
            if (isWalkable(level, up, settings)) {
                boolean side1Clear = true, side2Clear = true;
                for (int h = 0; h <= climb + 1; h++) {
                    mutable.set(adj1X, posY + h, posZ);
                    if (!isPassable(level, mutable, settings)) { side1Clear = false; break; }
                }
                if (side1Clear) {
                    for (int h = 0; h <= climb + 1; h++) {
                        mutable.set(posX, posY + h, adj2Z);
                        if (!isPassable(level, mutable, settings)) { side2Clear = false; break; }
                    }
                }
                if (side1Clear && side2Clear) {
                    neighbors.add(new Neighbor(up, settings.getHorizontalMoveCost() * 1.4 + settings.getVerticalMoveCost() * climb));
                }
            }
        }
    }

    private static boolean isPassable(Level level, BlockPos pos, BotPathSettings settings) {
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) return false;
        return settings.isNotAvoided(state.getBlock());
    }

    private static boolean isWalkable(Level level, BlockPos pos, BotPathSettings settings) {
        if (!isPassable(level, pos, settings)) return false;
        if (!isPassable(level, pos.above(), settings)) return false;
        BlockState below = level.getBlockState(pos.below());
        if (below.getCollisionShape(level, pos).isEmpty()) return false;
        return settings.isNotAvoided(below.getBlock());
    }

    public static double closestHDistToBlock(BlockPos pos, Vec3 target) {
        double closestX = Math.max(pos.getX(), Math.min(target.x, pos.getX() + 1.0));
        double closestZ = Math.max(pos.getZ(), Math.min(target.z, pos.getZ() + 1.0));
        double dx = closestX - target.x;
        double dz = closestZ - target.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        while (node != null) {
            path.add(node.pos);
            node = node.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static List<BlockPos> smoothPath(List<BlockPos> path, Level level, BotPathSettings settings) {
        if (path.size() <= 2) return path;

        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(path.getFirst());

        int current = 0;
        while (current < path.size() - 1) {
            int farthest = current + 1;
            for (int i = path.size() - 1; i > current + 1; i--) {
                if (path.get(i).getY() != path.get(current).getY()) continue;
                if (hasLineOfSight(level, path.get(current), path.get(i), settings)) {
                    farthest = i;
                    break;
                }
            }
            smoothed.add(path.get(farthest));
            current = farthest;
        }

        return smoothed;
    }

    private static boolean hasLineOfSight(Level level, BlockPos from, BlockPos to, BotPathSettings settings) {
        int y = from.getY();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int sx = Integer.compare(dx, 0);
        int sz = Integer.compare(dz, 0);
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);

        int x = from.getX();
        int z = from.getZ();
        int error = adx - adz;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        while (x != to.getX() || z != to.getZ()) {
            int e2 = error * 2;
            if (e2 > -adz) {
                error -= adz;
                x += sx;
            }
            if (e2 < adx) {
                error += adx;
                z += sz;
            }
            mutable.set(x, y, z);
            if (!isWalkable(level, mutable, settings)) return false;
        }

        return true;
    }

    private record Node(BlockPos pos, double gCost, double fCost, Node parent) {
    }
}
