/**
 * 瓦片管理：LOD 四叉树遍历 + 加载调度（并发上限 + 优先级）+ 数量/字节双阈值 LRU 淘汰。
 *
 * 每帧从清单最粗层级向下遍历：距相机足够近且存在子瓦片时细分为 4 个子瓦片，
 * 否则该瓦片即期望渲染集合的一员。期望瓦片未加载时以其最近已加载祖先垫底
 * （无洞、无全有全无），同时入队期望瓦片与缺失祖先；排序分按 1/2^level 加权，
 * 粗层背景永远优先于细层加载。LOD 瓦片材质带 polygonOffset（层级越深偏移越大），
 * 细层就位后与祖先背景重叠区域由细层赢得深度。lodCount=1 的清单退化为全量 hires 加载。
 */
import * as THREE from "three";
import { tileWorldOrigin, type MapManifest, type ManifestTile } from "@yudream/voxelith-core";
import { GlbTileLoader } from "./GlbTileLoader.js";

export interface TileManagerOptions {
  scene: THREE.Scene;
  mapBaseUrl: string;
  manifest: MapManifest;
  /** 并发下载上限 */
  maxConcurrent?: number;
  /** 已加载瓦片数量上限（超出按最近最少使用淘汰） */
  maxLoaded?: number;
  /** 已加载瓦片字节上限（超出淘汰） */
  maxBytes?: number;
  /** 细分距离系数：boxDistance < 瓦片边长 × lodFactor 时细化（越大越精细） */
  lodFactor?: number;
  /** 细节视距（区块）：视距内正常细分到 hires；视距外不剔除，而是按水平距离
   *  每翻一倍允许的最细层级粗一级（逐级 LOD），最粗层封顶；缺省不限 */
  detailDistanceChunks?: number;
  onTileLoaded?: (tile: ManifestTile, loaded: number, total: number) => void;
}

interface LiveTile {
  group: THREE.Group;
  bytes: number;
  lastUsed: number;
}

export class TileManager {
  private readonly scene: THREE.Scene;
  private readonly mapBaseUrl: string;
  private readonly manifest: MapManifest;
  private readonly maxConcurrent: number;
  private readonly maxLoaded: number;
  private readonly maxBytes: number;
  private readonly lodFactor: number;
  private readonly onTileLoaded?: TileManagerOptions["onTileLoaded"];
  /** 细节视距（方块）；Infinity = 视距内外无差别。由 AdaptiveDistance 每帧平滑写入 */
  private detailDistance = Infinity;

  private readonly loader = new GlbTileLoader();
  private readonly live = new Map<string, LiveTile>();
  private readonly byKey = new Map<string, ManifestTile>();
  private readonly topLevelTiles: ManifestTile[] = [];
  private readonly topLevel: number;
  private readonly loading = new Set<string>();
  private readonly failed = new Set<string>();
  private queue: ManifestTile[] = [];
  private frame = 0;
  private loadedBytes = 0;
  private viewPosition = new THREE.Vector3();
  private readonly frustum = new THREE.Frustum();
  private readonly frustumMatrix = new THREE.Matrix4();
  private readonly tileBox = new THREE.Box3();
  private readonly traversalStack: ManifestTile[] = [];

  constructor(options: TileManagerOptions) {
    this.scene = options.scene;
    this.mapBaseUrl = options.mapBaseUrl;
    this.manifest = options.manifest;
    this.maxConcurrent = options.maxConcurrent ?? 12;
    this.maxLoaded = options.maxLoaded ?? 4096;
    this.maxBytes = options.maxBytes ?? 2 * 1024 * 1024 * 1024;
    this.lodFactor = options.lodFactor ?? 4;
    if (options.detailDistanceChunks !== undefined) {
      this.detailDistance = options.detailDistanceChunks * 16;
    }
    this.onTileLoaded = options.onTileLoaded;
    this.topLevel = options.manifest.settings.lodCount - 1;
    for (const tile of options.manifest.tiles) {
      this.byKey.set(this.key(tile), tile);
      if (tile.level === this.topLevel) {
        this.topLevelTiles.push(tile);
      }
    }
  }

  private key(tile: ManifestTile): string {
    return `${tile.level}:${tile.x}:${tile.z}`;
  }

  /** 每帧调用：LOD 遍历决定渲染集合（祖先垫底）并调度缺失瓦片。 */
  update(camera: THREE.Camera): void {
    this.frame++;
    this.viewPosition.copy(camera.position);
    camera.updateMatrixWorld();
    this.frustumMatrix.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
    this.frustum.setFromProjectionMatrix(this.frustumMatrix);

    const desired = this.collectDesired();
    const renderKeys = new Set<string>();
    const candidates = new Map<string, ManifestTile>();
    for (const tile of desired) {
      const key = this.key(tile);
      if (this.markLive(renderKeys, key)) {
        continue;
      }
      candidates.set(key, tile);
      // 未加载：最近已加载祖先垫底；路上第一个缺失祖先入队（粗层先到位）
      for (let level = tile.level + 1; level <= this.topLevel; level++) {
        const shift = level - tile.level;
        const ancestor = this.byKey.get(`${level}:${tile.x >> shift}:${tile.z >> shift}`);
        if (!ancestor) {
          continue;
        }
        const ancestorKey = this.key(ancestor);
        if (this.markLive(renderKeys, ancestorKey)) {
          break;
        }
        candidates.set(ancestorKey, ancestor);
      }
    }

    // 不在渲染集合中的存活瓦片隐藏但保留（LRU 持有，视角回摆即无感恢复）
    for (const [key, tile] of this.live) {
      tile.group.visible = renderKeys.has(key);
    }

    this.queue = [...candidates.values()].filter(
      (tile) => !this.loading.has(this.key(tile)) && !this.failed.has(this.key(tile)),
    );
    this.sortQueue();

    while (this.loading.size < this.maxConcurrent && this.queue.length > 0) {
      const tile = this.queue.shift()!;
      const key = this.key(tile);
      if (this.live.has(key) || this.failed.has(key)) {
        continue;
      }
      this.loading.add(key);
      void this.loadTile(tile, key);
    }
  }

  /** LOD 四叉树遍历：从最粗层级向下，够近且有子瓦片则细分，返回期望渲染的瓦片集合。 */
  private collectDesired(): ManifestTile[] {
    const desired: ManifestTile[] = [];
    const stack = this.traversalStack;
    stack.push(...this.topLevelTiles);
    while (stack.length > 0) {
      const tile = stack.pop()!;
      if (tile.level > 0 && this.shouldRefine(tile) && this.pushChildren(tile, stack)) {
        continue;
      }
      desired.push(tile);
    }
    return desired;
  }

  /** 设置细节视距（方块）：视距外逐级使用更粗 LOD（不剔除）；传 Infinity 取消限制。 */
  setDetailDistanceBlocks(blocks: number): void {
    this.detailDistance = blocks;
  }

  get detailDistanceBlocks(): number {
    return this.detailDistance;
  }

  /** 相机到瓦片包围盒的水平（XZ）距离（盒内为 0），高度差不计入细节视距。 */
  private horizontalBoxDistance(tile: ManifestTile): number {
    const px = this.viewPosition.x;
    const pz = this.viewPosition.z;
    const dx = Math.max(tile.min[0] - px, 0, px - tile.max[0]);
    const dz = Math.max(tile.min[2] - pz, 0, pz - tile.max[2]);
    return Math.sqrt(dx * dx + dz * dz);
  }

  private shouldRefine(tile: ManifestTile): boolean {
    const size = this.manifest.settings.hiresTileSize * 2 ** tile.level;
    if (this.boxDistance(tile) >= size * this.lodFactor) {
      return false;
    }
    const dxz = this.horizontalBoxDistance(tile);
    if (dxz < this.detailDistance) {
      // 视距内：标准渲染，按常规规则细分到 hires
      return true;
    }
    // 视距外：不剔除，逐级使用 LOD——水平距离每翻一倍，允许的最细层级粗一级，最粗层封顶
    const allowedFinest = Math.min(
      this.topLevel,
      1 + Math.floor(Math.log2(dxz / this.detailDistance)),
    );
    return tile.level > allowedFinest;
  }

  /** 4 个子瓦片中存在于清单的压入栈；返回是否存在任何子瓦片。 */
  private pushChildren(tile: ManifestTile, stack: ManifestTile[]): boolean {
    const childLevel = tile.level - 1;
    const cx = tile.x * 2;
    const cz = tile.z * 2;
    let found = false;
    for (const [dx, dz] of [[0, 0], [1, 0], [0, 1], [1, 1]] as const) {
      const child = this.byKey.get(`${childLevel}:${cx + dx}:${cz + dz}`);
      if (child) {
        stack.push(child);
        found = true;
      }
    }
    return found;
  }

  /** 相机到瓦片世界包围盒的距离（盒内为 0）。 */
  private boxDistance(tile: ManifestTile): number {
    const px = this.viewPosition.x;
    const py = this.viewPosition.y;
    const pz = this.viewPosition.z;
    const dx = Math.max(tile.min[0] - px, 0, px - tile.max[0]);
    const dy = Math.max(tile.min[1] - py, 0, py - tile.max[1]);
    const dz = Math.max(tile.min[2] - pz, 0, pz - tile.max[2]);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /** 瓦片已加载则加入渲染集合并刷新 LRU 时间戳。 */
  private markLive(renderKeys: Set<string>, key: string): boolean {
    const liveTile = this.live.get(key);
    if (!liveTile) {
      return false;
    }
    liveTile.lastUsed = this.frame;
    renderKeys.add(key);
    return true;
  }

  /** 加载优先级：视锥内 ×0.1（视野内优先），层级越深分越高（粗层背景先加载）。 */
  private sortQueue(): void {
    const inView = (t: ManifestTile) => {
      this.tileBox.min.set(t.min[0], t.min[1], t.min[2]);
      this.tileBox.max.set(t.max[0], t.max[1], t.max[2]);
      return this.frustum.intersectsBox(this.tileBox);
    };
    const distSq = (t: ManifestTile) => {
      const dx = (t.min[0] + t.max[0]) / 2 - this.viewPosition.x;
      const dy = (t.min[1] + t.max[1]) / 2 - this.viewPosition.y;
      const dz = (t.min[2] + t.max[2]) / 2 - this.viewPosition.z;
      return dx * dx + dy * dy + dz * dz;
    };
    const score = (t: ManifestTile) =>
      (distSq(t) * (inView(t) ? 0.1 : 1)) / 2 ** t.level;
    this.queue.sort((a, b) => score(a) - score(b));
  }

  private async loadTile(tile: ManifestTile, key: string): Promise<void> {
    try {
      // 版本戳防 HTTP 强缓存拿到旧版瓦片（瓦片同 URL 覆盖发布）
      const group = await this.loader.load(`${this.mapBaseUrl}/${tile.url}?v=${this.manifest.version}`);
      const [ox, oy, oz] = tileWorldOrigin(this.manifest, tile);
      group.position.set(ox, oy, oz);
      group.matrixAutoUpdate = false;
      group.updateMatrix();
      if (tile.level > 0) {
        // LOD 背景在深度上让位：细层就位后与祖先重叠区域由细层绘制
        group.traverse((node) => {
          if (node instanceof THREE.Mesh) {
            const material = node.material as THREE.Material;
            material.polygonOffset = true;
            material.polygonOffsetFactor = tile.level;
            material.polygonOffsetUnits = tile.level * 2;
          }
        });
      }
      this.scene.add(group);
      this.live.set(key, { group, bytes: tile.bytes, lastUsed: this.frame });
      this.loadedBytes += tile.bytes;
      this.evictIfNeeded();
      this.onTileLoaded?.(tile, this.live.size, this.manifest.tiles.length);
    } catch (error) {
      this.failed.add(key);
      console.error(`瓦片加载失败 ${key}:`, error);
    } finally {
      this.loading.delete(key);
    }
  }

  /** 双阈值滞回淘汰：数量或字节超限，淘汰最久未用（优先远离视点）的瓦片。 */
  private evictIfNeeded(): void {
    while (this.live.size > this.maxLoaded || this.loadedBytes > this.maxBytes) {
      let victimKey: string | null = null;
      let victimScore = -Infinity;
      for (const [key, tile] of this.live) {
        const distance = tile.group.position.distanceTo(this.viewPosition);
        const score = distance + (this.frame - tile.lastUsed) * 1000;
        if (score > victimScore) {
          victimScore = score;
          victimKey = key;
        }
      }
      if (victimKey === null) {
        return;
      }
      const victim = this.live.get(victimKey)!;
      this.scene.remove(victim.group);
      disposeGroup(victim.group);
      this.loadedBytes -= victim.bytes;
      this.live.delete(victimKey);
    }
  }

  get loadedCount(): number {
    return this.live.size;
  }

  get failedCount(): number {
    return this.failed.size;
  }

  dispose(): void {
    for (const tile of this.live.values()) {
      this.scene.remove(tile.group);
      disposeGroup(tile.group);
    }
    this.live.clear();
    this.queue = [];
    this.loadedBytes = 0;
  }
}

function disposeGroup(group: THREE.Group): void {
  group.traverse((node) => {
    if (node instanceof THREE.Mesh) {
      node.geometry.dispose();
      const material = node.material as THREE.Material;
      material.dispose();
    }
  });
}
