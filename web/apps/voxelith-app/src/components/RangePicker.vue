<script lang="ts">
/** 渲染范围（方块坐标，含端点）——与管线参数 minX/maxX/minZ/maxZ 一一对应。 */
export interface BlockRange {
  minX: number;
  maxX: number;
  minZ: number;
  maxZ: number;
}
</script>

<script setup lang="ts">
/**
 * 二维地图框选：把存档地表预览图铺成一张可缩放/平移的俯视图，
 * 用鼠标拖出一个矩形来定义渲染范围（对应管线里的 minX/maxX/minZ/maxZ）。
 *
 * 交互按 Xaero 小地图的习惯来：
 * - 左键拖空白处 = 拉出新框选；框内拖动 = 平移选区；八个把手 = 改边
 * - 滚轮 = 以光标为中心缩放；中键 / 右键 / Shift+左键 = 平移视图
 * - 叠加 region（512 方块）与区块（16 方块）网格，便于对齐到整数网格
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import type { PreviewMeta } from "../api";

const props = defineProps<{
  imageUrl: string;
  meta: PreviewMeta;
  modelValue: BlockRange;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: BlockRange): void;
  (e: "hover", value: { x: number; z: number } | null): void;
}>();

/** 原版 region 边长（方块）：框选对齐到它就能避免多渲染出边角。 */
const REGION_BLOCKS = 512;
/** 区块边长（方块）。 */
const CHUNK_BLOCKS = 16;

const canvasRef = ref<HTMLCanvasElement | null>(null);
const showRegionGrid = ref(true);
const showChunkGrid = ref(true);
const cursor = ref<{ x: number; z: number } | null>(null);
const hoverValid = ref(false);

/** 视图：画布左上角对应的方块坐标 + 每方块占多少 CSS 像素。 */
const view = reactive({ x: 0, z: 0, scale: 1 });

let image: HTMLImageElement | null = null;
let imageReady = false;
let fitScale = 1;
let rafId = 0;
let observer: ResizeObserver | null = null;
/** 上一次的画布尺寸：容器变化时用它算出「原来的中心」再回正。 */
let lastSize = { width: 0, height: 0 };
/** 当前视图是否已按整图铺过；换图后置回 false，等图加载完重新铺。 */
let fitted = false;

type DragMode = "new" | "move" | "resize" | "pan";
type Handle = "nw" | "n" | "ne" | "e" | "se" | "s" | "sw" | "w";

interface DragState {
  mode: DragMode;
  handle: Handle | null;
  /** 按下时的屏幕坐标（CSS 像素） */
  screenX: number;
  screenY: number;
  /** 按下时的方块坐标 */
  blockX: number;
  blockZ: number;
  /** 按下时的视图原点与选区快照 */
  viewX: number;
  viewZ: number;
  origin: BlockRange;
}

let drag: DragState | null = null;

const selection = computed(() => normalize(props.modelValue));
const selectionWidth = computed(() => selection.value.maxX - selection.value.minX + 1);
const selectionDepth = computed(() => selection.value.maxZ - selection.value.minZ + 1);

const zoomLabel = computed(() => {
  const px = view.scale;
  if (px >= 1) return `${px.toFixed(2)} px/格`;
  return `${(1 / px).toFixed(1)} 格/px`;
});

function normalize(range: BlockRange): BlockRange {
  return {
    minX: Math.min(range.minX, range.maxX),
    maxX: Math.max(range.minX, range.maxX),
    minZ: Math.min(range.minZ, range.maxZ),
    maxZ: Math.max(range.minZ, range.maxZ),
  };
}

function clampX(value: number): number {
  return Math.max(props.meta.blockMinX, Math.min(props.meta.blockMaxX, Math.round(value)));
}

function clampZ(value: number): number {
  return Math.max(props.meta.blockMinZ, Math.min(props.meta.blockMaxZ, Math.round(value)));
}

function cssSize(): { width: number; height: number } {
  const canvas = canvasRef.value;
  return { width: canvas?.clientWidth ?? 0, height: canvas?.clientHeight ?? 0 };
}

function blockToScreenX(blockX: number): number {
  return (blockX - view.x) * view.scale;
}

function blockToScreenZ(blockZ: number): number {
  return (blockZ - view.z) * view.scale;
}

function screenToBlock(screenX: number, screenY: number): { x: number; z: number } {
  return { x: view.x + screenX / view.scale, z: view.z + screenY / view.scale };
}

/** 世界方块范围（用于把视图限制在内容附近，避免拖到空无一物的地方）。 */
function worldSpan(): { minX: number; maxX: number; minZ: number; maxZ: number } {
  const width = props.meta.width * props.meta.step;
  const depth = props.meta.depth * props.meta.step;
  return {
    minX: props.meta.originX,
    maxX: props.meta.originX + width,
    minZ: props.meta.originZ,
    maxZ: props.meta.originZ + depth,
  };
}

/** 适应窗口：整图留 4% 边距铺满。 */
function fit(): void {
  const { width, height } = cssSize();
  if (width <= 0 || height <= 0) return;
  const span = worldSpan();
  const worldW = span.maxX - span.minX;
  const worldD = span.maxZ - span.minZ;
  fitScale = Math.min(width / worldW, height / worldD) * 0.96;
  view.scale = fitScale;
  view.x = span.minX - (width / view.scale - worldW) / 2;
  view.z = span.minZ - (height / view.scale - worldD) / 2;
  fitted = true;
  lastSize = { width, height };
  schedule();
}

function zoomBy(factor: number): void {
  const { width, height } = cssSize();
  const center = screenToBlock(width / 2, height / 2);
  applyZoom(factor, width / 2, height / 2, center);
}

function applyZoom(factor: number, anchorScreenX: number, anchorScreenY: number,
                   anchorBlock: { x: number; z: number }): void {
  const next = Math.max(fitScale * 0.2, Math.min(16, view.scale * factor));
  view.scale = next;
  // 让锚点方块在缩放后仍停在原屏幕位置
  view.x = anchorBlock.x - anchorScreenX / next;
  view.z = anchorBlock.z - anchorScreenY / next;
  schedule();
}

/** 选区整体对齐到指定网格（region 512 / 区块 16）。 */
function snapSelection(grid: number): void {
  const current = selection.value;
  const minX = Math.floor(current.minX / grid) * grid;
  const minZ = Math.floor(current.minZ / grid) * grid;
  const maxX = Math.ceil((current.maxX + 1) / grid) * grid - 1;
  const maxZ = Math.ceil((current.maxZ + 1) / grid) * grid - 1;
  update({ minX: clampX(minX), maxX: clampX(maxX), minZ: clampZ(minZ), maxZ: clampZ(maxZ) });
}

function selectAll(): void {
  update({
    minX: props.meta.blockMinX,
    maxX: props.meta.blockMaxX,
    minZ: props.meta.blockMinZ,
    maxZ: props.meta.blockMaxZ,
  });
}

function update(range: BlockRange): void {
  const next = normalize(range);
  if (next.minX === props.modelValue.minX && next.maxX === props.modelValue.maxX
      && next.minZ === props.modelValue.minZ && next.maxZ === props.modelValue.maxZ) {
    return;
  }
  emit("update:modelValue", next);
}

function schedule(): void {
  if (rafId) return;
  rafId = window.requestAnimationFrame(() => {
    rafId = 0;
    draw();
  });
}

function draw(): void {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const dpr = window.devicePixelRatio || 1;
  const { width, height } = cssSize();
  if (width <= 0 || height <= 0) return;
  if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = "#0b1017";
  ctx.fillRect(0, 0, width, height);

  const span = worldSpan();
  const x0 = blockToScreenX(span.minX);
  const y0 = blockToScreenZ(span.minZ);
  const w = (span.maxX - span.minX) * view.scale;
  const h = (span.maxZ - span.minZ) * view.scale;

  if (imageReady && image) {
    // 放大到「1 图素 ≥ 2 屏素」时关掉平滑，保持像素感（Xaero 的观感）
    ctx.imageSmoothingEnabled = view.scale * props.meta.step >= 2;
    ctx.drawImage(image, x0, y0, w, h);
  } else {
    ctx.fillStyle = "rgba(255,255,255,0.05)";
    ctx.fillRect(x0, y0, w, h);
  }

  drawGrid(ctx, width, height);
  drawSelection(ctx);
  drawHud(ctx, width, height);
}

function drawGrid(ctx: CanvasRenderingContext2D, width: number, height: number): void {
  const span = worldSpan();
  const left = view.x;
  const right = view.x + width / view.scale;
  const top = view.z;
  const bottom = view.z + height / view.scale;

  if (showRegionGrid.value && view.scale * REGION_BLOCKS > 40) {
    ctx.strokeStyle = "rgba(255,255,255,0.22)";
    ctx.lineWidth = 1;
    ctx.font = "11px 'Segoe UI', system-ui, sans-serif";
    ctx.fillStyle = "rgba(255,255,255,0.45)";
    const startX = Math.floor(Math.max(left, span.minX) / REGION_BLOCKS) * REGION_BLOCKS;
    for (let bx = startX; bx <= Math.min(right, span.maxX); bx += REGION_BLOCKS) {
      const sx = Math.round(blockToScreenX(bx)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(sx, Math.max(0, blockToScreenZ(span.minZ)));
      ctx.lineTo(sx, Math.min(height, blockToScreenZ(span.maxZ)));
      ctx.stroke();
    }
    const startZ = Math.floor(Math.max(top, span.minZ) / REGION_BLOCKS) * REGION_BLOCKS;
    for (let bz = startZ; bz <= Math.min(bottom, span.maxZ); bz += REGION_BLOCKS) {
      const sy = Math.round(blockToScreenZ(bz)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(Math.max(0, blockToScreenX(span.minX)), sy);
      ctx.lineTo(Math.min(width, blockToScreenX(span.maxX)), sy);
      ctx.stroke();
    }
    // region 标号：只在格子够大时画，避免糊成一片
    for (const region of props.meta.regions) {
      const [rx = 0, rz = 0] = region;
      const bx = rx * REGION_BLOCKS;
      const bz = rz * REGION_BLOCKS;
      const sx = blockToScreenX(bx);
      const sy = blockToScreenZ(bz);
      if (sx < -80 || sy < -20 || sx > width || sy > height) continue;
      if (view.scale * REGION_BLOCKS < 80) continue;
      ctx.fillStyle = "rgba(255,255,255,0.5)";
      ctx.fillText(`r.${rx}.${rz}`, sx + 6, sy + 15);
    }
  }

  if (showChunkGrid.value && view.scale * CHUNK_BLOCKS > 8) {
    ctx.strokeStyle = "rgba(255,255,255,0.07)";
    ctx.lineWidth = 1;
    const startX = Math.floor(Math.max(left, span.minX) / CHUNK_BLOCKS) * CHUNK_BLOCKS;
    for (let bx = startX; bx <= Math.min(right, span.maxX); bx += CHUNK_BLOCKS) {
      const sx = Math.round(blockToScreenX(bx)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(sx, Math.max(0, blockToScreenZ(span.minZ)));
      ctx.lineTo(sx, Math.min(height, blockToScreenZ(span.maxZ)));
      ctx.stroke();
    }
    const startZ = Math.floor(Math.max(top, span.minZ) / CHUNK_BLOCKS) * CHUNK_BLOCKS;
    for (let bz = startZ; bz <= Math.min(bottom, span.maxZ); bz += CHUNK_BLOCKS) {
      const sy = Math.round(blockToScreenZ(bz)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(Math.max(0, blockToScreenX(span.minX)), sy);
      ctx.lineTo(Math.min(width, blockToScreenX(span.maxX)), sy);
      ctx.stroke();
    }
  }
}

function selectionScreenRect(): { x: number; y: number; w: number; h: number } {
  const sel = selection.value;
  const x = blockToScreenX(sel.minX);
  const y = blockToScreenZ(sel.minZ);
  return {
    x,
    y,
    w: (sel.maxX - sel.minX + 1) * view.scale,
    h: (sel.maxZ - sel.minZ + 1) * view.scale,
  };
}

function drawSelection(ctx: CanvasRenderingContext2D): void {
  const rect = selectionScreenRect();
  // 选区外压暗，让范围一眼可辨
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, cssSize().width, cssSize().height);
  ctx.rect(rect.x, rect.y, rect.w, rect.h);
  ctx.fillStyle = "rgba(6,10,16,0.45)";
  ctx.fill("evenodd");
  ctx.restore();

  ctx.fillStyle = "rgba(94,177,255,0.16)";
  ctx.fillRect(rect.x, rect.y, rect.w, rect.h);
  ctx.strokeStyle = "#5eb1ff";
  ctx.lineWidth = 2;
  ctx.strokeRect(rect.x + 1, rect.y + 1, Math.max(rect.w - 2, 1), Math.max(rect.h - 2, 1));

  // 八个把手
  ctx.fillStyle = "#eaf3ff";
  ctx.strokeStyle = "#2f6fd0";
  ctx.lineWidth = 1;
  for (const point of handlePoints(rect)) {
    ctx.beginPath();
    ctx.rect(point.x - 4, point.y - 4, 8, 8);
    ctx.fill();
    ctx.stroke();
  }
}

function handlePoints(rect: { x: number; y: number; w: number; h: number }):
Array<{ handle: Handle; x: number; y: number }> {
  const cx = rect.x + rect.w / 2;
  const cy = rect.y + rect.h / 2;
  const r = rect.x + rect.w;
  const b = rect.y + rect.h;
  return [
    { handle: "nw", x: rect.x, y: rect.y },
    { handle: "n", x: cx, y: rect.y },
    { handle: "ne", x: r, y: rect.y },
    { handle: "e", x: r, y: cy },
    { handle: "se", x: r, y: b },
    { handle: "s", x: cx, y: b },
    { handle: "sw", x: rect.x, y: b },
    { handle: "w", x: rect.x, y: cy },
  ];
}

function drawHud(ctx: CanvasRenderingContext2D, width: number, height: number): void {
  const lines: string[] = [];
  if (cursor.value) {
    lines.push(`X ${Math.round(cursor.value.x)}  Z ${Math.round(cursor.value.z)}`);
  } else {
    lines.push("把光标移到地图上读取坐标");
  }
  lines.push(`选区 ${selectionWidth.value} × ${selectionDepth.value} 方块`
    + `（${Math.ceil(selectionWidth.value / CHUNK_BLOCKS)} × ${Math.ceil(selectionDepth.value / CHUNK_BLOCKS)} 区块）`);

  ctx.font = "12px 'Segoe UI', system-ui, sans-serif";
  const pad = 8;
  const boxWidth = Math.max(...lines.map((l) => ctx.measureText(l).width)) + pad * 2;
  const boxHeight = lines.length * 16 + pad * 2 - 4;
  ctx.fillStyle = "rgba(10,15,22,0.78)";
  ctx.fillRect(10, 10, boxWidth, boxHeight);
  ctx.strokeStyle = "rgba(255,255,255,0.1)";
  ctx.strokeRect(10.5, 10.5, boxWidth - 1, boxHeight - 1);
  ctx.fillStyle = "#d7dde8";
  lines.forEach((line, i) => ctx.fillText(line, 10 + pad, 10 + pad + 10 + i * 16));

  // 比例尺：取一个「整」的方块长度
  const targets = [16, 32, 64, 128, 256, 512, 1024, 2048, 4096];
  const target = targets.find((t) => t * view.scale >= 60) ?? 8192;
  const barWidth = target * view.scale;
  const barX = width - barWidth - 16;
  const barY = height - 20;
  if (barX > 0) {
    ctx.strokeStyle = "rgba(255,255,255,0.75)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(barX, barY);
    ctx.lineTo(barX + barWidth, barY);
    ctx.moveTo(barX, barY - 5);
    ctx.lineTo(barX, barY + 5);
    ctx.moveTo(barX + barWidth, barY - 5);
    ctx.lineTo(barX + barWidth, barY + 5);
    ctx.stroke();
    ctx.fillStyle = "rgba(255,255,255,0.75)";
    ctx.font = "11px 'Segoe UI', system-ui, sans-serif";
    const label = `${target} 方块`;
    ctx.fillText(label, barX + barWidth / 2 - ctx.measureText(label).width / 2, barY - 8);
  }

  if (!hoverValid.value && cursor.value) {
    ctx.fillStyle = "rgba(240,163,94,0.9)";
    ctx.font = "11px 'Segoe UI', system-ui, sans-serif";
    ctx.fillText("超出有内容区域", 12, height - 10);
  }
}

function localPoint(e: PointerEvent | WheelEvent): { x: number; y: number } {
  const canvas = canvasRef.value;
  if (!canvas) return { x: 0, y: 0 };
  const rect = canvas.getBoundingClientRect();
  return { x: e.clientX - rect.left, y: e.clientY - rect.top };
}

function hitHandle(screenX: number, screenY: number): Handle | null {
  const rect = selectionScreenRect();
  const tolerance = 7;
  for (const point of handlePoints(rect)) {
    if (Math.abs(screenX - point.x) <= tolerance && Math.abs(screenY - point.y) <= tolerance) {
      return point.handle;
    }
  }
  return null;
}

function insideSelection(screenX: number, screenY: number): boolean {
  const rect = selectionScreenRect();
  return screenX >= rect.x && screenX <= rect.x + rect.w
    && screenY >= rect.y && screenY <= rect.y + rect.h;
}

function onPointerDown(e: PointerEvent): void {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const point = localPoint(e);
  const block = screenToBlock(point.x, point.y);
  const origin = { ...selection.value };
  let mode: DragMode;
  let handle: Handle | null = null;
  if (e.button === 1 || e.button === 2 || e.shiftKey) {
    mode = "pan";
  } else {
    handle = hitHandle(point.x, point.y);
    if (handle) {
      mode = "resize";
    } else if (insideSelection(point.x, point.y)) {
      mode = "move";
    } else {
      mode = "new";
    }
  }
  drag = {
    mode,
    handle,
    screenX: point.x,
    screenY: point.y,
    blockX: block.x,
    blockZ: block.z,
    viewX: view.x,
    viewZ: view.z,
    origin,
  };
  canvas.setPointerCapture(e.pointerId);
  if (mode === "new") {
    // 拉新框时先给一个 1×1 的种子，避免中间态是「负范围」
    update({
      minX: clampX(block.x),
      maxX: clampX(block.x),
      minZ: clampZ(block.z),
      maxZ: clampZ(block.z),
    });
  }
  e.preventDefault();
}

function onPointerMove(e: PointerEvent): void {
  const point = localPoint(e);
  const block = screenToBlock(point.x, point.y);
  cursor.value = { x: block.x, z: block.z };
  hoverValid.value = block.x >= props.meta.blockMinX && block.x <= props.meta.blockMaxX
    && block.z >= props.meta.blockMinZ && block.z <= props.meta.blockMaxZ;
  emit("hover", hoverValid.value ? { x: Math.round(block.x), z: Math.round(block.z) } : null);

  if (!drag) {
    return;
  }
  if (drag.mode === "pan") {
    view.x = drag.viewX - (point.x - drag.screenX) / view.scale;
    view.z = drag.viewZ - (point.y - drag.screenY) / view.scale;
    schedule();
    return;
  }
  const next = { ...drag.origin };
  if (drag.mode === "new") {
    next.minX = clampX(drag.blockX);
    next.maxX = clampX(block.x);
    next.minZ = clampZ(drag.blockZ);
    next.maxZ = clampZ(block.z);
  } else if (drag.mode === "move") {
    const dx = Math.round(block.x - drag.blockX);
    const dz = Math.round(block.z - drag.blockZ);
    const width = drag.origin.maxX - drag.origin.minX;
    const depth = drag.origin.maxZ - drag.origin.minZ;
    // 整体平移：碰到边界就贴边，不改变尺寸
    const minX = Math.max(props.meta.blockMinX, Math.min(props.meta.blockMaxX - width, drag.origin.minX + dx));
    const minZ = Math.max(props.meta.blockMinZ, Math.min(props.meta.blockMaxZ - depth, drag.origin.minZ + dz));
    next.minX = minX;
    next.maxX = minX + width;
    next.minZ = minZ;
    next.maxZ = minZ + depth;
  } else if (drag.mode === "resize" && drag.handle) {
    const handle = drag.handle;
    if (handle.includes("w")) next.minX = clampX(block.x);
    if (handle.includes("e")) next.maxX = clampX(block.x);
    if (handle.includes("n")) next.minZ = clampZ(block.z);
    if (handle.includes("s")) next.maxZ = clampZ(block.z);
  }
  update(next);
}

function onPointerUp(e: PointerEvent): void {
  const canvas = canvasRef.value;
  if (canvas && canvas.hasPointerCapture(e.pointerId)) {
    canvas.releasePointerCapture(e.pointerId);
  }
  drag = null;
}

function onWheel(e: WheelEvent): void {
  const point = localPoint(e);
  const anchor = screenToBlock(point.x, point.y);
  const factor = Math.exp(-e.deltaY * 0.0015);
  applyZoom(factor, point.x, point.y, anchor);
  e.preventDefault();
}

function onPointerLeave(): void {
  cursor.value = null;
  hoverValid.value = false;
  emit("hover", null);
}

function loadImage(): void {
  imageReady = false;
  fitted = false;
  const next = new Image();
  next.onload = () => {
    image = next;
    imageReady = true;
    fit();
  };
  next.onerror = () => {
    imageReady = false;
    schedule();
  };
  next.src = props.imageUrl;
}

onMounted(() => {
  const canvas = canvasRef.value;
  if (!canvas) return;
  canvas.addEventListener("contextmenu", (e) => e.preventDefault());
  lastSize = cssSize();
  observer = new ResizeObserver(() => {
    const size = cssSize();
    if (size.width <= 0 || size.height <= 0) {
      return;
    }
    if (!fitted) {
      // 弹窗刚展开时画布尺寸从 0 变有值，此时才真正能铺满整图
      if (imageReady) {
        fit();
      }
      lastSize = size;
      return;
    }
    // 容器尺寸变化时保持视口中心不动
    const center = screenToBlock(lastSize.width / 2, lastSize.height / 2);
    view.x = center.x - size.width / 2 / view.scale;
    view.z = center.z - size.height / 2 / view.scale;
    lastSize = size;
    schedule();
  });
  observer.observe(canvas);
  loadImage();
});

onBeforeUnmount(() => {
  observer?.disconnect();
  if (rafId) {
    window.cancelAnimationFrame(rafId);
  }
});

watch(() => props.imageUrl, loadImage);
watch(() => props.meta, () => fit(), { deep: false });
watch(() => [props.modelValue.minX, props.modelValue.maxX, props.modelValue.minZ, props.modelValue.maxZ],
  () => schedule());

defineExpose({ fit, zoomBy, snapSelection, selectAll, refresh: schedule });
</script>

<template>
  <div class="picker">
    <canvas
      ref="canvasRef"
      class="picker-canvas"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerLeave"
      @wheel="onWheel"
    ></canvas>
    <div class="picker-tools">
      <button type="button" title="放大" @click="zoomBy(1.4)">＋</button>
      <button type="button" title="缩小" @click="zoomBy(1 / 1.4)">－</button>
      <button type="button" title="适应窗口" @click="fit()">⤢</button>
      <span class="divider"></span>
      <button
        type="button"
        :class="{ on: showRegionGrid }"
        title="显示/隐藏 region 网格（512 方块）"
        @click="showRegionGrid = !showRegionGrid"
      >region</button>
      <button
        type="button"
        :class="{ on: showChunkGrid }"
        title="显示/隐藏区块网格（16 方块）"
        @click="showChunkGrid = !showChunkGrid"
      >区块</button>
      <span class="divider"></span>
      <button type="button" title="对齐到 region 边界（512 方块）" @click="snapSelection(512)">对齐 region</button>
      <button type="button" title="对齐到区块边界（16 方块）" @click="snapSelection(16)">对齐区块</button>
      <button type="button" title="选中全部有内容的范围" @click="selectAll()">全选</button>
      <span class="zoom">{{ zoomLabel }}</span>
    </div>
  </div>
</template>

<style scoped>
.picker {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.picker-canvas {
  width: 100%;
  height: 100%;
  min-height: 320px;
  display: block;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: #0b1017;
  cursor: crosshair;
  touch-action: none;
}

.picker-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #9aa4b5;
}

.picker-tools button {
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.05);
  color: #b9c2d0;
  border-radius: 6px;
  padding: 4px 9px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.15s;
}

.picker-tools button:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
}

.picker-tools button.on {
  background: #2f6fd0;
  border-color: #2f6fd0;
  color: #fff;
}

.picker-tools .divider {
  width: 1px;
  height: 16px;
  background: rgba(255, 255, 255, 0.12);
  margin: 0 4px;
}

.picker-tools .zoom {
  margin-left: auto;
  color: #8b93a3;
  font-variant-numeric: tabular-nums;
}
</style>
