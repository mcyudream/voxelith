  /**
   * 瓦片管理：LOD 四叉树遍历 + 加载调度（并发上限 + 优先级）+ 数量/字节双阈值 LRU 淘汰。
   *
   * 每帧从清单最粗层级向下遍历：距相机足够近（或俯视屏幕误差过大）且存在子瓦片时细分。
   * 期望瓦片未加载时以已加载祖先垫底，中间缺失层不入队（避免俯视被 L1 踏脚石占满带宽）。
   * lodCount=1 的清单退化为全量 hires 加载。
   */
import * as THREE from "three";
import { tileWorldOrigin, type MapManifest, type ManifestTile } from "@yudream/voxelith-core";
import { disposeTileGroup, GlbTileLoader } from "./GlbTileLoader.js";

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
  /** 加载器注入点（测试/自定义管线）；缺省用内置 GlbTileLoader（可带共享图集）。 */
  loader?: Pick<GlbTileLoader, "load">;
}

interface LiveTile {
  group: THREE.Group;
  bytes: number;
  lastUsed: number;
  /** 世界空间包围盒中心（淘汰打分用；与 group.position 的渲染空间区分） */
  center: THREE.Vector3;
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

  private readonly loader: Pick<GlbTileLoader, "load">;
  private readonly sharedAtlas: THREE.Texture | null = null;
  private readonly live = new Map<string, LiveTile>();
  private readonly byKey = new Map<string, ManifestTile>();
  private readonly topLevelTiles: ManifestTile[] = [];
  private readonly topLevel: number;
  private readonly loading = new Set<string>();
  private readonly failed = new Set<string>();
  private queue: ManifestTile[] = [];
  private frame = 0;
  private loadedBytes = 0;
  /**
   * 代际戳：dispose/换图时递增。in-flight 的异步加载完成后比对代际，
   * 过期结果（旧地图的迟到瓦片）直接销毁，绝不进新场景 —— 防快速切图竞态。
   */
  private generation = 0;
  /**
   * 世界→渲染空间偏移（浮点原点）。默认零向量 = 渲染空间即世界空间；
   * 由 FloatingOrigin 在超远坐标（边疆量级）重定基时写入。
   */
  private readonly worldOffset = new THREE.Vector3();
  private viewPosition = new THREE.Vector3();
  private readonly frustum = new THREE.Frustum();
  private readonly frustumMatrix = new THREE.Matrix4();
  private readonly tileBox = new THREE.Box3();
  private readonly traversalStack: ManifestTile[] = [];

  constructor(options: TileManagerOptions) {
    this.scene = options.scene;
    this.mapBaseUrl = options.mapBaseUrl;
    this.manifest = options.manifest;
    this.maxConcurrent = options.maxConcurrent ?? 16;
    this.maxLoaded = options.maxLoaded ?? 4096;
    this.maxBytes = options.maxBytes ?? 2 * 1024 * 1024 * 1024;
    this.lodFactor = options.lodFactor ?? 3;
    if (options.detailDistanceChunks !== undefined) {
      this.detailDistance = options.detailDistanceChunks * 16;
    }
    this.onTileLoaded = options.onTileLoaded;
    this.topLevel = options.manifest.settings.lodCount - 1;
    // hires 共享图集：每个 hires glb 内嵌同一张图集 PNG，逐瓦片解码会把显存耗尽。
    // 按 glTF 约定配置（flipY=false + SRGBColorSpace），过滤参数由 GlbTileLoader 统一设置。
    // 无 DOM 环境（vitest/node）无法解码图片，跳过共享图集回退逐瓦片内嵌。
    if (!options.loader && options.manifest.atlas?.url && typeof document !== "undefined") {
      this.sharedAtlas = new THREE.TextureLoader().load(
        `${this.mapBaseUrl}/${options.manifest.atlas.url}`,
      );
      this.sharedAtlas.flipY = false;
      this.sharedAtlas.colorSpace = THREE.SRGBColorSpace;
    }
    this.loader =
      options.loader ?? new GlbTileLoader({ sharedAtlas: this.sharedAtlas ?? undefined });
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
    // 逻辑坐标一律世界空间：相机在渲染空间，加浮点原点偏移还原
    this.viewPosition.copy(camera.position).add(this.worldOffset);
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
      // 未加载：已加载祖先垫底；若整条祖先链都还没到，只入队最粗缺失祖先一次，
      // 不要把 L1..L(n-1) 全塞进队列（俯视会被踏脚石占满带宽）。
      let queuedAncestor = false;
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
        if (!queuedAncestor && level === this.topLevel) {
          candidates.set(ancestorKey, ancestor);
          queuedAncestor = true;
        }
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

  /** 写入浮点原点偏移（世界→渲染空间）；新加载瓦片与视距逻辑随之对齐。 */
  setWorldOffset(offset: THREE.Vector3): void {
    this.worldOffset.copy(offset);
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
    const geometric = this.boxDistance(tile) < size * this.lodFactor;
    const sse = this.screenErrorExceeds(tile, size);
    if (!geometric && !sse) {
      return false;
    }
    const dxz = this.horizontalBoxDistance(tile);
    if (dxz < this.detailDistance) {
      return true;
    }
    const allowedFinest = Math.min(
      this.topLevel,
      1 + Math.floor(Math.log2(dxz / this.detailDistance)),
    );
    return tile.level > allowedFinest;
  }

  /**
   * 屏幕空间误差：俯视高空时几何距离会远大于瓦片边长，lodFactor 门限会把整图钉在最粗层。
   * 用「瓦片边长 / 相机高度」近似像素跨度，跨度过阈值则继续细分。
   */
  private screenErrorExceeds(tile: ManifestTile, size: number): boolean {
    const midY = (tile.min[1] + tile.max[1]) / 2;
    const height = Math.max(8, this.viewPosition.y - midY);
    if (this.horizontalBoxDistance(tile) > height * 4) {
      return false;
    }
    return size / height > 0.18;
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

  /** 加载优先级：视锥内优先；同屏先铺最粗背景，再填期望层。 */
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
    const score = (t: ManifestTile) => {
      const viewBias = inView(t) ? 0.08 : 1;
      // 最粗层先到（铺底），同层按距离。中间踏脚石已不再入队。
      return distSq(t) * viewBias / 2 ** t.level;
    };
    this.queue.sort((a, b) => score(a) - score(b));
  }

  private async loadTile(tile: ManifestTile, key: string): Promise<void> {
    const generation = this.generation;
    try {
      // 版本戳防 HTTP 强缓存拿到旧版瓦片（瓦片同 URL 覆盖发布）
      const group = await this.loader.load(`${this.mapBaseUrl}/${tile.url}?v=${this.manifest.version}`);
      if (generation !== this.generation || this.live.has(key)) {
        // 迟到/重复结果：所属管理器已换图或该瓦片已被另一路径加载，直接销毁防泄漏
        disposeTileGroup(group);
        return;
      }
      const [ox, oy, oz] = tileWorldOrigin(this.manifest, tile);
      group.position.set(
        ox - this.worldOffset.x,
        oy - this.worldOffset.y,
        oz - this.worldOffset.z,
      );
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
      this.live.set(key, {
        group,
        bytes: tile.bytes,
        lastUsed: this.frame,
        center: new THREE.Vector3(
          (tile.min[0] + tile.max[0]) / 2,
          (tile.min[1] + tile.max[1]) / 2,
          (tile.min[2] + tile.max[2]) / 2,
        ),
      });
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
        const distance = tile.center.distanceTo(this.viewPosition);
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
      disposeTileGroup(victim.group);
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
    // 代际递增：此后所有 in-flight 加载完成即销毁，不再进场景
    this.generation++;
    for (const tile of this.live.values()) {
      this.scene.remove(tile.group);
      disposeTileGroup(tile.group);
    }
    this.live.clear();
    this.queue = [];
    this.loadedBytes = 0;
    this.sharedAtlas?.dispose();
  }
}
