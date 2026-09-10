package online.yudream.voxelith.maps.interfaces.rest;

import online.yudream.voxelith.maps.application.ListMapsUseCase;
import online.yudream.voxelith.maps.application.MapSummary;
import online.yudream.voxelith.maps.domain.MapRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 地图查询接口。Phase 0 仅提供列表（hello-manifest 联通验证）。
 */
@RestController
@RequestMapping("/api/maps")
public class MapController {

    private final ListMapsUseCase listMapsUseCase;

    public MapController(ListMapsUseCase listMapsUseCase) {
        this.listMapsUseCase = listMapsUseCase;
    }

    @GetMapping
    public List<MapSummary> list() {
        return listMapsUseCase.execute();
    }

    /**
     * 用例的 Bean 装配（保持 domain/application 无注解，装配集中在 interfaces 层）。
     */
    @Configuration
    static class MapsUseCaseConfiguration {
        @Bean
        ListMapsUseCase listMapsUseCase(MapRepository mapRepository) {
            return new ListMapsUseCase(mapRepository);
        }
    }
}
