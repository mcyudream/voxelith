package online.yudream.voxelith.marker;

import online.yudream.voxelith.marker.application.LoadMarkerSetsUseCase;
import online.yudream.voxelith.marker.application.SaveMarkerSetsUseCase;
import online.yudream.voxelith.marker.domain.Marker;
import online.yudream.voxelith.marker.domain.MarkerSet;
import online.yudream.voxelith.marker.domain.MarkerStyle;
import online.yudream.voxelith.marker.infrastructure.FileMarkerRepository;
import online.yudream.voxelith.marker.infrastructure.JsonMarkerCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标注读写：JSON 协议往返、发布目录落盘、用例校验。
 *
 * <p>JSON 字段名必须与前端 {@code markerSetSchema} 一致——这里直接断言字面字段名，
 * 改协议时前后端两侧同时炸。</p>
 */
class MarkerJsonRoundTripTest {

    private static MarkerSet sampleSet() {
        return new MarkerSet("landmarks", "地标", true, false, 10, List.of(
                new Marker.Poi("library", "图书馆", new MarkerStyle("#e74c3c", null, null, 0.9, "📚", null),
                        0, 512, new Marker.Vec3(120.5, 68, -30.25), "<b>图书馆</b>"),
                new Marker.Line("patrol", "巡逻线", MarkerStyle.DEFAULT, 0, Double.MAX_VALUE,
                        List.of(new Marker.Vec3(0, 64, 0), new Marker.Vec3(16, 64, 8),
                                new Marker.Vec3(16, 65, 24))),
                new Marker.Shape("campus", "校园", new MarkerStyle("#2f6fd0", "#ffffff", null, 0.4, null, false),
                        0, 4096,
                        List.of(new Marker.Vec2(0, 0), new Marker.Vec2(32, 0),
                                new Marker.Vec2(32, 32), new Marker.Vec2(0, 32)),
                        List.of(List.of(new Marker.Vec2(8, 8), new Marker.Vec2(16, 8),
                                new Marker.Vec2(16, 16), new Marker.Vec2(8, 16))),
                        64),
                new Marker.Extrude("tower", "塔楼", MarkerStyle.DEFAULT, 0, Double.MAX_VALUE,
                        List.of(new Marker.Vec2(0, 0), new Marker.Vec2(8, 0), new Marker.Vec2(8, 8)),
                        List.of(), 64, 96),
                new Marker.Box("dorm", "宿舍", MarkerStyle.DEFAULT, 50, Double.MAX_VALUE,
                        new Marker.Vec3(0, 64, 0), new Marker.Vec3(12, 84, 30))));
    }

    @Test
    @DisplayName("JSON 往返：五种标注类型与样式字段全保真，字段名与前端 schema 对齐")
    void jsonRoundTrip() {
        JsonMarkerCodec codec = new JsonMarkerCodec();
        String json = codec.write("swust", List.of(sampleSet()));

        // 前端 zod schema 认得的字段名（改一处就要两边同时改）
        assertThat(json)
                .contains("\"formatVersion\": 1")
                .contains("\"mapId\": \"swust\"")
                .contains("\"defaultHidden\": false")
                .contains("\"sorting\": 10")
                .contains("\"type\": \"poi\"")
                .contains("\"minDistance\": 0.0")
                .contains("\"fillColor\": \"#e74c3c\"")
                .contains("\"depthTest\": false")
                .contains("\"detailHtml\"")
                .contains("\"shapeY\": 64.0")
                .contains("\"shapeMinY\": 64.0");

        List<MarkerSet> parsed = codec.read(json);
        assertThat(parsed).hasSize(1);
        MarkerSet set = parsed.get(0);
        assertThat(set.id()).isEqualTo("landmarks");
        assertThat(set.sorting()).isEqualTo(10);
        assertThat(set.markers()).hasSize(5);
        assertThat(set.markers().get(0)).isEqualTo(sampleSet().markers().get(0));
        assertThat(set.markers().get(2)).isEqualTo(sampleSet().markers().get(2));
        assertThat(set.markers().get(4)).isEqualTo(sampleSet().markers().get(4));
    }

    @Test
    @DisplayName("落盘与读取：发布目录里的 markers.json")
    void repositoryPersistsIntoPublishDir(@TempDir Path publishRoot) throws Exception {
        FileMarkerRepository repository = new FileMarkerRepository(publishRoot);
        assertThat(repository.load("swust")).isEmpty();

        repository.save("swust", List.of(sampleSet()));
        Path file = publishRoot.resolve("swust").resolve(FileMarkerRepository.FILE_NAME);
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"library\"");
        // 静态服务直接读同一份文件：/{mapId}/markers.json 与 /maps/{mapId}/markers.json 是同一路径
        assertThat(repository.load("swust")).isPresent();
        assertThat(repository.load("swust").orElseThrow()).hasSize(1);
    }

    @Test
    @DisplayName("用例：按 sorting 降序返回，整表替换，非法输入报错")
    void useCases(@TempDir Path publishRoot) {
        FileMarkerRepository repository = new FileMarkerRepository(publishRoot);
        LoadMarkerSetsUseCase load = new LoadMarkerSetsUseCase(repository);
        SaveMarkerSetsUseCase save = new SaveMarkerSetsUseCase(repository);

        save.save("m", List.of(
                new MarkerSet("low", "低", true, false, 1,
                        List.of(new Marker.Poi("a", "A", MarkerStyle.DEFAULT, 0, Double.MAX_VALUE,
                                new Marker.Vec3(0, 0, 0), null))),
                new MarkerSet("high", "高", true, false, 5,
                        List.of(new Marker.Poi("b", "B", MarkerStyle.DEFAULT, 0, Double.MAX_VALUE,
                                new Marker.Vec3(1, 0, 1), null)))));

        assertThat(load.load("m")).extracting(MarkerSet::id).containsExactly("high", "low");
        assertThat(load.load("unknown-map")).isEmpty();

        // 整表替换（写空 = 清空）
        save.save("m", List.of());
        assertThat(load.load("m")).isEmpty();

        assertThatThrownBy(() -> save.save("m", List.of(
                MarkerSet.of("dup", "A", List.of()),
                MarkerSet.of("dup", "B", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标注集 id 重复");
        assertThatThrownBy(() -> save.save("../escape", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法字符");
    }

    @Test
    @DisplayName("domain 校验：重复标注 id、非法几何、非法样式都要拦住")
    void domainValidation() {
        assertThatThrownBy(() -> new MarkerSet("s", "s", true, false, 0, List.of(
                new Marker.Poi("same", "A", MarkerStyle.DEFAULT, 0, 1, new Marker.Vec3(0, 0, 0), null),
                new Marker.Poi("same", "B", MarkerStyle.DEFAULT, 0, 1, new Marker.Vec3(0, 0, 0), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> new Marker.Line("l", "l", MarkerStyle.DEFAULT, 0, 1,
                List.of(new Marker.Vec3(0, 0, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 2 个点");
        assertThatThrownBy(() -> new Marker.Shape("s", "s", MarkerStyle.DEFAULT, 0, 1,
                List.of(new Marker.Vec2(0, 0), new Marker.Vec2(1, 1)), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 3 个顶点");
        assertThatThrownBy(() -> new Marker.Box("b", "b", MarkerStyle.DEFAULT, 0, 1,
                new Marker.Vec3(1, 1, 1), new Marker.Vec3(0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不小于");
        assertThatThrownBy(() -> new MarkerStyle("#fff", null, null, 1.5, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opacity");
        assertThatThrownBy(() -> new MarkerSet("s", "s", true, false, 0, List.of(
                new Marker.Poi("p", "p", MarkerStyle.DEFAULT, 10, 5, new Marker.Vec3(0, 0, 0), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minDistance");
    }
}
