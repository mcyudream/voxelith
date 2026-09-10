package online.yudream.voxelith.maps.application;

import online.yudream.voxelith.maps.domain.GameMap;

import java.time.Instant;

/**
 * 地图摘要 DTO（interfaces 层与前端协议对齐）。
 */
public record MapSummary(
        String id,
        String name,
        String dimension,
        String worldVersion,
        String state,
        Instant updatedAt
) {
    public static MapSummary of(GameMap map) {
        return new MapSummary(
                map.id(),
                map.name(),
                map.dimension(),
                map.worldVersion() == null ? "unknown" : map.worldVersion().name(),
                map.state().name(),
                map.updatedAt()
        );
    }
}
