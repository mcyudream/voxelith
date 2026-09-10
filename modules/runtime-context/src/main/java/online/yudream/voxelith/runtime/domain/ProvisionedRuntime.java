package online.yudream.voxelith.runtime.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一次运行时 provision 的产物。
 *
 * @param gameJar    MC 游戏主 jar（1.20.1 client，obfuscated，由加载器 remap 到 intermediary）
 * @param mappingsJar intermediary 映射 jar（fabric loader  remap 游戏 jar 所需）
 * @param loaderJar  fabric-loader 主 jar
 * @param libraries  加载器依赖 + MC 依赖库（已按平台规则过滤、剔除 org.lwjgl —— 由 worker stub 取代）
 */
public record ProvisionedRuntime(Path gameJar, Path mappingsJar, Path loaderJar, List<Path> libraries) {

    public ProvisionedRuntime {
        Objects.requireNonNull(gameJar, "gameJar");
        Objects.requireNonNull(mappingsJar, "mappingsJar");
        Objects.requireNonNull(loaderJar, "loaderJar");
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
    }
}
