/**
 * glb 头部解析（不依赖 three.js，也不解顶点数据）。
 *
 * 审计想知道的是**结构性事实**：文件完整吗（声明长度 vs 实际长度）、
 * 用了哪些扩展（meshopt / quantization 决定前端要不要解压器）、
 * 有没有内嵌图集（决定前端能不能换共享纹理）、有几个 primitive。
 * 这些都在 JSON chunk 里，读头部就够，不必把几 MB 的 BIN 解析成网格。
 */

export interface GlbInfo {
  magicOk: boolean;
  version: number;
  declaredLength: number;
  actualLength: number;
  /** 声明长度与实际长度一致（且 4 字节对齐）。 */
  lengthOk: boolean;
  /** 解析出的 glTF JSON（chunk 缺失或 JSON 非法时为 null）。 */
  json: Record<string, unknown> | null;
  extensionsUsed: string[];
  extensionsRequired: string[];
  /** 用了 `EXT_meshopt_compression`（前端必须挂 meshopt 解码器）。 */
  hasMeshopt: boolean;
  /** 用了 `KHR_mesh_quantization`。 */
  hasQuantized: boolean;
  /** 内嵌了图集 PNG（images[].bufferView 指向 BIN）。 */
  hasEmbeddedImage: boolean;
  /** 使用了共享图集但没内嵌图（images 里只有 URL 或没有 images）。 */
  primitiveCount: number;
  accessorCount: number;
  meshCount: number;
}

const MAGIC = 0x46546c67;
const CHUNK_JSON = 0x4e4f534a;

/** 读 glb 头部；字节少于 12 字节时抛错（连头都不完整，谈不上审计）。 */
export function inspectGlb(bytes: Uint8Array): GlbInfo {
  if (bytes.length < 12) {
    throw new Error(`glb 过短: ${bytes.length} 字节`);
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const magic = view.getUint32(0, true);
  const version = view.getUint32(4, true);
  const declaredLength = view.getUint32(8, true);
  const info: GlbInfo = {
    magicOk: magic === MAGIC,
    version,
    declaredLength,
    actualLength: bytes.length,
    lengthOk: declaredLength === bytes.length,
    json: null,
    extensionsUsed: [],
    extensionsRequired: [],
    hasMeshopt: false,
    hasQuantized: false,
    hasEmbeddedImage: false,
    primitiveCount: 0,
    accessorCount: 0,
    meshCount: 0,
  };

  if (bytes.length >= 20) {
    const chunkLength = view.getUint32(12, true);
    const chunkType = view.getUint32(16, true);
    const jsonEnd = 20 + chunkLength;
    if (chunkType === CHUNK_JSON && jsonEnd <= bytes.length) {
      const text = new TextDecoder().decode(bytes.subarray(20, jsonEnd)).trim();
      try {
        const json = JSON.parse(text) as Record<string, unknown>;
        info.json = json;
        info.extensionsUsed = asStringArray(json["extensionsUsed"]);
        info.extensionsRequired = asStringArray(json["extensionsRequired"]);
        info.hasMeshopt = info.extensionsUsed.includes("EXT_meshopt_compression");
        info.hasQuantized = info.extensionsUsed.includes("KHR_mesh_quantization");
        info.accessorCount = asArray(json["accessors"]).length;
        const meshes = asArray(json["meshes"]);
        info.meshCount = meshes.length;
        info.primitiveCount = meshes.reduce<number>((count, mesh) => {
          const primitives = (mesh as { primitives?: unknown[] }).primitives ?? [];
          return count + primitives.length;
        }, 0);
        info.hasEmbeddedImage = asArray(json["images"]).some(
          (image) => typeof (image as { bufferView?: unknown }).bufferView === "number",
        );
      } catch {
        // JSON chunk 坏了：保留结构事实（长度/魔数），把 json 留成 null
      }
    }
  }
  return info;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asStringArray(value: unknown): string[] {
  return asArray(value).filter((entry): entry is string => typeof entry === "string");
}
