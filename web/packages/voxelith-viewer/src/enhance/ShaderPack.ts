/**
 * 光影包（shader pack）加载：解 zip、读 shaders.properties，把其中可映射的选项
 * 换算成内置光影管线的参数。
 *
 * 边界说明（重要，写论文/答辩时别说成「运行光影包的 GLSL」）：
 * Minecraft 光影包（BSL / Complementary 等）本体是一堆面向游戏延迟渲染管线的
 * GLSL 程序（gbuffers/composite/final），依赖 MC 的 gbuffer 布局与 Java 侧 uniform，
 * 无法在 WebGL + 烘焙静态网格的查看器里直接执行。这里做的是：**解析光影包的配置层**
 * （shaders.properties 的开关与滑杆），把语义可对应的项映射到查看器内置光影管线的
 * 等效参数（阴影距离、太阳轨迹倾角、泛光强度、曝光……），GLSL 本体不执行。
 * 用户从社区光影包 zip 里得到的观感是「同风格的等效预设」，而不是逐像素复刻。
 */
import { unzipSync } from "fflate";
// 只为默认强度常数：LightEngine 对本模块只有 type 导入，无运行时环
import { DEFAULT_LIGHT_PARAMS, type LightParams } from "./LightEngine.js";

/** 从光影包里换算出来的内置管线参数（都是 LightEngine 参数的子集）。 */
export type ShaderPackTweaks = Partial<LightParams>;

/** 包作者声明的可选值选项（游戏内光影设置界面的数据源）。 */
export interface ShaderPackOption {
  name: string;
  /** 当前生效值（包内默认值，或用户在面板里改过的值）。 */
  value: string;
  /** 包作者声明的全部可选值（按声明顺序）。 */
  values: string[];
}

export interface ShaderPackApplied {
  /** 包展示名：zip 文件名（不含 .zip）。 */
  name: string;
  /** shaders.properties 的原始键值（调试/展示用）。 */
  options: Record<string, string>;
  /** 成功映射到内置管线的参数。 */
  tweaks: ShaderPackTweaks;
  /** 认了出来但语义对不上的选项名（UI 提示「未映射」）。 */
  unmapped: string[];
  /** 带「可选值列表」声明的选项（设置面板渲染成下拉框，改一项立即生效）。 */
  packOptions: ShaderPackOption[];
}

/**
 * 单个光影选项 → 内置管线参数。设置面板改一项就调一次；应用整包时对
 * {@link KNOWN_OPTION_KEYS} 逐项调用后合并。
 *
 * 语义标定（对照主流包实测，见记忆）：
 * - SUNLIGHT/SKYLIGHT_INTENSITY 是「相对包默认的倍率」（1.0 = 默认），
 *   按内置标定值缩放，直接当绝对值会双重计光过曝；
 * - BLOOM_AMOUNT 等是泛光纹理混合系数（0..1，1 = 全开），×0.5 折算 UnrealBloom 强度；
 * - shadowMapResolution 夹到网页端合理的 1024..4096（包里可能声明到 32768）。
 */
export function mapPackOption(name: string, rawValue: string): ShaderPackTweaks {
  const value = Number.parseFloat(rawValue);
  if (!Number.isFinite(value)) {
    return {};
  }
  switch (name) {
    case "shadowDistance":
      return value >= 32 ? { shadowDistance: clamp(value, 64, 512) } : {};
    case "shadowMapResolution":
      return { shadowMapSize: clamp(value, 1024, 4096) };
    case "sunPathRotation":
      return { sunPathRotation: clamp(value, -60, 60) };
    case "SUNLIGHT_INTENSITY":
      return { sunStrength: clamp(DEFAULT_LIGHT_PARAMS.sunStrength * value, 0, 2) };
    case "SKYLIGHT_INTENSITY":
      return { ambientStrength: clamp(DEFAULT_LIGHT_PARAMS.ambientStrength * value, 0.2, 1) };
    case "EXPOSURE":
      return value > 0 ? { exposure: clamp(value, 0.4, 2.5) } : {};
    case "BLOOM_AMOUNT":
    case "BLOOM_STRENGTH":
    case "bloomStrength":
    case "bloom_amount":
    case "bloomIntensity":
      return { bloom: clamp(value * 0.5, 0, 1.2) };
    default:
      return {};
  }
}

/** 会映射到内置管线的选项名（其余声明的选项不进设置面板，只计入「未映射」）。 */
const KNOWN_OPTION_KEYS = [
  "shadowDistance",
  "shadowMapResolution",
  "sunPathRotation",
  "SUNLIGHT_INTENSITY",
  "SKYLIGHT_INTENSITY",
  "EXPOSURE",
  "BLOOM_AMOUNT",
  "BLOOM_STRENGTH",
  "bloomStrength",
  "bloom_amount",
  "bloomIntensity",
] as const;

/**
 * 解析一个光影包 zip（本地上传场景）。
 *
 * @param zipBytes zip 文件内容
 * @param fallbackName zip 文件名（用于展示；可含 .zip 后缀）
 * @throws Error 找不到 shaders.properties 或 zip 解不开
 */
export function parseShaderPack(zipBytes: Uint8Array, fallbackName: string): ShaderPackApplied {
  const files = unzipSync(zipBytes);
  const entry = shaderPropertiesEntry(files);
  if (!entry) {
    throw new Error("zip 里没有找到 shaders.properties（不是光影包？）");
  }
  const properties = parseProperties(decodeUtf8(files[entry]!));
  return buildApplied(fallbackName, properties, {}, []);
}

/**
 * 从服务端代读的配置层构建预设（受管光影包场景）：
 * properties + 包内 GLSL 的标量 #define/const 默认值合并映射，再带上
 * 带「可选值列表」的选项声明（供设置面板渲染 MC 风格的配置项）。
 */
export function shaderPackFromConfig(
  name: string,
  propertiesText: string,
  defines: Record<string, string>,
  packOptions: ShaderPackOption[] = [],
): ShaderPackApplied {
  return buildApplied(name, parseProperties(propertiesText), defines ?? {}, packOptions);
}

/** properties 优先于 GLSL #define（显式配置覆盖源码默认值）。 */
function buildApplied(
  rawName: string,
  options: Record<string, string>,
  defines: Record<string, string>,
  declaredOptions: ShaderPackOption[],
): ShaderPackApplied {
  const lookup = (key: string): string | undefined =>
    options[key] ?? defines[key];
  let tweaks: ShaderPackTweaks = {};
  for (const key of KNOWN_OPTION_KEYS) {
    const value = lookup(key);
    if (value !== undefined) {
      tweaks = { ...tweaks, ...mapPackOption(key, value) };
    }
  }
  // 选项面板只放「改了会生效」的项：映射为空（{}）的声明是包私有概念，放出来是假控件
  const packOptions = declaredOptions.filter(
    (o) => Object.keys(mapPackOption(o.name, o.value)).length > 0,
  );
  return {
    name: rawName.replace(/\.zip$/i, ""),
    options,
    tweaks,
    unmapped: unmappedOptions(options),
    packOptions,
  };
}

/** 取最短路径的 shaders.properties（光影包常有子目录，兼容根探测）。 */
function shaderPropertiesEntry(files: Record<string, Uint8Array>): string | null {
  let best: string | null = null;
  for (const name of Object.keys(files)) {
    if (!name.endsWith("shaders.properties")) {
      continue;
    }
    if (best === null || name.length < best.length) {
      best = name;
    }
  }
  return best;
}

export function parseProperties(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#") || line.startsWith("!")) {
      continue;
    }
    const eq = line.indexOf("=");
    if (eq <= 0) {
      continue;
    }
    result[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return result;
}

/** 已知会出现在配置里、但内置管线不消费的常见键（不计入「未映射」）。 */
const IGNORED_KEYS = new Set([
  ...KNOWN_OPTION_KEYS,
  "SUNLIGHT_INTENSITY", "SKYLIGHT_INTENSITY",
  "profile", "screen", "screen.1", "sliders",
  "shadowDistanceRenderMul", "shadowDistanceMul",
  "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife",
  "underwaterOverlay", "sun", "moon", "vignette", "separateAo",
  "frustum.culling", "rain.depth", "beacon.beam.depth",
  "clouds", "oldLighting", "oldHandLight", "dynamicHandLight",
  // 语义对不上内置管线：NIGHT_BRIGHTNESS 是包内管线的小数乘子（0.0003 量级）、
  // EV_VALUE 是包的自动曝光系统参数、TORCHLIGHT_BRIGHTNESS 对应手持光源——
  // 查看器有自己的等价入口（方块光滑杆），不做名字映射
  "NIGHT_BRIGHTNESS", "EV_VALUE", "TORCHLIGHT_BRIGHTNESS",
]);

function unmappedOptions(options: Record<string, string>): string[] {
  return Object.keys(options).filter((key) => !IGNORED_KEYS.has(key) && !key.startsWith("profile."));
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function decodeUtf8(bytes: Uint8Array): string {
  return new TextDecoder("utf-8").decode(bytes);
}
