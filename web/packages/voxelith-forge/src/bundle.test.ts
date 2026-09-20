import { describe, expect, it } from "vitest";
import { sha1Hex } from "@yudream/voxelith-tiles";
import {
  BUNDLE_MAGIC,
  packVxtBundle,
  readBundleAsset,
  readBundleEntry,
  readBundleIndex,
  unpackBundle,
  verifyBundle,
} from "./bundle.js";
import { fakeGlb } from "./format.test.js";
import type { ManifestTile } from "@yudream/voxelith-core";

function tile(level: number, x: number, z: number, glb: Uint8Array): ManifestTile {
  const size = 32 * 2 ** level;
  return {
    level,
    x,
    z,
    url: level === 0 ? `tiles/hires/${x}/${z}.glb` : `tiles/lod/${level}/${x}/${z}.glb`,
    sha1: sha1Hex(glb),
    bytes: glb.length,
    quads: 2,
    min: [x * size, 64, z * size],
    max: [x * size + size, 70, z * size + size],
  };
}

function sources() {
  const a = fakeGlb({ asset: { version: "2.0" }, meshes: [{ primitives: [{}] }] });
  const b = fakeGlb({ asset: { version: "2.0" }, extensionsUsed: ["EXT_meshopt_compression"] });
  return [
    { tile: tile(0, 0, 0, a), glb: a },
    { tile: tile(0, 1, 0, b), glb: b },
  ];
}

describe(".vxtbundle 归档", () => {
  it("打包 → 读索引 → 取单片 → 解包，内容与原始 glb 一致", () => {
    const entries = sources();
    const bytes = packVxtBundle("demo", entries, { quantized: true }, "2026-09-20T00:00:00Z");

    expect(new TextDecoder().decode(bytes.subarray(0, 4))).toBe(BUNDLE_MAGIC);
    const { index, payloadLength } = readBundleIndex(bytes);
    expect(index.mapId).toBe("demo");
    expect(index.createdAt).toBe("2026-09-20T00:00:00Z");
    expect(index.tiles).toHaveLength(2);
    expect(index.tiles[0]).toMatchObject({ url: "tiles/hires/0/0.glb", level: 0, offset: 0 });
    expect(index.tiles[1]!.offset).toBe(index.tiles[0]!.length);
    expect(payloadLength).toBe(index.tiles.reduce((sum, entry) => sum + entry.length, 0));

    const unpacked = unpackBundle(bytes);
    expect(Array.from(unpacked.tiles.get("tiles/hires/0/0.glb")!)).toEqual(Array.from(entries[0]!.glb));
    expect(Array.from(unpacked.tiles.get("tiles/hires/1/0.glb")!)).toEqual(Array.from(entries[1]!.glb));
  });

  it("整包自检：逐片 .vxt 的 sha1 与索引坐标都对齐", () => {
    const bytes = packVxtBundle("demo", sources());
    const result = verifyBundle(bytes);
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);

    // 篡改第 2 片 payload 的最后一个字节 → 该片 sha1 检查失败
    const { index } = readBundleIndex(bytes);
    const tampered = bytes.slice();
    const entry = index.tiles[1]!;
    const { payloadOffset } = readBundleIndex(tampered);
    tampered[payloadOffset + entry.offset + entry.length - 1] =
      (tampered[payloadOffset + entry.offset + entry.length - 1]! ^ 0xff) & 0xff;
    const bad = verifyBundle(tampered);
    expect(bad.ok).toBe(false);
    expect(bad.errors.some((message) => message.includes(entry.url))).toBe(true);
  });

  it("坏容器：magic / 版本 / 索引截断 / 索引越界都明确报错", () => {
    const bytes = packVxtBundle("demo", sources());

    const wrongMagic = bytes.slice();
    wrongMagic.set(new TextEncoder().encode("XXXX"), 0);
    expect(() => readBundleIndex(wrongMagic)).toThrow(/不是 .vxtbundle/);

    const wrongVersion = bytes.slice();
    new DataView(wrongVersion.buffer).setUint16(4, 7, true);
    expect(() => readBundleIndex(wrongVersion)).toThrow(/版本/);

    expect(() => readBundleIndex(bytes.subarray(0, 8))).toThrow(/过短/);

    const truncateIndex = bytes.slice(0, 14);
    expect(() => readBundleIndex(truncateIndex)).toThrow(/索引截断/);

    // 手工造一个越界索引
    const indexJson = JSON.stringify({
      mapId: "demo",
      createdAt: "x",
      tiles: [{ url: "a.glb", level: 0, x: 0, z: 0, sha1: "s", offset: 0, length: 999 }],
    });
    const indexBytes = new TextEncoder().encode(indexJson);
    const broken = new Uint8Array(10 + indexBytes.length);
    const view = new DataView(broken.buffer);
    broken.set(new TextEncoder().encode(BUNDLE_MAGIC), 0);
    view.setUint16(4, 1, true);
    view.setUint32(6, indexBytes.length, true);
    broken.set(indexBytes, 10);
    expect(() => readBundleIndex(broken)).toThrow(/索引越界/);
  });

  it("空包也能读写（合法的边界情况：还没有瓦片）", () => {
    const bytes = packVxtBundle("empty", []);
    const { index, payloadLength } = readBundleIndex(bytes);
    expect(index.tiles).toEqual([]);
    expect(payloadLength).toBe(0);
    expect(verifyBundle(bytes).ok).toBe(true);
  });

  it("readBundleEntry 是零拷贝视图（取片不做整包复制）", () => {
    const bytes = packVxtBundle("demo", sources());
    const { index } = readBundleIndex(bytes);
    const slice = readBundleEntry(bytes, index.tiles[0]!);
    expect(slice.buffer).toBe(bytes.buffer);
    expect(slice.length).toBe(index.tiles[0]!.length);
  });

  it("assets：清单/图集能一起打包，读取是零拷贝，解包一并返回", () => {
    const entries = sources();
    const manifest = new TextEncoder().encode(JSON.stringify({
      mapId: "demo",
      atlas: { url: "atlas.png", size: 256, height: 256, textureCount: 9 },
      lodAtlases: [{ level: 1, url: "tiles/lod/1/lod-atlas.png", slotSize: 32, sha1: "x" }],
    }));
    const atlas = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]);
    const page = new Uint8Array([9, 9, 9]);
    const bytes = packVxtBundle("demo", entries, {}, "2026-09-20T00:00:00Z", [
      { key: "manifest.json", bytes: manifest },
      { key: "atlas.png", bytes: atlas },
      { key: "tiles/lod/1/lod-atlas.png", bytes: page },
    ]);

    const { index } = readBundleIndex(bytes);
    expect(index.assets?.map((asset) => asset.key)).toEqual([
      "manifest.json",
      "atlas.png",
      "tiles/lod/1/lod-atlas.png",
    ]);
    const manifestEntry = index.assets!.find((asset) => asset.key === "manifest.json")!;
    expect(Array.from(readBundleAsset(bytes, manifestEntry))).toEqual(Array.from(manifest));

    const unpacked = unpackBundle(bytes);
    expect(Array.from(unpacked.assets.get("atlas.png")!)).toEqual(Array.from(atlas));
    expect(unpacked.assets.size).toBe(3);
    expect(verifyBundle(bytes).ok).toBe(true);
  });

  it("自包含性检查：清单引用的图集没打进去时报错", () => {
    const entries = sources();
    const manifest = new TextEncoder().encode(JSON.stringify({
      mapId: "demo",
      atlas: { url: "atlas.png", size: 256, height: 256, textureCount: 9 },
    }));
    // 只放清单、不放 atlas.png
    const incomplete = packVxtBundle("demo", entries, {}, "2026-09-20T00:00:00Z", [
      { key: "manifest.json", bytes: manifest },
    ]);
    const result = verifyBundle(incomplete);
    expect(result.ok).toBe(false);
    expect(result.errors.some((message) => message.includes("atlas.png"))).toBe(true);
    expect(result.errors.some((message) => message.includes("不自包含"))).toBe(true);

    // 只有瓦片（没有 assets）的老包形态仍然合法：不做自包含检查
    expect(verifyBundle(packVxtBundle("demo", entries)).ok).toBe(true);
  });
});
