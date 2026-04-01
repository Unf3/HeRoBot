package hero.bane.herobot.bot;

import hero.bane.herobot.bot.connection.ServerPlayerInterface;
import hero.bane.herobot.bot.pathing.MovementHelper;
import hero.bane.herobot.bot.pathing.PathFinder;
import hero.bane.herobot.bot.pathing.PathSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BotPathing {
    private final BotPlayer bot;
    private final BotPlayerActionPack actionPack;
    private final CommandSourceStack source;
    private final PathSettings settings;

    private List<BlockPos> path;
    private Vec3 target;
    private int currentIndex;
    private int stuckTime;
    private Vec3 lastPos;
    private boolean done;
    private boolean retrying;
    private BlockPos retryTarget;
    private int retryNextIndex;
    private double originalNodeDistance;
    private boolean pendingRecalc;

    private final Entity targetEntity;
    private int recalcCd;
    private Vec3 lastRecalcTarget;

    private Set<BlockPos> debugger;

    public BotPathing(BotPlayer bot, List<BlockPos> path, Vec3 target,
                      CommandSourceStack source, PathSettings settings) {
        this.bot = bot;
        this.actionPack = ((ServerPlayerInterface) bot).getActionPack();
        this.path = path;
        this.target = target;
        this.source = source;
        this.settings = settings;
        this.targetEntity = null;
        this.currentIndex = 0;
        this.stuckTime = 0;
        this.lastPos = bot.position();
        this.lastRecalcTarget = target;
        initDebugNodes(path);
    }

    public BotPathing(BotPlayer bot, Entity targetEntity,
                      CommandSourceStack source, PathSettings settings) {
        this.bot = bot;
        this.actionPack = ((ServerPlayerInterface) bot).getActionPack();
        this.targetEntity = targetEntity;
        this.target = computeEntityTarget(targetEntity, settings, bot);
        this.source = source;
        this.settings = settings;
        this.currentIndex = 0;
        this.stuckTime = 0;
        this.lastPos = bot.position();
        this.lastRecalcTarget = target;

        this.path = PathFinder.findPath(bot.level(), bot.blockPosition(), target, settings, bot);
        if (this.path == null) {
            this.path = List.of();
        }
        initDebugNodes(this.path);
    }

    public void recalcPath() {
        if (done) return;
        Vec3 currentTarget = targetEntity != null ? targetEntity.position() : target;
        List<BlockPos> newPath = PathFinder.findPath(bot.level(), bot.blockPosition(), currentTarget, settings, bot);
        if (newPath != null && !newPath.isEmpty()) {
            this.path = newPath;
            this.currentIndex = 0;
            this.stuckTime = 0;
            initDebugNodes(newPath);
        }
    }

    public void requestRecalc() {
        if (!done) {
            pendingRecalc = true;
        }
    }

    public void tick() {
        if (done) return;

        if (pendingRecalc && bot.onGround()) {
            pendingRecalc = false;
            recalcPath();
        }

        Vec3 botPos = bot.position();

        if (!updateTarget(botPos)) return;
        advanceWaypoints(botPos);
        moveTowardGoal(botPos);
        tickDebugParticles();
        tickStuck(botPos);
    }

    // --- target management ---

    private boolean updateTarget(Vec3 botPos) {
        if (targetEntity != null) {
            if (targetEntity.isRemoved()) {
                stop();
                source.sendFailure(Component.literal(bot.getGameProfile().name() + " lost target entity"));
                return false;
            }
            target = computeEntityTarget(targetEntity, settings, bot);
        }

        if (isWithinTarget(botPos)) {
            if (targetEntity != null && !settings.isStopFollowing()) {
                actionPack.setForward(0);
                actionPack.setStrafing(0);
                actionPack.setSprinting(false);
                Vec3 eyePos = targetEntity.getEyePosition();
                actionPack.lookAt(eyePos);
                setVerticalLook(eyePos);
                return false;
            }
            stop();
            String msg = bot.getGameProfile().name() + " reached target"
                    + (targetEntity != null ? " entity" : " position");
            source.sendSuccess(() -> Component.literal(msg), false);
            return false;
        }

        if (targetEntity != null) {
            tickEntityRecalc();
        } else if (currentIndex >= path.size()) {
            tryRecalcPath();
        }

        return true;
    }

    private void tickEntityRecalc() {
        recalcCd--;
        if (recalcCd <= 0 || currentIndex >= path.size()) {
            tryRecalcPath();
            recalcCd = 20;
        }
    }

    private void tryRecalcPath() {
        if (lastRecalcTarget != null && lastRecalcTarget.distanceTo(target) <= 2.0
                && currentIndex < path.size()) {
            return;
        }
        List<BlockPos> newPath = PathFinder.findPath(bot.level(), bot.blockPosition(), target, settings, bot);
        if (newPath != null && !newPath.isEmpty()) {
            this.path = newPath;
            this.currentIndex = 0;
            this.lastRecalcTarget = target;
            initDebugNodes(newPath);
        }
    }

    // --- waypoint advancement ---

    private void advanceWaypoints(Vec3 botPos) {
        // advance past reached nodes
        while (currentIndex < path.size()) {
            BlockPos wp = path.get(currentIndex);
            double hDist = PathFinder.closestHDistToBlock(wp, botPos);
            double vDist = Math.abs(botPos.y - wp.getY());
            if (settings.isWithinNode(hDist, vDist)) {
                spawnDebugReached(wp);
                currentIndex++;
            } else {
                break;
            }
        }

        // skip ahead to a nearby future node
        if (currentIndex < path.size()) {
            for (int i = Math.min(path.size() - 1, currentIndex + 3); i > currentIndex; i--) {
                BlockPos futureWp = path.get(i);
                double hDist = PathFinder.closestHDistToBlock(futureWp, botPos);
                double vDist = Math.abs(botPos.y - futureWp.getY());
                if (settings.isWithinNode(hDist, vDist)) {
                    for (int j = currentIndex; j <= i; j++) {
                        spawnDebugReached(path.get(j));
                    }
                    currentIndex = i + 1;
                    break;
                }
            }
        }

        // allow skipping ahead to a lower node (descending) within 2 steps
        if (currentIndex < path.size()) {
            for (int i = currentIndex + 1; i < path.size() && (i - currentIndex) <= 2; i++) {
                BlockPos candidate = path.get(i);
                if (candidate.getY() < botPos.y - 0.5) {
                    double hDist = PathFinder.closestHDistToBlock(candidate, botPos);
                    if (hDist < 1.5) {
                        for (int j = currentIndex; j < i; j++) {
                            spawnDebugReached(path.get(j));
                        }
                        currentIndex = i;
                    }
                    break;
                }
            }
        }
    }

    // --- movement ---

    private void moveTowardGoal(Vec3 botPos) {
        if (currentIndex >= path.size()) {
            finalApproach(botPos);
            return;
        }

        BlockPos waypoint = path.get(currentIndex);

        if (isFullySubmerged(bot)) {
            swimToWaypoint(botPos, waypoint);
        } else if (isWading(bot)) {
            wadeToWaypoint(waypoint);
        } else {
            walkToWaypoint(botPos, waypoint);
        }
    }

    private void swimToWaypoint(Vec3 botPos, BlockPos waypoint) {
        Vec3 waypointMid = Vec3.atCenterOf(waypoint);
        actionPack.lookAt(waypointMid);
        setVerticalLook(waypointMid);
        actionPack.setForward(1);
        actionPack.setStrafing(0);
        actionPack.setSprinting(true);

        double dy = waypointMid.y - botPos.y;
        double hDist = Math.sqrt(horizontalDistanceSq(botPos, waypointMid));
        boolean moreVertical = Math.abs(dy) > hDist;

        if (dy > 0.5) {
            bot.setJumping(true);
            if (moreVertical) actionPack.setSneaking(false);
        } else if (dy < -0.5) {
            bot.setJumping(false);
            if (moreVertical) actionPack.setSneaking(true);
        } else {
            bot.setJumping(false);
            actionPack.setSneaking(false);
        }
    }

    private void wadeToWaypoint(BlockPos waypoint) {
        Vec3 waypointCenter = Vec3.atBottomCenterOf(waypoint);
        actionPack.setSneaking(false);
        bot.setJumping(false);
        actionPack.setForward(1);
        actionPack.setStrafing(0);
        actionPack.setSprinting(false);
        actionPack.lookAt(waypointCenter);
        setVerticalLook(waypointCenter);
    }

    private void walkToWaypoint(Vec3 botPos, BlockPos waypoint) {
        Vec3 waypointCenter = Vec3.atBottomCenterOf(waypoint);
        actionPack.setSneaking(false);
        bot.setJumping(false);

        Vec3 lookHorizontal;
        if (currentIndex + 1 < path.size()) {
            lookHorizontal = Vec3.atBottomCenterOf(path.get(currentIndex + 1));
        } else {
            lookHorizontal = targetEntity != null ? targetEntity.position() : target;
        }

        Vec3 verticalTarget = targetEntity != null ? targetEntity.getEyePosition() : waypointCenter;
        actionPack.lookAt(new Vec3(lookHorizontal.x, verticalTarget.y, lookHorizontal.z));
        setVerticalLook(verticalTarget);

        applyMoveType(false, botPos, waypoint);

        double dx = waypointCenter.x - botPos.x;
        double dz = waypointCenter.z - botPos.z;
        double toWaypointAngle = Math.atan2(-dx, dz);
        double facingAngle = Math.toRadians(bot.getYRot());
        double relativeAngle = toWaypointAngle - facingAngle;
        relativeAngle = Math.atan2(Math.sin(relativeAngle), Math.cos(relativeAngle));

        float forward = (float) Math.cos(relativeAngle);
        float strafe = (float) -Math.sin(relativeAngle);
        actionPack.setForward(forward > 0 ? forward : 0);
        actionPack.setStrafing(strafe);

        if (bot.onGround()) {
            if (waypoint.getY() > botPos.y + 0.5) {
                bot.jumpFromGround();
            } else if (isParkourJump(botPos, waypoint)) {
                actionPack.setSprinting(true);
                bot.jumpFromGround();
            } else if (waypoint.getY() < botPos.y - 0.5
                    && settings.getMoveType() == PathSettings.MoveType.SPRINT_JUMP) {
                bot.jumpFromGround();
            }
        }
    }

    private void finalApproach(Vec3 botPos) {
        Vec3 finalLook = targetEntity != null ? targetEntity.getEyePosition() : target;
        actionPack.lookAt(new Vec3(target.x, finalLook.y, target.z));
        setVerticalLook(finalLook);
        applyMoveType(true, botPos, null);
        actionPack.setStrafing(0);
    }

    // --- stuck detection ---

    private void tickStuck(Vec3 botPos) {
        if (retrying && currentIndex > retryNextIndex) {
            retrying = false;
            retryTarget = null;
            stuckTime = 0;
            settings.setNodeHorizontalDistance(originalNodeDistance);
        }

        if (horizontalDistanceSq(botPos, lastPos) < 0.001) {
            stuckTime++;
            if (stuckTime == 50) {
                actionPack.start(BotPlayerActionPack.ActionType.JUMP, BotPlayerActionPack.Action.once());
            }
            if (stuckTime > 100) {
                if (retrying) {
                    retryTarget = null;
                    settings.setNodeHorizontalDistance(originalNodeDistance);
                    stop();
                    source.sendFailure(Component.literal(bot.getGameProfile().name() + " got stuck while pathing"));
                    return;
                }
                int prevIndex = Math.max(0, currentIndex - 1);
                retryTarget = path.get(prevIndex);
                retryNextIndex = currentIndex;
                currentIndex = prevIndex;
                retrying = true;
                stuckTime = 0;
                originalNodeDistance = settings.getNodeHorizontalDistance();
                settings.setNodeHorizontalDistance(originalNodeDistance / 2.0);
            }
        } else {
            stuckTime = 0;
        }
        lastPos = botPos;
    }

    private boolean isParkourJump(Vec3 botPos, BlockPos waypoint) {
        int bx = (int) Math.floor(botPos.x);
        int bz = (int) Math.floor(botPos.z);
        int by = (int) Math.floor(botPos.y);
        int wx = waypoint.getX();
        int wz = waypoint.getZ();
        int wy = waypoint.getY();

        // only horizontal or ascending parkour
        if (wy < by) return false;

        int dx = wx - bx;
        int dz = wz - bz;
        int dist = Math.abs(dx) + Math.abs(dz);

        // gap jump is 2-4 blocks cardinal distance
        if (dist < 2 || dist > 4) return false;
        // must be cardinal (one axis is 0)
        if (dx != 0 && dz != 0) return false;

        // check that there's actually a gap (no ground under the block in front)
        int stepDx = Integer.compare(dx, 0);
        int stepDz = Integer.compare(dz, 0);
        return !MovementHelper.canWalkOn(bot.level(), bx + stepDx, by - 1, bz + stepDz, settings);
    }

    private static boolean isFullySubmerged(BotPlayer bot) {
        int feetX = (int) Math.floor(bot.getX());
        int feetY = (int) Math.floor(bot.getY());
        int feetZ = (int) Math.floor(bot.getZ());
        int headY = (int) Math.floor(bot.getY() + bot.getEyeHeight());
        return isSwimmableBlock(bot, feetX, feetY, feetZ)
                && isSwimmableBlock(bot, feetX, headY, feetZ);
    }

    private static boolean isWading(BotPlayer bot) {
        int feetX = (int) Math.floor(bot.getX());
        int feetY = (int) Math.floor(bot.getY());
        int feetZ = (int) Math.floor(bot.getZ());
        return isSwimmableBlock(bot, feetX, feetY, feetZ);
    }

    private static boolean isSwimmableBlock(BotPlayer bot, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = bot.level().getBlockState(pos);
        if (MovementHelper.isWater(bot.level(), x, y, z)) return true;
        if (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)) return true;
        return !state.getFluidState().isEmpty() && state.getCollisionShape(bot.level(), pos).isEmpty();
    }

    private void setVerticalLook(Vec3 lookTarget) {
        Vec3 botEye = bot.getEyePosition();
        double dx = lookTarget.x - botEye.x;
        double dy = lookTarget.y - botEye.y;
        double dz = lookTarget.z - botEye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, hDist));
        bot.setXRot(pitch);
    }

    private boolean shouldAllowJump(double hDistSqToTarget, BlockPos currentWaypoint) {
        if (hDistSqToTarget <= 100.0) {
            return false;
        }

        if (currentWaypoint != null && currentIndex + 1 < path.size()) {
            BlockPos nextWaypoint = path.get(currentIndex + 1);
            return nextWaypoint.getY() == currentWaypoint.getY();
        }

        return true;
    }

    private void applyMoveType(boolean finalApproach, Vec3 botPos, BlockPos currentWaypoint) {
        if (finalApproach) {
            actionPack.setForward(1);
            actionPack.setSprinting(false);
            return;
        }

        double hDistSq = horizontalDistanceSq(botPos, target);
        boolean nearTarget = hDistSq <= 25.0;
        boolean closeRange = hDistSq <= 100.0; // within 10 blocks

        switch (settings.getMoveType()) {
            case WALK -> {
                actionPack.setForward(1);
                actionPack.setSprinting(false);
                actionPack.autoJump = false;
            }
            case SPRINT -> {
                actionPack.setForward(1);
                actionPack.setSprinting(!nearTarget);
                actionPack.autoJump = false;
            }
            case SPRINT_JUMP -> {
                actionPack.setForward(1);
                actionPack.setSprinting(!nearTarget);
                if (closeRange) {
                    // within 10 blocks: stop spam jumping, let autojump handle obstacles
                    actionPack.autoJump = true;
                } else {
                    actionPack.autoJump = false;
                    if (bot.onGround() && shouldAllowJump(hDistSq, currentWaypoint)) {
                        bot.jumpFromGround();
                    }
                }
            }
        }
    }

    private boolean isWithinTarget(Vec3 botPos) {
        double hDist = Math.sqrt(horizontalDistanceSq(botPos, target));
        double vDist = Math.abs(botPos.y - target.y);
        return settings.isWithinTarget(hDist, vDist);
    }

    private static Vec3 computeEntityTarget(Entity entity, PathSettings settings, BotPlayer bot) {
        if (settings.getMaxVerticalDistance() < 0) {
            BlockPos start = entity.blockPosition();
            var level = bot.level();
            for (int dy = 0; dy <= 64; dy++) {
                BlockPos check = start.below(dy);
                BlockState state = level.getBlockState(check);
                if (!state.getCollisionShape(level, check).isEmpty()) {
                    return Vec3.atBottomCenterOf(check.above());
                }
            }
        }
        return entity.position();
    }

    private void initDebugNodes(List<BlockPos> pathNodes) {
        if (!settings.isDebug()) {
            debugger = null;
            return;
        }
        if (debugger == null) {
            debugger = new LinkedHashSet<>();
        } else {
            debugger.clear();
        }
        if (pathNodes != null) {
            debugger.addAll(pathNodes);
        }
    }

    private void tickDebugParticles() {
        if (debugger == null || debugger.isEmpty()) return;
        ServerLevel serverLevel = bot.level();
        for (BlockPos pos : debugger) {
            serverLevel.sendParticles(ParticleTypes.WAX_OFF, pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
        if (retryTarget != null) {
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, retryTarget.getX() + 0.5, retryTarget.getY() + 0.25, retryTarget.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
    }

    private void spawnDebugReached(BlockPos pos) {
        if (debugger == null || !debugger.remove(pos)) return;
        ServerLevel serverLevel = bot.level();
        serverLevel.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 5, 0.2, 0.5, 0.2, 0);
    }

    public void stop() {
        if (done) return;
        done = true;
        if (debugger != null) debugger.clear();
        actionPack.setForward(0);
        actionPack.setStrafing(0);
        actionPack.setSprinting(false);
        actionPack.setSneaking(false);
        actionPack.autoJump = false;
        bot.setJumping(false);
    }

    public boolean isDone() {
        return done;
    }

    public Entity getTargetEntity() {
        return targetEntity;
    }

    public Vec3 getTarget() {
        return target;
    }

    public boolean isEntityMode() {
        return targetEntity != null;
    }

    private static double horizontalDistanceSq(Vec3 botPos, Vec3 target) {
        double dx = botPos.x - target.x;
        double dz = botPos.z - target.z;
        return dx * dx + dz * dz;
    }
}
