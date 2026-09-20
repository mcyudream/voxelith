import { describe, expect, it } from "vitest";
import { parseManifest } from "./index.js";

/**
 * 清单协议：lodAtlases 是「LOD 每层共享图集页」的声明。
 * 必须同时满足两件事——老清单（无该字段）能读、新清单能读到页。
 */
function baseManifest(extra: Record<string, unknown> = {}) {
  return {
    formatVersion: 1,
    mapId: "demo",
    name: "演示",
    version: "0123456789ab",
    generatedAt: "2026-01-01T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 3 },
    boundsMin: [0, 0, 0],
    boundsMax: [64, 64, 64],
    atlas: { url: "atlas.png", size: 16, textureCount: 1 },
    tiles: [
      {
        level: 1,
        x: 0,
        z: 0,
        url: "tiles/lod/1/0/0.glb",
        sha1: "abc",
        bytes: 10,
        quads: 1,
        min: [0, 0, 0],
        max: [64, 64, 64],
      },
    ],
    ...extra,
  };
}

describe("parseManifest lodAtlases", () => {
  it("缺省为空数组（老清单必须能读）", () => {
    expect(parseManifest(baseManifest()).lodAtlases).toEqual([]);
  });

  it("解析每层图集页", () => {
    const manifest = parseManifest(baseManifest({
      lodAtlases: [
        { level: 1, url: "tiles/lod/1/lod-atlas.png", slotSize: 64, sha1: "deadbeef" },
        { level: 2, url: "tiles/lod/2/lod-atlas.png", slotSize: 32, sha1: "cafebabe" },
      ],
    }));

    expect(manifest.lodAtlases).toHaveLength(2);
    expect(manifest.lodAtlases[0]).toEqual({
      level: 1,
      url: "tiles/lod/1/lod-atlas.png",
      slotSize: 64,
      sha1: "deadbeef",
    });
    expect(manifest.lodAtlases[1]?.slotSize).toBe(32);
  });

  it("拒绝层级 0 / 空 url / 非正槽位（协议层把明显的坏数据挡在外面）", () => {
    const bad = [
      [{ level: 0, url: "x.png", slotSize: 64, sha1: "a" }],
      [{ level: 1, url: "", slotSize: 64, sha1: "a" }],
      [{ level: 1, url: "x.png", slotSize: 0, sha1: "a" }],
      [{ level: 1, url: "x.png", slotSize: 64, sha1: "" }],
    ];
    for (const lodAtlases of bad) {
      expect(() => parseManifest(baseManifest({ lodAtlases }))).toThrow();
    }
  });
});

describe("清单图集引用（增量扩图集）", () => {
  it("老清单没有 height：仍可解析，前端按正方形处理", () => {
    const manifest = parseManifest(baseManifest());
    expect(manifest.atlas.size).toBe(16);
    expect(manifest.atlas.height).toBeUndefined();
  });

  it("增量扩图集后高度大于宽度：height 如实透传", () => {
    const manifest = parseManifest(baseManifest({
      atlas: { url: "atlas.png", size: 256, height: 320, textureCount: 150 },
    }));
    expect(manifest.atlas.size).toBe(256);
    expect(manifest.atlas.height).toBe(320);
  });

  it("height 非法（0 / 负数）时拒绝", () => {
    for (const height of [0, -8]) {
      expect(() =>
        parseManifest(baseManifest({ atlas: { url: "atlas.png", size: 256, height, textureCount: 1 } })),
      ).toThrow();
    }
  });
});
