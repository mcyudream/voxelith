import * as THREE from "three";
import { describe, expect, it } from "vitest";
import {
  configureHiresAtlas,
  configureLodColormap,
  disposeTileGroup,
  TileGeometryError,
  validateTileGroup,
} from "./GlbTileLoader.js";

/** 构造最小合法瓦片组：2 个三角形 + 一个 float 属性 + 索引。 */
function makeGroup(): THREE.Group {
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute(
    "position",
    new THREE.BufferAttribute(new Float32Array([0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1]), 3),
  );
  geometry.setAttribute(
    "normal",
    new THREE.BufferAttribute(new Float32Array([0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0]), 3),
  );
  geometry.setIndex([0, 1, 2, 0, 2, 3]);
  const material = new THREE.MeshBasicMaterial();
  const group = new THREE.Group();
  group.add(new THREE.Mesh(geometry, material));
  return group;
}

describe("validateTileGroup 数据校验", () => {
  it("干净几何通过", () => {
    expect(() => validateTileGroup(makeGroup())).not.toThrow();
  });

  it("position 含 NaN 抛 TileGeometryError", () => {
    const group = makeGroup();
    (group.children[0] as THREE.Mesh).geometry.getAttribute("position").array[3] = NaN;
    expect(() => validateTileGroup(group, "t")).toThrow(TileGeometryError);
    expect(() => validateTileGroup(group, "t")).toThrow(/NaN\/Infinity/);
  });

  it("normal 含 Infinity 同样拦截", () => {
    const group = makeGroup();
    (group.children[0] as THREE.Mesh).geometry.getAttribute("normal").array[0] = Infinity;
    expect(() => validateTileGroup(group)).toThrow(TileGeometryError);
  });

  it("索引越界抛 TileGeometryError", () => {
    const group = makeGroup();
    (group.children[0] as THREE.Mesh).geometry.setIndex([0, 1, 99]);
    expect(() => validateTileGroup(group)).toThrow(/索引越界/);
  });

  it("缺 position 属性抛错", () => {
    const group = new THREE.Group();
    group.add(new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial()));
    expect(() => validateTileGroup(group)).toThrow(/position/);
  });
});

describe("disposeTileGroup 资源释放", () => {
  it("释放 geometry/material/纹理，保留共享图集", () => {
    const group = makeGroup();
    const mesh = group.children[0] as THREE.Mesh;
    const material = mesh.material as THREE.MeshBasicMaterial;
    const ownTexture = new THREE.Texture();
    const sharedTexture = new THREE.Texture();
    sharedTexture.userData.voxelithShared = true;
    material.map = ownTexture;

    let ownDisposed = false;
    ownTexture.dispose = () => {
      ownDisposed = true;
    };
    let sharedDisposed = false;
    sharedTexture.dispose = () => {
      sharedDisposed = true;
    };
    let geometryDisposed = false;
    mesh.geometry.dispose = () => {
      geometryDisposed = true;
    };

    disposeTileGroup(group);
    expect(ownDisposed).toBe(true);
    expect(sharedDisposed).toBe(false);
    expect(geometryDisposed).toBe(true);

    // 共享纹理路径：map 换成共享纹理后不释放
    const group2 = makeGroup();
    (group2.children[0] as THREE.Mesh).material = new THREE.MeshBasicMaterial({
      map: sharedTexture,
    });
    disposeTileGroup(group2);
    expect(sharedDisposed).toBe(false);
  });
});

describe("图集过滤约定", () => {
  it("hires 图集禁用 mipmap 与 anisotropy，避免格子串色", () => {
    const texture = new THREE.Texture();
    configureHiresAtlas(texture);
    expect(texture.magFilter).toBe(THREE.NearestFilter);
    expect(texture.minFilter).toBe(THREE.NearestFilter);
    expect(texture.generateMipmaps).toBe(false);
    expect(texture.anisotropy).toBe(1);
    expect(texture.flipY).toBe(false);
    expect(texture.colorSpace).toBe(THREE.SRGBColorSpace);
  });

  it("LOD 色图 LINEAR 无 mip，flipY=false", () => {
    const texture = new THREE.Texture();
    configureLodColormap(texture);
    expect(texture.magFilter).toBe(THREE.LinearFilter);
    expect(texture.minFilter).toBe(THREE.LinearFilter);
    expect(texture.generateMipmaps).toBe(false);
    expect(texture.flipY).toBe(false);
    expect(texture.wrapS).toBe(THREE.ClampToEdgeWrapping);
  });
});
