/**
 * `.vxtbundle`：把整张图的瓦片打成**一个归档文件**。
 *
 * 用途是离线分发与冷归档：U 盘拷一张图、对象存储里放一个对象、
 * 或者给第三方查看器一个自包含文件。每片仍是 `.vxt` 容器（自带元数据与 sha1），
 * 外层只加一个索引，便于「先看目录再取某一片」而不用扫描整包。
 *
 * 布局（小端）：
 *
 * ```
 * 0  : "VXB1"        magic（4 字节）
 * 4  : version       u16（= 1）
 * 6  : indexLength   u32（索引 JSON 字节数）
 * 10 : index JSON    { mapId, tiles: [{ url, level, x, z, sha1, offset, length }] }
 * 10+indexLength : payload（依次拼接的 .vxt 字节；offset 相对本段起点）
 * ```
 *
 * 与「逐片 .vxt」的关系：bundle 里存的就是 `.vxt` 字节本身，所以
 * {@link readBundleEntry} 拿到的片段可以直接喂给 `readVxt`——不引入第二套格式。
 */
import { readVxt, verifyVxt, writeVxt, type VxtFlags } from "@yudream/voxelith-tiles";
import type { ManifestTile } from "@yudream/voxelith-core";

export const BUNDLE_MAGIC = "VXB1";
export const BUNDLE_VERSION = 1;

export interface BundleEntry {
  url: string;
  level: number;
  x: number;
  z: number;
  sha1: string;
  /** 相对 payload 起点的偏移与长度。 */
  offset: number;
  length: number;
}

export interface BundleIndex {
  mapId: string;
  /** 打包时间（ISO 字符串）。 */
  createdAt: string;
  tiles: BundleEntry[];
}

export interface BundleSource {
  tile: ManifestTile;
  glb: Uint8Array;
}

/** 打包：`mapId` 只写进索引（瓦片目录名可能被改过，索引里留个溯源）。 */
export function packVxtBundle(
  mapId: string,
  sources: readonly BundleSource[],
  flags: VxtFlags = {},
  createdAt = new Date().toISOString(),
): Uint8Array {
  const chunks: Uint8Array[] = [];
  const entries: BundleEntry[] = [];
  let offset = 0;
  for (const source of sources) {
    const vxt = writeVxt(source.tile, source.glb, flags);
    entries.push({
      url: source.tile.url,
      level: source.tile.level,
      x: source.tile.x,
      z: source.tile.z,
      sha1: source.tile.sha1,
      offset,
      length: vxt.length,
    });
    chunks.push(vxt);
    offset += vxt.length;
  }
  const index: BundleIndex = { mapId, createdAt, tiles: entries };
  const indexBytes = new TextEncoder().encode(JSON.stringify(index));
  const out = new Uint8Array(10 + indexBytes.length + offset);
  const view = new DataView(out.buffer);
  out.set(new TextEncoder().encode(BUNDLE_MAGIC), 0);
  view.setUint16(4, BUNDLE_VERSION, true);
  view.setUint32(6, indexBytes.length, true);
  out.set(indexBytes, 10);
  let cursor = 10 + indexBytes.length;
  for (const chunk of chunks) {
    out.set(chunk, cursor);
    cursor += chunk.length;
  }
  return out;
}

/** 只读索引（不复制 payload）：先看目录，再决定取哪几片。 */
export function readBundleIndex(
  bytes: Uint8Array,
): { index: BundleIndex; payloadOffset: number; payloadLength: number } {
  if (bytes.length < 10) {
    throw new Error(`.vxtbundle 过短: ${bytes.length} 字节`);
  }
  const magic = new TextDecoder().decode(bytes.subarray(0, 4));
  if (magic !== BUNDLE_MAGIC) {
    throw new Error(`不是 .vxtbundle（magic=${JSON.stringify(magic)}）`);
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const version = view.getUint16(4, true);
  if (version !== BUNDLE_VERSION) {
    throw new Error(`不支持的 .vxtbundle 版本: ${version}`);
  }
  const indexLength = view.getUint32(6, true);
  const payloadOffset = 10 + indexLength;
  if (payloadOffset > bytes.length) {
    throw new Error(`.vxtbundle 索引截断: 需要 ${payloadOffset} 字节，实际 ${bytes.length}`);
  }
  const index = JSON.parse(new TextDecoder().decode(bytes.subarray(10, payloadOffset))) as BundleIndex;
  const payloadLength = bytes.length - payloadOffset;
  for (const entry of index.tiles) {
    if (entry.offset < 0 || entry.offset + entry.length > payloadLength) {
      throw new Error(
        `.vxtbundle 索引越界: ${entry.url}（offset=${entry.offset}, length=${entry.length}）`,
      );
    }
  }
  return { index, payloadOffset, payloadLength };
}

/** 取某一片的 `.vxt` 片段（零拷贝视图；需要长期持有时自行 `.slice()`）。 */
export function readBundleEntry(bytes: Uint8Array, entry: BundleEntry): Uint8Array {
  const { payloadOffset } = readBundleIndex(bytes);
  return bytes.subarray(payloadOffset + entry.offset, payloadOffset + entry.offset + entry.length);
}

/** 校验整包：逐片验证 `.vxt` 自洽（sha1/字节数），并把索引与容器头部对齐。 */
export function verifyBundle(bytes: Uint8Array): { ok: boolean; errors: string[]; index: BundleIndex } {
  const { index } = readBundleIndex(bytes);
  const errors: string[] = [];
  for (const entry of index.tiles) {
    const slice = readBundleEntry(bytes, entry);
    try {
      const result = verifyVxt(slice);
      if (!result.ok) {
        errors.push(`${entry.url}: ${result.errors.join("；")}`);
      }
      if (result.header.tile.level !== entry.level
        || result.header.tile.x !== entry.x
        || result.header.tile.z !== entry.z) {
        errors.push(`${entry.url}: 索引与容器头部的层级/坐标不一致`);
      }
    } catch (error) {
      errors.push(`${entry.url}: ${String(error)}`);
    }
  }
  return { ok: errors.length === 0, errors, index };
}

/** 全量解开成 `{ url → glb }`（大图慎用：内存里会有两份数据）。 */
export function unpackBundle(
  bytes: Uint8Array,
): { index: BundleIndex; tiles: Map<string, Uint8Array> } {
  const { index } = readBundleIndex(bytes);
  const tiles = new Map<string, Uint8Array>();
  for (const entry of index.tiles) {
    tiles.set(entry.url, readVxt(readBundleEntry(bytes, entry)).glb);
  }
  return { index, tiles };
}
