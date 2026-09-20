/**
 * `@yudream/voxelith-tiles` —— VMC 的瓦片格式工具链。
 *
 * - `.vxt` 单文件瓦片容器：glb + 清单条目 + 内容标志 + sha1 自检（{@link writeVxt}）；
 * - OGC 3D Tiles 1.1 互操作：清单 ↔ `tileset.json`（{@link toTileset}）；
 * - 纯 TS 的 SHA-1（{@link sha1Hex}）：浏览器/Node 都能算，用于内容指纹与容器校验。
 *
 * 本包不依赖 three.js，也不碰文件系统：既能跑在浏览器 Worker 里校验下载到的瓦片，
 * 也能被 Node 侧工具（`@yudream/voxelith-forge`）直接复用。
 */
export { sha1, sha1Hex } from "./sha1.js";
export {
  cacheBustedUrl,
  encodeVxt,
  peekVxt,
  readVxt,
  verifyVxt,
  writeVxt,
  VXT_KIND,
  VXT_MAGIC,
  VXT_VERSION,
  type VxtFile,
  type VxtFlags,
  type VxtHeader,
  type VxtTileEntry,
  type VxtVerifyResult,
} from "./vxt.js";
export {
  geometricErrorFor,
  regionForBounds,
  tileWorldSize,
  tilesetContents,
  toTileset,
  type Tileset,
  type TilesetBoundingBox,
  type TilesetBoundingRegion,
  type TilesetBoundingVolume,
  type TilesetContent,
  type TilesetOptions,
  type TilesetTile,
} from "./tileset.js";
