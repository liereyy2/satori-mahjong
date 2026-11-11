package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Public world/lobby metadata only. No credentials, cloud snapshots or concealed tiles. */
public final class TablePackets {
    private TablePackets() {}

    public record Occupant(String minecraftName, String userId, String name, boolean host) {}

    public record Presence(ResourceKey<Level> dimension, BlockPos pos, String userId, String name)
            implements CustomPacketPayload {
        public static final Type<Presence> TYPE = new Type<>(SatoriMahjong.id("table_presence"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Presence> CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            buf.writeResourceKey(p.dimension);
                            buf.writeBlockPos(p.pos);
                            buf.writeUtf(p.userId, 36);
                            buf.writeUtf(p.name, 128);
                        },
                        buf ->
                                new Presence(
                                        buf.readResourceKey(Registries.DIMENSION),
                                        buf.readBlockPos(),
                                        buf.readUtf(36),
                                        buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Lobby(
            ResourceKey<Level> dimension,
            BlockPos pos,
            String gameId,
            boolean editable,
            List<Occupant> seats)
            implements CustomPacketPayload {
        public Lobby {
            seats = List.copyOf(seats);
            if (seats.size() != 4) throw new IllegalArgumentException("Four lobby seats required");
        }

        public static final Type<Lobby> TYPE = new Type<>(SatoriMahjong.id("table_lobby"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Lobby> CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            buf.writeResourceKey(p.dimension);
                            buf.writeBlockPos(p.pos);
                            buf.writeUtf(p.gameId, 36);
                            buf.writeBoolean(p.editable);
                            for (var seat : p.seats) {
                                buf.writeUtf(seat.minecraftName, 16);
                                buf.writeUtf(seat.userId, 36);
                                buf.writeUtf(seat.name, 128);
                                buf.writeBoolean(seat.host);
                            }
                        },
                        buf -> {
                            var dimension = buf.readResourceKey(Registries.DIMENSION);
                            var pos = buf.readBlockPos();
                            var game = buf.readUtf(36);
                            boolean editable = buf.readBoolean();
                            var seats = new ArrayList<Occupant>();
                            for (int i = 0; i < 4; i++)
                                seats.add(
                                        new Occupant(
                                                buf.readUtf(16),
                                                buf.readUtf(36),
                                                buf.readUtf(128),
                                                buf.readBoolean()));
                            return new Lobby(dimension, pos, game, editable, seats);
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Leave() implements CustomPacketPayload {
        public static final Type<Leave> TYPE = new Type<>(SatoriMahjong.id("leave_seat"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Leave> CODEC =
                StreamCodec.unit(new Leave());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Open(BlockPos pos, String gameId, boolean editable)
            implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(SatoriMahjong.id("open_table"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            buf.writeBlockPos(p.pos);
                            buf.writeUtf(p.gameId, 36);
                            buf.writeBoolean(p.editable);
                        },
                        buf -> new Open(buf.readBlockPos(), buf.readUtf(36), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Linked(ResourceKey<Level> dimension, BlockPos pos, String gameId)
            implements CustomPacketPayload {
        public static final Type<Linked> TYPE = new Type<>(SatoriMahjong.id("table_linked"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Linked> CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            buf.writeResourceKey(p.dimension);
                            buf.writeBlockPos(p.pos);
                            buf.writeUtf(p.gameId, 36);
                        },
                        buf ->
                                new Linked(
                                        buf.readResourceKey(Registries.DIMENSION),
                                        buf.readBlockPos(),
                                        buf.readUtf(36)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Link(ResourceKey<Level> dimension, BlockPos pos, String expected, String gameId)
            implements CustomPacketPayload {
        public static final Type<Link> TYPE = new Type<>(SatoriMahjong.id("link_table"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Link> CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            buf.writeResourceKey(p.dimension);
                            buf.writeBlockPos(p.pos);
                            buf.writeUtf(p.expected, 36);
                            buf.writeUtf(p.gameId, 36);
                        },
                        buf ->
                                new Link(
                                        buf.readResourceKey(Registries.DIMENSION),
                                        buf.readBlockPos(),
                                        buf.readUtf(36),
                                        buf.readUtf(36)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
