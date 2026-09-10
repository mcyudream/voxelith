package online.yudream.voxelith.maps.domain;

import online.yudream.voxelith.sharedkernel.vo.McVersion;

import java.time.Instant;

/**
 * 地图聚合根：一张地图 = 一个世界存档的一个维度 + 一套渲染配置。
 */
public class GameMap {

    private final String id;
    private String name;
    private String worldPath;
    private String dimension;
    private McVersion worldVersion;
    private MapRenderSettings settings;
    private MapState state;
    private final Instant createdAt;
    private Instant updatedAt;

    public GameMap(String id, String name, String worldPath, String dimension,
                   McVersion worldVersion, MapRenderSettings settings) {
        this.id = id;
        this.name = name;
        this.worldPath = worldPath;
        this.dimension = dimension;
        this.worldVersion = worldVersion;
        this.settings = settings;
        this.state = MapState.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markRendering() {
        this.state = MapState.RENDERING;
        this.updatedAt = Instant.now();
    }

    public void markReady() {
        this.state = MapState.READY;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.state = MapState.FAILED;
        this.updatedAt = Instant.now();
    }

    public String id() { return id; }
    public String name() { return name; }
    public String worldPath() { return worldPath; }
    public String dimension() { return dimension; }
    public McVersion worldVersion() { return worldVersion; }
    public MapRenderSettings settings() { return settings; }
    public MapState state() { return state; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public enum MapState {
        /** 已创建，尚未渲染 */
        CREATED,
        /** 渲染管线执行中 */
        RENDERING,
        /** 渲染完成，可对外服务 */
        READY,
        /** 渲染失败 */
        FAILED
    }
}
