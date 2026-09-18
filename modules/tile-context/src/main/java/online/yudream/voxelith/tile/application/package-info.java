/**
 * tile-context —— 瓦片域：瓦片网格划分、glb 编码、图集打包、清单生成
 *
 * <p>应用服务层：用例编排、事务边界、出入参 DTO，以及提供给其他限界上下文的出站端口
 * （{@link online.yudream.voxelith.tile.application.TextureColorSampler}、
 * {@link online.yudream.voxelith.tile.application.VertexColorTileExporter}、
 * {@link online.yudream.voxelith.tile.application.ImageCodec} 等）；只依赖 domain。
 * 跨上下文只允许经由此层，故端口放这里而非 domain（ArchUnit 守护）。</p>
 */
package online.yudream.voxelith.tile.application;
