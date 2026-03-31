package hero.bane.herobot.bot.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class PathFinder {

    private static final int NO_FALL = 256;
    private static final Moves[] ALL_MOVES = Moves.values();

    private static int getMaxFallDistance(Player player) {
        if (player instanceof ServerPlayer sp && sp.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            return NO_FALL;
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

    public static List<BlockPos> findPath(Level world, BlockPos start, Vec3 target, PathSettings settings, Player player) {
        return findPath(world, start, target, settings, player, 50000);
    }

    public static List<BlockPos> findPath(Level world, BlockPos start, Vec3 target, PathSettings settings, Player player, int maxIterations) {
        int maxJump = getMaxJumpHeight(player);
        int maxFall = getMaxFallDistance(player);

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<Long, Double> bestCost = new HashMap<>();
        MoveResult result = new MoveResult();

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

            int cx = current.pos.getX();
            int cy = current.pos.getY();
            int cz = current.pos.getZ();

            for (Moves move : ALL_MOVES) {
                result.reset();
                move.apply(world, cx, cy, cz, settings, maxJump, maxFall, result);

                if (result.cost >= MoveResult.COST_INF) continue;

                double tentativeG = current.gCost + result.cost;
                long key = BlockPos.asLong(result.x, result.y, result.z);

                if (tentativeG < bestCost.getOrDefault(key, Double.MAX_VALUE)) {
                    bestCost.put(key, tentativeG);
                    BlockPos neighborPos = new BlockPos(result.x, result.y, result.z);
                    openSet.add(new Node(neighborPos, tentativeG, tentativeG + heuristic(neighborPos, target), current));
                }
            }
        }

        return null;
    }

    private static boolean isWithinGoal(BlockPos pos, Vec3 target, PathSettings settings) {
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

    public static double closestHDistToBlock(BlockPos pos, Vec3 target) {
        double closestX = Math.clamp(target.x, pos.getX(), pos.getX() + 1.0);
        double closestZ = Math.clamp(target.z, pos.getZ(), pos.getZ() + 1.0);
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

    private static List<BlockPos> smoothPath(List<BlockPos> path, Level level, PathSettings settings) {
        if (path.size() <= 2) return path;

        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(path.getFirst());

        int current = 0;
        while (current < path.size() - 1) {
            // check if the next segment is a parkour jump (gap between nodes)
            if (isParkourSegment(path, current, level, settings)) {
                // preserve the parkour landing node exactly - don't smooth across gaps
                smoothed.add(path.get(current + 1));
                current = current + 1;
                continue;
            }

            int farthest = current + 1;
            for (int i = path.size() - 1; i > current + 1; i--) {
                if (path.get(i).getY() != path.get(current).getY()) continue;
                // don't smooth past a parkour segment
                if (containsParkourSegment(path, current + 1, i, level, settings)) continue;
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

    private static boolean isParkourSegment(List<BlockPos> path, int index, Level level, PathSettings settings) {
        if (index + 1 >= path.size()) return false;
        BlockPos from = path.get(index);
        BlockPos to = path.get(index + 1);
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        int dist = dx + dz;
        // parkour = cardinal 2-4 block horizontal gap
        if (dist < 2 || dist > 4) return false;
        if (dx != 0 && dz != 0) return false;
        // verify there's actually a gap (no ground under the first step)
        int stepX = Integer.compare(to.getX() - from.getX(), 0);
        int stepZ = Integer.compare(to.getZ() - from.getZ(), 0);
        return !MovementHelper.canWalkOn(level, from.getX() + stepX, from.getY() - 1, from.getZ() + stepZ, settings);
    }

    private static boolean containsParkourSegment(List<BlockPos> path, int startIdx, int endIdx, Level level, PathSettings settings) {
        for (int i = startIdx; i < endIdx; i++) {
            if (isParkourSegment(path, i, level, settings)) return true;
        }
        return false;
    }

    private static boolean hasLineOfSight(Level level, BlockPos from, BlockPos to, PathSettings settings) {
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
            if (!MovementHelper.isWalkable(level, x, y, z, settings)) return false;
        }

        return true;
    }

    private record Node(BlockPos pos, double gCost, double fCost, Node parent) {
    }
}
