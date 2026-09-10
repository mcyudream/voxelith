package online.yudream.voxelith.runtime.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSpecTest {

    @TempDir
    Path workDir;

    @Test
    void validatesMandatoryFields() {
        assertThatThrownBy(() -> new RuntimeSpec(" ", LoaderKind.FABRIC, "0.16.9", List.of(), workDir))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeSpec("1.20.1", null, "0.16.9", List.of(), workDir))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeSpec("1.20.1", LoaderKind.FABRIC, null, List.of(), workDir))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.9", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAndDefensiveCopies() {
        RuntimeSpec spec = new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.9", null, workDir);
        assertThat(spec.modJars()).isEmpty();
        assertThatThrownBy(() -> spec.modJars().add(workDir))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
