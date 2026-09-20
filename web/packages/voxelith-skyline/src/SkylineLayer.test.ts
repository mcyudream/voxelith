import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { parseManifest, type ManifestTile, type MapManifest } from "@yudream/voxelith-core";
import { applyUvRect, SkylineLayer } from "./SkylineLayer.js";
import { lodAtlasUvRect } from "./lodAtlas.js";

/** 两层 LOD：level 0 四片各 32 方块，level 1 一片覆盖 64 方块。 */
function manifest(): MapManifest {
  const tiles: ManifestTile[] = [];
  const push = (level: number, x: number, z: number): void => {
    const size = 32 * 2 ** level;
    tiles.push({
      level,
      x,
      z,
      url: `tiles/lod/${level}/${x}/${z}.glb`,
      sha1: `${level}${x}${z}`.padEnd(8, "0"),
      bytes: 100,
      quads: 4,
      min: [x * size, 64, z * size],
      max: [x * size + size, 80 + 4 * level, z * size + size],
    });
  };
  for (let x = 0; x < 2; x++) {
    for (let z = 0; z < 2; z++) {
      push(0, x, z);
    }
  }
  push(1, 0, 0);
  return parseManifest({
    formatVersion: 1,
    mapId: "demo",
    name: "演示",
    version: "v1",
    generatedAt: "2026-09-20T00:00:00Z",
    settings: { hiresTileSize: 32, lodCount: 2 },
    boundsMin: [0, 64, 0],
    boundsMax: [64, 84, 64],
    atlas: { url: "atlas.png", size: 256, textureCount: 9 },
    // 只有 LOD 层（≥1）才有图集页：hires 用清单里的主图集 atlas.png，不走天际线
    lodAtlases: [{ level: 1, url: "tiles/lod/1/lod-atlas.png", slotSize: 32, sha1: "l1" }],
    tiles,
  });
}

function layer(detail = 64, far = 512, atlasTextures?: Map<number, THREE.Texture>): SkylineLayer {
  return new SkylineLayer({
    manifest: manifest(),
    detailDistance: detail,
    farDistance: far,
    ...(atlasTextures ? { atlasTextures } : {}),
    fallbackColor: 0x223344,
  });
}

describe("SkylineLayer 平面 LOD", () => {
  it("每片远景瓦片一个面片，位置/尺寸/朝向正确（水平面片，抬到该片最高点之上）", () => {
    const skyline = layer();
    // hires 层没有 lod 图集页，因此远景面片只来自 LOD1 的那一片
    expect(skyline.tileCount()).toBe(1);

    const meshes = skyline.object3d.children as THREE.Mesh[];
    const lod1 = meshes[0]!;
    expect(lod1.position.y).toBeCloseTo(84.05, 5);
    lod1.geometry.computeBoundingBox();
    const box = lod1.geometry.boundingBox!;
    expect(box.max.x - box.min.x).toBeCloseTo(64, 5);
    expect(box.max.z - box.min.z).toBeCloseTo(64, 5);
    expect(box.max.y - box.min.y).toBeCloseTo(0, 5);
    expect(lod1.material).toBeInstanceOf(THREE.MeshBasicMaterial);
    expect((lod1.material as THREE.MeshBasicMaterial).fog).toBe(true);
    skyline.dispose();
  });

  it("远的相机只画最粗的 active 层，近处不画远景", () => {
    const skyline = layer(64, 512);
    const camera = new THREE.PerspectiveCamera();

    camera.position.set(32, 80, 32);
    skyline.update(camera);
    expect(skyline.visibleTileCount()).toBe(0);

    camera.position.set(332, 120, 32);
    skyline.update(camera);
    expect(skyline.activeLevel()).toBe(1);
    expect(skyline.visibleTileCount()).toBe(1);

    camera.position.set(932, 120, 32);
    skyline.update(camera);
    expect(skyline.visibleTileCount()).toBe(0);
    skyline.dispose();
  });

  it("地平线带边界带滞回：远端越界 15% 内不撤、近端提前接上", () => {
    const skyline = layer(64, 300);
    const camera = new THREE.PerspectiveCamera();

    // LOD1 面片覆盖 x∈[0,64]；相机在 x=364 时到面片的水平距离正好 300 = farDistance
    camera.position.set(364, 120, 32);
    skyline.update(camera);
    expect(skyline.activeLevel()).toBe(1);
    expect(skyline.visibleTileCount()).toBe(1);

    // 越界 5%（300 → 320）：滞回 15%（上限 345）内，保持可见
    camera.position.set(384, 120, 32);
    skyline.update(camera);
    expect(skyline.visibleTileCount()).toBe(1);

    // 越界 33%（300 → 400）：超出滞回，撤掉
    camera.position.set(464, 120, 32);
    skyline.update(camera);
    expect(skyline.visibleTileCount()).toBe(0);

    // 近端：距离 60 < 进入阈值 57.6 之外的 0.9×0.85=48.96 时仍保持，再近就撤掉
    camera.position.set(124, 120, 32);
    skyline.update(camera);
    expect(skyline.visibleTileCount()).toBe(1);
    camera.position.set(104, 120, 32);
    skyline.update(camera);
    expect(skyline.visibleTileCount()).toBe(0);
    skyline.dispose();
  });

  it("提供图集纹理时用它做材质，缺纹理回退纯色", () => {
    const texture = new THREE.Texture();
    const withAtlas = layer(64, 512, new Map([[1, texture]]));
    expect(withAtlas.hasAtlasTexture(1)).toBe(true);
    expect(withAtlas.hasAtlasTexture(0)).toBe(false);
    withAtlas.dispose();

    const fallback = layer();
    expect(fallback.hasAtlasTexture(1)).toBe(false);
    const mesh = (fallback.object3d.children as THREE.Mesh[])[0]!;
    const material = mesh.material as THREE.MeshBasicMaterial;
    expect(material.map).toBeNull();
    expect(material.color.getHex()).toBe(0x223344);
    fallback.dispose();
  });

  it("面片 UV = 图集槽位矩形，且 v 轴与世界 Z 同向", () => {
    const skyline = layer();
    const meshes = skyline.object3d.children as THREE.Mesh[];
    const lod1 = meshes[0]!;
    const uv = skyline.uvFor(1, 0, 0)!;
    expect(uv).not.toBeNull();

    const position = lod1.geometry.getAttribute("position");
    const texcoord = lod1.geometry.getAttribute("uv");
    let minZVertex = -1;
    let maxZVertex = -1;
    let minXVertex = -1;
    for (let i = 0; i < position.count; i++) {
      const z = position.getZ(i);
      const x = position.getX(i);
      if (minZVertex < 0 || z < position.getZ(minZVertex)) {
        minZVertex = i;
      }
      if (maxZVertex < 0 || z > position.getZ(maxZVertex)) {
        maxZVertex = i;
      }
      if (minXVertex < 0 || x < position.getX(minXVertex)) {
        minXVertex = i;
      }
    }
    expect(texcoord.getY(maxZVertex)).toBeCloseTo(uv.v1, 6);
    expect(texcoord.getY(minZVertex)).toBeCloseTo(uv.v0, 6);
    expect(texcoord.getX(minXVertex)).toBeCloseTo(uv.u0, 6);
    skyline.dispose();
  });

  it("applyUvRect 直接改写几何 UV；缺 uv 属性时明确报错", () => {
    const geometry = new THREE.PlaneGeometry(1, 1);
    applyUvRect(geometry, {
      level: 1,
      url: "x.png",
      u0: 0.25,
      v0: 0.5,
      u1: 0.5,
      v1: 0.75,
      slotSize: 32,
      pageWidth: 128,
      pageHeight: 128,
    });
    const uv = geometry.getAttribute("uv");
    const values = Array.from({ length: uv.count }, (_, i) => [uv.getX(i), uv.getY(i)]);
    expect(new Set(values.map(([u]) => Number(u!.toFixed(6))))).toEqual(new Set([0.25, 0.5]));
    expect(new Set(values.map(([, v]) => Number(v!.toFixed(6))))).toEqual(new Set([0.5, 0.75]));

    expect(() => applyUvRect(new THREE.BufferGeometry(), {
      level: 1,
      url: "x.png",
      u0: 0,
      v0: 0,
      u1: 1,
      v1: 1,
      slotSize: 1,
      pageWidth: 1,
      pageHeight: 1,
    })).toThrow(/uv/);
  });

  it("uvFor 与 lodAtlasUvRect 同源（不重复实现换算）", () => {
    const skyline = layer();
    expect(skyline.uvFor(1, 0, 0)).toEqual(lodAtlasUvRect(manifest(), 1, 0, 0));
    skyline.dispose();
  });
});
