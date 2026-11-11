package cn.satori.mahjong.benchmark;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Objects;

/** Install explicitly requested graphics fixtures after Loom cleans its isolated run directory. */
public final class ShaderPackFixture implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        String source = System.getProperty("satori.performance.shaderpack");
        if (source == null) return;
        Objects.requireNonNull(System.getProperty("satori.performance.report"));
        var settings =
                Path.of(
                        Objects.requireNonNull(
                                System.getProperty("satori.performance.shaderSettings")));
        var pack = Path.of(source);
        var run = FabricLoader.getInstance().getGameDir();
        try {
            Files.createDirectories(run.resolve("shaderpacks"));
            Files.createDirectories(run.resolve("config"));
            Files.copy(
                    pack,
                    run.resolve("shaderpacks").resolve(pack.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(
                    settings,
                    run.resolve("shaderpacks").resolve(pack.getFileName() + ".txt"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(
                    run.resolve("config/iris.properties"),
                    "enableShaders=true\nshaderPack="
                            + pack.getFileName()
                            + "\nallowUnknownShaders=false\nmaxShadowRenderDistance=32\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("SATORI_SHADER_FIXTURE: " + pack.getFileName());
    }
}
