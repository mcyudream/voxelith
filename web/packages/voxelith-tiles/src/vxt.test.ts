import { describe, expect, it } from "vitest";
import { parseManifest } from "@yudream/voxelith-core";
import { sha1Hex } from "./sha1.js";
import { encodeVxt, peekVxt, readVxt, verifyVxt, writeVxt, VXT_MAGIC } from "./vxt.js";

/** 造一片最小 glb（12 字节头 + JSON chunk），够验证容器布局。 */
function fakeGlb(payload = "gltf"): Uint8Array {
  const json = new TextEncoder().encode('{"asset":{"version":"2.0"}}');
  const padded = (json.length + 3) & ~3;
  const total = 12 + 8 + padded;
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  out.set(new TextEncoder().encode(payload), 0); // magic 用调用方给的串，便于造“不同内容”
  view.setUint32(4, 2, true);
  view.setUint32(8, total, true);
  view.setUint32(12, padded, true);
  view.setUint32(16, 0x4e4f534a, true); // "JSON"
  out.set(json, 20);
  return out;
}

function tileOf(glb: Uint8Array, level = 0, x = 3, z = -2) {
  return {
    level,
    x,
    z,
    url: level === 0 ? `tiles/hires/${x}/${z}.glb` : `tiles/lod/${level}/${x}/${z}.glb`,
    sha1: sha1Hex(glb),
    bytes: glb.length,
    quads: 12,
    min: [x * 32, 60, z * 32] as [number, number, number],
    max: [x * 32 + 32, 96, z * 32 + 32] as [number, number, number],
  };
}

describe("sha1", () => {
  it("与标准向量一致（后端 HexFormat 输出同源）", () => {
    expect(sha1Hex(new TextEncoder().encode(""))).toBe("da39a3ee5e6b4b0d3255bfef95601890afd80709");
    expect(sha1Hex(new TextEncoder().encode("abc"))).toBe("a9993e364706816aba3e25717850c26c9cd0d89d");
    expect(sha1Hex(new TextEncoder().encode("The quick brown fox jumps over the lazy dog")))
      .toBe("2fd4e1c67a2d28fced849ee1bb76e7391b93eb12");
  });

  it("跨 64 字节块边界与多块填充仍然正确", () => {
    // FIPS 180-1 的经典向量：56 字节（块边界）、百万字符（多块 + 长度编码）
    expect(sha1Hex(new TextEncoder().encode(
      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
    ))).toBe("84983e441c3bd26ebaae4aa1f95129e5e54670f1");
    expect(sha1Hex(new TextEncoder().encode("a".repeat(1_000_000))))
      .toBe("34aa973cd4c4daa4f61eeb2bdbad27316534016f");
  });
});

describe(".vxt 容器", () => {
  it("写入 → 读回：头部元数据与 glb 逐字节一致", () => {
    const glb = fakeGlb();
    const bytes = writeVxt(tileOf(glb), glb, { meshopt: true, quantized: true, embedImage: false });

    expect(new TextDecoder().decode(bytes.subarray(0, 4))).toBe(VXT_MAGIC);
    const { header, payloadLength } = peekVxt(bytes);
    expect(header.kind).toBe("voxelith-tile/1");
    expect(header.contentType).toBe("model/gltf-binary");
    expect(header.tile).toMatchObject({ level: 0, x: 3, z: -2, bytes: glb.length });
    expect(header.flags).toEqual({ lod: false, meshopt: true, quantized: true, embedImage: false });
    expect(payloadLength).toBe(glb.length);

    const file = readVxt(bytes);
    expect(Array.from(file.glb)).toEqual(Array.from(glb));
  });

  it("LOD 瓦片自动标记 lod 标志", () => {
    const glb = fakeGlb();
    const bytes = writeVxt(tileOf(glb, 3, 0, 0), glb, { embedImage: true });
    expect(peekVxt(bytes).header.flags).toMatchObject({ lod: true, embedImage: true });
  });

  it("自检：sha1 与字节数对得上才算通过", () => {
    const glb = fakeGlb();
    const bytes = writeVxt(tileOf(glb), glb);
    const result = verifyVxt(bytes);
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);
    expect(result.actualSha1).toBe(sha1Hex(glb));

    // 篡改 payload（改一个字节）后 sha1 立刻对不上
    const tampered = bytes.slice();
    tampered[tampered.length - 1] = (tampered[tampered.length - 1]! ^ 0xff) & 0xff;
    const bad = verifyVxt(tampered);
    expect(bad.ok).toBe(false);
    expect(bad.errors.some((message) => message.includes("sha1 不一致"))).toBe(true);
  });

  it("坏数据明确报错（magic 错、版本错、截断、头部种类错）", () => {
    const glb = fakeGlb();
    const good = writeVxt(tileOf(glb), glb);

    const wrongMagic = good.slice();
    wrongMagic.set(new TextEncoder().encode("XXXX"), 0);
    expect(() => peekVxt(wrongMagic)).toThrow(/不是 .vxt 容器/);

    const wrongVersion = good.slice();
    new DataView(wrongVersion.buffer).setUint16(4, 9, true);
    expect(() => peekVxt(wrongVersion)).toThrow(/不支持的 .vxt 版本/);

    expect(() => peekVxt(good.subarray(0, good.length - 4))).toThrow(/截断/);
    expect(() => peekVxt(new Uint8Array(8))).toThrow(/过短/);

    const header = { ...peekVxt(good).header, kind: "other/1" } as unknown as Parameters<typeof encodeVxt>[0];
    expect(() => peekVxt(encodeVxt(header, glb))).toThrow(/头部种类不符/);
  });

  it("清单条目能直接喂进来（与 manifest schema 同构）", () => {
    const glb = fakeGlb();
    const manifest = parseManifest({
      formatVersion: 1,
      mapId: "demo",
      name: "演示",
      version: "abc123",
      generatedAt: "2026-09-20T00:00:00Z",
      settings: { hiresTileSize: 32, lodCount: 2 },
      boundsMin: [0, 0, 0],
      boundsMax: [64, 64, 64],
      atlas: { url: "atlas.png", size: 16, textureCount: 1 },
      tiles: [tileOf(glb)],
    });

    const bytes = writeVxt(manifest.tiles[0]!, glb, { quantized: true });
    const header = peekVxt(bytes).header;
    expect(header.tile.url).toBe("tiles/hires/3/-2.glb");
    expect(header.tile.sha1).toBe(sha1Hex(glb));
  });
});
