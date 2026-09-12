package online.yudream.voxelith.runtime.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一次运行时 provision 的产物。
 *
 * @param gameJar         MC 游戏主 jar（obfuscated，由加载器 remap 到 intermediary）
 * @param mappingsJar     intermediary 映射 jar
 * @param loaderJar       fabric-loader 主 jar
 * @param libraries       加载器依赖 + MC 依赖库（窗口/GL LWJGL 已剔除）
 * @param extraMods       按 fabric.mod.json depends 自动补全的 mod（如 fabric-api），并入 worker {@code fabric.addMods}
 * @param extraClasspath  额外适配器/依赖 jar，并入 worker classpath（Forge 适配器 jar 走这里）
 */
public record ProvisionedRuntime(Path gameJar, Path mappingsJar, Path loaderJar, List<Path> libraries,
                                 List<Path> extraMods, List<Path> extraClasspath) {

    public ProvisionedRuntime {
        Objects.requireNonNull(gameJar, "gameJar");
        Objects.requireNonNull(mappingsJar, "mappingsJar");
        Objects.requireNonNull(loaderJar, "loaderJar");
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
        extraMods = extraMods == null ? List.of() : List.copyOf(extraMods);
        extraClasspath = extraClasspath == null ? List.of() : List.copyOf(extraClasspath);
    }

    public ProvisionedRuntime(Path gameJar, Path mappingsJar, Path loaderJar, List<Path> libraries) {
        this(gameJar, mappingsJar, loaderJar, libraries, List.of(), List.of());
    }
}
