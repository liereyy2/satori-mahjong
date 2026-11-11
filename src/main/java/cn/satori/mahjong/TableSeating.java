package cn.satori.mahjong;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

/** Physical seating. Public lobby claims never authenticate a cloud game seat. */
public final class TableSeating {
    private TableSeating() {}

    public static Direction outward(int seat) {
        return switch (Math.floorMod(seat, 4)) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    public static int direction(BlockPos center, Vec3 point) {
        double x = point.x - center.getX() - .5, z = point.z - center.getZ() - .5;
        return Math.abs(z) >= Math.abs(x) ? (z >= 0 ? 0 : 2) : (x < 0 ? 1 : 3);
    }

    public static BlockPos chair(BlockPos center, int seat) {
        return center.relative(outward(seat), 2);
    }

    public static boolean furnish(Level level, BlockPos center) {
        if (!TableBlock.complete(level, center)) return false;
        for (int seat = 0; seat < 4; seat++) {
            BlockPos pos = chair(center, seat);
            var state = level.getBlockState(pos);
            if (!level.hasChunkAt(pos)
                    || !level.getBlockState(pos.above()).isAir()
                    || !(state.isAir()
                            || state.is(SatoriMahjong.chair())
                                    && state.getValue(ChairBlock.FACING)
                                            == outward(seat).getOpposite())) return false;
        }
        for (int seat = 0; seat < 4; seat++)
            level.setBlockAndUpdate(
                    chair(center, seat),
                    SatoriMahjong.chair()
                            .defaultBlockState()
                            .setValue(ChairBlock.FACING, outward(seat).getOpposite()));
        return true;
    }

    public static boolean sit(ServerPlayer player, BlockPos center, int seat) {
        var level = player.level();
        if (seat < 0
                || seat > 3
                || !level.hasChunkAt(center)
                || player.distanceToSqr(center.getCenter()) > 64
                || !level.mayInteract(player, center)) return false;
        if (!TableBlock.complete(level, center)) {
            player.displayClientMessage(Component.translatable("satori.table.incomplete"), true);
            return false;
        }
        if (!HostedServer.get(level.getServer())
                .allowsSeat(player, (TableBlockEntity) level.getBlockEntity(center), seat))
            return false;
        if (!furnish(level, center)) {
            player.displayClientMessage(Component.translatable("satori.seat.space"), true);
            return false;
        }
        if (player.getVehicle() instanceof TableSeatEntity existing
                && existing.center().equals(center)
                && existing.direction() == seat) {
            open(player, center);
            return true;
        }
        BlockPos pos = chair(center, seat);
        var occupied =
                level.getEntitiesOfClass(
                        TableSeatEntity.class,
                        new AABB(pos).inflate(.1),
                        entity ->
                                entity.center().equals(center)
                                        && entity.direction() == seat
                                        && entity.isVehicle());
        if (!occupied.isEmpty()) {
            player.displayClientMessage(Component.translatable("satori.seat.occupied"), true);
            return false;
        }
        if (player.isPassenger()) player.stopRiding();
        TableSeatEntity entity = new TableSeatEntity(SatoriMahjong.seatEntityType(), level);
        entity.configure(center, seat);
        level.addFreshEntity(entity);
        if (!player.startRiding(entity)) {
            entity.discard();
            return false;
        }
        player.setYRot(180 + seat * 90);
        player.setYHeadRot(player.getYRot());
        player.setYBodyRot(player.getYRot());
        open(player, center);
        return true;
    }

    private static void open(ServerPlayer player, BlockPos center) {
        var table = (TableBlockEntity) player.level().getBlockEntity(center);
        HostedServer.get(player.level().getServer()).openTable(player, table);
    }
}
