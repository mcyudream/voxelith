package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.lod.domain.heightfield.Heightfield;

import java.util.Optional;

/**
 * 全图柱状高度场仓储：增量 LOD 先清 region 再 merge 后写回。
 * 实现位于 infrastructure（heightfield.bin）。
 */
public interface HeightfieldStore {

    Optional<Heightfield> load();

    void save(Heightfield field);
}
