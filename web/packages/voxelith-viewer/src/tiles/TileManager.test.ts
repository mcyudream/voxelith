import * as THREE from "three";
import { describe, expect, it, vi } from "vitest";
import type { MapManifest, ManifestTile } from "@yudream/voxelith-core";
import { evictionScore, retryDelayMs, TileManager } from "./TileManager.js";

/**
 * 构造一棵小型 LOD 金字塔：hiresTileSize=32，lodCount=3（层级 0/1/2）。
 * L2(0,0) 覆盖 [0,128]²，其下完整展开到 L0；L2(1,0) 覆盖 [128,256]² 且无子瓦片。
 */
function tile(level: number, x: number, z: number): ManifestTile {
  const size = 32 * 2 ** level;
  return {
    level,
    x,
    z,
    url: `tiles/l${level}/${x}/${z}.glb`,
    sha1: "test",
    bytes: 1,
    quads: 1,
    min: [x * size, 0, z * size],
    max: [(x + 1) * size, 64, (z + 1) * size],
  };
}

function makeManifest(): MapManifest {
  const tiles: ManifestTile[] = [tile(2, 0, 0), tile(2, 1, 0)];
  // L2(0,0) 的 L1 子树
  for (const [x, z] of [[0, 0], [1, 0], [0, 1], [1, 1]] as const) {
    tiles.push(tile(1, x, z));
  }
  // L1(0,0) 与 L1(1,0) 的 L0 子树
  for (const [x, z] of [[0, 0], [1, 0], [0, 1], [1, 1], [2, 0], [3, 0], [2, 1], [3, 1]] as const) {
    tiles.push(tile(0, x, z));
  }
  return {
    formatVersion: 1,
    mapId: "test",
    name: "test",
    version: "v1",
    generatedAt: "2026-01-01T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 3 },
    boundsMin: [0, 0, 0],
    boundsMax: [256, 64, 128],
    atlas: { url: "atlas.png", size: 128, textureCount: 1 },
    lodAtlases: [],
    tiles,
  };
}

/** 跳过 update()（避免真实加载），直接设置视点并取期望渲染集合的 key 列表。 */
function desiredKeys(tm: TileManager, x: number, y: number, z: number): string[] {
  const inner = tm as unknown as {
    viewPosition: THREE.Vector3;
    collectDesired: () => ManifestTile[];
  };
  inner.viewPosition.set(x, y, z);
  return inner
    .collectDesired()
    .map((t) => `${t.level}:${t.x}:${t.z}`)
    .sort();
}

describe("TileManager 细节视距（视距外逐级 LOD）", () => {
  it("默认不限视距：近处按常规规则细分到 hires", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      lodFactor: 4,
    });
    const keys = desiredKeys(tm, 16, 10, 16);
    // 相机所在角落细分到 L0
    expect(keys).toContain("0:0:0");
    expect(keys).toContain("0:1:1");
    // 48 方块外的 L1(1,0) 在不限视距时也细分到 L0
    expect(keys).toContain("0:2:0");
    // 无子瓦片的远端 L2 保持原级
    expect(keys).toContain("2:1:0");
  });

  it("细节视距内标准渲染，视距外逐级变粗且不超过最粗层", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      lodFactor: 4,
      detailDistanceChunks: 3, // 48 方块
    });
    const keys = desiredKeys(tm, 16, 10, 16);
    // 视距内（dxz < 48）：hires
    expect(keys).toContain("0:0:0");
    // 第一环 [48,96)：最细到 L1，不再细分出 L0
    expect(keys).toContain("1:1:0");
    expect(keys).toContain("1:0:1");
    expect(keys).not.toContain("0:2:0");
    // 远端（dxz ≈ 112 → [96,192) 环）：封顶最粗层 L2
    expect(keys).toContain("2:1:0");
  });

  it("setDetailDistanceBlocks(Infinity) 恢复全量细分", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      lodFactor: 4,
      detailDistanceChunks: 3,
    });
    tm.setDetailDistanceBlocks(Infinity);
    const keys = desiredKeys(tm, 16, 10, 16);
    expect(keys).toContain("0:2:0");
    expect(tm.detailDistanceBlocks).toBe(Infinity);
  });

  it("高空斜视：水平距离仍判定为视距内（高度差不计入细节视距）", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      lodFactor: 4,
      detailDistanceChunks: 3,
    });
    // 相机在 (16,10,16) 正上方 200m：水平距离 0 < 48，应细分到 hires
    const keys = desiredKeys(tm, 16, 200, 16);
    expect(keys).toContain("0:0:0");
    expect(keys).toContain("1:1:0");
    expect(keys).not.toContain("0:2:0");
  });

  it("高空俯视：屏幕误差门限让正下方继续细分，而不是钉在最粗层", () => {
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      lodFactor: 1,
    });
    // lodFactor=1 时纯几何门限会把 200m 高空的 L2(128m) 判为不细分；
    // SSE 应按相机高度继续向下。
    const keys = desiredKeys(tm, 16, 200, 16);
    expect(keys).toContain("0:0:0");
    expect(keys).not.toContain("2:0:0");
  });
});

/** 构造可追踪释放的瓦片组。 */
function makeTrackableGroup(): { group: THREE.Group; disposed: { value: boolean } } {
  const disposed = { value: false };
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute(
    "position",
    new THREE.BufferAttribute(new Float32Array([0, 0, 0, 1, 0, 0, 0, 0, 1]), 3),
  );
  geometry.dispose = () => {
    disposed.value = true;
  };
  const group = new THREE.Group();
  group.add(new THREE.Mesh(geometry, new THREE.MeshBasicMaterial()));
  return { group, disposed };
}

function flushMicrotasks(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe("TileManager 代际防竞态与浮点原点", () => {
  it("dispose 后迟到的加载结果直接销毁，不进场景", async () => {
    const scene = new THREE.Scene();
    const pending: Array<{
      resolve: () => void;
      disposed: { value: boolean };
    }> = [];
    const loader = {
      load: (_url: string) => {
        const { group, disposed } = makeTrackableGroup();
        return new Promise<THREE.Group>((resolve) => {
          pending.push({ resolve: () => resolve(group), disposed });
        });
      },
    };
    const tm = new TileManager({
      scene,
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
    });
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16, 10, 16);
    tm.update(camera);
    expect(pending.length).toBeGreaterThan(0);

    // 玩家切图：旧管理器销毁
    tm.dispose();
    // 在途请求此刻才返回（过期数据）
    for (const p of pending) {
      p.resolve();
    }
    await flushMicrotasks();

    expect(tm.loadedCount).toBe(0);
    expect(scene.children.length).toBe(0);
    // 每个迟到组的 geometry 都被 dispose（不泄漏 GPU 资源）
    for (const p of pending) {
      expect(p.disposed.value).toBe(true);
    }
  });

  it("setWorldOffset 后新瓦片按渲染空间定位（世界原点 − 偏移）", async () => {
    const scene = new THREE.Scene();
    const byUrl = new Map<string, THREE.Group>();
    const loader = {
      load: (url: string) => {
        const { group } = makeTrackableGroup();
        byUrl.set(url, group);
        return Promise.resolve(group);
      },
    };
    const tm = new TileManager({
      scene,
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
    });
    tm.setWorldOffset(new THREE.Vector3(1024, 0, -2048));
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16 - 1024, 10, 16 + 2048); // 渲染空间相机：世界 (16,10,16)
    tm.update(camera);
    await flushMicrotasks();

    // L2(1,0) 世界原点 (128,0,0) → 渲染空间 (128-1024, 0, 0-(-2048))
    const entry = [...byUrl.entries()].find(([url]) => url.includes("tiles/l2/1/0.glb"));
    expect(entry).toBeDefined();
    const group = entry![1];
    expect(group.position.x).toBe(128 - 1024);
    expect(group.position.z).toBe(0 + 2048);
    // matrixAutoUpdate=false 时矩阵已同步
    expect(group.matrix.elements[12]).toBe(group.position.x);
    expect(scene.children).toContain(group);
    tm.dispose();
  });

  it("setLayerFilter('hires') 隐藏 LOD 组", async () => {
    const scene = new THREE.Scene();
    const loader = {
      load: () => Promise.resolve(makeTrackableGroup().group),
    };
    const tm = new TileManager({
      scene,
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
    });
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16, 10, 16);
    tm.update(camera);
    await flushMicrotasks();
    tm.setLayerFilter("hires");
    const inner = tm as unknown as { live: Map<string, { group: THREE.Group }> };
    for (const [key, tile] of inner.live) {
      const level = Number(key.split(":")[0]);
      expect(tile.group.visible).toBe(level === 0);
    }
    tm.dispose();
  });
});

describe("TileManager 缓存版本戳", () => {
  it("瓦片 URL 用自身 sha1 做版本戳，而不是全图聚合 version", async () => {
    const urls: string[] = [];
    const loader = {
      load: (url: string) => {
        urls.push(url);
        return Promise.resolve(makeTrackableGroup().group);
      },
    };
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
    });
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16, 10, 16);
    tm.update(camera);
    await flushMicrotasks();

    expect(urls.length).toBeGreaterThan(0);
    for (const url of urls) {
      expect(url).toContain("?sha=test");
      expect(url).not.toContain("?v=v1");
    }
    tm.dispose();
  });
});

describe("TileManager 失败重试", () => {
  it("retryDelayMs 指数退避并封顶", () => {
    expect(retryDelayMs(1, 1000, 30000)).toBe(1000);
    expect(retryDelayMs(2, 1000, 30000)).toBe(2000);
    expect(retryDelayMs(3, 1000, 30000)).toBe(4000);
    expect(retryDelayMs(9, 1000, 30000)).toBe(30000);
    expect(retryDelayMs(0, 1000, 30000)).toBe(0);
  });

  it("瞬时失败在退避到期后重试成功，失败计数清零", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    let clock = 0;
    let attempts = 0;
    const loader = {
      load: (url: string) => {
        if (!url.includes("tiles/l2/1/0.glb")) {
          return Promise.resolve(makeTrackableGroup().group);
        }
        attempts++;
        return attempts === 1
          ? Promise.reject(new Error("瞬时网络错误"))
          : Promise.resolve(makeTrackableGroup().group);
      },
    };
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
      now: () => clock,
      retryBaseDelayMs: 1000,
    });
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16, 10, 16);

    tm.update(camera);
    await flushMicrotasks();
    expect(attempts).toBe(1);
    expect(tm.failedCount).toBe(1);

    // 退避未到期：不重新入队，避免对刚失败的瓦片疯狂重试
    clock = 999;
    tm.update(camera);
    await flushMicrotasks();
    expect(attempts).toBe(1);

    // 退避到期：重新入队并成功
    clock = 1000;
    tm.update(camera);
    await flushMicrotasks();
    expect(attempts).toBe(2);
    expect(tm.failedCount).toBe(0);
    tm.dispose();
    errorSpy.mockRestore();
  });

  it("超过尝试上限后不再重试（保留失败计数）", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    let clock = 0;
    let attempts = 0;
    const loader = {
      load: (url: string) => {
        if (!url.includes("tiles/l2/1/0.glb")) {
          return Promise.resolve(makeTrackableGroup().group);
        }
        attempts++;
        return Promise.reject(new Error("持续失败"));
      },
    };
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
      now: () => clock,
      maxTileAttempts: 2,
      retryBaseDelayMs: 100,
    });
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16, 10, 16);

    for (clock of [0, 100, 1000, 10000]) {
      tm.update(camera);
      await flushMicrotasks();
    }
    expect(attempts).toBe(2);
    expect(tm.failedCount).toBe(1);
    tm.dispose();
    errorSpy.mockRestore();
  });
});

describe("TileManager 淘汰", () => {
  it("evictionScore 以帧龄为主、距离为辅（越旧越先淘汰）", () => {
    // 陈旧一帧（+1000）压过 999 方块的距离差
    expect(evictionScore(999, 1)).toBeGreaterThan(evictionScore(0, 0));
    // 同帧龄时更远的先淘汰
    expect(evictionScore(100, 0)).toBeGreaterThan(evictionScore(10, 0));
  });

  it("数量上限被强制，不会因任何保护逻辑突破", async () => {
    const loader = {
      load: () => Promise.resolve(makeTrackableGroup().group),
    };
    const tm = new TileManager({
      scene: new THREE.Scene(),
      mapBaseUrl: "http://localhost/maps/test",
      manifest: makeManifest(),
      loader,
      maxLoaded: 1,
    });
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(16, 10, 16);
    tm.update(camera);
    await flushMicrotasks();

    expect(tm.loadedCount).toBeLessThanOrEqual(1);
    tm.dispose();
  });
});
