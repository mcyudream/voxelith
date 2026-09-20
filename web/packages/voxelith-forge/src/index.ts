/**
 * `@yudream/voxelith-forge` —— VMC 管线产物工具链（纯逻辑部分）。
 *
 * 三块能力：
 * 1. **审计**（{@link auditManifest}）：清单 ↔ 盘上产物的交叉检查
 *    （url 唯一性、sha1/字节数、glb 结构、图集与 LOD 图集页尺寸、层级合法性）；
 * 2. **格式解析**：{@link inspectGlb}（不依赖 three.js 的 glb 头部信息）与
 *    {@link inspectPng}（只读 IHDR 拿宽高）；
 * 3. **归档打包**：{@link packVxtBundle} 把整张图打成单个 `.vxtbundle`。
 *
 * 需要直接对着目录跑的便捷函数在 `@yudream/voxelith-forge/node`，
 * 命令行入口是 `voxelith-forge`（`audit` / `tileset` / `pack` / `stats` / `ext`）。
 */
export { inspectGlb, type GlbInfo } from "./glb.js";
export { inspectPng, type PngInfo } from "./png.js";
export {
  auditManifest,
  formatAuditReport,
  type AuditIssue,
  type AuditOptions,
  type AuditReport,
  type AuditSeverity,
  type AuditStats,
} from "./audit.js";
export {
  BUNDLE_MAGIC,
  BUNDLE_VERSION,
  packVxtBundle,
  readBundleAsset,
  readBundleEntry,
  readBundleIndex,
  unpackBundle,
  verifyBundle,
  type BundleAsset,
  type BundleAssetEntry,
  type BundleEntry,
  type BundleIndex,
  type BundleSource,
} from "./bundle.js";
