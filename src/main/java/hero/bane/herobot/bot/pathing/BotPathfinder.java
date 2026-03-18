package hero.bane.herobot.bot.pathing;

import net.minecraft.core.BlockPos;
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

    private static int getMaxFallDistance(Player player) {
        if (player instanceof ServerPlayer sp && sp.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            return noFall;
        }
        return (int) player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
    }

    public static List<BlockPos> findPath(Level world, BlockPos start, Vec3 target, BotPathSettings settings, Player player) {
        return findPath(world, start, target, settings, player, 50000);
    }

    public static List<BlockPos> findPath(Level world, BlockPos start, Vec3 target, BotPathSettings settings, Player player, int maxIterations) {
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<Long, Double> bestCost = new HashMap<>();

        Node startNode = new Node(start, 0, heuristic(start, target), null);
        openSet.add(startNode);
        bestCost.put(start.asLong(), 0.0);

        while (!openSet.isEmpty() && maxIterations-- > 0) {
            Node current = openSet.poll();

            if (isWithinGoal(current.pos, target, settings)) {
                return reconstructPath(current);
            }

            if (current.gCost > bestCost.getOrDefault(current.pos.asLong(), Double.MAX_VALUE)) {
                continue;
            }

            for (Neighbor neighbor : getNeighbors(world, current.pos, settings, player)) {
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
        double hDist = closestHorizontalDistToBlock(pos, target);
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

    private static List<Neighbor> getNeighbors(Level level, BlockPos pos, BotPathSettings settings, Player player) {
        List<Neighbor> neighbors = new ArrayList<>();
        int s = settings.getNodeSpacing();
        int[][] cardinal = {{s, 0}, {-s, 0}, {0, s}, {0, -s}};
        int[][] diagonal = {{s, s}, {s, -s}, {-s, s}, {-s, -s}};

        for (int[] off : cardinal) {
            addCardinalNeighbors(level, pos, off[0], off[1], neighbors, settings, player);
        }
        for (int[] off : diagonal) {
            addDiagonalNeighbor(level, pos, off[0], off[1], neighbors, settings);
        }
        return neighbors;
    }

    private static void addCardinalNeighbors(Level level, BlockPos pos, int dx, int dz, List<Neighbor> neighbors, BotPathSettings settings, Player player) {
        int x = pos.getX() + dx;
        int z = pos.getZ() + dz;

        BlockPos sameLevel = new BlockPos(x, pos.getY(), z);
        if (isWalkable(level, sameLevel, settings)) {
            neighbors.add(new Neighbor(sameLevel, settings.getHorizontalMoveCost()));
        }

        BlockPos up = new BlockPos(x, pos.getY() + 1, z);
        if (isWalkable(level, up, settings) && isPassable(level, pos.above(2), settings)) {
            neighbors.add(new Neighbor(up, settings.getVerticalMoveCost()));
        }

        if (!isPassable(level, new BlockPos(x, pos.getY(), z), settings)
                || !isPassable(level, new BlockPos(x, pos.getY() + 1, z), settings)) {
            return;
        }
        for (int drop = 1; drop <= getMaxFallDistance(player); drop++) {
            BlockPos landing = new BlockPos(x, pos.getY() - drop, z);
            if (isWalkable(level, landing, settings)) {
                neighbors.add(new Neighbor(landing, settings.getHorizontalMoveCost() + drop * 0.3));
                return;
            }
            if (!isPassable(level, landing, settings)) return;
        }
    }

    private static void addDiagonalNeighbor(Level level, BlockPos pos, int dx, int dz, List<Neighbor> neighbors, BotPathSettings settings) {
        int x = pos.getX() + dx;
        int z = pos.getZ() + dz;

        BlockPos target = new BlockPos(x, pos.getY(), z);
        if (!isWalkable(level, target, settings)) return;

        BlockPos adj1 = new BlockPos(pos.getX() + dx, pos.getY(), pos.getZ());
        BlockPos adj2 = new BlockPos(pos.getX(), pos.getY(), pos.getZ() + dz);
        if (isPassable(level, adj1, settings) && isPassable(level, adj1.above(), settings)
                && isPassable(level, adj2, settings) && isPassable(level, adj2.above(), settings)) {
            neighbors.add(new Neighbor(target, settings.getHorizontalMoveCost() * 1.414));
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

    public static double closestHorizontalDistToBlock(BlockPos pos, Vec3 target) {
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

    private static class Node {
        final BlockPos pos;
        final double gCost;
        final double fCost;
        final Node parent;

        Node(BlockPos pos, double gCost, double fCost, Node parent) {
            this.pos = pos;
            this.gCost = gCost;
            this.fCost = fCost;
            this.parent = parent;
        }
    }
}
