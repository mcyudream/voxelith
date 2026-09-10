package online.yudream.voxelith.server.config;

import online.yudream.voxelith.resource.application.ResolveCommand;
import online.yudream.voxelith.resource.application.ResolveResourcesUseCase;
import online.yudream.voxelith.resource.infrastructure.artifact.FileResolveArtifactSink;
import online.yudream.voxelith.resource.infrastructure.pack.ResourcePackAutoFactory;
import online.yudream.voxelith.resource.infrastructure.parse.GsonBlockstateParser;
import online.yudream.voxelith.resource.infrastructure.parse.GsonModelParser;
import online.yudream.voxelith.runtime.application.ModelAcquisitionSink;
import online.yudream.voxelith.runtime.application.StaticModelResolvePort;
import online.yudream.voxelith.runtime.infrastructure.fallback.JsonModelAcquisitionSink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * runtime-context 降级兜底的组合根（ADR 0001）：把 resource-context 的静态 resolve
 * 链路装配为 {@link StaticModelResolvePort}，供模型获取用例在 headless 采集失败时回退。
 */
@Configuration
public class RuntimeHarvestConfig {

    @Bean
    public StaticModelResolvePort staticModelResolvePort() {
        ResolveResourcesUseCase resolve = new ResolveResourcesUseCase(
                new ResourcePackAutoFactory(),
                new GsonBlockstateParser(),
                new GsonModelParser(),
                new FileResolveArtifactSink());
        return (packs, outputDir) -> resolve.resolve(new ResolveCommand(packs, outputDir));
    }

    @Bean
    public ModelAcquisitionSink modelAcquisitionSink() {
        return new JsonModelAcquisitionSink();
    }
}
