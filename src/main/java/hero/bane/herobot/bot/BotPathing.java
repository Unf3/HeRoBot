package hero.bane.herobot.bot;

import hero.bane.herobot.bot.connection.ServerPlayerInterface;
import hero.bane.herobot.bot.pathing.BotPathSettings;
import hero.bane.herobot.bot.pathing.BotPathfinder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class BotPathing {
    private final BotPlayer bot;
    private final CommandSourceStack source;
    private final BotPathSettings settings;

    private List<BlockPos> path;
    private Vec3 target;
    private int currentIndex;
    private int stuckTime;
    private Vec3 lastPos;
    private boolean done;

    private final Entity targetEntity;
    private int recalcCd;
    private Vec3 lastRecalcTarget;

    // 45 strafe stuff
    private boolean fortyFiveStrafe;
    private float preJumpYaw;

    public BotPathing(BotPlayer bot, List<BlockPos> path, Vec3 target,
                      CommandSourceStack source, BotPathSettings settings) {
        this.bot = bot;
        this.path = path;
        this.target = target;
        this.source = source;
        this.settings = settings;
        this.targetEntity = null;
        this.currentIndex = 0;
        this.stuckTime = 0;
        this.lastPos = bot.position();
    }

    public BotPathing(BotPlayer bot, Entity targetEntity,
                      CommandSourceStack source, BotPathSettings settings) {
        this.bot = bot;
        this.targetEntity = targetEntity;
        this.target = computeEntityTarget(targetEntity, settings, bot);
        this.source = source;
        this.settings = settings;
        this.currentIndex = 0;
        this.stuckTime = 0;
        this.lastPos = bot.position();
        this.lastRecalcTarget = target;

        this.path = BotPathfinder.findPath(bot.level(), bot.blockPosition(), target, settings, bot);
        if (this.path == null) {
            this.path = List.of();
        }
    }

    public void recalcPath() {
        if (done) return;
        Vec3 currentTarget = targetEntity != null ? targetEntity.position() : target;
        List<BlockPos> newPath = BotPathfinder.findPath(bot.level(), bot.blockPosition(), currentTarget, settings, bot);
        if (newPath != null && !newPath.isEmpty()) {
            this.path = newPath;
            this.currentIndex = 0;
            this.stuckTime = 0;
        }
    }

    public void tick() {
        if (done) return;

        Vec3 botPos = bot.position();

        if (targetEntity != null) {
            if (targetEntity.isRemoved()) {
                stop();
                source.sendFailure(Component.literal(bot.getGameProfile().name() + " lost target entity"));
                return;
            }
            target = computeEntityTarget(targetEntity, settings, bot);

            if (isWithinTarget(botPos)) {
                if (settings.isStopFollowing()) {
                    stop();
                    source.sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " reached target entity"), false);
                } else {
                    BotPlayerActionPack ap = ((ServerPlayerInterface) bot).getActionPack();
                    ap.setForward(0);
                    ap.setSprinting(false);
                    Vec3 eyePos = targetEntity.getEyePosition();
                    ap.lookAt(eyePos);
                    setVerticalLook(eyePos);
                }
                return;
            }

            recalcCd--;
            if (recalcCd <= 0 || currentIndex >= path.size()) {
                if (lastRecalcTarget == null || lastRecalcTarget.distanceTo(target) > 2.0
                        || currentIndex >= path.size()) {
                    List<BlockPos> newPath = BotPathfinder.findPath(bot.level(), bot.blockPosition(), target, settings, bot);
                    if (newPath != null && !newPath.isEmpty()) {
                        this.path = newPath;
                        this.currentIndex = 0;
                        this.lastRecalcTarget = target;
                    }
                }
                recalcCd = 20;
            }
        } else {
            if (isWithinTarget(botPos)) {
                stop();
                source.sendSuccess(() -> Component.literal(bot.getGameProfile().name() + " reached target position"), false);
                return;
            }
        }

        while (currentIndex < path.size()) {
            BlockPos wp = path.get(currentIndex);
            double hDist = BotPathfinder.closestHorizontalDistToBlock(wp, botPos);
            double vDist = Math.abs(botPos.y - wp.getY());
            if (settings.isWithinNode(hDist, vDist)) {
                currentIndex++;
            } else {
                break;
            }
        }

        if (currentIndex < path.size()) {
            BlockPos wp = path.get(currentIndex);
            if (botPos.y + 0.1 < wp.getY()) {
                int maxSkip = settings.getMaxDownwardSkip();
                int bestIndex = currentIndex;
                for (int i = currentIndex + 1; i < path.size() && (i - currentIndex) <= maxSkip; i++) {
                    BlockPos candidate = path.get(i);
                    if (candidate.getY() <= botPos.y + 0.5) {
                        bestIndex = i;
                        break;
                    }
                    if (candidate.getY() < wp.getY()) {
                        bestIndex = i;
                    }
                }
                currentIndex = bestIndex;
            }
        }

        BotPlayerActionPack ap = ((ServerPlayerInterface) bot).getActionPack();

        if (fortyFiveStrafe && bot.onGround()) {
            fortyFiveStrafe = false;
            ap.setStrafing(0);
            bot.setYRot(preJumpYaw);
        }

        if (currentIndex < path.size()) {
            BlockPos waypoint = path.get(currentIndex);
            Vec3 waypointCenter = Vec3.atBottomCenterOf(waypoint);

            Vec3 verticalTarget = targetEntity != null ? targetEntity.getEyePosition() : waypointCenter;
            if (!fortyFiveStrafe) {
                ap.lookAt(new Vec3(waypointCenter.x, verticalTarget.y, waypointCenter.z));
            }
            setVerticalLook(verticalTarget);

            applyMoveType(ap, false, botPos, waypoint);

            if (waypoint.getY() > botPos.y + 0.5 && bot.onGround()) {
                bot.jumpFromGround();
            }
        } else {
            Vec3 finalLook = targetEntity != null ? targetEntity.getEyePosition() : target;
            ap.lookAt(new Vec3(target.x, finalLook.y, target.z));
            setVerticalLook(finalLook);
            applyMoveType(ap, true, botPos, null);
        }

        if (botPos.distanceToSqr(lastPos) < 0.001) {
            stuckTime++;
            if (stuckTime > 100) {
                stop();
                source.sendFailure(Component.literal(bot.getGameProfile().name() + " got stuck while pathing"));
                return;
            }
        } else {
            stuckTime = 0;
        }
        lastPos = botPos;
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

    private boolean shouldAllowJump(Vec3 botPos, BlockPos currentWaypoint) {
        double distToTarget = Math.sqrt(horizontalDistanceSq(botPos, target));
        if (distToTarget <= 10.0) {
            return false;
        }

        if (currentWaypoint != null && currentIndex + 1 < path.size()) {
            BlockPos nextWaypoint = path.get(currentIndex + 1);
            return nextWaypoint.getY() == currentWaypoint.getY();
        }

        return true;
    }

    private void applyMoveType(BotPlayerActionPack ap, boolean finalApproach, Vec3 botPos, BlockPos currentWaypoint) {
        if (finalApproach) {
            ap.setForward(1);
            ap.setSprinting(false);
            return;
        }

        boolean nearTarget = horizontalDistanceSq(botPos, target) <= 25.0;

        switch (settings.getMoveType()) {
            case WALK -> {
                ap.setForward(1);
                ap.setSprinting(false);
            }
            case SPRINT -> {
                ap.setForward(1);
                ap.setSprinting(!nearTarget);
            }
            case SPRINT_JUMP -> {
                ap.setForward(1);
                ap.setSprinting(!nearTarget);
                if (!nearTarget && bot.onGround() && shouldAllowJump(botPos, currentWaypoint)) {
                    bot.jumpFromGround();
                }
            }
            case SPRINT_45 -> {
                ap.setForward(1);
                ap.setSprinting(!nearTarget);
                if (!nearTarget && bot.onGround() && shouldAllowJump(botPos, currentWaypoint)) {
                    preJumpYaw = bot.getYRot();

                    double yawRad = Math.toRadians(preJumpYaw);
                    double forwardX = -Math.sin(yawRad);
                    double forwardZ = Math.cos(yawRad);
                    double toTargetX = target.x - botPos.x;
                    double toTargetZ = target.z - botPos.z;
                    double cross = forwardX * toTargetZ - forwardZ * toTargetX;
                    boolean strafe45Left = cross > 0;
                    float yawOffset = strafe45Left ? 45.0f : -45.0f;

                    bot.jumpFromGround();
                    fortyFiveStrafe = true;

                    bot.setYRot(preJumpYaw + yawOffset);
                    ap.setStrafing(strafe45Left ? 1 : -1);
                }
            }
        }
    }

    private boolean isWithinTarget(Vec3 botPos) {
        double hDist = Math.sqrt(horizontalDistanceSq(botPos, target));
        double vDist = Math.abs(botPos.y - target.y);
        return settings.isWithinTarget(hDist, vDist);
    }

    private static Vec3 computeEntityTarget(Entity entity, BotPathSettings settings, BotPlayer bot) {
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

    public void stop() {
        if (done) return;
        done = true;
        BotPlayerActionPack ap = ((ServerPlayerInterface) bot).getActionPack();
        ap.setForward(0);
        ap.setSprinting(false);
        ap.setStrafing(0);
        fortyFiveStrafe = false;
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
