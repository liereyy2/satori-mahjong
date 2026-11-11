package cn.satori.mahjong.benchmark;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Does not load client classes on the dedicated-server test. */
public final class TickSamples {
    public static volatile boolean active;
    public static final ConcurrentLinkedQueue<Double> VALUES = new ConcurrentLinkedQueue<>();

    public static void record(long nanos) {
        if (active) VALUES.add(nanos / 1e6);
    }
}
