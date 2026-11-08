package com.example.claudecompanion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/** The player-like body of the Claude companion. The "brain" lives in ClaudeManager. */
public class ClaudeEntity extends PathfinderMob {

    public enum Mode { FOLLOW, STAY, GOTO }

    private UUID ownerId;
    private Mode mode = Mode.FOLLOW;
    private BlockPos gotoPos;
    private int gotoTicks;
    private boolean defend = true;

    public ClaudeEntity(EntityType<? extends ClaudeEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCustomName(Component.literal("Claude"));
        this.setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    // ---- state accessors used by the manager ----
    UUID getOwnerId() { return ownerId; }
    void setOwner(UUID id) { this.ownerId = id; }
    Mode getMode() { return mode; }
    boolean isDefending() { return defend; }
    void setDefend(boolean v) { this.defend = v; }
    void follow() { this.mode = Mode.FOLLOW; }
    void stay() { this.mode = Mode.STAY; this.getNavigation().stop(); }
    void goTo(BlockPos pos) { this.gotoPos = pos; this.mode = Mode.GOTO; this.gotoTicks = 0; }

    private Player owner() {
        return ownerId == null ? null : this.level().getPlayerByUUID(ownerId);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(2, new GotoGoal());
        this.goalSelector.addGoal(3, new FollowOwnerGoal());
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false, e -> this.defend));
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || ownerId == null || this.tickCount % 10 != 0) {
            return;
        }
        // Only one Claude per owner: stale duplicates (e.g. from old saves) remove themselves.
        if (!ClaudeManager.claim(ownerId, this)) {
            this.discard();
            return;
        }
        MinecraftServer server = this.level().getServer();
        if (server == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            ClaudeManager.setListening(ownerId, false);
            return;
        }
        if (owner.level() != this.level()) {
            // Owner changed dimension: respawn next to them.
            ClaudeManager.summon(owner, false);
            return;
        }
        double d2 = this.distanceToSqr(owner);
        double range = Config.HEARING_RANGE.get();
        ClaudeManager.setListening(ownerId, d2 <= range * range);

        if (mode == Mode.FOLLOW && d2 > 24.0D * 24.0D) {
            this.moveTo(owner.getX() + 1.0D, owner.getY(), owner.getZ() + 1.0D, this.getYRot(), 0.0F);
            this.getNavigation().stop();
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide) {
            ClaudeManager.onDeath(this);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
        }
        tag.putString("Mode", mode.name());
        tag.putBoolean("Defend", defend);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            ownerId = tag.getUUID("Owner");
        }
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.FOLLOW;
        }
        if (mode == Mode.GOTO) {
            mode = Mode.STAY;
        }
        if (tag.contains("Defend")) {
            defend = tag.getBoolean("Defend");
        }
    }

    // ---- goals ----

    private class FollowOwnerGoal extends Goal {
        private int recalc;

        FollowOwnerGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            Player o = owner();
            return mode == Mode.FOLLOW && o != null && distanceToSqr(o) > 25.0D;
        }

        @Override
        public boolean canContinueToUse() {
            Player o = owner();
            return mode == Mode.FOLLOW && o != null && distanceToSqr(o) > 9.0D;
        }

        @Override
        public void start() {
            recalc = 0;
        }

        @Override
        public void stop() {
            getNavigation().stop();
        }

        @Override
        public void tick() {
            Player o = owner();
            if (o == null) {
                return;
            }
            getLookControl().setLookAt(o, 10.0F, (float) getMaxHeadXRot());
            if (--recalc <= 0) {
                recalc = 10;
                getNavigation().moveTo(o, 1.15D);
            }
        }
    }

    private class GotoGoal extends Goal {
        GotoGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mode == Mode.GOTO && gotoPos != null;
        }

        @Override
        public void start() {
            gotoTicks = 0;
            moveToTarget();
        }

        @Override
        public void tick() {
            gotoTicks += 2;
            if (distanceToSqr(Vec3.atCenterOf(gotoPos)) < 6.0D || gotoTicks > 600) {
                mode = Mode.STAY;
                getNavigation().stop();
                return;
            }
            if (getNavigation().isDone()) {
                moveToTarget();
            }
        }

        @Override
        public void stop() {
            getNavigation().stop();
        }

        private void moveToTarget() {
            getNavigation().moveTo(gotoPos.getX() + 0.5D, gotoPos.getY(), gotoPos.getZ() + 0.5D, 1.0D);
        }
    }
}
