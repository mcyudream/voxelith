package online.yudream.voxelith.orchestration.infrastructure.incremental;

import online.yudream.voxelith.orchestration.domain.ManifestInvalidatePort;
import online.yudream.voxelith.tile.application.InvalidateManifestUseCase;

import java.util.Map;

/**
 * 把 tile-context 的清单局部失效用例接到编排域端口。
 * 跨上下文只依赖 tile.application（ArchUnit 合法）。
 */
public final class TileManifestInvalidateAdapter implements ManifestInvalidatePort {

    private final InvalidateManifestUseCase useCase;

    public TileManifestInvalidateAdapter(InvalidateManifestUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public void invalidate(String mapId, Map<String, String> sha1ByUrl) {
        useCase.invalidate(mapId, sha1ByUrl);
    }
}
