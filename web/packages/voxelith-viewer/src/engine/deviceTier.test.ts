import { describe, expect, it } from "vitest";
import { computeTier, initialViewDistanceChunks, scoreGpu } from "./deviceTier.js";

describe("scoreGpu", () => {
  it("软件渲染与入门 GPU 记 0 分", () => {
    expect(scoreGpu("Google SwiftShader")).toBe(0);
    expect(scoreGpu("llvmpipe (LLVM 15.0, 256 bits)")).toBe(0);
    expect(scoreGpu("Microsoft Basic Render Driver")).toBe(0);
    expect(scoreGpu("Intel HD Graphics 620")).toBe(0);
    expect(scoreGpu("Mali-450 MP")).toBe(0);
    expect(scoreGpu("Adreno 330")).toBe(0);
    expect(scoreGpu("PowerVR SGX 540")).toBe(0);
  });

  it("高端独显与旗舰移动 GPU 记 2 分", () => {
    expect(scoreGpu("ANGLE (NVIDIA GeForce RTX 3060)")).toBe(2);
    expect(scoreGpu("AMD Radeon RX 6700 XT")).toBe(2);
    expect(scoreGpu("Apple M1")).toBe(2);
    expect(scoreGpu("Adreno 650")).toBe(2);
    expect(scoreGpu("Mali-G78")).toBe(2);
    expect(scoreGpu("Mali-G710 MC10")).toBe(2);
    expect(scoreGpu("Dimensity 9200")).toBe(2);
  });

  it("中端与未知型号记 1 分", () => {
    expect(scoreGpu("NVIDIA GeForce GTX 1060")).toBe(1);
    expect(scoreGpu("Intel Iris Xe Graphics")).toBe(1);
    expect(scoreGpu("")).toBe(1);
  });
});

describe("computeTier", () => {
  it("全缺省信号按中档兜底", () => {
    expect(computeTier({})).toBe("mid");
  });

  it("总分 ≥5 高档", () => {
    expect(
      computeTier({ cores: 16, memoryGb: 32, gpuRenderer: "NVIDIA GeForce RTX 4070" }),
    ).toBe("high");
    // 恰好 5 分（2+1+2）也是高档
    expect(computeTier({ cores: 8, memoryGb: 4, gpuRenderer: "Apple M2" })).toBe("high");
  });

  it("总分 3-4 中档", () => {
    expect(computeTier({ cores: 4, memoryGb: 4, gpuRenderer: "GTX 1060" })).toBe("mid");
    expect(computeTier({ cores: 8, memoryGb: 8, gpuRenderer: "SwiftShader" })).toBe("mid");
  });

  it("总分 <3 低档", () => {
    expect(computeTier({ cores: 2, memoryGb: 2, gpuRenderer: "SwiftShader" })).toBe("low");
    expect(computeTier({ cores: 4, memoryGb: 2, gpuRenderer: "Mali-400" })).toBe("low");
  });
});

describe("initialViewDistanceChunks", () => {
  it("高 24 / 中 16 / 低 10 区块", () => {
    expect(initialViewDistanceChunks("high")).toBe(24);
    expect(initialViewDistanceChunks("mid")).toBe(16);
    expect(initialViewDistanceChunks("low")).toBe(10);
  });
});
