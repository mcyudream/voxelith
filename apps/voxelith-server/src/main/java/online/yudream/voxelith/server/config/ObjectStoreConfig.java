package online.yudream.voxelith.server.config;

import online.yudream.voxelith.maps.domain.ObjectStore;
import online.yudream.voxelith.maps.infrastructure.store.FileObjectStore;
import online.yudream.voxelith.maps.infrastructure.store.S3ObjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.file.Path;

/**
 * 发布对象存储组合根：默认 FILE（publish-dir）；
 * {@code yudream.voxelith.storage=s3} 时切到 S3 兼容端点（MinIO / R2 / AWS）。
 */
@Configuration
public class ObjectStoreConfig {

    @Bean
    public ObjectStore objectStore(
            @Value("${yudream.voxelith.storage.type:file}") String storage,
            @Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir,
            @Value("${yudream.voxelith.s3.endpoint:}") String endpoint,
            @Value("${yudream.voxelith.s3.bucket:voxelith}") String bucket,
            @Value("${yudream.voxelith.s3.region:us-east-1}") String region,
            @Value("${yudream.voxelith.s3.access-key:}") String accessKey,
            @Value("${yudream.voxelith.s3.secret-key:}") String secretKey) {
        if ("s3".equalsIgnoreCase(storage)) {
            if (endpoint.isBlank() || accessKey.isBlank()) {
                throw new IllegalStateException(
                        "storage=s3 需要 yudream.voxelith.s3.endpoint / access-key / secret-key");
            }
            return new S3ObjectStore(URI.create(endpoint), bucket, region, accessKey, secretKey);
        }
        return new FileObjectStore(Path.of(publishDir));
    }
}
