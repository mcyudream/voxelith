package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.lod.domain.heightfield.AerialRaster;
import online.yudream.voxelith.tile.application.LodAtlasPage;
import online.yudream.voxelith.tile.application.TileOutcome;

import java.util.List;

/**
 * @param tiles      生成的 LOD 瓦片摘要（level ≥ 1，供 manifest 链路合并发布）
 * @param levels     实际生成的 LOD 层级数（manifest settings.lodCount = levels + 1）
 * @param atlasPages 每层的共享图集页（仅全量生成时有值；增量沿用清单里已发布的页）。
 *                   空列表 = 该批瓦片各自内嵌色图，前端按内嵌色图渲染。
 * @param surface    逐格地表色与高度（1 格 = 1 方块），供后端全景渲染使用；
 *                   纯增量运行或没有朝上表面时为 null。调用方按需落盘，不落盘不影响渲染。
 */
public record LodOutcome(List<TileOutcome.TileSummary> tiles, int levels, List<LodAtlasPage> atlasPages,
                         AerialRaster surface) {

    public LodOutcome {
        atlasPages = atlasPages == null ? List.of() : List.copyOf(atlasPages);
    }

    public LodOutcome(List<TileOutcome.TileSummary> tiles, int levels) {
        this(tiles, levels, List.of(), null);
    }

    public LodOutcome(List<TileOutcome.TileSummary> tiles, int levels, List<LodAtlasPage> atlasPages) {
        this(tiles, levels, atlasPages, null);
    }
}
