# ADR 0003：发布对象存储 —— FILE 默认 + S3 兼容 SPI（无 AWS SDK）

- 状态：已接受（2026-09-10，Phase 6.4）
- 上下文：计划 Phase 6 要求「S3 存储 SPI」；现有发布布局是 `{publish-dir}/{mapId}/manifest.json|atlas.png|tiles/**`，静态资源经 Spring 直接挂文件系统。

## 决策

1. **端口在 map-context domain**：`ObjectStore`（put/get/exists/delete/list），键为相对发布根路径，与 URL `/maps/{key}` 对齐。存储是地图域的布局问题，不放到 tile-context。
2. **FILE 实现**：`FileObjectStore`，`{root}/{key}` 与现有 publish-dir 1:1；拒绝 `../` 越界。
3. **S3 实现不引 AWS SDK**：`S3ObjectStore` 用 `HttpURLConnection` + 手写 AWS SigV4（`S3Signer` 纯函数可单测）。path-style 寻址 `/{bucket}/{key}`，兼容 MinIO / R2 / AWS。ListObjectsV2 用最小 XML 抽 `<Key>`。
4. **组合根**：`apps/voxelith-server` `ObjectStoreConfig`，`yudream.voxelith.storage.type=file|s3`。tile 产物仍先落本地工作目录（jshell 管线），S3 作为发布端点预留；不把 ObjectStore 注入 TileArtifactSink（那会让 tile.infrastructure 依赖 maps.domain，ArchUnit 禁止）。
5. **密钥**：access-key / secret-key 走配置/环境变量，不入库。

## 未纳入本期

- 把 FileManifestPublisher / 静态资源 handler 切到 ObjectStore（现网 FILE 路径零行为变化）。
- 分片上传、预签名 URL、CDN 回源。
