package online.yudream.voxelith.resource.infrastructure.bootstrap;

import online.yudream.voxelith.resource.application.DefaultResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.domain.model.ModelResolver;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.domain.pack.ResourcePack;
import online.yudream.voxelith.resource.infrastructure.biome.PackBiomeSource;
import online.yudream.voxelith.resource.infrastructure.pack.ResourcePackAutoFactory;
import online.yudream.voxelith.resource.infrastructure.parse.GsonBlockstateParser;
import online.yudream.voxelith.resource.infrastructure.parse.GsonModelParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源上下文组合根：按优先级顺序打开资源包并装配解析目录。
 * 供编排层/其他上下文在不直连本上下文内部结构的前提下获得 application 契约实例。
 */
public final class ResourceContextBootstrap {

    private ResourceContextBootstrap() {
    }

    /**
     * @param packPaths 资源包路径，按优先级从低到高排列（后者覆盖前者）
     */
    public static ResolvedResourceCatalog openCatalog(List<Path> packPaths) {
        ResourcePackAutoFactory factory = new ResourcePackAutoFactory();
        List<ResourcePack> packs = new ArrayList<>();
        for (int i = 0; i < packPaths.size(); i++) {
            packs.add(factory.open(packPaths.get(i), i));
        }
        PackStack stack = PackStack.of(packs);
        return new DefaultResolvedResourceCatalog(
                stack,
                new GsonBlockstateParser(),
                new ModelResolver(stack, new GsonModelParser()),
                new PackBiomeSource(stack));
    }
}
