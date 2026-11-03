import { describe, expect, it } from "vitest";
import { zipSync, strToU8 } from "fflate";
import { DayNightCycle, daylightFactor } from "./DayNightCycle.js";
import {
  mapPackOption,
  parseProperties,
  parseShaderPack,
  shaderPackFromConfig,
} from "./ShaderPack.js";

describe("DayNightCycle", () => {
  it("日出在东方、正午在头顶、日落在西方", () => {
    const cycle = new DayNightCycle();
    cycle.timeOfDay = 6;
    expect(cycle.sample().sunDir.y).toBeCloseTo(0, 5);
    expect(cycle.sample().sunDir.x).toBeGreaterThan(0.99);

    cycle.timeOfDay = 12;
    expect(cycle.sample().sunDir.y).toBeGreaterThan(0.99);

    cycle.timeOfDay = 18;
    expect(cycle.sample().sunDir.x).toBeLessThan(-0.99);

    cycle.timeOfDay = 0;
    expect(cycle.sample().sunDir.y).toBeLessThan(-0.99);
  });

  it("月亮与太阳对置，夜里月亮方向在地面以上", () => {
    const cycle = new DayNightCycle({ timeOfDay: 0 });
    const s = cycle.sample();
    expect(s.moonDir.y).toBeGreaterThan(0.99);
    expect(s.daylight).toBe(0);
  });

  it("昼光系数在日出日落附近平滑过渡，深夜为 0、正午为 1", () => {
    expect(daylightFactor(-0.5)).toBe(0);
    expect(daylightFactor(0.9)).toBe(1);
    // 太阳略低于地平线时已经开始过渡（民用晨昏）
    const mid = daylightFactor(0.03);
    expect(mid).toBeGreaterThan(0.05);
    expect(mid).toBeLessThan(0.95);
  });

  it("自动循环按真实时间推进并回绕到 0 点", () => {
    const cycle = new DayNightCycle({ timeOfDay: 23, hoursPerSecond: 2 });
    cycle.advance(0.75);
    expect(cycle.timeOfDay).toBeCloseTo(0.5, 5);
  });

  it("太阳轨迹倾角把日出点转出东西轴", () => {
    const cycle = new DayNightCycle({ timeOfDay: 6, sunPathRotation: 30 });
    expect(Math.abs(cycle.sample().sunDir.z)).toBeGreaterThan(0.3);
  });
});

describe("parseShaderPack", () => {
  const pack = zipSync({
    "BSL/shaders/shaders.properties": strToU8(
      [
        "# 注释行",
        "shadowDistance=220.0",
        "sunPathRotation=-25.5",
        "BLOOM_STRENGTH=0.65",
        "profile.PROFILE_LOW=<!shadowMapResolution=1024>",
        "screen=<empty>",
      ].join("\n"),
    ),
    "BSL/shaders/gbuffers_terrain.vsh": strToU8("void main() {}"),
  });

  it("读出 properties 并映射成内置管线参数", () => {
    const applied = parseShaderPack(pack, "BSL_v8.4.zip");
    expect(applied.name).toBe("BSL_v8.4");
    expect(applied.tweaks.shadowDistance).toBe(220);
    expect(applied.tweaks.sunPathRotation).toBeCloseTo(-25.5, 5);
    // 泛光混合系数按 0.5 折算成 UnrealBloom 等效强度
    expect(applied.tweaks.bloom).toBeCloseTo(0.65 * 0.5, 5);
  });

  it("profile/screen 等包私有键不算未映射，陌生键才计入", () => {
    const applied = parseShaderPack(pack, "BSL.zip");
    // 常见配置键（阴影距离/轨迹/泛光/profile/screen）都有归属，不制造噪音
    expect(applied.unmapped).toEqual([]);
    const withUnknown = zipSync({
      "shaders.properties": strToU8("shadowDistance=220\nMY_CUSTOM_TOGGLE=1"),
    });
    expect(parseShaderPack(withUnknown, "u.zip").unmapped).toEqual(["MY_CUSTOM_TOGGLE"]);
  });

  it("没有 shaders.properties 的 zip 明确报错", () => {
    const notAShaderPack = zipSync({ "pack.mcmeta": strToU8("{}") });
    expect(() => parseShaderPack(notAShaderPack, "x.zip")).toThrow(/shaders\.properties/);
  });

  it("阴影距离夹到内置管线的有效区间", () => {
    const extreme = zipSync({
      "shaders.properties": strToU8("shadowDistance=99999"),
    });
    expect(parseShaderPack(extreme, "e.zip").tweaks.shadowDistance).toBe(512);
  });
});

describe("parseProperties", () => {
  it("跳过注释与无值行，键值两侧空白归零", () => {
    const parsed = parseProperties("# c\n\n  key = value \nbroken\n!also comment");
    expect(parsed).toEqual({ key: "value" });
  });
});

describe("shaderPackFromConfig", () => {
  it("GLSL #define 提供默认值，properties 显式配置优先", () => {
    const applied = shaderPackFromConfig(
      "iterationT",
      "clouds=off\n", // properties 里没有 shadowDistance
      { shadowDistance: "220.0", SUNLIGHT_INTENSITY: "0.8", SKYLIGHT_INTENSITY: "0.9" },
    );
    expect(applied.tweaks.shadowDistance).toBe(220);
    // 强度滑杆是「相对包默认」的倍率：1.0 = 内置标定默认（0.55/0.75）
    expect(applied.tweaks.sunStrength).toBeCloseTo(0.55 * 0.8, 5);
    expect(applied.tweaks.ambientStrength).toBeCloseTo(0.75 * 0.9, 5);
  });

  it("properties 的同名键覆盖 GLSL 默认值", () => {
    const applied = shaderPackFromConfig(
      "p",
      "shadowDistance=320",
      { shadowDistance: "220.0" },
    );
    expect(applied.tweaks.shadowDistance).toBe(320);
  });

  it("非数值的 define（宏/布尔）不进映射", () => {
    const applied = shaderPackFromConfig("p", "", {
      sunPathRotation: "-25.5",
      WAVING_WATER: "(0.15 + 0.1)",
    });
    expect(applied.tweaks.sunPathRotation).toBeCloseTo(-25.5, 5);
    expect(applied.options).toEqual({});
  });

  it("选项面板只放能映射的声明，其余被过滤掉", () => {
    const applied = shaderPackFromConfig("p", "", {}, [
      { name: "shadowDistance", value: "192.0", values: ["64.0", "192.0", "384.0"] },
      { name: "shadowMapResolution", value: "2048", values: ["1024", "2048", "4096"] },
      { name: "CAVE_MODE", value: "1", values: ["0", "1"] }, // 包私有概念，映射为空
    ]);
    expect(applied.packOptions.map((o) => o.name)).toEqual(["shadowDistance", "shadowMapResolution"]);
  });
});

describe("mapPackOption", () => {
  it("单选项映射与整包应用同一张表：改一项只动对应参数", () => {
    expect(mapPackOption("shadowDistance", "384")).toEqual({ shadowDistance: 384 });
    expect(mapPackOption("shadowMapResolution", "4096")).toEqual({ shadowMapSize: 4096 });
    // 阴影精度夹到网页端合理区间（包里可能声明 32768）
    expect(mapPackOption("shadowMapResolution", "32768")).toEqual({ shadowMapSize: 4096 });
    expect(mapPackOption("SUNLIGHT_INTENSITY", "1.2")?.sunStrength).toBeCloseTo(0.55 * 1.2, 5);
    expect(mapPackOption("BLOOM_AMOUNT", "1.0")).toEqual({ bloom: 0.5 });
    expect(mapPackOption("CAVE_MODE", "1")).toEqual({});
  });
});
