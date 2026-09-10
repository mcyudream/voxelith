# ADR 0004：瓦片几何压缩 —— 先量化、meshopt 熵编码预留

- 状态：已接受（2026-09-10，Phase 6.5）
- 上下文：计划要求「meshopt+quantize 几何压缩」；meshoptimizer 无成熟纯 Java 实现，JNI/CLI 引入构建期复杂度。three.js GLTFLoader 已原生支持 `KHR_mesh_quantization`，不支持未配套 decoder 的 `EXT_meshopt_compression`。

## 决策

1. **默认仍输出未压缩 float32 glb**，现网瓦片与 golden 测试零行为变化。
2. **可选 `EncodeOptions.quantized()`**：POSITION i16 + NORMAL i8 + TEXCOORD_0 u16，全部 normalized；node.scale 还原 POSITION；声明 `KHR_mesh_quantization`。COLOR_0/`_LIGHT`/indices/PNG 不量化。
3. **不在本期接 meshopt 熵编码**（`EXT_meshopt_compression`）。需要 meshoptimizer 编码器（JNI 或构建期 gltf-transform CLI）+ 前端 MeshoptDecoder；扩展位已在协议文档预留。
4. **入口**：`TileCommand.encode` / `VertexColorTileExporter` 第三构造参；`GlbTileEncoder.encode(..., EncodeOptions)`。jshell 全量管线暂不改默认。

## 后果

量化误差：hires 瓦片边长 32，i16 精度约 `32/32767 ≈ 1mm`，远小于方块像素。LOD 瓦片随覆盖增大，误差按 node.scale 同比放大，远景可接受。
