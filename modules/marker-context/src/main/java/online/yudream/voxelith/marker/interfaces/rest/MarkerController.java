package online.yudream.voxelith.marker.interfaces.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.marker.application.LoadMarkerSetsUseCase;
import online.yudream.voxelith.marker.application.MarkerCodecPort;
import online.yudream.voxelith.marker.application.SaveMarkerSetsUseCase;
import online.yudream.voxelith.marker.domain.MarkerSet;
import online.yudream.voxelith.marker.domain.MarkerRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 标注 REST 接口。
 *
 * <p>标注文件本身也落在发布目录（{@code /maps/{mapId}/markers.json}），前端渲染时直接静态读取；
 * 这里的接口是**读写入口**：GET 给「没有静态文件时也能拿到空列表」的语义，
 * PUT 供标注编辑器整表写回。</p>
 *
 * <p>PUT 的请求体就是 {@code markers.json} 的结构（{@code {"sets": [...]}}），
 * 这样「导出文件 / 贴一段 JSON 回去」与接口调用是同一份格式，不需要两套 DTO。</p>
 */
@RestController
@RequestMapping("/api/maps/{mapId}/markers")
public class MarkerController {

    private final LoadMarkerSetsUseCase load;
    private final SaveMarkerSetsUseCase save;
    private final MarkerCodecPort codec;

    public MarkerController(LoadMarkerSetsUseCase load, SaveMarkerSetsUseCase save, MarkerCodecPort codec) {
        this.load = load;
        this.save = save;
        this.codec = codec;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public String list(@PathVariable String mapId) {
        return codec.write(mapId, load.load(mapId));
    }

    /** @return 写入的标注数；请求体非法时由 {@code MarkerExceptionHandler} 转 400 */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String replace(@PathVariable String mapId, @RequestBody String body) {
        List<MarkerSet> sets = parse(body);
        int written = save.save(mapId, sets);
        return summary(written, sets.size());
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public String clear(@PathVariable String mapId) {
        save.save(mapId, List.of());
        return summary(0, 0);
    }

    private static String summary(int markers, int sets) {
        JsonObject json = new JsonObject();
        json.addProperty("markers", markers);
        json.addProperty("sets", sets);
        return json.toString();
    }

    /** 请求体既可以是 {@code {"sets":[...]}}，也可以直接是数组（手写标注时更省事）。 */
    private List<MarkerSet> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        var parsed = JsonParser.parseString(body);
        if (parsed.isJsonArray()) {
            JsonObject wrapper = new JsonObject();
            wrapper.add("sets", parsed);
            return codec.read(wrapper.toString());
        }
        return codec.read(parsed.toString());
    }

    /** 参数非法 / JSON 语法错误统一 400，避免 500 把用户输入问题伪装成服务端故障。 */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({IllegalArgumentException.class, com.google.gson.JsonParseException.class,
            IllegalStateException.class})
    public String badRequest(RuntimeException e) {
        JsonObject json = new JsonObject();
        json.addProperty("error", String.valueOf(e.getMessage()));
        return json.toString();
    }

    /** 用例 Bean 装配（domain/application 保持无注解，装配集中在 interfaces 层）。 */
    @Configuration
    static class MarkerUseCaseConfiguration {

        @Bean
        LoadMarkerSetsUseCase loadMarkerSetsUseCase(MarkerRepository markerRepository) {
            return new LoadMarkerSetsUseCase(markerRepository);
        }

        @Bean
        SaveMarkerSetsUseCase saveMarkerSetsUseCase(MarkerRepository markerRepository) {
            return new SaveMarkerSetsUseCase(markerRepository);
        }
    }
}
