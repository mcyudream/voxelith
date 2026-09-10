package online.yudream.voxelith.resource.application;

import java.nio.file.Path;
import java.util.List;

/**
 * resolve 链路输入。
 *
 * @param packs     资源包路径，列表顺序即优先级从低到高（后者覆盖前者，与游戏内一致）
 * @param outputDir 产物落盘目录（resolved-registry.json / resolve-report.json / textures/）
 */
public record ResolveCommand(List<Path> packs, Path outputDir) {

    public ResolveCommand {
        if (packs == null || packs.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个资源包（通常为原版客户端 jar）");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir 不能为空");
        }
    }
}
