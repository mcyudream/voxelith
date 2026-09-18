package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.world.domain.world.MapArtFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 地图画几何注入 + 地图贴图供给。
 *
 * <p>Minecraft 的物品展示框是实体、地图内容在 {@code data/map_*.dat}，两者都不在方块数据里，
 * 所以纯方块渲染看不到地图画（那面墙会是一块空墙）。这里把每个展示框补成一个 1×1 面片，
 * 并把地图当成普通贴图喂给图集——因为图集本来就是按贴图打包的，这样无需改瓦片协议。</p>
 *
 * <p>贴图 id 用 {@code voxelith:map/<编号>}，走同一套图集格与 UV 映射。</p>
 */
public final class MapArtInjector implements TexturePixelSource {

    /** 地图贴图 id 的命名空间。 */
    public static final String NAMESPACE = "voxelith";
    /** 地图贴图 id 的路径前缀。 */
    public static final String PATH_PREFIX = "map/";

    private final TexturePixelSource delegate;
    private final Map<String, AtlasTexture> mapTextures = new LinkedHashMap<>();

    public MapArtInjector(TexturePixelSource delegate) {
        this.delegate = delegate;
    }

    /**
     * 注册一张地图，返回其贴图 id。
     *
     * @param argb 128×128 ARGB（行主序，行 0 = 地图北边）
     */
    public String register(int mapId, int[] argb) {
        String id = NAMESPACE + ":" + PATH_PREFIX + mapId;
        mapTextures.put(id, new AtlasTexture(Identifier.parse(id), 128, 128, argb));
        return id;
    }

    /** 已注册的地图数量。 */
    public int registeredCount() {
        return mapTextures.size();
    }

    @Override
    public Optional<AtlasTexture> load(Identifier textureId) {
        if (NAMESPACE.equals(textureId.namespace()) && textureId.path().startsWith(PATH_PREFIX)) {
            return Optional.ofNullable(mapTextures.get(textureId.namespace() + ":" + textureId.path()));
        }
        return delegate.load(textureId);
    }

    /**
     * 把展示框面片注入对应区块的网格。未注册贴图的展示框会被跳过。
     *
     * @return 注入后的新网格表（原表不被修改；区块不存在时新建一条）
     */
    public Map<ChunkPos, BakedChunkMeshData> inject(List<MapArtFrame> frames,
                                                    Map<ChunkPos, BakedChunkMeshData> meshes) {
        Map<ChunkPos, BakedChunkMeshData> out = new LinkedHashMap<>(meshes);
        int injected = 0;
        for (MapArtFrame frame : frames) {
            String textureId = NAMESPACE + ":" + PATH_PREFIX + frame.mapId();
            if (!mapTextures.containsKey(textureId)) {
                continue;
            }
            BakedQuadData quad = quad(frame, textureId);
            if (quad == null) {
                continue;
            }
            ChunkPos chunk = new ChunkPos(Math.floorDiv(frame.x(), 16), Math.floorDiv(frame.z(), 16));
            BakedChunkMeshData mesh = out.get(chunk);
            if (mesh == null) {
                mesh = new BakedChunkMeshData(chunk, List.of(), Map.of(), 0);
            }
            List<BakedQuadData> quads = new ArrayList<>(mesh.quads());
            quads.add(quad);
            out.put(chunk, new BakedChunkMeshData(chunk, List.copyOf(quads), mesh.missing(), mesh.blocksBaked()));
            injected++;
        }
        return out;
    }

    /** 注入了几个面片（诊断用）。 */
    public static int count(List<MapArtFrame> frames) {
        return frames.size();
    }

    /**
     * 单个展示框的面片：贴在该方块朝向面内侧 1/16 处，尺寸 1×1。
     * 顶点顺序与 UV 保证正面（沿 facing 方向看过去）不镜像、图像正立且绕序朝外。
     */
    private static BakedQuadData quad(MapArtFrame frame, String textureId) {
        float[] dir = direction(frame.facing());
        if (dir == null) {
            return null;
        }
        boolean horizontal = dir[1] == 0;
        float[] up = horizontal ? new float[]{0, 1, 0} : new float[]{0, 0, -1};
        float[] right = cross(up, dir);
        float cx = frame.x() + 0.5f;
        float cy = frame.y() + 0.5f;
        float cz = frame.z() + 0.5f;
        float d = 0.5f - 1f / 16f;          // 离方块中心 7/16：贴在墙面内侧
        float h = 0.5f;                      // 半宽（整块 1×1）
        float ox = dir[0] * d;
        float oy = dir[1] * d;
        float oz = dir[2] * d;

        // p0..p3 = 左下 → 右下 → 右上 → 左上（以 facing 方向为正面看过去）
        float[][] corners = new float[4][3];
        float[][] offsets = {
                {-1, -1}, {1, -1}, {1, 1}, {-1, 1},
        };
        for (int i = 0; i < 4; i++) {
            float su = offsets[i][0] * h;
            float sv = offsets[i][1] * h;
            corners[i][0] = cx + ox + right[0] * su + up[0] * sv;
            corners[i][1] = cy + oy + right[1] * su + up[1] * sv;
            corners[i][2] = cz + oz + right[2] * su + up[2] * sv;
        }
        float[] positions = new float[12];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(corners[i], 0, positions, i * 3, 3);
        }
        // v 向下增大（图集行序）：左下 v=16、左上 v=0
        float[] uvs = {0, 16, 16, 16, 16, 0, 0, 0};
        byte[] sky = {(byte) (15 * 17), (byte) (15 * 17), (byte) (15 * 17), (byte) (15 * 17)};
        byte[] zero = {0, 0, 0, 0};
        return new BakedQuadData(positions, uvs, dir.clone(), textureId,
                -1, true, frame.facing(), -1, sky, zero, zero, false);
    }

    private static float[] direction(String facing) {
        return switch (facing) {
            case "down" -> new float[]{0, -1, 0};
            case "up" -> new float[]{0, 1, 0};
            case "north" -> new float[]{0, 0, -1};
            case "south" -> new float[]{0, 0, 1};
            case "west" -> new float[]{-1, 0, 0};
            case "east" -> new float[]{1, 0, 0};
            default -> null;
        };
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }
}
