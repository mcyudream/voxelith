/**
 * 产物审计：把「清单说的」和「盘上真有的」对一遍。
 *
 * 为什么值得单独做：渲染管线的产物是**跨进程、跨机器**生成又消费的——
 * 后端 bake→tile 之后可能被拷贝、被对象存储归档、被增量重跑覆盖过一部分。
 * 出问题时前端只会表现成「某块地图像素错位/贴图是品红/瓦片 404」，
 * 很难从现象反推是哪一环坏了。审计把这些检查一次性做完，并给出可执行的结论。
 *
 * 检查项（error = 一定会渲染错；warning = 可能有问题或只是信息）：
 * - 瓦片：url 唯一、sha1 形状、文件存在、字节数一致、sha1 一致（可选）；
 * - glb：magic/长度自洽、扩展声明（meshopt/quantization）是否与清单一致；
 * - 图集：文件存在、PNG 尺寸与清单 `size`/`height` 一致；
 * - LOD 图集页：文件存在、页尺寸 = 该层网格 × slotSize（打包器的核心约定）；
 * - 层级：hires（level 0）不能为空，level 不得超过 lodCount-1。
 *
 * 纯函数 + 注入的文件读取器：既能跑在 Node（`./node` 提供 fs 版本），
 * 也能跑在浏览器里对着 fetch 到的产物做自检。
 */
import type { MapManifest, ManifestTile } from "@yudream/voxelith-core";
import { sha1Hex } from "@yudream/voxelith-tiles";
import { inspectGlb, type GlbInfo } from "./glb.js";
import { inspectPng } from "./png.js";

export type AuditSeverity = "error" | "warning";

export interface AuditIssue {
  severity: AuditSeverity;
  code: string;
  message: string;
  url?: string;
}

export interface AuditStats {
  tiles: number;
  hiresTiles: number;
  lodTiles: number;
  levels: number;
  totalBytes: number;
  /**
   * 以下三项来自 glb 头部**抽样**（`glbSampleSize`），不是全量统计：
   * 解析 glb 要读文件头，全量读几万片纯属浪费；抽样足以判断「这一版瓦片到底压没压」。
   */
  sampledWithEmbeddedImage: number;
  sampledMeshopt: number;
  sampledQuantized: number;
}

export interface AuditReport {
  ok: boolean;
  issues: AuditIssue[];
  stats: AuditStats;
}

export interface AuditOptions {
  /**
   * 相对地图根读取文件（如 `tiles/hires/0/0.glb`）；返回 null = 不存在。
   * 不传时只做「不需要读盘」的检查（url/sha1 形状、层级、页尺寸推算等）。
   */
  readFile?: (relativeUrl: string) => Uint8Array | null;
  /** 校验每个瓦片的 sha1 与字节数（默认 true；要求 readFile）。 */
  verifySha1?: boolean;
  /** 校验图集 PNG 尺寸（默认 true；要求 readFile）。 */
  checkAtlasSize?: boolean;
  /** 抽样解析前 N 个瓦片的 glb 头部（默认 0 = 不解析；CLI 默认用 5）。 */
  glbSampleSize?: number;
  /** 期望的地图 id（通常取目录名）：与清单不一致时告警。 */
  expectedMapId?: string;
}

export function auditManifest(manifest: MapManifest, options: AuditOptions = {}): AuditReport {
  const issues: AuditIssue[] = [];
  const verifySha1 = options.verifySha1 ?? true;
  const checkAtlasSize = options.checkAtlasSize ?? true;
  const readFile = options.readFile;

  const stats: AuditStats = {
    tiles: manifest.tiles.length,
    hiresTiles: 0,
    lodTiles: 0,
    levels: 0,
    totalBytes: 0,
    sampledWithEmbeddedImage: 0,
    sampledMeshopt: 0,
    sampledQuantized: 0,
  };

  if (options.expectedMapId && options.expectedMapId !== manifest.mapId) {
    issues.push({
      severity: "warning",
      code: "map-id-mismatch",
      message: `清单 mapId=${manifest.mapId} 与目录名 ${options.expectedMapId} 不一致`,
    });
  }
  if (manifest.tiles.length === 0) {
    issues.push({ severity: "error", code: "no-tiles", message: "清单里没有任何瓦片" });
    return { ok: false, issues, stats };
  }

  const seenUrls = new Set<string>();
  const levels = new Set<number>();
  for (const tile of manifest.tiles) {
    levels.add(tile.level);
    if (tile.level === 0) {
      stats.hiresTiles++;
    } else {
      stats.lodTiles++;
    }
    stats.totalBytes += tile.bytes;

    if (seenUrls.has(tile.url)) {
      issues.push({
        severity: "error",
        code: "duplicate-url",
        message: `瓦片 url 重复: ${tile.url}`,
        url: tile.url,
      });
    }
    seenUrls.add(tile.url);

    if (!/^[0-9a-f]{40}$/.test(tile.sha1)) {
      issues.push({
        severity: "error",
        code: "bad-sha1",
        message: `sha1 不是 40 位十六进制: ${tile.sha1}`,
        url: tile.url,
      });
    }
    if (tile.level > manifest.settings.lodCount - 1) {
      issues.push({
        severity: "error",
        code: "level-exceeds-lod-count",
        message: `瓦片层级 ${tile.level} 超过清单声明的 lodCount=${manifest.settings.lodCount}`,
        url: tile.url,
      });
    }
    const size = manifest.settings.hiresTileSize * 2 ** tile.level;
    const expectedSpan = size;
    const spanX = Math.abs(tile.max[0] - tile.min[0]);
    const spanZ = Math.abs(tile.max[2] - tile.min[2]);
    if (spanX > expectedSpan + 0.01 || spanZ > expectedSpan + 0.01) {
      issues.push({
        severity: "warning",
        code: "tile-bounds-too-large",
        message: `瓦片包围盒 ${spanX}×${spanZ} 超过该层边长 ${expectedSpan}`,
        url: tile.url,
      });
    }
  }
  stats.levels = levels.size;

  if (stats.hiresTiles === 0) {
    issues.push({
      severity: "error",
      code: "no-hires-tiles",
      message: "没有任何 hires（level 0）瓦片：前端进场后看不到近景",
    });
  }

  if (readFile) {
    for (const tile of manifest.tiles) {
      const bytes = readFile(tile.url);
      if (!bytes) {
        issues.push({
          severity: "error",
          code: "tile-missing",
          message: "瓦片文件不存在",
          url: tile.url,
        });
        continue;
      }
      if (bytes.length !== tile.bytes) {
        issues.push({
          severity: "error",
          code: "tile-size-mismatch",
          message: `字节数不一致: 清单 ${tile.bytes} / 实际 ${bytes.length}`,
          url: tile.url,
        });
      }
      if (verifySha1) {
        const actual = sha1Hex(bytes);
        if (actual !== tile.sha1) {
          issues.push({
            severity: "error",
            code: "tile-sha1-mismatch",
            message: `sha1 不一致: 清单 ${tile.sha1} / 实际 ${actual}（前端缓存会失效或渲染旧内容）`,
            url: tile.url,
          });
        }
      }
    }

    const sampleSize = Math.max(0, options.glbSampleSize ?? 0);
    if (sampleSize > 0) {
      for (const tile of sampleTiles(manifest.tiles, sampleSize)) {
        const bytes = readFile(tile.url);
        if (!bytes) {
          continue;
        }
        let info: GlbInfo | null = null;
        try {
          info = inspectGlb(bytes);
        } catch (error) {
          issues.push({
            severity: "error",
            code: "glb-unreadable",
            message: `glb 头部无法解析: ${String(error)}`,
            url: tile.url,
          });
        }
        if (!info) {
          continue;
        }
        if (!info.magicOk) {
          issues.push({
            severity: "error",
            code: "glb-bad-magic",
            message: "不是 glb（magic 不符）",
            url: tile.url,
          });
        }
        if (!info.lengthOk) {
          issues.push({
            severity: "error",
            code: "glb-length-mismatch",
            message: `glb 声明长度 ${info.declaredLength} 与实际 ${info.actualLength} 不一致（文件被截断/拼接）`,
            url: tile.url,
          });
        }
        if (info.hasEmbeddedImage) {
          stats.sampledWithEmbeddedImage++;
        }
        if (info.hasMeshopt) {
          stats.sampledMeshopt++;
        }
        if (info.hasQuantized) {
          stats.sampledQuantized++;
        }
        if (tile.level > 0 && info.hasEmbeddedImage === false
          && !manifest.lodAtlases.some((page) => page.level === tile.level)) {
          issues.push({
            severity: "warning",
            code: "lod-tile-without-texture",
            message: "LOD 瓦片既没内嵌色图、清单也没有该层图集页：这层会只剩方向明暗",
            url: tile.url,
          });
        }
      }
    }

    const atlasBytes = readFile(manifest.atlas.url);
    if (!atlasBytes) {
      issues.push({
        severity: "error",
        code: "atlas-missing",
        message: "图集文件不存在",
        url: manifest.atlas.url,
      });
    } else if (checkAtlasSize) {
      try {
        const png = inspectPng(atlasBytes);
        if (png.width !== manifest.atlas.size
          || png.height !== (manifest.atlas.height ?? manifest.atlas.size)) {
          issues.push({
            severity: "error",
            code: "atlas-size-mismatch",
            message: `图集尺寸不一致: 清单 ${manifest.atlas.size}×${manifest.atlas.height ?? manifest.atlas.size}`
              + ` / 实际 ${png.width}×${png.height}（UV 会整体错位）`,
            url: manifest.atlas.url,
          });
        }
      } catch (error) {
        issues.push({
          severity: "error",
          code: "atlas-unreadable",
          message: `图集不是合法 PNG: ${String(error)}`,
          url: manifest.atlas.url,
        });
      }
    }

    for (const page of manifest.lodAtlases) {
      const bytes = readFile(page.url);
      if (!bytes) {
        issues.push({
          severity: "error",
          code: "lod-atlas-missing",
          message: `L${page.level} 图集页不存在`,
          url: page.url,
        });
        continue;
      }
      if (!checkAtlasSize) {
        continue;
      }
      const bounds = levelBounds(manifest, page.level);
      if (!bounds) {
        issues.push({
          severity: "warning",
          code: "lod-atlas-without-tiles",
          message: `清单声明了 L${page.level} 图集页，但没有该层瓦片`,
          url: page.url,
        });
        continue;
      }
      try {
        const png = inspectPng(bytes);
        const expectedWidth = bounds.gridWidth * page.slotSize;
        const expectedHeight = bounds.gridHeight * page.slotSize;
        if (png.width !== expectedWidth || png.height !== expectedHeight) {
          issues.push({
            severity: "error",
            code: "lod-atlas-size-mismatch",
            message: `L${page.level} 图集页尺寸 ${png.width}×${png.height} 与`
              + ` 网格 ${bounds.gridWidth}×${bounds.gridHeight} × slot ${page.slotSize}`
              + ` = ${expectedWidth}×${expectedHeight} 不一致（该层 UV 会错位）`,
            url: page.url,
          });
        }
      } catch (error) {
        issues.push({
          severity: "error",
          code: "lod-atlas-unreadable",
          message: `L${page.level} 图集页不是合法 PNG: ${String(error)}`,
          url: page.url,
        });
      }
    }
  }

  return { ok: !issues.some((issue) => issue.severity === "error"), issues, stats };
}

/** 均匀抽样：首片、末片与中间若干，避免只看到同一批区块。 */
function sampleTiles(tiles: readonly ManifestTile[], size: number): ManifestTile[] {
  if (tiles.length <= size) {
    return [...tiles];
  }
  const step = (tiles.length - 1) / (size - 1 || 1);
  const picked: ManifestTile[] = [];
  for (let i = 0; i < size; i++) {
    picked.push(tiles[Math.round(i * step)]!);
  }
  return picked;
}

/** 某层瓦片的网格范围（与后端 LodAtlasPacker 的槽位定位一致）。 */
function levelBounds(
  manifest: MapManifest,
  level: number,
): { gridWidth: number; gridHeight: number } | null {
  let minX = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let minZ = Number.POSITIVE_INFINITY;
  let maxZ = Number.NEGATIVE_INFINITY;
  for (const tile of manifest.tiles) {
    if (tile.level !== level) {
      continue;
    }
    minX = Math.min(minX, tile.x);
    maxX = Math.max(maxX, tile.x);
    minZ = Math.min(minZ, tile.z);
    maxZ = Math.max(maxZ, tile.z);
  }
  if (!Number.isFinite(minX)) {
    return null;
  }
  return { gridWidth: maxX - minX + 1, gridHeight: maxZ - minZ + 1 };
}

/** 人类可读的一行结论（CLI 与日志用）。 */
export function formatAuditReport(report: AuditReport): string {
  const errors = report.issues.filter((issue) => issue.severity === "error");
  const warnings = report.issues.filter((issue) => issue.severity === "warning");
  const lines = [
    `${report.ok ? "✅ 审计通过" : "❌ 审计失败"}：瓦片 ${report.stats.tiles}`
      + `（hires ${report.stats.hiresTiles} / lod ${report.stats.lodTiles}，${report.stats.levels} 层），`
      + `合计 ${(report.stats.totalBytes / 1024 / 1024).toFixed(1)} MB`
      + `；错误 ${errors.length}，告警 ${warnings.length}`,
  ];
  for (const issue of [...errors, ...warnings]) {
    const icon = issue.severity === "error" ? "✖" : "⚠";
    lines.push(`  ${icon} [${issue.code}] ${issue.message}${issue.url ? `  (${issue.url})` : ""}`);
  }
  return lines.join("\n");
}
