/**
 * 实时光照：太阳/月亮方向光（带阴影贴图）+ 环境光。
 *
 * 基线不变式（这条是整个模块最重要的约定）：
 * **光影关闭时，瓦片材质（MeshLambertMaterial）必须渲染出与旧 MeshBasicMaterial
 * 完全相同的颜色。** three 0.179 的 Lambert 光照带 1/π 因子（BRDF_Lambert），
 * 而 AmbientLight 的 uniform 不做 π 补偿——所以「环境光全亮」的强度是 Math.PI。
 * 关闭时 ambient = π、太阳强度 0、shadowMap 关闭，与旧管线逐像素一致；
 * 开启后所有「用户强度」参数都乘 π 换算成 three 的物理口径。
 *
 * 阴影相机：正交、跟随相机焦点。焦点按阴影贴图纹素尺寸取整（world-space snapping），
 * 否则相机一动阴影边缘就闪烁。烘焙天空光（_LIGHT）继续负责洞穴/屋檐的静态明暗，
 * 这里的阴影贴图只加「太阳被山体/建筑挡住」的动态成分——两者叠加，不互相替代。
 */
import * as THREE from "three";
import type { DayNightSample } from "./DayNightCycle.js";

export interface SunShadowOptions {
  /** 阴影贴图边长（纹素）。1024 快但虚，4096 细但贵；默认 2048。 */
  shadowMapSize?: number;
}

export class SunShadow {
  readonly ambient: THREE.AmbientLight;
  readonly sun: THREE.DirectionalLight;

  private mapSize: number;
  private shadowDistance = 160;  /** 当前光照方向（update 里由太阳/月亮接力产生，阴影相机跟随用）。 */
  private readonly lightDir = new THREE.Vector3(0.5, 0.8, 0.2);
  /** 用户口径的强度参数（内部统一乘 π 换算，见类注释）。 */
  private sunStrength = 0.9;
  private ambientStrength = 0.5;

  constructor(options: SunShadowOptions = {}) {
    this.mapSize = options.shadowMapSize ?? 2048;
    // 强度 π = 「环境光贡献 1.0 × 反照率」，与旧无光照管线的输出一致
    this.ambient = new THREE.AmbientLight(0xffffff, Math.PI);
    this.sun = new THREE.DirectionalLight(0xffffff, 0);
    this.sun.castShadow = false;
    this.sun.shadow.mapSize.set(this.mapSize, this.mapSize);
    this.applyShadowDistance(160);
    // 影缘既不能硬成锯齿，也不能糊到看不出轮廓
    this.sun.shadow.radius = 3;
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.6;
  }

  /**
   * 运行时改阴影贴图边长（光影包的 shadowMapResolution 选项）。
   * 换尺寸必须丢弃旧的深度贴图，否则 three 继续复用旧尺寸的纹理。
   */
  setMapSize(size: number): void {
    const next = Math.round(Math.min(4096, Math.max(1024, size)));
    if (next === this.mapSize) {
      return;
    }
    this.mapSize = next;
    this.sun.shadow.mapSize.set(next, next);
    if (this.sun.shadow.map) {
      this.sun.shadow.map.dispose();
      this.sun.shadow.map = null;
    }
  }

  /** 阴影相机半宽（方块）。光影包的 shadowDistance 直接映射到这里。 */
  get distance(): number {
    return this.shadowDistance;
  }

  set distance(value: number) {
    this.applyShadowDistance(value);
  }

  /** 开关实时阴影。切换 shadowMap.enabled 必须让全部材质重编译，否则不生效。 */
  setShadowEnabled(scene: THREE.Scene, renderer: THREE.WebGLRenderer, enabled: boolean): void {
    const next = renderer.shadowMap.enabled !== enabled;
    renderer.shadowMap.enabled = enabled;
    this.sun.castShadow = enabled;
    if (next) {
      scene.traverse((node) => {
        if (node instanceof THREE.Mesh) {
          (node.material as THREE.Material).needsUpdate = true;
        }
      });
    }
  }

  setStrength(sunStrength: number, ambientStrength: number): void {
    this.sunStrength = sunStrength;
    this.ambientStrength = ambientStrength;
  }

  /**
   * 每帧更新：太阳/月亮接力的方向与强度、环境光昼夜曲线、阴影相机跟随。
   *
   * @param focus 阴影相机焦点（取相机注视点附近，一般直接用相机位置）
   * @param enabled false = 光影关闭：环境光回到全亮基线、太阳归零（基线不变式）
   */
  update(focus: THREE.Vector3, sample: DayNightSample, enabled: boolean): void {
    if (!enabled) {
      this.ambient.intensity = Math.PI;
      this.sun.intensity = 0;
      return;
    }
    const daylight = sample.daylight;
    // 太阳与月亮接力：白昼用太阳（暖白、日落偏橙），夜里用月亮（冷蓝、很弱）
    const useSun = sample.sunDir.y > -0.04;
    const dir = useSun ? sample.sunDir : sample.moonDir;
    const sunUp = Math.max(0, sample.sunElevation);
    const sunFactor = Math.min(1, sunUp / 0.35);
    const sunColor = new THREE.Color(1.0, 0.62 + 0.36 * sunFactor, 0.32 + 0.62 * sunFactor);
    const sunI = this.sunStrength * sunFactor * daylight;
    const moonI = this.sunStrength * 0.1 * (1 - daylight);
    if (sunI >= moonI) {
      this.sun.color.copy(sunColor);
      this.sun.intensity = sunI * Math.PI;
    } else {
      this.sun.color.setRGB(0.55, 0.65, 1.0);
      this.sun.intensity = moonI * Math.PI;
    }
    this.lightDir.set(dir.x, dir.y, dir.z).normalize();
    // 环境光：白天≈ambientStrength、夜里留 0.22 底（夜空散射），避免夜里死黑
    const ambientLevel = this.ambientStrength * (0.22 + 0.78 * daylight);
    this.ambient.intensity = Math.max(0.06, ambientLevel) * Math.PI;
    this.snapShadowCamera(focus);
  }

  /** 焦点按阴影纹素尺寸取整（消除相机移动时的阴影边缘闪烁），光位随之重建。 */
  private snapShadowCamera(focus: THREE.Vector3): void {
    const texel = (2 * this.shadowDistance) / this.mapSize;
    const x = Math.round(focus.x / texel) * texel;
    const z = Math.round(focus.z / texel) * texel;
    const y = Math.round(focus.y / (texel * 4)) * texel * 4;
    this.sun.target.position.set(x, y, z);
    this.sun.target.updateMatrixWorld();
    this.sun.position.copy(this.sun.target.position).addScaledVector(this.lightDir, 400);
  }

  private applyShadowDistance(value: number): void {
    this.shadowDistance = Math.min(512, Math.max(64, value));
    const camera = this.sun.shadow.camera;
    camera.left = -this.shadowDistance;
    camera.right = this.shadowDistance;
    camera.top = this.shadowDistance;
    camera.bottom = -this.shadowDistance;
    camera.near = 50;
    camera.far = 1000;
    camera.updateProjectionMatrix();
  }
}
