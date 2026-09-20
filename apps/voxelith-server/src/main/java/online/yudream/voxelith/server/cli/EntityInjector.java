package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.world.domain.world.PlacedEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实体几何注入：把盔甲架这类**实体**补成方块几何，让它们在地图里出现。
 *
 * <p>为什么这么做：实体不在方块数据里，纯方块渲染看不到它们（护甲架、画、掉落物都属于此类）。
 * 这里不去还原原版实体模型/贴图（那需要实体贴图，不在方块图集里），而是用一个**简化的盒体模型**
 * 按原版比例摆出来：能一眼认出「这里有个盔甲架、朝哪边、是不是小型」。规格与取舍：</p>
 *
 * <ul>
 *   <li>高度 32 像素（= 2 方块，与原版盔甲架一致）；底座 12×1×12、双腿 2×11×2、
 *       躯干 4×12×2、手臂 2×12×2（仅 {@code ShowArms} 时画）、头 8×8×8；</li>
 *   <li>{@code Small} 整体缩放 0.5、{@code NoBasePlate} 去掉底座、{@code Rotation[0]} 决定朝向；</li>
 *   <li>贴图用**内置**的木纹（{@code voxelith:entity/armor_stand}），不依赖资源包里任何贴图名——
 *       这样任何版本、任何资源包都能画出来（版本无关）；</li>
 *   <li>不渲染身上的装备（盔甲/手持物）与 {@code Pose}，两者都需要原版实体模型；</li>
 *   <li>光照按「满天空光」写入，与地图画面片一致（实体没有烘焙光照数据）。</li>
 * </ul>
 */
public final class EntityInjector implements TexturePixelSource {

    /** 盔甲架贴图 id（内置，不查资源包）。 */
    public static final String ARMOR_STAND_TEXTURE = "voxelith:entity/armor_stand";

    /** 一个方块 = 16 像素（原版实体模型的单位）。 */
    private static final float PIXEL = 1f / 16f;

    private final TexturePixelSource delegate;
    private final Map<String, AtlasTexture> own = new LinkedHashMap<>();

    public EntityInjector(TexturePixelSource delegate) {
        this.delegate = delegate;
        own.put(ARMOR_STAND_TEXTURE, armorStandTexture());
    }

    @Override
    public Optional<AtlasTexture> load(Identifier textureId) {
        AtlasTexture texture = own.get(textureId.toString());
        return texture != null ? Optional.of(texture) : delegate.load(textureId);
    }

    /**
     * 本注入器供给的贴图 id（内置盔甲架木纹）。
     * 多遍渲染预打包共享图集时必须并进去，否则盔甲架会落品红兜底格。
     */
    public List<String> textureIds() {
        return List.copyOf(own.keySet());
    }

    /**
     * 把实体几何注入对应区块。目前只画盔甲架；其余实体类型直接忽略（白名单在读取侧）。
     *
     * @return 注入后的新网格表（原表不被修改）
     */
    public Map<ChunkPos, BakedChunkMeshData> inject(List<PlacedEntity> entities,
                                                    Map<ChunkPos, BakedChunkMeshData> meshes) {
        Map<ChunkPos, BakedChunkMeshData> out = new LinkedHashMap<>(meshes);
        for (PlacedEntity entity : entities) {
            if (!entity.isArmorStand()) {
                continue;
            }
            List<BakedQuadData> quads = armorStandQuads(entity, ARMOR_STAND_TEXTURE);
            if (quads.isEmpty()) {
                continue;
            }
            ChunkPos chunk = new ChunkPos(
                    Math.floorDiv((int) Math.floor(entity.x()), 16),
                    Math.floorDiv((int) Math.floor(entity.z()), 16));
            BakedChunkMeshData mesh = out.get(chunk);
            if (mesh == null) {
                mesh = new BakedChunkMeshData(chunk, List.of(), Map.of(), 0);
            }
            List<BakedQuadData> merged = new ArrayList<>(mesh.quads());
            merged.addAll(quads);
            out.put(chunk, new BakedChunkMeshData(chunk, List.copyOf(merged), mesh.missing(),
                    mesh.blocksBaked()));
        }
        return out;
    }

    /** 盔甲架的盒体模型（局部坐标，脚底中心为原点，单位为方块）。 */
    static List<BakedQuadData> armorStandQuads(PlacedEntity entity, String textureId) {
        float scale = entity.small() ? 0.5f : 1f;
        List<float[]> boxes = new ArrayList<>();
        if (!entity.noBasePlate()) {
            boxes.add(box(-6, 0, -6, 6, 1, 6));
        }
        // 双腿：各 2×11×2，从底座上方到腰部
        boxes.add(box(-3, 1, -1, -1, 12, 1));
        boxes.add(box(1, 1, -1, 3, 12, 1));
        // 躯干：4×12×2
        boxes.add(box(-2, 12, -1, 2, 24, 1));
        if (entity.showArms()) {
            boxes.add(box(-4, 12, -1, -2, 24, 1));
            boxes.add(box(2, 12, -1, 4, 24, 1));
        }
        // 头：8×8×8
        boxes.add(box(-4, 24, -4, 4, 32, 4));

        List<BakedQuadData> quads = new ArrayList<>();
        for (float[] bounds : boxes) {
            quads.addAll(boxQuads(entity, bounds, scale, textureId));
        }
        return List.copyOf(quads);
    }

    /** 像素坐标（16 像素 = 1 方块）的轴对齐盒体。 */
    private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new float[]{x0 * PIXEL, y0 * PIXEL, z0 * PIXEL, x1 * PIXEL, y1 * PIXEL, z1 * PIXEL};
    }

    /** 一个盒体的 6 个外表面（顶点按「从外面看逆时针」排，保证正面剔除下可见）。 */
    private static List<BakedQuadData> boxQuads(PlacedEntity entity, float[] b, float scale,
                                                String textureId) {
        float x0 = b[0];
        float y0 = b[1];
        float z0 = b[2];
        float x1 = b[3];
        float y1 = b[4];
        float z1 = b[5];
        List<BakedQuadData> quads = new ArrayList<>(6);
        // 每个面：4 个角 + 外法线（顺序经单测校验：绕序叉乘必须等于该法线且指向盒外）
        quads.add(face(entity, scale, textureId, new float[][]{
                {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}}, new float[]{1, 0, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {x0, y0, z0}}, new float[]{-1, 0, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}}, new float[]{0, 1, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}}, new float[]{0, -1, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}}, new float[]{0, 0, 1}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}}, new float[]{0, 0, -1}));
        return quads;
    }

    /** 单个面：按实体位置/朝向/缩放变换到世界坐标，并写入满天空光。 */
    private static BakedQuadData face(PlacedEntity entity, float scale, String textureId,
                                      float[][] corners, float[] normal) {
        float yawRad = (float) Math.toRadians(entity.yaw());
        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);
        float[] positions = new float[12];
        for (int i = 0; i < 4; i++) {
            float lx = corners[i][0] * scale;
            float ly = corners[i][1] * scale;
            float lz = corners[i][2] * scale;
            // 绕 Y 轴按实体 yaw 旋转（yaw=0 朝南 +Z，yaw=90 朝西 -X），再平移到实体位置
            positions[i * 3] = (float) entity.x() + lx * cos - lz * sin;
            positions[i * 3 + 1] = (float) entity.y() + ly;
            positions[i * 3 + 2] = (float) entity.z() + lx * sin + lz * cos;
        }
        float[] worldNormal = new float[]{
                normal[0] * cos - normal[2] * sin,
                normal[1],
                normal[0] * sin + normal[2] * cos,
        };
        float[] uvs = {0, 16, 16, 16, 16, 0, 0, 0};
        // skyLight 的约定是**原始等级 0..15**（TileMeshAssembler 再 ×17 打包成 ubyte），
        // 写 255 会被二次相乘溢出成 239；实体没有烘焙光照，给满天空光即可。
        byte[] sky = {15, 15, 15, 15};
        byte[] zero = {0, 0, 0, 0};
        return new BakedQuadData(positions, uvs, worldNormal, textureId,
                -1, true, BakedQuadData.NON_TERRAIN_FACE, -1, sky, zero, zero, false);
    }

    /**
     * 内置木纹贴图（16×16）：盔甲架是木制的，深浅两色竖纹 + 深色描边，
     * 不依赖任何资源包贴图名，所以任何版本都能画。
     */
    private static AtlasTexture armorStandTexture() {
        int light = 0xFFC08A4E;
        int dark = 0xFF9C6B3F;
        int edge = 0xFF6E4A2A;
        int[] argb = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean border = x == 0 || y == 0 || x == 15 || y == 15;
                argb[y * 16 + x] = border ? edge : ((x % 4 == 0) ? dark : light);
            }
        }
        return new AtlasTexture(Identifier.parse(ARMOR_STAND_TEXTURE), 16, 16, argb);
    }
}
