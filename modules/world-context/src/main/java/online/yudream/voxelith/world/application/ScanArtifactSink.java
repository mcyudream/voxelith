package online.yudream.voxelith.world.application;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.WorldReader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * scan 链路产物落盘端口。实现位于 infrastructure 层。
 */
public interface ScanArtifactSink {

    void writeScanArtifacts(Path outputDir,
                            String versionName,
                            int dataVersion,
                            Map<String, Map<RegionPos, List<WorldReader.ChunkRef>>> dimensions);
}
