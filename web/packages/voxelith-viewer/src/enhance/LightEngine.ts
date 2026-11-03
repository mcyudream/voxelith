/**
 * 光影引擎门面：把昼夜循环、天空穹、云层、实时阴影、后处理组装成一个开关。
 *
 * 设计约定：
 * - **关闭 = 与旧渲染完全一致**。环境光回到全亮基线（π）、太阳归零、阴影关、
 *   天空穹与云隐藏、后处理旁路、色调映射还原 NoToneMapping。使用者随时可以
 *   在两种画质之间来回切，不产生任何残留状态。
 * - **材质不必为光影重建**。瓦片材质固定 MeshLambertMaterial（见 GlbTileLoader），
 *   光影开关只动灯光与渲染器状态，几百万顶点的场景无需重新加载。
 * - **与 Display P3 输出互斥**（原因见 PostChain 顶注）：开启光影时强制关闭 P3。
 *
 * 典型用法：
 * ```ts
 * const light = new LightEngine(engine);
 * light.setParams({ enabled: true, timeOfDay: 17.5, shadow: true });
 * light.applyShaderPack(parsed);   // 可选：光影包的等效参数
 * ```
 */
import * as THREE from "three";
import type { MapEngine } from "../engine/MapEngine.js";
import { LightingUniforms } from "../tiles/GlbTileLoader.js";
import { DayNightCycle } from "./DayNightCycle.js";
import { CloudLayer } from "./CloudLayer.js";
import { PostChain } from "./PostChain.js";
import { SkyDome } from "./SkyDome.js";
import { SunShadow } from "./SunShadow.js";
import { mapPackOption, type ShaderPackApplied } from "./ShaderPack.js";

export interface LightParams {
  /** 光影总开关（关 = 旧渲染管线，逐像素一致）。 */
  enabled: boolean;
  /** 一天中的时刻（小时 0..24，6 = 日出 18 = 日落）。 */
  timeOfDay: number;
  /** 自动昼夜循环。 */
  autoCycle: boolean;
  /** 一昼夜对应的真实分钟数（自动循环速度）。 */
  cycleMinutes: number;
  /** 太阳强度（0..2，1 = 晴天正午的标定值）。 */
  sunStrength: number;
  /** 环境光强度（0..1，1 = 无方向性的全亮环境）。 */
  ambientStrength: number;
  /** 实时阴影（太阳被山体/建筑遮挡）。 */
  shadow: boolean;
  /** 阴影相机半宽（方块，64..512）。 */
  shadowDistance: number;
  /** 阴影贴图边长（纹素，1024..4096）。光影包的 shadowMapResolution 映射到这里。 */
  shadowMapSize: number;
  /** 太阳轨迹倾角（度，-60..60，光影包的 sunPathRotation）。 */
  sunPathRotation: number;
  /** 泛光强度（0..1.5，0 = 关）。 */
  bloom: number;
  /** 曝光（0.4..2.5，1 = 不补偿）。 */
  exposure: number;
  /** 云层显隐。 */
  clouds: boolean;
}

export const DEFAULT_LIGHT_PARAMS: LightParams = {
  enabled: false,
  timeOfDay: 10,
  autoCycle: false,
  cycleMinutes: 10,
  // 标定（见 frame 里 BAKED_SKY_SCALE 的说明）：顶面总亮度 ≈ 1.0×反照率，与原版一致；
  // 环境光承担面方向明暗的底、太阳只做增量与阴影，否则烘焙天空光上再叠全额太阳会过曝
  sunStrength: 0.55,
  ambientStrength: 0.75,
  shadow: true,
  shadowDistance: 160,
  shadowMapSize: 2048,
  sunPathRotation: 0,
  // 亮色地表（沙滩/雪原/白石板）在直射下很容易溢出，默认给得保守，面板里可以加
  bloom: 0.25,
  exposure: 1,
  clouds: true,
};

/**
 * 光影开启时对烘焙天空光的缩放。烘焙 sky light 本身已经含「这面朝天 = 晒满阳光」
 * 的语义，再叠加全额太阳直射就是重复计光（实测顶面 1.4× 反照率，高光全灭）——
 * 这里压到 0.8 作为底光，太阳/环境光在其上做方向增量与阴影。
 */
const BAKED_SKY_SCALE = 0.8;

export interface LightEngineOptions {
  /** 天空穹半径（渲染空间）。默认 30000（相机 far 100000 内、覆盖常规远景）。 */
  skyRadius?: number;
  /** 云层初始高度（渲染空间 Y）。换地图时用 setCloudAltitude 跟随地形。 */
  cloudAltitude?: number;
}

export class LightEngine {
  private readonly engine: MapEngine;
  private readonly cycle = new DayNightCycle();
  private readonly sky: SkyDome;
  private readonly clouds: CloudLayer;
  private readonly lights: SunShadow;
  private readonly post: PostChain;
  private readonly params: LightParams = { ...DEFAULT_LIGHT_PARAMS };
  /** 开启光影前的场景背景（关闭光影时还原）。 */
  private readonly savedBackground: THREE.Color | null;

  constructor(engine: MapEngine, options: LightEngineOptions = {}) {
    this.engine = engine;
    this.savedBackground = engine.scene.background as THREE.Color | null;
    this.sky = new SkyDome({ radius: options.skyRadius });
    this.clouds = new CloudLayer({
      altitude: options.cloudAltitude ?? 220,
      extent: Math.min(options.skyRadius ?? 30000, 20000),
    });
    this.lights = new SunShadow();
    this.post = new PostChain(engine.renderer, engine.scene, engine.camera);
    engine.scene.add(this.sky.mesh, this.clouds.mesh, this.lights.ambient, this.lights.sun, this.lights.sun.target);
    engine.addFrameHook(this.frame);
    // 参数走一遍 setParams 完成初始状态装配（enabled=false 的基线也在里面）
    this.setParams(this.params);
  }

  get currentParams(): Readonly<LightParams> {
    return this.params;
  }

  /** 已应用的光影包名（未加载过 = null），设置面板展示用。 */
  shaderPackName: string | null = null;

  /**
   * 更新参数（任意子集）。频繁调用安全：内部全部是赋值与开关，无重建。
   */
  setParams(patch: Partial<LightParams>): void {
    Object.assign(this.params, patch);
    const p = this.params;
    const on = p.enabled;

    // 与 P3 广色域互斥：后处理链的中间缓冲会吃掉 P3 输出变换，两者同时开颜色必错
    if (on && this.engine.isDisplayP3) {
      this.engine.setDisplayP3(false);
    }
    // Neutral 而不是 ACES：ACES 会把中间调整体提亮，贴图风的地形会发奶白；
    // Khronos Neutral 只压缩高光、保住色相与饱和度，方块地形的观感更接近原版
    this.engine.renderer.toneMapping = on ? THREE.NeutralToneMapping : THREE.NoToneMapping;
    this.engine.renderer.toneMappingExposure = p.exposure;
    this.engine.setPostPipeline(on ? this.post : null);
    this.engine.scene.background = on ? null : this.savedBackground;

    this.sky.setVisible(on);
    this.clouds.setVisible(on && p.clouds);
    this.lights.setShadowEnabled(this.engine.scene, this.engine.renderer, on && p.shadow);
    this.lights.distance = p.shadowDistance;
    this.lights.setMapSize(p.shadowMapSize);
    this.lights.setStrength(p.sunStrength, p.ambientStrength);
    this.cycle.timeOfDay = p.timeOfDay;
    this.cycle.sunPathRotation = p.sunPathRotation;
    this.cycle.hoursPerSecond = on && p.autoCycle ? 24 / (p.cycleMinutes * 60) : 0;
    this.post.setParams({ bloom: p.bloom, exposure: p.exposure });
    if (!on) {
      // 关闭时把烘焙天空光调光还给用户滑杆（开启时由昼夜循环驱动，见 frame）
      this.applyBakedSkyLight(this.userSkyLight);
    }
  }

  /** 云层高度（渲染空间 Y）。换地图时按新地图的包围盒顶调用。 */
  setCloudAltitude(altitude: number): void {
    this.clouds.altitude = altitude;
  }

  /**
   * 应用光影包的等效参数（解析层映射结果）。只覆盖包里给出的项，
   * 其余保持用户当前设置。
   */
  applyShaderPack(pack: ShaderPackApplied): void {
    this.shaderPackName = pack.name;
    this.setParams(pack.tweaks);
  }

  /**
   * 改光影包的**单个**选项（设置面板下拉框）。映射与整包应用共用同一张表，
   * 改一项只动对应参数，其余保持不变。
   */
  setPackOption(name: string, value: string): void {
    this.setParams(mapPackOption(name, value));
  }

  /** 用户设置的天空光滑杆值（光影开启期间由昼夜循环接管该 uniform）。 */
  userSkyLight = 1;

  /** 清掉已加载的光影包（名字还原，参数保持用户当前值）。 */
  resetShaderPack(): void {
    this.shaderPackName = null;
  }

  private frame = (dtSeconds: number): void => {
    const p = this.params;
    if (!p.enabled) {
      return;
    }
    this.cycle.advance(dtSeconds);
    const sample = this.cycle.sample();
    this.sky.update(this.engine.camera, sample.sunDir, sample.daylight);
    this.clouds.update(this.engine.camera, dtSeconds, sample.daylight);
    this.lights.update(this.engine.camera.position, sample, p.enabled);
    // 烘焙天空光跟着昼夜走并压到 0.8（见 BAKED_SKY_SCALE）；
    // 夜里留底 = 月光照明的观感，火把等方块光不受影响
    this.applyBakedSkyLight((0.22 + 0.78 * sample.daylight) * BAKED_SKY_SCALE);
  };

  private applyBakedSkyLight(value: number): void {
    LightingUniforms.skyLightStrength.value = value;
  }

  dispose(): void {
    this.setParams({ enabled: false });
    this.engine.removeFrameHook(this.frame);
    this.engine.setPostPipeline(null);
    this.engine.scene.remove(
      this.sky.mesh, this.clouds.mesh, this.lights.ambient, this.lights.sun, this.lights.sun.target,
    );
    this.sky.dispose();
    this.clouds.dispose();
    this.post.dispose();
  }
}
