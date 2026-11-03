/**
 * 平流层云：一层程序化噪声云图铺在地面上方，随时间缓慢漂移、随昼夜变色。
 *
 * 原版的云是 y≈192 的一层方块化像素云；这里用可平铺 value noise 生成软性云图
 * （一次生成一张 256² 画布纹理，之后零 CPU 开销），观感介于原版方块云与
 * 光影包的卷云之间。云面永远跟随相机 XZ，靠 UV 偏移保持世界锚定——
 * 云在世界系里不动（除了风），移动的是相机的「取景窗」。
 */
import * as THREE from "three";

const TEXTURE_SIZE = 256;
/** 一张云图纹理覆盖的世界尺寸（方块）。 */
const CLOUD_TILE_BLOCKS = 1024;
/** 风速（方块/秒，沿 +X）。原版约 0.6 方块/秒，这里取同量级。 */
const WIND_BLOCKS_PER_SECOND = 1.2;

export interface CloudLayerOptions {
  /** 云层高度（渲染空间 Y）。 */
  altitude: number;
  /** 云面边长（方块）。要大于远景可视距离，边缘用透明度淡出遮丑。 */
  extent?: number;
}

export class CloudLayer {
  readonly mesh: THREE.Mesh;
  private readonly material: THREE.MeshBasicMaterial;
  private readonly texture: THREE.CanvasTexture;
  private readonly extent: number;
  private time = 0;

  constructor(options: CloudLayerOptions) {
    this.extent = options.extent ?? 20000;
    this.texture = makeCloudTexture();
    this.material = new THREE.MeshBasicMaterial({
      map: this.texture,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
      opacity: 0.72,
      color: 0xffffff,
    });
    const geometry = new THREE.PlaneGeometry(this.extent, this.extent);
    geometry.rotateX(-Math.PI / 2);
    this.mesh = new THREE.Mesh(geometry, this.material);
    this.mesh.position.y = options.altitude;
    this.mesh.renderOrder = 900; // 半透明最后画
    const repeat = this.extent / CLOUD_TILE_BLOCKS;
    this.texture.wrapS = THREE.RepeatWrapping;
    this.texture.wrapT = THREE.RepeatWrapping;
    this.texture.repeat.set(repeat, repeat);
  }

  get altitude(): number {
    return this.mesh.position.y;
  }

  set altitude(value: number) {
    this.mesh.position.y = value;
  }

  /**
   * 每帧同步：云面跟随相机 XZ，UV 反向补偿相机位移（世界锚定）并叠加风漂移。
   *
   * @param daylight 昼光系数（云的颜色跟着昼夜走）
   */
  update(camera: THREE.Camera, dtSeconds: number, daylight: number): void {
    this.time += dtSeconds;
    this.mesh.position.x = camera.position.x;
    this.mesh.position.z = camera.position.z;
    const uvPerBlock = 1 / CLOUD_TILE_BLOCKS;
    const u = camera.position.x * uvPerBlock + this.time * WIND_BLOCKS_PER_SECOND * uvPerBlock;
    const v = camera.position.z * uvPerBlock;
    this.texture.offset.set(u, v);
    // 白天纯白、夜晚压成冷灰蓝（云被月光照亮的感觉）
    this.material.color.setRGB(
      0.22 + 0.78 * daylight,
      0.25 + 0.75 * daylight,
      0.38 + 0.62 * daylight,
    );
  }

  setVisible(visible: boolean): void {
    this.mesh.visible = visible;
  }

  dispose(): void {
    this.mesh.geometry.dispose();
    this.texture.dispose();
    this.material.dispose();
  }
}

/** 生成一张可平铺的 value-noise 云图（RGB=白，A=云量）。 */
function makeCloudTexture(): THREE.CanvasTexture {
  const canvas = document.createElement("canvas");
  canvas.width = TEXTURE_SIZE;
  canvas.height = TEXTURE_SIZE;
  const ctx = canvas.getContext("2d")!;
  const image = ctx.createImageData(TEXTURE_SIZE, TEXTURE_SIZE);
  const noise = tileableNoise(TEXTURE_SIZE, 4);
  for (let y = 0; y < TEXTURE_SIZE; y++) {
    for (let x = 0; x < TEXTURE_SIZE; x++) {
      const i = (y * TEXTURE_SIZE + x) * 4;
      // 两个八度叠出形状，再用阈值切出云朵边缘
      const n = noise(x, y) * 0.7 + noise((x * 3) % TEXTURE_SIZE, (y * 3) % TEXTURE_SIZE) * 0.3;
      const alpha = Math.round(smoothstep(0.52, 0.72, n) * 235);
      image.data[i] = 255;
      image.data[i + 1] = 255;
      image.data[i + 2] = 255;
      image.data[i + 3] = alpha;
    }
  }
  ctx.putImageData(image, 0, 0);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.LinearFilter;
  texture.minFilter = THREE.LinearFilter;
  return texture;
}

/** 可平铺 value noise：随机格点 + 周期双线性插值，幅度归一。 */
function tileableNoise(size: number, cells: number): (x: number, y: number) => number {
  const grid = new Float32Array(cells * cells);
  for (let i = 0; i < grid.length; i++) {
    grid[i] = Math.random();
  }
  const at = (cx: number, cy: number) => grid[((cy % cells + cells) % cells) * cells + ((cx % cells + cells) % cells)]!;
  const fade = (t: number) => t * t * (3 - 2 * t);
  return (x, y) => {
    const fx = (x / size) * cells;
    const fy = (y / size) * cells;
    const x0 = Math.floor(fx);
    const y0 = Math.floor(fy);
    const tx = fade(fx - x0);
    const ty = fade(fy - y0);
    const a = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx;
    const b = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx;
    return a * (1 - ty) + b * ty;
  };
}

function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)));
  return t * t * (3 - 2 * t);
}
