/**
 * 金标准向量：tile-context 的纯 Java meshopt 编码器（MeshoptVertexCodec / MeshoptIndexCodec）
 * 产出的位流，必须能被 three.js 自带的官方解码器（meshoptimizer 0.22 WASM）原样解回。
 *
 * 这组向量把「后端编码格式」和「前端解码器」钉在一起：任何一侧改了位流都会在这里炸。
 * 向量与后端 `MeshoptCodecTest` / `GlbTileEncoderMeshoptTest` 同源（同一段确定性输入），
 * 块边界与多块场景由后端往返用例覆盖，这里守的是**跨实现互通**。
 */
import { describe, expect, it } from "vitest";
import { MeshoptDecoder } from "three/addons/libs/meshopt_decoder.module.js";

const VERTEX_COUNT = 20;
const VERTEX_SIZE = 12;

/** 同一个 20 顶点 × 12 字节缓冲的 meshopt 顶点位流（version 0）。 */
const VERTEX_COMPRESSED =
  "a00a0aaaaaaaaaaaaaaaaaaa0000000000000a0aaaaaaaaaaaaaaaaaaa0000000000000a0aaaaaaaaaaaaaaaaaaa0000000000000" +
  "60aaaaaaaaaaaaaaaff0000000a0a0af5060aaaaaaaaaaaaaaaff0000000af50a0a0a0aaaaaaaaaaaaaaff5aaaa000000000000" +
  "0a0aaaaaaaaaaaafaaf5aaaa0000000000000a0aaaaaaaaaafaaaaf5aaaa0000000000000a0aaaaaaafaaaaaaaf5aaaa00000000" +
  "00000a0aaaaafaaaaaaaaaf5aaaa0000000000000a0aaafaaaaaaaaaaaf5aaaa0000000000000a0afaaaaaaaaaaaaaf5aaaa0000" +
  "000000000000000000000000000000000000000000000000000b16212c37424d58636e79";

/** 6 个三角（18 个索引）的 meshopt 三角位流（version 1），尾部 16 字节是 codeaux 表。 */
const INDEX_COMPRESSED = "e1f0fcfcfcfcfc007687566778a9866589689801690000";

const INDICES = [0, 1, 2, 2, 3, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 12];

/** 期望的原始顶点字节，由与 Java 侧相同的确定性公式生成。 */
function expectedVertices(): Uint8Array {
  const out = new Uint8Array(VERTEX_COUNT * VERTEX_SIZE);
  for (let i = 0; i < VERTEX_COUNT; i++) {
    for (let b = 0; b < VERTEX_SIZE; b++) {
      out[i * VERTEX_SIZE + b] = (i * 5 + b * 11) & 0x7f;
    }
  }
  return out;
}

function bytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/** meshopt 允许把三角写成输入的循环轮转（顶点集合与绕向不变）。 */
function sameTriangle(expected: number[], actual: Uint32Array, at: number): boolean {
  const [a, b, c] = [actual[at], actual[at + 1], actual[at + 2]];
  const [x, y, z] = [expected[at], expected[at + 1], expected[at + 2]];
  return (
    (a === x && b === y && c === z) ||
    (a === y && b === z && c === x) ||
    (a === z && b === x && c === y)
  );
}

describe("meshopt 位流与官方解码器互通", () => {
  it("官方解码器可用（否则压缩瓦片无法渲染）", async () => {
    await MeshoptDecoder.ready;
    expect(MeshoptDecoder.supported).toBe(true);
  });

  it("Java 编码的顶点流被官方解码器逐字节还原", async () => {
    await MeshoptDecoder.ready;
    const out = new Uint8Array(VERTEX_COUNT * VERTEX_SIZE);
    MeshoptDecoder.decodeGltfBuffer(
      out,
      VERTEX_COUNT,
      VERTEX_SIZE,
      bytes(VERTEX_COMPRESSED),
      "ATTRIBUTES",
      "NONE",
    );
    expect(Array.from(out)).toEqual(Array.from(expectedVertices()));
  });

  it("Java 编码的索引流被官方解码器还原为同一批三角面，且压到 1 字节/三角量级", async () => {
    await MeshoptDecoder.ready;
    const out = new Uint32Array(INDICES.length);
    MeshoptDecoder.decodeGltfBuffer(
      new Uint8Array(out.buffer),
      INDICES.length,
      4,
      bytes(INDEX_COMPRESSED),
      "TRIANGLES",
      "NONE",
    );
    for (let i = 0; i < INDICES.length; i += 3) {
      expect(sameTriangle(INDICES, out, i), `三角 #${i / 3}`).toBe(true);
    }
    // 未压缩索引 18×4 = 72 字节 → 位流 23 字节
    expect(INDEX_COMPRESSED.length / 2).toBeLessThan(INDICES.length * 4);
  });
});
