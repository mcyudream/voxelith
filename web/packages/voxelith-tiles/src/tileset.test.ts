import { describe, expect, it } from "vitest";
import { parseManifest, type ManifestTile, type MapManifest } from "@yudream/voxelith-core";
import { geometricErrorFor, regionForBounds, tileWorldSize, tilesetContents, toTileset } from "./tileset.js";

/** 造一份三级 LOD 的清单：L2 一片 → L1 四片 → L0 十六片。 */
function pyramid(): MapManifest {
  const tiles: ManifestTile[] = [];
  const push = (level: number, x: number, z: number): void => {
    const size = 32 * 2 ** level;
    tiles.push({
      level,
      x,
      z,
      url: level === 0 ? `tiles/hires/${x}/${z}.glb` : `tiles/lod/${level}/${x}/${z}.glb`,
      sha1: `${level}${x}${z}`.padEnd(8, "0"),
      bytes: 1000,
      quads: 10,
      min: [x * size, 60, z * size],
      max: [x * size + size, 60 + 8 * (level + 1), z * size + size],
    });
  };
  for (let x = 0; x < 2; x++) {
    for (let z = 0; z < 2; z++) {
      push(1, x, z);
    }
  }
  for (let x = 0; x < 4; x++) {
    for (let z = 0; z < 4; z++) {
      push(0, x, z);
    }
  }
  push(2, 0, 0);
  return parseManifest({
    formatVersion: 1,
    mapId: "demo",
    name: "演示",
    version: "v1",
    generatedAt: "2026-09-20T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 3 },
    boundsMin: [0, 60, 0],
    boundsMax: [128, 84, 128],
    atlas: { url: "atlas.png", size: 256, height: 256, textureCount: 9 },
    tiles,
  });
}

describe("3D Tiles 转换", () => {
  it("瓦片边长与 geometricError 按层级翻倍递减", () => {
    const manifest = pyramid();
    expect(tileWorldSize(manifest, 0)).toBe(32);
    expect(tileWorldSize(manifest, 2)).toBe(128);
    expect(geometricErrorFor(32, 1, 16)).toBe(2);
    expect(geometricErrorFor(128, 1, 16)).toBe(8);
    // 米制缩放同样线性
    expect(geometricErrorFor(32, 0.5, 16)).toBe(1);
  });

  it("region 包围体按锚点换算经纬高（弧度 / 米）", () => {
    const region = regionForBounds([0, 60, 0], [128, 84, 128], {
      anchor: { lonDeg: 104.06, latDeg: 30.67 },
      metersPerBlock: 2,
    }).region;
    const [west, south, east, north, minHeight, maxHeight] = region;
    expect(west).toBeCloseTo((104.06 * Math.PI) / 180, 6);
    expect(south).toBeCloseTo((30.67 * Math.PI) / 180, 6);
    expect(east).toBeGreaterThan(west!);
    expect(north).toBeGreaterThan(south!);
    expect(minHeight).toBeCloseTo(120, 6);   // 60 方块 × 2 米
    expect(maxHeight).toBeCloseTo(168, 6);   // 84 方块 × 2 米
  });

  it("退化高度区间会被撑开 1 厘米（region 不允许高度为 0）", () => {
    const [, , , , minHeight, maxHeight] = regionForBounds([0, 64, 0], [32, 64, 32]).region;
    expect(maxHeight - minHeight).toBeGreaterThan(0);
    expect(maxHeight - minHeight).toBeCloseTo(0.01, 6);
  });

  it("清单 → tileset：单根四叉树、每片一个 content、层级向细递减", () => {
    const tileset = toTileset(pyramid(), {
      anchor: { lonDeg: 104.06, latDeg: 30.67 },
      metersPerBlock: 1,
    });

    expect(tileset.asset.version).toBe("1.1");
    expect(tileset.geometricError).toBe(tileset.root.geometricError);
    // 根是 L2（最粗），geometricError = 128/16 = 8
    expect(tileset.root.extras).toMatchObject({ level: 2, x: 0, z: 0 });
    expect(tileset.root.geometricError).toBe(8);
    expect(tileset.root.content?.uri).toBe("tiles/lod/2/0/0.glb");
    // 一层四叉：L2 → 4 个 L1 → 每个 L1 下 4 个 L0
    expect(tileset.root.children).toHaveLength(4);
    for (const child of tileset.root.children!) {
      expect(child.extras).toMatchObject({ level: 1 });
      expect(child.geometricError).toBe(4);
      expect(child.children).toHaveLength(4);
      for (const leaf of child.children!) {
        expect(leaf.extras).toMatchObject({ level: 0 });
        expect(leaf.geometricError).toBe(2);
        expect(leaf.children).toBeUndefined();
      }
    }
    expect(tileset.root.refine).toBe("ADD");
  });

  it("图不连通（多棵根）时套一个无 content 的合成根，不丢瓦片", () => {
    const manifest = pyramid();
    // 删掉最粗层 L2(0,0)：四个 L1 就没有祖先可挂，各自成为一棵树的根
    const pruned = parseManifest({
      ...manifest,
      tiles: manifest.tiles.filter((tile) => tile.level !== 2),
    });
    const tileset = toTileset(pruned);
    expect(tileset.root.content).toBeUndefined();
    expect(tileset.root.children).toHaveLength(4);
    expect(tileset.root.children!.every((child) => child.extras?.level === 1)).toBe(true);

    const contents = tilesetContents(tileset);
    expect(contents).toHaveLength(pruned.tiles.length);
    expect(new Set(contents.map((entry) => entry.uri)).size).toBe(pruned.tiles.length);
  });

  it("自定义 content uri 与 REPLACE 细化", () => {
    const tileset = toTileset(pyramid(), {
      contentUri: (tile) => `https://cdn.example.com/maps/demo/${tile.url}`,
      refine: "REPLACE",
    });
    expect(tileset.root.content?.uri).toMatch(/^https:\/\/cdn\.example\.com\/maps\/demo\//);
    expect(tileset.root.refine).toBe("REPLACE");
  });

  it("tilesetContents 还原层级：深度越大层级越低", () => {
    const contents = tilesetContents(toTileset(pyramid()));
    const byLevel = new Map<number, number>();
    for (const entry of contents) {
      byLevel.set(entry.level, (byLevel.get(entry.level) ?? 0) + 1);
    }
    expect(byLevel.get(2)).toBe(1);
    expect(byLevel.get(1)).toBe(4);
    expect(byLevel.get(0)).toBe(16);
    // 根（最粗层）深度最小
    const rootEntry = contents.find((entry) => entry.level === 2)!;
    expect(rootEntry.depth).toBe(0);
  });
});
