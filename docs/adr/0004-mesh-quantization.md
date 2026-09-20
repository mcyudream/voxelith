# ADR 0004：瓦片几何压缩 —— 量化 + meshopt 熵编码（纯 Java 实现）

- 状态：已接受（2026-09-10，Phase 6.5）；**2026-09-20 修订**（Phase 7：meshopt 落地）
- 上下文：计划要求「meshopt+quantize 几何压缩」。meshoptimizer 官方只有 C++ 实现，
  JNI/CLI 会把构建期复杂度引进来；但 `EXT_meshopt_compression` 的**线格式是公开且稳定的**
  （`vertexcodec.cpp` / `indexcodec.cpp`），而 three.js GLTFLoader 自带 WASM 解码器。

## 决策

1. **量化（Phase 6.5，保留）**：`EncodeOptions.quantized()` → POSITION i16 + NORMAL i8 +
   TEXCOORD_0 u16，全部 normalized；`node.scale` 还原 POSITION；声明 `KHR_mesh_quantization`。
   COLOR_0/`_LIGHT`/indices/PNG 不量化。
2. **熵编码（Phase 7，新增）**：`EncodeOptions.meshopt()` → 位置/法线/UV/索引改写成
   `EXT_meshopt_compression` 的 bufferView；编码器是**仓库内纯 Java 移植**
   （`tile-context/infrastructure/meshopt`），不引入 JNI、不引入构建期 CLI。
   - 顶点流用 **version 0**：three.js 内置的 `meshopt_decoder.module.js`（meshoptimizer 0.22 构建）
     只认 v0，收到 `0xa1` 直接返回 -1。v1 编码能力保留（`MeshoptVertexCodec.encode(..., 1)`），
     需要 meshoptimizer ≥ 0.23 的解码器。
   - 索引流用 **version 1**（边 FIFO + 顶点 FIFO 预测 + codeaux 表）。
   - 解码器同样移植进仓库：单测往返校验 + 瓦片自检 + 将来做服务端校验都用得上。
3. **落盘形态与 gltfpack 一致**：compressed-only 的 glb 需要两条 buffer —— buffer 0 = BIN
   （真实压缩数据），buffer 1 = 无 URI 的**占位回退缓冲**
   （`extensions.EXT_meshopt_compression.fallback = true`，byteLength = 解压后总字节数）。
   被压缩的 bufferView 按「解压后的布局」引用 buffer 1，扩展对象再指向 buffer 0 的真实区间。
   COLOR_0/`_LIGHT` 元素宽 3 字节（不满足「byteStride 被 4 整除」）保持原样不压。
4. **入口**：`TileCommand.encode` / `VertexColorTileExporter` / `GlbTileEncoder.encode(..., EncodeOptions)`；
   全量管线（`renderMap`）与网页渲染默认**开启** meshopt，`--no-meshopt` / `render.meshopt=false` 关闭。
   前端 `GlbTileLoader` 构造时 `setMeshoptDecoder(MeshoptDecoder)`——不挂解码器时 GLTFLoader
   会因 `extensionsRequired` 直接抛错。

## 实测（64×64 网格瓦片，4225 顶点 / 24576 索引）

| 编码 | BIN 字节数 | 相对未压缩 |
|---|---|---|
| 未压缩 float32 | 258 856 | 100% |
| 量化（i16/i8/u16） | 191 256 | 74% |
| 量化 + meshopt 熵编码 | 50 228 | **19%** |

索引流单独看更夸张：规则网格约 **1 字节/三角**（未压缩是 12 字节/三角）。
量化那一步省得有限，是因为索引与 COLOR_0/`_LIGHT` 不随量化变化；真正的大头是熵编码。

## 后果

- 量化误差：hires 瓦片边长 32，i16 精度约 `32/32767 ≈ 1mm`，远小于方块像素；
  LOD 瓦片随覆盖增大，误差按 node.scale 同比放大，远景可接受。
- 兼容性代价：压缩瓦片要求前端具备 meshopt 解码器。老瓦片（未压缩）与新瓦片可以混在一张图里，
  前端逐 glb 自适应，所以增量发布不需要整图重渲。
- 线格式正确性由两处测试守住：后端 `MeshoptCodecTest`（往返 + 压缩率）与前端
  `MeshoptGolden.test.ts`（金标准位流 → three.js 官方 WASM 解码器逐字节还原）。
