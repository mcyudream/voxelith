package online.yudream.voxelith.resource.application;

import online.yudream.voxelith.resource.domain.registry.BlockModelRegistry;
import online.yudream.voxelith.resource.domain.registry.ResolveReport;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * resolve 链路产物落盘端口。实现位于 infrastructure 层。
 */
public interface ResolveArtifactSink {

    /** 写 resolved-registry.json（方块 blockstate + 引用模型 + 展开后模型表）。 */
    void writeRegistry(Path outputDir, BlockModelRegistry registry);

    /** 写 resolve-report.json（覆盖率与缺失清单）。 */
    void writeReport(Path outputDir, ResolveReport report);

    /**
     * 导出注册表引用到的全部贴图到 textures/ 目录。
     *
     * @param reader 按贴图 id 读原始字节（来自包栈）
     * @return 实际导出的贴图数量
     */
    int writeTextures(Path outputDir, Collection<Identifier> textures, Function<Identifier, Optional<byte[]>> reader);
}
