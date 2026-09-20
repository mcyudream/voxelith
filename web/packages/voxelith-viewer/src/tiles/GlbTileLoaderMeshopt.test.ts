/**
 * 端到端：Java 产出的**量化 + meshopt** 瓦片，交给真实的 three.js GLTFLoader 解析。
 *
 * 为什么必须有这一层：`MeshoptGolden.test.ts` 只验位流能被官方解码器还原，
 * `GlbTileEncoderMeshoptTest`（后端）只验 glb 结构自洽。两者都覆盖不到
 * 「GLTFLoader 认不认这份 glb」——例如 EXT_meshopt_compression 的 bufferView
 * 形状、KHR_mesh_quantization 的 accessor 声明、node.scale 还原，任何一处写错
 * 都是「位流对、浏览器里加载失败」。
 *
 * fixture 由后端 `GlbTileEncoder.encode(quad, null, EncodeOptions.quantized().withMeshopt(true))`
 * 生成（4 顶点 quad，共享图集、无内嵌 PNG），与产线瓦片同款编码。
 */
import { readFileSync } from "node:fs";
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { MeshoptDecoder } from "three/addons/libs/meshopt_decoder.module.js";
import { describe, expect, it } from "vitest";

const FIXTURE = new URL("./fixtures/quantized-meshopt-tile.glb", import.meta.url);

async function parseFixture(): Promise<THREE.Group> {
  const bytes = readFileSync(FIXTURE);
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const loader = new GLTFLoader();
  loader.setMeshoptDecoder(MeshoptDecoder);
  await MeshoptDecoder.ready;
  return new Promise<THREE.Group>((resolve, reject) => {
    loader.parse(buffer as ArrayBuffer, "", (gltf) => resolve(gltf.scene), reject);
  });
}

describe("量化 + meshopt 瓦片经 GLTFLoader 加载", () => {
  it("解出全部顶点属性（含 _LIGHT 与顶点色），索引数正确", async () => {
    const scene = await parseFixture();
    const meshes: THREE.Mesh[] = [];
    scene.traverse((node) => {
      if (node instanceof THREE.Mesh) {
        meshes.push(node);
      }
    });
    expect(meshes).toHaveLength(1);

    const geometry = meshes[0]!.geometry;
    expect(Object.keys(geometry.attributes).sort()).toEqual(
      ["_light", "color", "normal", "position", "uv"].sort(),
    );
    expect(geometry.getAttribute("position").count).toBe(4);
    expect(geometry.getIndex()!.count).toBe(6);
    // 没有内嵌图集（共享图集模式）：材质不该带 map，避免逐瓦片解码整张图集
    expect((meshes[0]!.material as THREE.MeshBasicMaterial).map ?? null).toBeNull();
  });

  it("量化 + node.scale 还原：世界坐标仍落在瓦片局部 [0,0,0]..[1,0,1]", async () => {
    const scene = await parseFixture();
    scene.updateMatrixWorld(true);
    const mesh = scene.getObjectByProperty("type", "Mesh") as THREE.Mesh;
    const position = mesh.geometry.getAttribute("position");

    const min = new THREE.Vector3(Infinity, Infinity, Infinity);
    const max = new THREE.Vector3(-Infinity, -Infinity, -Infinity);
    const vertex = new THREE.Vector3();
    for (let i = 0; i < position.count; i++) {
      vertex.set(position.getX(i), position.getY(i), position.getZ(i)).applyMatrix4(mesh.matrixWorld);
      min.min(vertex);
      max.max(vertex);
    }
    // i16 量化误差远小于 1e-3 方块
    expect(min.toArray()).toEqual([
      expect.closeTo(0, 3), expect.closeTo(0, 3), expect.closeTo(0, 3),
    ]);
    expect(max.toArray()).toEqual([
      expect.closeTo(1, 3), expect.closeTo(0, 3), expect.closeTo(1, 3),
    ]);
  });

  it("索引指向的三角形与后端烘焙一致（两个三角，逆时针）", async () => {
    const scene = await parseFixture();
    const mesh = scene.getObjectByProperty("type", "Mesh") as THREE.Mesh;
    const index = mesh.geometry.getIndex()!;
    expect(Array.from({ length: index.count }, (_, i) => index.getX(i))).toEqual([0, 1, 2, 0, 2, 3]);
  });
});
