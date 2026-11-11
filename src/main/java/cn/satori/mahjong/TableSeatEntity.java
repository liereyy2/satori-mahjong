package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/** Non-persistent invisible vehicle, synchronized by vanilla passenger packets. */
public final class TableSeatEntity extends Entity {
    // Chair mesh cushion top: 10/16. AvatarRenderer scales the humanoid by 15/16;
    // LivingEntityRenderer translates by 1.501, with the pelvis at model y=12/16.
    public static final double CUSHION_TOP = 10.0 / 16;
    public static final double PLAYER_HIP_HEIGHT = (1.501 - 12.0 / 16) * 15.0 / 16;

    private static final EntityDataAccessor<BlockPos> CENTER =
            SynchedEntityData.defineId(TableSeatEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> DIRECTION =
            SynchedEntityData.defineId(TableSeatEntity.class, EntityDataSerializers.INT);

    public TableSeatEntity(EntityType<? extends TableSeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(CENTER, BlockPos.ZERO);
        builder.define(DIRECTION, 0);
    }

    public BlockPos center() {
        return entityData.get(CENTER);
    }

    public int direction() {
        return entityData.get(DIRECTION);
    }

    public void configure(BlockPos center, int seat) {
        entityData.set(CENTER, center.immutable());
        entityData.set(DIRECTION, seat);
        BlockPos chair = TableSeating.chair(center, seat);
        setPos(chair.getX() + .5, chair.getY() + CUSHION_TOP, chair.getZ() + .5);
        setYRot(180 + seat * 90);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()
                && (!isVehicle()
                        || !TableBlock.complete(level(), center())
                        || !level().getBlockState(TableSeating.chair(center(), direction()))
                                .is(SatoriMahjong.chair()))) {
            ejectPassengers();
            discard();
        }
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        Player player = (Player) passenger;
        move.accept(player, getX(), getY() - PLAYER_HIP_HEIGHT * player.getScale(), getZ());
        passenger.setYBodyRot(getYRot());
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        var outward = TableSeating.outward(direction());
        return new Vec3(
                getX() + outward.getStepX() * .8,
                center().getY(),
                getZ() + outward.getStepZ() * .8);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return passenger instanceof Player && getPassengers().isEmpty();
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (level() instanceof ServerLevel serverLevel)
            HostedServer.get(serverLevel.getServer())
                    .occupantsChanged(level().dimension(), center());
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean saveAsPassenger(ValueOutput output) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}
}
