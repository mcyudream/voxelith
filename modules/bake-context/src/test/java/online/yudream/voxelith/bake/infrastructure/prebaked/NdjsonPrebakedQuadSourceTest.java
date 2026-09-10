package online.yudream.voxelith.bake.infrastructure.prebaked;

import online.yudream.voxelith.bake.domain.geometry.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NdjsonPrebakedQuadSourceTest {

    @TempDir
    Path dir;

    @Test
    void loadsAndIndexesByCanonicalStateKey() throws IOException {
        Path file = writeSample();

        NdjsonPrebakedQuadSource source = NdjsonPrebakedQuadSource.load(file);

        // 查询侧属性表乱序，须与导出侧定义顺序状态串命中同一份 quad
        Optional<List<Quad>> hit = source.quads("mymod:machine", Map.of("on", "true", "facing", "north"));
        assertThat(hit).isPresent();
        assertThat(hit.get()).hasSize(2);

        Quad culled = hit.get().get(0);
        assertThat(culled.texture()).isEqualTo("mymod:block/machine_top");
        assertThat(culled.cullface()).isEqualTo("up");
        assertThat(culled.face()).isEqualTo("up");
        assertThat(culled.tintIndex()).isEqualTo(-1);
        assertThat(culled.shade()).isTrue();
        assertThat(culled.normal()).containsExactly(0f, 1f, 0f);
        assertThat(culled.positions()).hasSize(12);
        assertThat(culled.positions()[1]).isEqualTo(16f);
        assertThat(culled.uvs()).hasSize(8);

        Quad uncullable = hit.get().get(1);
        assertThat(uncullable.cullface()).isNull();
        assertThat(uncullable.normal()).containsExactly(0f, 0f, 1f);

        // 无属性状态用空串键
        assertThat(source.quads("mymod:lamp", Map.of())).isPresent();
        // 未知方块 / 未采集状态 → 空，调用方走静态降级
        assertThat(source.quads("mymod:unknown", Map.of())).isEmpty();
        assertThat(source.quads("mymod:machine", Map.of("facing", "south", "on", "true"))).isEmpty();
    }

    @Test
    void rejectsForeignFormat() throws IOException {
        Path file = dir.resolve("bad.json.gz");
        try (Writer w = gzipWriter(file)) {
            w.write("{\"format\":\"other/9\"}\n");
        }
        assertThatThrownBy(() -> NdjsonPrebakedQuadSource.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式不符");
    }

    private Path writeSample() throws IOException {
        Path file = dir.resolve("models.json.gz");
        try (Writer w = gzipWriter(file)) {
            w.write("{\"format\":\"voxelith-models/1\",\"sprites\":3}\n");
            w.write("{\"block\":\"mymod:machine\",\"states\":[{\"state\":\"facing=north,on=true\","
                    + "\"model\":\"mymod:block/machine#facing=north,on=true\",\"quads\":["
                    + "{\"cull\":\"up\",\"face\":\"up\",\"tint\":-1,\"shade\":true,"
                    + "\"tex\":\"mymod:block/machine_top\","
                    + "\"pos\":[0,16,0,16,16,0,16,16,16,0,16,16],\"uv\":[0,0,16,0,16,16,0,16]},"
                    + "{\"face\":\"south\",\"tint\":0,\"shade\":false,"
                    + "\"tex\":\"mymod:block/machine_front\","
                    + "\"pos\":[0,0,16,16,0,16,16,16,16,0,16,16],\"uv\":[0,0,16,0,16,16,0,16]}"
                    + "]}]}\n");
            w.write("{\"block\":\"mymod:lamp\",\"states\":[{\"state\":\"\","
                    + "\"model\":\"mymod:block/lamp\",\"quads\":[]}]}\n");
        }
        return file;
    }

    private static Writer gzipWriter(Path file) throws IOException {
        return new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)),
                StandardCharsets.UTF_8);
    }
}
