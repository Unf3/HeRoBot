package hero.bane.herobot.bot.pathing;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class BotPathSettings {

    public enum MoveType {
        WALK, SPRINT, SPRINT_JUMP, SPRINT_45;

        public String displayName() {
            return switch (this) {
                case WALK -> "walk";
                case SPRINT -> "sprint";
                case SPRINT_JUMP -> "sprint-jump";
                case SPRINT_45 -> "45 strafe jumps";
            };
        }
    }

    private final Set<Block> avoidedBlocks = new LinkedHashSet<>();
    private MoveType moveType = MoveType.SPRINT;
    private double maxHorizontalDistance = 1.0;
    private double maxVerticalDistance = 2.0;
    private double nodeHorizontalDistance = 0.5;
    private double nodeVerticalDistance = 1.0;
    private int maxDownwardSkip = 2;
    private int nodeSpacing = 2;
    private boolean stopFollowing = true;
    private double horizontalMoveCost = 1.0;
    private double verticalMoveCost = 1.5;

    public BotPathSettings() {
        avoidedBlocks.add(Blocks.WATER);
        avoidedBlocks.add(Blocks.LAVA);
        avoidedBlocks.add(Blocks.CACTUS);
        avoidedBlocks.add(Blocks.SWEET_BERRY_BUSH);
        avoidedBlocks.add(Blocks.COBWEB);
        avoidedBlocks.add(Blocks.MAGMA_BLOCK);
        avoidedBlocks.add(Blocks.FIRE);
        avoidedBlocks.add(Blocks.SOUL_FIRE);
        avoidedBlocks.add(Blocks.RAIL);
        avoidedBlocks.add(Blocks.POWERED_RAIL);
        avoidedBlocks.add(Blocks.DETECTOR_RAIL);
        avoidedBlocks.add(Blocks.ACTIVATOR_RAIL);
    }

    public void copyFrom(BotPathSettings other) {
        this.avoidedBlocks.clear();
        this.avoidedBlocks.addAll(other.avoidedBlocks);
        this.moveType = other.moveType;
        this.maxHorizontalDistance = other.maxHorizontalDistance;
        this.maxVerticalDistance = other.maxVerticalDistance;
        this.nodeHorizontalDistance = other.nodeHorizontalDistance;
        this.nodeVerticalDistance = other.nodeVerticalDistance;
        this.maxDownwardSkip = other.maxDownwardSkip;
        this.nodeSpacing = other.nodeSpacing;
        this.stopFollowing = other.stopFollowing;
        this.horizontalMoveCost = other.horizontalMoveCost;
        this.verticalMoveCost = other.verticalMoveCost;
    }

    public boolean isNotAvoided(Block block) {
        return !avoidedBlocks.contains(block);
    }

    public Set<Block> getAvoidedBlocks() {
        return Collections.unmodifiableSet(avoidedBlocks);
    }

    public void addAvoidedBlock(Block block) {
        avoidedBlocks.add(block);
    }

    public boolean removeAvoidedBlock(Block block) {
        return avoidedBlocks.remove(block);
    }

    public void clearAvoidedBlocks() {
        avoidedBlocks.clear();
    }

    public MoveType getMoveType() {
        return moveType;
    }

    public void setMoveType(MoveType moveType) {
        this.moveType = moveType;
    }

    public double getMaxHorizontalDistance() {
        return maxHorizontalDistance;
    }

    public void setMaxHorizontalDistance(double value) {
        if (value <= 0) return;
        this.maxHorizontalDistance = value;
    }

    public double getMaxVerticalDistance() {
        return maxVerticalDistance;
    }

    public void setMaxVerticalDistance(double value) {
        this.maxVerticalDistance = value;
    }

    public double getNodeHorizontalDistance() {
        return nodeHorizontalDistance;
    }

    public void setNodeHorizontalDistance(double value) {
        if (value <= 0) return;
        this.nodeHorizontalDistance = value;
    }

    public double getNodeVerticalDistance() {
        return nodeVerticalDistance;
    }

    public void setNodeVerticalDistance(double value) {
        this.nodeVerticalDistance = value;
    }

    public int getMaxDownwardSkip() {
        return maxDownwardSkip;
    }

    public void setMaxDownwardSkip(int value) {
        this.maxDownwardSkip = Math.max(0, value);
    }

    public int getNodeSpacing() {
        return nodeSpacing;
    }

    public void setNodeSpacing(int value) {
        if (value < 1) return;
        this.nodeSpacing = value;
    }

    public boolean isStopFollowing() {
        return stopFollowing;
    }

    public void setStopFollowing(boolean value) {
        this.stopFollowing = value;
    }

    public double getHorizontalMoveCost() {
        return horizontalMoveCost;
    }

    public void setHorizontalMoveCost(double value) {
        if (value <= 0) return;
        this.horizontalMoveCost = value;
    }

    public double getVerticalMoveCost() {
        return verticalMoveCost;
    }

    public void setVerticalMoveCost(double value) {
        if (value <= 0) return;
        this.verticalMoveCost = value;
    }

    public boolean isWithinTarget(double hDist, double vDist) {
        boolean hOk = hDist <= maxHorizontalDistance;
        boolean vOk = maxVerticalDistance < 0 || vDist <= maxVerticalDistance;
        return hOk && vOk;
    }

    public boolean isWithinNode(double hDist, double vDist) {
        boolean hOk = hDist <= nodeHorizontalDistance;
        boolean vOk = nodeVerticalDistance < 0 || vDist <= nodeVerticalDistance;
        return hOk && vOk;
    }
}
