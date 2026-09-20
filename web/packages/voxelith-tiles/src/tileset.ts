/**
 * OGC 3D Tiles 1.1 互操作：把 VMC 清单翻译成 `tileset.json`，也能反向读出内容列表。
 *
 * 为什么是 1.1：3D Tiles 1.1 直接支持 glTF（`.glb`）作为 tile content，
 * 不需要 b3dm 包装，所以 VMC 的瓦片可以原样供给 Cesium / three.js 的 3D Tiles 加载器。
 *
 * 坐标换算：VMC 用「方块」为单位的世界坐标（X 东、Y 上、Z 南），3D Tiles 的
 * `region` 包围体用**经纬高（弧度/米）**。两者之间需要一个锚点：
 *
 * ```
 * lon = lon0 + (x * metersPerBlock) / (111320 * cos(lat0))
 * lat = lat0 + (z * metersPerBlock) / 110540
 * ```
 *
 * 这是小范围（校园/城市级）的等距近似：几公里范围内误差远小于瓦片本身，
 * 而地图展示不需要大地水准面的严格精度。超出范围请改用 `box` 包围体（本包暂只输出 region）。
 */
import type { MapManifest, ManifestTile } from "@yudream/voxelith-core";

/** region 包围体：`[west, south, east, north, minHeight, maxHeight]`（弧度 / 米）。 */
export interface TilesetBoundingRegion {
  region: [number, number, number, number, number, number];
}

/** box 包围体：`[cx, cy, cz, xHalf, yHalf, zHalf, ...]`（ECEF，本包保留类型以兼容读取）。 */
export interface TilesetBoundingBox {
  box: number[];
}

export type TilesetBoundingVolume = TilesetBoundingRegion | TilesetBoundingBox;

export interface TilesetTile {
  boundingVolume: TilesetBoundingVolume;
  geometricError: number;
  refine?: "ADD" | "REPLACE";
  content?: { uri: string };
  children?: TilesetTile[];
  extras?: Record<string, unknown>;
}

export interface Tileset {
  asset: { version: "1.1"; generator?: string };
  geometricError: number;
  root: TilesetTile;
}

export interface TilesetOptions {
  /** 世界原点 (0,0) 对应的经纬度（度）；默认 (0, 0)，即赤道本初子午线 */
  anchor?: { lonDeg: number; latDeg: number };
  /** 锚点海拔（米）；默认 0 */
  anchorHeightMeters?: number;
  /** 1 方块 = 多少米；默认 1（1:1 比例） */
  metersPerBlock?: number;
  /**
   * 屏幕空间误差换算：`geometricError = 瓦片边长 / sseFactor`。
   * 3D Tiles 的 geometricError 语义是「该 tile 在当前层级下可接受的几何误差（米）」，
   * 取边长/16 相当于「一片最多错 1/16 边长」——与 VMC 前端「按水平距离每翻倍粗一级 LOD」
   * 的观感一致。默认 16。
   */
  sseFactor?: number;
  /** 细化方式；默认 ADD（父片继续显示，子片叠加，适合 VMC 的「祖先垫底」策略） */
  refine?: "ADD" | "REPLACE";
  /** 自定义 content uri（默认用清单里的 tile.url） */
  contentUri?: (tile: ManifestTile) => string;
}

const METERS_PER_DEGREE_LAT = 110540;
const METERS_PER_DEGREE_LON_EQUATOR = 111320;

/** 瓦片在某一层的世界边长（方块）。 */
export function tileWorldSize(manifest: MapManifest, level: number): number {
  return manifest.settings.hiresTileSize * 2 ** level;
}

/** 该层瓦片的 geometricError（米）。 */
export function geometricErrorFor(sizeBlocks: number, metersPerBlock: number, sseFactor: number): number {
  return (sizeBlocks / sseFactor) * metersPerBlock;
}

/** 世界方块包围盒 → 3D Tiles region 包围体。 */
export function regionForBounds(
  min: readonly [number, number, number],
  max: readonly [number, number, number],
  options: TilesetOptions = {},
): TilesetBoundingRegion {
  const lonDeg = options.anchor?.lonDeg ?? 0;
  const latDeg = options.anchor?.latDeg ?? 0;
  const anchorHeight = options.anchorHeightMeters ?? 0;
  const metersPerBlock = options.metersPerBlock ?? 1;
  const lonScale = METERS_PER_DEGREE_LON_EQUATOR * Math.max(0.01, Math.cos((latDeg * Math.PI) / 180));

  const toLon = (x: number): number => ((lonDeg + (x * metersPerBlock) / lonScale) * Math.PI) / 180;
  const toLat = (z: number): number =>
    ((latDeg + (z * metersPerBlock) / METERS_PER_DEGREE_LAT) * Math.PI) / 180;
  const toHeight = (y: number): number => anchorHeight + y * metersPerBlock;

  const west = Math.min(toLon(min[0]), toLon(max[0]));
  const east = Math.max(toLon(min[0]), toLon(max[0]));
  const south = Math.min(toLat(min[2]), toLat(max[2]));
  const north = Math.max(toLat(min[2]), toLat(max[2]));
  let minHeight = Math.min(toHeight(min[1]), toHeight(max[1]));
  let maxHeight = Math.max(toHeight(min[1]), toHeight(max[1]));
  if (maxHeight - minHeight < 0.01) {
    // region 的高度区间不能退化成一个点，给 1 厘米的裕度
    minHeight -= 0.005;
    maxHeight += 0.005;
  }
  return { region: [west, south, east, north, minHeight, maxHeight] };
}

/** 瓦片在树里的连接信息。 */
interface Node {
  tile: ManifestTile;
  children: Node[];
}

function tileKey(level: number, x: number, z: number): string {
  return `${level}:${x}:${z}`;
}

/**
 * 把扁平的瓦片列表组装成四叉树。
 *
 * <p>清单里的瓦片是**不保证完整**的：空洞区域没有瓦片（比如地图中央一片海）
 * 或者中间层被裁掉过。所以找不到直接父节点时向上找最近祖先，
 * 一直找不到就挂到合成根下——**绝不丢瓦片**（丢了等于地图上少一块）。</p>
 */
function buildForest(tiles: readonly ManifestTile[]): { roots: Node[]; levels: number[] } {
  const byKey = new Map<string, Node>();
  for (const tile of tiles) {
    byKey.set(tileKey(tile.level, tile.x, tile.z), { tile, children: [] });
  }
  const roots: Node[] = [];
  const topLevel = tiles.reduce((max, tile) => Math.max(max, tile.level), 0);
  for (const tile of tiles) {
    let parent: Node | undefined;
    for (let level = tile.level + 1; level <= topLevel; level++) {
      const candidate = byKey.get(tileKey(level, Math.floor(tile.x / 2 ** (level - tile.level)), Math.floor(tile.z / 2 ** (level - tile.level))));
      if (candidate) {
        parent = candidate;
        break;
      }
    }
    const node = byKey.get(tileKey(tile.level, tile.x, tile.z))!;
    if (parent) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }
  return { roots, levels: [...new Set(tiles.map((tile) => tile.level))].sort((a, b) => a - b) };
}

/** 整张图的包围盒（清单 bounds 与逐瓦片包围盒的并集，取更宽的那个）。 */
function manifestBounds(manifest: MapManifest): { min: [number, number, number]; max: [number, number, number] } {
  const min: [number, number, number] = [...manifest.boundsMin] as [number, number, number];
  const max: [number, number, number] = [...manifest.boundsMax] as [number, number, number];
  for (const tile of manifest.tiles) {
    for (let i = 0; i < 3; i++) {
      min[i] = Math.min(min[i]!, tile.min[i]!);
      max[i] = Math.max(max[i]!, tile.max[i]!);
    }
  }
  if (!(max[0] > min[0]) || !(max[2] > min[2])) {
    // 单点/空图：给 1 方块的裕度，否则 region 退化
    max[0] = min[0]! + 1;
    max[2] = min[2]! + 1;
  }
  return { min, max };
}

/**
 * VMC 清单 → 3D Tiles 1.1 tileset。
 *
 * 结构：最粗的 LOD 层是根（geometricError 最大），向下细化到 hires；
 * 有多个根（图不连通）时套一个**无 content 的合成根**，它的包围盒是整图包围盒——
 * 这是 3D Tiles 允许的写法，也是把多棵树收进单根的常规做法。
 */
export function toTileset(manifest: MapManifest, options: TilesetOptions = {}): Tileset {
  const sseFactor = options.sseFactor ?? 16;
  const metersPerBlock = options.metersPerBlock ?? 1;
  const refine = options.refine ?? "ADD";
  const bounds = manifestBounds(manifest);
  const { roots } = buildForest(manifest.tiles);

  const toNode = (node: Node): TilesetTile => {
    const size = tileWorldSize(manifest, node.tile.level);
    const painted: TilesetTile = {
      boundingVolume: regionForBounds(
        [node.tile.min[0]!, node.tile.min[1]!, node.tile.min[2]!] as const,
        [node.tile.max[0]!, node.tile.max[1]!, node.tile.max[2]!] as const,
        options,
      ),
      geometricError: geometricErrorFor(size, metersPerBlock, sseFactor),
      refine,
      content: { uri: options.contentUri ? options.contentUri(node.tile) : node.tile.url },
      extras: { level: node.tile.level, x: node.tile.x, z: node.tile.z, sha1: node.tile.sha1 },
    };
    const children = [...node.children].sort(
      (a, b) => a.tile.level - b.tile.level || a.tile.x - b.tile.x || a.tile.z - b.tile.z,
    );
    if (children.length > 0) {
      painted.children = children.map(toNode);
    }
    return painted;
  };

  const rootGeometricError = geometricErrorFor(
    tileWorldSize(manifest, roots[0]?.tile.level ?? 0),
    metersPerBlock,
    sseFactor,
  );

  let root: TilesetTile;
  if (roots.length === 1) {
    root = toNode(roots[0]!);
  } else {
    root = {
      boundingVolume: regionForBounds(bounds.min, bounds.max, options),
      geometricError: rootGeometricError,
      refine,
      children: [...roots]
        .sort((a, b) => a.tile.level - b.tile.level || a.tile.x - b.tile.x || a.tile.z - b.tile.z)
        .map(toNode),
    };
  }

  return {
    asset: { version: "1.1", generator: "yudream-voxelith" },
    geometricError: root.geometricError,
    root,
  };
}

/** 3D Tiles 里的一个内容条目（按树深度还原成层级）。 */
export interface TilesetContent {
  uri: string;
  /** 从根算起的深度（0 = 根）。 */
  depth: number;
  /** 还原出的 VMC 层级：深度越深、层级越低（0 = hires）。 */
  level: number;
  boundingVolume: TilesetBoundingVolume;
  extras?: Record<string, unknown>;
}

/**
 * 反向读取：把 tileset 里所有 content 摊平，按深度映射回 VMC 层级。
 *
 * <p>用于「别的产线产出的 3D Tiles 想接进 VMC」的场景：拿到 uri 列表与层级就够
 * 组装一份清单骨架（挖空（x,z）等 VMC 专有信息，需要时由包围盒反推）。</p>
 */
export function tilesetContents(tileset: Tileset): TilesetContent[] {
  const out: TilesetContent[] = [];
  let maxDepth = 0;
  const walk = (tile: TilesetTile, depth: number): void => {
    maxDepth = Math.max(maxDepth, depth);
    if (tile.content?.uri) {
      out.push({
        uri: tile.content.uri,
        depth,
        level: -1, // 先占位，等遍历完知道最大深度再回填
        boundingVolume: tile.boundingVolume,
        ...(tile.extras ? { extras: tile.extras } : {}),
      });
    }
    for (const child of tile.children ?? []) {
      walk(child, depth + 1);
    }
  };
  walk(tileset.root, 0);
  return out.map((entry) => ({ ...entry, level: maxDepth - entry.depth }));
}
