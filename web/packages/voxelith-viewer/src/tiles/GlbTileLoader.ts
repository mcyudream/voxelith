/**
 * glb 瓦片加载：GLTFLoader 包装 + 数据校验 + 材质归一化 + 资源释放。
 *
 * 瓦片几何的 POSITION 为瓦片局部坐标，加载后由 TileManager 平移到世界原点。
 * Phase 2 起瓦片携带烘焙光照（_LIGHT ubyte normalized：sky/block/ao）与染色（COLOR_0），
 * 统一转为带光照解码 shader 的 MeshBasicMaterial：texel × tint × 光照亮度 × AO，
 * 光照强度经全局共享 uniforms 参数化（昼夜调光），旧格式瓦片（无 _LIGHT）回退无光照渲染。
 *
 * 防御约定：
 * - 每个瓦片解析完成后先做 {@link validateTileGroup}：拦截 NaN/Infinity 顶点与越界索引，
 *   坏数据直接抛错进 TileManager 的 failed 集合，绝不允许带病进场景。
 * - hires 瓦片逐瓦片内嵌同一张图集 PNG；传入 sharedAtlas 时用共享纹理替换，
 *   内嵌副本立即 dispose —— 否则每帧几百张重复图集纹理会把显存耗尽。
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

/** 瓦片几何数据校验失败（NaN/Infinity、索引越界等）。 */
export class TileGeometryError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TileGeometryError";
  }
}

/** 共享纹理标记：disposeTileGroup 不释放带此标记的 map（所有权在调用方）。 */
const SHARED_TEXTURE_KEY = "voxelithShared";

/**
 * 瓦片几何健全性校验：在 BufferGeometry 进场景前拦截脏数据。
 *
 * 检查项（发现即抛 {@link TileGeometryError}）：
 * 1. 缺少 position 属性；
 * 2. 任何 float32 属性（position/normal/uv 等）含 NaN 或 Infinity —— GPU 端表现为顶点爆炸；
 * 3. 索引最大值越界（>= position.count）—— GPU 端表现为随机三角形/条状拉伸。
 *
 * 成本：每瓦片数万次数组读，相对网络与解码耗时可忽略；Uint8 属性天然有限，跳过。
 */
export function validateTileGroup(group: THREE.Group, label = "tile"): void {
  group.traverse((node) => {
    if (!(node instanceof THREE.Mesh)) {
      return;
    }
    const geometry = node.geometry as THREE.BufferGeometry;
    const position = geometry.getAttribute("position");
    if (!position) {
      throw new TileGeometryError(`${label}: 缺少 position 属性`);
    }
    for (const [name, attribute] of Object.entries(geometry.attributes)) {
      const array = (attribute as THREE.BufferAttribute).array;
      if (!(array instanceof Float32Array)) {
        continue;
      }
      for (let i = 0; i < array.length; i++) {
        if (!Number.isFinite(array[i])) {
          throw new TileGeometryError(
            `${label}: 属性 ${name} 含 NaN/Infinity（元素 ${i}/${array.length}）`,
          );
        }
      }
    }
    const index = geometry.getIndex();
    if (index) {
      const array = index.array as unknown as ArrayLike<number>;
      let max = -1;
      for (let i = 0; i < array.length; i++) {
        const v = array[i] ?? -1;
        if (v > max) {
          max = v;
        }
      }
      if (max >= position.count) {
        throw new TileGeometryError(
          `${label}: 索引越界 max=${max}，顶点数=${position.count}`,
        );
      }
    }
  });
}

/**
 * 释放瓦片组的全部 GPU 资源：geometry + material + 纹理。
 * 共享图集（带 SHARED_TEXTURE_KEY 标记）不释放，所有权在 TileManager。
 */
export function disposeTileGroup(group: THREE.Group): void {
  group.traverse((node) => {
    if (!(node instanceof THREE.Mesh)) {
      return;
    }
    node.geometry.dispose();
    const material = node.material as THREE.MeshBasicMaterial;
    const map = material.map;
    if (map && !(map.userData && map.userData[SHARED_TEXTURE_KEY])) {
      map.dispose();
    }
    material.dispose();
  });
}

export interface GlbTileLoaderOptions {
  /**
   * hires 共享图集纹理：替换每瓦片内嵌的图集副本（每个 hires glb 都内嵌同一张
   * 图集 PNG，GLTFLoader 会逐瓦片解码出独立 GPU 纹理，显存随加载数线性增长）。
   * 必须按 {@link configureHiresAtlas} 配置；LOD 航拍色图逐瓦片不同，不受影响。
   */
  sharedAtlas?: THREE.Texture;
}

/**
 * hires 图集过滤：禁止 mipmap。256² 图集里 69 个格子，任何带 mip 的 minFilter
 * 都会在格子边界串色，俯视时整片变成彩虹噪点。anisotropy 同样会跨格采样。
 */
export function configureHiresAtlas(texture: THREE.Texture): THREE.Texture {
  texture.flipY = false;
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.generateMipmaps = false;
  texture.anisotropy = 1;
  texture.wrapS = THREE.ClampToEdgeWrapping;
  texture.wrapT = THREE.ClampToEdgeWrapping;
  texture.needsUpdate = true;
  return texture;
}

/** LOD 航拍色图：与 glTF sampler（LINEAR / LINEAR / CLAMP）一致，不生成 mip。 */
export function configureLodColormap(texture: THREE.Texture): THREE.Texture {
  texture.flipY = false;
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.LinearFilter;
  texture.minFilter = THREE.LinearFilter;
  texture.generateMipmaps = false;
  texture.anisotropy = 1;
  texture.wrapS = THREE.ClampToEdgeWrapping;
  texture.wrapT = THREE.ClampToEdgeWrapping;
  return texture;
}

export class GlbTileLoader {
  private readonly loader = new GLTFLoader();
  private readonly sharedAtlas?: THREE.Texture;

  constructor(options: GlbTileLoaderOptions = {}) {
    this.sharedAtlas = options.sharedAtlas;
    if (this.sharedAtlas) {
      configureHiresAtlas(this.sharedAtlas);
      this.sharedAtlas.userData[SHARED_TEXTURE_KEY] = true;
    }
  }

  async load(url: string): Promise<THREE.Group> {
    const gltf = await this.loader.loadAsync(url);
    const root = gltf.scene;
    validateTileGroup(root, url);
    // 同一 glb 内多个 primitive（opaque + 水面）共享同一内嵌纹理，替换时去重避免重复 dispose
    const replaced = new Set<THREE.Texture>();
    root.traverse((node) => {
      if (node instanceof THREE.Mesh) {
        node.material = this.toLitMaterial(node, replaced);
        node.matrixAutoUpdate = false;
        node.updateMatrix();
      }
    });
    return root;
  }

  private toLitMaterial(
    mesh: THREE.Mesh,
    replaced: Set<THREE.Texture>,
  ): THREE.MeshBasicMaterial {
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
    if (material.map && source.map) {
      // LOD 色图 sampler 为 LINEAR（9729）；hires 图集为 NEAREST（9728）。
      const linear = source.map.magFilter === THREE.LinearFilter;
      if (linear) {
        configureLodColormap(material.map);
      } else if (this.sharedAtlas) {
        // hires：共享图集替换内嵌副本；内嵌副本立即释放。
        // 多 primitive 共享同一内嵌纹理：第二个 mesh 同样换成共享图集，但只 dispose 一次。
        const embedded = material.map;
        material.map = this.sharedAtlas;
        if (!replaced.has(embedded)) {
          replaced.add(embedded);
          embedded.dispose();
        }
      } else {
        configureHiresAtlas(material.map);
      }
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
    // GLTFLoader 创建的原始 PBR 材质不再使用，立即释放
    source.dispose();
    return material;
  }
}
