import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { FloatingOrigin } from "./FloatingOrigin.js";

describe("FloatingOrigin 浮点原点", () => {
  it("阈值内不触发", () => {
    const fo = new FloatingOrigin(1 << 20, 1024);
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(5000, 64, -5000);
    const scene = new THREE.Scene();
    expect(fo.maybeRebase(camera, scene)).toBeNull();
    expect(fo.origin.length()).toBe(0);
    expect(camera.position.x).toBe(5000);
  });

  it("超阈值按 snap 网格重定基：相机与顶层节点同步平移", () => {
    const fo = new FloatingOrigin(1 << 20, 1024);
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(1_100_000, 64, -1_100_000);
    const scene = new THREE.Scene();
    const tile = new THREE.Group();
    tile.position.set(1_048_576, 0, -1_048_576);
    tile.matrixAutoUpdate = false;
    tile.updateMatrix();
    scene.add(tile);

    const delta = fo.maybeRebase(camera, scene);
    expect(delta).not.toBeNull();
    // 1100000 / 1024 ≈ 1074.2 → 吸附 1074×1024 = 1099776
    expect(delta!.x).toBe(1099776);
    expect(delta!.z).toBe(-1099776);
    // 相机与瓦片同量反向平移，相对关系不变
    expect(camera.position.x).toBe(1_100_000 - 1099776);
    expect(tile.position.x).toBe(1_048_576 - 1099776);
    expect(tile.position.x - camera.position.x).toBeCloseTo(1_048_576 - 1_100_000, 6);
    expect(fo.origin.x).toBe(1099776);
    // matrixAutoUpdate=false 的节点矩阵已同步
    expect(tile.matrix.elements[12]).toBe(tile.position.x);
  });

  it("reset 归零", () => {
    const fo = new FloatingOrigin(1 << 20, 1024);
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(2_000_000, 64, 0);
    fo.maybeRebase(camera, new THREE.Scene());
    expect(fo.origin.x).not.toBe(0);
    fo.reset();
    expect(fo.origin.length()).toBe(0);
  });
});
