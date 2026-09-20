import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { buildTileCollisionProxy } from "./TileCollisionProxy.js";

interface Quad {
  /** 四个角（按环序） */
  points: number[][];
}

/** 一组四边形 → BufferGeometry（每 quad 两个三角形，与瓦片编码一致）。 */
function geometryOf(quads: Quad[]): THREE.BufferGeometry {
  const positions: number[] = [];
  const indices: number[] = [];
  for (const quad of quads) {
    const base = positions.length / 3;
    for (const p of quad.points) {
      positions.push(p[0]!, p[1]!, p[2]!);
    }
    indices.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(positions), 3));
  geometry.setIndex(indices);
  return geometry;
}

function meshOf(quads: Quad[], transparent = false): THREE.Mesh {
  const mesh = new THREE.Mesh(
    geometryOf(quads),
    new THREE.MeshBasicMaterial({ side: THREE.FrontSide, transparent }),
  );
  mesh.matrixAutoUpdate = false;
  mesh.updateMatrix();
  return mesh;
}

function groupOf(meshes: THREE.Mesh[], x = 1024, z = 512): THREE.Group {
  const group = new THREE.Group();
  for (const mesh of meshes) {
    group.add(mesh);
  }
  group.position.set(x, 0, z);
  group.matrixAutoUpdate = false;
  group.updateMatrix();
  group.updateMatrixWorld(true);
  return group;
}

/** 8×8 个 1×1 地板面（法线 +Y） */
function floorQuads(): Quad[] {
  const quads: Quad[] = [];
  for (let x = 0; x < 8; x++) {
    for (let z = 0; z < 8; z++) {
      quads.push({ points: [[x, 64, z], [x, 64, z + 1], [x + 1, 64, z + 1], [x + 1, 64, z]] });
    }
  }
  return quads;
}

/** x=8 处 8 个 1×1 墙面（法线 -X），覆盖 y 64~65 */
function wallQuads(): Quad[] {
  const quads: Quad[] = [];
  for (let z = 0; z < 8; z++) {
    quads.push({ points: [[8, 64, z], [8, 65, z + 1], [8, 65, z], [8, 64, z + 1]] });
  }
  return quads;
}

/**
 * 一株草：绕 Y 轴 45° 的竖直面片，和真实瓦片里的表现一致 ——
 * 三个顶点在 X/Y/Z 上都不共面（斜跨 0.64 格）。
 */
function plantQuads(): Quad[] {
  const c = 0.45 * Math.SQRT1_2;
  const cx = 4;
  const cz = 4;
  return [{
    points: [
      [cx - c, 64, cz - c],
      [cx + c, 64, cz + c],
      [cx + c, 65, cz + c],
      [cx - c, 65, cz - c],
    ],
  }];
}

const raycaster = new THREE.Raycaster();

function hit(target: THREE.Object3D, from: THREE.Vector3, direction: THREE.Vector3): THREE.Intersection | null {
  raycaster.set(from, direction);
  raycaster.near = 0;
  raycaster.far = 1024;
  const hits = raycaster.intersectObject(target, true);
  return hits.length > 0 ? hits[0]! : null;
}

function proxiesOf(tile: THREE.Group, grid = 8): { solid: THREE.Group; water: THREE.Group } {
  const proxies = buildTileCollisionProxy(tile, grid);
  tile.add(proxies.solid);
  tile.add(proxies.water);
  proxies.solid.updateMatrixWorld(true);
  proxies.water.updateMatrixWorld(true);
  return proxies;
}

describe("TileCollisionProxy 瓦片碰撞代理", () => {
  it("打代理与打原始瓦片得到同一个地面交点", () => {
    const floor = meshOf(floorQuads());
    const tile = groupOf([floor, meshOf(wallQuads())]);
    const { solid } = proxiesOf(tile);

    const from = new THREE.Vector3(1028.5, 200, 516.5);
    const down = new THREE.Vector3(0, -1, 0);
    const original = hit(floor, from, down);
    const viaProxy = hit(solid, from, down);
    expect(original).not.toBeNull();
    expect(viaProxy).not.toBeNull();
    expect(viaProxy!.distance).toBeCloseTo(original!.distance, 6);
    expect(viaProxy!.point.y).toBeCloseTo(64, 6);
  });

  it("打代理与打原始瓦片得到同一个墙面交点（脚上方 0.5 格朝墙走）", () => {
    const wall = meshOf(wallQuads());
    const tile = groupOf([meshOf(floorQuads()), wall]);
    const { solid } = proxiesOf(tile);

    const from = new THREE.Vector3(1028, 64.5, 516);
    const towardWall = new THREE.Vector3(1, 0, 0);
    const original = hit(wall, from, towardWall);
    const viaProxy = hit(solid, from, towardWall);
    expect(original).not.toBeNull();
    expect(viaProxy).not.toBeNull();
    expect(viaProxy!.distance).toBeCloseTo(original!.distance, 6);
    // 墙面在局部 x=8 → 世界 x=1032，射线起点 1028 → 4 格
    expect(viaProxy!.distance).toBeCloseTo(4, 6);
  });

  it("草（45° 交叉面片）不进碰撞代理：原始网格挡人，代理不挡", () => {
    const plant = meshOf(plantQuads());
    const tile = groupOf([meshOf(floorQuads()), plant]);
    const { solid } = proxiesOf(tile);

    const from = new THREE.Vector3(1024 + 2, 64.5, 512 + 4);
    const toward = new THREE.Vector3(1, 0, 0);
    expect(hit(plant, from, toward), "原始几何里草确实在射线路径上").not.toBeNull();
    expect(hit(solid, from, toward), "代理里草不该挡人").toBeNull();
    // 但草所在的那一格，脚下地面仍然要能站（地面射线照到地板）
    const ground = hit(solid, new THREE.Vector3(1024 + 4, 200, 512 + 4), new THREE.Vector3(0, -1, 0));
    expect(ground).not.toBeNull();
    expect(ground!.point.y).toBeCloseTo(64, 6);
  });

  it("水面单独成代理：实体射线穿过水打到水底，水面射线打到水面", () => {
    const water = meshOf(
      [{ points: [[0, 66, 0], [0, 66, 8], [8, 66, 8], [8, 66, 0]] }],
      true,
    );
    const tile = groupOf([meshOf(floorQuads()), water]);
    const { solid, water: waterProxy } = proxiesOf(tile);

    const from = new THREE.Vector3(1028, 200, 516);
    const down = new THREE.Vector3(0, -1, 0);
    // 实体：穿过水面打到地板
    const solidHit = hit(solid, from, down);
    expect(solidHit).not.toBeNull();
    expect(solidHit!.point.y).toBeCloseTo(64, 6);
    // 水面：打到 y=66 的水面
    const waterHit = hit(waterProxy, from, down);
    expect(waterHit).not.toBeNull();
    expect(waterHit!.point.y).toBeCloseTo(66, 6);
    // 水里横着走：实体代理不该被水挡
    expect(
      hit(solid, new THREE.Vector3(1028, 65, 516), new THREE.Vector3(1, 0, 0)),
    ).toBeNull();
    expect(waterProxy.children.length, "水面代理不该是空的").toBeGreaterThan(0);
  });

  it("代理是瓦片上不渲染（visible=false）的子节点，求交照常工作", () => {
    const tile = groupOf([meshOf(floorQuads()), meshOf(wallQuads())]);
    const { solid } = proxiesOf(tile);
    expect(solid.visible).toBe(false);
    expect(solid.parent).toBe(tile);
    expect(
      hit(solid, new THREE.Vector3(1028.5, 200, 516.5), new THREE.Vector3(0, -1, 0)),
    ).not.toBeNull();
  });

  it("代理跟着瓦片平移：瓦片挪位置后同一条世界射线仍打中同一处", () => {
    const tile = groupOf([meshOf(floorQuads()), meshOf(wallQuads())]);
    const { solid } = proxiesOf(tile);
    tile.position.set(-4096, 0, 2048);
    tile.updateMatrix();
    tile.updateMatrixWorld(true);
    solid.updateMatrixWorld(true);

    const ground = hit(solid, new THREE.Vector3(-4096 + 4.5, 200, 2048 + 4.5), new THREE.Vector3(0, -1, 0));
    const wall = hit(solid, new THREE.Vector3(-4096 + 4, 64.5, 2048 + 4), new THREE.Vector3(1, 0, 0));
    expect(ground!.point.y).toBeCloseTo(64, 6);
    expect(wall!.distance).toBeCloseTo(4, 6);
  });

  it("每个区域子网格带紧凑包围球（比整片瓦片小得多，避免逐面扫全瓦片）", () => {
    const tile = groupOf([meshOf(floorQuads()), meshOf(wallQuads())]);
    const { solid } = proxiesOf(tile, 4);
    const source = tile.children[0] as THREE.Mesh;
    source.geometry.computeBoundingSphere();
    const whole = source.geometry.boundingSphere!.radius;

    const meshes = solid.children as THREE.Mesh[];
    expect(meshes.length).toBeGreaterThan(1);
    // 地板 + 墙两个网格各自切片，都落在 4×4 以内的区域网格里
    expect(meshes.length).toBeLessThanOrEqual(2 * 16);
    for (const mesh of meshes) {
      const sphere = mesh.geometry.boundingSphere;
      expect(sphere).not.toBeNull();
      expect(sphere!.radius).toBeLessThan(whole / 2);
    }
  });
});
