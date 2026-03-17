package hero.bane.herobot.bot;

import com.mojang.authlib.GameProfile;
import hero.bane.herobot.HeroBotSettings;
import hero.bane.herobot.bot.connection.BotClientConnection;
import hero.bane.herobot.bot.connection.ServerPlayerInterface;
import hero.bane.herobot.mixin.LivingEntityAccessor;
import hero.bane.herobot.mixin.ServerPlayerAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("EntityConstructor")
public class BotPlayer extends ServerPlayer {
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static final Set<String> spawning = new HashSet<>();

    public int ping = 0;

    //thought this was a clean way to do this
    private record DelayedKnockback(long tick, double strength, double x, double z) {
    }

    private final List<DelayedKnockback> pendingKnockbacks = new ArrayList<>();

    private record DelayedExplosionKB(long tick, Vec3 explosionKB) {
    }

    private final List<DelayedExplosionKB> pendingExplosionKB = new ArrayList<>();

    public Runnable fixStartingPosition = () -> {
    };
    public boolean isAShadow;

    public Vec3 spawnPos;
    public double spawnYaw;

    // Returns 1 if it was successful, 0 if it couldn't spawn
    public static int createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying) {
        //prolly half of that crap is not necessary, but it works
        ServerLevel worldIn = server.getLevel(dimensionId);
        server.services().nameToIdCache().resolveOfflineUsers(false);
        GameProfile gameprofile;

        UUID uuid = OldUsersConverter.convertMobOwnerIfNecessary(server, username);
        //NameAndId res = server.services().nameToIdCache().get(username).orElseThrow(); //findByName  .orElse(null)
        if (uuid == null && HeroBotSettings.allowSpawningOfflinePlayers) {
            server.services().nameToIdCache().resolveOfflineUsers(server.isDedicatedServer() && server.usesAuthentication());
            uuid = UUIDUtil.createOfflinePlayerUUID(username);
        }
        if (uuid == null) {
            return 0; // no uuid, no player
        }
        gameprofile = new GameProfile(uuid, username);


        //GameProfile finalGP = gameprofile;

        // We need to mark this player as spawning so that we do not
        // try to spawn another player with the name while the profile
        // is being fetched - preventing multiple players spawning
        String name = gameprofile.name();
        spawning.add(name);

        fetchGameProfile(server, gameprofile.id()).whenCompleteAsync((p, t) -> {
            // Always remove the name, even if exception occurs
            spawning.remove(name);
            if (t != null) {
                return;
            }

            GameProfile current;
            if (p.name().isEmpty()) {
                current = gameprofile;
            } else {
                current = p;
            }

            BotPlayer instance = new BotPlayer(server, worldIn, current, ClientInformation.createDefault(), false);
            instance.fixStartingPosition = () -> instance.snapTo(pos.x, pos.y, pos.z, (float) yaw, (float) pitch);
            server.getPlayerList().placeNewPlayer(new BotClientConnection(PacketFlow.SERVERBOUND), instance, new CommonListenerCookie(current, 0, instance.clientInformation(), false));
            loadPlayerData(instance);
            instance.stopRiding(); // otherwise the created bot player will be on the vehicle
            assert worldIn != null;
            instance.teleportTo(worldIn, pos.x, pos.y, pos.z, Set.of(), (float) yaw, (float) pitch, true);
            instance.setHealth(20.0F);
            instance.unsetRemoved();
            Objects.requireNonNull(instance.getAttribute(Attributes.STEP_HEIGHT)).setBaseValue(0.6F);
            instance.gameMode.changeGameModeForPlayer(gamemode);
            instance.spawnPos = pos;
            instance.setRespawnPosition(
                    new ServerPlayer.RespawnConfig(
                            LevelData.RespawnData.of(
                                    dimensionId,
                                    BlockPos.containing(pos),
                                    (float) yaw,
                                    (float) pitch
                            ),
                            true
                    ),
                    false
            );
            instance.ping = 0;
            instance.spawnYaw = yaw;
            server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)), dimensionId);//instance.dimension);
            server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(instance), dimensionId);//instance.dimension);
            instance.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // show all model layers (incl. capes)
            instance.getAbilities().flying = flying;
        }, server);
        return 1;
    }

    private static CompletableFuture<GameProfile> fetchGameProfile(MinecraftServer server, final UUID name) {
        final ResolvableProfile resolvableProfile = ResolvableProfile.createUnresolved(name);
        return resolvableProfile.resolveProfile(server.services().profileResolver());
    }

    private static void loadPlayerData(BotPlayer player) {
        player.level().getServer().getPlayerList()
                .loadPlayerData(player.nameAndId())
                .map(tag -> TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        player.registryAccess(),
                        tag
                ))
                .ifPresent(valueInput -> {
                    player.load(valueInput);
                    player.loadAndSpawnEnderPearls(valueInput);
                    player.loadAndSpawnParentVehicle(valueInput);
                });
    }

    /*
    public static BotPlayer createShadow(MinecraftServer server, ServerPlayer player) {
        player.connection.disconnect(Component.translatable("multiplayer.disconnect.duplicate_login"));
        ServerLevel worldIn = player.level();//.getWorld(player.dimension);
        GameProfile gameprofile = player.getGameProfile();
        BotPlayer playerShadow = new BotPlayer(server, worldIn, gameprofile, player.clientInformation(), true);
        playerShadow.setChatSession(player.getChatSession());
        server.getPlayerList().placeNewPlayer(new BotClientConnection(PacketFlow.SERVERBOUND), playerShadow, new CommonListenerCookie(gameprofile, 0, player.clientInformation(), true));
        loadPlayerData(playerShadow);

        playerShadow.setHealth(player.getHealth());
        playerShadow.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        playerShadow.gameMode.changeGameModeForPlayer(player.gameMode.getGameModeForPlayer());
        ((ServerPlayerInterface) playerShadow).getActionPack().copyFrom(((ServerPlayerInterface) player).getActionPack());
        // this might create problems if a player logs back in...
        playerShadow.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        playerShadow.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, player.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION));


        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(playerShadow, (byte) (player.yHeadRot * 256 / 360)), playerShadow.level().dimension());
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, playerShadow));
        //player.world.getChunkManager().updatePosition(playerShadow);
        playerShadow.getAbilities().flying = player.getAbilities().flying;
        return playerShadow;
    }
    */

    public void copycat(ServerPlayer otherPlayer) {
        if (!(otherPlayer instanceof ServerPlayerInterface src)) {
            return;
        }

        if (!(this instanceof ServerPlayerInterface dst)) {
            return;
        }

        dst.getActionPack().copyFrom(src.getActionPack());

        this.getInventory().clearContent();
        for (int i = 0; i < otherPlayer.getInventory().getContainerSize(); i++) {
            this.getInventory().setItem(i, otherPlayer.getInventory().getItem(i).copy());
        }

        this.inventoryMenu.sendAllDataToRemote();
    }

    public static BotPlayer respawnFake(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation cli) {
        return new BotPlayer(server, level, profile, cli, false);
    }

    public static boolean isSpawningPlayer(String username) {
        return spawning.contains(username);
    }

    private BotPlayer(MinecraftServer server, ServerLevel worldIn, GameProfile profile, ClientInformation cli, boolean shadow) {
        super(server, worldIn, profile, cli);
        this.isAShadow = shadow;
    }

    public static final byte SKIN_CAPE = 0x01;
    public static final byte SKIN_JACKET = 0x02;
    public static final byte SKIN_LEFT_SLEEVE = 0x04;
    public static final byte SKIN_RIGHT_SLEEVE = 0x08;
    public static final byte SKIN_LEFT_PANT = 0x10;
    public static final byte SKIN_RIGHT_PANT = 0x20;
    public static final byte SKIN_HAT = 0x40;

    public void toggleSkinPart(byte mask) {
        byte current = this.entityData.get(DATA_PLAYER_MODE_CUSTOMISATION);
        this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) (current ^ mask));
    }

    public boolean isSkinPartEnabled(byte mask) {
        return (this.entityData.get(DATA_PLAYER_MODE_CUSTOMISATION) & mask) != 0;
    }

    public void setMainHand(HumanoidArm arm) {
        this.entityData.set(DATA_PLAYER_MAIN_HAND, arm);
    }

    @Override
    public void move(@NonNull MoverType moverType, @NonNull Vec3 vec3) {
        double oldX = this.getX();
        double oldZ = this.getZ();
        super.move(moverType, vec3);
        float movedX = (float) (this.getX() - oldX);
        float movedZ = (float) (this.getZ() - oldZ);
        ((ServerPlayerInterface) this).getActionPack().updateAutoJump(movedX, movedZ);
    }

    @Override
    public void onEquipItem(final @NonNull EquipmentSlot slot, final @NonNull ItemStack previous, final @NonNull ItemStack stack) {
        if (!isUsingItem()) super.onEquipItem(slot, previous, stack);
    }

    @Override
    public void kill(@NonNull ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    public void botPlayerDisconnect(Component reason) {
        this.level().getServer().schedule(new TickTask(this.level().getServer().getTickCount(), () ->
                this.connection.onDisconnect(new DisconnectionDetails(reason))
        ));
    }

    public void kill(Component reason) {
        shakeOff();

        if (reason.getContents() instanceof TranslatableContents text
                && text.getKey().equals("multiplayer.disconnect.duplicate_login")) {
            this.connection.onDisconnect(new DisconnectionDetails(reason));
            return;
        }
        this.hurtServer(this.level(), this.level().damageSources().fellOutOfWorld(), Float.MAX_VALUE);
    }


    @Override
    public void tick() {
        if (this.level().getServer().getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        try {
            super.tick();

//            ((ServerPlayerInterface) this).getActionPack().onUpdate();
            this.doTick();

            processPendingKBs();
            handleSpear(); //not sure where else to put it so here it goes [maybe onUpdate would be better, but this is easier]
        } catch (NullPointerException ignored) {
            // happens with that paper port thingy - not sure what that would fix, but hey
            // the game not gonna crash violently.
        }
    }

    private void processPendingKBs() {
        long currentTick = this.level().getServer().getTickCount();
        if (!pendingKnockbacks.isEmpty()) {
            var i = pendingKnockbacks.iterator();
            while (i.hasNext()) {
                DelayedKnockback kb = i.next();
                if (currentTick >= kb.tick()) {
                    super.knockback(kb.strength(), kb.x(), kb.z());
                    i.remove();
                }
            }
        }
        if (!pendingExplosionKB.isEmpty()) {
            var i = pendingExplosionKB.iterator();
            while (i.hasNext()) {
                DelayedExplosionKB dp = i.next();
                if (currentTick >= dp.tick()) {
                    super.push(dp.explosionKB());
                    i.remove();
                }
            }
        }
    }

    public void delayedExplosionKB(Vec3 vec3) {
        int delayTicks = delayTicks();
        if (delayTicks <= 0) {
            super.push(vec3);
        } else {
            long executeAt = this.level().getServer().getTickCount() + delayTicks;
            pendingExplosionKB.add(new DelayedExplosionKB(executeAt, vec3));
        }
    }

    @Override
    public void knockback(double strength, double x, double z) {
        int delayTicks = delayTicks();
        if (delayTicks <= 0) {
            super.knockback(strength, x, z);
        } else {
            long executeAt = this.level().getServer().getTickCount() + delayTicks;
            pendingKnockbacks.add(new DelayedKnockback(executeAt, strength, x, z));
        }
    }

    public int delayTicks() {
        int pingToTicks = HeroBotSettings.botPingToTicks;
        int remainder = ping % pingToTicks;
        if (remainder == 0) {
            return ping / pingToTicks;
        }
        int random = ThreadLocalRandom.current().nextInt(pingToTicks);
        // I think this should be similar to how regular mc works with ping
        return random < remainder ? (ping / pingToTicks) + 1 : ping / pingToTicks;
    }

    private void handleSpear() {
        if (!this.isUsingItem()) return;

        ItemStack stack = this.getUseItem();
        if (stack.isEmpty()) return;
        KineticWeapon kineticWeapon = stack.get(DataComponents.KINETIC_WEAPON);
        if (kineticWeapon == null) return;

        int used = stack.getUseDuration(this) - this.getUseItemRemainingTicks();

        if (used < kineticWeapon.delayTicks()) return;

        int effChargeTicks = used - kineticWeapon.delayTicks();

        Vec3 look = this.getLookAngle();
        Vec3 attackerMotion = this.getDeltaMovement().scale(20.0); //Cause velocity is bugged, it should be getKnownSpeed but getKnownSpeed is always 0 for some reason
        double attackerDot = look.dot(attackerMotion);
        double baseDamage = this.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);

        Collection<EntityHitResult> hits =
                ProjectileUtil.getHitEntitiesAlong(
                                this,
                                this.entityAttackRange(),
                                e -> PiercingWeapon.canHitEntity(this, e),
                                ClipContext.Block.COLLIDER
                        )
                        .right()
                        .orElse(List.of());

        boolean anyHit = false;

        for (EntityHitResult hit : hits) {
            Entity entity = hit.getEntity();

            if (entity instanceof EnderDragonPart part)
                entity = part.parentMob;

            //This is commented out cause it runs better without it? Not sure why tho
//            if (this.wasRecentlyStabbed(entity, kinetic.contactCooldownTicks())) continue;
//            this.rememberStabbedEntity(entity);

            Vec3 targetMotion = entity instanceof BotPlayer ? this.getDeltaMovement().scale(20.0) : entity.getKnownSpeed().scale(20.0);
            double targetDot = look.dot(targetMotion);
            double relative = Math.max(0.0, attackerDot - targetDot);

            boolean damageOk = kineticWeapon.damageConditions()
                    .map(c -> c.test(effChargeTicks, attackerDot, relative, 1.0))
                    .orElse(false);

            if (!damageOk) continue;

            float finalDamage =
                    (float) baseDamage +
                            (float) Mth.floor(relative * kineticWeapon.damageMultiplier());

            boolean result = this.stabAttack(
                    EquipmentSlot.MAINHAND,
                    entity,
                    finalDamage,
                    true,
                    false,
                    false
            );

            anyHit |= result; //For anyone reading this, it's the same as anyHit = anyHit || result for our case
        }

        if (anyHit) {
            this.level().broadcastEntityEvent(this, (byte) 2);
        }
    }

    @Override
    public boolean hurtServer(@NonNull ServerLevel serverLevel, @NonNull DamageSource damageSource, float finalDamage) {
        if (
                (this.gameMode.getGameModeForPlayer() == GameType.CREATIVE || this.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) &&
                        (damageSource != this.level().damageSources().fellOutOfWorld())
        ) {
            return false;
        }
        if (damageSource.getDirectEntity() instanceof ThrowableItemProjectile) {
            return false;
        }
        if (this.isInvulnerableTo(serverLevel, damageSource)) {
            return false;
        } else if (this.isDeadOrDying()) {
            return false;
        } else if (damageSource.is(DamageTypeTags.IS_FIRE) && this.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return false;
        } else if (damageSource.is(DamageTypeTags.IS_EXPLOSION)) {
            return super.hurtServer(serverLevel, damageSource, finalDamage);
        } else {
            if (this.isSleeping()) {
                this.stopSleeping();
            }

            this.noActionTime = 0;
            if (finalDamage < 0.0F) {
                finalDamage = 0.0F;
            }

            float originalDamage = finalDamage;
            float blockedDamage = this.applyItemBlocking(serverLevel, damageSource, finalDamage);
            finalDamage -= blockedDamage;
            boolean blocked = blockedDamage > 0.0F;
            if (damageSource.is(DamageTypeTags.IS_FREEZING) && this.getType().is(EntityTypeTags.FREEZE_HURTS_EXTRA_TYPES)) {
                finalDamage *= 5.0F;
            }

            if (damageSource.is(DamageTypeTags.DAMAGES_HELMET) && !this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                this.hurtHelmet(damageSource, finalDamage);
                finalDamage *= 0.75F;
            }

            if (Float.isNaN(finalDamage) || Float.isInfinite(finalDamage)) {
                finalDamage = Float.MAX_VALUE;
            }

            boolean cleanHit = true;
            if ((float) this.invulnerableTime > 10.0F && !damageSource.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
                if (finalDamage <= this.lastHurt) {
                    return false;
                }

                this.actuallyHurt(serverLevel, damageSource, finalDamage - this.lastHurt);
                this.lastHurt = finalDamage;
                cleanHit = false;
            } else {
                this.lastHurt = finalDamage;
                this.invulnerableTime = 20;
                this.actuallyHurt(serverLevel, damageSource, finalDamage);
                this.hurtDuration = 10;
                this.hurtTime = this.hurtDuration;
            }

            this.resolveMobResponsibleForDamage(damageSource);
            this.resolvePlayerResponsibleForDamage(damageSource);
            if (cleanHit) {
                BlocksAttacks blocksAttacks = this.getUseItem().get(DataComponents.BLOCKS_ATTACKS);
                if (blocked && blocksAttacks != null) {
                    blocksAttacks.onBlocked(serverLevel, this);
                } else {
                    serverLevel.broadcastDamageEvent(this, damageSource);
                }

                if (!damageSource.is(DamageTypeTags.NO_IMPACT) && (!blocked || finalDamage > 0.0F)) {
                    this.markHurt();
                }

                if (!damageSource.is(DamageTypeTags.NO_KNOCKBACK)) {
                    double kb_x = 0.0d;
                    double kb_z = 0.0d;
                    Entity directEntity = damageSource.getDirectEntity();
                    if (directEntity instanceof Projectile projectile) {
                        DoubleDoubleImmutablePair kbVector = projectile.calculateHorizontalHurtKnockbackDirection(this, damageSource);
                        kb_x = -kbVector.leftDouble();
                        kb_z = -kbVector.rightDouble();
                    } else if (damageSource.getSourcePosition() != null) {
                        kb_x = damageSource.getSourcePosition().x() - this.getX();
                        kb_z = damageSource.getSourcePosition().z() - this.getZ();
                    }

                    if (!blocked) {
                        this.knockback(0.4d, kb_x, kb_z);
                        this.indicateDamage(kb_x, kb_z);
                    }
                }
            }

            if (this.isDeadOrDying()) {
                try {
                    if (!((LivingEntityAccessor) this).invokeCheckTotemDeathProtection(damageSource)) {
                        if (cleanHit) {
                            this.makeSound(this.getDeathSound());
                            ((LivingEntityAccessor) this).invokePlaySecondaryHurtSound(damageSource);
                        }

                        this.die(damageSource);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            } else if (cleanHit && (!HeroBotSettings.shieldStunning || !blocked)) {
                this.playHurtSound(damageSource);
                ((LivingEntityAccessor) this).invokePlaySecondaryHurtSound(damageSource);
            }

            boolean damageExists = !blocked || finalDamage > 0.0F;
            if (damageExists) {
                ((LivingEntityAccessor) this).setLastDamageSource(damageSource);
                ((LivingEntityAccessor) this).setLastDamageStamp(this.level().getGameTime());

                for (MobEffectInstance mobEffectInstance : this.getActiveEffects()) {
                    mobEffectInstance.onMobHurt(serverLevel, this, damageSource, finalDamage);
                }
            }

            ServerPlayer serverPlayer1 = this;
            CriteriaTriggers.ENTITY_HURT_PLAYER.trigger(serverPlayer1, damageSource, originalDamage, finalDamage, blocked);
            if (blockedDamage > 0.0F && blockedDamage < 3.4028235E37F) {
                serverPlayer1.awardStat(Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(blockedDamage * 10.0F));
            }

            Entity attackingEntity = damageSource.getEntity();
            if (attackingEntity instanceof ServerPlayer serverPlayer) {
                CriteriaTriggers.PLAYER_HURT_ENTITY.trigger(serverPlayer, this, damageSource, originalDamage, finalDamage, blocked);
            }

            return damageExists;
        }
    }


    private void shakeOff() {
        if (getVehicle() instanceof Player) stopRiding();
        for (Entity passenger : getIndirectPassengers()) {
            if (passenger instanceof Player) passenger.stopRiding();
        }
    }

    @Override
    public void die(@NonNull DamageSource cause) {
        shakeOff();
        super.die(cause);

        MinecraftServer server = ((ServerPlayerAccessor) this).getServer();

        if (HeroBotSettings.botLeaveOnDeath) {
            botPlayerDisconnect(Component.literal("Died"));
            return;
        }

        server.execute(() -> {
            this.connection.handleClientCommand(
                    new ServerboundClientCommandPacket(
                            ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                    )
            );

            ServerPlayer p = this.connection.player;
            if (p instanceof BotPlayer bot) {
                bot.setHealth(20.0F);
                bot.foodData = new FoodData();
                bot.setExperienceLevels(0);
                bot.setExperiencePoints(0);
            }
        });
    }


    @Override
    public @NonNull String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return HeroBotSettings.allowListingBotPlayers;
    }

    // Can be commented out since it runs fine without
//    @Override
//    protected void checkFallDamage(double y, boolean onGround, @NonNull BlockState state, @NonNull BlockPos pos) {
//        doCheckFallDamage(0.0, y, 0.0, onGround);
//    }

    @Override
    public boolean isInvulnerableTo(@NonNull ServerLevel serverLevel, @NonNull DamageSource damageSource) {
        return super.isInvulnerableTo(serverLevel, damageSource)
                || this.isChangingDimension() && !damageSource.is(DamageTypes.ENDER_PEARL);
    }

    @Override
    public ServerPlayer teleport(@NonNull TeleportTransition serverLevel) {
        super.teleport(serverLevel);
        if (wonGame) {
            ServerboundClientCommandPacket p = new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
            connection.handleClientCommand(p);
        }

        if (connection.player.isChangingDimension()) {
            connection.player.hasChangedDimension();
        }
        return connection.player;
    }

    @Override
    protected void blockUsingItem(@NonNull ServerLevel serverLevel, LivingEntity livingEntity) {
        ItemStack itemStack = this.getItemBlockingWith();
        BlocksAttacks blocksAttacks = itemStack != null ? itemStack.get(DataComponents.BLOCKS_ATTACKS) : null;
        float f = livingEntity.getSecondsToDisableBlocking();
        if (f > 0.0F && blocksAttacks != null) {
            blocksAttacks.disable(serverLevel, this, f, itemStack);
            this.invulnerableTime = 20;
            if (HeroBotSettings.shieldStunning) {
                executor.schedule(() -> this.invulnerableTime = 0, 1, TimeUnit.MILLISECONDS);
            }
        }
    }
}