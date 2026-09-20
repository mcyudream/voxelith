import { describe, expect, it } from "vitest";
import { parseArgs } from "./cli.js";

/**
 * CLI 参数解析：`--no-*` 是布尔开关，其余都要取值；缺值要给出可读报错。
 * 这块以前只有手工冒烟，命令写错时用户看到的是「未知参数」之类的模糊提示。
 */
describe("forge CLI 参数解析", () => {
  it("解析命令、键值对与布尔开关", () => {
    const args = parseArgs([
      "pack",
      "--map-dir", "./data/maps/swust",
      "--out", "swust.vxtbundle",
      "--no-verify",
      "--no-assets",
    ]);
    expect(args.command).toBe("pack");
    expect(args.values.get("map-dir")).toBe("./data/maps/swust");
    expect(args.values.get("out")).toBe("swust.vxtbundle");
    expect(args.flags.has("no-verify")).toBe(true);
    expect(args.flags.has("no-assets")).toBe(true);
  });

  it("缺值 / 未知位置参数 / 缺命令都报错", () => {
    expect(() => parseArgs(["audit", "--map-dir"])).toThrow(/缺少取值/);
    expect(() => parseArgs(["audit", "positional"])).toThrow(/未知参数/);
    expect(() => parseArgs([])).toThrow(/缺少命令/);
    expect(() => parseArgs(["--help"])).toThrow(/缺少命令/);
  });

  it("tileset 的锚点与比例参数按字符串透传（数值转换在命令里做）", () => {
    const args = parseArgs([
      "tileset",
      "--map-dir", "m",
      "--out", "tileset.json",
      "--lon", "104.06",
      "--lat", "30.67",
      "--meters-per-block", "2",
    ]);
    expect(Object.fromEntries(args.values)).toEqual({
      "map-dir": "m",
      out: "tileset.json",
      lon: "104.06",
      lat: "30.67",
      "meters-per-block": "2",
    });
    expect(args.flags.size).toBe(0);
  });
});
