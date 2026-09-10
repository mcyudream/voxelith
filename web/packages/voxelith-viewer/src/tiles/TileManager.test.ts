import * as THREE from "three";
import { describe, expect, it } from "vitest";
import type { MapManifest, ManifestTile } from "@yudream/voxelith-core";
import { TileManager } from "./TileManager.js";

/**
 * 构造一棵小型 LOD 金字塔：hiresTileSize=32，lodCount=3（层级 0/1/2）。
 * L2(0,0) 覆盖 [0,128]²，其下完整展开到 L0；L2(1,0) 覆盖 [128,256]² 且无子瓦片。
 */
function tile(level: number, x: number, z: number): ManifestTile {
  const size = 32 * 2 ** level;
  return {
    level,
    x,
    z,
    url: `tiles/l${level}/${x}/${z}.glb`,
    sha1: "test",
    bytes: 1,
    quads: 1,
    min: [x * size, 0, z * size],
    max: [(x + 1) * size, 64, (z + 1) * size],
  };
}

function makeManifest(): MapManifest {
  const tiles: ManifestTile[] = [tile(2, 0, 0), tile(2, 1, 0)];
  // L2(0,0) 的 L1 子树
  for (const [x, z] of [[0, 0], [1, 0], [0, 1], [1, 1]] as const) {
    tiles.push(tile(1, x, z));
  }
  // L1(0,0) 与 L1(1,0) 的 L0 子树
  for (const [x, z] of [[0, 0], [1, 0], [0, 1], [1, 1], [2, 0], [3, 0], [2, 1], [3, 1]] as const) {
    tiles.push(tile(0, x, z));
  }
  return {
    formatVersion: 1,
    mapId: "test",
    name: "test",
    version: "v1",
    generatedAt: "2026-01-01T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 3 },
    boundsMin: [0, 0, 0],
    boundsMax: [256, 64, 128],
    atlas: { url: "atlas.png", size: 128, textureCount: 1 },
    tiles,
  };
}

/** 跳过 update()（避免真实加载），直接设置视点并取期望渲染集合的 key 列表。 */
function desiredKeys(tm: TileManager, x: number, y: number, z: number): string[] {
  const inner = tm as unknown as {
    viewPosition: THREE.Vector3;
    collectDesired: () => ManifestTile[];
  };
  inner.viewPosition.set(x, y, z);
  return inner
    .collectDesired()
    .map((t) => `${t.level}:${t.x}:${t.z}`)
    .sort();
}

describe("TileManager 细节视距（视距外逐级 LOD）", () => {
  it("默认不限视距：近处按常规规则细分到 hires", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
    });
    const keys = desiredKeys(tm, 16, 10, 16);
    // 相机所在角落细分到 L0
    expect(keys).toContain("0:0:0");
    expect(keys).toContain("0:1:1");
    // 48 方块外的 L1(1,0) 在不限视距时也细分到 L0
    expect(keys).toContain("0:2:0");
    // 无子瓦片的远端 L2 保持原级
    expect(keys).toContain("2:1:0");
  });

  it("细节视距内标准渲染，视距外逐级变粗且不超过最粗层", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      detailDistanceChunks: 3, // 48 方块
    });
    const keys = desiredKeys(tm, 16, 10, 16);
    // 视距内（dxz < 48）：hires
    expect(keys).toContain("0:0:0");
    // 第一环 [48,96)：最细到 L1，不再细分出 L0
    expect(keys).toContain("1:1:0");
    expect(keys).toContain("1:0:1");
    expect(keys).not.toContain("0:2:0");
    // 远端（dxz ≈ 112 → [96,192) 环）：封顶最粗层 L2
    expect(keys).toContain("2:1:0");
  });

  it("setDetailDistanceBlocks(Infinity) 恢复全量细分", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      detailDistanceChunks: 3,
    });
    tm.setDetailDistanceBlocks(Infinity);
    const keys = desiredKeys(tm, 16, 10, 16);
    expect(keys).toContain("0:2:0");
    expect(tm.detailDistanceBlocks).toBe(Infinity);
  });

  it("高空斜视：水平距离仍判定为视距内（高度差不计入细节视距）", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      detailDistanceChunks: 3,
    });
    // 相机在 (16,10,16) 正上方 200m：3D 距离 ~136 仍在细化门限内，
    // 若细节视距按 3D 距离算，正下方 L1(0,0) 会被判到第二环而停在 L1；
    // 按水平距离算则 dxz=0 < 48，正常细分到 hires
    const keys = desiredKeys(tm, 16, 200, 16);
    expect(keys).toContain("0:0:0");
    expect(keys).toContain("1:1:0");
    expect(keys).not.toContain("0:2:0");
  });
});
