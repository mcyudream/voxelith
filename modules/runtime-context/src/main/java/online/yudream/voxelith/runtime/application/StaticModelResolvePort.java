package online.yudream.voxelith.runtime.application;

import online.yudream.voxelith.resource.application.ResolveOutcome;

import java.nio.file.Path;
import java.util.List;

/**
 * 静态解析兜底端口：runtime 采集失败时，直接解析 jar 内 blockstate/model 资源
 * （BlueMap 式）。实现由组合根装配（委托 resource-context 的 resolve 链路）。
 *
 * <p>跨上下文约束：本端口只暴露 resource-context application 层的契约类型。</p>
 */
public interface StaticModelResolvePort {

    /**
     * 对包栈（原版客户端 jar + mod jar，优先级从低到高）执行静态解析并落盘产物。
     *
     * @param packs     资源包路径（后者覆盖前者）
     * @param outputDir 静态解析产物目录（resolved-registry.json / textures/ 等）
     */
    ResolveOutcome resolve(List<Path> packs, Path outputDir);
}
