import { describe, expect, it } from "vitest";
import { parseManifest, type ManifestTile, type MapManifest } from "@yudream/voxelith-core";
import { sha1Hex } from "@yudream/voxelith-tiles";
import { auditManifest, formatAuditReport } from "./audit.js";
import { fakeGlb, fakePng } from "./format.test.js";

/** 一张可审计的小地图：2×2 hires + 1 片 LOD1 + hier 主图集 + LOD 图集页。 */
function fixture(): { manifest: MapManifest; files: Map<string, Uint8Array>; glb: Uint8Array } {
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
  const tiles: ManifestTile[] = [];
  const files = new Map<string, Uint8Array>();
  const push = (level: number, x: number, z: number, bytes: Uint8Array): void => {
    const size = 32 * 2 ** level;
    const url = level === 0 ? `tiles/hires/${x}/${z}.glb` : `tiles/lod/${level}/${x}/${z}.glb`;
    tiles.push({
      level,
      x,
      z,
      url,
      sha1: sha1Hex(bytes),
      bytes: bytes.length,
      quads: 4,
      min: [x * size, 64, z * size],
      max: [x * size + size, 80, z * size + size],
    });
    files.set(url, bytes);
  };
  for (let x = 0; x < 2; x++) {
    for (let z = 0; z < 2; z++) {
      push(0, x, z, glb);
    }
  }
  push(1, 0, 0, lodGlb);
  files.set("atlas.png", fakePng(256, 256));
  // LOD1 网格 1×1，slot 32 → 页 32×32
  files.set("tiles/lod/1/lod-atlas.png", fakePng(32, 32));

  const manifest = parseManifest({
    formatVersion: 1,
    mapId: "demo",
    name: "演示",
    version: "v1",
    generatedAt: "2026-09-20T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 2 },
    boundsMin: [0, 64, 0],
    boundsMax: [64, 80, 64],
    atlas: { url: "atlas.png", size: 256, height: 256, textureCount: 9 },
    lodAtlases: [{ level: 1, url: "tiles/lod/1/lod-atlas.png", slotSize: 32, sha1: "page" }],
    tiles,
  });
  return { manifest, files, glb };
}

function reader(files: Map<string, Uint8Array>) {
  return (url: string): Uint8Array | null => files.get(url) ?? null;
}

describe("产物审计", () => {
  it("健康产物：通过，并给出统计", () => {
    const { manifest, files } = fixture();
    const report = auditManifest(manifest, { readFile: reader(files), glbSampleSize: 5 });
    expect(report.issues.filter((issue) => issue.severity === "error")).toEqual([]);
    expect(report.ok).toBe(true);
    expect(report.stats).toMatchObject({
      tiles: 5,
      hiresTiles: 4,
      lodTiles: 1,
      levels: 2,
      // LOD 那片没声明扩展，所以抽样命中 4 片 hires
      sampledMeshopt: 4,
      sampledQuantized: 4,
    });
    expect(report.stats.totalBytes).toBeGreaterThan(0);
    expect(formatAuditReport(report)).toContain("审计通过");
  });

  it("缺瓦片 / 字节数不符 / sha1 不符都报 error", () => {
    const { manifest, files, glb } = fixture();
    files.delete("tiles/hires/1/1.glb");
    const report = auditManifest(manifest, { readFile: reader(files) });
    expect(report.ok).toBe(false);
    expect(report.issues.map((issue) => issue.code)).toContain("tile-missing");

    const withWrongBytes = fixture();
    withWrongBytes.files.set("tiles/hires/0/0.glb", new Uint8Array(glb.length + 4));
    const bytesIssue = auditManifest(withWrongBytes.manifest, { readFile: reader(withWrongBytes.files) });
    expect(bytesIssue.issues.map((issue) => issue.code)).toContain("tile-size-mismatch");

    const withTampered = fixture();
    const tampered = withTampered.glb.slice();
    tampered[tampered.length - 1] = (tampered[tampered.length - 1]! ^ 0xff) & 0xff;
    withTampered.files.set("tiles/hires/0/0.glb", tampered);
    const shaIssue = auditManifest(withTampered.manifest, { readFile: reader(withTampered.files) });
    expect(shaIssue.issues.map((issue) => issue.code)).toContain("tile-sha1-mismatch");
    expect(shaIssue.ok).toBe(false);
  });

  it("图集尺寸与 LOD 图集页尺寸按打包器约定交叉校验", () => {
    const atlasMismatch = fixture();
    atlasMismatch.files.set("atlas.png", fakePng(128, 128));
    const atlasReport = auditManifest(atlasMismatch.manifest, {
      readFile: reader(atlasMismatch.files),
    });
    expect(atlasReport.issues.map((issue) => issue.code)).toContain("atlas-size-mismatch");

    const pageMismatch = fixture();
    pageMismatch.files.set("tiles/lod/1/lod-atlas.png", fakePng(64, 64));
    const pageReport = auditManifest(pageMismatch.manifest, {
      readFile: reader(pageMismatch.files),
    });
    const issue = pageReport.issues.find((entry) => entry.code === "lod-atlas-size-mismatch");
    expect(issue).toBeDefined();
    expect(issue!.message).toContain("网格 1×1");
  });

  it("glb 结构问题（magic/长度）与 LOD 无纹理的告警", () => {
    const bad = fixture();
    const broken = fakeGlb({ asset: { version: "2.0" } }, { declaredLengthDelta: 8, magic: "XXXX" });
    bad.files.set("tiles/hires/0/0.glb", broken);
    const manifest = parseManifest({
      ...bad.manifest,
      tiles: bad.manifest.tiles.map((tile) =>
        tile.url === "tiles/hires/0/0.glb"
          ? { ...tile, sha1: sha1Hex(broken), bytes: broken.length }
          : tile),
    });
    const report = auditManifest(manifest, { readFile: reader(bad.files), glbSampleSize: 5 });
    const codes = report.issues.map((issue) => issue.code);
    expect(codes).toContain("glb-bad-magic");
    expect(codes).toContain("glb-length-mismatch");

    // 去掉 LOD 图集页声明：LOD 瓦片既不内嵌色图也没有页 → 告警
    const noPage = parseManifest({ ...bad.manifest, lodAtlases: [] });
    const warnReport = auditManifest(noPage, { readFile: reader(bad.files), glbSampleSize: 5 });
    expect(warnReport.issues.map((issue) => issue.code)).toContain("lod-tile-without-texture");
  });

  it("清单自洽问题：url 重复 / sha1 形状 / 层级越界 / 没有 hires", () => {
    const { manifest } = fixture();
    const duplicated = parseManifest({
      ...manifest,
      tiles: [...manifest.tiles, { ...manifest.tiles[0]!, x: 9, z: 9 }],
    });
    const dupReport = auditManifest(duplicated, {});
    expect(dupReport.issues.map((issue) => issue.code)).toContain("duplicate-url");

    const badSha = parseManifest({
      ...manifest,
      tiles: manifest.tiles.map((tile, index) => (index === 0 ? { ...tile, sha1: "zzz" } : tile)),
    });
    expect(auditManifest(badSha, {}).issues.map((issue) => issue.code)).toContain("bad-sha1");

    const tooDeep = parseManifest({
      ...manifest,
      settings: { hiresTileSize: 32, lodCount: 1 },
    });
    expect(auditManifest(tooDeep, {}).issues.map((issue) => issue.code))
      .toContain("level-exceeds-lod-count");

    const noHires = parseManifest({
      ...manifest,
      tiles: manifest.tiles.filter((tile) => tile.level > 0),
    });
    expect(auditManifest(noHires, {}).issues.map((issue) => issue.code)).toContain("no-hires-tiles");
  });

  it("目录名与清单 mapId 不一致时告警（不改判定为失败）", () => {
    const { manifest, files } = fixture();
    const report = auditManifest(manifest, { readFile: reader(files), expectedMapId: "swust" });
    expect(report.ok).toBe(true);
    expect(report.issues.map((issue) => issue.code)).toContain("map-id-mismatch");
  });

  it("不注入读取器时只做不需要读盘的检查", () => {
    const { manifest } = fixture();
    const report = auditManifest(manifest, {});
    expect(report.ok).toBe(true);
    expect(report.issues).toEqual([]);
  });
});
