package online.yudream.voxelith.orchestration.infrastructure.incremental;

import online.yudream.voxelith.orchestration.domain.IncrementalPatch;
import online.yudream.voxelith.orchestration.domain.ManifestInvalidatePort;
import online.yudream.voxelith.tile.application.InvalidateManifestUseCase;
import online.yudream.voxelith.tile.application.ManifestPatch;

import java.util.ArrayList;
import java.util.List;

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
    public void invalidate(String mapId, IncrementalPatch patch) {
        List<ManifestPatch.NewTile> inserts = new ArrayList<>(patch.inserts().size());
        for (IncrementalPatch.NewTile tile : patch.inserts()) {
            inserts.add(new ManifestPatch.NewTile(
                    tile.url(), tile.level(), tile.x(), tile.z(),
                    tile.sha1(), tile.bytes(), tile.quads(), tile.min(), tile.max()));
        }
        useCase.invalidate(mapId, new ManifestPatch(patch.sha1ByUrl(), inserts));
    }
}
