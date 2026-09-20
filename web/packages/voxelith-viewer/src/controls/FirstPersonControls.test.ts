import * as THREE from "three";
import { describe, expect, it } from "vitest";
import {
  FirstPersonControls,
  type TerrainMedium,
  type TerrainProbe,
} from "./FirstPersonControls.js";

/** 控制器只用到 window 事件与画布的点击/指针锁定，node 环境下给最小替身。 */
const canvas = {
  addEventListener() {},
  removeEventListener() {},
  requestPointerLock() {},
} as unknown as HTMLCanvasElement;

interface DomStub {
  /** 触发注册在 window 上的监听器（键盘按下/抬起）。 */
  fire(type: string, event: unknown): void;
}

function installDom(): DomStub {
  const listeners = new Map<string, Set<(event: unknown) => void>>();
  Object.assign(globalThis, {
    window: {
      addEventListener(type: string, fn: (event: unknown) => void) {
        const set = listeners.get(type) ?? new Set<(event: unknown) => void>();
        set.add(fn);
        listeners.set(type, set);
      },
      removeEventListener(type: string, fn: (event: unknown) => void) {
        listeners.get(type)?.delete(fn);
      },
    },
    document: { pointerLockElement: null, exitPointerLock() {} },
  });
  return {
    fire(type, event) {
      for (const fn of listeners.get(type) ?? []) {
        fn(event);
      }
    },
  };
}

/**
 * 测试用假地形：列高度场（列 = 1×1 方块，key 为 `${floor(x)},${floor(z)}`）。
 * 只实现控制器真正会用的轴对齐射线：竖直向下 / 向上、水平向前。
 */
class FakeTerrain implements TerrainProbe {
  private readonly tops = new Map<string, number>();
  /** 水面高度（null = 没有水）：整片地图一个水平面，用来测游泳/沉水 */
  waterLevel: number | null = null;

  constructor(private readonly base: number) {}

  /** 把 [x0,x1) × [z0,z1) 的列顶高度设为 y（模拟一整片台地/墙）。 */
  fill(x0: number, x1: number, z0: number, z1: number, y: number): this {
    for (let cx = x0; cx < x1; cx++) {
      for (let cz = z0; cz < z1; cz++) {
        this.tops.set(`${cx},${cz}`, y);
      }
    }
    return this;
  }

  private columnTop(x: number, z: number): number {
    return this.tops.get(`${Math.floor(x)},${Math.floor(z)}`) ?? this.base;
  }

  topSurfaceY(x: number, z: number, medium: TerrainMedium = "solid"): number | null {
    if (medium === "water") {
      return this.waterLevel;
    }
    return this.columnTop(x, z);
  }

  groundBelow(
    x: number,
    z: number,
    fromY: number,
    maxDrop = Number.POSITIVE_INFINITY,
    medium: TerrainMedium = "solid",
  ): number | null {
    if (medium === "water") {
      if (this.waterLevel === null || this.waterLevel > fromY || fromY - this.waterLevel > maxDrop) {
        return null;
      }
      return this.waterLevel;
    }
    const top = this.columnTop(x, z);
    // 起点在地表之下 = 没有"脚下的地面"；超出下探深度同样算探不到
    if (top > fromY || fromY - top > maxDrop) {
      return null;
    }
    return top;
  }

  castRay(
    x: number,
    y: number,
    z: number,
    dx: number,
    dy: number,
    dz: number,
    maxDistance: number,
    medium: TerrainMedium = "solid",
  ): number | null {
    if (medium === "water") {
      // 水面是水平面：只有竖直射线能打到（水平射线穿过水体不算墙）
      if (dx !== 0 || dz !== 0 || this.waterLevel === null || dy >= 0) {
        return null;
      }
      const distance = y - this.waterLevel;
      return distance >= 0 && distance <= maxDistance ? distance : null;
    }
    if (dx === 0 && dz === 0) {
      const top = this.columnTop(x, z);
      if (dy > 0) {
        const distance = top - y; // 头顶方块底面
        return distance > 0 && distance <= maxDistance ? distance : null;
      }
      if (dy < 0) {
        const distance = y - top;
        return distance >= 0 && distance <= maxDistance ? distance : null;
      }
      return null;
    }
    // 水平：0.1 格步进，射线高度上出现实心列即命中
    for (let t = 0.1; t <= maxDistance + 1e-9; t += 0.1) {
      if (this.columnTop(x + dx * t, z + dz * t) > y) {
        return t;
      }
    }
    return null;
  }
}

const EYE = 1.62;
const GROUND = 64;

function cameraAt(x: number, y: number, z: number): THREE.PerspectiveCamera {
  const camera = new THREE.PerspectiveCamera();
  camera.position.set(x, y, z);
  return camera;
}

function press(dom: DomStub, code: string): void {
  dom.fire("keydown", { code, preventDefault() {} });
}

function release(dom: DomStub, code: string): void {
  dom.fire("keyup", { code });
}

function step(controls: FirstPersonControls, seconds: number): void {
  const frames = Math.round(seconds * 60);
  for (let i = 0; i < frames; i++) {
    controls.update(1 / 60);
  }
}

describe("FirstPersonControls 生存式移动", () => {
  it("切进第一人称站到地表上：不悬空、也不下沉", () => {
    installDom();
    const camera = cameraAt(0, 200, 0); // 自由飞行悬在地图上空
    const controls = new FirstPersonControls(camera, canvas, { probe: new FakeTerrain(GROUND) });

    expect(camera.position.y).toBeCloseTo(GROUND + EYE, 5);
    step(controls, 2);
    expect(camera.position.y).toBeCloseTo(GROUND + EYE, 5);
  });

  it("一格高的坎走不上去、跳上去才站得稳（MC 同手感）", () => {
    const dom = installDom();
    const terrain = new FakeTerrain(GROUND).fill(-8, 8, -40, -2, GROUND + 1);
    const camera = cameraAt(0, GROUND + EYE, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });

    press(dom, "KeyW");
    step(controls, 2);
    // 贴住台边停下（台面自 z = -2 起，玩家半径 0.3），且没有被抬上去
    expect(camera.position.z).toBeCloseTo(-2 + 0.3, 1);
    expect(camera.position.y).toBeCloseTo(GROUND + EYE, 5);

    press(dom, "Space");
    step(controls, 0.1); // 起跳（按住 Space 会像 MC 一样连跳，这里只跳一次）
    release(dom, "Space");
    step(controls, 0.5);
    release(dom, "KeyW");
    step(controls, 1.5);
    expect(camera.position.z).toBeLessThan(-2);
    expect(camera.position.y).toBeCloseTo(GROUND + 1 + EYE, 5);
  });

  it("半格高的坎自动迈上去，不用跳", () => {
    const dom = installDom();
    const terrain = new FakeTerrain(GROUND).fill(-8, 8, -40, -2, GROUND + 0.5);
    const camera = cameraAt(0, GROUND + EYE, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });

    press(dom, "KeyW");
    step(controls, 1.5);
    expect(camera.position.z).toBeLessThan(-2);
    expect(camera.position.y).toBeCloseTo(GROUND + 0.5 + EYE, 5);
  });

  it("撞墙停下不穿模", () => {
    const dom = installDom();
    const terrain = new FakeTerrain(GROUND).fill(-8, 8, -40, -2, GROUND + 3);
    const camera = cameraAt(0, GROUND + EYE, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });

    press(dom, "KeyW");
    step(controls, 3);
    expect(camera.position.z).toBeCloseTo(-2 + 0.3, 1);
    expect(camera.position.y).toBeCloseTo(GROUND + EYE, 5);
  });

  it("走出悬崖边会自由落体落到下方地面", () => {
    const dom = installDom();
    const terrain = new FakeTerrain(GROUND).fill(-20, 20, -40, -2, GROUND - 6);
    const camera = cameraAt(0, GROUND + EYE, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });

    press(dom, "KeyW");
    step(controls, 3);
    expect(camera.position.z).toBeLessThan(-2);
    expect(camera.position.y).toBeCloseTo(GROUND - 6 + EYE, 5);
  });

  it("地形还没加载时原地悬停，绝不掉进虚空", () => {
    installDom();
    const unknown: TerrainProbe = {
      topSurfaceY: () => null,
      groundBelow: () => null,
      castRay: () => null,
    };
    const camera = cameraAt(0, 120, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: unknown });

    step(controls, 3);
    expect(camera.position.y).toBeCloseTo(120, 5);
  });

  it("切进水里落在水面上，然后缓慢下沉到水底（沉水）", () => {
    installDom();
    const terrain = new FakeTerrain(60); // 水底 60
    terrain.waterLevel = 66;
    const camera = cameraAt(0, 200, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });

    // 水面比水底高：落点是水面，而不是直接掉到水底
    expect(camera.position.y).toBeCloseTo(66 + EYE, 5);
    step(controls, 0.5);
    const half = camera.position.y - EYE;
    expect(half).toBeLessThan(66); // 已经开始下沉
    expect(half).toBeGreaterThan(64); // 但沉得很慢（终端 3 格/秒）
    step(controls, 3);
    expect(camera.position.y).toBeCloseTo(60 + EYE, 5); // 最终沉到水底站稳
  });

  it("水里按住 Space 上浮，并能在水面借力窜出水面（游泳）", () => {
    const dom = installDom();
    const terrain = new FakeTerrain(60);
    terrain.waterLevel = 66;
    const camera = cameraAt(0, 200, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });
    step(controls, 4); // 先沉到水底
    expect(camera.position.y).toBeCloseTo(60 + EYE, 5);

    press(dom, "Space");
    step(controls, 2.5);
    // 上浮速度 3.2 格/秒：2.5 秒足以浮出水面（并不会一直贴在 66）
    expect(camera.position.y - EYE).toBeGreaterThan(66);
    release(dom, "Space");
    step(controls, 4);
    expect(camera.position.y).toBeCloseTo(60 + EYE, 5); // 松手后重新缓慢沉回水底
  });

  it("水面上方的陆地/桥上不会被判定成在水里", () => {
    installDom();
    const terrain = new FakeTerrain(70); // 地面 70 高于水面 66
    terrain.waterLevel = 66;
    const camera = cameraAt(0, 200, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: terrain });
    step(controls, 2);
    // 站在陆地上（不是水面）
    expect(camera.position.y).toBeCloseTo(70 + EYE, 5);
  });

  it("跳跃后落回地面", () => {
    const dom = installDom();
    const camera = cameraAt(0, GROUND + EYE, 0);
    const controls = new FirstPersonControls(camera, canvas, { probe: new FakeTerrain(GROUND) });

    press(dom, "Space");
    step(controls, 0.3);
    expect(camera.position.y).toBeGreaterThan(GROUND + EYE);
    release(dom, "Space");
    step(controls, 2);
    expect(camera.position.y).toBeCloseTo(GROUND + EYE, 5);
  });

  it("水平范围限制把玩家收在地图内", () => {
    const dom = installDom();
    const camera = cameraAt(0, GROUND + EYE, 0);
    const controls = new FirstPersonControls(camera, canvas, {
      probe: new FakeTerrain(GROUND),
      clampXZ: (p) => {
        p.z = THREE.MathUtils.clamp(p.z, -5, 5);
      },
    });

    press(dom, "KeyW");
    step(controls, 10);
    expect(camera.position.z).toBe(-5);
  });
});
