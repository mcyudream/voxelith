#!/usr/bin/env node
/**
 * `voxelith-forge` 命令行：对已发布的地图目录做审计、导出与打包。
 *
 * 用法（先 `pnpm -r build`，产物在 `dist/`）：
 *
 * ```
 * voxelith-forge audit   --map-dir ./data/maps/swust [--no-sha1] [--json out.json]
 * voxelith-forge stats   --map-dir ./data/maps/swust [--sample 50]
 * voxelith-forge tileset --map-dir ./data/maps/swust --out tileset.json \
 *                        [--lon 104.06 --lat 30.67] [--meters-per-block 1]
 * voxelith-forge pack    --map-dir ./data/maps/swust --out swust.vxtbundle [--no-verify]
 * voxelith-forge ext     --map-dir ./data/maps/swust [--sample 20]
 * ```
 *
 * 退出码：0 = 成功；1 = 审计发现 error（或命令执行失败）；2 = 参数错误。
 */
import { auditMapDir, exportTileset, loadMapDir, mapStats, packMapDir, tileExtensionSummary, writeAuditReport } from "./node.js";
import { formatAuditReport } from "./audit.js";
import { pathToFileURL } from "node:url";

interface ParsedArgs {
  command: string;
  values: Map<string, string>;
  flags: Set<string>;
}

const USAGE = `voxelith-forge —— VMC 产物工具链

命令：
  audit    审计地图目录（清单/瓦片/图集/LOD 图集页/sha1 一致性）
  stats    打印统计（瓦片数、层级、体积、扩展使用）
  tileset  导出 OGC 3D Tiles 1.1 tileset.json
  pack     打包成单个 .vxtbundle（离线分发用）
  ext      抽样查看瓦片用了哪些 glTF 扩展

通用参数：
  --map-dir <dir>        地图目录（含 manifest.json），必填
  --json <file>          把结果写成 JSON（audit/stats/ext）
  --sample <n>           抽样片数（stats/ext，默认 50/20）
  --no-sha1              跳过 sha1 校验（大图快速体检）
  --out <file>           tileset / pack 的输出路径，必填
  --lon/--lat <deg>      3D Tiles 的经纬锚点（默认 0,0）
  --meters-per-block <n> 1 方块 = 多少米（默认 1）
  --no-verify            pack 后不做整包自检
`;

export function main(argv: readonly string[]): number {
  let args: ParsedArgs;
  try {
    args = parseArgs(argv);
  } catch (error) {
    console.error(`参数错误: ${(error as Error).message}\n`);
    console.error(USAGE);
    return 2;
  }
  const mapDir = args.values.get("map-dir");
  if (!mapDir) {
    console.error("缺少 --map-dir\n");
    console.error(USAGE);
    return 2;
  }

  try {
    switch (args.command) {
      case "audit": {
        const report = auditMapDir(mapDir, {
          verifySha1: !args.flags.has("no-sha1"),
          checkAtlasSize: true,
          glbSampleSize: Number(args.values.get("sample") ?? 5),
        });
        console.log(formatAuditReport(report));
        const jsonOut = args.values.get("json");
        if (jsonOut) {
          writeAuditReport(report, jsonOut);
          console.log(`报告已写入 ${jsonOut}`);
        }
        return report.ok ? 0 : 1;
      }
      case "stats": {
        const stats = mapStats(mapDir);
        const summary = {
          ...stats,
          totalMb: Number((stats.totalBytes / 1024 / 1024).toFixed(2)),
        };
        console.log(JSON.stringify(summary, null, 2));
        writeJsonIfRequested(args, summary);
        return 0;
      }
      case "tileset": {
        const out = args.values.get("out");
        if (!out) {
          console.error("缺少 --out");
          return 2;
        }
        const result = exportTileset(mapDir, out, {
          anchor: {
            lonDeg: Number(args.values.get("lon") ?? 0),
            latDeg: Number(args.values.get("lat") ?? 0),
          },
          metersPerBlock: Number(args.values.get("meters-per-block") ?? 1),
        });
        console.log(`已导出 ${result.tiles} 片瓦片的 3D Tiles：${result.outFile}`);
        return 0;
      }
      case "pack": {
        const out = args.values.get("out");
        if (!out) {
          console.error("缺少 --out");
          return 2;
        }
        const result = packMapDir(mapDir, out, { verify: !args.flags.has("no-verify") });
        console.log(
          `已打包 ${result.tiles} 片瓦片 → ${out}`
          + `（${(result.bytes / 1024 / 1024).toFixed(1)} MB）`
          + (result.verified ? "，自检通过" : ""),
        );
        return 0;
      }
      case "ext": {
        const loaded = loadMapDir(mapDir);
        const summary = tileExtensionSummary(mapDir, Number(args.values.get("sample") ?? 20));
        console.log(
          `地图 ${loaded.manifest.mapId}：抽样 ${summary.sampled} 片 →`
          + ` meshopt ${summary.meshopt}，量化 ${summary.quantized}，内嵌图集 ${summary.embeddedImage}`
          + `，长度不符 ${summary.mismatchedLength}`,
        );
        writeJsonIfRequested(args, summary);
        return summary.mismatchedLength === 0 ? 0 : 1;
      }
      default: {
        console.error(`未知命令: ${args.command}\n`);
        console.error(USAGE);
        return 2;
      }
    }
  } catch (error) {
    console.error(`执行失败: ${(error as Error).message}`);
    return 1;
  }
}

function writeJsonIfRequested(args: ParsedArgs, value: unknown): void {
  const out = args.values.get("json");
  if (!out) {
    return;
  }
  writeAuditReport(value as never, out);
  console.log(`结果已写入 ${out}`);
}

export function parseArgs(argv: readonly string[]): ParsedArgs {
  const [command, ...rest] = argv;
  if (!command || command === "--help" || command === "-h") {
    throw new Error("缺少命令");
  }
  const values = new Map<string, string>();
  const flags = new Set<string>();
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i]!;
    if (!arg.startsWith("--")) {
      throw new Error(`未知参数: ${arg}`);
    }
    const name = arg.slice(2);
    if (name.startsWith("no-")) {
      flags.add(name);
      continue;
    }
    const value = rest[i + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`参数 ${arg} 缺少取值`);
    }
    values.set(name, value);
    i++;
  }
  return { command, values, flags };
}

// 直接 `node dist/cli.js ...` 时执行；被测试 import 时不自执行
// （pathToFileURL 处理 Windows 盘符与转义，别手拼 file:// 字符串）
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(main(process.argv.slice(2)));
}
