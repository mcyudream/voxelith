import { describe, expect, it } from "vitest";
import { dominantLevel, hazeBand, levelForFarDistance, planSkyline, skylineBands } from "./policy.js";

const base = {
  hiresTileSize: 32,
  maxLevel: 3,
  detailDistance: 256,
  farDistance: 4096,
};

describe("天际线层带策略", () => {
  it("层带按 2 的幂展开，far 被 farDistance 夹住", () => {
    const bands = skylineBands(base);
    expect(bands.map((band) => band.far)).toEqual([512, 1024, 2048, 4096]);
    expect(bands.map((band) => band.sizeBlocks)).toEqual([32, 64, 128, 256]);
    // 重叠：下一层的 near 在上一层 far 之内
    expect(bands[1]!.near).toBeLessThan(bands[0]!.far);
    expect(bands[1]!.near).toBeCloseTo(512 * 0.75, 6);

    const clamped = skylineBands({ ...base, farDistance: 1000 });
    expect(clamped.map((band) => band.far)).toEqual([512, 1000, 1000, 1000]);
  });

  it("距离决定哪几层 active；最粗的 active 层就是当前主层", () => {
    // 近处：只有 hires 层（level 0）在带宽内
    const near = planSkyline(base, 100);
    expect(near[0]!.active).toBe(true);
    expect(near[3]!.active).toBe(false);
    expect(dominantLevel(near)).toBe(0);

    // 中距离 1200：level 1 的带是 [384,1024]、level 2 是 [768,2048]，
    // 1200 落在 level 2 的带里（level 3 的带从 1536×0.85 才算进入）
    const mid = planSkyline(base, 1200);
    expect(mid.map((band) => band.active)).toEqual([false, false, true, false]);
    expect(dominantLevel(mid)).toBe(2);

    // 极远：只有最粗层
    const far = planSkyline(base, 4000);
    expect(far.map((band) => band.active)).toEqual([false, false, false, true]);
    expect(dominantLevel(far)).toBe(3);
  });

  it("滞回：已经启用的层要跨过 far×(1+滞回) 才关闭，避免边界抖动", () => {
    const first = planSkyline(base, 2048);      // level 2 far=2048，正好压线
    expect(first[2]!.active).toBe(true);

    // 略微越过阈值（+5%）：滞回 15% 内，仍然保持
    const stillOn = planSkyline(base, 2048 * 1.05, first);
    expect(stillOn[2]!.active).toBe(true);

    // 越过滞回上限（+20%）：关闭
    const off = planSkyline(base, 2048 * 1.2, first);
    expect(off[2]!.active).toBe(false);

    // 同一距离、没有上一帧状态时按纯阈值判定（不保持）
    expect(planSkyline(base, 2048 * 1.2)[2]!.active).toBe(false);
  });

  it("雾带从 detailDistance 起、到 farDistance 止（至少 1 方块宽）", () => {
    expect(hazeBand(base)).toEqual({ near: 256, far: 4096 });
    expect(hazeBand({ ...base, detailDistance: 0, farDistance: 0 })).toEqual({ near: 1, far: 2 });
  });

  it("地平线选层：让远景带约 N 片铺满，并夹在 [0, maxLevel] 内", () => {
    // 32 × 8 = 256 → far 512 时目标边长 64 → level 1
    expect(levelForFarDistance(32, 512, 8, 8)).toBe(1);
    // far 4096 → 目标边长 512 → level 4
    expect(levelForFarDistance(32, 4096, 8, 8)).toBe(4);
    // 层级不够时夹到 maxLevel
    expect(levelForFarDistance(32, 4096, 2, 8)).toBe(2);
    // 远景比一片瓦片还小 → 退到 0
    expect(levelForFarDistance(32, 64, 8, 8)).toBe(0);
    // 非法输入不抛错
    expect(levelForFarDistance(0, 100, 4)).toBe(0);
    expect(levelForFarDistance(32, 0, 4)).toBe(0);
  });
});
