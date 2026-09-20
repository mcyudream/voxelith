/**
 * `.vxt` 瓦片容器（Voxelith Tile，v1）：把**一个瓦片的 glb 与其清单条目**打成一个自描述文件。
 *
 * 为什么需要它：发布目录里的瓦片是「一堆 glb + 一份 manifest.json」，
 * 脱离清单就不知道某片是第几层、覆盖哪里、贴图怎么挂；而离线分发（U 盘、对象存储归档、
 * 第三方查看器）经常只能拿到单个文件。`.vxt` 把元数据内嵌进同一个文件，读到它就够渲染。
 *
 * 布局（小端）：
 *
 * ```
 * 0   : "VXT1"            magic（4 字节 ASCII）
 * 4   : version           u16（= 1）
 * 6   : headerLength      u16（头部 JSON 字节数）
 * 8   : payloadLength     u32（glb 字节数）
 * 12  : header            JSON（UTF-8）
 * 12+headerLength : payload（glb）
 * ```
 *
 * 头部 JSON 里带 `sha1`：读取方可以就地校验内容有没有被改过
 * （与发布清单用的是同一个值，所以 `.vxt` 与清单能互相校对）。
 */
import type { ManifestTile, Vec3 } from "@yudream/voxelith-core";
import { sha1Hex } from "./sha1.js";

export const VXT_MAGIC = "VXT1";
export const VXT_VERSION = 1;
export const VXT_KIND = "voxelith-tile/1";

/** 规范化的瓦片条目（与清单 `tiles[]` 同构，`url` 在容器里可省）。 */
export interface VxtTileEntry {
  level: number;
  x: number;
  z: number;
  sha1: string;
  bytes: number;
  quads: number;
  min: Vec3 | [number, number, number];
  max: Vec3 | [number, number, number];
  /** 相对地图根的原始地址（可选：脱离清单时仅作溯源信息）。 */
  url?: string;
  mapId?: string;
}

/** 内容标志：告诉读取方 glb 里用到了哪些扩展，避免「能不能解码」靠猜。 */
export interface VxtFlags {
  /** `EXT_meshopt_compression`（需要 meshopt 解码器） */
  meshopt?: boolean;
  /** `KHR_mesh_quantization` */
  quantized?: boolean;
  /** 内嵌图集 PNG（false = 贴图来自共享图集） */
  embedImage?: boolean;
  /** 该片是 LOD 层（level > 0） */
  lod?: boolean;
}

export interface VxtHeader {
  kind: string;
  contentType: string;
  tile: VxtTileEntry;
  flags: VxtFlags;
  createdAt?: string;
}

export interface VxtFile {
  header: VxtHeader;
  /** glb 字节（读取时是副本，改它不会影响原数组）。 */
  glb: Uint8Array;
}

function toVec3(value: Vec3 | [number, number, number]): Vec3 {
  if (Array.isArray(value)) {
    const [x, y, z] = value;
    return { x: x ?? 0, y: y ?? 0, z: z ?? 0 };
  }
  return value;
}

/** 从清单条目 + glb 字节写入一个 `.vxt`。 */
export function writeVxt(tile: ManifestTile | VxtTileEntry, glb: Uint8Array, flags: VxtFlags = {}): Uint8Array {
  const header: VxtHeader = {
    kind: VXT_KIND,
    contentType: "model/gltf-binary",
    tile: {
      level: tile.level,
      x: tile.x,
      z: tile.z,
      sha1: tile.sha1,
      bytes: tile.bytes,
      quads: tile.quads,
      min: toVec3(tile.min),
      max: toVec3(tile.max),
      ...("url" in tile && tile.url ? { url: tile.url } : {}),
    },
    flags: { lod: tile.level > 0, ...flags },
    createdAt: new Date(0).toISOString(),
  };
  return encodeVxt(header, glb);
}

/** 直接按给定头部编码（需要自定义 flags / createdAt 时用）。 */
export function encodeVxt(header: VxtHeader, glb: Uint8Array): Uint8Array {
  const headerBytes = new TextEncoder().encode(JSON.stringify(header));
  if (headerBytes.length > 0xffff) {
    throw new Error(`.vxt 头部过大: ${headerBytes.length} 字节（上限 65535）`);
  }
  const total = 12 + headerBytes.length + glb.length;
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  out.set(new TextEncoder().encode(VXT_MAGIC), 0);
  view.setUint16(4, VXT_VERSION, true);
  view.setUint16(6, headerBytes.length, true);
  view.setUint32(8, glb.length, true);
  out.set(headerBytes, 12);
  out.set(glb, 12 + headerBytes.length);
  return out;
}

/**
 * 只读头部（不复制 payload）：批量扫描目录时先看元数据，需要哪片再解 payload。
 * 头部长度不合法、magic 不对、payload 截断都会抛错——容器是自描述格式，
 * 静默读出错位数据比直接报错难查得多。
 */
export function peekVxt(bytes: Uint8Array): { header: VxtHeader; payloadOffset: number; payloadLength: number } {
  if (bytes.length < 12) {
    throw new Error(`.vxt 过短: ${bytes.length} 字节`);
  }
  const magic = new TextDecoder().decode(bytes.subarray(0, 4));
  if (magic !== VXT_MAGIC) {
    throw new Error(`不是 .vxt 容器（magic=${JSON.stringify(magic)}）`);
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const version = view.getUint16(4, true);
  if (version !== VXT_VERSION) {
    throw new Error(`不支持的 .vxt 版本: ${version}`);
  }
  const headerLength = view.getUint16(6, true);
  const payloadLength = view.getUint32(8, true);
  const payloadOffset = 12 + headerLength;
  if (payloadOffset + payloadLength > bytes.length) {
    throw new Error(
      `.vxt 截断: 需要 ${payloadOffset + payloadLength} 字节，实际 ${bytes.length}`,
    );
  }
  const header = JSON.parse(
    new TextDecoder().decode(bytes.subarray(12, payloadOffset)),
  ) as VxtHeader;
  if (header?.kind !== VXT_KIND) {
    throw new Error(`.vxt 头部种类不符: ${String(header?.kind)}`);
  }
  return { header, payloadOffset, payloadLength };
}

/** 读取整个容器（payload 复制一份，安全地交给解码器/上传）。 */
export function readVxt(bytes: Uint8Array): VxtFile {
  const { header, payloadOffset, payloadLength } = peekVxt(bytes);
  return { header, glb: bytes.slice(payloadOffset, payloadOffset + payloadLength) };
}

export interface VxtVerifyResult {
  ok: boolean;
  header: VxtHeader;
  actualSha1: string;
  errors: string[];
}

/**
 * 校验容器自洽性：payload 字节数与头部 `bytes` 一致、sha1 与头部 `sha1` 一致。
 * 离线分发/对象存储归档后做完整性自检用（不用联网，也不需要清单）。
 */
export function verifyVxt(bytes: Uint8Array): VxtVerifyResult {
  const { header, payloadOffset, payloadLength } = peekVxt(bytes);
  const glb = bytes.subarray(payloadOffset, payloadOffset + payloadLength);
  const actualSha1 = sha1Hex(glb);
  const errors: string[] = [];
  if (header.tile.bytes !== payloadLength) {
    errors.push(`头部 bytes=${header.tile.bytes} 与实际 payload=${payloadLength} 不一致`);
  }
  if (header.tile.sha1 && header.tile.sha1 !== actualSha1) {
    errors.push(`sha1 不一致: 头部 ${header.tile.sha1} / 实际 ${actualSha1}`);
  }
  return { ok: errors.length === 0, header, actualSha1, errors };
}

/** 由清单条目算出 `?sha=` 查询串里用的缓存戳（与前端一致）。 */
export function cacheBustedUrl(url: string, sha1: string): string {
  return `${url}${url.includes("?") ? "&" : "?"}sha=${sha1}`;
}
