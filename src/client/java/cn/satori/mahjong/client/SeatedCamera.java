package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.TableCameraFraming;

import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

/** Client-only camera entity. Never spawned, saved or sent to the server. */
public final class SeatedCamera {
    private static Marker camera;
    private static TableSeatEntity seat;
    private static TableSeatEntity departing;
    private static Vec3 from;
    private static float fromYaw, fromPitch;
    private static int oldFov;
    private static boolean oldHideGui;
    private static CameraType oldPerspective;
    private static long started;
    private static long transitionNanos;
    private static double aspect;
    private static TableCameraFraming.Settings settings = TableCameraFraming.DEFAULT;

    private SeatedCamera() {}

    public static boolean active() {
        return camera != null && seat != null;
    }

    public static int direction() {
        return seat == null ? 0 : seat.direction();
    }

    public static BlockPos center() {
        if (!active()) throw new IllegalStateException("No seated camera");
        return seat.center();
    }

    public static TableCameraFraming.Settings settings() {
        return settings;
    }

    public static void settings(TableCameraFraming.Settings next) {
        if (settings.equals(next)) return;
        settings = next;
        if (active()) transition(220_000_000L);
    }

    public static void zoom(double steps) {
        settings(settings.withCoverage(settings.coverage() + steps * .02));
    }

    private static void transition(long duration) {
        var current = Minecraft.getInstance().gameRenderer.getMainCamera();
        from = current.position();
        fromYaw = current.yRot();
        fromPitch = current.xRot();
        started = System.nanoTime();
        transitionNanos = duration;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean wasSeated = active();
        if (mc.player == null
                || mc.level == null
                || !(mc.player.getVehicle() instanceof TableSeatEntity next)) {
            departing = null;
            reset();
            if (mc.player != null
                    && mc.level != null
                    && (mc.screen instanceof SeatedTableScreen
                            || mc.screen instanceof CameraSettingsScreen
                            || wasSeated
                                    && mc.screen instanceof TablePage page
                                    && page.controller().hosted != null)) mc.setScreen(null);
            return;
        }
        // The server owns dismounting; do not re-enter while its passenger update is in flight.
        if (next == departing) return;
        departing = null;
        if (next != seat) {
            reset();
            seat = next;
            oldFov = mc.options.fov().get();
            oldHideGui = mc.options.hideGui;
            oldPerspective = mc.options.getCameraType();
            transition(650_000_000L);
            // Player dimensions keep Camera's eye-height interpolation continuous at entry.
            camera = new Marker(EntityType.PLAYER, mc.level);
            camera.setPos(from.x, from.y - camera.getEyeHeight(), from.z);
            camera.setYRot(fromYaw);
            camera.setXRot(fromPitch);
            camera.setOldPosAndRot();
            aspect = (double) mc.getWindow().getWidth() / mc.getWindow().getHeight();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.fov().set((int) TableCameraFraming.FOV);
            mc.options.hideGui = true;
            mc.setCameraEntity(camera);
        }
        if (mc.getWindow().getWidth() <= 0 || mc.getWindow().getHeight() <= 0) return;
        double nextAspect = (double) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        if (nextAspect != aspect) {
            aspect = nextAspect;
            transition(220_000_000L);
        }
        float t = Math.clamp((System.nanoTime() - started) / (float) transitionNanos, 0, 1);
        float eased = t * t * (3 - 2 * t);
        var pose = TableCameraFraming.fit(settings, seat.direction(), aspect);
        Vec3 target =
                new Vec3(
                        seat.center().getX() + .5 + pose.eye().x(),
                        seat.center().getY() + pose.eye().y(),
                        seat.center().getZ() + .5 + pose.eye().z());
        Vec3 eye = from.lerp(target, eased);
        float yaw = (float) pose.yaw();
        camera.setOldPosAndRot();
        camera.setPos(eye.x, eye.y - camera.getEyeHeight(), eye.z);
        camera.setYRot(fromYaw + net.minecraft.util.Mth.wrapDegrees(yaw - fromYaw) * eased);
        camera.setXRot(fromPitch + ((float) pose.pitch() - fromPitch) * eased);
    }

    public static void reset() {
        if (camera == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCameraEntity() == camera) mc.setCameraEntity(mc.player);
        mc.options.fov().set(oldFov);
        mc.options.hideGui = oldHideGui;
        mc.options.setCameraType(oldPerspective);
        camera = null;
        seat = null;
    }

    /** Restore the native camera before Screen.onClose recaptures mouse input. */
    public static boolean leave() {
        var mc = Minecraft.getInstance();
        boolean send =
                mc.player != null
                        && mc.player.getVehicle() instanceof TableSeatEntity next
                        && next != departing;
        if (send) departing = (TableSeatEntity) mc.player.getVehicle();
        reset();
        mc.setScreen(null);
        return send;
    }
}
