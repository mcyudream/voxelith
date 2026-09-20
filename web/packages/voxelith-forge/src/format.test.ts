import { describe, expect, it } from "vitest";
import { inspectGlb } from "./glb.js";
import { inspectPng } from "./png.js";

/** 造一个 glb：12 字节头 + JSON chunk（BIN chunk 可选）。 */
export function fakeGlb(
  json: Record<string, unknown>,
  options: { binLength?: number; declaredLengthDelta?: number; magic?: string } = {},
): Uint8Array {
  const jsonBytes = new TextEncoder().encode(JSON.stringify(json));
  const jsonPadded = (jsonBytes.length + 3) & ~3;
  const binLength = (options.binLength ?? 0 + 0) ? options.binLength! : 0;
  const binPadded = (binLength + 3) & ~3;
  const total = 12 + 8 + jsonPadded + (binLength > 0 ? 8 + binPadded : 0);
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  out.set(new TextEncoder().encode(options.magic ?? "glTF"), 0);
  view.setUint32(4, 2, true);
  view.setUint32(8, total + (options.declaredLengthDelta ?? 0), true);
  view.setUint32(12, jsonPadded, true);
  view.setUint32(16, 0x4e4f534a, true);
  out.set(jsonBytes, 20);
  for (let i = jsonBytes.length; i < jsonPadded; i++) {
    out[20 + i] = 0x20;
  }
  if (binLength > 0) {
    const at = 20 + jsonPadded;
    view.setUint32(at, binPadded, true);
    view.setUint32(at + 4, 0x004e4942, true);
  }
  return out;
}

/** 造一个 PNG 头（真实 PNG 只需签名 + IHDR；本工具也只读头部）。 */
export function fakePng(width: number, height: number, colorType = 6, bitDepth = 8): Uint8Array {
  const out = new Uint8Array(33);
  out.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0);
  const view = new DataView(out.buffer);
  view.setUint32(8, 13, false);
  out.set(new TextEncoder().encode("IHDR"), 12);
  view.setUint32(16, width, false);
  view.setUint32(20, height, false);
  out[24] = bitDepth;
  out[25] = colorType;
  return out;
}

describe("glb 头部解析", () => {
  it("读出扩展、primitive 数与图集内嵌情况", () => {
    const glb = fakeGlb({
      asset: { version: "2.0" },
      extensionsUsed: ["KHR_mesh_quantization", "EXT_meshopt_compression"],
      extensionsRequired: ["EXT_meshopt_compression"],
      accessors: [{}, {}, {}],
      images: [{ bufferView: 3, mimeType: "image/png" }],
      meshes: [{ primitives: [{}, {}] }, { primitives: [{}] }],
    }, { binLength: 16 });

    const info = inspectGlb(glb);
    expect(info.magicOk).toBe(true);
    expect(info.version).toBe(2);
    expect(info.lengthOk).toBe(true);
    expect(info.hasMeshopt).toBe(true);
    expect(info.hasQuantized).toBe(true);
    expect(info.hasEmbeddedImage).toBe(true);
    expect(info.accessorCount).toBe(3);
    expect(info.meshCount).toBe(2);
    expect(info.primitiveCount).toBe(3);
  });

  it("共享图集模式（images 没有 bufferView）识别为未内嵌", () => {
    const glb = fakeGlb({
      asset: { version: "2.0" },
      extensionsUsed: [],
      accessors: [{}],
      meshes: [{ primitives: [{}] }],
      images: [{ uri: "atlas.png" }],
    });
    const info = inspectGlb(glb);
    expect(info.hasEmbeddedImage).toBe(false);
    expect(info.hasMeshopt).toBe(false);
    expect(info.primitiveCount).toBe(1);
  });

  it("长度不符 / magic 不符 / JSON 坏掉都能看出来（不抛错）", () => {
    const truncated = fakeGlb({ asset: { version: "2.0" } }, { declaredLengthDelta: 64 });
    expect(inspectGlb(truncated).lengthOk).toBe(false);

    const wrongMagic = fakeGlb({ asset: { version: "2.0" } }, { magic: "XXXX" });
    expect(inspectGlb(wrongMagic).magicOk).toBe(false);

    const badJson = fakeGlb({ asset: { version: "2.0" } });
    badJson[21] = 0x7b; // 破坏 JSON 第一个字符
    const info = inspectGlb(badJson);
    expect(info.json).toBeNull();
    expect(info.magicOk).toBe(true);
  });

  it("过短的输入直接抛错（连头都不完整）", () => {
    expect(() => inspectGlb(new Uint8Array(4))).toThrow(/过短/);
  });
});

describe("PNG 头部解析", () => {
  it("读出宽高与位深/颜色类型", () => {
    const png = inspectPng(fakePng(256, 320));
    expect(png).toMatchObject({ width: 256, height: 320, bitDepth: 8, colorType: 6, valid: true });
  });

  it("签名或 IHDR 不对时报错", () => {
    const png = fakePng(16, 16);
    const wrongSignature = png.slice();
    wrongSignature[0] = 0x00;
    expect(() => inspectPng(wrongSignature)).toThrow(/签名/);

    const wrongChunk = png.slice();
    wrongChunk.set(new TextEncoder().encode("IHDR".split("").reverse().join("")), 12);
    expect(() => inspectPng(wrongChunk)).toThrow(/IHDR/);

    expect(() => inspectPng(new Uint8Array(10))).toThrow(/过短/);
  });

  it("位深与颜色类型组合非法时标为 invalid（可疑文件）", () => {
    // colorType=3（索引色）允许 1/2/4/8，16 位非法
    expect(inspectPng(fakePng(8, 8, 3, 16)).valid).toBe(false);
    expect(inspectPng(fakePng(8, 8, 3, 4)).valid).toBe(true);
  });
});
