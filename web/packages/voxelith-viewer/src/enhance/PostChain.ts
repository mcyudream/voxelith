/**
 * 后处理链：场景渲染 → UnrealBloom 泛光 → 输出（Neutral 色调映射 + sRGB）。
 *
 * 光影观感的最后一块：泛光让太阳/雪地/水面高光有「溢出」的能量感，Neutral
 * 色调映射（Khronos PBR Neutral）把过亮的高光压回显示范围且不改变中间调的
 * 色相与饱和度——ACES 会提亮中间调，贴图风地形的观感会发奶白，所以不用。
 * 关闭光影时整条链路旁路，渲染走 renderer.render 原路。
 *
 * 与 Display P3 输出的互斥：P3 模式靠全局替换 colorspace_fragment 给每个
 * 材质的输出插入基色矩阵，而后处理链的中间 RenderTarget 也会走同一 chunk——
 * 颜色会在中间缓冲里被提前变换、最终输出时二次变换，颜色就错了。所以
 * 后处理开启时必须关闭 P3（LightEngine 负责强制这个互斥）。
 */
import * as THREE from "three";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { UnrealBloomPass } from "three/addons/postprocessing/UnrealBloomPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";

export interface PostChainParams {
  /** 泛光强度（0 = 关闭泛光 pass，省一个 pass 的开销）。 */
  bloom: number;
  /** 曝光（0.4..2.5，1 = 不补偿）。 */
  exposure: number;
}

export class PostChain {
  readonly composer: EffectComposer;
  private readonly bloomPass: UnrealBloomPass;
  private bloomEnabled = true;

  constructor(renderer: THREE.WebGLRenderer, scene: THREE.Scene, camera: THREE.Camera) {
    this.composer = new EffectComposer(renderer);
    this.composer.addPass(new RenderPass(scene, camera));
    this.bloomPass = new UnrealBloomPass(
      new THREE.Vector2(renderer.domElement.clientWidth, renderer.domElement.clientHeight),
      0.35, // strength
      0.6,  // radius
      1.2,  // threshold：作用在线性 HDR 中间缓冲上，只有真正超过 1 的能量（太阳/雪面高光）
            // 才溢出。0.9 量级的阈值会把普通受光地表整个卷进泛光，画面发奶白（实测）。
    );
    this.composer.addPass(this.bloomPass);
    this.composer.addPass(new OutputPass());
  }

  setParams(params: PostChainParams): void {
    this.bloomPass.strength = params.bloom;
    const next = params.bloom > 0.001;
    if (next !== this.bloomEnabled) {
      this.bloomPass.enabled = next;
      this.bloomEnabled = next;
    }
    // 曝光由 renderer.toneMappingExposure 承载，OutputPass 采样它
  }

  setSize(width: number, height: number): void {
    this.composer.setSize(width, height);
  }

  render(): void {
    this.composer.render();
  }

  dispose(): void {
    this.composer.dispose();
  }
}
