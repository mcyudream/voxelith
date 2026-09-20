/**
 * 上传 / 预览 / 渲染 后端接口客户端。
 *
 * 错误统一抛 Error（message 直接来自后端的 {"error": ...}），
 * 调用方只需把 message 显示给用户，不必再翻译状态码。
 */

export interface WorldUpload {
  id: string;
  name: string;
  worldDir: string;
  versionName: string;
  dataVersion: number;
  dimensions: string[];
  source: "LOCAL_DIR" | "ARCHIVE" | string;
  createdAt: string;
}

export interface DimensionStats {
  regionCount: number;
  chunkCount: number;
  blockMinX: number;
  blockMaxX: number;
  blockMinZ: number;
  blockMaxZ: number;
}

export interface UploadDetail {
  upload: WorldUpload;
  dimensionStats: Record<string, DimensionStats>;
}

export interface PreviewMeta {
  /** 缓存 schema 版本，后端增减字段时会变化 */
  version: number;
  dimension: string;
  originX: number;
  originZ: number;
  /** 1 像素 = step 个方块 */
  step: number;
  width: number;
  depth: number;
  blockMinX: number;
  blockMaxX: number;
  blockMinZ: number;
  blockMaxZ: number;
  regionCount: number;
  chunkCount: number;
  /** 抽样列里最低的地表高度；无数据时后端会给一个极小的哨兵值 */
  minSurfaceY: number;
  maxSurfaceY: number;
  /** 存在内容的 region 坐标 [x, z] */
  regions: number[][];
}

export interface PreviewStatus {
  state: "IDLE" | "RUNNING" | "READY" | "FAILED";
  done: number;
  total: number;
  message: string;
  meta: PreviewMeta | null;
}

export interface RenderJob {
  id: string;
  uploadId: string;
  mapId: string;
  mapName: string;
  dimension: string;
  minX: number;
  maxX: number;
  minZ: number;
  maxZ: number;
  minY: number;
  state: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
  progress: number;
  stage: string;
  startedAt: string | null;
  finishedAt: string | null;
  exitCode: number | null;
  logStart: number;
  logSize: number;
}

export interface RenderDefaults {
  packs: string[];
  modelsFile: string | null;
  clientJarCandidates: string[];
  workDir: string;
  /** 单次渲染允许的最大非空区块数（服务端硬上限）；0 = 不限制 */
  maxChunks: number;
  /** 超过建议上限后自动分遍渲染时，每批的区块数；0 = 不分遍 */
  batchChunks: number;
}

export interface RenderRequest {
  uploadId: string;
  mapId?: string;
  mapName?: string;
  dimension: string;
  minX: number;
  maxX: number;
  minZ: number;
  maxZ: number;
  minY?: number;
  maxLevel?: number;
  packs?: string[];
  modelsFile?: string;
  lodAtlas?: boolean;
}

/**
 * 带 HTTP 状态码的接口错误。
 *
 * 调用方要区分「服务端明确答复」（4xx：任务不存在、参数不对，重试没用）与
 * 「后端暂时连不上」（5xx / 无响应：开发代理在后端挂掉时会回 500，重试有意义），
 * 只看 message 字符串区分不了，所以把状态码带出来。
 */
export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}

/** 这个错误是不是「后端暂时不可达，值得重试」。 */
export function isTransientError(e: unknown): boolean {
  if (e instanceof TypeError) {
    // fetch 自身失败：服务没监听 / 连接被重置 / DNS 之类，一律当暂时
    return true;
  }
  return e instanceof ApiError && (e.status === 0 || e.status >= 500);
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(url, init);
  if (!resp.ok) {
    let message = `请求失败（HTTP ${resp.status}）`;
    try {
      const body = (await resp.json()) as { error?: string; message?: string };
      message = body.error ?? body.message ?? message;
    } catch {
      // 非 JSON 错误体：保留状态码信息
    }
    throw new ApiError(message, resp.status);
  }
  if (resp.status === 204) {
    return undefined as T;
  }
  return (await resp.json()) as T;
}

function postJson<T>(url: string, body: unknown): Promise<T> {
  return request<T>(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function listUploads(): Promise<WorldUpload[]> {
  return request<WorldUpload[]>("/api/uploads");
}

export function uploadDetail(id: string): Promise<UploadDetail> {
  return request<UploadDetail>(`/api/uploads/${encodeURIComponent(id)}`);
}

/**
 * 上传一份存档：.zip 压缩包（按包解）或单个 level.dat（服务端据此在本机认领存档目录）。
 * onProgress 收到 0-1 的上传进度（XHR 才能拿到上传进度）。
 */
export function uploadArchive(
  file: File,
  name: string,
  onProgress?: (ratio: number) => void,
): Promise<WorldUpload> {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append("file", file);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `/api/uploads/archive?name=${encodeURIComponent(name)}`);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(e.loaded / e.total);
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(JSON.parse(xhr.responseText) as WorldUpload);
        return;
      }
      reject(new Error(extractError(xhr.responseText, xhr.status)));
    };
    // 状态码 0 = 请求根本没到服务端（后端没起来 / 上传中途被断），与 4xx 的「服务端拒绝」不同
    xhr.onerror = () => reject(new ApiError("上传失败：网络中断", 0));
    xhr.send(form);
  });
}

export function registerLocalDir(path: string, name: string): Promise<WorldUpload> {
  return postJson<WorldUpload>("/api/uploads/local", { path, name });
}

export function deleteUpload(id: string): Promise<{ removed: boolean }> {
  return request<{ removed: boolean }>(`/api/uploads/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

/** 删除一张已发布地图（发布目录与渲染工作目录一并清掉，不可恢复）。 */
export function deleteMap(mapId: string): Promise<{ removed: boolean }> {
  return request<{ removed: boolean }>(`/api/maps/${encodeURIComponent(mapId)}`, {
    method: "DELETE",
  });
}

export function startPreview(id: string, dimension: string): Promise<PreviewStatus> {
  return request<PreviewStatus>(
    `/api/uploads/${encodeURIComponent(id)}/preview?dimension=${encodeURIComponent(dimension)}`,
    { method: "POST" },
  );
}

export function previewStatus(id: string, dimension: string): Promise<PreviewStatus> {
  return request<PreviewStatus>(
    `/api/uploads/${encodeURIComponent(id)}/preview?dimension=${encodeURIComponent(dimension)}`,
  );
}

export function previewImageUrl(id: string, dimension: string): string {
  return `/api/uploads/${encodeURIComponent(id)}/preview.png?dimension=${encodeURIComponent(dimension)}`;
}

export function renderDefaults(): Promise<RenderDefaults> {
  return request<RenderDefaults>("/api/render/defaults");
}

export function submitRender(body: RenderRequest): Promise<RenderJob> {
  return postJson<RenderJob>("/api/render/jobs", body);
}

export function renderJob(id: string): Promise<RenderJob> {
  return request<RenderJob>(`/api/render/jobs/${encodeURIComponent(id)}`);
}

export function renderJobLog(
  id: string,
  since: number,
): Promise<{ lines: string[]; logStart: number; logSize: number }> {
  return request(`/api/render/jobs/${encodeURIComponent(id)}/log?since=${since}`);
}

export function localWorldCandidates(
  root: string,
  depth = 2,
): Promise<{ path: string; name: string }[]> {
  return request(
    `/api/local-worlds?root=${encodeURIComponent(root)}&depth=${depth}`,
  );
}

function extractError(text: string, status: number): string {
  try {
    const body = JSON.parse(text) as { error?: string; message?: string };
    return body.error ?? body.message ?? `HTTP ${status}`;
  } catch {
    return `HTTP ${status}`;
  }
}
