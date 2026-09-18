package online.yudream.voxelith.server.config;

import online.yudream.voxelith.resource.application.ResolveCommand;
import online.yudream.voxelith.resource.application.ResolveResourcesUseCase;
import online.yudream.voxelith.resource.infrastructure.artifact.FileResolveArtifactSink;
import online.yudream.voxelith.resource.infrastructure.pack.ResourcePackAutoFactory;
import online.yudream.voxelith.resource.infrastructure.parse.GsonBlockstateParser;
import online.yudream.voxelith.resource.infrastructure.parse.GsonModelParser;
import online.yudream.voxelith.runtime.application.StaticModelResolvePort;

/**
 * resource-context 静态解析链路 → runtime-context 兜底端口的装配（ADR 0001）。
 * Spring 组合根与 CLI 入口共用，避免两处各写一份包解析器装配而漂移。
 */
public final class StaticModelResolveFactory {

    private StaticModelResolveFactory() {
    }

    public static StaticModelResolvePort create() {
        ResolveResourcesUseCase resolve = new ResolveResourcesUseCase(
                new ResourcePackAutoFactory(),
                new GsonBlockstateParser(),
                new GsonModelParser(),
                new FileResolveArtifactSink());
        return (packs, outputDir) -> resolve.resolve(new ResolveCommand(packs, outputDir));
    }
}
