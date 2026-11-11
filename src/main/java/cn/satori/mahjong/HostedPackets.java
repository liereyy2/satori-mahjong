package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server/client envelopes. Seat/account state is private; table_view is scoped to one recipient by
 * Rust.
 */
public final class HostedPackets {
    private HostedPackets() {}

    public record Request(ResourceKey<Level> dimension, BlockPos pos, boolean control, String json)
            implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(SatoriMahjong.id("hosted_request_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeResourceKey(p.dimension);
                            b.writeBlockPos(p.pos);
                            b.writeBoolean(p.control);
                            b.writeUtf(p.json, 8192);
                        },
                        b ->
                                new Request(
                                        b.readResourceKey(Registries.DIMENSION),
                                        b.readBlockPos(),
                                        b.readBoolean(),
                                        b.readUtf(8192)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @Override
        public String toString() {
            return "HostedRequest[redacted]";
        }
    }

    public record State(
            ResourceKey<Level> dimension, BlockPos pos, boolean control, boolean open, String json)
            implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(SatoriMahjong.id("hosted_state_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeResourceKey(p.dimension);
                            b.writeBlockPos(p.pos);
                            b.writeBoolean(p.control);
                            b.writeBoolean(p.open);
                            b.writeUtf(p.json, 65536);
                        },
                        b ->
                                new State(
                                        b.readResourceKey(Registries.DIMENSION),
                                        b.readBlockPos(),
                                        b.readBoolean(),
                                        b.readBoolean(),
                                        b.readUtf(65536)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @Override
        public String toString() {
            return "HostedState[private]";
        }
    }
}
