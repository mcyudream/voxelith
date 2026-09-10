package online.yudream.voxelith.resource.domain.registry;

import java.util.List;

/**
 * 解析覆盖率报告：未解析方块/缺失模型/缺失贴图，供链路确认门人工核查。
 */
public record ResolveReport(
        int blocksFound,
        int blocksResolved,
        List<String> unresolvedBlocks,
        int modelsFound,
        int modelsResolved,
        List<String> missingModels,
        List<String> missingTextures) {

    public double blockCoverage() {
        return blocksFound == 0 ? 1.0 : (double) blocksResolved / blocksFound;
    }

    public double modelCoverage() {
        return modelsFound == 0 ? 1.0 : (double) modelsResolved / modelsFound;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int blocksFound;
        private int blocksResolved;
        private final java.util.SortedSet<String> unresolvedBlocks = new java.util.TreeSet<>();
        private int modelsFound;
        private int modelsResolved;
        private final java.util.SortedSet<String> missingModels = new java.util.TreeSet<>();
        private final java.util.SortedSet<String> missingTextures = new java.util.TreeSet<>();

        public Builder blocksFound(int n) {
            this.blocksFound = n;
            return this;
        }

        public Builder blockResolved() {
            this.blocksResolved++;
            return this;
        }

        public Builder blockUnresolved(String block, String reason) {
            this.unresolvedBlocks.add(block + " (" + reason + ")");
            return this;
        }

        public Builder modelsFound(int n) {
            this.modelsFound = n;
            return this;
        }

        public Builder modelResolved() {
            this.modelsResolved++;
            return this;
        }

        public Builder modelMissing(String model) {
            this.missingModels.add(model);
            return this;
        }

        public Builder textureMissing(String texture) {
            this.missingTextures.add(texture);
            return this;
        }

        public ResolveReport build() {
            return new ResolveReport(
                    blocksFound, blocksResolved, List.copyOf(unresolvedBlocks),
                    modelsFound, modelsResolved, List.copyOf(missingModels),
                    List.copyOf(missingTextures));
        }
    }
}
