import * as THREE from "three";
import { describe, expect, it } from "vitest";
import {
  configureHiresAtlas,
  configureLodColormap,
  disposeTileGroup,
  resolveLodTexture,
  resolveTileTexture,
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

describe("resolveTileTexture 层级判据", () => {
  it("LOD 保留逐瓦片内嵌色图，即使提供了共享图集", () => {
    const embedded = new THREE.Texture();
    const shared = new THREE.Texture();
    const resolved = resolveTileTexture(embedded, true, shared);
    expect(resolved).toBe(embedded);
    expect(resolved!.magFilter).toBe(THREE.LinearFilter);
    expect(resolved!.generateMipmaps).toBe(false);
  });

  it("hires 换成共享图集", () => {
    const embedded = new THREE.Texture();
    const shared = new THREE.Texture();
    expect(resolveTileTexture(embedded, false, shared)).toBe(shared);
  });

  it("无共享图集时 hires 就地按图集参数配置", () => {
    const embedded = new THREE.Texture();
    const resolved = resolveTileTexture(embedded, false);
    expect(resolved).toBe(embedded);
    expect(resolved!.magFilter).toBe(THREE.NearestFilter);
    expect(resolved!.generateMipmaps).toBe(false);
  });

  it("sampler 为 NEAREST 的 LOD 不被误判为 hires（回归：半屏色块）", () => {
    // 旧实现用 source.map.magFilter === LinearFilter 反推层级：
    // LOD 的 sampler 一旦不是 LINEAR 就被当成 hires，换成共享图集后
    // 它的 0..1 全幅 UV 去采样整张方块图集 → 整片红/青色块。
    const lodTexture = new THREE.Texture();
    lodTexture.magFilter = THREE.NearestFilter;
    const shared = new THREE.Texture();
    expect(resolveTileTexture(lodTexture, true, shared)).toBe(lodTexture);
  });

  it("无内嵌图的 hires（共享图集模式）也拿到共享图集", () => {
    // 共享图集模式下瓦片只带 UV、不内嵌 PNG：material.map 为 null，
    // 但清单声明了 atlas，必须挂上共享纹理，否则整片瓦片没有贴图。
    const shared = new THREE.Texture();
    expect(resolveTileTexture(null, false, shared)).toBe(shared);
  });

  it("既无内嵌图也无共享图集时 hires 返回 null（仅顶点色）", () => {
    expect(resolveTileTexture(null, false, undefined)).toBeNull();
  });

  it("sampler 为 LINEAR 的 hires 照样换成共享图集", () => {
    const hiresTexture = new THREE.Texture();
    hiresTexture.magFilter = THREE.LinearFilter;
    const shared = new THREE.Texture();
    expect(resolveTileTexture(hiresTexture, false, shared)).toBe(shared);
  });
});

describe("resolveLodTexture 图集页与内嵌色图的优先级", () => {
  it("无内嵌色图时用该层图集页（全量生成的瓦片）", () => {
    const atlas = new THREE.Texture();
    const resolved = resolveLodTexture(null, atlas);
    expect(resolved).toBe(atlas);
    // 图集页要按 LOD 色图参数配置：LINEAR、无 mip、flipY=false
    expect(resolved!.magFilter).toBe(THREE.LinearFilter);
    expect(resolved!.generateMipmaps).toBe(false);
    expect(resolved!.flipY).toBe(false);
    expect(resolved!.wrapS).toBe(THREE.ClampToEdgeWrapping);
  });

  it("内嵌色图优先于图集页（增量/旧格式瓦片的 UV 是瓦片局部 0..1）", () => {
    const embedded = new THREE.Texture();
    const atlas = new THREE.Texture();
    // 若这里改取 atlas，瓦片会用局部 UV 采整页图集 → 乱色
    expect(resolveLodTexture(embedded, atlas)).toBe(embedded);
  });

  it("既无内嵌色图也无图集页时返回 null（仅方向明暗）", () => {
    expect(resolveLodTexture(null, undefined)).toBeNull();
  });

  it("只传内嵌色图（清单未声明该层图集）时仍可用", () => {
    const embedded = new THREE.Texture();
    expect(resolveLodTexture(embedded, undefined)).toBe(embedded);
  });
});
