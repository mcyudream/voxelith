/**
 * 程序化天空穹：昼夜渐变 + 太阳/月亮圆盘 + 星空，替代查看器原本的纯色背景。
 *
 * 为什么用自绘 shader 而不是贴图球：昼夜循环的渐变、晚霞、星空都随太阳方向连续变化，
 * 一张静态贴图表达不了；并且全部在片元里程序化生成，零贴图内存。
 *
 * 与场景的关系：天空穹每帧跟随相机（渲染空间坐标），深度写入关闭、最先渲染，
 * 不参与雾——地平线以下渐变成雾色由远处几何的雾与天空的地平线色共同衔接。
 */
import * as THREE from "three";

const VERTEX = /* glsl */ `
varying vec3 vDirection;
void main() {
  vDirection = position;
  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mvPosition;
}
`;

const FRAGMENT = /* glsl */ `
varying vec3 vDirection;
uniform vec3 uSunDir;
uniform vec3 uMoonDir;
uniform float uDaylight;
uniform vec3 uZenithDay;
uniform vec3 uHorizonDay;
uniform vec3 uZenithNight;
uniform vec3 uHorizonNight;
uniform vec3 uSunColor;
uniform float uStarIntensity;

/** 方向哈希 → 星点：把方向量化到网格，只有极少数格子有星。 */
float starField(vec3 dir) {
  vec3 p = floor(dir * 220.0);
  float h = fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
  float star = step(0.9975, h);
  // 星星亮度再随格子哈希分级，避免全都一样亮
  return star * (0.35 + 0.65 * fract(h * 91.17));
}

void main() {
  vec3 dir = normalize(vDirection);
  float up = clamp(dir.y, -1.0, 1.0);

  // 天顶 → 地平线渐变（地平线以下用镜像，避免穹顶边缘出现暗带）
  float t = pow(1.0 - clamp(up, 0.0, 1.0), 1.6);
  vec3 zenith = mix(uZenithNight, uZenithDay, uDaylight);
  vec3 horizon = mix(uHorizonNight, uHorizonDay, uDaylight);
  vec3 color = mix(zenith, horizon, t);

  // 晚霞：太阳贴着地平线时，面向太阳一侧的地平线染上暖色
  float twilight = exp(-abs(uSunDir.y) * 6.0);
  vec2 dirXZ = normalize(dir.xz + vec2(1e-5));
  vec2 sunXZ = normalize(uSunDir.xz + vec2(1e-5));
  float towardsSun = clamp(dot(dirXZ, sunXZ) * 0.5 + 0.5, 0.0, 1.0);
  color += vec3(0.95, 0.42, 0.18) * twilight * pow(towardsSun, 3.0) * pow(1.0 - clamp(up, 0.0, 1.0), 2.5) * 0.55;

  // 太阳圆盘 + 光晕
  float cosSun = dot(dir, uSunDir);
  float disc = smoothstep(0.9993, 0.9997, cosSun);
  float glow = pow(clamp(cosSun, 0.0, 1.0), 350.0) * 0.5 + pow(clamp(cosSun, 0.0, 1.0), 12.0) * 0.12;
  color += uSunColor * (disc + glow * (0.35 + 0.65 * uDaylight));

  // 月亮：小而冷的白盘，夜里可见
  float cosMoon = dot(dir, uMoonDir);
  float moon = smoothstep(0.99965, 0.99985, cosMoon);
  color += vec3(0.82, 0.87, 0.95) * moon * (1.0 - uDaylight);

  // 星空：夜里淡入，地平线附近被大气光压掉
  float stars = starField(dir) * uStarIntensity * smoothstep(0.0, 0.35, up) * (1.0 - uDaylight);
  color += vec3(stars);

  gl_FragColor = vec4(color, 1.0);
}
`;

export interface SkyDomeOptions {
  /** 穹顶半径（渲染空间）。要小于相机 far、大于最远可视距离。 */
  radius?: number;
}

export class SkyDome {
  readonly mesh: THREE.Mesh;
  private readonly material: THREE.ShaderMaterial;

  constructor(options: SkyDomeOptions = {}) {
    this.material = new THREE.ShaderMaterial({
      vertexShader: VERTEX,
      fragmentShader: FRAGMENT,
      side: THREE.BackSide,
      depthWrite: false,
      depthTest: false,
      fog: false,
      uniforms: {
        uSunDir: { value: new THREE.Vector3(0, 1, 0) },
        uMoonDir: { value: new THREE.Vector3(0, -1, 0) },
        uDaylight: { value: 1 },
        uZenithDay: { value: new THREE.Color(0.32, 0.56, 0.94) },
        uHorizonDay: { value: new THREE.Color(0.74, 0.85, 0.99) },
        uZenithNight: { value: new THREE.Color(0.012, 0.02, 0.05) },
        uHorizonNight: { value: new THREE.Color(0.03, 0.05, 0.10) },
        uSunColor: { value: new THREE.Color(1.0, 0.97, 0.9) },
        uStarIntensity: { value: 0.85 },
      },
    });
    this.mesh = new THREE.Mesh(new THREE.SphereGeometry(options.radius ?? 30000, 48, 24), this.material);
    this.mesh.frustumCulled = false;
    // 最先画，且不写深度：之后的一切几何都直接盖在它上面
    this.mesh.renderOrder = -1000;
  }

  /** 每帧同步：贴着相机（天空没有位置语义），并把昼夜采样写进 uniforms。 */
  update(camera: THREE.Camera, sunDir: { x: number; y: number; z: number }, daylight: number): void {
    this.mesh.position.copy(camera.position);
    const uniforms = this.material.uniforms;
    (uniforms.uSunDir!.value as THREE.Vector3).set(sunDir.x, sunDir.y, sunDir.z).normalize();
    (uniforms.uMoonDir!.value as THREE.Vector3).set(-sunDir.x, -sunDir.y, -sunDir.z).normalize();
    uniforms.uDaylight!.value = daylight;
  }

  setVisible(visible: boolean): void {
    this.mesh.visible = visible;
  }

  dispose(): void {
    this.mesh.geometry.dispose();
    this.material.dispose();
  }
}
