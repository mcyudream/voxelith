import * as THREE from "three";
import { beforeEach, describe, expect, it } from "vitest";
import {
  bucketIntersectsSlice,
  currentYSlice,
  setYSlice,
  sliceContainsY,
  sliceRaycastTargets,
  YSliceUniforms,
} from "./YSlice.js";

/** 造一个带包围球的碰撞代理桶（真实桶由 TileCollisionProxy 按索引子集算球）。 */
function bucket(minY: number, maxY: number, x = 0): THREE.Mesh {
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute(
    "position",
    new THREE.BufferAttribute(new Float32Array([x, minY, 0, x + 1, minY, 0, x + 1, maxY, 0, x, maxY, 0]), 3),
  );
  geometry.computeBoundingSphere();
  const mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial());
  mesh.updateMatrixWorld(true);
  return mesh;
}

function proxyGroup(...buckets: THREE.Mesh[]): THREE.Group {
  const group = new THREE.Group();
  group.matrixAutoUpdate = false;
  for (const child of buckets) {
    group.add(child);
  }
  group.updateMatrixWorld(true);
  return group;
}

describe("Y 轴切片", () => {
  beforeEach(() => {
    setYSlice(null);
  });

  it("默认关闭：区间无界、GLSL 开关为 0、任何 Y 都算在内", () => {
    expect(currentYSlice()).toBeNull();
    expect(YSliceUniforms.ySliceEnabled.value).toBe(0);
    expect(sliceContainsY(1e6)).toBe(true);
    expect(sliceContainsY(-1e6)).toBe(true);
  });

  it("设置后 uniform 与查询同步，min/max 反了自动交换", () => {
    const applied = setYSlice({ min: 80, max: 40 });
    expect(applied).toEqual({ min: 40, max: 80 });
    expect(YSliceUniforms.ySliceEnabled.value).toBe(1);
    expect(YSliceUniforms.ySliceMin.value).toBe(40);
    expect(YSliceUniforms.ySliceMax.value).toBe(80);
    expect(sliceContainsY(60)).toBe(true);
    expect(sliceContainsY(39.9)).toBe(false);
    expect(sliceContainsY(80.1)).toBe(false);
  });

  it("零厚度 / 非有限值视为关闭", () => {
    expect(setYSlice({ min: 64, max: 64 })).toBeNull();
    expect(setYSlice({ min: Number.NEGATIVE_INFINITY, max: 64 })).toBeNull();
    expect(YSliceUniforms.ySliceEnabled.value).toBe(0);
  });

  it("分桶按世界 Y 与切片相交，未开启时全部保留", () => {
    const low = bucket(60, 64);
    const high = bucket(100, 104);
    expect(bucketIntersectsSlice(low, { min: 62, max: 63 })).toBe(true);
    expect(bucketIntersectsSlice(high, { min: 62, max: 63 })).toBe(false);
    expect(bucketIntersectsSlice(high, null)).toBe(true);
  });

  it("射线目标过滤掉被裁掉的桶；关闭切片时原样返回（不分配新数组）", () => {
    const group = proxyGroup(bucket(60, 64), bucket(100, 104), bucket(140, 148));

    const all = sliceRaycastTargets([group], null);
    expect(all).toHaveLength(1);
    expect(all[0]).toBe(group);

    const sliced = sliceRaycastTargets([group], { min: 96, max: 120 });
    expect(sliced).toHaveLength(1);
    expect((sliced[0] as THREE.Mesh).geometry.boundingSphere!.center.y).toBeCloseTo(102, 5);

    expect(sliceRaycastTargets([group], { min: 200, max: 300 })).toHaveLength(0);
  });

  it("叶子代理（无子节点）也能被切片裁掉", () => {
    const leaf = bucket(0, 4);
    expect(sliceRaycastTargets([leaf], { min: 8, max: 12 })).toHaveLength(0);
    expect(sliceRaycastTargets([leaf], { min: 2, max: 3 })).toHaveLength(1);
  });
});
