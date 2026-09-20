import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { SkylineHaze } from "./haze.js";

describe("SkylineHaze 远景雾化", () => {
  it("按策略生成雾带：从细节视距到最远距离", () => {
    const haze = SkylineHaze.fromPolicy(
      { hiresTileSize: 32, maxLevel: 4, detailDistance: 256, farDistance: 4096 },
      0x87ceeb,
    );
    expect(haze.fog.near).toBe(256);
    expect(haze.fog.far).toBe(4096);
    expect(haze.fog.color.getHex()).toBe(0x87ceeb);
  });

  it("near 不能大于等于 far（否则 fog 计算会退化）", () => {
    const haze = new SkylineHaze({ color: 0x000000, near: 500, far: 100 });
    expect(haze.fog.far).toBeGreaterThan(haze.fog.near);
    haze.setBand(900, 50);
    expect(haze.fog.near).toBe(900);
    expect(haze.fog.far).toBe(901);
  });

  it("apply 挂到场景并保留原雾，dispose 还原（不污染别人的场景状态）", () => {
    const scene = new THREE.Scene();
    const original = new THREE.Fog(0x111111, 10, 20);
    scene.fog = original;

    const haze = new SkylineHaze({ color: 0x87ceeb, near: 100, far: 1000 });
    haze.apply(scene);
    expect(scene.fog).toBe(haze.fog);

    haze.dispose();
    expect(scene.fog).toBe(original);

    // 没有原雾的场景：dispose 后回到 null
    const bare = new THREE.Scene();
    const haze2 = new SkylineHaze({ color: 0x000000, near: 1, far: 2 });
    haze2.apply(bare);
    expect(bare.fog).toBe(haze2.fog);
    haze2.apply(bare); // 幂等：重复 apply 不重复记录
    haze2.dispose();
    expect(bare.fog).toBeNull();
  });

  it("重复 apply 到另一个场景时先还原上一个", () => {
    const first = new THREE.Scene();
    const second = new THREE.Scene();
    const haze = new SkylineHaze({ color: 0x000000, near: 1, far: 2 });
    haze.apply(first);
    haze.apply(second);
    expect(first.fog).toBeNull();
    expect(second.fog).toBe(haze.fog);
    haze.dispose();
    expect(second.fog).toBeNull();
  });

  it("改颜色/改带不需要重建雾对象（避免材质重编译）", () => {
    const haze = new SkylineHaze({ color: 0x111111, near: 10, far: 100 });
    const fog = haze.fog;
    haze.setColor(0x222222);
    haze.setBand(20, 200);
    expect(haze.fog).toBe(fog);
    expect(haze.fog.color.getHex()).toBe(0x222222);
    expect(haze.fog.near).toBe(20);
    expect(haze.fog.far).toBe(200);
  });
});
