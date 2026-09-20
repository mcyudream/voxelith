import { describe, expect, it } from "vitest";
import { parseManifest, type ManifestTile, type MapManifest } from "@yudream/voxelith-core";
import { levelTileBounds, levelTilesWithUv, lodAtlasUvRect } from "./lodAtlas.js";

/**
 * 测例刻意用**非 2 的幂网格 + 负坐标**：真实地图从 (0,0) 向负方向扩展，
 * 槽位定位一旦用错基准（比如忘记减 minTileX），负坐标那半边就会整片错色。
 */
function manifestWithAtlas(): MapManifest {
  const tiles: ManifestTile[] = [];
  const push = (level: number, x: number, z: number): void => {
    const size = 32 * 2 ** level;
    tiles.push({
      level,
      x,
      z,
      url: `tiles/lod/${level}/${x}/${z}.glb`,
      sha1: `${level}${x}${z}`.padEnd(8, "0"),
      bytes: 100,
      quads: 4,
      min: [x * size, 64, z * size],
      max: [x * size + size, 70, z * size + size],
    });
  };
  // level 2 的网格：x ∈ [-1, 1]，z ∈ [0, 2] → 3×3 槽位，slotSize=32 → 96×96 页
  for (let x = -1; x <= 1; x++) {
    for (let z = 0; z <= 2; z++) {
      push(2, x, z);
    }
  }
  return parseManifest({
    formatVersion: 1,
    mapId: "demo",
    name: "演示",
    version: "v1",
    generatedAt: "2026-09-20T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 3 },
    boundsMin: [-128, 64, 0],
    boundsMax: [128, 70, 384],
    atlas: { url: "atlas.png", size: 256, textureCount: 9 },
    lodAtlases: [{ level: 2, url: "tiles/lod/2/lod-atlas.png", slotSize: 32, sha1: "abc" }],
    tiles,
  });
}

describe("LOD 图集页槽位换算", () => {
  it("层瓦片范围按实际瓦片算（含负坐标）", () => {
    const bounds = levelTileBounds(manifestWithAtlas(), 2);
    expect(bounds).toEqual({ minX: -1, maxX: 1, minZ: 0, maxZ: 2 });
    expect(levelTileBounds(manifestWithAtlas(), 0)).toBeNull();
  });

  it("UV 矩形与后端 LodAtlasPacker 同一套公式（行主序 + 半纹素内缩）", () => {
    const manifest = manifestWithAtlas();
    // 槽位 (0,0) = 最小 x/z 的那格；页 96×96，slot 32，内缩 0.5 像素
    const first = lodAtlasUvRect(manifest, 2, -1, 0)!;
    expect(first.pageWidth).toBe(96);
    expect(first.pageHeight).toBe(96);
    expect(first.u0).toBeCloseTo(0.5 / 96, 8);
    expect(first.v0).toBeCloseTo(0.5 / 96, 8);
    expect(first.u1).toBeCloseTo(31.5 / 96, 8);
    expect(first.v1).toBeCloseTo(31.5 / 96, 8);

    // 槽位 (2,1) = 第 3 列第 2 行
    const middle = lodAtlasUvRect(manifest, 2, 1, 1)!;
    expect(middle.u0).toBeCloseTo((2 * 32 + 0.5) / 96, 8);
    expect(middle.v0).toBeCloseTo((1 * 32 + 0.5) / 96, 8);
    expect(middle.u1).toBeCloseTo((3 * 32 - 0.5) / 96, 8);
    expect(middle.v1).toBeCloseTo((2 * 32 - 0.5) / 96, 8);
    expect(middle.url).toBe("tiles/lod/2/lod-atlas.png");
  });

  it("相邻槽位不重叠（半纹素内缩后首尾相接）", () => {
    const manifest = manifestWithAtlas();
    const left = lodAtlasUvRect(manifest, 2, -1, 0)!;
    const right = lodAtlasUvRect(manifest, 2, 0, 0)!;
    expect(right.u0).toBeGreaterThan(left.u1);
  });

  it("没有图集页 / 越界 / 层内无瓦片都返回 null（调用方据此退回 glb）", () => {
    const manifest = manifestWithAtlas();
    expect(lodAtlasUvRect(manifest, 1, 0, 0)).toBeNull();      // 该层没有声明页
    expect(lodAtlasUvRect(manifest, 2, 5, 5)).toBeNull();      // 越界
    expect(lodAtlasUvRect(manifest, 0, 0, 0)).toBeNull();      // 层内无瓦片
  });

  it("整层枚举按 z 再 x 稳定排序，条数与该层瓦片一致", () => {
    const entries = levelTilesWithUv(manifestWithAtlas(), 2);
    expect(entries).toHaveLength(9);
    expect(entries[0]).toMatchObject({ tile: { x: -1, z: 0 } });
    expect(entries[8]).toMatchObject({ tile: { x: 1, z: 2 } });
  });
});
