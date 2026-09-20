/**
 * Node 侧便捷封装：直接对着**发布目录**做审计 / 导出 / 打包。
 *
 * 纯函数部分（`./index`）只依赖注入的读取器，所以能在浏览器/Worker 里跑；
 * 这一层才引入 `node:fs`，给 CLI 与本地脚本用。
 *
 * 路径安全：所有相对路径都会先解到 `mapDir` 之内（拒绝 `../`），
 * 因为清单里的 `url` 理论上可能被改过，而审计工具经常对着来路不明的产物跑。
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { parseManifest, type MapManifest } from "@yudream/voxelith-core";
import { toTileset, type TilesetOptions } from "@yudream/voxelith-tiles";
import { auditManifest, type AuditOptions, type AuditReport, type AuditStats } from "./audit.js";
import { packVxtBundle, verifyBundle, type BundleAsset, type BundleSource } from "./bundle.js";
import { inspectGlb } from "./glb.js";

export interface LoadedMap {
  dir: string;
  manifest: MapManifest;
  /** 读地图内的相对路径；不存在返回 null；越界抛错。 */
  readFile(relativeUrl: string): Uint8Array | null;
  /** 目录内所有文件的相对路径（正斜杠）。 */
  listFiles(): string[];
}

/** 读取 `{mapDir}/manifest.json` 并校验协议（zod 失败直接抛错）。 */
export function loadMapDir(mapDir: string): LoadedMap {
  const root = resolve(mapDir);
  const manifestPath = join(root, "manifest.json");
  if (!existsSync(manifestPath)) {
    throw new Error(`找不到清单: ${manifestPath}`);
  }
  const manifest = parseManifest(JSON.parse(readFileSync(manifestPath, "utf8")) as unknown);
  return {
    dir: root,
    manifest,
    readFile: (relativeUrl) => readMapFile(root, relativeUrl),
    listFiles: () => listMapFiles(root),
  };
}

/** 审计一张已发布的图（默认校验 sha1、图集尺寸，并抽样 5 片 glb）。 */
export function auditMapDir(
  mapDir: string,
  options: Omit<AuditOptions, "readFile"> = {},
): AuditReport {
  const loaded = loadMapDir(mapDir);
  return auditManifest(loaded.manifest, {
    glbSampleSize: 5,
    ...options,
    expectedMapId: options.expectedMapId ?? basename(loaded.dir),
    readFile: loaded.readFile,
  });
}

/** 导出 OGC 3D Tiles 1.1 `tileset.json`。 */
export function exportTileset(mapDir: string, outFile: string, options: TilesetOptions = {}): {
  tiles: number;
  outFile: string;
} {
  const loaded = loadMapDir(mapDir);
  const tileset = toTileset(loaded.manifest, options);
  writeFileSync(outFile, `${JSON.stringify(tileset, null, 2)}\n`, "utf8");
  return { tiles: loaded.manifest.tiles.length, outFile };
}

/**
 * 把整张图打成 `.vxtbundle`：读出每片 glb → 逐片 `.vxt` → 外层索引，
 * 并把**清单、图集、LOD 图集页**一起塞进 assets——这样整包才是自包含的
 * （只有瓦片的包落地后渲染不出贴图）。
 *
 * @param verify 打包后立刻整包自检（默认 true；大图会多读一遍，但值得）
 */
export function packMapDir(
  mapDir: string,
  outFile: string,
  options: { mapId?: string; verify?: boolean; withAssets?: boolean } = {},
): { tiles: number; assets: number; bytes: number; verified: boolean } {
  const loaded = loadMapDir(mapDir);
  const sources: BundleSource[] = [];
  for (const tile of loaded.manifest.tiles) {
    const glb = loaded.readFile(tile.url);
    if (!glb) {
      throw new Error(`打包失败：瓦片缺失 ${tile.url}`);
    }
    sources.push({ tile, glb });
  }
  const mapId = options.mapId ?? loaded.manifest.mapId;
  const assets = (options.withAssets ?? true) ? collectAssets(loaded) : [];
  const bytes = packVxtBundle(mapId, sources, {
    quantized: true,
    lod: false,
  }, new Date().toISOString(), assets);
  if (options.verify ?? true) {
    const verified = verifyBundle(bytes);
    if (!verified.ok) {
      throw new Error(`打包后自检失败：${verified.errors.join("；")}`);
    }
  }
  writeFileSync(outFile, bytes);
  return {
    tiles: sources.length,
    assets: assets.length,
    bytes: bytes.length,
    verified: options.verify ?? true,
  };
}

/**
 * 收集要打进包里的旁路文件：清单 + 主图集 + 图集布局 + 每层 LOD 图集页。
 * 缺哪个就跳过哪个（增量发布过的图可能没有布局文件），不因此让打包失败。
 */
function collectAssets(loaded: LoadedMap): BundleAsset[] {
  const keys = new Set<string>(["manifest.json", "atlas.png", "atlas-layout.json"]);
  keys.add(loaded.manifest.atlas.url);
  for (const page of loaded.manifest.lodAtlases) {
    keys.add(page.url);
  }
  const assets: BundleAsset[] = [];
  for (const key of keys) {
    const bytes = loaded.readFile(key);
    if (bytes) {
      assets.push({ key, bytes });
    }
  }
  return assets;
}

/** 统计：瓦片数、层级、字节数、扩展使用情况（抽样 glb 头部）。 */
export function mapStats(mapDir: string): AuditStats {
  const loaded = loadMapDir(mapDir);
  const report = auditManifest(loaded.manifest, {
    readFile: loaded.readFile,
    verifySha1: false,
    checkAtlasSize: false,
    glbSampleSize: loaded.manifest.tiles.length > 200 ? 50 : loaded.manifest.tiles.length,
  });
  return report.stats;
}

/** 抽样查看瓦片用了哪些扩展（排障：确认 meshopt/量化是否真的生效）。 */
export function tileExtensionSummary(
  mapDir: string,
  sampleSize = 20,
): { meshopt: number; quantized: number; embeddedImage: number; sampled: number; mismatchedLength: number } {
  const loaded = loadMapDir(mapDir);
  const tiles = loaded.manifest.tiles.slice(0, sampleSize);
  let meshopt = 0;
  let quantized = 0;
  let embeddedImage = 0;
  let mismatchedLength = 0;
  for (const tile of tiles) {
    const bytes = loaded.readFile(tile.url);
    if (!bytes) {
      continue;
    }
    const info = inspectGlb(bytes);
    if (info.hasMeshopt) {
      meshopt++;
    }
    if (info.hasQuantized) {
      quantized++;
    }
    if (info.hasEmbeddedImage) {
      embeddedImage++;
    }
    if (!info.lengthOk) {
      mismatchedLength++;
    }
  }
  return { meshopt, quantized, embeddedImage, sampled: tiles.length, mismatchedLength };
}

function readMapFile(root: string, relativeUrl: string): Uint8Array | null {
  const target = safeJoin(root, relativeUrl);
  if (!existsSync(target) || !statSync(target).isFile()) {
    return null;
  }
  return new Uint8Array(readFileSync(target));
}

function listMapFiles(root: string): string[] {
  const files: string[] = [];
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else {
        files.push(relative(root, full).split(sep).join("/"));
      }
    }
  };
  if (existsSync(root)) {
    walk(root);
  }
  return files.sort();
}

function safeJoin(root: string, relativeUrl: string): string {
  const target = resolve(root, relativeUrl);
  if (target !== root && !target.startsWith(root + sep)) {
    throw new Error(`清单里的路径越界（拒绝读取）: ${relativeUrl}`);
  }
  return target;
}

function basename(dir: string): string {
  const parts = dir.split(sep).filter(Boolean);
  return parts[parts.length - 1] ?? dir;
}

/** 供脚本使用：把审计结论写成文件（CI 里归档用）。 */
export function writeAuditReport(report: AuditReport, outFile: string): void {
  writeJsonFile(report, outFile);
}

/** 把任意结果写成格式化 JSON 文件（CLI 的 `--json` 用）。 */
export function writeJsonFile(value: unknown, outFile: string): void {
  mkdirSync(dirname(resolve(outFile)), { recursive: true });
  writeFileSync(outFile, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}
