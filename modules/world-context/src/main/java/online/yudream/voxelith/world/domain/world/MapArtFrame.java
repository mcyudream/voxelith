package online.yudream.voxelith.world.domain.world;

/**
 * 挂在墙上的地图画（物品展示框 + 已填充地图）。
 *
 * <p>Minecraft 里物品展示框是<b>实体</b>而不是方块，地图内容存在存档的 {@code data/map_N.dat}，
 * 因此纯方块渲染看不到它们；需要单独读取并作为贴图面片补进瓦片几何。</p>
 *
 * @param x      展示框所在方块 x
 * @param y      展示框所在方块 y
 * @param z      展示框所在方块 z
 * @param facing 朝向（down/up/north/south/west/east）：决定地图画面朝哪一侧、离墙面偏 1/16
 * @param mapId  地图编号（对应 data/map_{mapId}.dat）
 */
public record MapArtFrame(int x, int y, int z, String facing, int mapId) {

    public MapArtFrame {
        if (facing == null || facing.isBlank()) {
            throw new IllegalArgumentException("facing 不能为空");
        }
        if (mapId < 0) {
            throw new IllegalArgumentException("mapId 不能为负: " + mapId);
        }
    }
}
