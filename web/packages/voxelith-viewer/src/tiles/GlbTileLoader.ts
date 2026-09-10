/**
 * glb 瓦片加载：GLTFLoader 包装 + 材质归一化。
 *
 * 瓦片几何的 POSITION 为瓦片局部坐标，加载后由 TileManager 平移到世界原点。
 * Phase 2 起瓦片携带烘焙光照（_LIGHT ubyte normalized：sky/block/ao）与染色（COLOR_0），
 * 统一转为带光照解码 shader 的 MeshBasicMaterial：texel × tint × 光照亮度 × AO，
 * 光照强度经全局共享 uniforms 参数化（昼夜调光），旧格式瓦片（无 _LIGHT）回退无光照渲染。
 */
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";

/**
 * 全局烘焙光照参数。所有瓦片材质共享同一组 uniform 引用，
 * 修改 value 即时全场景生效（设置面板的昼夜/调光入口）。
 */
export const LightingUniforms = {
  /** 天空光强度（0 = 夜晚，1 = 白天）。 */
  skyLightStrength: { value: 1.0 },
  /** 方块光强度（火把/岩浆等自发光源）。 */
  blockLightStrength: { value: 1.0 },
  /** 环境光遮蔽强度（0 = 关闭 AO）。 */
  aoStrength: { value: 1.0 },
  /** 环境光下限：完全无光处的保底亮度。 */
  ambientFloor: { value: 0.18 },
};

export class GlbTileLoader {
  private readonly loader = new GLTFLoader();

  async load(url: string): Promise<THREE.Group> {
    const gltf = await this.loader.loadAsync(url);
    const root = gltf.scene;
    root.traverse((node) => {
      if (node instanceof THREE.Mesh) {
        node.material = toLitMaterial(node);
        node.matrixAutoUpdate = false;
        node.updateMatrix();
      }
    });
    return root;
  }
}

function toLitMaterial(mesh: THREE.Mesh): THREE.MeshBasicMaterial {
  const source = mesh.material as THREE.MeshStandardMaterial;
  const geometry = mesh.geometry;
  const hasBakedLight = geometry.hasAttribute("_light");
  const material = new THREE.MeshBasicMaterial({
    map: source.map ?? null,
    alphaTest: source.alphaTest > 0 ? source.alphaTest : 0,
    transparent: source.transparent,
    opacity: source.transparent ? source.opacity : 1,
    side: THREE.FrontSide,
    vertexColors: geometry.hasAttribute("color"),
  });
  if (source.transparent) {
    // 半透明面（水）：写入深度以避免相邻瓦片叠blend接缝；透明通道内按距离排序绘制
    material.depthWrite = true;
  }
  if (material.map) {
    // 与 MC 原版一致：放大 Nearest 保持像素锐利；缩小时在 mipmap 链内取最近点、
    // 跨 mip 级线性过渡 —— 避免 LinearMipmapLinear 在图集格子边界串色产生的条纹。
    material.map.magFilter = THREE.NearestFilter;
    material.map.minFilter = THREE.NearestMipmapLinearFilter;
    material.map.generateMipmaps = true;
    material.map.anisotropy = 8;
    material.map.needsUpdate = true;
  }
  material.onBeforeCompile = (shader) => {
    // 共享引用：LightingUniforms 的 value 修改自动同步到所有已编译材质
    Object.assign(shader.uniforms, LightingUniforms);
    shader.vertexShader = shader.vertexShader
      .replace(
        "#include <common>",
        `#include <common>
${hasBakedLight ? "attribute vec3 _light;\nvarying vec3 vBakedLight;" : ""}`,
      )
      .replace(
        "#include <begin_vertex>",
        `#include <begin_vertex>
${hasBakedLight ? "vBakedLight = _light;" : ""}`,
      );
    shader.fragmentShader = shader.fragmentShader
      .replace(
        "#include <common>",
        `#include <common>
${hasBakedLight ? "varying vec3 vBakedLight;" : ""}
uniform float skyLightStrength;
uniform float blockLightStrength;
uniform float aoStrength;
uniform float ambientFloor;`,
      )
      .replace(
        "#include <color_fragment>",
        `#include <color_fragment>
${
  hasBakedLight
    ? `{
  float lum = max(vBakedLight.x * skyLightStrength, vBakedLight.y * blockLightStrength);
  float bright = mix(ambientFloor, 1.0, pow(lum, 1.3));
  float ao = 1.0 - aoStrength * 0.75 * vBakedLight.z;
  diffuseColor.rgb *= bright * ao;
}`
    : ""
}`,
      );
  };
  // onBeforeCompile 与默认材质不同，必须区分程序缓存键
  material.customProgramCacheKey = () =>
    hasBakedLight ? "yudream-baked-light" : "yudream-plain";
  return material;
}
