<script setup lang="ts">
/**
 * 上传地图 → 框选范围 → 渲染：把「上传存档 + 手填 region 窗口」这套命令行流程搬进网页。
 *
 * 四步：选择存档（上传压缩包 / 指向本机目录 / 复用已登记）→ 二维框选渲染范围 →
 * 渲染设置 → 后台渲染（阶段进度 + 实时日志）。范围最终落到管线的 minX/maxX/minZ/maxZ。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import * as api from "../api";
import type { PreviewMeta, PreviewStatus, RenderJob, UploadDetail, WorldUpload } from "../api";
import RangePicker from "./RangePicker.vue";
import type { BlockRange } from "./RangePicker.vue";

const props = defineProps<{ maps: { id: string; name: string }[] }>();

const emit = defineEmits<{
  (e: "close"): void;
  (e: "rendered", mapId: string): void;
}>();

const DIMENSION_LABELS: Record<string, string> = {
  "minecraft:overworld": "主世界",
  "minecraft:the_nether": "下界",
  "minecraft:the_end": "末地",
};

const step = ref(1);
const busy = ref(false);
const error = ref("");

// ---- 步骤一：选择存档 ----
const sourceTab = ref<"archive" | "local" | "existing">("archive");
const uploads = ref<WorldUpload[]>([]);
const activeUpload = ref<WorldUpload | null>(null);
const detail = ref<UploadDetail | null>(null);

const archiveFile = ref<File | null>(null);
const archiveName = ref("");
/** 名称是自动填的（跟随文件名）还是用户改过：改过之后换文件不再覆盖。 */
const nameAuto = ref(true);
const uploadRatio = ref(0);
const dragActive = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

const localPath = ref("");
const localName = ref("");
const candidates = ref<{ path: string; name: string }[]>([]);

// ---- 步骤二：维度 + 预览 + 框选 ----
const dimension = ref("minecraft:overworld");
const preview = ref<PreviewStatus | null>(null);
const range = ref<BlockRange>({ minX: 0, maxX: 0, minZ: 0, maxZ: 0 });
const pickerRef = ref<InstanceType<typeof RangePicker> | null>(null);

// ---- 步骤三：渲染设置 ----
const form = reactive({
  mapId: "",
  mapName: "",
  minY: -64,
  maxLevel: 0,
  lodAtlas: true,
  packs: "",
  modelsFile: "",
});
const defaults = ref<api.RenderDefaults | null>(null);
const showAdvanced = ref(false);
/** 用户手动改过 minY 之后就不再被预览建议值覆盖。 */
const minYTouched = ref(false);

// ---- 步骤四：渲染任务 ----
const job = ref<RenderJob | null>(null);
/** 与渲染服务的连接断了（网络层失败），正在重试——不是渲染失败，别吓用户 */
const jobStalled = ref(false);
const logLines = ref<string[]>([]);
const logSince = ref(0);
const logBox = ref<HTMLDivElement | null>(null);

let previewTimer = 0;
let jobTimer = 0;
/** 连续多少次网络层失败后提示「连接中断」（一两次抖动不打扰用户） */
let jobFetchFailures = 0;

const dimensionOptions = computed(() => activeUpload.value?.dimensions ?? []);
const currentStats = computed(() => detail.value?.dimensionStats[dimension.value] ?? null);
const previewMeta = computed<PreviewMeta | null>(() =>
  preview.value?.state === "READY" ? preview.value.meta : null);
const previewReady = computed(() => previewMeta.value !== null);
const rangeWidth = computed(() => range.value.maxX - range.value.minX + 1);
const rangeDepth = computed(() => range.value.maxZ - range.value.minZ + 1);
const rangeChunks = computed(() =>
  `${Math.ceil(rangeWidth.value / 16)} × ${Math.ceil(rangeDepth.value / 16)} 区块`);
/** 地图 id 与已发布地图重名：会被覆盖，提前告知。 */
const idConflict = computed(() =>
  props.maps.some((m) => m.id === form.mapId.trim()) && form.mapId.trim().length > 0);

/** 选中范围最多覆盖多少区块（按外接矩形算；实际只会更少，因为空区块会被跳过）。 */
const selectedChunks = computed(() =>
  Math.ceil(rangeWidth.value / 16) * Math.ceil(rangeDepth.value / 16));
/**
 * 预估耗时（分钟）：按实测吞吐换算——青义存档 1024 区块 bake 约 73s，
 * 再算上扫描区块 / 瓦片 / LOD 的固定开销。用于在提交前劝住「整个存档全选」。
 */
const estimatedMinutes = computed(() =>
  Math.max(1, Math.round((selectedChunks.value * 0.075 + 60) / 60)));
/** 超过这个量级就不再是「等一下就好」：整存档全选能有几万区块，跑一小时以上且容易中途失败。 */
const HEAVY_CHUNKS = 8000;
/**
 * 服务端单次渲染的区块上限（0 = 未知/不限制）。
 *
 * bake 会把窗口内全部区块的网格留在内存里，服务端据此设了硬上限并会在提交时拒绝超限窗口。
 * 前端拿到这个值就能在提交前先劝住——比等服务端拒绝再退回框选友好得多。
 */
const chunkLimit = computed(() => defaults.value?.maxChunks ?? 0);
/** 超限（或拿不到上限时的经验阈值）：提示「这个范围多半跑不完」。 */
const heavyRange = computed(() => chunkLimit.value > 0
  ? selectedChunks.value > chunkLimit.value
  : selectedChunks.value > HEAVY_CHUNKS);
/** 摘要里那句「上限」提示；拿不到上限时不显示，免得编数字。 */
const chunkLimitHint = computed(() => chunkLimit.value > 0
  ? `（建议单次不超过 ${formatCount(chunkLimit.value)} 区块）`
  : "");
/** 超过建议值后服务端会自动分遍渲染；把每批的数量说出来，用户才知道代价在哪。 */
const batchChunks = computed(() => defaults.value?.batchChunks ?? 0);
const batchHint = computed(() => batchChunks.value > 0
  ? `（每批约 ${formatCount(batchChunks.value)} 区块，内存不会再卡住）`
  : "");

/**
 * 预览统计出的「最低地表高度」推出的 minY 建议值：往下留一个 section（16 格）的余量，
 * 既能保住陡坡侧面的可见几何，又能把地下洞穴/矿层整段裁掉。
 */
const suggestedMinY = computed<number | null>(() => {
  const meta = previewMeta.value;
  if (!meta || meta.minSurfaceY <= -1000) return null;
  const withMargin = meta.minSurfaceY - 16;
  return Math.max(-64, Math.floor(withMargin / 16) * 16);
});
const minYIsSuggested = computed(() => suggestedMinY.value !== null && form.minY === suggestedMinY.value);

function applySuggestedMinY(): void {
  if (suggestedMinY.value === null) return;
  form.minY = suggestedMinY.value;
  minYTouched.value = true;
}

watch(() => form.minY, (value, old) => {
  if (value !== old && value !== suggestedMinY.value) minYTouched.value = true;
});

const canGoStep2 = computed(() => activeUpload.value !== null);
const canGoStep3 = computed(() => previewReady.value && rangeWidth.value > 0 && rangeDepth.value > 0);

const jobRunning = computed(() =>
  job.value !== null && (job.value.state === "QUEUED" || job.value.state === "RUNNING"));

const jobStateLabel = computed(() => {
  switch (job.value?.state) {
    case "QUEUED":
      return "排队中";
    case "RUNNING":
      return "渲染中";
    case "SUCCEEDED":
      return "已完成";
    case "FAILED":
      return "失败";
    default:
      return "";
  }
});

function dimensionLabel(id: string): string {
  return DIMENSION_LABELS[id] ?? id;
}

function sourceLabel(source: string): string {
  return source === "LOCAL_DIR" ? "本机目录" : "上传包";
}

/**
 * 单个 level.dat 里没有世界名（LevelName 未必等于目录名），
 * 所以不拿它填名称——留给服务端用认领到的目录名，别默认出个 "level"。
 */
function suggestedName(file: File): string {
  return /^level\.dat$/i.test(file.name) ? "" : file.name.replace(/\.zip$/i, "");
}

function formatCount(value: number): string {
  return value.toLocaleString("zh-CN");
}

async function loadUploads(): Promise<void> {
  try {
    uploads.value = await api.listUploads();
  } catch (e) {
    error.value = message(e);
  }
}

async function loadDefaults(): Promise<void> {
  try {
    defaults.value = await api.renderDefaults();
    // 刻意不预填资源包：留空时服务端会按存档版本自动匹配
    // （.cache/minecraft/client-<版本>.jar 优先，其余包垫在下面补缺）。
    // 预填会把「版本匹配」这条逻辑顶掉——之前这个预填就是青义地图整片品红的直接原因。
    if (!form.modelsFile && defaults.value.modelsFile) {
      form.modelsFile = defaults.value.modelsFile;
    }
  } catch {
    // 默认值拿不到不影响使用，用户可以在高级设置里手填
  }
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ---------- 步骤一 ----------

function pickFile(event: Event): void {
  const input = event.target as HTMLInputElement;
  setArchiveFile(input.files?.[0] ?? null);
}

function onDrop(event: DragEvent): void {
  dragActive.value = false;
  setArchiveFile(event.dataTransfer?.files?.[0] ?? null);
}

function setArchiveFile(file: File | null): void {
  archiveFile.value = file;
  if (file && nameAuto.value) {
    archiveName.value = suggestedName(file);
  }
}

const levelDatSelected = computed(() =>
  archiveFile.value !== null && /^level\.dat$/i.test(archiveFile.value.name));

async function submitArchive(): Promise<void> {
  if (!archiveFile.value) {
    error.value = "请先选择存档压缩包（.zip）或 level.dat";
    return;
  }
  busy.value = true;
  error.value = "";
  uploadRatio.value = 0;
  try {
    const upload = await api.uploadArchive(
      archiveFile.value,
      archiveName.value || archiveFile.value.name,
      (ratio) => {
        uploadRatio.value = ratio;
      },
    );
    await selectUpload(upload);
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
    uploadRatio.value = 0;
  }
}

async function submitLocal(): Promise<void> {
  if (!localPath.value.trim()) {
    error.value = "请填写本机存档目录路径";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const upload = await api.registerLocalDir(localPath.value.trim(), localName.value);
    await selectUpload(upload);
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function probeLocal(): Promise<void> {
  if (!localPath.value.trim()) {
    error.value = "先填一个要扫描的根目录";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    candidates.value = await api.localWorldCandidates(localPath.value.trim(), 3);
    if (candidates.value.length === 0) {
      error.value = "该目录下没找到含 level.dat 的存档目录";
    }
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function removeUpload(u: WorldUpload) {
  if (!window.confirm(`删除登记「${u.name}」？原始存档目录不会被删除。`)) return;
  await api.deleteUpload(u.id);
  uploads.value = await api.listUploads();
  if (activeUpload.value?.id === u.id) activeUpload.value = null;
}

async function selectUpload(upload: WorldUpload): Promise<void> {
  activeUpload.value = upload;
  dimension.value = upload.dimensions.includes("minecraft:overworld")
    ? "minecraft:overworld"
    : (upload.dimensions[0] ?? "minecraft:overworld");
  form.mapId = upload.id;
  form.mapName = upload.name;
  minYTouched.value = false;
  detail.value = null;
  preview.value = null;
  try {
    detail.value = await api.uploadDetail(upload.id);
  } catch {
    // 详情拿不到不阻塞流程，框选后仍可渲染
  }
  step.value = 2;
  void startPreview();
}

// ---------- 步骤二 ----------

async function startPreview(): Promise<void> {
  if (!activeUpload.value) return;
  window.clearInterval(previewTimer);
  error.value = "";
  try {
    preview.value = await api.startPreview(activeUpload.value.id, dimension.value);
  } catch (e) {
    error.value = message(e);
    return;
  }
  if (preview.value.state === "READY" && preview.value.meta) {
    applyMeta(preview.value.meta);
    return;
  }
  previewTimer = window.setInterval(async () => {
    if (!activeUpload.value) return;
    try {
      const status = await api.previewStatus(activeUpload.value.id, dimension.value);
      preview.value = status;
      if (status.state === "READY" && status.meta) {
        window.clearInterval(previewTimer);
        applyMeta(status.meta);
      } else if (status.state === "FAILED") {
        window.clearInterval(previewTimer);
      }
    } catch (e) {
      window.clearInterval(previewTimer);
      error.value = message(e);
    }
  }, 1000);
}

/** 预览就绪后把框选范围默认落到「全部有内容范围」。 */
function applyMeta(meta: api.PreviewMeta): void {
  range.value = {
    minX: meta.blockMinX,
    maxX: meta.blockMaxX,
    minZ: meta.blockMinZ,
    maxZ: meta.blockMaxZ,
  };
  // 地表高度只有拿到预览才知道，用它替换掉写死的 -64；用户改过就不动。
  if (!minYTouched.value && meta.minSurfaceY > -1000) {
    const withMargin = meta.minSurfaceY - 16;
    form.minY = Math.max(-64, Math.floor(withMargin / 16) * 16);
  }
  pickerRef.value?.refresh();
}

const previewImageUrl = computed(() => {
  if (!activeUpload.value || !previewReady.value) return "";
  return api.previewImageUrl(activeUpload.value.id, dimension.value);
});

const previewProgress = computed(() => {
  const status = preview.value;
  if (!status || status.total === 0) return 0;
  return Math.round((status.done / status.total) * 100);
});

watch(dimension, () => {
  preview.value = null;
  // 换维度等于换地表高度，重新按新预览给建议值
  minYTouched.value = false;
  void startPreview();
});

// ---------- 步骤三 ----------

function goStep3(): void {
  if (!canGoStep3.value) {
    error.value = "请先等预览就绪并框选一个范围";
    return;
  }
  error.value = "";
  step.value = 3;
}

async function submitRender(): Promise<void> {
  if (!activeUpload.value) return;
  if (!form.mapId.trim()) {
    error.value = "请填写地图 id";
    return;
  }
  // 大范围先劝一句：几万区块的烘焙会把内存和时间都吃满，与其等一小时再失败，
  // 不如先小范围试跑一次确认效果（确认过就继续，不强制）
  if (heavyRange.value && !window.confirm(
    `这个范围最多涉及 ${formatCount(selectedChunks.value)} 个区块，预计要跑 ${estimatedMinutes.value} 分钟以上。\n`
    + (batchChunks.value > 0
      ? `这么大的窗口会自动分遍渲染${batchHint.value}，不会再因为内存跑不完，但耗时与磁盘占用会明显增长。\n\n`
      : "越大的范围越容易在中途内存不足，建议先小范围试跑。\n\n")
    + "建议：先按 region 对齐框一个校园/城区大小的范围试跑一次，确认效果和耗时后再铺开。\n\n"
    + "仍要按当前范围提交吗？")) {
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const packs = form.packs.split(/[,;]/).map((p) => p.trim()).filter(Boolean);
    job.value = await api.submitRender({
      uploadId: activeUpload.value.id,
      mapId: form.mapId.trim(),
      mapName: form.mapName.trim() || form.mapId.trim(),
      dimension: dimension.value,
      minX: range.value.minX,
      maxX: range.value.maxX,
      minZ: range.value.minZ,
      maxZ: range.value.maxZ,
      minY: form.minY,
      maxLevel: form.maxLevel,
      lodAtlas: form.lodAtlas,
      packs: packs.length > 0 ? packs : undefined,
      modelsFile: form.modelsFile.trim() || undefined,
    });
    logLines.value = [];
    logSince.value = 0;
    jobFetchFailures = 0;
    jobStalled.value = false;
    step.value = 4;
    pollJob();
  } catch (e) {
    // 提交时连不上后端：把「服务没起」和「参数不对」分开说，否则用户只会看到 Failed to fetch
    error.value = api.isTransientError(e)
      ? "连不上渲染服务（后端可能没启动或刚重启）。确认服务在跑之后重新点一次即可，范围设置不会丢。"
      : message(e);
  } finally {
    busy.value = false;
  }
}

// ---------- 步骤四 ----------

function pollJob(): void {
  window.clearInterval(jobTimer);
  jobTimer = window.setInterval(() => void refreshJob(), 1000);
}

/**
 * 拉一次任务状态。
 *
 * 关键约定：**连不上后端不算渲染失败**。服务重启、代理瞬断会让 fetch 抛
 * TypeError: Failed to fetch，开发代理在后端挂掉时还会回 500——这两种都只是「问不到」，
 * 渲染是在服务端后台跑的，前端一停轮询，进度条就永远卡在失败上，看起来像渲染挂了。
 * 所以这里继续重试（连续几次才提示「连接中断」），服务回来后接着显示；
 * 只有服务端明确答复才收手：4xx 参数/任务状态问题、或「任务不存在」
 * （进程重启后任务表是内存态，重启即丢）——那时把原因写清楚。
 */
async function refreshJob(): Promise<void> {
  const current = job.value;
  if (!current) return;
  let next: RenderJob;
  let log: { lines: string[]; logStart: number; logSize: number };
  try {
    [next, log] = await Promise.all([
      api.renderJob(current.id),
      api.renderJobLog(current.id, logSince.value),
    ]);
    jobFetchFailures = 0;
    jobStalled.value = false;
  } catch (e) {
    if (api.isTransientError(e)) {
      // 只是问不到：继续轮询，连续失败几次才提示用户（一两次抖动不打扰）
      jobFetchFailures += 1;
      jobStalled.value = jobFetchFailures >= 3;
      return;
    }
    // 服务端给了明确答复：再轮询也不会有变化
    window.clearInterval(jobTimer);
    if (message(e).includes("任务不存在")) {
      job.value = { ...current, state: "FAILED", stage: "任务已丢失" };
      error.value = "渲染服务重启过，这个任务的状态已经丢了（渲染没能跑完）。请重新提交一次。";
    } else {
      error.value = message(e);
    }
    return;
  }
  job.value = next;
  if (log.lines.length > 0) {
    logLines.value = logLines.value.concat(log.lines);
    if (logLines.value.length > 2000) {
      logLines.value = logLines.value.slice(-2000);
    }
    logSince.value = log.logSize;
    scrollLog();
  }
  if (next.state !== "RUNNING" && next.state !== "QUEUED") {
    window.clearInterval(jobTimer);
  }
}

function scrollLog(): void {
  const box = logBox.value;
  if (!box) return;
  window.requestAnimationFrame(() => {
    box.scrollTop = box.scrollHeight;
  });
}

function openMap(): void {
  const mapId = job.value?.mapId;
  if (mapId) {
    emit("rendered", mapId);
  }
}

function close(): void {
  emit("close");
}

function backToStart(): void {
  window.clearInterval(jobTimer);
  job.value = null;
  logLines.value = [];
  logSince.value = 0;
  step.value = 1;
  error.value = "";
  void loadUploads();
}

onMounted(() => {
  void loadUploads();
  void loadDefaults();
});

onBeforeUnmount(() => {
  window.clearInterval(previewTimer);
  window.clearInterval(jobTimer);
});
</script>

<template>
  <div class="overlay" @click.self="close">
    <div class="dialog glass">
      <header class="dialog-head">
        <h2>上传地图 · 自定义渲染范围</h2>
        <ol class="steps">
          <li :class="{ active: step === 1, done: step > 1 }">1 选择存档</li>
          <li :class="{ active: step === 2, done: step > 2 }">2 框选范围</li>
          <li :class="{ active: step === 3, done: step > 3 }">3 渲染设置</li>
          <li :class="{ active: step === 4 }">4 渲染</li>
        </ol>
        <button class="close" type="button" title="关闭" @click="close">✕</button>
      </header>

      <p v-if="error" class="alert">{{ error }}</p>

      <div class="dialog-body">
        <!-- 步骤一：选择存档 -->
        <section v-if="step === 1" class="pane">
          <div class="tabs">
            <button type="button" :class="{ on: sourceTab === 'archive' }" @click="sourceTab = 'archive'">
              上传压缩包
            </button>
            <button type="button" :class="{ on: sourceTab === 'local' }" @click="sourceTab = 'local'">
              本机目录
            </button>
            <button type="button" :class="{ on: sourceTab === 'existing' }" @click="sourceTab = 'existing'">
              已登记存档（{{ uploads.length }}）
            </button>
          </div>

          <div v-if="sourceTab === 'archive'" class="form-row">
            <div
              class="dropzone"
              :class="{ over: dragActive }"
              @dragover.prevent="dragActive = true"
              @dragleave.prevent="dragActive = false"
              @drop.prevent="onDrop"
              @click="fileInput?.click()"
            >
              <template v-if="archiveFile">
                <b>{{ archiveFile.name }}</b>
                <span>{{ (archiveFile.size / 1024 / 1024).toFixed(1) }} MB</span>
              </template>
              <template v-else>
                <b>把存档压缩包或 level.dat 拖到这里</b>
                <span>或点击选择 .zip（包内可直接是 level.dat，也可以多一层外壳目录）</span>
              </template>
              <input ref="fileInput" type="file" accept=".zip,.dat,application/zip" hidden @change="pickFile" />
            </div>
            <p v-if="levelDatSelected" class="dim">
              只传一个 level.dat 时，服务端会在本机把它所属的存档目录认出来再登记
              （会搜 %APPDATA%\.minecraft\saves、已登记存档的同级目录等），所以存档得在这台机器上；
              认不出来会把搜过的目录列出来。
            </p>
            <label class="field">
              <span>存档名称{{ levelDatSelected ? "（留空 = 用认领到的目录名）" : "" }}</span>
              <input
                v-model="archiveName"
                type="text"
                placeholder="用于地图展示名与 id"
                @input="nameAuto = false"
              />
            </label>
            <div v-if="uploadRatio > 0" class="progress">
              <div class="bar"><i :style="{ width: `${Math.round(uploadRatio * 100)}%` }"></i></div>
              <span>上传 {{ Math.round(uploadRatio * 100) }}%</span>
            </div>
            <button class="primary" type="button" :disabled="busy || !archiveFile" @click="submitArchive">
              {{ busy ? "处理中…" : "上传并解析" }}
            </button>
          </div>

          <div v-else-if="sourceTab === 'local'" class="form-row">
            <label class="field">
              <span>存档目录（服务端本机路径）</span>
              <input v-model="localPath" type="text" placeholder="例：A:\maps\我的存档（含 level.dat）" />
            </label>
            <div class="inline">
              <button type="button" :disabled="busy" @click="probeLocal">扫描该目录下的存档</button>
              <span class="dim">扫描只读目录，不会修改存档</span>
            </div>
            <ul v-if="candidates.length" class="candidates">
              <li v-for="c in candidates" :key="c.path" @click="localPath = c.path; localName = c.name">
                <b>{{ c.name }}</b>
                <span>{{ c.path }}</span>
              </li>
            </ul>
            <label class="field">
              <span>存档名称（可留空）</span>
              <input v-model="localName" type="text" placeholder="默认取目录名" />
            </label>
            <button class="primary" type="button" :disabled="busy || !localPath" @click="submitLocal">
              {{ busy ? "登记中…" : "登记该目录" }}
            </button>
          </div>

          <div v-else class="form-row">
            <ul v-if="uploads.length" class="candidates">
              <li v-for="u in uploads" :key="u.id" @click="selectUpload(u)">
                <div class="upload-row">
                  <span class="upload-info">
                    <b>{{ u.name }}</b>
                    <span>{{ u.versionName }} · {{ u.dimensions.length }} 个维度 · {{ sourceLabel(u.source) }}</span>
                    <span>{{ u.worldDir }}</span>
                  </span>
                  <button class="upload-del" title="删除登记（不影响原始存档）"
                          @click.stop="removeUpload(u)">删除</button>
                </div>
              </li>
            </ul>
            <p v-else class="dim">还没有登记过存档，先上传压缩包或指定本机目录。</p>
          </div>
        </section>

        <!-- 步骤二：框选范围 -->
        <section v-else-if="step === 2" class="pane">
          <div class="pane-head">
            <div class="info">
              <b>{{ activeUpload?.name }}</b>
              <span class="dim">{{ activeUpload?.versionName }}</span>
              <span v-if="currentStats" class="dim">
                {{ formatCount(currentStats.regionCount) }} region ·
                {{ formatCount(currentStats.chunkCount) }} 区块
              </span>
            </div>
            <label class="field inline-field">
              <span>维度</span>
              <select v-model="dimension">
                <option v-for="d in dimensionOptions" :key="d" :value="d">{{ dimensionLabel(d) }}</option>
              </select>
            </label>
          </div>

          <div v-if="!previewReady" class="preview-pending">
            <template v-if="preview?.state === 'FAILED'">
              <p class="alert">预览生成失败：{{ preview.message }}</p>
            </template>
            <template v-else>
              <p>正在生成二维地表预览…（首次需要读一遍区块，大存档约几十秒）</p>
              <div class="progress">
                <div class="bar"><i :style="{ width: `${previewProgress}%` }"></i></div>
                <span>{{ preview?.message || "准备中…" }}</span>
              </div>
            </template>
          </div>

          <template v-else-if="previewMeta">
            <RangePicker
              ref="pickerRef"
              :image-url="previewImageUrl"
              :meta="previewMeta"
              :model-value="range"
              @update:model-value="range = $event"
            />
            <div class="range-inputs">
              <label><span>minX</span><input v-model.number="range.minX" type="number" /></label>
              <label><span>maxX</span><input v-model.number="range.maxX" type="number" /></label>
              <label><span>minZ</span><input v-model.number="range.minZ" type="number" /></label>
              <label><span>maxZ</span><input v-model.number="range.maxZ" type="number" /></label>
              <div class="range-summary">
                范围 <b>{{ rangeWidth }} × {{ rangeDepth }}</b> 方块（{{ rangeChunks }}）
              </div>
            </div>
            <p class="dim">
              拖动框选要渲染的区域；范围越小渲染越快。按 region 对齐可以避免多渲染出边缘的 512 方块。
            </p>
          </template>
        </section>

        <!-- 步骤三：渲染设置 -->
        <section v-else-if="step === 3" class="pane">
          <div class="grid-2">
            <label class="field">
              <span>地图 id（发布目录名）</span>
              <input v-model="form.mapId" type="text" />
            </label>
            <label class="field">
              <span>地图展示名</span>
              <input v-model="form.mapName" type="text" />
            </label>
            <p v-if="idConflict" class="alert">
              已有同名地图 id「{{ form.mapId.trim() }}」，渲染会覆盖它的产物。换个 id 可以并存。
            </p>
            <label class="field">
              <span>最低渲染高度 minY</span>
              <input v-model.number="form.minY" type="number" />
            </label>
            <label class="field">
              <span>LOD 最高层级（0 = 自动）</span>
              <input v-model.number="form.maxLevel" type="number" min="0" max="8" />
            </label>
          </div>
          <p class="dim">
            低于 minY 的方块不参与网格化：地下洞穴与矿层对地表地图没有意义，裁掉能明显减少几何量。
          </p>
          <p v-if="suggestedMinY !== null" class="dim">
            预览测出该范围地表最低在 y={{ previewMeta?.minSurfaceY }}，建议 minY =
            <b>{{ suggestedMinY }}</b>。
            <button v-if="!minYIsSuggested" class="link inline" type="button" @click="applySuggestedMinY">
              用建议值
            </button>
            <span v-else>（已采用）</span>
          </p>
          <p v-else class="dim">
            预览未测出地表高度（该维度可能没有可识别的地表方块），保持 {{ form.minY }} 即可。
          </p>
          <label class="check">
            <input v-model="form.lodAtlas" type="checkbox" />
            <span>生成 LOD 分层图集页（远景更省显存，推荐开启）</span>
          </label>

          <button class="link" type="button" @click="showAdvanced = !showAdvanced">
            {{ showAdvanced ? "收起" : "展开" }}高级设置（资源包 / 采集产物）
          </button>
          <div v-if="showAdvanced" class="grid-2">
            <label class="field wide">
              <span>资源包（低 → 高优先级，逗号分隔；第一个是原版 client jar）</span>
              <input
                v-model="form.packs"
                type="text"
                placeholder="留空 = 按存档版本自动匹配（client-<版本>.jar）"
              />
            </label>
            <label class="field wide">
              <span>采集产物 models.json.gz</span>
              <input v-model="form.modelsFile" type="text" placeholder="留空 = 自动探测 work 目录" />
            </label>
            <div v-if="defaults?.clientJarCandidates.length" class="candidates wide">
              <p class="dim">本机发现的原版 jar（点一下填入）：</p>
              <ul>
                <li
                  v-for="jar in defaults.clientJarCandidates.slice(0, 5)"
                  :key="jar"
                  @click="form.packs = jar"
                >
                  <span>{{ jar }}</span>
                </li>
              </ul>
            </div>
          </div>

          <div class="summary">
            <div><span class="dim">存档</span><b>{{ activeUpload?.name }}</b></div>
            <div><span class="dim">维度</span><b>{{ dimensionLabel(dimension) }}</b></div>
            <div>
              <span class="dim">范围</span>
              <b>X [{{ range.minX }}, {{ range.maxX }}] · Z [{{ range.minZ }}, {{ range.maxZ }}]</b>
            </div>
            <div>
              <span class="dim">规模</span>
              <b>{{ rangeWidth }} × {{ rangeDepth }} 方块，最多 {{ formatCount(selectedChunks) }} 区块</b>
            </div>
            <div>
              <span class="dim">预估</span>
              <b :class="{ warn: heavyRange }">约 {{ estimatedMinutes }} 分钟以上</b>
            </div>
          </div>
          <p v-if="heavyRange" class="alert">
            范围偏大：{{ formatCount(selectedChunks) }} 个区块（实际只会更少，空区块会被跳过）。
            服务端会自动分遍渲染{{ batchHint }}，不会再因为内存跑不完，但耗时与磁盘占用随范围增长，
            建议按 region 对齐框选{{ chunkLimitHint }}。
          </p>
          <p v-else class="dim">
            超过建议值的范围会自动分遍渲染（每批约 {{ formatCount(batchChunks) }} 区块），
            因此范围没有硬上限{{ chunkLimitHint }}，代价是耗时与磁盘。
          </p>
        </section>

        <!-- 步骤四：渲染 -->
        <section v-else class="pane">
          <div class="job-head">
            <b>{{ job?.mapName }}</b>
            <span class="badge" :class="job?.state?.toLowerCase()">{{ jobStateLabel }}</span>
            <span class="dim">{{ job?.stage }}</span>
          </div>
          <div class="progress">
            <div class="bar"><i :style="{ width: `${job?.progress ?? 0}%` }"></i></div>
            <span>{{ job?.progress ?? 0 }}%</span>
          </div>
          <p v-if="jobStalled" class="alert">
            与渲染服务的连接中断了，正在自动重试（进度可能短暂停住）。渲染跑在服务端后台，
            连接恢复后这里会接着显示——先别关这个窗口。
          </p>
          <p class="dim">
            渲染在服务端后台执行，关掉这个窗口也会继续跑完。渲染完成后地图会自动出现在左上角的地图列表里。
          </p>
          <div ref="logBox" class="log">
            <div v-for="(line, i) in logLines" :key="i">{{ line }}</div>
            <div v-if="!logLines.length" class="dim">等待日志…</div>
          </div>
          <template v-if="job?.state === 'FAILED'">
            <p v-if="job.exitCode === 3" class="alert">
              范围太大：服务端拒绝了这个窗口（窗口内的非空区块超过了单次渲染上限）。
              请回到上一步把框选范围缩小到校园 / 城区大小再提交一次。
            </p>
            <p v-else-if="job.exitCode === 4" class="alert">
              渲染进程内存不足：这个范围的几何量超出了服务端给渲染用的堆。
              缩小范围能立竿见影，也可以调大配置里的 <code>render.heap</code> 后再试。
            </p>
            <p v-else class="alert">
              渲染失败（退出码 {{ job.exitCode }}）：请在上面的日志里查看原因；常见原因是资源包路径不对或缺 mod jar。
            </p>
            <div class="footer-actions">
              <button type="button" @click="step = 2">返回框选范围</button>
            </div>
          </template>
        </section>
      </div>

      <footer class="dialog-foot">
        <template v-if="step === 1">
          <span class="dim">支持 .zip 存档包或单个 level.dat，也可以直接指向本机已有存档目录</span>
        </template>
        <template v-else-if="step === 2">
          <button type="button" @click="step = 1">上一步</button>
          <button class="primary" type="button" :disabled="!canGoStep2 || !canGoStep3" @click="goStep3">
            下一步：渲染设置
          </button>
        </template>
        <template v-else-if="step === 3">
          <button type="button" @click="step = 2">上一步</button>
          <button class="primary" type="button" :disabled="busy" @click="submitRender">
            {{ busy ? "提交中…" : "开始渲染" }}
          </button>
        </template>
        <template v-else>
          <button type="button" @click="backToStart">再渲染一个范围</button>
          <button
            class="primary"
            type="button"
            :disabled="job?.state !== 'SUCCEEDED'"
            @click="openMap"
          >
            打开地图
          </button>
        </template>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: absolute;
  inset: 0;
  background: rgba(4, 8, 13, 0.62);
  backdrop-filter: blur(3px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 20;
  padding: 24px;
}

.dialog {
  width: min(1080px, 100%);
  max-height: 92vh;
  display: flex;
  flex-direction: column;
  padding: 16px 18px;
  background: rgba(13, 18, 26, 0.94);
}

.dialog-head {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.dialog-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.steps {
  display: flex;
  gap: 6px;
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12px;
  color: #7d8698;
  flex-wrap: wrap;
}

.steps li {
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
}

.steps li.active {
  background: #2f6fd0;
  color: #fff;
}

.steps li.done {
  color: #9fb6d8;
}

.close {
  margin-left: auto;
  border: none;
  background: transparent;
  color: #9aa4b5;
  font-size: 15px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
}

.close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}

.dialog-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 14px 2px;
  display: flex;
  flex-direction: column;
}

.pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.pane-head {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.pane-head .info {
  display: flex;
  gap: 10px;
  align-items: baseline;
}

.tabs {
  display: flex;
  gap: 6px;
}

.tabs button {
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.04);
  color: #9aa4b5;
  border-radius: 7px;
  padding: 6px 12px;
  font-size: 12.5px;
  cursor: pointer;
}

.tabs button.on {
  background: #2f6fd0;
  border-color: #2f6fd0;
  color: #fff;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dropzone {
  border: 1px dashed rgba(255, 255, 255, 0.22);
  border-radius: 10px;
  padding: 26px 18px;
  text-align: center;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: rgba(255, 255, 255, 0.02);
  transition: all 0.15s;
}

.dropzone.over,
.dropzone:hover {
  border-color: #5eb1ff;
  background: rgba(94, 177, 255, 0.08);
}

.dropzone span {
  font-size: 12px;
  color: #8b93a3;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: #aab3c2;
}

.field input,
.field select,
.inline-field select {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  color: #e6ebf4;
  padding: 6px 9px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
}

.field input:focus,
.field select:focus {
  border-color: #2f6fd0;
}

.inline-field {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.inline {
  display: flex;
  align-items: center;
  gap: 10px;
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 10px 14px;
}

.grid-2 .wide {
  grid-column: 1 / -1;
}

button.primary {
  align-self: flex-start;
  border: none;
  background: #2f6fd0;
  color: #fff;
  border-radius: 7px;
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
}

button.primary:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

button.link {
  align-self: flex-start;
  border: none;
  background: transparent;
  color: #5eb1ff;
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
}

/* 夹在说明文字里的小按钮：跟着基线走，别把自己当成 flex 项。 */
button.link.inline {
  display: inline;
  align-self: auto;
  margin-left: 4px;
  font-size: inherit;
  text-decoration: underline;
}

.candidates {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow: auto;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
}

.candidates li {
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  cursor: pointer;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  font-size: 12.5px;
}

.candidates li:last-child {
  border-bottom: none;
}

.candidates li:hover {
  background: rgba(94, 177, 255, 0.1);
}

.candidates li span {
  color: #8b93a3;
  font-size: 11.5px;
  word-break: break-all;
}

.progress {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #9aa4b5;
}

.progress .bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.09);
  overflow: hidden;
}

.progress .bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #2f6fd0, #5eb1ff);
  transition: width 0.25s;
}

.preview-pending {
  padding: 24px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 13px;
}

.range-inputs {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.range-inputs label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #8b93a3;
}

.range-inputs input {
  width: 96px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  color: #e6ebf4;
  padding: 5px 7px;
  font-size: 12.5px;
  font-family: inherit;
  outline: none;
}

.range-summary {
  margin-left: auto;
  font-size: 12.5px;
  color: #aab3c2;
}

.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #aab3c2;
}

.check input {
  accent-color: #2f6fd0;
}

.summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px 16px;
  padding: 12px 14px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 12.5px;
}

.summary div {
  display: flex;
  gap: 8px;
}

.job-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.badge {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
}

.badge.running,
.badge.queued {
  background: rgba(94, 177, 255, 0.2);
  color: #9fd0ff;
}

.badge.succeeded {
  background: rgba(88, 200, 130, 0.2);
  color: #8ee0ac;
}

.badge.failed {
  background: rgba(240, 110, 110, 0.2);
  color: #ff9f9f;
}

/* 超限提示：橙色而不是报错的红色——是「劝一句」，不是失败 */
.warn {
  color: #ffb96b;
}

.footer-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.log {
  height: 300px;
  overflow: auto;
  background: #080c12;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 10px 12px;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.6;
  color: #b9c2d0;
  white-space: pre-wrap;
  word-break: break-all;
}

.alert {
  margin: 0;
  padding: 9px 12px;
  border-radius: 8px;
  background: rgba(240, 110, 110, 0.14);
  border: 1px solid rgba(240, 110, 110, 0.3);
  color: #ffb4b4;
  font-size: 12.5px;
}

.dim {
  color: #8b93a3;
  font-size: 12px;
  margin: 0;
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.dialog-foot button {
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.05);
  color: #d7dde8;
  border-radius: 7px;
  padding: 8px 15px;
  font-size: 13px;
  cursor: pointer;
}

.dialog-foot button.primary {
  border: none;
  background: #2f6fd0;
  color: #fff;
}

.dialog-foot .dim {
  margin-left: auto;
}
.upload-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.upload-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.upload-del {
  background: rgba(255, 80, 80, 0.12);
  color: #ff8a8a;
  border: 1px solid rgba(255, 120, 120, 0.35);
  border-radius: 5px;
  padding: 3px 8px;
  cursor: pointer;
  font-size: 12px;
}
.upload-del:hover {
  background: rgba(255, 80, 80, 0.25);
}
</style>
