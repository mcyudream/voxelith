/**
 * `.vxtbundle`：把整张图的瓦片打成**一个归档文件**。
 *
 * 用途是离线分发与冷归档：U 盘拷一张图、对象存储里放一个对象，或给第三方查看器一个文件。
 * 每片仍是 `.vxt` 容器（自带元数据与 sha1），外层加一个索引；除了瓦片，还可以把
 * **清单、图集、LOD 图集页**作为 assets 一起打进去——这样整包就是自包含的，
 * 落地后不需要再找任何旁路文件（只有瓦片而没有图集的包，渲染出来是一堆白模）。
 *
 * 布局（小端）：
 *
 * ```
 * 0  : "VXB1"        magic（4 字节）
 * 4  : version       u16（= 1）
 * 6  : indexLength   u32（索引 JSON 字节数）
 * 10 : index JSON    { mapId, tiles: [...], assets: [{ key, offset, length }] }
 * 10+indexLength : payload（先依次拼接 .vxt 字节，再拼 assets；offset 均相对本段起点）
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
  /** 非瓦片文件（manifest.json / atlas.png / LOD 图集页…）；老包没有这一段。 */
  assets?: BundleAssetEntry[];
}

export interface BundleSource {
  tile: ManifestTile;
  glb: Uint8Array;
}

/** 打包进 assets 的旁路文件（清单、图集、LOD 图集页）。 */
export interface BundleAsset {
  /** 相对地图根的路径，如 {@code atlas.png}、{@code tiles/lod/1/lod-atlas.png}。 */
  key: string;
  bytes: Uint8Array;
}

export interface BundleAssetEntry {
  key: string;
  offset: number;
  length: number;
}

/** 打包：`mapId` 只写进索引（瓦片目录名可能被改过，索引里留个溯源）。 */
export function packVxtBundle(
  mapId: string,
  sources: readonly BundleSource[],
  flags: VxtFlags = {},
  createdAt = new Date().toISOString(),
  assets: readonly BundleAsset[] = [],
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
  // assets 接在瓦片之后：老读取器只认 tiles，忽略多出来的尾段即可（向后兼容）
  const assetEntries: BundleAssetEntry[] = [];
  for (const asset of assets) {
    assetEntries.push({ key: asset.key, offset, length: asset.bytes.length });
    offset += asset.bytes.length;
  }
  const index: BundleIndex = { mapId, createdAt, tiles: entries, assets: assetEntries };
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
  for (const asset of assets) {
    out.set(asset.bytes, cursor);
    cursor += asset.bytes.length;
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
  for (const asset of index.assets ?? []) {
    if (asset.offset < 0 || asset.offset + asset.length > payloadLength) {
      throw new Error(
        `.vxtbundle 资源越界: ${asset.key}（offset=${asset.offset}, length=${asset.length}）`,
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

/** 取一个旁路文件（清单/图集/图集页）的字节（零拷贝视图）。 */
export function readBundleAsset(bytes: Uint8Array, asset: BundleAssetEntry): Uint8Array {
  const { payloadOffset } = readBundleIndex(bytes);
  return bytes.subarray(payloadOffset + asset.offset, payloadOffset + asset.offset + asset.length);
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
  // 自包含性检查：清单在，且清单引用的图集与 LOD 图集页都在包里
  const assetKeys = new Set((index.assets ?? []).map((asset) => asset.key));
  const manifestKey = "manifest.json";
  if (assetKeys.size > 0) {
    if (!assetKeys.has(manifestKey)) {
      errors.push("缺少 manifest.json：包不自包含");
    }
    for (const key of referencedAssets(bytes)) {
      if (!assetKeys.has(key)) {
        errors.push(`清单引用了 ${key}，但包里没有：包不自包含`);
      }
    }
  }
  return { ok: errors.length === 0, errors, index };
}

/** 从包内清单解析出被引用的图集文件路径（缺清单时返回空）。 */
function referencedAssets(bytes: Uint8Array): string[] {
  const { index } = readBundleIndex(bytes);
  const manifestEntry = (index.assets ?? []).find((asset) => asset.key === "manifest.json");
  if (!manifestEntry) {
    return [];
  }
  try {
    const manifest = JSON.parse(new TextDecoder().decode(readBundleAsset(bytes, manifestEntry))) as {
      atlas?: { url?: string };
      lodAtlases?: Array<{ url?: string }>;
    };
    const keys: string[] = [];
    if (manifest.atlas?.url) {
      keys.push(manifest.atlas.url);
    }
    for (const page of manifest.lodAtlases ?? []) {
      if (page.url) {
        keys.push(page.url);
      }
    }
    return keys;
  } catch {
    return [];
  }
}

/** 全量解开成 `{ url → glb }`（大图慎用：内存里会有两份数据）。 */
export function unpackBundle(
  bytes: Uint8Array,
): { index: BundleIndex; tiles: Map<string, Uint8Array>; assets: Map<string, Uint8Array> } {
  const { index } = readBundleIndex(bytes);
  const tiles = new Map<string, Uint8Array>();
  for (const entry of index.tiles) {
    tiles.set(entry.url, readVxt(readBundleEntry(bytes, entry)).glb);
  }
  const assets = new Map<string, Uint8Array>();
  for (const asset of index.assets ?? []) {
    assets.set(asset.key, readBundleAsset(bytes, asset));
  }
  return { index, tiles, assets };
}
