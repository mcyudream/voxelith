import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parseManifest, type ManifestTile } from "@yudream/voxelith-core";
import { readVxt, sha1Hex } from "@yudream/voxelith-tiles";
import {
  auditMapDir,
  exportTileset,
  loadMapDir,
  mapStats,
  packMapDir,
  tileExtensionSummary,
} from "./node.js";
import { fakeGlb, fakePng } from "./format.test.js";
import { readBundleIndex } from "./bundle.js";

/** 在临时目录里落一张真实的小地图（清单 + 4 片 hires + 图集）。 */
function writeMap(mapId = "demo"): string {
  const dir = mkdtempSync(join(tmpdir(), "voxelith-forge-"));
  mkdirSync(join(dir, "tiles/hires/0"), { recursive: true });
  mkdirSync(join(dir, "tiles/hires/1"), { recursive: true });
  mkdirSync(join(dir, "tiles/lod/1/0"), { recursive: true });

  const glb = fakeGlb({
    asset: { version: "2.0" },
    extensionsUsed: ["KHR_mesh_quantization", "EXT_meshopt_compression"],
    accessors: [{}],
    meshes: [{ primitives: [{}] }],
  });
  const lodGlb = fakeGlb({
    asset: { version: "2.0" },
    accessors: [{}],
    meshes: [{ primitives: [{}] }],
    images: [{ uri: "lod-atlas.png" }],
  });
  const atlas = fakePng(256, 256);
  const lodAtlas = fakePng(32, 32);

  const tiles: ManifestTile[] = [];
  const write = (level: number, x: number, z: number, bytes: Uint8Array): void => {
    const size = 32 * 2 ** level;
    const url = level === 0 ? `tiles/hires/${x}/${z}.glb` : `tiles/lod/${level}/${x}/${z}.glb`;
    writeFileSync(join(dir, url), bytes);
    tiles.push({
      level,
      x,
      z,
      url,
      sha1: sha1Hex(bytes),
      bytes: bytes.length,
      quads: 2,
      min: [x * size, 64, z * size],
      max: [x * size + size, 72, z * size + size],
    });
  };
  write(0, 0, 0, glb);
  write(0, 1, 0, glb);
  write(0, 0, 1, glb);
  write(0, 1, 1, glb);
  write(1, 0, 0, lodGlb);
  writeFileSync(join(dir, "atlas.png"), atlas);
  writeFileSync(join(dir, "tiles/lod/1/lod-atlas.png"), lodAtlas);

  const manifest = parseManifest({
    formatVersion: 1,
    mapId,
    name: "演示",
    version: "v1",
    generatedAt: "2026-09-20T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 2 },
    boundsMin: [0, 64, 0],
    boundsMax: [64, 72, 64],
    atlas: { url: "atlas.png", size: 256, height: 256, textureCount: 9 },
    lodAtlases: [{ level: 1, url: "tiles/lod/1/lod-atlas.png", slotSize: 32, sha1: sha1Hex(lodAtlas) }],
    tiles,
  });
  writeFileSync(join(dir, "manifest.json"), JSON.stringify(manifest, null, 2));
  return dir;
}

describe("Node 侧封装（真实文件系统）", () => {
  it("loadMapDir 读清单、列文件、拒绝越界路径", () => {
    const dir = writeMap();
    const loaded = loadMapDir(dir);
    expect(loaded.manifest.mapId).toBe("demo");
    expect(loaded.listFiles()).toContain("tiles/hires/0/0.glb");
    expect(loaded.listFiles()).toContain("manifest.json");
    expect(loaded.readFile("tiles/hires/0/0.glb")).toBeInstanceOf(Uint8Array);
    expect(loaded.readFile("nope.glb")).toBeNull();
    expect(() => loaded.readFile("../outside.glb")).toThrow(/越界/);
  });

  it("auditMapDir 对真实目录给出通过结论（目录名与 mapId 一致）", () => {
    const dir = writeMap();
    const report = auditMapDir(dir);
    expect(report.ok).toBe(true);
    expect(report.issues.filter((issue) => issue.severity === "error")).toEqual([]);
    expect(report.stats).toMatchObject({ tiles: 5, hiresTiles: 4, lodTiles: 1 });
  });

  it("mapStats 与 tileExtensionSummary 反映压缩与内嵌情况", () => {
    const dir = writeMap();
    const stats = mapStats(dir);
    expect(stats.tiles).toBe(5);
    const summary = tileExtensionSummary(dir, 10);
    expect(summary.sampled).toBe(5);
    expect(summary.meshopt).toBe(4);   // LOD 那片没声明 meshopt
    expect(summary.embeddedImage).toBe(0);
    expect(summary.mismatchedLength).toBe(0);
  });

  it("exportTileset 写出可解析的 tileset.json（含锚点换算）", () => {
    const dir = writeMap();
    const out = join(dir, "tileset.json");
    const result = exportTileset(dir, out, {
      anchor: { lonDeg: 104.06, latDeg: 30.67 },
      metersPerBlock: 1,
    });
    expect(result.tiles).toBe(5);
    const tileset = JSON.parse(readFileSync(out, "utf8")) as {
      asset: { version: string };
      root: { children?: unknown[]; geometricError: number };
    };
    expect(tileset.asset.version).toBe("1.1");
    expect(tileset.root.geometricError).toBeGreaterThan(0);
    expect(tileset.root.children).toHaveLength(4);
  });

  it("packMapDir 打出 .vxtbundle，且每片能被 .vxt 读取器解开", () => {
    const dir = writeMap();
    const out = join(dir, "demo.vxtbundle");
    const result = packMapDir(dir, out);
    expect(result.tiles).toBe(5);
    expect(result.assets).toBeGreaterThanOrEqual(3);   // 清单 + 主图集 + LOD 图集页
    expect(result.verified).toBe(true);

    const bytes = new Uint8Array(readFileSync(out));
    const { index } = readBundleIndex(bytes);
    expect(index.mapId).toBe("demo");
    expect(index.tiles).toHaveLength(5);
    // 自包含：清单与图集都在包里，落地后不用再找旁路文件
    expect(index.assets?.map((asset) => asset.key)).toContain("manifest.json");
    expect(index.assets?.map((asset) => asset.key)).toContain("atlas.png");
    expect(index.assets?.map((asset) => asset.key)).toContain("tiles/lod/1/lod-atlas.png");

    // 逐片用 .vxt 解析，头部元数据与清单一致
    for (const entry of index.tiles) {
      const { payloadOffset } = readBundleIndex(bytes);
      const slice = bytes.subarray(payloadOffset + entry.offset, payloadOffset + entry.offset + entry.length);
      const file = readVxt(slice);
      expect(file.header.tile).toMatchObject({ level: entry.level, x: entry.x, z: entry.z });
    }
  });
});
