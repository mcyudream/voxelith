<script setup lang="ts">
import {
  AdaptiveDistance,
  cacheLimitsForTier,
  detectDeviceProfile,
  FirstPersonControls,
  FloatingOrigin,
  FreeFlightControls,
  initialViewDistanceChunks,
  LightingUniforms,
  loadManifest,
  MapEngine,
  supportsDisplayP3,
  TileManager,
  TiltOrbitControls,
  type CameraControls,
  type CameraMode,
  type DeviceProfile,
  type TerrainMedium,
  type TerrainProbe,
} from "@yudream/voxelith-viewer";
import type { MapManifest } from "@yudream/voxelith-core";
import * as THREE from "three";
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { deleteMap } from "./api";
import UploadDialog from "./components/UploadDialog.vue";

interface MapSummary {
  id: string;
  name: string;
  dimension: string;
  worldVersion: string;
  state: string;
}

const MODE_LABELS: Record<CameraMode, string> = {
  flight: "自由飞行",
  firstPerson: "第一人称",
  tiltOrbit: "俯视倾斜",
};

const MODE_HINTS: Record<CameraMode, string> = {
  flight: "点击画面锁定鼠标 · WASD 移动 · Space/Ctrl 升降 · Shift 加速 · 滚轮调速 · Esc 释放鼠标",
  firstPerson: "点击画面锁定鼠标 · WASD 行走 · Space 跳跃（水里按住 = 上浮）· Shift/Ctrl 疾跑 · Esc 释放鼠标 · 生存式重力/碰撞：半格自动迈、一格坎要跳、水里缓慢下沉、草木不挡路",
  tiltOrbit: "左键拖拽/WASD 移动 · 右键拖拽/Alt+WASD 旋转倾斜 · 滚轮/+- 缩放",
};

const canvasRef = ref<HTMLCanvasElement | null>(null);
const maps = ref<MapSummary[]>([]);
/** 列表里新出现、用户还没切过去的地图：工具栏据此提示「新地图已就绪」。 */
const newMaps = ref<MapSummary[]>([]);
const activeMapId = ref("");
/** 删除当前地图进行中（防重复点击） */
const deletingMap = ref(false);
const manifest = ref<MapManifest | null>(null);
const loadedTiles = ref(0);
const failedTiles = ref(0);
const status = ref<"loading" | "ready" | "error">("loading");
const errorMessage = ref("");
const mode = ref<CameraMode>("flight");
const showSettings = ref(false);
/** 上传地图弹窗（上传存档 → 二维框选渲染范围 → 后台渲染） */
const showUpload = ref(false);
const cameraPos = reactive({ x: 0, y: 0, z: 0 });

const settings = reactive({
  /** 鼠标灵敏度（弧度/像素） */
  sensitivity: 0.0011,
  /** 飞行速度（方块/秒） */
  flySpeed: 20,
  /** 视场角 */
  fov: 75,
  /** 第一人称地面高度 */
  groundY: 4,
  /** 天空光强度（0 = 夜晚，1 = 白天） */
  skyLight: 1,
  /** 方块光强度（火把/路灯等） */
  blockLight: 1,
  /** 环境光遮蔽强度 */
  aoStrength: 1,
  /** 广色域 Display P3 输出（仅 P3 屏幕生效，基准 sRGB 不变） */
  displayP3: localStorage.getItem("yudream.displayP3") === "1",
  /** 自适应视距：按 FPS 动态调整（关 = 手动滑杆） */
  autoDistance: localStorage.getItem("yudream.autoDistance") !== "0",
  /** 手动视距（区块，8～32） */
  distanceChunks: Math.min(32, Math.max(8, Number(localStorage.getItem("yudream.distanceChunks")) || 16)),
});

/** 设备性能分档（引擎创建后探测，含 localStorage 缓存） */
const deviceProfile = ref<DeviceProfile | null>(null);
/** 当前实际视距（区块，自适应插值读数） */
const currentDistance = ref(0);

/** 屏幕是否支持 P3（不支持则不显示开关） */
const p3Supported = supportsDisplayP3();

let engine: MapEngine | null = null;
let controls: CameraControls | null = null;
let tileManager: TileManager | null = null;
let adaptive: AdaptiveDistance | null = null;
/** 浮点原点：常规坐标（±2^24 内）下不触发；边疆量级自动重定基防 float32 精度撕裂 */
const floatingOrigin = new FloatingOrigin();
let posTimer = 0;
/** 地图列表同步定时器（见 scheduleMapSync） */
let mapSyncTimer = 0;
/** 首次拉到列表之前的空列表不算「没有新地图」，首次拉完之后的才算差集 */
let mapsPrimed = false;

const modeHint = computed(() => MODE_HINTS[mode.value]);
/** 工具栏提示里显示的最新一张新地图（没有新地图时为空串）。 */
const newMapLabel = computed(() => newMaps.value[0]?.name ?? "");

/**
 * 地图 id 直接进 URL：id 允许中文等字符（发布目录名就是它），但不编码时
 * `#`、`%`、`&` 会把路径截断或改变含义——所以统一过一遍 encodeURIComponent。
 */
function mapBaseUrl(mapId: string): string {
  return `/maps/${encodeURIComponent(mapId)}`;
}

/** 地形射线复用对象（第一人称每帧多次探测，避免逐帧分配） */
const terrainRaycaster = new THREE.Raycaster();
const terrainRayOrigin = new THREE.Vector3();
const terrainRayDirection = new THREE.Vector3();
/** 竖直射线起点留在地图最高点之上的余量（方块） */
const TERRAIN_SKY_MARGIN = 32;
/** 第一人称可行走范围相对地图边界的内缩（方块） */
const FIRST_PERSON_EDGE_MARGIN = 1;

/**
 * 渲染空间射线求交（第一人称碰撞用），命中返回最近距离。
 *
 * 只打 hires（level 0）瓦片的**碰撞代理**：LOD 柱顶取的是 2^L 方块内的**最高**表面，
 * 粗层顶面能比真实地面高几十格，拿它当地面人就被托在半空；代理按区域切了子网格，
 * 射线只扫穿过的 1~2 块（密集瓦片整片求交要几毫秒）。沿途只取起点/终点两列
 * （射线都很短，不会跨第三片瓦片）。
 */
function castTerrainRay(
  x: number,
  y: number,
  z: number,
  dx: number,
  dy: number,
  dz: number,
  maxDistance: number,
  medium: TerrainMedium = "solid",
): number | null {
  if (!engine || !tileManager || maxDistance <= 0) {
    return null;
  }
  // 实体 / 水面各有一套代理：实体已剔除植物与水面，水面单独用于游泳与浮沉
  const start = medium === "water" ? tileManager.waterProxyAt(x, z) : tileManager.solidProxyAt(x, z);
  const end = medium === "water"
    ? tileManager.waterProxyAt(x + dx * maxDistance, z + dz * maxDistance)
    : tileManager.solidProxyAt(x + dx * maxDistance, z + dz * maxDistance);
  const groups: THREE.Object3D[] = [];
  if (start) {
    groups.push(start);
  }
  if (end && end !== start) {
    groups.push(end);
  }
  if (groups.length === 0) {
    return null;
  }
  terrainRaycaster.set(
    terrainRayOrigin.set(x, y, z),
    terrainRayDirection.set(dx, dy, dz),
  );
  terrainRaycaster.near = 0;
  terrainRaycaster.far = maxDistance;
  const hits = terrainRaycaster.intersectObjects(groups, true);
  return hits.length > 0 ? hits[0]!.distance : null;
}

/**
 * 第一人称地形探测：脚下地面、撞墙、撞头都走这里。
 * 探不到地形（视距外 / 图外 / 瓦片还没到）时返回 null，控制器据此维持原高度而不是下沉。
 */
const terrainProbe: TerrainProbe = {
  topSurfaceY(x, z, medium = "solid") {
    const m = manifest.value;
    if (!m) {
      return null;
    }
    const top = m.boundsMax[1] - floatingOrigin.origin.y + TERRAIN_SKY_MARGIN;
    const bottom = m.boundsMin[1] - floatingOrigin.origin.y;
    const hit = castTerrainRay(x, top, z, 0, -1, 0, top - bottom, medium);
    return hit === null ? null : top - hit;
  },
  groundBelow(x, z, fromY, maxDrop = Number.POSITIVE_INFINITY, medium = "solid") {
    const m = manifest.value;
    const floor = m ? m.boundsMin[1] - floatingOrigin.origin.y : fromY - 4096;
    const drop = Math.min(maxDrop, fromY - floor);
    if (drop <= 0) {
      return null;
    }
    const hit = castTerrainRay(x, fromY, z, 0, -1, 0, drop, medium);
    return hit === null ? null : fromY - hit;
  },
  castRay: castTerrainRay,
};

/** 第一人称水平活动范围：收进地图包围盒内，免得走出图边缘悬空（渲染空间就地修改）。 */
function clampToMapBounds(position: THREE.Vector3): void {
  const m = manifest.value;
  if (!m) {
    return;
  }
  const origin = floatingOrigin.origin;
  position.x = THREE.MathUtils.clamp(
    position.x,
    m.boundsMin[0] - origin.x + FIRST_PERSON_EDGE_MARGIN,
    m.boundsMax[0] - origin.x - FIRST_PERSON_EDGE_MARGIN,
  );
  position.z = THREE.MathUtils.clamp(
    position.z,
    m.boundsMin[2] - origin.z + FIRST_PERSON_EDGE_MARGIN,
    m.boundsMax[2] - origin.z - FIRST_PERSON_EDGE_MARGIN,
  );
}

function createControls(m: CameraMode, tiltTarget?: THREE.Vector3): CameraControls {
  if (!engine || !canvasRef.value) {
    throw new Error("engine not ready");
  }
  const canvas = canvasRef.value;
  if (m === "flight") {
    return new FreeFlightControls(engine.camera, canvas, {
      sensitivity: settings.sensitivity,
      speed: settings.flySpeed,
    });
  }
  if (m === "firstPerson") {
    return new FirstPersonControls(engine.camera, canvas, {
      sensitivity: settings.sensitivity,
      groundY: settings.groundY,
      // 生存式移动：脚下地面与碰撞全部来自 hires 瓦片几何的射线探测
      probe: terrainProbe,
      clampXZ: clampToMapBounds,
    });
  }
  // 俯视倾斜：默认以相机前方 60 格的地表点为目标，从当前视角平滑过渡
  const forward = new THREE.Vector3();
  engine.camera.getWorldDirection(forward);
  const target = tiltTarget ?? engine.camera.position.clone().addScaledVector(forward, 60);
  if (!tiltTarget) {
    target.y = settings.groundY;
  }
  return new TiltOrbitControls(engine.camera, canvas, { target });
}

function switchMode(m: CameraMode): void {
  if (m === mode.value || !engine) return;
  mode.value = m;
  controls?.dispose();
  controls = createControls(m);
}

async function openMap(mapId: string): Promise<void> {
  if (!engine) return;
  forgetNewMap(mapId);
  status.value = "loading";
  loadedTiles.value = 0;
  failedTiles.value = 0;
  tileManager?.dispose();
  tileManager = null;
  // 场景与相机随即按世界坐标重建，渲染原点归零
  floatingOrigin.reset();
  try {
    const baseUrl = mapBaseUrl(mapId);
    const m = await loadManifest(baseUrl);
    manifest.value = m;
    const cx = (m.boundsMin[0] + m.boundsMax[0]) / 2;
    const cz = (m.boundsMin[2] + m.boundsMax[2]) / 2;
    engine.camera.position.set(cx, m.boundsMax[1] + 60, cz + 80);
    // 出生视角必须保持水平：若俯视地图中心（原实现 -37° 俯角），"前进"会自带俯冲分量，
    // 进图一奔跑就扎进地形下方——方块几何是单面渲染，从下往上看整个世界被背面剔除，
    // 画面在地表与纯天空色之间来回翻转（表现为屏幕闪烁）。
    engine.camera.lookAt(cx, m.boundsMax[1] + 60, cz);
    // 排障/分享用：?pos=x,y,z 直接落到指定世界坐标（第一人称会再落到该列表面上）
    const posParam = new URLSearchParams(window.location.search).get("pos");
    if (posParam) {
      const [px, py, pz] = posParam.split(",").map(Number);
      if (px !== undefined && py !== undefined && pz !== undefined
          && Number.isFinite(px) && Number.isFinite(py) && Number.isFinite(pz)) {
        engine.camera.position.set(px, py, pz);
      }
    }
    // 重建当前控制器以吸收新机位朝向
    controls?.dispose();
    controls = createControls(mode.value);
    tileManager = new TileManager({
      scene: engine.scene,
      mapBaseUrl: baseUrl,
      manifest: m,
      // 缓存上限按设备档位收紧：弱 GPU/移动端先耗尽的是显存与内存
      ...cacheLimitsForTier(deviceProfile.value?.tier ?? "mid"),
      onTileLoaded: () => {
        loadedTiles.value = tileManager?.loadedCount ?? 0;
        failedTiles.value = tileManager?.failedCount ?? 0;
      },
    });
    // 自适应视距：初始值取设备分档（自动）或手动滑杆；每帧驱动瓦片截止与雾效
    const initialChunks = settings.autoDistance
      ? initialViewDistanceChunks(deviceProfile.value?.tier ?? "mid")
      : settings.distanceChunks;
    adaptive = new AdaptiveDistance({
      tileManager,
      initialChunks,
      mobile: deviceProfile.value?.mobile,
      onChange: (c) => {
        currentDistance.value = c;
      },
    });
    adaptive.setEnabled(settings.autoDistance);
    currentDistance.value = Math.round(adaptive.chunks);
    status.value = "ready";
    const url = new URL(window.location.href);
    url.searchParams.set("map", mapId);
    window.history.replaceState(null, "", url);
  } catch (e) {
    status.value = "error";
    errorMessage.value = e instanceof Error ? e.message : String(e);
  }
}

function selectMap(e: Event): void {
  const id = (e.target as HTMLSelectElement).value;
  if (id && id !== activeMapId.value) {
    activeMapId.value = id;
    void openMap(id);
  }
}

/** 某张地图已经显示出来了，从「新地图」提示里摘掉。 */
function forgetNewMap(mapId: string): void {
  if (newMaps.value.some((m) => m.id === mapId)) {
    newMaps.value = newMaps.value.filter((m) => m.id !== mapId);
  }
}

/**
 * 删除当前打开的地图：清掉发布产物（瓦片/清单）与渲染工作目录，不可恢复。
 * 删完 refreshMaps 会自动退回列表第一张；最后一张也删掉时把视图停在一个明确的错误态。
 */
async function deleteActiveMap(): Promise<void> {
  const map = maps.value.find((m) => m.id === activeMapId.value);
  if (!map || deletingMap.value) return;
  if (!window.confirm(`删除地图「${map.name}」？渲染产物会一并清掉，不可恢复。`)) {
    return;
  }
  deletingMap.value = true;
  try {
    await deleteMap(map.id);
    forgetNewMap(map.id);
    await refreshMaps();
    if (!maps.value.some((m) => m.id === activeMapId.value) && !maps.value.length) {
      // 最后一张也删了：没有可退回的地图，收掉场景给出明确提示
      tileManager?.dispose();
      tileManager = null;
      manifest.value = null;
      activeMapId.value = "";
      status.value = "error";
      errorMessage.value = "没有可用地图（最后一张已删除），可从「上传地图」重新渲染一张";
    }
  } catch (e) {
    window.alert(e instanceof Error ? e.message : String(e));
  } finally {
    deletingMap.value = false;
  }
}

/**
 * 地图列表：渲染产物落盘即发布，重新拉一次 /api/maps 就能看到新地图。
 *
 * 每次刷新都会对上一份列表做差集：新出现的记为「新地图」（工具栏给一条提示，
 * 用户点一下就切过去），正在看的那张被删掉则退回列表第一张——否则下拉框会停在空值上。
 */
async function refreshMaps(): Promise<void> {
  let next: MapSummary[];
  try {
    const resp = await fetch("/api/maps");
    if (!resp.ok) {
      return;
    }
    next = (await resp.json()) as MapSummary[];
  } catch {
    // 列表接口不可用时保持原列表，仍可凭 ?map= 直连
    return;
  }
  const known = new Set(maps.value.map((m) => m.id));
  if (mapsPrimed) {
    // 首次加载不算「新地图」，否则一进页面就提示一堆
    for (const map of next) {
      if (!known.has(map.id) && map.id !== activeMapId.value) {
        newMaps.value = [...newMaps.value, map];
      }
    }
  }
  mapsPrimed = true;
  newMaps.value = newMaps.value.filter((m) => next.some((n) => n.id === m.id));
  maps.value = next;
  const fallback = next[0];
  if (fallback && activeMapId.value
      && !next.some((m) => m.id === activeMapId.value)) {
    // 发布目录被删：当前地图已经加载不出来了，退到列表第一张
    activeMapId.value = fallback.id;
    await openMap(fallback.id);
  }
}

/** 上传弹窗里渲染完成后：刷新列表并直接切到新地图。 */
async function onRendered(mapId: string): Promise<void> {
  showUpload.value = false;
  await refreshMaps();
  forgetNewMap(mapId);
  if (mapId && mapId !== activeMapId.value) {
    activeMapId.value = mapId;
    await openMap(mapId);
  }
}

/** 关掉上传弹窗也要刷一次：渲染是后台跑的，关窗不等于没产物。 */
function onUploadClose(): void {
  showUpload.value = false;
  void refreshMaps();
}

/** 工具栏的「新地图」提示：点一下直接切过去。 */
function openLatestNewMap(): void {
  const target = newMaps.value[0];
  if (!target) {
    return;
  }
  activeMapId.value = target.id;
  void openMap(target.id);
}

/** 还有渲染任务在排队/执行吗（有就轮询得快一点）。 */
async function hasActiveRenderJob(): Promise<boolean> {
  try {
    const resp = await fetch("/api/render/jobs");
    if (!resp.ok) {
      return false;
    }
    const jobs = (await resp.json()) as { state: string }[];
    return jobs.some((j) => j.state === "QUEUED" || j.state === "RUNNING");
  } catch {
    return false;
  }
}

/**
 * 地图列表由后台渲染产物决定，前端不会收到通知，所以自己轮询：
 * 有任务在跑时 3s 一次（弹窗关了也在跑，进度得跟上），空闲时 15s 一次
 * （兜住服务端重启、别的终端触发渲染等「没被本页面看见」的产物）。
 * 页面不可见时跳过请求，回来时立刻补一次。
 */
async function scheduleMapSync(): Promise<void> {
  window.clearTimeout(mapSyncTimer);
  if (document.visibilityState === "visible") {
    await refreshMaps();
  }
  const delay = (await hasActiveRenderJob()) ? 3000 : 15000;
  mapSyncTimer = window.setTimeout(() => void scheduleMapSync(), delay);
}

/** 切回本页时立刻对一次账，不用等下一个轮询周期。 */
function onVisibilityChange(): void {
  if (document.visibilityState === "visible") {
    void scheduleMapSync();
  }
}

// 设置实时生效
watch(
  () => settings.sensitivity,
  (v) => {
    const c = controls as { sensitivity?: number } | null;
    if (c) c.sensitivity = v;
  },
);
watch(
  () => settings.flySpeed,
  (v) => {
    if (controls instanceof FreeFlightControls) controls.speed = v;
  },
);
watch(
  () => settings.fov,
  (v) => {
    if (!engine) return;
    engine.camera.fov = v;
    engine.camera.updateProjectionMatrix();
  },
);

// 烘焙光照参数实时生效（共享 uniforms，改 value 即全场景刷新）
const SKY_DAY = new THREE.Color(0x87ceeb);
const SKY_NIGHT = new THREE.Color(0x0a0e1a);
watch(
  () => settings.skyLight,
  (v) => {
    LightingUniforms.skyLightStrength.value = v;
    if (engine) {
      // 天空底色随昼夜渐变
      engine.scene.background = SKY_DAY.clone().lerp(SKY_NIGHT, 1 - v);
    }
  },
);
watch(
  () => settings.blockLight,
  (v) => {
    LightingUniforms.blockLightStrength.value = v;
  },
);
watch(
  () => settings.aoStrength,
  (v) => {
    LightingUniforms.aoStrength.value = v;
  },
);
watch(
  () => settings.displayP3,
  (v) => {
    localStorage.setItem("yudream.displayP3", v ? "1" : "0");
    engine?.setDisplayP3(v);
  },
);
watch(
  () => settings.autoDistance,
  (v) => {
    localStorage.setItem("yudream.autoDistance", v ? "1" : "0");
    adaptive?.setEnabled(v);
    if (!v) {
      // 切手动：以当前实际视距为起点，避免跳变
      settings.distanceChunks = Math.min(32, Math.max(8, Math.round(adaptive?.chunks ?? 16)));
    }
  },
);
watch(
  () => settings.distanceChunks,
  (v) => {
    localStorage.setItem("yudream.distanceChunks", String(v));
    adaptive?.setChunks(v);
  },
);

onMounted(async () => {
  if (!canvasRef.value) return;
  engine = new MapEngine({ canvas: canvasRef.value, displayP3: settings.displayP3 });
  // 设备性能静态预判（结果带 localStorage 缓存）：决定初始视距与目标帧率
  deviceProfile.value = detectDeviceProfile(engine.renderer.getContext());
  // 诊断句柄：浏览器控制台/自动化可直接操作相机与场景
  (window as unknown as { __map: object }).__map = {
    get engine() { return engine; },
    get tileManager() { return tileManager; },
    get controls() { return controls; },
    get adaptive() { return adaptive; },
    deviceProfile,
    // 烘焙光照全局参数（天空光/方块光/AO 强度，调 value 即时生效）
    lighting: LightingUniforms,
    /** 排障：只看 hires / 只看 lod / 全部。每帧 update 会尊重此过滤。 */
    setLayer(filter: "all" | "hires" | "lod") {
      tileManager?.setLayerFilter(filter);
    },
    loadedByLevel() {
      return tileManager?.loadedByLevel();
    },
    /** 自动化定位：摆相机并让当前控制器吸收新朝向 */
    setView(px: number, py: number, pz: number, tx: number, ty: number, tz: number) {
      if (!engine) return;
      engine.camera.position.set(px, py, pz);
      engine.camera.lookAt(tx, ty, tz);
      controls?.dispose();
      controls = createControls(mode.value, new THREE.Vector3(tx, ty, tz));
    },
  };
  controls = createControls("flight");
  engine.addFrameHook((dt) => controls?.update(dt));
  engine.addFrameHook(() => {
    // 先重定基再调度瓦片：相机/场景平移后，目标点与瓦片逻辑同步对齐到世界坐标
    const delta = engine && floatingOrigin.maybeRebase(engine.camera, engine.scene);
    if (delta) {
      const targetHolder = controls as { target?: THREE.Vector3 } | null;
      targetHolder?.target?.sub(delta);
      tileManager?.setWorldOffset(floatingOrigin.origin);
    }
  });
  engine.addFrameHook(() => tileManager?.update(engine!.camera));
  engine.addFrameHook((dt, rawDt) => adaptive?.update(dt, rawDt));
  posTimer = window.setInterval(() => {
    if (!engine) return;
    const p = engine.camera.position;
    // HUD 显示世界坐标（渲染坐标 + 浮点原点；常规地图原点为 0）
    cameraPos.x = Math.round(p.x + floatingOrigin.origin.x);
    cameraPos.y = Math.round(p.y + floatingOrigin.origin.y);
    cameraPos.z = Math.round(p.z + floatingOrigin.origin.z);
  }, 150);

  // 地图列表：?map= 优先，其次 swust，最后列表第一张
  let initial = new URLSearchParams(window.location.search).get("map") ?? "";
  await refreshMaps();
  if (!initial || !maps.value.some((m) => m.id === initial)) {
    initial = maps.value.some((m) => m.id === "swust") ? "swust" : (maps.value[0]?.id ?? initial);
  }
  if (initial) {
    activeMapId.value = initial;
    await openMap(initial);
  } else {
    status.value = "error";
    errorMessage.value = "没有可用地图（/api/maps 为空）";
  }
  // 后台渲染产物由轮询带进列表：新地图会自己出现在下拉框里
  document.addEventListener("visibilitychange", onVisibilityChange);
  void scheduleMapSync();
});

onUnmounted(() => {
  window.clearInterval(posTimer);
  window.clearTimeout(mapSyncTimer);
  document.removeEventListener("visibilitychange", onVisibilityChange);
  adaptive = null;
  tileManager?.dispose();
  controls?.dispose();
  engine?.dispose();
  tileManager = null;
  controls = null;
  engine = null;
});
</script>

<template>
  <div class="layout">
    <canvas ref="canvasRef" class="viewport"></canvas>

    <!-- 顶部工具栏：地图选择 + 模式切换 + 设置 -->
    <div class="toolbar glass">
      <span class="brand">yudream<span class="brand-accent">voxelith</span></span>
      <select class="map-select" :value="activeMapId" @change="selectMap">
        <option v-for="m in maps" :key="m.id" :value="m.id">{{ m.name }}</option>
        <option v-if="!maps.length" disabled>{{ status === "loading" ? "加载中…" : "无地图" }}</option>
      </select>
      <!-- 删除当前地图：清掉发布产物，确认后执行；删完自动退回列表第一张 -->
      <button
        v-if="activeMapId"
        class="icon-btn danger"
        :disabled="deletingMap"
        :title="deletingMap ? '删除中…' : `删除地图「${maps.find((m) => m.id === activeMapId)?.name ?? ''}」`"
        @click="deleteActiveMap"
      >
        🗑
      </button>
      <!-- 后台渲染刚发布的地图：点一下直接切过去（列表本身也已自动刷新） -->
      <button
        v-if="newMaps.length"
        class="new-map-btn"
        :title="`新地图「${newMapLabel}」已渲染完成，点击打开`"
        @click="openLatestNewMap"
      >
        <span class="dot"></span>新地图：{{ newMapLabel }}
      </button>
      <div class="mode-group">
        <button
          v-for="(label, key) in MODE_LABELS"
          :key="key"
          class="mode-btn"
          :class="{ active: mode === key }"
          @click="switchMode(key as CameraMode)"
        >
          {{ label }}
        </button>
      </div>
      <button class="text-btn" title="上传存档并自定义渲染范围" @click="showUpload = true">
        <span class="plus">＋</span> 上传地图
      </button>
      <button class="icon-btn" :class="{ active: showSettings }" title="设置" @click="showSettings = !showSettings">
        ⚙
      </button>
    </div>

    <!-- 上传地图：上传存档 → 二维框选范围 → 后台渲染 -->
    <UploadDialog
      v-if="showUpload"
      :maps="maps"
      @close="onUploadClose"
      @rendered="onRendered"
    />

    <!-- 设置面板 -->
    <div v-if="showSettings" class="settings glass">
      <h3>设置</h3>
      <label>
        <span>鼠标灵敏度 <b>{{ (settings.sensitivity * 1000).toFixed(1) }}</b></span>
        <input v-model.number="settings.sensitivity" type="range" min="0.0003" max="0.004" step="0.0001" />
      </label>
      <label>
        <span>飞行速度 <b>{{ settings.flySpeed }}</b></span>
        <input v-model.number="settings.flySpeed" type="range" min="1" max="200" step="1" />
      </label>
      <label>
        <span>视场角 <b>{{ settings.fov }}°</b></span>
        <input v-model.number="settings.fov" type="range" min="50" max="110" step="1" />
      </label>
      <label>
        <span>地面高度 Y <b>{{ settings.groundY }}</b></span>
        <input v-model.number="settings.groundY" type="range" min="-64" max="128" step="1" />
      </label>
      <label>
        <span>天空光（昼夜） <b>{{ settings.skyLight.toFixed(2) }}</b></span>
        <input v-model.number="settings.skyLight" type="range" min="0" max="1" step="0.01" />
      </label>
      <label>
        <span>方块光 <b>{{ settings.blockLight.toFixed(2) }}</b></span>
        <input v-model.number="settings.blockLight" type="range" min="0" max="1.5" step="0.01" />
      </label>
      <label>
        <span>环境光遮蔽 AO <b>{{ settings.aoStrength.toFixed(2) }}</b></span>
        <input v-model.number="settings.aoStrength" type="range" min="0" max="1" step="0.01" />
      </label>
      <label v-if="p3Supported" class="toggle">
        <span>广色域 Display P3 <b>{{ settings.displayP3 ? "开" : "关" }}</b></span>
        <input v-model="settings.displayP3" type="checkbox" />
      </label>
      <label class="toggle">
        <span>
          自适应视距
          <b>{{ settings.autoDistance ? `自动 · ${currentDistance} 区块` : "手动" }}</b>
        </span>
        <input v-model="settings.autoDistance" type="checkbox" />
      </label>
      <label v-if="!settings.autoDistance">
        <span>视距 <b>{{ settings.distanceChunks }} 区块</b></span>
        <input v-model.number="settings.distanceChunks" type="range" min="8" max="32" step="1" />
      </label>
      <div v-if="deviceProfile" class="device-info">
        设备档位 {{ { high: "高", mid: "中", low: "低" }[deviceProfile.tier] }} ·
        {{ deviceProfile.cores }} 核 · {{ deviceProfile.memoryGb }}GB
      </div>
    </div>

    <!-- 底部：状态栏 + 操作提示。放进同一个弹性容器，避免两者各自绝对定位后互相压住 -->
    <div class="bottombar">
      <div class="statusbar glass">
        <template v-if="status === 'ready' && manifest">
          <span class="map-name">{{ manifest.name }}</span>
          <span class="dim">v{{ manifest.version }}</span>
          <span :class="{ warn: failedTiles > 0 }">
            瓦片 {{ loadedTiles }}/{{ manifest.tiles.length }}<template v-if="failedTiles">（失败 {{ failedTiles }}）</template>
          </span>
          <span class="dim">X {{ cameraPos.x }} · Y {{ cameraPos.y }} · Z {{ cameraPos.z }}</span>
          <span class="dim">视距 {{ currentDistance }} 区块{{ settings.autoDistance ? "（自动）" : "" }}</span>
        </template>
        <template v-else-if="status === 'loading'">加载清单中…</template>
        <template v-else>清单加载失败：{{ errorMessage }}</template>
      </div>

      <div class="hintbar glass" :title="modeHint">{{ modeHint }}</div>
    </div>
  </div>
</template>

<style>
html,
body,
#app {
  margin: 0;
  height: 100%;
  overflow: hidden;
  font-family: "Segoe UI", system-ui, sans-serif;
}

.layout {
  position: relative;
  height: 100%;
}

.viewport {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

/* 深色玻璃拟态面板 */
.glass {
  background: rgba(13, 18, 26, 0.68);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  color: #d7dde8;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}

/* 顶部工具栏 */
.toolbar {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 14px;
  user-select: none;
}

.brand {
  font-weight: 700;
  font-size: 14px;
  letter-spacing: 0.4px;
}

.brand-accent {
  color: #5eb1ff;
}

.map-select {
  background: rgba(255, 255, 255, 0.07);
  color: #d7dde8;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 13px;
  outline: none;
  cursor: pointer;
}

.map-select option {
  background: #141a24;
}

/* 后台渲染产物的提示：绿点 + 地图名，点一下切过去 */
.new-map-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  border: 1px solid rgba(88, 200, 130, 0.45);
  background: rgba(88, 200, 130, 0.14);
  color: #8ee0ac;
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.15s;
}

.new-map-btn:hover {
  background: rgba(88, 200, 130, 0.26);
  color: #b6f0cb;
}

.new-map-btn .dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: #58c882;
  box-shadow: 0 0 6px rgba(88, 200, 130, 0.9);
}

.mode-group {
  display: flex;
  gap: 4px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  padding: 3px;
}

.mode-btn {
  border: none;
  background: transparent;
  color: #9aa4b5;
  font-size: 13px;
  padding: 5px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.mode-btn:hover {
  color: #e6ebf4;
}

.mode-btn.active {
  background: #2f6fd0;
  color: #fff;
}

.icon-btn {
  border: none;
  background: transparent;
  color: #9aa4b5;
  font-size: 16px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.icon-btn:hover,
.icon-btn.active {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}

.icon-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

/* 删除当前地图：危险操作，hover 用红色而不是常规高亮 */
.icon-btn.danger:hover:not(:disabled) {
  color: #ff9f9f;
  background: rgba(240, 110, 110, 0.16);
}

/* 设置面板 */
.settings {
  position: absolute;
  top: 64px;
  right: 12px;
  width: 240px;
  padding: 14px 16px;
}

.settings h3 {
  margin: 0 0 10px;
  font-size: 13px;
  color: #8b93a3;
  font-weight: 600;
  letter-spacing: 1px;
}

.settings label {
  display: block;
  margin-bottom: 12px;
  font-size: 13px;
}

.settings label span {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  color: #aab3c2;
}

.settings label b {
  color: #e6ebf4;
  font-weight: 600;
}

.settings input[type="range"] {
  width: 100%;
  accent-color: #2f6fd0;
}

.settings label.toggle input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: #2f6fd0;
  cursor: pointer;
}

.settings .device-info {
  margin-top: -4px;
  font-size: 11.5px;
  color: #8b93a3;
}

/* 底部条：状态栏（左）与操作提示（右）同一个弹性行，天然不重叠；
   窄屏放不下时改为上下堆叠，而不是互相压住 */
.bottombar {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 12px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  pointer-events: none;
}

/* 容器不拦事件（别挡住 3D 画布的拖拽），但两块浮窗自身要能接收 hover/选中 */
.statusbar,
.hintbar {
  pointer-events: auto;
}

.statusbar {
  display: flex;
  gap: 14px;
  align-items: baseline;
  padding: 7px 14px;
  font-size: 12.5px;
  white-space: nowrap;
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
}

.map-name {
  font-weight: 600;
  /* 窄屏时优先压缩地图名，而不是整条状态栏被裁掉半截 */
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dim {
  color: #8b93a3;
}

.warn {
  color: #f0a35e;
}

/* 底部提示 */
.hintbar {
  padding: 6px 16px;
  font-size: 12px;
  color: #8b93a3;
  flex: 0 1 auto;
  min-width: 0;
  max-width: 48%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .bottombar {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }

  .hintbar {
    max-width: 100%;
  }
}

/* 顶部工具栏的上传入口 */
.text-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid rgba(94, 177, 255, 0.45);
  background: rgba(94, 177, 255, 0.12);
  color: #cfe4ff;
  font-size: 13px;
  padding: 5px 11px;
  border-radius: 7px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.text-btn:hover {
  background: rgba(94, 177, 255, 0.24);
  color: #fff;
}

.text-btn .plus {
  font-weight: 700;
}
</style>
