/**
 * @yudream/voxelith-viewer —— VMC three.js 渲染核心。
 *
 * 当前：清单加载 + glb 瓦片流（TileManager）+ 三模式相机（自由飞行 / 第一人称 / 俯视倾斜）。
 * 后续：LodTraverser / Y 切片 / MarkerRendererRegistry。
 */
export { MapEngine, supportsDisplayP3, type MapEngineOptions } from "./engine/MapEngine.js";
export {
  AdaptiveDistance,
  decideChunks,
  type AdaptiveDistanceOptions,
  type DecideResult,
} from "./engine/AdaptiveDistance.js";
export {
  computeTier,
  detectDeviceProfile,
  initialViewDistanceChunks,
  scoreGpu,
  type DeviceProfile,
  type DeviceSignals,
  type DeviceTier,
} from "./engine/deviceTier.js";
export { TileManager, type TileManagerOptions } from "./tiles/TileManager.js";
export {
  disposeTileGroup,
  GlbTileLoader,
  LightingUniforms,
  TileGeometryError,
  validateTileGroup,
  type GlbTileLoaderOptions,
} from "./tiles/GlbTileLoader.js";
export { FloatingOrigin } from "./engine/FloatingOrigin.js";
export { FreeFlightControls, type FreeFlightOptions } from "./controls/FreeFlightControls.js";
export { FirstPersonControls, type FirstPersonOptions } from "./controls/FirstPersonControls.js";
export { TiltOrbitControls, type TiltOrbitOptions } from "./controls/TiltOrbitControls.js";
export type { CameraControls, CameraMode, LookOptions } from "./controls/CameraControls.js";
export { loadManifest } from "./manifest/loadManifest.js";
