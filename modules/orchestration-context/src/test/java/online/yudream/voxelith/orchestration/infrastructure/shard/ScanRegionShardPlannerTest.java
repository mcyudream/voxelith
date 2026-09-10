package online.yudream.voxelith.orchestration.infrastructure.shard;

import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.ShardedStageExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanRegionShardPlannerTest {

    @TempDir
    Path runDir;

    @Test
    void listsRegionsInNumericOrderIncludingNegatives() throws Exception {
        Files.writeString(runDir.resolve("chunks.json"), """
                {
                  "minecraft:overworld": {
                    "1,0": [],
                    "-1,0": [],
                    "0,0": [],
                    "0,-1": []
                  }
                }
                """);
        ScanRegionShardPlanner planner = new ScanRegionShardPlanner(noop(), "minecraft:overworld");
        assertThat(planner.shards(null, runDir)).containsExactly(
                "r.-1.0", "r.0.-1", "r.0.0", "r.1.0");
    }

    @Test
    void missingChunksFileYieldsNoShards() throws Exception {
        ScanRegionShardPlanner planner = new ScanRegionShardPlanner(noop());
        assertThat(planner.shards(null, runDir)).isEmpty();
    }

    private static ShardedStageExecutor noop() {
        return new ShardedStageExecutor() {
            @Override
            public List<String> shards(PipelineStage stage, Path dir) {
                return List.of();
            }

            @Override
            public List<String> executeShard(PipelineStage stage, String shard, Path dir) {
                return List.of();
            }
        };
    }
}
