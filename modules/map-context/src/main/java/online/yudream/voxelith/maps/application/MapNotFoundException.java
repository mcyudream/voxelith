package online.yudream.voxelith.maps.application;

import online.yudream.voxelith.sharedkernel.exception.DomainException;

public class MapNotFoundException extends DomainException {

    public MapNotFoundException(String id) {
        super("MAP_NOT_FOUND", "地图不存在: " + id);
    }
}
