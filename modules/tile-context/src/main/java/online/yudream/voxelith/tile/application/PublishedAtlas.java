package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult;

import java.util.Optional;

/**
 * 已发布图集查询端口：增量重跑优先复用，避免重打包打乱 UV。
 * 实现位于 infrastructure（读 atlas-layout.json + atlas.png）；application 只依赖本接口。
 */
public interface PublishedAtlas {

    Optional<AtlasReuse> load(String mapId);

    /** 全量 tile 完成后把 layout + png 一并落盘，供后续增量读取。 */
    void save(String mapId, AtlasResult atlas, byte[] png);
}
