import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { markerSetSchema, type MarkerSet } from "@yudream/voxelith-core";
import { MarkerLayer, type MarkerTextureFactory } from "./MarkerLayer.js";

/** 测试用纹理工厂：不碰 DOM Canvas。 */
const fakeTextures: MarkerTextureFactory = {
  pin: () => new THREE.Texture(),
  label: () => new THREE.Texture(),
};

/** 走一遍 core 的 zod 校验，顺带保证「schema 能接受测试数据」。 */
function parse(raw: unknown): MarkerSet {
  return markerSetSchema.parse(raw);
}

function layer(): MarkerLayer {
  const layer = new MarkerLayer({ textureFactory: fakeTextures });
  layer.object3d.updateMatrixWorld(true);
  return layer;
}

function poiSet(): MarkerSet {
  return parse({
    id: "landmarks",
    label: "地标",
    markers: [
      { id: "library", type: "poi", label: "图书馆", position: { x: 10, y: 70, z: 20 } },
      {
        id: "gate",
        type: "poi",
        label: "校门",
        position: { x: 100, y: 64, z: 0 },
        minDistance: 50,
      },
    ],
  });
}

describe("MarkerLayer", () => {
  it("POI：图钉 + 标签 sprite，锚点为世界坐标，可射线拾取", () => {
    const markers = layer();
    markers.setMarkerSets([poiSet()]);

    const library = markers.object3d.getObjectByName("marker:landmarks:library") as THREE.Sprite;
    expect(library).toBeInstanceOf(THREE.Sprite);
    // 图钉 sprite 相对锚点抬了 0.9 格（钉尖落在锚点上），锚点本身是标注的世界坐标
    expect(library.parent!.getWorldPosition(new THREE.Vector3()).toArray()).toEqual([10, 70, 20]);
    expect(library.position.y).toBeCloseTo(0.9, 5);
    // POI 组 = 图钉 + 标签
    expect(library.parent!.children).toHaveLength(2);

    markers.object3d.updateMatrixWorld(true);
    // 瞄准图钉中心（锚点上方 0.9 格），沿 -Z 打过去
    const ray = new THREE.Raycaster(new THREE.Vector3(10, 70.9, 60), new THREE.Vector3(0, 0, -1));
    const camera = new THREE.PerspectiveCamera();
    camera.position.set(10, 70.9, 60);
    camera.lookAt(10, 70.9, 20);
    camera.updateMatrixWorld(true);
    const hit = markers.pick(ray, camera);
    expect(hit?.marker.id).toBe("library");
    expect(hit?.setId).toBe("landmarks");

    markers.dispose();
  });

  it("距离剔除：minDistance 之外的 POI 不显示，进入范围后出现", () => {
    const markers = layer();
    markers.setMarkerSets([poiSet()]);
    const camera = new THREE.PerspectiveCamera();

    camera.position.set(100, 64, 10); // 距 gate 10 格 < minDistance 50
    markers.update(camera);
    const gate = markers.object3d.getObjectByName("marker:landmarks:gate")!;
    const library = markers.object3d.getObjectByName("marker:landmarks:library")!;
    expect(gate.parent!.visible).toBe(false);
    expect(library.parent!.visible).toBe(true);

    camera.position.set(100, 64, 200); // 距 gate 200 格 > 50
    markers.update(camera);
    expect(gate.parent!.visible).toBe(true);

    markers.dispose();
  });

  it("标注集显隐：defaultHidden 生效，setSetVisible 可切换", () => {
    const hidden = parse({
      id: "debug",
      label: "调试",
      defaultHidden: true,
      markers: [{ id: "p", type: "poi", label: "点", position: { x: 0, y: 0, z: 0 } }],
    });
    const markers = layer();
    markers.setMarkerSets([poiSet(), hidden]);

    expect(markers.isSetVisible("debug")).toBe(false);
    expect(markers.object3d.getObjectByName("marker:debug:p")!.parent!.visible).toBe(false);

    markers.setSetVisible("debug", true);
    expect(markers.object3d.getObjectByName("marker:debug:p")!.parent!.visible).toBe(true);
    expect(markers.setIds()).toEqual(["landmarks", "debug"]);
    expect(markers.pointsOfInterest("landmarks").map((poi) => poi.marker.id))
      .toEqual(["library", "gate"]);

    markers.dispose();
  });

  it("line / shape / extrude / box 都生成对应几何", () => {
    const sets = [
      parse({
        id: "routes",
        label: "路线",
        markers: [
          {
            id: "path",
            type: "line",
            label: "巡逻线",
            points: [
              { x: 0, y: 64, z: 0 },
              { x: 16, y: 64, z: 0 },
              { x: 16, y: 64, z: 16 },
            ],
          },
        ],
      }),
      parse({
        id: "zones",
        label: "区域",
        markers: [
          {
            id: "campus",
            type: "shape",
            label: "校园",
            shape: [
              { x: 0, z: 0 },
              { x: 32, z: 0 },
              { x: 32, z: 32 },
              { x: 0, z: 32 },
            ],
            holes: [[{ x: 8, z: 8 }, { x: 16, z: 8 }, { x: 16, z: 16 }, { x: 8, z: 16 }]],
            shapeY: 64,
            style: { lineColor: "#ffcc00" },
          },
          {
            id: "tower",
            type: "extrude",
            label: "塔楼",
            shape: [
              { x: 0, z: 0 },
              { x: 8, z: 0 },
              { x: 8, z: 8 },
            ],
            shapeMinY: 64,
            shapeMaxY: 96,
          },
          {
            id: "dorm",
            type: "box",
            label: "宿舍楼",
            min: { x: 0, y: 64, z: 0 },
            max: { x: 12, y: 84, z: 30 },
            style: { lineColor: "#ffffff" },
          },
        ],
      }),
    ];

    const markers = layer();
    markers.setMarkerSets(sets);

    const line = markers.object3d.children.find((child) => child.type === "Line") as THREE.Line;
    expect(line).toBeInstanceOf(THREE.Line);
    expect(line.geometry.getAttribute("position").count).toBe(3);

    const zones = markers.object3d.children.filter((child) => child.type === "Group");
    // shape = 面 + 描边，extrude = 棱柱，box = 盒 + 描边
    expect(zones).toHaveLength(3);
    const shapeMesh = zones[0]!.children[0] as THREE.Mesh;
    expect(shapeMesh.geometry.getAttribute("position").count).toBeGreaterThan(4);
    // ShapeGeometry 带洞：顶点数比无洞四边形多
    const extrude = zones[1]!.children[0] as THREE.Mesh;
    const extrudeBox = new THREE.Box3().setFromObject(extrude);
    expect(extrudeBox.min.y).toBeCloseTo(64, 3);
    expect(extrudeBox.max.y).toBeCloseTo(96, 3);
    const boxMesh = zones[2]!.children[0] as THREE.Mesh;
    const box = new THREE.Box3().setFromObject(boxMesh);
    expect(box.min.toArray()).toEqual([0, 64, 0]);
    expect(box.max.toArray()).toEqual([12, 84, 30]);

    markers.dispose();
    expect(markers.object3d.children).toHaveLength(0);
  });

  it("换一组标注会清空旧内容", () => {
    const markers = layer();
    markers.setMarkerSets([poiSet()]);
    expect(markers.object3d.children.length).toBeGreaterThan(0);
    markers.setMarkerSets([]);
    expect(markers.object3d.children).toHaveLength(0);
    expect(markers.setIds()).toEqual([]);
    markers.dispose();
  });
});
