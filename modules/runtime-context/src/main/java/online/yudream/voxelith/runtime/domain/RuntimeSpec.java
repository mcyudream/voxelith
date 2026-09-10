package online.yudream.voxelith.runtime.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次 headless 运行的规格：目标 MC 版本、加载器、待加载 mod jar 与工作目录。
 * 工作目录由启动器写入 spec.json / 读取 worker-result.json（文件协议，见 ADR 0001）。
 */
public record RuntimeSpec(
        String mcVersion,
        LoaderKind loader,
        String loaderVersion,
        List<Path> modJars,
        Path workDir) {

    public RuntimeSpec {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion 不能为空");
        }
        if (loader == null) {
            throw new IllegalArgumentException("loader 不能为空");
        }
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IllegalArgumentException("loaderVersion 不能为空");
        }
        if (workDir == null) {
            throw new IllegalArgumentException("workDir 不能为空");
        }
        modJars = modJars == null ? List.of() : List.copyOf(modJars);
    }
}
