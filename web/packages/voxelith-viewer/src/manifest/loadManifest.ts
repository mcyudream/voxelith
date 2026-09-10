/**
 * 从后端拉取并校验地图清单。
 */
import { parseManifest, type MapManifest } from "@yudream/voxelith-core";

export async function loadManifest(mapBaseUrl: string): Promise<MapManifest> {
  const resp = await fetch(`${mapBaseUrl}/manifest.json`);
  if (!resp.ok) {
    throw new Error(`清单加载失败: HTTP ${resp.status} (${mapBaseUrl}/manifest.json)`);
  }
  return parseManifest(await resp.json());
}
