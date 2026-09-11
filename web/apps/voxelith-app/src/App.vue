<script setup lang="ts">
import {
  AdaptiveDistance,
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
} from "@yudream/voxelith-viewer";
import type { MapManifest } from "@yudream/voxelith-core";
import * as THREE from "three";
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";

interface MapSummary {
  id: string;
  name: string;
  version: string;
}

const MODE_LABELS: Record<CameraMode, string> = {
  flight: "自由飞行",
  firstPerson: "第一人称",
  tiltOrbit: "俯视倾斜",
};

const MODE_HINTS: Record<CameraMode, string> = {
  flight: "点击画面锁定鼠标 · WASD 移动 · Space/Ctrl 升降 · Shift 加速 · 滚轮调速 · Esc 释放鼠标",
  firstPerson: "点击画面锁定鼠标 · WASD 行走 · Space 跳跃 · Shift 疾跑 · Esc 释放鼠标",
  tiltOrbit: "左键拖拽/WASD 移动 · 右键拖拽/Alt+WASD 旋转倾斜 · 滚轮/+- 缩放",
};

const canvasRef = ref<HTMLCanvasElement | null>(null);
const maps = ref<MapSummary[]>([]);
const activeMapId = ref("");
const manifest = ref<MapManifest | null>(null);
const loadedTiles = ref(0);
const failedTiles = ref(0);
const status = ref<"loading" | "ready" | "error">("loading");
const errorMessage = ref("");
const mode = ref<CameraMode>("flight");
const showSettings = ref(false);
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

const modeHint = computed(() => MODE_HINTS[mode.value]);

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
  status.value = "loading";
  loadedTiles.value = 0;
  failedTiles.value = 0;
  tileManager?.dispose();
  tileManager = null;
  // 场景与相机随即按世界坐标重建，渲染原点归零
  floatingOrigin.reset();
  try {
    const m = await loadManifest(`/maps/${mapId}`);
    manifest.value = m;
    const cx = (m.boundsMin[0] + m.boundsMax[0]) / 2;
    const cz = (m.boundsMin[2] + m.boundsMax[2]) / 2;
    engine.camera.position.set(cx, m.boundsMax[1] + 60, cz + 80);
    engine.camera.lookAt(cx, m.boundsMax[1], cz);
    // 重建当前控制器以吸收新机位朝向
    controls?.dispose();
    controls = createControls(mode.value);
    tileManager = new TileManager({
      scene: engine.scene,
      mapBaseUrl: `/maps/${mapId}`,
      manifest: m,
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
  engine.addFrameHook((dt) => adaptive?.update(dt));
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
  try {
    const resp = await fetch("/api/maps");
    if (resp.ok) {
      maps.value = (await resp.json()) as MapSummary[];
    }
  } catch {
    // 列表接口不可用时仍可凭 ?map= 直连
  }
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
});

onUnmounted(() => {
  window.clearInterval(posTimer);
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
      <button class="icon-btn" :class="{ active: showSettings }" title="设置" @click="showSettings = !showSettings">
        ⚙
      </button>
    </div>

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

    <!-- 左下状态栏 -->
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

    <!-- 底部操作提示 -->
    <div class="hintbar glass">{{ modeHint }}</div>
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

/* 左下状态栏 */
.statusbar {
  position: absolute;
  left: 12px;
  bottom: 12px;
  display: flex;
  gap: 14px;
  align-items: baseline;
  padding: 7px 14px;
  font-size: 12.5px;
  pointer-events: none;
  white-space: nowrap;
}

.map-name {
  font-weight: 600;
}

.dim {
  color: #8b93a3;
}

.warn {
  color: #f0a35e;
}

/* 底部提示 */
.hintbar {
  position: absolute;
  bottom: 12px;
  left: 50%;
  transform: translateX(-50%);
  padding: 6px 16px;
  font-size: 12px;
  color: #8b93a3;
  pointer-events: none;
  white-space: nowrap;
}
</style>
