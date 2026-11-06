/**
 * 设备性能静态预判：页面初始化时综合 CPU 核数、内存、GPU 型号把设备分为 高/中/低 三档，
 * 决定初始视距（区块数）。
 *
 * - 信号来源：navigator.hardwareConcurrency、navigator.deviceMemory（缺失时按 4GB 兜底）、
 *   WebGL WEBGL_debug_renderer_info 的 UNMASKED_RENDERER（WebView 中被禁用时按中档兜底）；
 * - 结果缓存 localStorage（同一台设备结果不变，省掉每次启动的扩展查询）；
 * - computeTier / scoreGpu 为纯函数，便于单测。
 */

export type DeviceTier = "high" | "mid" | "low";

export interface DeviceSignals {
  /** CPU 逻辑核数（缺失按 4 兜底） */
  cores?: number;
  /** 设备内存 GB（缺失按 4 兜底） */
  memoryGb?: number;
  /** WebGL 未掩码 GPU 型号字符串（不可得时按中档兜底） */
  gpuRenderer?: string;
  /** 是否移动端（影响目标帧率，不影响分档） */
  mobile?: boolean;
}

export interface DeviceProfile {
  tier: DeviceTier;
  mobile: boolean;
  cores: number;
  memoryGb: number;
  gpuRenderer: string;
}

/**
 * 三档初始视距（区块）：高档 64、中档 32、低档 16。
 *
 * <p>2026-11-06 上调（原 24/16/10）：视距内细分到块级几何（hires 瓦片，真实贴图、窗户可辨），
 * 视距外只有 LOD 色图。旧的 24 区块 = 384 方块，中远处建筑只剩色块、看不见窗户
 * （用户对比 Voxy 的直观差异）。地图的块级瓦片本来就是全量渲染的（校园 4215 片 L0），
 * 高档 GPU 完全吃得下更大视距；弱设备仍由低档与自适应机制兜底。</p>
 */
export function initialViewDistanceChunks(tier: DeviceTier): number {
  return tier === "high" ? 64 : tier === "mid" ? 32 : 16;
}

/**
 * 三档瓦片缓存上限（数量 + 字节）。低档设备/移动端按比例收紧：
 * 缓存按瓦片数封顶时，弱 GPU 的显存与内存率先耗尽，而首屏并不会因此变慢
 * （期望集合内的瓦片受 LRU 保护，不会刚加载就被淘汰）。
 */
export function cacheLimitsForTier(tier: DeviceTier): { maxLoaded: number; maxBytes: number } {
  switch (tier) {
    case "high":
      return { maxLoaded: 4096, maxBytes: 2 * 1024 * 1024 * 1024 };
    case "mid":
      return { maxLoaded: 2048, maxBytes: 1024 * 1024 * 1024 };
    default:
      return { maxLoaded: 1024, maxBytes: 512 * 1024 * 1024 };
  }
}

/** GPU 型号启发式打分：0 软件渲染/入门，1 中端/未知，2 高端独显或旗舰移动 GPU。 */
export function scoreGpu(renderer: string): 0 | 1 | 2 {
  const r = renderer.toLowerCase();
  if (!r) return 1;
  if (/swiftshader|llvmpipe|softpipe|software|basic render/.test(r)) return 0;
  if (
    /rtx|radeon rx [67]|radeon pro|apple m\d|adreno [67]\d0|mali-g(78|710|715|720)|mali-g610|dimensity 9/.test(
      r,
    )
  ) {
    return 2;
  }
  if (/intel hd graphics|intel\(r\) hd|mali-4|adreno [34]\d\d|powervr sgx/.test(r)) return 0;
  // gtx、iris/uhd、中端 adreno/mali、Apple A 系与未知型号按中档
  return 1;
}

/**
 * 综合分档：核数/内存/GPU 各 0-2 分，总分 ≥5 高档、≥3 中档、否则低档。
 */
export function computeTier(signals: DeviceSignals): DeviceTier {
  const cores = signals.cores ?? 4;
  const memoryGb = signals.memoryGb ?? 4;
  const gpuScore = scoreGpu(signals.gpuRenderer ?? "");
  const score =
    (cores >= 8 ? 2 : cores >= 4 ? 1 : 0) +
    (memoryGb >= 8 ? 2 : memoryGb >= 4 ? 1 : 0) +
    gpuScore;
  return score >= 5 ? "high" : score >= 3 ? "mid" : "low";
}

const CACHE_KEY = "yudream.deviceProfile.v1";

/**
 * 运行时探测（浏览器/WebView）。优先读 localStorage 缓存；
 * 未命中时采集信号并写入缓存。所有 API 调用均有兜底，WebView 中缺失特性不会抛错。
 */
export function detectDeviceProfile(
  gl?: WebGLRenderingContext | WebGL2RenderingContext | null,
): DeviceProfile {
  try {
    const cached = globalThis.localStorage?.getItem(CACHE_KEY);
    if (cached) {
      const parsed = JSON.parse(cached) as DeviceProfile;
      if (parsed && typeof parsed.tier === "string") {
        return parsed;
      }
    }
  } catch {
    // localStorage 不可用（隐私模式等）时静默降级为每次探测
  }

  const nav = typeof navigator !== "undefined" ? navigator : undefined;
  const mobile =
    !!nav &&
    (/Android|iPhone|iPad|iPod|Mobile/i.test(nav.userAgent) ||
      (typeof globalThis.matchMedia === "function" &&
        globalThis.matchMedia("(pointer: coarse)").matches));

  let gpuRenderer = "";
  if (gl) {
    try {
      const ext = gl.getExtension("WEBGL_debug_renderer_info");
      if (ext) {
        gpuRenderer = String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) ?? "");
      }
    } catch {
      // WebView 禁用该扩展时按空串（中档）兜底
    }
  }

  const profile: DeviceProfile = {
    tier: computeTier({
      cores: nav?.hardwareConcurrency,
      memoryGb: (nav as { deviceMemory?: number } | undefined)?.deviceMemory,
      gpuRenderer,
      mobile,
    }),
    mobile,
    cores: nav?.hardwareConcurrency ?? 4,
    memoryGb: (nav as { deviceMemory?: number } | undefined)?.deviceMemory ?? 4,
    gpuRenderer,
  };

  try {
    globalThis.localStorage?.setItem(CACHE_KEY, JSON.stringify(profile));
  } catch {
    // 写缓存失败不影响功能
  }
  return profile;
}
