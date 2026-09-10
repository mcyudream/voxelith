import { describe, expect, it, vi } from "vitest";
import type { TileManager } from "../tiles/TileManager.js";
import { AdaptiveDistance, decideChunks } from "./AdaptiveDistance.js";

const MIN = 8;
const MAX = 32;
const STEP = 1;

describe("decideChunks", () => {
  it("fps 低于目标 85% 立即缩 1 档", () => {
    expect(decideChunks(16, 40, 60, 0, MIN, MAX, STEP)).toEqual({ chunks: 15, goodStreak: 0 });
  });

  it("缩小不越过下限", () => {
    expect(decideChunks(8, 10, 60, 0, MIN, MAX, STEP)).toEqual({ chunks: 8, goodStreak: 0 });
  });

  it("单个达标窗口不放大，只累计连续计数", () => {
    expect(decideChunks(16, 59.5, 60, 0, MIN, MAX, STEP)).toEqual({ chunks: 16, goodStreak: 1 });
  });

  it("连续 2 个窗口达标才放大 1 档", () => {
    expect(decideChunks(16, 59.5, 60, 1, MIN, MAX, STEP)).toEqual({ chunks: 17, goodStreak: 0 });
  });

  it("放大不越过上限", () => {
    expect(decideChunks(32, 60, 60, 1, MIN, MAX, STEP)).toEqual({ chunks: 32, goodStreak: 0 });
  });

  it("中间区间保持并重置连续计数", () => {
    expect(decideChunks(16, 55, 60, 1, MIN, MAX, STEP)).toEqual({ chunks: 16, goodStreak: 0 });
  });
});

function stubTileManager() {
  const calls: number[] = [];
  const stub = {
    setDetailDistanceBlocks: vi.fn((blocks: number) => calls.push(blocks)),
  } as unknown as TileManager;
  return { stub, calls };
}

describe("AdaptiveDistance", () => {
  it("构造时把初始视距写入细节视距（区块 × 16 方块）", () => {
    const { stub, calls } = stubTileManager();
    new AdaptiveDistance({ tileManager: stub, initialChunks: 20 });
    expect(calls).toEqual([320]);
  });

  it("初始视距夹在 [min, max]", () => {
    const { stub, calls } = stubTileManager();
    new AdaptiveDistance({ tileManager: stub, initialChunks: 100 });
    expect(calls).toEqual([MAX * 16]);
  });

  it("setChunks 后逐帧平滑插值并回调整数变化", () => {
    const { stub, calls } = stubTileManager();
    const reported: number[] = [];
    const a = new AdaptiveDistance({
      tileManager: stub,
      initialChunks: 20,
      onChange: (c) => reported.push(c),
    });
    a.setEnabled(false); // 关掉自动调节，避免测试中的低 fps 窗口改目标
    a.setChunks(28);
    a.update(0.1); // 收敛速率 2.5/s：20 + 8 × 0.25 = 22
    expect(a.chunks).toBeCloseTo(22);
    expect(calls.at(-1)).toBeCloseTo(352);
    expect(reported).toEqual([22]);
    // 足够长时间后吸附到目标
    for (let i = 0; i < 60; i++) a.update(0.1);
    expect(a.chunks).toBe(28);
    expect(calls.at(-1)).toBe(448);
    expect(reported.at(-1)).toBe(28);
  });

  it("低帧率窗口触发缩小（自动模式）", () => {
    const { stub } = stubTileManager();
    // windowMs=50，单帧 50ms → fps ≈ 20 < 60 × 0.85 → 缩 1 档
    const a = new AdaptiveDistance({ tileManager: stub, initialChunks: 16, windowMs: 50 });
    for (let i = 0; i < 200; i++) a.update(0.05);
    expect(a.chunks).toBeLessThan(16);
  });

  it("关闭自动调节后视距不随帧率变化", () => {
    const { stub } = stubTileManager();
    const a = new AdaptiveDistance({ tileManager: stub, initialChunks: 16, windowMs: 50 });
    a.setEnabled(false);
    for (let i = 0; i < 200; i++) a.update(0.05);
    expect(a.chunks).toBe(16);
  });
});
